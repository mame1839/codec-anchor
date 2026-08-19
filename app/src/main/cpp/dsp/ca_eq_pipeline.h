#ifndef CA_EQ_PIPELINE_H_
#define CA_EQ_PIPELINE_H_

#include <cstdint>

#include "ca_eq_conv.h"
#include "ca_eq_curve.h"
#include "ca_eq_dsp.h"
#include "ca_eq_fir.h"

namespace caeq {

inline constexpr int kMaxConvBlock = 4096;

inline constexpr unsigned kDeclaredMemoryKb = 64;

inline constexpr double kSliceBudgetFrac = 0.25;

class EqPipeline {
public:
    EqPipeline() = default;
    ~EqPipeline();
    EqPipeline(const EqPipeline&) = delete;
    EqPipeline& operator=(const EqPipeline&) = delete;

    enum class FirState : uint8_t {
        kBiquad  = 0,
        kPrepare = 1,
        kFadeIn  = 2,
        kFir     = 3,
        kFadeOut = 4,
    };

    void configure(double sample_rate, int channels, Structure structure);

    void setFirCapable(bool capable);

    bool setParams(const Params& p);
    bool snapParams(const Params& p);
    void setActive(bool active);
    void reset();
    void warmUp();

    void setClock(uint64_t (*now_ns)());

    Eq& biquad() { return eq_; }
    const Eq& biquad() const { return eq_; }

    void setFirEnabled(bool enabled);

    void setCurve(const float* db401, uint32_t generation);

    void process(const float* in, float* out, int frames, bool accumulate = false);

    FirState firState() const { return state_; }
    bool     idle() const;
    int      fdlFill() const { return kernel_.fill(); }
    int      fdlPartitions() const { return kernel_.partitions(); }
    bool     blockOk() const { return p_ok_; }
    uint32_t rebuilds() const { return rebuilds_; }
    uint32_t faceFades() const { return face_fades_; }
    uint32_t fallbacks() const { return fallbacks_; }
    uint32_t modeOffs() const { return mode_offs_; }
    uint32_t designFailures() const { return design_failures_; }
    uint32_t unfitSizeCount() const { return unfit_size_; }
    uint32_t unfitBudgetCount() const { return unfit_budget_; }
    uint32_t curveRejected() const { return curve_rejected_; }
    bool     firCapable() const { return fir_capable_; }
    bool     curveFailed() const { return curve_failed_; }
    uint32_t scrubbedSamples() const { return kernel_.scrubbedSamples(); }
    uint64_t maxSliceNs() const { return max_slice_ns_; }
    uint32_t curveGeneration() const { return active_gen_; }
    bool     firAvailable() const { return arena_ != nullptr; }
    size_t   arenaBytes() const { return arena_bytes_; }
    int      designTaps() const { return taps_; }
    int      designM() const { return m_; }

#ifdef CA_EQ_DSP_TEST_HOOKS
    double firWet() const { return fir_wet_; }
    FirDesigner& designerForTest() { return designer_; }
    const void* arenaBaseForTest() const { return arena_; }
#endif

private:
    enum class Drop : uint8_t {
        kHard,
        kHandoff,
        kQuiet,
    };

    void releaseArena();
    void reserveArena();
    void reserveForCurrent();
    void invalidateFir(Drop reason);
    bool evaluateBlock(int frames);
    void adoptCurveIfDirty();
    void startDesigner(int face);
    void stepPrepare(int frames);
    void stepDesigner();
    double fadeStep() const;
    void mixFirOut(const float* in, float* out, int frames, bool accumulate,
                   const float* bq, double mix0, double dmix);

    Eq eq_;
    FirKernel   kernel_;
    FirDesigner designer_;
    FftPlan     plan_m_;
    FftPlan     plan_2p_;

    void*  arena_       = nullptr;
    size_t arena_bytes_ = 0;
    void*  mem_setup_m_  = nullptr;
    void*  mem_setup_2p_ = nullptr;
    size_t bytes_setup_2p_ = 0;
    float* buf_data_  = nullptr;
    float* buf_work_  = nullptr;
    float* buf_fdl_   = nullptr;
    float* buf_filt_[2] = {nullptr, nullptr};
    float* buf_tail_  = nullptr;
    float* buf_stage_ = nullptr;
    float* buf_acc_   = nullptr;
    float* buf_acc2_  = nullptr;
    float* buf_fir_y_ = nullptr;
    float* buf_bq_y_  = nullptr;

    double fs_   = 0.0;
    int    ch_   = 0;
    int    taps_ = 0;
    int    m_    = 0;

    FirState state_ = FirState::kBiquad;
    bool fir_enabled_ = false;
    bool fir_capable_ = true;
    bool p_ok_        = false;
    int  p_cur_       = 0;
    int64_t budget_ns_ = 0;

    float    pending_curve_[kCurvePoints] = {};
    float    active_curve_[kCurvePoints]  = {};
    uint32_t pending_gen_  = 0;
    uint32_t adopting_gen_ = 0;
    uint32_t active_gen_   = 0;
    bool     curve_dirty_  = false;
    bool     have_curve_   = false;
    bool     curve_failed_ = false;

    int  active_face_    = 0;
    bool face_fading_    = false;
    bool tail_warmed_    = false;
    double fir_mix_ = 0.0;
    double face_w_  = 0.0;

    bool   active_     = false;
    double fir_wet_    = 0.0;
    double pre_cur_  = 1.0;
    double pre_from_ = 1.0;
    double pre_to_   = 1.0;
    int64_t pre_pos_ = 0;
    int64_t pre_len_ = 0;

    uint64_t (*now_ns_)() = nullptr;
    uint64_t max_slice_ns_ = 0;

    uint32_t rebuilds_        = 0;
    uint32_t face_fades_      = 0;
    uint32_t fallbacks_       = 0;
    uint32_t mode_offs_       = 0;
    uint32_t design_failures_ = 0;
    uint32_t unfit_size_      = 0;
    uint32_t unfit_budget_    = 0;
    uint32_t curve_rejected_  = 0;
};

}

#endif
