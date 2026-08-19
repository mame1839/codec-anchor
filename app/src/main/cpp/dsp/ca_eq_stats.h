#ifndef CA_EQ_STATS_H_
#define CA_EQ_STATS_H_

#include <cstring>

#include "../ca_eq_shm.h"
#include "ca_eq_seq.h"

namespace caeq {

inline void statsBeginWrite(ca_slot_t* s) { seqBeginWrite(&s->seq); }

inline void statsEndWrite(ca_slot_t* s) { seqEndWrite(&s->seq); }

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

}

#endif
