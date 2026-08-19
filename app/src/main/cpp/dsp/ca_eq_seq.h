#ifndef CA_EQ_SEQ_H_
#define CA_EQ_SEQ_H_

#include <atomic>
#include <cstdint>

namespace caeq {

static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t),
              "std::atomic<uint32_t> が uint32_t と同じ大きさでない (共有メモリの並びが崩れる)");
static_assert(alignof(std::atomic<uint32_t>) == alignof(uint32_t),
              "std::atomic<uint32_t> の整列が uint32_t と違う");
static_assert(std::atomic<uint32_t>::is_always_lock_free,
              "std::atomic<uint32_t> がロックを使う実装 (プロセスをまたげない)");

inline std::atomic<uint32_t>* asAtomic(uint32_t* p) {
    return reinterpret_cast<std::atomic<uint32_t>*>(p);
}
inline const std::atomic<uint32_t>* asAtomic(const uint32_t* p) {
    return reinterpret_cast<const std::atomic<uint32_t>*>(p);
}

inline void seqBeginWrite(uint32_t* seq) {
    std::atomic<uint32_t>* s = asAtomic(seq);
    s->store(s->load(std::memory_order_relaxed) + 1u, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
}

inline void seqEndWrite(uint32_t* seq) {
    std::atomic_thread_fence(std::memory_order_release);
    std::atomic<uint32_t>* s = asAtomic(seq);
    s->store(s->load(std::memory_order_relaxed) + 1u, std::memory_order_release);
}

}

#endif
