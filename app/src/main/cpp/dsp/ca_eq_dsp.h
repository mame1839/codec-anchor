#ifndef CA_EQ_DSP_H_
#define CA_EQ_DSP_H_

#include <cstdint>

#if defined(__FAST_MATH__)
#error "ca_eq_dsp は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

inline constexpr int kMaxBands    = 32;
inline constexpr int kMaxChannels = 32;

inline constexpr int kChunkFrames = 32;

inline constexpr int kDefaultCoefStride = 1;

inline constexpr double kMinFcHz   = 1.0;
inline constexpr double kMinQ      = 0.1;
inline constexpr double kMaxQ      = 40.0;
inline constexpr double kMaxGainDb = 40.0;
inline constexpr double kMinPreampDb = -40.0;
inline constexpr double kMaxPreampDb = 12.0;

inline constexpr double kValidFcRatio = 0.49;
inline constexpr double kMaxFcRatio = 0.4995;

inline constexpr double kDenormalFloor = 1e-200;

enum class BandType : uint8_t {
    kPeaking   = 0,
    kLowShelf  = 1,
    kHighShelf = 2,
};

enum class Structure : uint8_t {
    kTdf2 = 0,
    kSvf  = 1,
};

enum class Interp : uint8_t {
    kCoef  = 0,
    kParam = 1,
};

inline constexpr Structure kDefaultStructure = Structure::kTdf2;
inline constexpr Interp    kDefaultInterp    = Interp::kCoef;

struct Band {
    BandType type    = BandType::kPeaking;
    double   fc      = 1000.0;
    double   q       = 1.0;
    double   gain_db = 0.0;
};

struct Params {
    int    band_count = 0;
    double preamp_db  = 0.0;
    Band   bands[kMaxBands] = {};
};

struct Coef {
    double c[6] = {0, 0, 0, 0, 0, 0};
};

Coef designTdf2(const Band& band, double sample_rate);

Coef designSvf(const Band& band, double sample_rate);

Coef design(const Band& band, double sample_rate, Structure structure);

Coef unityFor(const Band& band, double sample_rate, Structure structure);

bool validate(const Params& p, double sample_rate);

class Eq {
public:
    Eq();

    void configure(double sample_rate, int channels, Structure structure);

    void setInterp(Interp mode) { interp_ = mode; }
    Interp interp() const { return interp_; }

    void setRampMillis(double ms);
    double rampMillis() const;

    void setFadeMillis(double ms);
    double fadeMillis() const { return fade_ms_; }

    void setCoefStride(int frames);
    int  coefStride() const { return coef_stride_; }

    bool setParams(const Params& p);

    bool snapParams(const Params& p);

    void setActive(bool active);
    bool active() const { return active_; }
    bool idle() const { return !active_ && wet_cur_ == 0.0; }

    void reset();

    void warmUp();

    void process(const float* in, float* out, int frames, bool accumulate = false);

    bool     ramping() const { return ramp_pos_ < ramp_len_; }
    uint32_t rejectedCount() const { return rejected_; }
    uint32_t scrubbedBlocks() const { return scrubbed_; }
    uint32_t stateResets() const { return state_resets_; }
    uint32_t denormalFlushes() const { return denormal_flushes_; }
    int      activeBands() const { return nb_run_; }
    double   sampleRate() const { return sr_; }
    int      channels() const { return ch_; }
    Structure structure() const { return structure_; }
    double   stateMagnitude() const;

#ifdef CA_EQ_DSP_TEST_HOOKS
    void injectState(double v);
#endif

private:
    void applyPending();
    void updateRampCoef();
    void finishRamp();
    double processChunk(const float* in, float* out, int n, bool accumulate);
    void clearState();
    void clearBandState(int band);
    void rebuildFromParams();

    double    sr_        = 48000.0;
    int       ch_        = 2;
    Structure structure_ = kDefaultStructure;
    Interp    interp_    = kDefaultInterp;

    Params  params_{};
    Params  pending_{};
    bool    has_pending_ = false;

    int     nb_run_    = 0;
    int     nb_target_ = 0;

    Coef    from_[kMaxBands]{};
    Coef    to_[kMaxBands]{};
    Coef    cur_[kMaxBands]{};
    Band    band_from_[kMaxBands]{};
    Band    band_to_[kMaxBands]{};
    Band    band_cur_[kMaxBands]{};

    double  pre_from_ = 1.0;
    double  pre_to_   = 1.0;
    double  pre_cur_  = 1.0;

    int64_t ramp_len_    = 480;
    int64_t ramp_pos_    = 480;
    int     coef_stride_ = kDefaultCoefStride;

    bool    active_    = false;
    double  wet_cur_   = 0.0;
    double  wet_target_ = 0.0;
    double  wet_step_  = 1.0;
    double  fade_ms_   = 10.0;

    double  s1_[kMaxBands * kMaxChannels]{};
    double  s2_[kMaxBands * kMaxChannels]{};
    double  scratch_[kChunkFrames * kMaxChannels]{};
    double  dry_[kChunkFrames * kMaxChannels]{};

    uint32_t rejected_         = 0;
    uint32_t scrubbed_         = 0;
    uint32_t state_resets_     = 0;
    uint32_t denormal_flushes_ = 0;
};

}

#endif
