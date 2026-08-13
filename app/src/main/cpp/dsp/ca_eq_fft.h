// vendored PFFFT (dsp/pffft/) の薄い覆い。**Android にも Xposed にも依存しない。**
//
// 覆いを挟む理由は 2 つ。
//   1. サイズの合法判定を自前の述語で固定する。PFFFT の実際の制約は SIMD 幅から来る
//      (SIMD 有効 = 32 の倍数、スカラ = 2 の倍数) ので、ライブラリ任せにすると
//      ホスト (SSE) と実機 (NEON) とスカラビルドで判定が食い違い、ホストで通って
//      実機で落ちる形になる。ここでは常に厳しい側「32 の倍数かつ 2^a·3^b·5^c」に固定する。
//   2. オリジナルの pffft_new_setup は不正サイズを assert で見る。NDEBUG では消えるので、
//      呼ぶ前にここで必ず弾く (通すと黙って壊れた setup ができる)。
//
// 使い方の約束:
//   - init() / initInPlace() は制御スレッド専用。init() は内部で malloc する
//     (pffft_new_setup)。initInPlace() は確保せず、呼び出し側のメモリに組み立てる。
//   - transform 系は確保もロックもしない。**同じ FftPlan を複数のスレッド /
//     インスタンスが並行に読んでも安全** — pffft.h の宣言に加えて rev 09796885 の
//     ソースで確認した: transform 経路は setup の全フィールド (N / Ncvec / ifac /
//     transform / e / twiddle) を読むだけで、twiddle への書き込み (rffti1_ps /
//     cffti1_ps / decompose) は setup の生成からしか呼ばれない。可変状態は
//     すべて呼び出し側が渡す入出力と work に載る。work と入出力だけスレッドごとに分けること。
//   - work は**常に明示で渡す** (n floats)。PFFFT は work == NULL だと内部で
//     alloca 相当に落ちる — オーディオスレッドで巨大 FFT のスタック確保をさせない。
//   - すべてのバッファは 16 バイト整列 (SSE/NEON の要求。arena からは 64 B で切り出す)。
//
// 正規化: PFFFT は正規化なし — inverse(forward(x)) = n·x。**1/n は呼び出し側が
// ちょうど 1 回掛ける。**この製品では、最小位相化 (ca_eq_fir.cpp) は折り返しと窓の
// 工程に、畳み込み (ca_eq_conv.cpp) はフィルタスペクトルに焼く。
//
// 実データの並び (順序付き):
//   forwardOrdered の出力は [X(0), X(n/2), Re X(1), Im X(1), ..., Re X(n/2-1), Im X(n/2-1)]
//   の n 本 (X(0) と X(n/2) は実数なので先頭 2 本に詰められる)。inverseOrdered は
//   この並びを受け取る。
//   forward (順序なし) の出力は内部順で、解釈しない。convolveAccumulate と inverse に
//   そのまま渡すためだけの表現。
#ifndef CA_EQ_FFT_H_
#define CA_EQ_FFT_H_

#include <cstddef>

// -ffast-math は NaN の検査ごと消す (ca_eq_dsp.h と同じ柵)。
#if defined(__FAST_MATH__)
#error "ca_eq_fft は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

// 合法サイズ: 32 の倍数かつ 2^a·3^b·5^c。上限は PFFFT と同じ 2^26。
bool fftSizeValid(int n);

// 畳み込みのブロック長 P の合法判定は FFT サイズ 2P で見る (P 自体は 16 の倍数になる)。
inline bool convBlockSizeValid(int p) {
    return p > 0 && p <= (1 << 25) && fftSizeValid(2 * p);
}

// n の setup を置くのに必要なバイト数 (initInPlace 用)。n が大きいほど大きい (単調)。
size_t fftSetupBytes(int n);

// --- 整列の不変条件 ---------------------------------------------------------
//
// ⚠️ **PFFFT は process 経路でも assert で整列を見る** (pffft_transform_internal /
// pffft_zconvolve_accumulate)。この製品は NDEBUG を定義しないので assert は生きたまま
// `.so` に載り、崩れると audio HAL が abort() する = 端末全体が無音になる。
// **assert に到達しないことをこちら側の不変条件で保証する** (pffft.c 冒頭の「assert の扱い」)。
//
// 不変条件 (これが成り立つ限り、派生ポインタも必ず 16 バイト整列):
//   1. FFT に渡す**基底**のバッファは 16 B 整列 (arena は 64 B で切る)。
//      FirDesigner::start / FirKernel::bind が fftAligned で検査し、崩れていたら
//      false を返す → 呼び出し側は FIR を使わずカウンタを進めて biquad へ落ちる。
//   2. fftSizeValid(n) ⟹ n は 32 の倍数。convBlockSizeValid(P) ⟹ 2P は 32 の倍数。
//   3. 1 と 2 から、要素数 n の倍数で進む派生ポインタ (FDL の枠 idx·n、分割スペクトルの
//      k·n、data 末尾の m−n) はすべて n·4 = 128 バイトの倍数だけずれる → 整列は保たれる。
// ハーネス 29 節がこの不変条件 (派生ポインタの実測 + 崩したときに拒否されること) を釘付け。
inline bool fftAligned(const void* p) {
    return (reinterpret_cast<size_t>(p) & 15u) == 0;
}

class FftPlan {
public:
    FftPlan() = default;
    ~FftPlan() { release(); }
    FftPlan(const FftPlan&) = delete;
    FftPlan& operator=(const FftPlan&) = delete;

    // 制御スレッド専用。内部で malloc する (pffft_new_setup)。不正な n は false。
    bool init(int n);

    // 制御スレッド専用。呼び出し側のメモリ (fftSetupBytes(n) 以上、16 B 整列) に
    // 組み立てる。**確保しない。**メモリの寿命は呼び出し側が持つ (release() は触らない)。
    bool initInPlace(int n, void* mem, size_t bytes);

    // init() で作った分だけ解放する。initInPlace() の setup はポインタを忘れるだけ。
    void release();

    bool ready() const { return setup_ != nullptr; }
    int  size() const { return n_; }

    // --- 実 FFT (順序付き)。最小位相化の経路 ---------------------------------
    // in == out でよい (pffft が work 経由で処理する)。work は n floats。
    void forwardOrdered(const float* in, float* out, float* work) const;
    void inverseOrdered(const float* in, float* out, float* work) const;

    // --- 実 FFT (順序なし)。畳み込みの経路 -----------------------------------
    // 出力は内部順。convolveAccumulate / inverse へそのまま渡す。in == out 可。
    void forward(const float* in, float* out, float* work) const;
    void inverse(const float* in, float* out, float* work) const;

    // acc += (a · b) · scaling。a / b は forward (順序なし) の出力。
    void convolveAccumulate(const float* a, const float* b, float* acc, float scaling) const;

private:
    void* setup_ = nullptr;  // PFFFT_Setup*。pffft.h をここに晒さない
    int   n_     = 0;
    bool  owned_ = false;    // init() で作った (= release() で破棄する) か
};

}  // namespace caeq

#endif  // CA_EQ_FFT_H_
