// vendored PFFFT (dsp/pffft/) の薄い覆い。設計根拠は eq-dsp-internals.md §1。
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

#if defined(__FAST_MATH__)
#error "ca_eq_fft は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

bool fftSizeValid(int n);

inline bool convBlockSizeValid(int p) {
    return p > 0 && p <= (1 << 25) && fftSizeValid(2 * p);
}

size_t fftSetupBytes(int n);

// ⚠️ 整列の不変条件 (eq-dsp-internals.md §1)。崩れると PFFFT の assert に当たり
// audio HAL が abort() する = 端末全体が無音になる。ハーネス「29. 整列の不変条件」が釘付け。
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
