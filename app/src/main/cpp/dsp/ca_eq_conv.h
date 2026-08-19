// 一様分割畳み込みの演算核。設計は eq-dsp-internals.md §2。
#ifndef CA_EQ_CONV_H_
#define CA_EQ_CONV_H_

#include <cstdint>

#include "ca_eq_fft.h"

namespace caeq {

inline int firPartitions(int taps, int block) { return (taps + block - 1) / block; }

class FirKernel {
public:
    FirKernel() = default;
    FirKernel(const FirKernel&) = delete;
    FirKernel& operator=(const FirKernel&) = delete;

    // すべて 16 B 整列 (N = 2P)。
    struct Buffers {
        float* fdl     = nullptr;  // ch · K · N。**未初期化でよい** (fill が守る)
        float* filt[2] = {nullptr, nullptr};  // 面ごと K · N (FirDesigner が書く)
        float* tail    = nullptr;  // ch · P (OLA の重なり)
        float* stage   = nullptr;  // N (集約とゼロ詰め)
        float* acc     = nullptr;  // N (MAC の集積と逆 FFT)
        float* acc2    = nullptr;  // N (面クロスフェード中だけ)
        float* work    = nullptr;  // N (FFT work)
    };

    // 検査して巻き戻す (fill = 0)。不正なら false。plan は 2P の FftPlan。
    bool bind(const FftPlan* plan, int block, int taps, int channels, const Buffers& b);

    // fill = 0、tail をゼロ。FDL 本体には触らない。
    void reset();

    // ⚠️ バッファの寿命が尽きる (arena の解放) 前に必ず呼ぶ。bind したまま reset() が
    // 来ると解放済みの tail へ memset して heap を壊す。
    void unbind() { bound_ = false; }

    bool ready() const { return bound_ && fill_ >= k_total_; }
    bool bound() const { return bound_; }
    int  partitions() const { return k_total_; }
    int  fill() const { return fill_; }
    uint32_t scrubbedSamples() const { return scrubbed_; }

    // 入力だけ進める (FDL を温める)。FFT は回すが MAC と逆 FFT はしない。
    // in はインタリーブ P フレーム。
    void pushBlock(const float* in);

    // 1 ブロック処理。in / out はインタリーブ P フレーム。in == out 可。
    // 出力は畳み込みの生の値 — preamp / wet / dry の混合は呼び出し側 (EqPipeline) がやる。
    //
    // face: 鳴らす面 (0/1)。fade_face: -1 なら単面。0/1 ならその面へ乗り移り中で、
    // フレーム i の重みは clamp(fade_w0 + i·fade_dw, 0, 1)。
    void processBlock(const float* in, float* out, int face, int fade_face, float fade_w0,
                      float fade_dw);

private:
    void pushInput(const float* in);
    void macFace(int ch_index, int face, float* acc) const;

    const FftPlan* plan_ = nullptr;
    Buffers b_{};
    int  block_   = 0;
    int  n_       = 0;  // 2P
    int  taps_    = 0;
    int  ch_      = 0;
    int  k_total_ = 0;
    int  head_    = 0;  // 最新ブロックの FDL 位置
    int  fill_    = 0;  // 妥当な履歴の本数 (0..K)
    bool bound_   = false;
    uint32_t scrubbed_ = 0;
};

}  // namespace caeq

#endif  // CA_EQ_CONV_H_
