#include "ca_eq_pipeline.h"

#include <cmath>
#include <cstring>
#include <new>

namespace caeq {
namespace {

constexpr double kFaceFadeMs = 10.0;

inline size_t align64(size_t v) { return (v + 63u) & ~static_cast<size_t>(63u); }

inline double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

constexpr int64_t kSetupNsPerElem = 120;
inline int64_t setupBuildNs(int n) { return static_cast<int64_t>(n) * kSetupNsPerElem; }

}

double EqPipeline::fadeStep() const {
    double n = eq_.fadeMillis() * fs_ / 1000.0;
    if (n < 1.0) n = 1.0;
    return 1.0 / n;
}

EqPipeline::~EqPipeline() { releaseArena(); }

void EqPipeline::releaseArena() {
    invalidateFir(Drop::kHard);
    kernel_.unbind();
    plan_m_.release();
    plan_2p_.release();
    if (arena_ != nullptr) {
        ::operator delete(arena_, std::align_val_t(64));
        arena_ = nullptr;
    }
    arena_bytes_ = 0;
    mem_setup_m_  = nullptr;
    mem_setup_2p_ = nullptr;
    bytes_setup_2p_ = 0;
    buf_data_    = nullptr;
    buf_work_    = nullptr;
    buf_fdl_     = nullptr;
    buf_filt_[0] = nullptr;
    buf_filt_[1] = nullptr;
    buf_tail_    = nullptr;
    buf_stage_   = nullptr;
    buf_acc_     = nullptr;
    buf_acc2_    = nullptr;
    buf_fir_y_   = nullptr;
    buf_bq_y_    = nullptr;
    have_curve_  = false;
    curve_dirty_ = false;
}

static size_t worstPartitionFloats(int taps) {
    size_t worst = 0;
    for (int p = 16; p <= kMaxConvBlock; p += 16) {
        if (!convBlockSizeValid(p)) continue;
        const size_t kn = static_cast<size_t>(firPartitions(taps, p)) *
                          static_cast<size_t>(2 * p);
        if (kn > worst) worst = kn;
    }
    return worst;
}

void EqPipeline::reserveArena() {
    const size_t setup_m_bytes  = fftSetupBytes(m_);
    const size_t setup_2p_bytes = fftSetupBytes(2 * kMaxConvBlock);
    const size_t max_n  = static_cast<size_t>(2 * kMaxConvBlock);
    const size_t max_kn = worstPartitionFloats(taps_);
    const size_t chs    = static_cast<size_t>(ch_);
    const size_t blk    = static_cast<size_t>(kMaxConvBlock);

    size_t off = 0;
    auto carve = [&off](size_t bytes) {
        const size_t at = align64(off);
        off = at + bytes;
        return at;
    };
    const size_t o_setup_m  = carve(setup_m_bytes);
    const size_t o_setup_2p = carve(setup_2p_bytes);
    const size_t o_data  = carve(sizeof(float) * static_cast<size_t>(m_));
    const size_t o_work  = carve(sizeof(float) * static_cast<size_t>(m_));
    const size_t o_fdl   = carve(sizeof(float) * chs * max_kn);
    const size_t o_filt0 = carve(sizeof(float) * max_kn);
    const size_t o_filt1 = carve(sizeof(float) * max_kn);
    const size_t o_tail  = carve(sizeof(float) * chs * blk);
    const size_t o_stage = carve(sizeof(float) * max_n);
    const size_t o_acc   = carve(sizeof(float) * max_n);
    const size_t o_acc2  = carve(sizeof(float) * max_n);
    const size_t o_fir_y = carve(sizeof(float) * chs * blk);
    const size_t o_bq_y  = carve(sizeof(float) * chs * blk);
    const size_t total   = align64(off);

    arena_ = ::operator new(total, std::align_val_t(64), std::nothrow);
    if (arena_ == nullptr) {
        arena_bytes_ = 0;
        return;
    }
    arena_bytes_ = total;
    char* base = static_cast<char*>(arena_);
    mem_setup_m_    = base + o_setup_m;
    mem_setup_2p_   = base + o_setup_2p;
    bytes_setup_2p_ = setup_2p_bytes;
    buf_data_    = reinterpret_cast<float*>(base + o_data);
    buf_work_    = reinterpret_cast<float*>(base + o_work);
    buf_fdl_     = reinterpret_cast<float*>(base + o_fdl);
    buf_filt_[0] = reinterpret_cast<float*>(base + o_filt0);
    buf_filt_[1] = reinterpret_cast<float*>(base + o_filt1);
    buf_tail_    = reinterpret_cast<float*>(base + o_tail);
    buf_stage_   = reinterpret_cast<float*>(base + o_stage);
    buf_acc_     = reinterpret_cast<float*>(base + o_acc);
    buf_acc2_    = reinterpret_cast<float*>(base + o_acc2);
    buf_fir_y_   = reinterpret_cast<float*>(base + o_fir_y);
    buf_bq_y_    = reinterpret_cast<float*>(base + o_bq_y);

    if (!plan_m_.initInPlace(m_, mem_setup_m_, setup_m_bytes)) {
        releaseArena();
    }
}

void EqPipeline::reserveForCurrent() {
    taps_ = firTapsFor(fs_);
    m_    = firDefaultM(fs_);
    reserveArena();
    pre_len_ = static_cast<int64_t>(eq_.rampMillis() * fs_ / 1000.0 + 0.5);
    p_cur_ = 0;
    p_ok_  = false;
}

void EqPipeline::configure(double sample_rate, int channels, Structure structure) {
    eq_.configure(sample_rate, channels, structure);
    if (!std::isfinite(sample_rate) || sample_rate <= 0.0) return;
    int ch = channels;
    if (ch < 1) ch = 1;

    if (ch > 2 || !fir_capable_) {
        releaseArena();
        fs_ = sample_rate;
        ch_ = ch;
        return;
    }
    if (arena_ != nullptr && sample_rate == fs_ && ch == ch_) {
        return;
    }
    releaseArena();
    fs_ = sample_rate;
    ch_ = ch;
    reserveForCurrent();
}

void EqPipeline::setFirCapable(bool capable) {
    if (capable == fir_capable_) return;
    fir_capable_ = capable;
    if (!capable) {
        invalidateFir(Drop::kHandoff);
        releaseArena();
        return;
    }
    if (fs_ > 0.0 && ch_ >= 1 && ch_ <= 2 && arena_ == nullptr) reserveForCurrent();
}

bool EqPipeline::setParams(const Params& p) {
    const bool ok = eq_.setParams(p);
    if (ok) {
        pre_from_ = pre_cur_;
        pre_to_   = std::pow(10.0, p.preamp_db / 20.0);
        pre_pos_  = 0;
    }
    return ok;
}

bool EqPipeline::snapParams(const Params& p) {
    const bool ok = eq_.snapParams(p);
    if (ok) {
        pre_cur_ = pre_from_ = pre_to_ = std::pow(10.0, p.preamp_db / 20.0);
        pre_pos_ = pre_len_;
    }
    return ok;
}

void EqPipeline::setActive(bool active) {
    active_ = active;
    if (state_ == FirState::kBiquad || state_ == FirState::kPrepare ||
        state_ == FirState::kFadeOut) {
        eq_.setActive(active);
    }
}

void EqPipeline::reset() {
    eq_.reset();
    invalidateFir(Drop::kHard);
}

void EqPipeline::warmUp() {
    eq_.warmUp();
    if (arena_ != nullptr) {
        volatile char* p = static_cast<volatile char*>(arena_);
        for (size_t i = 0; i < arena_bytes_; i += 4096) p[i] = p[i];
    }
}

void EqPipeline::setClock(uint64_t (*now_ns)()) { now_ns_ = now_ns; }

void EqPipeline::setFirEnabled(bool enabled) { fir_enabled_ = enabled; }

void EqPipeline::setCurve(const float* db401, uint32_t generation) {
    if (db401 == nullptr) return;
    if (generation == pending_gen_ && have_curve_) return;
    std::memcpy(pending_curve_, db401, sizeof(pending_curve_));
    pending_gen_ = generation;
    curve_dirty_ = true;
}

bool EqPipeline::idle() const {
    if (state_ == FirState::kBiquad) return eq_.idle();
    return !active_ && fir_wet_ == 0.0 && eq_.idle();
}

void EqPipeline::invalidateFir(Drop reason) {
    const bool was_audible = (state_ == FirState::kFadeIn || state_ == FirState::kFir ||
                              state_ == FirState::kFadeOut);
    if (reason == Drop::kHard && was_audible) fallbacks_++;
    if (reason != Drop::kQuiet && was_audible) {
        if (state_ == FirState::kFir) eq_.reset();
        eq_.setActive(active_);
    }
    kernel_.reset();
    designer_.abort();
    tail_warmed_ = false;
    face_fading_ = false;
    fir_mix_     = 0.0;
    face_w_      = 0.0;
    active_gen_  = 0;
    state_       = FirState::kBiquad;
}

bool EqPipeline::evaluateBlock(int frames) {
    if (state_ != FirState::kBiquad) invalidateFir(Drop::kHard);
    p_cur_ = frames;
    p_ok_  = false;
    if (arena_ == nullptr) return false;
    if (frames > kMaxConvBlock || !convBlockSizeValid(frames)) {
        unfit_size_++;
        return false;
    }
    budget_ns_ = static_cast<int64_t>(kSliceBudgetFrac * static_cast<double>(frames) /
                                      fs_ * 1e9);
    const int64_t q_fft   = fircost::maxQuantumNs(m_);
    const int64_t q_setup = setupBuildNs(2 * frames);
    const int64_t q_max   = q_fft > q_setup ? q_fft : q_setup;
    if (q_max > budget_ns_) {
        unfit_budget_++;
        return false;
    }
    p_ok_ = true;
    return true;
}

void EqPipeline::adoptCurveIfDirty() {
    if (!curve_dirty_ || face_fading_) return;
    curve_dirty_ = false;
    if (!curveValid(pending_curve_)) {
        curve_rejected_++;
        return;
    }
    std::memcpy(active_curve_, pending_curve_, sizeof(active_curve_));
    adopting_gen_ = pending_gen_;
    have_curve_   = true;
    curve_failed_ = false;
    const int face = (state_ == FirState::kFir || state_ == FirState::kFadeIn ||
                      state_ == FirState::kFadeOut)
                         ? 1 - active_face_
                         : active_face_;
    startDesigner(face);
}

void EqPipeline::stepDesigner() {
    if (!designer_.running()) return;
    const uint64_t t0 = now_ns_ != nullptr ? now_ns_() : 0;
    designer_.step(budget_ns_);
    if (now_ns_ != nullptr) {
        const uint64_t dt = now_ns_() - t0;
        if (dt > max_slice_ns_) max_slice_ns_ = dt;
    }
    if (designer_.failed()) {
        designer_.abort();
        design_failures_++;
        curve_failed_ = true;
    }
}

void EqPipeline::startDesigner(int face) {
    FirDesignSpec spec;
    spec.curve_db = active_curve_;
    spec.fs       = fs_;
    spec.taps     = taps_;
    spec.m        = m_;
    spec.block    = p_cur_;
    spec.window   = FirWindow::kTailTaper;
    spec.taper    = firDefaultTaper(taps_);
    designer_.abort();
    if (designer_.start(spec, &plan_m_, &plan_2p_, buf_data_, buf_work_, buf_filt_[face])) {
        rebuilds_++;
    }
}

void EqPipeline::stepPrepare(int frames) {
    const uint64_t t0 = now_ns_ != nullptr ? now_ns_() : 0;

    if (!plan_2p_.ready() || plan_2p_.size() != 2 * frames) {
        if (plan_2p_.initInPlace(2 * frames, mem_setup_2p_, bytes_setup_2p_)) {
            FirKernel::Buffers kb;
            kb.fdl     = buf_fdl_;
            kb.filt[0] = buf_filt_[0];
            kb.filt[1] = buf_filt_[1];
            kb.tail    = buf_tail_;
            kb.stage   = buf_stage_;
            kb.acc     = buf_acc_;
            kb.acc2    = buf_acc2_;
            kb.work    = buf_work_;
            if (!kernel_.bind(&plan_2p_, frames, taps_, ch_, kb)) {
                p_ok_ = false;
                unfit_size_++;
            }
        } else {
            p_ok_ = false;
            unfit_size_++;
        }
    } else if (designer_.running()) {
        stepDesigner();
    } else if (!designer_.done()) {
        adoptCurveIfDirty();
        if (!designer_.running() && have_curve_ && !curve_failed_) {
            startDesigner(active_face_);
        }
    }
    if (designer_.running() || designer_.done()) {
        state_ = FirState::kPrepare;
    } else if (curve_failed_ || !have_curve_) {
        state_ = FirState::kBiquad;
    }

    if (now_ns_ != nullptr) {
        const uint64_t dt = now_ns_() - t0;
        if (dt > max_slice_ns_) max_slice_ns_ = dt;
    }
}

void EqPipeline::mixFirOut(const float* in, float* out, int frames, bool accumulate,
                           const float* bq, double mix0, double dmix) {
    const int ch = ch_;
    for (int i = 0; i < frames; i++) {
        if (pre_pos_ < pre_len_) {
            const double u = static_cast<double>(pre_pos_) / static_cast<double>(pre_len_);
            pre_cur_ = pre_from_ + (pre_to_ - pre_from_) * u;
            pre_pos_++;
        } else {
            pre_cur_ = pre_to_;
        }
        const double wet    = fir_wet_;
        const double target = active_ ? 1.0 : 0.0;
        const double step   = fadeStep();
        if (fir_wet_ < target) {
            fir_wet_ += step;
            if (fir_wet_ > target) fir_wet_ = target;
        } else if (fir_wet_ > target) {
            fir_wet_ -= step;
            if (fir_wet_ < target) fir_wet_ = target;
        }
        const double mix   = clamp01(mix0 + static_cast<double>(i) * dmix);
        const double share = wet * mix;

        const size_t base = static_cast<size_t>(i) * static_cast<size_t>(ch);
        for (int c = 0; c < ch; c++) {
            const size_t at  = base + static_cast<size_t>(c);
            const float raw  = in[at];
            const double dry = std::isfinite(raw) ? static_cast<double>(raw) : 0.0;
            const double y   = static_cast<double>(buf_fir_y_[at]) * pre_cur_;
            const double eq_out = (bq != nullptr) ? static_cast<double>(bq[at]) : dry;
            const double mixed  = eq_out + (y - dry) * share;
            if (accumulate) {
                out[at] += static_cast<float>(mixed);
            } else {
                out[at] = static_cast<float>(mixed);
            }
        }
    }
}

void EqPipeline::process(const float* in, float* out, int frames, bool accumulate) {
    if (in == nullptr || out == nullptr || frames <= 0) return;

    if (frames != p_cur_) evaluateBlock(frames);

    const bool can_hold = arena_ != nullptr && p_ok_;
    const bool want_fir = can_hold && fir_enabled_;
    const bool curve_usable = (have_curve_ && !curve_failed_) || curve_dirty_;
    const bool can_start    = want_fir && active_ && curve_usable;

    if (!can_hold && state_ != FirState::kBiquad) invalidateFir(Drop::kHard);
    if (can_hold && !fir_enabled_) {
        if (state_ == FirState::kFir || state_ == FirState::kFadeIn) {
            mode_offs_++;
            eq_.reset();
            eq_.setActive(active_);
            state_  = FirState::kFadeOut;
        } else if (state_ == FirState::kPrepare) {
            invalidateFir(Drop::kQuiet);
        }
    }

    switch (state_) {
    case FirState::kBiquad:
    case FirState::kPrepare: {
        eq_.process(in, out, frames, accumulate);
        const double target = active_ ? 1.0 : 0.0;
        const double step   = fadeStep() * static_cast<double>(frames);
        if (fir_wet_ < target) fir_wet_ = fir_wet_ + step > target ? target : fir_wet_ + step;
        if (fir_wet_ > target) fir_wet_ = fir_wet_ - step < target ? target : fir_wet_ - step;

        if (state_ == FirState::kPrepare) {
            if (!can_start) {
                invalidateFir(Drop::kQuiet);
                break;
            }
            adoptCurveIfDirty();
            if (designer_.done() && kernel_.fill() >= kernel_.partitions() - 1 &&
                !tail_warmed_) {
                kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
                tail_warmed_ = true;
            } else {
                kernel_.pushBlock(in);
                stepPrepare(frames);
            }
            if (designer_.done() && kernel_.ready() && tail_warmed_) {
                designer_.abort();
                fir_mix_ = 0.0;
                eq_.setActive(false);
                state_ = FirState::kFadeIn;
            }
        } else if (can_start) {
            stepPrepare(frames);
        }
        break;
    }
    case FirState::kFadeIn:
    case FirState::kFadeOut: {
        const bool in_ = state_ == FirState::kFadeIn;
        eq_.process(in, buf_bq_y_, frames, false);
        kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        const double d = (in_ ? 1.0 : -1.0) * fadeStep();
        mixFirOut(in, out, frames, accumulate, buf_bq_y_, fir_mix_, d);
        fir_mix_ += d * static_cast<double>(frames);
        if (in_ && fir_mix_ >= 1.0) {
            fir_mix_ = 1.0;
            if (eq_.idle()) {
                eq_.reset();
                active_gen_ = adopting_gen_;
                state_      = FirState::kFir;
            }
        } else if (!in_ && fir_mix_ <= 0.0) {
            invalidateFir(Drop::kQuiet);
        }
        break;
    }
    case FirState::kFir: {
        adoptCurveIfDirty();
        stepDesigner();
        if (designer_.done()) {
            designer_.abort();
            face_fading_ = true;
            face_w_      = 0.0;
        }
        const double dw = 1.0 / (kFaceFadeMs * fs_ / 1000.0);
        if (face_fading_) {
            kernel_.processBlock(in, buf_fir_y_, active_face_, 1 - active_face_,
                                 static_cast<float>(face_w_), static_cast<float>(dw));
            face_w_ += dw * static_cast<double>(frames);
            if (face_w_ >= 1.0) {
                active_face_ = 1 - active_face_;
                face_fading_ = false;
                face_fades_++;
                active_gen_  = adopting_gen_;
            }
        } else {
            kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        }
        mixFirOut(in, out, frames, accumulate, nullptr, 1.0, 0.0);
        if (!active_ && fir_wet_ == 0.0) {
            invalidateFir(Drop::kQuiet);
        }
        break;
    }
    }
}

}
