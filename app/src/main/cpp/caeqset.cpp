// 共有メモリのパラメータ枠に書く道具。**`.so` に実際のバンドを掛けさせる唯一の書き手。**
// アプリが APK の中の自分自身を `su` 経由で実行して呼ぶ (`nativeLibraryDir/libcaeqset.so`)。
//
// seqlock の書き込みは dsp/ca_eq_params.h の paramsBeginWrite / paramsEndWrite をそのまま使う。
// **`.so` の読み手と同じ定義を共有している**ので、ここで書けたものは必ず読める。
//
//   caeqset --auto-slot --preamp -3 --band 100:1.0:6 --band 4000:2:-4 --band 10000:0.7:3:hs
//   caeqset --auto-slot --off     # enabled を落とす
//   caeqset --slot 0 ...          # 枠を手で指定する (実機で調べるとき用)
//   caeqset --show                # いまの中身と、どの枠が生きているかを出すだけ
//   caeqset --auto-slot --dry-run # 選ぶところまでやって、書かずに終了コードだけ返す
//
//   caeqset --auto-slot --hp --curve out.txt   # 高精度 (最小位相 FIR) を要求し、曲線を送る
//
// **曲線は 401 点の dB を 1 行 1 値で並べたテキスト。**周波数は書かない — グリッドは
// 20 Hz〜20 kHz の対数等間隔で `dsp/ca_eq_curve.h` に定義があり、点数も間隔もそこ 1 箇所。
// ファイルに周波数を書くと定義が 2 箇所になる。
//
// --- 終了コード -------------------------------------------------------------
//
// **`EqParams.kt` が同じ値を literal で持っている。片方だけ変えないこと。**
// 番号はこのコマンドのもので、`module/eq_devices.sh` の表とは別 (0 と 10 だけ意味が揃っている)。
//
//   | 値 | 意味                                                                        |
//   |----|-----------------------------------------------------------------------------|
//   |  0 | 書けた                                                                      |
//   | 10 | 使い方が不正。引数の組み立てを間違えている (通常は出ない)                    |
//   | 11 | 共有メモリが無い / 開けない。モジュールが動いていない                        |
//   | 12 | 共有メモリの版・大きさが合わない。**アプリとモジュールの版ずれ**             |
//   | 13 | 生きた枠が無い。**イヤホンが繋がっていないだけで、失敗ではない**             |
//   | 14 | 生きた枠が複数。イヤホンが 2 台繋がっているので断った                        |
//   | 15 | パラメータが検査に落ちた (`.so` と同じ範囲で先に検査している)                |
//
// 実体は実行ファイルだが、AGP に APK へ載せてもらうため lib*.so を名乗る (caeqstat と同じ)。
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "ca_eq_curve_io.h"
#include "ca_eq_pick.h"
#include "ca_eq_shm.h"
#include "dsp/ca_eq_params.h"

// **引数の検査より前に stdout へ出す印。**「プロセスは動いたが root マネージャに拒まれた」を、
// 終了コードだけでは区別できない (su は拒否のとき自分の終了コードを返す) ので、
// 「走ったこと」の証拠を別に持つ。`eq_devices.sh` の CA_EQ_DEVICES_BEGIN と同じ役。
#define CA_EQ_SET_BEGIN "CA_EQ_SET_BEGIN"

