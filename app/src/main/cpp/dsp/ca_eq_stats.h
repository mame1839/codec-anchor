// 規約は eq-shm-abi.md §3。ここを ca_eq.cpp に直接書かないこと (ホストで検証できなくなる)。
#ifndef CA_EQ_STATS_H_
#define CA_EQ_STATS_H_

#include <cstring>

#include "../ca_eq_shm.h"
#include "ca_eq_seq.h"

namespace caeq {

// --- 書き手 (`.so`。オーディオスレッドと制御スレッドの両方から) ---------------

inline void statsBeginWrite(ca_slot_t* s) { seqBeginWrite(&s->seq); }

inline void statsEndWrite(ca_slot_t* s) { seqEndWrite(&s->seq); }

// --- 読み手 (`caeqstat`。別プロセス) -----------------------------------------

inline bool statsRead(const ca_slot_t* src, ca_slot_t* dst, int attempts = 100) {
    const std::atomic<uint32_t>* seq = asAtomic(&src->seq);
    for (int i = 0; i < attempts; i++) {
        const uint32_t s1 = seq->load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        std::memcpy(dst, src, sizeof(*dst));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 == seq->load(std::memory_order_acquire)) return true;
    }
    std::memcpy(dst, src, sizeof(*dst));
    return false;
}

}  // namespace caeq

#endif  // CA_EQ_STATS_H_
