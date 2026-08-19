#ifndef CA_EQ_PARAMS_H_
#define CA_EQ_PARAMS_H_

#include <atomic>
#include <cstring>

#include "../ca_eq_shm.h"
#include "ca_eq_dsp.h"
#include "ca_eq_seq.h"

namespace caeq {

static_assert(CA_EQ_MAX_BANDS <= kMaxBands, "演算層のバンド数が転送の上限より少ない");

inline uint32_t paramsGeneration(const ca_eq_slot_t* s) {
    return asAtomic(&s->generation)->load(std::memory_order_relaxed);
}

inline void paramsBeginWrite(ca_eq_slot_t* s) { seqBeginWrite(&s->seq); }

inline void paramsEndWrite(ca_eq_slot_t* s) { seqEndWrite(&s->seq); }

inline bool paramsRead(const ca_eq_slot_t* src, ca_eq_slot_t* dst) {
    const std::atomic<uint32_t>* seq = asAtomic(&src->seq);
    for (int i = 0; i < 3; i++) {
        const uint32_t s1 = seq->load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        std::memcpy(dst, src, sizeof(*dst));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 == seq->load(std::memory_order_acquire)) return true;
    }
    return false;
}

inline bool paramsConvert(const ca_eq_slot_t& s, Params* out) {
    if (s.band_count > CA_EQ_MAX_BANDS) return false;
    out->band_count = static_cast<int>(s.band_count);
    out->preamp_db = static_cast<double>(s.preamp_db);
    for (uint32_t i = 0; i < s.band_count; i++) {
        Band& b = out->bands[i];
        switch (s.band[i].type) {
        case CA_EQ_BAND_PEAKING:    b.type = BandType::kPeaking;   break;
        case CA_EQ_BAND_LOW_SHELF:  b.type = BandType::kLowShelf;  break;
        case CA_EQ_BAND_HIGH_SHELF: b.type = BandType::kHighShelf; break;
        default: return false;
        }
        b.fc      = static_cast<double>(s.band[i].fc_hz);
        b.q       = static_cast<double>(s.band[i].q);
        b.gain_db = static_cast<double>(s.band[i].gain_db);
    }
    return true;
}

}

#endif
