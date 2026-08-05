// 統計ファイルを読んで人間が読める形で出す。実体は実行ファイル。
#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "ca_eq_shm.h"

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
        printf("magic=0x%08x (未初期化。まだ .so が一度も読み込まれていない)\n", m->magic);
        return 1;
    }
    printf("version=%u slots=%u slot_size=%u\n", m->version, m->slot_count, m->slot_size);
    printf("%-4s %-18s %-6s %-8s %-4s %-7s %-6s %-7s %s\n",
           "slot", "ctx", "io", "frames", "ch", "rate", "block", "gain_mB", "state");
    int active = 0;
    for (uint32_t i = 0; i < m->slot_count && i < static_cast<uint32_t>(CA_SHM_SLOTS); i++) {
        const ca_slot_t* s = &m->slots[i];
        if (s->in_use != CA_SHM_MAGIC) continue;
        active++;
        printf("%-4u 0x%-16llx %-6d %-8llu %-4u %-7u %-6u %-7d %s%s%s\n",
               i, (unsigned long long) s->ctx, s->io_id,
               (unsigned long long) s->frames, s->channels, s->sample_rate,
               s->block_frames, s->gain_mb,
               (s->state & CA_STATE_ENABLED) ? "enabled " : "",
               (s->state & CA_STATE_CONFIGURED) ? "configured " : "",
               (s->state & CA_STATE_PASSTHROUGH_ONLY) ? "PASSTHROUGH_ONLY" : "");
    }
    if (active == 0) printf("(使用中のスロットなし)\n");
    return 0;
}