namespace {

enum {
    kExitOk            = 0,
    kExitBadInput      = 10,
    kExitNoShm         = 11,
    kExitShmMismatch   = 12,
    kExitNoLiveSlot    = 13,
    kExitAmbiguousSlot = 14,
    kExitRejected      = 15,
};

void usage() {
    printf("caeqset — 共有メモリのパラメータ枠に書く\n\n");
    printf("  caeqset [--file PATH] (--auto-slot|--slot N) [--off|--on] [--preamp dB]\n");
    printf("          [--band fc:q:gain[:type]] ... [--std|--hp] [--curve PATH] [--dry-run]\n");
    printf("  caeqset [--file PATH] --show\n\n");
    printf("  --auto-slot は生きているイヤホン側の枠を自分で選ぶ (アプリはこちらを使う)\n");
    printf("  type は pk (peaking, 既定) / ls (low shelf) / hs (high shelf)\n");
    printf("  --band を 1 つも渡さなければバンドは空 (プリアンプだけ) になる\n");
    printf("  --std / --hp は処理方式 (標準 = biquad / 高精度 = 最小位相 FIR)。既定は標準\n");
    printf("  --curve は %d 行の dB (1 行 1 値)。周波数は書かない — グリッドは固定\n",
           caeq::kCurvePoints);
    printf("  検査は .so と同じ範囲で先に行う。落ちたら書かずに理由を出す\n");
}

// 解析そのものは ca_eq_curve_io.h (ホストのハーネスが同じコードを掛ける)。ここは理由の表示だけ。
bool readCurve(const char* path, float* out) {
    const caeq::CurveReadResult r = caeq::readCurveFile(path, out);
    switch (r.status) {
    case caeq::CurveRead::kOk:
        return true;
    case caeq::CurveRead::kOpenFailed:
        fprintf(stderr, "曲線を開けない %s: %s\n", path, std::strerror(errno));
        return false;
    case caeq::CurveRead::kWrongCount:
        fprintf(stderr, "曲線の点数が違う: %s は %d 点%s (期待 %d 点)\n", path, r.count,
                r.count > caeq::kCurvePoints ? " 以上" : "", caeq::kCurvePoints);
        return false;
    }
    return false;
}

// 実機での生存確認。/proc/<pid> が無ければそのプロセスは死んでいる。
// kill(pid, 0) でも同じことは分かるが、シグナルを撃たない形のほうが事故が無い。
bool pidAlive(uint64_t pid, void*) {
    if (pid == 0) return false;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%llu", static_cast<unsigned long long>(pid));
    return access(path, F_OK) == 0;
}

// "fc:q:gain[:type]" を 1 バンドに。
bool parseBand(const char* s, ca_eq_band_t* out) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), "%s", s);
    char* save = nullptr;
    const char* fc = strtok_r(buf, ":", &save);
    const char* q = strtok_r(nullptr, ":", &save);
    const char* g = strtok_r(nullptr, ":", &save);
    const char* t = strtok_r(nullptr, ":", &save);
    if (fc == nullptr || q == nullptr || g == nullptr) return false;
    out->fc_hz = static_cast<float>(atof(fc));
    out->q = static_cast<float>(atof(q));
    out->gain_db = static_cast<float>(atof(g));
    out->type = CA_EQ_BAND_PEAKING;
    if (t != nullptr) {
        if (std::strcmp(t, "pk") == 0)      out->type = CA_EQ_BAND_PEAKING;
        else if (std::strcmp(t, "ls") == 0) out->type = CA_EQ_BAND_LOW_SHELF;
        else if (std::strcmp(t, "hs") == 0) out->type = CA_EQ_BAND_HIGH_SHELF;
        else return false;
    }
    return true;
}

const char* typeName(uint32_t t) {
    switch (t) {
    case CA_EQ_BAND_PEAKING:    return "pk";
    case CA_EQ_BAND_LOW_SHELF:  return "ls";
    case CA_EQ_BAND_HIGH_SHELF: return "hs";
    default: return "??";
    }
}

// 枠がどう見えているかを 1 行で。**「使用中」と「生きている」と「イヤホン側」は全部別。**
void describeSlot(const ca_slot_t& s) {
    if (s.in_use != CA_SHM_MAGIC) { printf("  | .so: この枠を読んでいるエフェクトは無い"); return; }
    const bool alive = pidAlive(s.pid, nullptr);
    const bool device = s.session_id == CA_AUDIO_SESSION_DEVICE;
    printf("  | .so: io=%d session=%d rate=%u ch=%u 適用済み gen=%u 却下=%u [%s]",
           s.io_id, s.session_id, s.sample_rate, s.channels, s.param_gen, s.param_rejected,
           !alive  ? "残骸 (pid が死んでいる)"
           : device ? "イヤホン側 (DEVICE)"
                    : "イヤホン以外 (postprocess 等)");
}

