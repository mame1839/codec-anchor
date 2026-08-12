// 一様分割畳み込みの演算核。**Android にも Xposed にも依存しない。**
//
// 構造 (11_fir_vs_biquad.py の bench_conv と同一):
//   セグメント長 = ブロック長 P、FFT サイズ N = 2P、K = ceil(taps/P) 分割。
//   入力ブロックをゼロ詰め 2P で実 FFT → FDL (周波数領域の入力履歴、チャンネルごとの
//   リング) へ push → 全分割の zconvolve (MAC) → 逆 FFT → 前ブロックの尻尾と重ね合わせ
//   (OLA)。**追加遅延 0** — 今のブロックの入力が分割 0 (h[0] を含む) を通って
//   今のブロックの出力に出る。
//
// フィルタスペクトルは **2 面**。FIR → FIR の曲線差し替えは、裏の面に FirDesigner が
// スペクトルを組み、フェード窓の間だけ両面の MAC + 逆 FFT を回して時間領域で
// クロスフェードする (入力 FDL は共有 — 履歴はフィルタに依存しない)。
//
// **FDL のクリアは memset ではなく fill カウンタ。**RESET はオーディオスレッドから来るので、
// メガバイト級の FDL をゼロで書き潰す仕事を置かない。fill 本しか履歴が無いあいだは
// その分しか MAC しない (= 無い履歴はゼロと同じ)。
//
// **確保・ロック・ログ・例外なし** (既存 dsp と同じ規律)。バッファはすべて呼び出し側から。
// 正規化: 1/(2P) は FirDesigner がフィルタスペクトルに焼いてある。ここでは掛けない。
#ifndef CA_EQ_CONV_H_
#define CA_EQ_CONV_H_

#include <cstdint>

#include "ca_eq_fft.h"

namespace caeq {

// 分割数 K。taps が block で割り切れない端数は最後の分割のゼロ詰めで吸収する
// (8192/960 → K=9、最後は 512 本)。
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

    // 履歴を無効化する。fill = 0、tail をゼロ (ch·P floats — 最大 32 KB で、既存 Eq の
    // clearState と同規模)。FDL 本体には触らない。
    void reset();

    // バッファへの参照を放す。**バッファの寿命が尽きる (arena の解放) 前に必ず呼ぶ** —
    // bind したまま reset() が来ると、解放済みの tail へ memset して heap を壊す。
    void unbind() { bound_ = false; }

    bool ready() const { return bound_ && fill_ >= k_total_; }
    bool bound() const { return bound_; }
    int  partitions() const { return k_total_; }
    int  fill() const { return fill_; }
    uint32_t scrubbedSamples() const { return scrubbed_; }

    // 入力だけ進める (FDL を温める)。FFT は回すが MAC と逆 FFT はしない。
    // in はインタリーブ P フレーム。
    void pushBlock(const float* in);

    // 1 ブロック処理。in / out はインタリーブ P フレーム。in == out 可
    // (チャンネルごとに「読み終えてから書く」ため)。出力は**畳み込みの生の値** —
    // preamp / wet / dry の混合は呼び出し側 (EqPipeline) がやる。
    //
    // face: 鳴らす面 (0/1)。fade_face: -1 なら単面。0/1 ならその面へ乗り移り中で、
    // フレーム i の重みは clamp(fade_w0 + i·fade_dw, 0, 1)。尻尾 (次のブロックで出る
    // P 本) には i = P..2P-1 の外挿重みを使う — フェードの軌跡はブロックをまたいで
    // 一定速で進むので、尻尾が実際に鳴る時刻の重みと一致する (完了後は 1 に飽和)。
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
