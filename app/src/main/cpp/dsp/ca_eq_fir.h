#ifndef CA_EQ_FIR_H_
#define CA_EQ_FIR_H_

#include <cstdint>

#include "ca_eq_conv.h"
#include "ca_eq_curve.h"
#include "ca_eq_fft.h"

namespace caeq {

inline int firTapsFor(double fs) { return fs >= 64000.0 ? 16384 : 8192; }

inline int firDefaultM(double fs) { return firTapsFor(fs) * 2; }

enum class FirWindow : uint8_t {
    kHalfHann  = 0,
    kRect      = 1,
    kTailTaper = 2,
};

inline int firDefaultTaper(int taps) { return taps / 8; }

namespace fircost {

inline constexpr int64_t kResampleNsPerBin = 60;
inline constexpr int64_t kFoldNsPerElem    = 4;
inline constexpr int64_t kExpNsPerBin      = 40;
inline constexpr int64_t kWindowNsPerTap   = 30;
inline constexpr int64_t kCopyNsPerElem    = 4;

inline constexpr double kFftNsPerUnit = 0.40;

inline int64_t fftNs(int n) {
    int lg = 0;
    for (int v = n; v > 1; v >>= 1) lg++;
    return static_cast<int64_t>(kFftNsPerUnit * static_cast<double>(n) *
                                static_cast<double>(lg));
}

inline int64_t maxQuantumNs(int m) { return fftNs(m); }

}

struct FirDesignSpec {
    const float* curve_db = nullptr;
    double    fs     = 48000.0;
    int       taps   = 8192;
    int       m      = 16384;
    int       block  = 0;
    FirWindow window = FirWindow::kTailTaper;
    int       taper  = 1024;
};

class FirDesigner {
public:
    FirDesigner() = default;
    FirDesigner(const FirDesigner&) = delete;
    FirDesigner& operator=(const FirDesigner&) = delete;

    enum class Phase : uint8_t {
        kIdle = 0,
        kResample,
        kCepFft,
        kFold,
        kSpecFft,
        kExp,
        kIrFft,
        kWindow,
        kPartition,
        kDone,
    };

    bool start(const FirDesignSpec& spec, const FftPlan* plan_m, const FftPlan* plan_2p,
               float* data, float* work, float* filt);

    bool step(int64_t budget_ns);

    void abort();

    bool running() const { return phase_ != Phase::kIdle && phase_ != Phase::kDone; }
    bool done() const { return phase_ == Phase::kDone && !failed_; }
    bool failed() const { return failed_; }
    Phase phase() const { return phase_; }
    int  partitions() const { return k_total_; }

    int64_t lastStepModelNs() const { return last_step_ns_; }

#ifdef CA_EQ_DSP_TEST_HOOKS
    void injectNonFinite() { inject_nan_ = true; }
#endif

    const float* ir() const { return data_; }

private:
    void resampleRange(int from, int to);
    void foldRange(int from, int to);
    void expRange(int from, int to);
    void windowRange(int from, int to);
    void partitionOne(int k);

    FirDesignSpec  spec_{};
    const FftPlan* plan_m_  = nullptr;
    const FftPlan* plan_2p_ = nullptr;
    float* data_ = nullptr;
    float* work_ = nullptr;
    float* filt_ = nullptr;

    Phase phase_   = Phase::kIdle;
    bool  failed_  = false;
    int   pos_     = 0;
    int   k_total_ = 0;
    int64_t last_step_ns_ = 0;
#ifdef CA_EQ_DSP_TEST_HOOKS
    bool  inject_nan_ = false;
#endif
};

}

#endif
