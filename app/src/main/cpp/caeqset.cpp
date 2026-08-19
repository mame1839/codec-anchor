//   | 値 | 意味                                                                        |
//   |----|-----------------------------------------------------------------------------|
//   |  0 | 書けた                                                                      |
//   | 10 | 使い方が不正。引数の組み立てを間違えている (通常は出ない)                    |
//   | 11 | 共有メモリが無い / 開けない。モジュールが動いていない                        |
//   | 12 | 共有メモリの版・大きさが合わない。**アプリとモジュールの版ずれ**             |
//   | 13 | 生きた枠が無い。**イヤホンが繋がっていないだけで、失敗ではない**             |
//   | 14 | 生きた枠が複数。イヤホンが 2 台繋がっているので断った                        |
//   | 15 | パラメータが検査に落ちた (`.so` と同じ範囲で先に検査している)                |
//   | 16 | 枠が尽きた。生きた枠が 0 で、かつ全部が使用中。**「繋がっていない」ではない** |
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
#include "ca_eq_proc.h"
#include "ca_eq_shm.h"
#include "dsp/ca_eq_params.h"
#include "dsp/ca_eq_stats.h"

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
    kExitSlotsFull     = 16,
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

constexpr caeq::PidAliveFn pidAlive = caeq::procPidAlive;

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

int reportPickFailure(const caeq::SlotPickResult& pick) {
    if (pick.status == caeq::SlotPick::kAmbiguous) {
        fprintf(stderr, "イヤホン側の生きた枠が %u ある。**どの枠がどのイヤホンかは .so から"
                        "分からない**ので、推測せずに断る (--show で内訳が出る)\n",
                pick.live_count);
        return kExitAmbiguousSlot;
    }
    if (pick.slot_count > 0 && pick.used_count >= pick.slot_count) {
        fprintf(stderr, "枠 %u 個が全部使用中で、そのどれもイヤホン側ではない "
                        "(残骸 %u / イヤホン以外 %u)。**繋がっていないのとは別の状態。**"
                        "回収しても空かないので、audio HAL を再起動するしかない\n",
                pick.slot_count, pick.stale_count, pick.other_count);
        return kExitSlotsFull;
    }
    fprintf(stderr, "イヤホン側の生きた枠が無い (使用中 %u/%u / 残骸 %u / イヤホン以外 %u)。"
                    "イヤホンが繋がっていないだけなら異常ではない\n",
            pick.used_count, pick.slot_count, pick.stale_count, pick.other_count);
    return kExitNoLiveSlot;
}

void reportFir(const ca_shm_t* m, uint32_t stats_slot, bool highPrecision, uint32_t curve_gen) {
    ca_slot_t s{};
    caeq::statsRead(&m->slots[stats_slot], &s);
    if (highPrecision) s.fir_flags |= CA_FIR_F_REQUESTED;
    else               s.fir_flags &= ~CA_FIR_F_REQUESTED;
    ca_eq_slot_t probe{};
    probe.curve_gen = curve_gen;
    long long age_ms = -1;
    if (s.last_ns != 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        const uint64_t now = static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
                             static_cast<uint64_t>(ts.tv_nsec);
        age_ms = now > s.last_ns ? static_cast<long long>((now - s.last_ns) / 1000000ull) : 0;
    }
    printf("CA_EQ_FIR why=%s rate=%u block=%u frames=%llu age_ms=%lld\n",
           caeq::firWhyToken(caeq::firWhyReported(s, &probe)), s.sample_rate, s.block_frames,
           static_cast<unsigned long long>(s.frames), age_ms);
}

}

int main(int argc, char** argv) {
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

    switch (caeq::shmState(m)) {
    case caeq::ShmState::kNotInitialised:
        fprintf(stderr, "エフェクトのインスタンスが 1 度も作られていない。"
                        "イヤホンが繋がっていないだけなら異常ではない\n");
        return kExitNoLiveSlot;
    case caeq::ShmState::kForeign:
        fprintf(stderr, "magic=0x%08x — %s は我々のファイルではない\n", m->magic, path);
        return kExitShmMismatch;
    case caeq::ShmState::kVersionMismatch:
        fprintf(stderr, "共有メモリの版が %u (期待 %u)。アプリとモジュールの版がずれている。"
                        "モジュールを入れ直すこと\n",
                m->version, CA_SHM_VERSION);
        return kExitShmMismatch;
    case caeq::ShmState::kLayoutMismatch:
        fprintf(stderr, "共有メモリの並びが違う (枠 %u B / 期待 %zu、曲線 %u 点 / 期待 %d、"
                        "統計 %u B / 期待 %zu)。版は同じなので、.so とこのコマンドの"
                        "ビルドが食い違っている\n",
                m->param_slot_size, sizeof(ca_eq_slot_t), m->curve_points, caeq::kCurvePoints,
                m->slot_size, sizeof(ca_slot_t));
        return kExitShmMismatch;
    case caeq::ShmState::kOk:
        break;
    }

    {
        const uint32_t freed = caeq::reclaimDeadSlots(m, pidAlive, nullptr,
                                                      static_cast<uint64_t>(getpid()));
        if (freed) printf("残骸の枠を %u 個回収した\n", freed);
    }

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
        reportFir(m, stats_slot, highPrecision, m->params[param_slot].curve_gen);
        printf("--dry-run: 枠 %u に書けるところまで確かめた (書いていない)\n", param_slot);
        return kExitOk;
    }

    ca_eq_slot_t* dst = &m->params[param_slot];
    uint32_t gen = dst->generation + 1;
    if (gen == 0) gen = 1;

    uint32_t curve_gen = dst->curve_gen;
    if (haveCurve) {
        curve_gen++;
        if (curve_gen == 0) curve_gen = 1;
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

    reportFir(m, stats_slot, highPrecision, curve_gen);

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