void showAll(const ca_shm_t* m) {
    printf("version=%u (期待 %u) slots=%u 枠 %u B (期待 %zu) 曲線 %u 点 (期待 %d)\n",
           m->version, CA_SHM_VERSION, m->slot_count, m->param_slot_size,
           sizeof(ca_eq_slot_t), m->curve_points, caeq::kCurvePoints);
    for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
        const ca_eq_slot_t* q = &m->params[i];
        const ca_slot_t* s = &m->slots[i];
        if (q->generation == 0 && s->in_use != CA_SHM_MAGIC) continue;
        printf("枠 %u: gen=%u flags=0x%x%s bands=%u preamp=%.2f dB 曲線 gen=%u writer_pid=%u",
               i, q->generation, q->flags,
               (q->flags & CA_EQ_FLAG_HIGH_PRECISION) ? " 高精度" : " 標準",
               q->band_count, static_cast<double>(q->preamp_db), q->curve_gen,
               q->writer_pid);
        describeSlot(*s);
        printf("\n");
        for (uint32_t b = 0; b < q->band_count && b < CA_EQ_MAX_BANDS; b++) {
            printf("        %2u: %8.1f Hz  Q %5.2f  %+6.2f dB  %s\n", b,
                   static_cast<double>(q->band[b].fc_hz), static_cast<double>(q->band[b].q),
                   static_cast<double>(q->band[b].gain_db), typeName(q->band[b].type));
        }
    }
    const caeq::SlotPickResult pick = caeq::pickDeviceSlot(m, pidAlive, nullptr);
    printf("\n自動選択: ");
    switch (pick.status) {
    case caeq::SlotPick::kOk:
        printf("枠 %u (統計の枠 %u)\n", pick.param_slot, pick.stats_slot);
        break;
    case caeq::SlotPick::kNone:
        printf("イヤホン側の生きた枠が無い — 繋がっていない (異常ではない)\n");
        break;
    case caeq::SlotPick::kAmbiguous:
        printf("イヤホン側の生きた枠が %u — 2 台繋がっているので選ばない\n", pick.live_count);
        break;
    }
    printf("  残骸 %u / イヤホン以外 %u\n", pick.stale_count, pick.other_count);
}

// 選べなかった理由を stderr に出して、対応する終了コードを返す。
int reportPickFailure(const caeq::SlotPickResult& pick) {
    if (pick.status == caeq::SlotPick::kAmbiguous) {
        fprintf(stderr, "イヤホン側の生きた枠が %u ある。**どの枠がどのイヤホンかは .so から"
                        "分からない**ので、推測せずに断る (--show で内訳が出る)\n",
                pick.live_count);
        return kExitAmbiguousSlot;
    }
    fprintf(stderr, "イヤホン側の生きた枠が無い (残骸 %u / イヤホン以外 %u)。"
                    "イヤホンが繋がっていないだけなら異常ではない\n",
            pick.stale_count, pick.other_count);
    return kExitNoLiveSlot;
}

}  // namespace

