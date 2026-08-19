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

    struct Buffers {
        float* fdl     = nullptr;
        float* filt[2] = {nullptr, nullptr};
        float* tail    = nullptr;
        float* stage   = nullptr;
        float* acc     = nullptr;
        float* acc2    = nullptr;
        float* work    = nullptr;
    };

    bool bind(const FftPlan* plan, int block, int taps, int channels, const Buffers& b);

    void reset();

    void unbind() { bound_ = false; }

    bool ready() const { return bound_ && fill_ >= k_total_; }
    bool bound() const { return bound_; }
    int  partitions() const { return k_total_; }
    int  fill() const { return fill_; }
    uint32_t scrubbedSamples() const { return scrubbed_; }

    void pushBlock(const float* in);

    void processBlock(const float* in, float* out, int face, int fade_face, float fade_w0,
                      float fade_dw);

private:
    void pushInput(const float* in);
    void macFace(int ch_index, int face, float* acc) const;

    const FftPlan* plan_ = nullptr;
    Buffers b_{};
    int  block_   = 0;
    int  n_       = 0;
    int  taps_    = 0;
    int  ch_      = 0;
    int  k_total_ = 0;
    int  head_    = 0;
    int  fill_    = 0;
    bool bound_   = false;
    uint32_t scrubbed_ = 0;
};

}

#endif
