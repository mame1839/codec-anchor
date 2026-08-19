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

inline bool fftAligned(const void* p) {
    return (reinterpret_cast<size_t>(p) & 15u) == 0;
}

class FftPlan {
public:
    FftPlan() = default;
    ~FftPlan() { release(); }
    FftPlan(const FftPlan&) = delete;
    FftPlan& operator=(const FftPlan&) = delete;

    bool init(int n);

    bool initInPlace(int n, void* mem, size_t bytes);

    void release();

    bool ready() const { return setup_ != nullptr; }
    int  size() const { return n_; }

    void forwardOrdered(const float* in, float* out, float* work) const;
    void inverseOrdered(const float* in, float* out, float* work) const;

    void forward(const float* in, float* out, float* work) const;
    void inverse(const float* in, float* out, float* work) const;

    void convolveAccumulate(const float* a, const float* b, float* acc, float scaling) const;

private:
    void* setup_ = nullptr;
    int   n_     = 0;
    bool  owned_ = false;
};

}

#endif
