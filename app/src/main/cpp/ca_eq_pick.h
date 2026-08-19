#ifndef CA_EQ_PICK_H_
#define CA_EQ_PICK_H_

#include <stdint.h>

#include <atomic>
#include <cerrno>
#include <cstring>

#include "ca_eq_shm.h"

namespace caeq {

enum class ShmState {
    kOk = 0,
    kNotInitialised,
    kForeign,
    kVersionMismatch,
    kLayoutMismatch,
};

inline ShmState shmState(const ca_shm_t* m) {
    if (m == nullptr) return ShmState::kForeign;
    if (m->magic == 0u) return ShmState::kNotInitialised;
    if (m->magic != CA_SHM_MAGIC) return ShmState::kForeign;
    if (m->version != CA_SHM_VERSION) return ShmState::kVersionMismatch;
    if (m->slot_size != static_cast<uint32_t>(sizeof(ca_slot_t)) ||
        m->param_slot_size != static_cast<uint32_t>(sizeof(ca_eq_slot_t)) ||
        m->curve_points != static_cast<uint32_t>(kCurvePoints)) {
        return ShmState::kLayoutMismatch;
    }
    return ShmState::kOk;
}

inline bool sessionCanBeAddressed(int32_t session_id) {
    return session_id == CA_AUDIO_SESSION_DEVICE;
}

enum class FirWhy {
    kRunning = 0,
    kNoAudio,
    kNotRequested,
    kNotAddressable,
    kNoArena,
    kBlockUnfit,
    kNoCurve,
    kDesignFailed,
    kWarming,
    kAlmost,
};

inline FirWhy firWhy(const ca_slot_t& s, const ca_eq_slot_t* q) {
    if (s.fir_state == CA_FIR_STATE_FIR) return FirWhy::kRunning;
    if ((s.fir_flags & CA_FIR_F_REQUESTED) == 0u) return FirWhy::kNotRequested;
    if ((s.fir_flags & CA_FIR_F_ADDRESSABLE) == 0u) return FirWhy::kNotAddressable;
    if ((s.fir_flags & CA_FIR_F_ARENA) == 0u) return FirWhy::kNoArena;
    if ((s.fir_flags & CA_FIR_F_BLOCK_OK) == 0u) return FirWhy::kBlockUnfit;
    if (q != nullptr && q->curve_gen == 0u) return FirWhy::kNoCurve;
    if ((s.fir_flags & CA_FIR_F_CURVE_FAILED) != 0u) return FirWhy::kDesignFailed;
    if (s.fir_fill < s.fir_partitions) return FirWhy::kWarming;
    return FirWhy::kAlmost;
}

inline FirWhy firWhyReported(const ca_slot_t& s, const ca_eq_slot_t* q) {
    if (s.frames == 0u) return FirWhy::kNoAudio;
    return firWhy(s, q);
}

inline const char* firWhyToken(FirWhy w) {
    switch (w) {
    case FirWhy::kRunning:        return "running";
    case FirWhy::kNoAudio:        return "no_audio";
    case FirWhy::kNotRequested:   return "not_requested";
    case FirWhy::kNotAddressable: return "not_addressable";
    case FirWhy::kNoArena:        return "no_arena";
    case FirWhy::kBlockUnfit:     return "block_unfit";
    case FirWhy::kNoCurve:        return "no_curve";
    case FirWhy::kDesignFailed:   return "design_failed";
    case FirWhy::kWarming:        return "warming";
    case FirWhy::kAlmost:         return "almost";
    }
    return "";
}

enum class SlotPick {
    kOk = 0,
    kNone,
    kAmbiguous,
};

struct SlotPickResult {
    SlotPick status;
    uint32_t param_slot;
    uint32_t stats_slot;
    uint32_t live_count;
    uint32_t stale_count;
    uint32_t other_count;
    uint32_t used_count;
    uint32_t slot_count;
};

typedef bool (*PidAliveFn)(uint64_t pid, void* user);

inline bool aliveFromAccess(int rc, int err) {
    if (rc == 0) return true;
    return err != ENOENT;
}

inline uint32_t slotCountOf(const ca_shm_t* m) {
    const uint32_t n = m->slot_count;
    return n > static_cast<uint32_t>(CA_SHM_SLOTS) ? static_cast<uint32_t>(CA_SHM_SLOTS) : n;
}

inline SlotPickResult pickDeviceSlot(const ca_shm_t* m, PidAliveFn alive, void* user) {
    SlotPickResult r{SlotPick::kNone, 0u, 0u, 0u, 0u, 0u, 0u, 0u};
    if (m == nullptr || alive == nullptr) return r;

    const uint32_t count = slotCountOf(m);
    r.slot_count = count;

    for (uint32_t i = 0; i < count; i++) {
        const ca_slot_t& s = m->slots[i];
        if (s.in_use != CA_SHM_MAGIC) continue;
        r.used_count++;
        if (s.pid == 0) continue;
        if (!alive(s.pid, user)) { r.stale_count++; continue; }
        if (!sessionCanBeAddressed(s.session_id)) { r.other_count++; continue; }
        if (s.param_slot >= static_cast<uint32_t>(CA_SHM_SLOTS)) continue;
        r.live_count++;
        if (r.live_count == 1) {
            r.param_slot = s.param_slot;
            r.stats_slot = i;
        }
    }

    if (r.live_count == 1) {
        r.status = SlotPick::kOk;
    } else if (r.live_count > 1) {
        r.status = SlotPick::kAmbiguous;
    }
    return r;
}

constexpr uint32_t kNoSlot = 0xFFFFFFFFu;

inline std::atomic<uint32_t>* slotInUse(ca_slot_t* s) {
    return reinterpret_cast<std::atomic<uint32_t>*>(&s->in_use);
}

inline bool releaseSlot(ca_slot_t* s) {
    if (s == nullptr) return false;
    std::atomic<uint32_t>* in_use = slotInUse(s);
    uint32_t expected = CA_SHM_MAGIC;
    if (!in_use->compare_exchange_strong(expected, CA_SHM_RECLAIM)) return false;
    std::memset(&s->seq, 0, sizeof(ca_slot_t) - offsetof(ca_slot_t, seq));
    std::atomic_thread_fence(std::memory_order_release);
    in_use->store(0u, std::memory_order_release);
    return true;
}

inline uint32_t reclaimDeadSlots(ca_shm_t* m, PidAliveFn alive, void* user, uint64_t self_pid) {
    if (m == nullptr || alive == nullptr) return 0u;
    const uint32_t count = slotCountOf(m);
    uint32_t n = 0u;
    for (uint32_t i = 0; i < count; i++) {
        ca_slot_t* s = &m->slots[i];
        if (slotInUse(s)->load(std::memory_order_acquire) != CA_SHM_MAGIC) continue;
        const uint64_t pid = s->pid;
        if (pid == 0u) continue;
        if (pid == self_pid) continue;
        if (alive(pid, user)) continue;
        if (releaseSlot(s)) n++;
    }
    return n;
}

inline uint32_t paramSlotAfterAttach(uint32_t current, uint32_t taken) {
    return current < static_cast<uint32_t>(CA_SHM_SLOTS) ? current : taken;
}

inline uint32_t acquireSlot(ca_shm_t* m, PidAliveFn alive, void* user, uint64_t self_pid) {
    if (m == nullptr) return kNoSlot;
    const uint32_t count = slotCountOf(m);
    for (int pass = 0; pass < 2; pass++) {
        for (uint32_t i = 0; i < count; i++) {
            uint32_t expected = 0u;
            if (slotInUse(&m->slots[i])->compare_exchange_strong(expected, CA_SHM_MAGIC)) {
                return i;
            }
        }
        if (pass != 0) break;
        if (reclaimDeadSlots(m, alive, user, self_pid) == 0u) break;
    }
    return kNoSlot;
}

}

#endif
