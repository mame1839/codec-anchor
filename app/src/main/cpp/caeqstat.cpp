// 統計ファイルを読んで人間が読める形で出す。実体は実行ファイル。
#include <fcntl.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "ca_eq_shm.h"

namespace {

// seqlock の読み手側。seq が偶数で、コピーの前後で変わっていなければ内容は一貫している。
// 書き手 (オーディオスレッド) が奇数の区間に居るのは数十 ns なので、まず 1 回で通る。
bool read_slot(const ca_slot_t* src, ca_slot_t* dst) {
    for (int attempt = 0; attempt < 100; attempt++) {
        const uint32_t s1 = src->seq;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 & 1u) continue;                  // 書き込み中
        std::memcpy(dst, src, sizeof(ca_slot_t));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 == src->seq) return true;
    }
    std::memcpy(dst, src, sizeof(ca_slot_t));   // 諦めて素で読み、行に印を付ける
    return false;
}

uint64_t now_monotonic_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<uint64_t>(ts.tv_nsec);
}

// 振幅を dBFS で。無音 (0.0) は -inf なので別扱いにする。
void format_dbfs(char* buf, size_t n, float peak) {
    if (peak <= 0.0f) { snprintf(buf, n, "silent"); return; }
    snprintf(buf, n, "%.1f", 20.0 * log10(static_cast<double>(peak)));
}

// 最後に process() が回ってからの経過。進んでいるのか凍っているのかは、これで 1 回で分かる。
void format_age(char* buf, size_t n, uint64_t last_ns, uint64_t now_ns) {
    if (last_ns == 0) { snprintf(buf, n, "never"); return; }
    if (last_ns > now_ns) { snprintf(buf, n, "?"); return; }
    snprintf(buf, n, "%.2fs", static_cast<double>(now_ns - last_ns) / 1e9);
}

}  // namespace

int main(int argc, char** argv) {
    const char* path = (argc > 1) ? argv[1] : CA_SHM_PATH;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { fprintf(stderr, "open %s failed\n", path); return 2; }

    // 短いファイルを mmap して読むと、ページの終端を越えた時点で SIGBUS になる。
    // 「カウンタが出ない」を調べている最中に理由の分からない死に方をされると困るので先に弾く。
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < sizeof(ca_shm_t)) {
        fprintf(stderr, "%s is too small: %lld bytes (%zu required)\n",
                path, static_cast<long long>(st.st_size), sizeof(ca_shm_t));
        close(fd);
        return 2;
    }

    void* p = mmap(nullptr, sizeof(ca_shm_t), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { fprintf(stderr, "mmap failed\n"); return 2; }
    const ca_shm_t* m = static_cast<const ca_shm_t*>(p);
    if (m->magic != CA_SHM_MAGIC) {
        // ここの文言を「.so が読み込まれていない」に戻さないこと。
        // この領域を初期化する ca_stats_open() は create_effect() からしか呼ばれないので、
        // HAL が .so を dlopen 済みでも、エフェクトが 1 度も生成されていなければ magic は 0 のまま。
        // 2 つを混同すると、正しい観測から「刺さっていない」という誤った結論が出る。
        printf("magic=0x%08x — まだエフェクトのインスタンスが 1 つも作られていない\n", m->magic);
        printf("\n");
        printf("  これは「.so が読み込まれていない」という意味ではない。\n");
        printf("  この領域を初期化するのは ca_stats_open() で、create_effect() からしか呼ばれない。\n");
        printf("  .so が読み込まれたかどうかは、HAL が map しているかで別に確かめる:\n");
        printf("    grep libcaeq /proc/$(pidof android.hardware.audio.service.mediatek)/maps\n");
        return 1;
    }
    printf("version=%u slots=%u slot_size=%u\n", m->version, m->slot_count, m->slot_size);
    printf("%-4s %-18s %-6s %-10s %-4s %-7s %-6s %-9s %-6s %-7s %-8s %-8s %s\n",
           "slot", "ctx", "io", "frames", "ch", "rate", "block", "age", "pid", "gain_mB",
           "in_dBFS", "out_dBFS", "state");

    const uint64_t now_ns = now_monotonic_ns();
    int active = 0;
    for (uint32_t i = 0; i < m->slot_count && i < static_cast<uint32_t>(CA_SHM_SLOTS); i++) {
        ca_slot_t s;
        const bool stable = read_slot(&m->slots[i], &s);
        if (s.in_use != CA_SHM_MAGIC) continue;
        active++;
        char age[16], ind[16], outd[16];
        format_age(age, sizeof(age), s.last_ns, now_ns);
        format_dbfs(ind, sizeof(ind), s.in_peak);
        format_dbfs(outd, sizeof(outd), s.out_peak);
        printf("%-4u 0x%-16llx %-6d %-10llu %-4u %-7u %-6u %-9s %-6llu %-7d %-8s %-8s %s%s%s%s\n",
               i, (unsigned long long) s.ctx, s.io_id,
               (unsigned long long) s.frames, s.channels, s.sample_rate,
               s.block_frames, age, (unsigned long long) s.pid, s.gain_mb, ind, outd,
               (s.state & CA_STATE_ENABLED) ? "enabled " : "",
               (s.state & CA_STATE_CONFIGURED) ? "configured " : "",
               (s.state & CA_STATE_PASSTHROUGH_ONLY) ? "PASSTHROUGH_ONLY " : "",
               stable ? "" : "(読み取り中に更新された)");
    }
    if (active == 0) {
        // magic が立っている = ca_stats_open() が走った = create_effect() が最低 1 回はあった。
        // ここでも「.so が読み込まれていない」とは書かない。
        printf("(使用中のスロットなし — エフェクトが作られたことはあるが、いま生きているものは無い)\n");
    }
    printf("\n");
    printf("age = 最後に process() が回ってからの経過。読み方:\n");
    printf("  never          このインスタンスで process() が 1 度も呼ばれていない。\n");
    printf("                 生成も有効化もされたが、音声がここを通っていない\n");
    printf("  伸び続ける     過去には回っていたが、いまは止まっている。\n");
    printf("                 (再生していない / 経路から外れた / pid のプロセスが死んだ残骸)\n");
    printf("  更新され続ける 音声が実際に通っている\n");
    printf("\n");
    printf("frames は 2 回実行して差を見ること。age だけでは「いま回っているか」しか分からない。\n");
    printf("in_dBFS が silent なら無音が来ているだけで、経路に入っていないのとは別。\n");
    printf("out_dBFS - in_dBFS が gain_mB/100 と一致していれば、加工が実際に効いている。\n");
    return 0;
}
