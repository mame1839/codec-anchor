// 共有メモリのパラメータ枠を読み書きする規約。**Android に依存しない** —
// ホストのハーネスが、書き手のスレッドを立てて千切れた読みが起きないことを直接確かめる。
//
// 向きは統計と逆で、**書き手が外 (設定を持っている側)、読み手が `.so`。**
// 書き手と読み手が同じ定義を使うことが要点で、片方だけ手で書くと、
// 実機でしか出ない・しかも再現しない不整合になる。
#ifndef CA_EQ_PARAMS_H_
#define CA_EQ_PARAMS_H_

#include <atomic>
#include <cstring>

#include "../ca_eq_shm.h"
#include "ca_eq_dsp.h"

namespace caeq {

// 演算層の容量が転送の容量を下回ると、band_count の検査を通った値で配列の外を触る。
static_assert(CA_EQ_MAX_BANDS <= kMaxBands, "演算層のバンド数が転送の上限より少ない");

// --- 共有メモリ上の 32 bit を不可分に触る ----------------------------------
//
// **構造体のフィールドを `std::atomic<uint32_t>` に置き換えないこと。**
// この構造体は 2 つのプロセスが同じバイト列として見る転送の形で、大きさと並びが
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

// 世代だけを覗く安い読み。**目安でしかない** — 途中まで書かれた値を掴んでも、
// この後の paramsRead の seqlock が弾く。動いていないなら丸ごとの読みを省ける。
inline uint32_t paramsGeneration(const ca_eq_slot_t* s) {
    return asAtomic(&s->generation)->load(std::memory_order_relaxed);
}

// --- 書き手 ---------------------------------------------------------------
//
// 書く前に奇数、書き終えたら偶数。**読み手はスピンしないので、書き込みは短く。**

inline void paramsBeginWrite(ca_eq_slot_t* s) {
    std::atomic<uint32_t>* seq = asAtomic(&s->seq);
    seq->store(seq->load(std::memory_order_relaxed) + 1u, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
}

inline void paramsEndWrite(ca_eq_slot_t* s) {
    std::atomic_thread_fence(std::memory_order_release);
    std::atomic<uint32_t>* seq = asAtomic(&s->seq);
    seq->store(seq->load(std::memory_order_relaxed) + 1u, std::memory_order_release);
}

// --- 読み手 ---------------------------------------------------------------

// 初回 + リトライ 2 回で諦める。**オーディオスレッドで絶対にスピンしない** —
// 書き手が書き込みの途中で死んでも、諦めて次のブロックへ進めば音は前の設定で鳴り続ける。
// ミューテックスは使えない (プロセスをまたぐうえ、書き手は切断時に kill される)。
inline bool paramsRead(const ca_eq_slot_t* src, ca_eq_slot_t* dst) {
    const std::atomic<uint32_t>* seq = asAtomic(&src->seq);
    for (int i = 0; i < 3; i++) {
        const uint32_t s1 = seq->load(std::memory_order_acquire);
        if (s1 & 1u) continue;                    // 書き込みの最中
        std::memcpy(dst, src, sizeof(*dst));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 == seq->load(std::memory_order_acquire)) return true;
    }
    return false;
}

// 共有メモリの並びを演算層の構造体へ。**値の範囲は検査しない** — それは
// Eq::setParams が全部やる。範囲の表を 2 箇所に持つと、片方だけ直したときに
// 黙って通る値ができる。ここで見るのは「この並びを読んでよいか」だけ。
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

}  // namespace caeq

#endif  // CA_EQ_PARAMS_H_
