#include "ca_eq_fft.h"

#include "pffft/pffft.h"

namespace caeq {

bool fftSizeValid(int n) {
    if (n <= 0 || n > (1 << 26)) return false;
    if (n % 32 != 0) return false;
    while (n % 2 == 0) n /= 2;
    while (n % 3 == 0) n /= 3;
    while (n % 5 == 0) n /= 5;
    return n == 1;
}

size_t fftSetupBytes(int n) {
    if (!fftSizeValid(n)) return 0;
    return pffft_setup_bytes(n, PFFFT_REAL);
}

bool FftPlan::init(int n) {
    if (!fftSizeValid(n)) return false;
    release();
    PFFFT_Setup* s = pffft_new_setup(n, PFFFT_REAL);
    if (s == nullptr) return false;
    setup_ = s;
    n_     = n;
    owned_ = true;
    return true;
}

bool FftPlan::initInPlace(int n, void* mem, size_t bytes) {
    if (!fftSizeValid(n) || mem == nullptr || !fftAligned(mem)) return false;
    if (bytes < pffft_setup_bytes(n, PFFFT_REAL)) return false;
    release();
    PFFFT_Setup* s = pffft_new_setup_inplace(n, PFFFT_REAL, mem);
    if (s == nullptr) return false;
    setup_ = s;
    n_     = n;
    owned_ = false;
    return true;
}

void FftPlan::release() {
    if (setup_ != nullptr && owned_) {
        pffft_destroy_setup(static_cast<PFFFT_Setup*>(setup_));
    }
    setup_ = nullptr;
    n_     = 0;
    owned_ = false;
}

void FftPlan::forwardOrdered(const float* in, float* out, float* work) const {
    if (setup_ == nullptr || work == nullptr) return;
    pffft_transform_ordered(static_cast<PFFFT_Setup*>(setup_), in, out, work, PFFFT_FORWARD);
}

void FftPlan::inverseOrdered(const float* in, float* out, float* work) const {
    if (setup_ == nullptr || work == nullptr) return;
    pffft_transform_ordered(static_cast<PFFFT_Setup*>(setup_), in, out, work, PFFFT_BACKWARD);
}

void FftPlan::forward(const float* in, float* out, float* work) const {
    if (setup_ == nullptr || work == nullptr) return;
    pffft_transform(static_cast<PFFFT_Setup*>(setup_), in, out, work, PFFFT_FORWARD);
}

void FftPlan::inverse(const float* in, float* out, float* work) const {
    if (setup_ == nullptr || work == nullptr) return;
    pffft_transform(static_cast<PFFFT_Setup*>(setup_), in, out, work, PFFFT_BACKWARD);
}

void FftPlan::convolveAccumulate(const float* a, const float* b, float* acc,
                                 float scaling) const {
    if (setup_ == nullptr) return;
    pffft_zconvolve_accumulate(static_cast<PFFFT_Setup*>(setup_), a, b, acc, scaling);
}

}  // namespace caeq
