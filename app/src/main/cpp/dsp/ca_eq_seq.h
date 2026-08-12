// 共有メモリ上の 32 bit を不可分に触るための下ごしらえ。**Android に依存しない。**
//
// 共有メモリには向きの違う 2 本の seqlock がある (統計は `.so` が書いて外が読む、
// パラメータは外が書いて `.so` が読む)。**この土台だけを 1 箇所に持ち**、
// それぞれの規約は ca_eq_stats.h / ca_eq_params.h が書く。
#ifndef CA_EQ_SEQ_H_
#define CA_EQ_SEQ_H_

#include <atomic>
#include <cstdint>

namespace caeq {

// **構造体のフィールドを `std::atomic<uint32_t>` に置き換えないこと。**
// 共有メモリの構造体は 2 つのプロセスが同じバイト列として見る転送の形で、大きさと並びが
// `CA_SHM_BYTES` ⇄ `module/common/setup.sh` の `bs=` の一致で縛られている。
// 型を変えるとレイアウトが処理系任せになる。C++20 の `std::atomic_ref` がこの用途
// そのものだが、この製品は C++17 なので `reinterpret_cast` で被せる
// (`ca_eq.cpp` の `ca_in_use()` が `in_use` に対して既に使っている同じ形)。
//
// GCC/clang の `__atomic_*` builtin を直接使っていたのを std::atomic へ移した。
// **builtin は MSVC に無く、それだけでホストのハーネス全体が MSVC で組めなくなる**
// — この環境で sanitizer を持っているのは MSVC だけなので、それは網を 1 枚失うこと。
static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t),
              "std::atomic<uint32_t> が uint32_t と同じ大きさでない (共有メモリの並びが崩れる)");
static_assert(alignof(std::atomic<uint32_t>) == alignof(uint32_t),
              "std::atomic<uint32_t> の整列が uint32_t と違う");
// ロック付きの実装だとロックはプロセスごとに別物になり、別プロセスの書き手とは
// 何も同期しない。**黙って壊れるので、ここでビルドを止める。**
static_assert(std::atomic<uint32_t>::is_always_lock_free,
              "std::atomic<uint32_t> がロックを使う実装 (プロセスをまたげない)");

inline std::atomic<uint32_t>* asAtomic(uint32_t* p) {
    return reinterpret_cast<std::atomic<uint32_t>*>(p);
}
inline const std::atomic<uint32_t>* asAtomic(const uint32_t* p) {
    return reinterpret_cast<const std::atomic<uint32_t>*>(p);
}

// 書く前に奇数、書き終えたら偶数。**両方向で同じ形。**
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

}  // namespace caeq

#endif  // CA_EQ_SEQ_H_