int main(int argc, char** argv) {
    // **引数の検査より前に出す。**これが 1 行も出ていなければ「root を拒否された」。
    printf("%s\n", CA_EQ_SET_BEGIN);
    fflush(stdout);

    const char* path = CA_SHM_PATH;
    int slot = -1;
    bool autoSlot = false;
    bool show = false;
    bool dryRun = false;
    bool enabled = true;
    bool highPrecision = false;
    double preamp = 0.0;
    ca_eq_band_t bands[CA_EQ_MAX_BANDS];
    uint32_t band_count = 0;
    float curve[caeq::kCurvePoints] = {};
    bool haveCurve = false;

    for (int i = 1; i < argc; i++) {
        const char* a = argv[i];
        if (std::strcmp(a, "--file") == 0 && i + 1 < argc)        path = argv[++i];
        else if (std::strcmp(a, "--slot") == 0 && i + 1 < argc)   slot = atoi(argv[++i]);
        else if (std::strcmp(a, "--auto-slot") == 0)              autoSlot = true;
        else if (std::strcmp(a, "--preamp") == 0 && i + 1 < argc) preamp = atof(argv[++i]);
        else if (std::strcmp(a, "--off") == 0)                    enabled = false;
        else if (std::strcmp(a, "--on") == 0)                     enabled = true;
        else if (std::strcmp(a, "--std") == 0)                    highPrecision = false;
        else if (std::strcmp(a, "--hp") == 0)                     highPrecision = true;
        else if (std::strcmp(a, "--show") == 0)                   show = true;
        else if (std::strcmp(a, "--dry-run") == 0)                dryRun = true;
        else if (std::strcmp(a, "--curve") == 0 && i + 1 < argc) {
            if (!readCurve(argv[++i], curve)) return kExitBadInput;
            haveCurve = true;
        }
        else if (std::strcmp(a, "--band") == 0 && i + 1 < argc) {
            if (band_count >= CA_EQ_MAX_BANDS) {
                fprintf(stderr, "バンドが多すぎる (上限 %d)\n", CA_EQ_MAX_BANDS);
                return kExitBadInput;
            }
            if (!parseBand(argv[++i], &bands[band_count])) {
                fprintf(stderr, "--band の書式が不正: %s\n", argv[i]);
                return kExitBadInput;
            }
            band_count++;
        } else {
            usage();
            return kExitBadInput;
        }
    }
    if (!show) {
        if (autoSlot == (slot >= 0)) {
            fprintf(stderr, "--auto-slot と --slot はどちらか一方だけ指定する\n");
            usage();
            return kExitBadInput;
        }
        if (slot >= CA_SHM_SLOTS) {
            fprintf(stderr, "--slot は 0..%d\n", CA_SHM_SLOTS - 1);
            return kExitBadInput;
        }
    }

    const int fd = open(path, show ? O_RDONLY : O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", path, std::strerror(errno));
        return kExitNoShm;
    }
    // 短いファイルを map して後ろを触ると SIGBUS。版が古いまま残っていることがある。
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < sizeof(ca_shm_t)) {
        fprintf(stderr, "%s が小さすぎる: %lld バイト (%zu 必要)。"
                        "post-fs-data.sh が古い\n",
                path, static_cast<long long>(st.st_size), sizeof(ca_shm_t));
        close(fd);
        return kExitShmMismatch;
    }
    void* p = mmap(nullptr, sizeof(ca_shm_t), show ? PROT_READ : (PROT_READ | PROT_WRITE),
                   MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { fprintf(stderr, "mmap: %s\n", std::strerror(errno)); return kExitNoShm; }
    ca_shm_t* m = static_cast<ca_shm_t*>(p);

    if (show) { showAll(m); return kExitOk; }

    // 判定は caeq::shmState に一本化してある (ハーネスが表ごと固定している)。
    // **順序が肝で、version は magic が正しいときにしか意味を持たない** —
    // 起動直後のファイルは全部ゼロなので、version だけ見ると毎回「版ずれ」になる。
    switch (caeq::shmState(m)) {
    case caeq::ShmState::kNotInitialised:
        // この領域を初期化する ca_stats_open() は create_effect() からしか呼ばれない。
        // **.so が入っていてもインスタンスが立つまでここに来る。異常ではない。**
        fprintf(stderr, "エフェクトのインスタンスが 1 度も作られていない。"
                        "イヤホンが繋がっていないだけなら異常ではない\n");
        return kExitNoLiveSlot;
    case caeq::ShmState::kForeign:
        fprintf(stderr, "magic=0x%08x — %s は我々のファイルではない\n", m->magic, path);
        return kExitShmMismatch;
    case caeq::ShmState::kVersionMismatch:
        // 版 2 の .so は session_id を書かないので、そのまま読むと「イヤホン側の枠が 1 つも
        // 無い」に見えて、繋がっているのに「繋がっていない」という嘘の理由が出る。
        fprintf(stderr, "共有メモリの版が %u (期待 %u)。アプリとモジュールの版がずれている。"
                        "モジュールを入れ直すこと\n",
                m->version, CA_SHM_VERSION);
        return kExitShmMismatch;
    case caeq::ShmState::kLayoutMismatch:
        // 版の番号は同じなのに並びが違う = 版を上げずに構造体を変えたビルドが混ざっている。
        // そのまま書くと枠の境界がずれて別のインスタンスの設定を上書きする。
        fprintf(stderr, "共有メモリの並びが違う (枠 %u B / 期待 %zu、曲線 %u 点 / 期待 %d、"
                        "統計 %u B / 期待 %zu)。版は同じなので、.so とこのコマンドの"
                        "ビルドが食い違っている\n",
                m->param_slot_size, sizeof(ca_eq_slot_t), m->curve_points, caeq::kCurvePoints,
                m->slot_size, sizeof(ca_slot_t));
        return kExitShmMismatch;
    case caeq::ShmState::kOk:
        break;
    }

    // --slot は手で調べるとき用。**統計の枠とパラメータの枠が同じ添字である前提**に依る
    // (.so の既定の対応。SET_PARAM で移していると外れる)。--auto-slot はその対応を見て決める。
    uint32_t param_slot = 0;
    uint32_t stats_slot = 0;
    if (autoSlot) {
        const caeq::SlotPickResult pick = caeq::pickDeviceSlot(m, pidAlive, nullptr);
        if (pick.status != caeq::SlotPick::kOk) return reportPickFailure(pick);
        param_slot = pick.param_slot;
        stats_slot = pick.stats_slot;
        printf("枠 %u を選んだ (統計の枠 %u / イヤホン以外 %u / 残骸 %u)\n",
               param_slot, stats_slot, pick.other_count, pick.stale_count);
    } else if (slot >= 0 && slot < CA_SHM_SLOTS) {
        param_slot = static_cast<uint32_t>(slot);
        stats_slot = param_slot;
    } else {
        fprintf(stderr, "--slot は 0..%d\n", CA_SHM_SLOTS - 1);
        return kExitBadInput;
    }

    // **書く前に .so と同じ検査を通す。**サンプルレートは統計側から引く — fc の上限だけが
    // fs に依るので、ここを取り違えると「書けたのに黙って却下される」になる。
    const uint32_t rate = m->slots[stats_slot].sample_rate;
    const double fs = rate ? static_cast<double>(rate) : 48000.0;
    if (rate == 0) {
        printf("注意: 枠 %u を読むエフェクトがまだ SET_CONFIG を受けていない。"
               "検査は 48000 Hz として行う\n", stats_slot);
    }
    ca_eq_slot_t staged{};
    staged.band_count = band_count;
    staged.preamp_db = static_cast<float>(preamp);
    for (uint32_t i = 0; i < band_count; i++) staged.band[i] = bands[i];

    // 曲線も .so と同じ検査を先に通す。**落ちる並びを書くと、.so は枠の更新を丸ごと
    // 捨てる** — バンドまで一緒に消えるので、ここで止めるほうが原因が分かる。
    if (haveCurve && !caeq::curveValid(curve)) {
        fprintf(stderr, "曲線が検査に落ちた (各点が有限かつ |dB| <= %.0f であること)。"
                        ".so も同じ理由で枠の更新を丸ごと捨てる\n",
                static_cast<double>(caeq::kCurveMaxAbsDb));
        return kExitRejected;
    }

    caeq::Params check;
    if (!caeq::paramsConvert(staged, &check)) {
        fprintf(stderr, "並びが不正 (type かバンド数)\n");
        return kExitRejected;
    }
    if (!caeq::validate(check, fs)) {
        fprintf(stderr, "検査に落ちた (fs=%.0f Hz)。.so も同じ理由で却下する。\n", fs);
        fprintf(stderr, "  fc は %.0f 〜 %.0f Hz / Q は %.1f 〜 %.0f / gain は ±%.0f dB / "
                        "preamp は %.0f 〜 +%.0f dB\n",
                caeq::kMinFcHz, caeq::kValidFcRatio * fs, caeq::kMinQ, caeq::kMaxQ,
                caeq::kMaxGainDb, caeq::kMinPreampDb, caeq::kMaxPreampDb);
        return kExitRejected;
    }

    if (dryRun) {
        printf("--dry-run: 枠 %u に書けるところまで確かめた (書いていない)\n", param_slot);
        return kExitOk;
    }

    // seqlock で書く。generation は必ず動かす — 動かさないと .so は読みに来ない。
    ca_eq_slot_t* dst = &m->params[param_slot];
    uint32_t gen = dst->generation + 1;
    if (gen == 0) gen = 1;   // 0 は「未割り当て」の意味なので使わない

    // **曲線の版は曲線を渡したときだけ進める。**バンドやプリアンプを触るたびに進めると、
    // ドラッグのたびに FIR の再構築が走る (eq-fir-design.md §2)。
    // 曲線を渡さなければ、前に書いた曲線と版がそのまま残る。
    uint32_t curve_gen = dst->curve_gen;
    if (haveCurve) {
        curve_gen++;
        if (curve_gen == 0) curve_gen = 1;   // 0 は「曲線が載っていない」の意味
    }

    caeq::paramsBeginWrite(dst);
    dst->generation = gen;
    dst->flags = (enabled ? CA_EQ_FLAG_ENABLED : 0u) |
                 (highPrecision ? CA_EQ_FLAG_HIGH_PRECISION : 0u);
    dst->band_count = band_count;
    dst->preamp_db = static_cast<float>(preamp);
    dst->writer_pid = static_cast<uint32_t>(getpid());
    for (uint32_t i = 0; i < CA_EQ_MAX_BANDS; i++) {
        dst->band[i] = (i < band_count) ? bands[i] : ca_eq_band_t{};
    }
    if (haveCurve) {
        for (int i = 0; i < caeq::kCurvePoints; i++) dst->curve_db[i] = curve[i];
    }
    dst->curve_gen = curve_gen;
    caeq::paramsEndWrite(dst);

    printf("枠 %u に書いた: gen=%u %s %s bands=%u preamp=%.2f dB 曲線 gen=%u%s "
           "(fs=%.0f Hz で検査済み)\n",
           param_slot, gen, enabled ? "enabled" : "disabled",
           highPrecision ? "高精度" : "標準", band_count, preamp, curve_gen,
           haveCurve ? " (今回更新)" : "", fs);
    for (uint32_t i = 0; i < band_count; i++) {
        printf("  %2u: %8.1f Hz  Q %5.2f  %+6.2f dB  %s\n", i,
               static_cast<double>(bands[i].fc_hz), static_cast<double>(bands[i].q),
               static_cast<double>(bands[i].gain_db), typeName(bands[i].type));
    }
    printf("\n反映は caeqstat の param 行で見る (適用済み gen が %u になれば通っている)\n", gen);
    return kExitOk;
}
