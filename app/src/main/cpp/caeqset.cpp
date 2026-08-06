// 共有メモリのパラメータ枠に書く道具。実機で `.so` に実際のバンドを掛けさせるための、
// **書き手の役をする最小限のツール。**本番の書き手 (保持プロセス or フック) ができるまでの
// あいだ、これが唯一の書き手になる。
//
// seqlock の書き込みは dsp/ca_eq_params.h の paramsBeginWrite / paramsEndWrite をそのまま使う。
// **`.so` の読み手と同じ定義を共有している**ので、ここで書けたものは必ず読める。
//
//   caeqset --slot 0 --preamp -3 --band 100:1.0:6 --band 4000:2:-4 --band 10000:0.7:3:hs
//   caeqset --slot 0 --off        # enabled を落とす (バンドはそのまま)
//   caeqset --show                # いまの中身を出すだけ
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

#include "ca_eq_shm.h"
#include "dsp/ca_eq_params.h"

namespace {

void usage() {
    printf("caeqset — 共有メモリのパラメータ枠に書く\n\n");
    printf("  caeqset [--file PATH] --slot N [--off|--on] [--preamp dB]\n");
    printf("          [--band fc:q:gain[:type]] ...\n");
    printf("  caeqset [--file PATH] --show\n\n");
    printf("  type は pk (peaking, 既定) / ls (low shelf) / hs (high shelf)\n");
    printf("  --band を 1 つも渡さなければバンドは空 (プリアンプだけ) になる\n");
    printf("  検査は .so と同じ範囲で先に行う。落ちたら書かずに理由を出す\n");
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

void showAll(const ca_shm_t* m) {
    printf("version=%u slots=%u\n", m->version, m->slot_count);
    for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
        const ca_eq_slot_t* q = &m->params[i];
        const ca_slot_t* s = &m->slots[i];
        if (q->generation == 0 && s->in_use != CA_SHM_MAGIC) continue;
        printf("枠 %u: gen=%u flags=0x%x bands=%u preamp=%.2f dB writer_pid=%u",
               i, q->generation, q->flags, q->band_count,
               static_cast<double>(q->preamp_db), q->writer_pid);
        if (s->in_use == CA_SHM_MAGIC) {
            printf("  | .so: io=%d rate=%u ch=%u 適用済み gen=%u 却下=%u",
                   s->io_id, s->sample_rate, s->channels, s->param_gen, s->param_rejected);
        } else {
            printf("  | .so: この枠を読んでいるエフェクトは無い");
        }
        printf("\n");
        for (uint32_t b = 0; b < q->band_count && b < CA_EQ_MAX_BANDS; b++) {
            printf("        %2u: %8.1f Hz  Q %5.2f  %+6.2f dB  %s\n", b,
                   static_cast<double>(q->band[b].fc_hz), static_cast<double>(q->band[b].q),
                   static_cast<double>(q->band[b].gain_db), typeName(q->band[b].type));
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    const char* path = CA_SHM_PATH;
    int slot = -1;
    bool show = false;
    bool enabled = true;
    double preamp = 0.0;
    ca_eq_band_t bands[CA_EQ_MAX_BANDS];
    uint32_t band_count = 0;

    for (int i = 1; i < argc; i++) {
        const char* a = argv[i];
        if (std::strcmp(a, "--file") == 0 && i + 1 < argc)        path = argv[++i];
        else if (std::strcmp(a, "--slot") == 0 && i + 1 < argc)   slot = atoi(argv[++i]);
        else if (std::strcmp(a, "--preamp") == 0 && i + 1 < argc) preamp = atof(argv[++i]);
        else if (std::strcmp(a, "--off") == 0)                    enabled = false;
        else if (std::strcmp(a, "--on") == 0)                     enabled = true;
        else if (std::strcmp(a, "--show") == 0)                   show = true;
        else if (std::strcmp(a, "--band") == 0 && i + 1 < argc) {
            if (band_count >= CA_EQ_MAX_BANDS) {
                fprintf(stderr, "バンドが多すぎる (上限 %d)\n", CA_EQ_MAX_BANDS);
                return 2;
            }
            if (!parseBand(argv[++i], &bands[band_count])) {
                fprintf(stderr, "--band の書式が不正: %s\n", argv[i]);
                return 2;
            }
            band_count++;
        } else {
            usage();
            return 2;
        }
    }
    if (!show && slot < 0) { usage(); return 2; }

    const int fd = open(path, show ? O_RDONLY : O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", path, std::strerror(errno));
        return 2;
    }
    // 短いファイルを map して後ろを触ると SIGBUS。版が古いまま残っていることがある。
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < sizeof(ca_shm_t)) {
        fprintf(stderr, "%s が小さすぎる: %lld バイト (%zu 必要)。"
                        "post-fs-data.sh が古い\n",
                path, static_cast<long long>(st.st_size), sizeof(ca_shm_t));
        close(fd);
        return 2;
    }
    void* p = mmap(nullptr, sizeof(ca_shm_t), show ? PROT_READ : (PROT_READ | PROT_WRITE),
                   MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { fprintf(stderr, "mmap: %s\n", std::strerror(errno)); return 2; }
    ca_shm_t* m = static_cast<ca_shm_t*>(p);

    if (show) { showAll(m); return 0; }

    if (slot >= CA_SHM_SLOTS) {
        fprintf(stderr, "--slot は 0..%d\n", CA_SHM_SLOTS - 1);
        return 2;
    }

    // **書く前に .so と同じ検査を通す。**サンプルレートは統計側から引く — fc の上限だけが
    // fs に依るので、ここを取り違えると「書けたのに黙って却下される」になる。
    const uint32_t rate = m->slots[slot].sample_rate;
    const double fs = rate ? static_cast<double>(rate) : 48000.0;
    if (rate == 0) {
        printf("注意: 枠 %d を読むエフェクトがまだ SET_CONFIG を受けていない。"
               "検査は 48000 Hz として行う\n", slot);
    }
    ca_eq_slot_t staged{};
    staged.band_count = band_count;
    staged.preamp_db = static_cast<float>(preamp);
    for (uint32_t i = 0; i < band_count; i++) staged.band[i] = bands[i];

    caeq::Params check;
    if (!caeq::paramsConvert(staged, &check)) {
        fprintf(stderr, "並びが不正 (type かバンド数)\n");
        return 1;
    }
    if (!caeq::validate(check, fs)) {
        fprintf(stderr, "検査に落ちた (fs=%.0f Hz)。.so も同じ理由で却下する。\n", fs);
        fprintf(stderr, "  fc は %.0f 〜 %.0f Hz / Q は %.1f 〜 %.0f / gain は ±%.0f dB / "
                        "preamp は %.0f 〜 +%.0f dB\n",
                caeq::kMinFcHz, caeq::kValidFcRatio * fs, caeq::kMinQ, caeq::kMaxQ,
                caeq::kMaxGainDb, caeq::kMinPreampDb, caeq::kMaxPreampDb);
        return 1;
    }

    // seqlock で書く。generation は必ず動かす — 動かさないと .so は読みに来ない。
    ca_eq_slot_t* dst = &m->params[slot];
    uint32_t gen = dst->generation + 1;
    if (gen == 0) gen = 1;   // 0 は「未割り当て」の意味なので使わない

    caeq::paramsBeginWrite(dst);
    dst->generation = gen;
    dst->flags = enabled ? CA_EQ_FLAG_ENABLED : 0u;
    dst->band_count = band_count;
    dst->preamp_db = static_cast<float>(preamp);
    dst->writer_pid = static_cast<uint32_t>(getpid());
    for (uint32_t i = 0; i < CA_EQ_MAX_BANDS; i++) {
        dst->band[i] = (i < band_count) ? bands[i] : ca_eq_band_t{};
    }
    caeq::paramsEndWrite(dst);

    printf("枠 %d に書いた: gen=%u %s bands=%u preamp=%.2f dB (fs=%.0f Hz で検査済み)\n",
           slot, gen, enabled ? "enabled" : "disabled", band_count, preamp, fs);
    for (uint32_t i = 0; i < band_count; i++) {
        printf("  %2u: %8.1f Hz  Q %5.2f  %+6.2f dB  %s\n", i,
               static_cast<double>(bands[i].fc_hz), static_cast<double>(bands[i].q),
               static_cast<double>(bands[i].gain_db), typeName(bands[i].type));
    }
    printf("\n反映は caeqstat の param 行で見る (適用済み gen が %u になれば通っている)\n", gen);
    return 0;
}
