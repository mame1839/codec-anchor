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
#include "ca_eq_seq.h"

namespace caeq {

// 演算層の容量が転送の容量を下回ると、band_count の検査を通った値で配列の外を触る。
static_assert(CA_EQ_MAX_BANDS <= kMaxBands, "演算層のバンド数が転送の上限より少ない");

// 32 bit を不可分に触る土台 (`asAtomic` と `std::atomic<uint32_t>` の並びの釘) は
// ca_eq_seq.h にある。**統計の向き (ca_eq_stats.h) と同じ形を使うため**で、
// 片方だけ書き換えられる形にしない。

// 世代だけを覗く安い読み。**目安でしかない** — 途中まで書かれた値を掴んでも、
// この後の paramsRead の seqlock が弾く。動いていないなら丸ごとの読みを省ける。
inline uint32_t paramsGeneration(const ca_eq_slot_t* s) {
    return asAtomic(&s->generation)->load(std::memory_order_relaxed);
}

// --- 書き手 ---------------------------------------------------------------
//
// 書く前に奇数、書き終えたら偶数。**読み手はスピンしないので、書き込みは短く。**

inline void paramsBeginWrite(ca_eq_slot_t* s) { seqBeginWrite(&s->seq); }

inline void paramsEndWrite(ca_eq_slot_t* s) { seqEndWrite(&s->seq); }

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
