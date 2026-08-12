#include "ca_eq_pipeline.h"

#include <cmath>
#include <cstring>
#include <new>

namespace caeq {
namespace {

// 遷移のフェードはすべて 10 ms (Eq の既定の fade / ramp と同じ長さ)。
constexpr double kXfadeMs = 10.0;

inline size_t align64(size_t v) { return (v + 63u) & ~static_cast<size_t>(63u); }

inline float clamp01f(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// 2P の setup 構築 (in-place) の費用モデル。sincos が支配する O(N)。
// 係数はホスト実測を上に丸めた保守値 (fircost と同じ流儀。会計はハーネス 25 節)。
constexpr int64_t kSetupNsPerElem = 120;
inline int64_t setupBuildNs(int n) { return static_cast<int64_t>(n) * kSetupNsPerElem; }

}  // namespace

EqPipeline::~EqPipeline() { releaseArena(); }

void EqPipeline::releaseArena() {
    plan_m_.release();
    plan_2p_.release();
    if (arena_ != nullptr) {
        ::operator delete(arena_, std::align_val_t(64));
        arena_ = nullptr;
    }
    arena_bytes_ = 0;
    invalidateFir();
    have_curve_  = false;
    curve_dirty_ = false;
    bq_gate_     = 1.0;  // 落下の途中で arena が消えても null の bq_y を触らない
}

// 最悪の合法 P に対する K·2P (floats)。FDL とフィルタ面の予約はこれで測る。
// K·2P = ceil(taps/P)·2P は P に対して単調でない (端数のゼロ詰め分が波打つ) ので、
// 上限 4096 までの合法 P を全部歩く。数十個なので configure() で回してよい。
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
    // 区画の大きさはすべて実行時に計算する (pffft_setup_bytes / 合法 P の列挙)。
    // バイト数の literal を焼くと、pffft の構造体が変わったときに黙ってずれる。
    const size_t setup_m_bytes  = fftSetupBytes(m_);
    const size_t setup_2p_bytes = fftSetupBytes(2 * kMaxConvBlock);  // 単調なので最大で予約
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
        return;  // FIR なしで生きる (firAvailable() = false、biquad は無傷)
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
        // m_ は firDefaultM が返す合法サイズなのでここには来ないはずだが、
        // 来たら FIR を丸ごと諦める (biquad は無傷)。
        releaseArena();
    }
}

void EqPipeline::configure(double sample_rate, int channels, Structure structure) {
    eq_.configure(sample_rate, channels, structure);
    // Eq と同じ受け方で、FIR 側の可否だけここで決める。
    if (!std::isfinite(sample_rate) || sample_rate <= 0.0) return;
    int ch = channels;
    if (ch < 1) ch = 1;

    // arena は ch ≤ 2 のときだけ (eq-fir-design.md §2 — 12ch の spatializer には作らない)。
    if (ch > 2) {
        releaseArena();
        fs_ = sample_rate;
        ch_ = ch;
        return;
    }
    if (arena_ != nullptr && sample_rate == fs_ && ch == ch_) {
        return;  // 冪等 — SET_CONFIG は同じ値で何度も来る
    }
    releaseArena();
    fs_   = sample_rate;
    ch_   = ch;
    taps_ = firTapsFor(fs_);
    m_    = firDefaultM(fs_);
    reserveArena();

    fir_wet_step_ = 1.0 / (kXfadeMs * fs_ / 1000.0 < 1.0 ? 1.0 : kXfadeMs * fs_ / 1000.0);
    pre_len_ = static_cast<int64_t>(eq_.rampMillis() * fs_ / 1000.0 + 0.5);
    p_cur_ = 0;
    p_ok_  = false;
}

bool EqPipeline::setParams(const Params& p) {
    const bool ok = eq_.setParams(p);
    if (ok) {
        // FIR 側の preamp は IR に焼かず、スカラを 10 ms でランプする
        // (プリアンプだけの変更で再構築を走らせない — eq-fir-design.md §2)。
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
    eq_.setActive(active);
    active_ = active;
}

void EqPipeline::reset() {
    eq_.reset();
    invalidateFir();
}

void EqPipeline::warmUp() {
    eq_.warmUp();
    // 初回のページフォルトを process() の外へ。arena を 1 ページごとに触る。
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
    return !active_ && fir_wet_ == 0.0;
}

void EqPipeline::invalidateFir() {
    if (state_ == FirState::kFir) {
        // FIR 稼働中は biquad を並走させていない — 状態が古い。ゼロにして
        // 10 ms のゲートで立ち上げる (「落下時はゼロ状態 + 10 ms フェードイン」)。
        fallbacks_++;
        eq_.reset();
        bq_gate_ = 0.0;
    } else if (state_ == FirState::kFadeIn) {
        // フェード中は biquad も毎ブロック回っていて状態が新しい — そのまま続ける。
        // v が畳まれるぶんの段差は残る (落下のクリックとしてハーネスで実測する)。
        fallbacks_++;
    }
    kernel_.reset();
    designer_.abort();
    tail_warmed_ = false;
    face_fading_ = false;
    engine_v_    = 0.0;
    face_w_      = 0.0;
    state_       = FirState::kBiquad;
}

// P (ブロック長) が変わった。合法か・予算に収まるかを判定し直す。
bool EqPipeline::evaluateBlock(int frames) {
    if (state_ != FirState::kBiquad) invalidateFir();  // 履歴は P に紐づく — 丸ごと失効
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
    if (!curve_dirty_ || face_fading_) return;  // 面フェード中は両面が使用中 — 終わってから
    curve_dirty_ = false;
    if (!curveValid(pending_curve_)) {
        curve_rejected_++;
        return;  // 前の曲線 (と鳴っている面) を保つ
    }
    std::memcpy(active_curve_, pending_curve_, sizeof(active_curve_));
    active_gen_ = pending_gen_;
    have_curve_ = true;
    // 再始動 (FDL は生かす)。kFir なら裏の面へ、準備中なら同じ面へ組み直す。
    const int face = (state_ == FirState::kFir || state_ == FirState::kFadeIn)
                         ? 1 - active_face_
                         : active_face_;
    startDesigner(face);
}

void EqPipeline::startDesigner(int face) {
    FirDesignSpec spec;
    spec.curve_db = active_curve_;
    spec.fs       = fs_;
    spec.taps     = taps_;
    spec.m        = m_;
    spec.block    = p_cur_;
    spec.window   = FirWindow::kHalfHann;
    designer_.abort();
    if (designer_.start(spec, &plan_m_, &plan_2p_, buf_data_, buf_work_, buf_filt_[face])) {
        rebuilds_++;
    }
}

// 準備中のスライス 1 回分。2P の setup 構築は 1 クォンタム (このブロックはそれで終わり)。
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
                p_ok_ = false;  // 予約の想定外 — FIR を諦める (biquad は無傷)
                unfit_size_++;
            }
        } else {
            p_ok_ = false;
            unfit_size_++;
        }
    } else if (designer_.running()) {
        designer_.step(budget_ns_);
        if (designer_.failed()) {
            // 検査済み曲線からは数学的に出ないはずの非有限。曲線を捨てて biquad に留まる
            // (同じ曲線で作り直しても同じ結果 — ループさせない)。
            designer_.abort();
            have_curve_ = false;
            fallbacks_++;
        }
    } else if (!designer_.done()) {
        adoptCurveIfDirty();
        if (!designer_.running() && have_curve_ && state_ == FirState::kBiquad) {
            startDesigner(active_face_);
        }
    }
    if (designer_.running() || designer_.done()) {
        state_ = FirState::kPrepare;
    }

    if (now_ns_ != nullptr) {
        const uint64_t dt = now_ns_() - t0;
        if (dt > max_slice_ns_) max_slice_ns_ = dt;
    }
}

// FIR 側の出力の混合。bq が非 null なら biquad → FIR のクロスフェード中で、
// v = v0 + i·dv (0→1) で乗り移る。wet / preamp は Eq と同じ意味論で 1 フレームずつ進める。
void EqPipeline::mixFirOut(const float* in, float* out, int frames, bool accumulate,
                           const float* bq, float v0, float dv) {
    const int ch = ch_;
    for (int i = 0; i < frames; i++) {
        // preamp のランプ (Eq の pre_cur_ 補間と同じ線形)
        if (pre_pos_ < pre_len_) {
            const double u = static_cast<double>(pre_pos_) / static_cast<double>(pre_len_);
            pre_cur_ = pre_from_ + (pre_to_ - pre_from_) * u;
            pre_pos_++;
        } else {
            pre_cur_ = pre_to_;
        }
        // wet (Eq の processChunk と同じ「使ってから進める」)
        const double wet = fir_wet_;
        const double target = active_ ? 1.0 : 0.0;
        if (fir_wet_ < target) {
            fir_wet_ += fir_wet_step_;
            if (fir_wet_ > target) fir_wet_ = target;
        } else if (fir_wet_ > target) {
            fir_wet_ -= fir_wet_step_;
            if (fir_wet_ < target) fir_wet_ = target;
        }
        float v = 1.0f;
        if (bq != nullptr) v = clamp01f(v0 + static_cast<float>(i) * dv);

        const size_t base = static_cast<size_t>(i) * static_cast<size_t>(ch);
        for (int c = 0; c < ch; c++) {
            const size_t at  = base + static_cast<size_t>(c);
            const double dry = static_cast<double>(in[at]);
            const double y   = static_cast<double>(buf_fir_y_[at]) * pre_cur_;
            double mixed     = dry + (y - dry) * wet;
            if (bq != nullptr) {
                const double b = static_cast<double>(bq[at]);
                mixed = b + (mixed - b) * static_cast<double>(v);
            }
            if (accumulate) {
                out[at] += static_cast<float>(mixed);
            } else {
                out[at] = static_cast<float>(mixed);
            }
        }
    }
}

// biquad 経路。落下 (bq_gate_ < 1) の間だけ dry から 10 ms で立ち上げる。
void EqPipeline::processBiquadGated(const float* in, float* out, int frames,
                                    bool accumulate) {
    if (bq_gate_ >= 1.0 || buf_bq_y_ == nullptr) {
        eq_.process(in, out, frames, accumulate);
        return;
    }
    eq_.process(in, buf_bq_y_, frames, false);
    const double dg = 1.0 / (kXfadeMs * fs_ / 1000.0);
    const int    ch = ch_;
    for (int i = 0; i < frames; i++) {
        double g = bq_gate_ + dg * static_cast<double>(i);
        if (g > 1.0) g = 1.0;
        const size_t base = static_cast<size_t>(i) * static_cast<size_t>(ch);
        for (int c = 0; c < ch; c++) {
            const size_t at    = base + static_cast<size_t>(c);
            const double dry   = static_cast<double>(in[at]);
            const double mixed = dry + (static_cast<double>(buf_bq_y_[at]) - dry) * g;
            if (accumulate) {
                out[at] += static_cast<float>(mixed);
            } else {
                out[at] = static_cast<float>(mixed);
            }
        }
    }
    bq_gate_ += dg * static_cast<double>(frames);
    if (bq_gate_ > 1.0) bq_gate_ = 1.0;
}

void EqPipeline::process(const float* in, float* out, int frames, bool accumulate) {
    if (in == nullptr || out == nullptr || frames <= 0) return;

    if (frames != p_cur_) evaluateBlock(frames);

    // 開始条件と継続条件を分ける。active_ は開始側にだけ入れる — DISABLE は
    // kFir の wet フェードで抜ける (即落とすと 10 ms のフェードアウトが消えて段差になる)。
    const bool want_keep  = fir_enabled_ && arena_ != nullptr && p_ok_;
    const bool want_start = want_keep && active_;

    if (!want_keep && state_ != FirState::kBiquad) invalidateFir();

    switch (state_) {
    case FirState::kBiquad:
    case FirState::kPrepare: {
        processBiquadGated(in, out, frames, accumulate);
        // fir_wet_ は FIR の混合ループでしか進まないので、ここでも軌跡を揃えておく
        // (次に FIR 経路へ入るときの初期値。同一性テストが Eq と突き合わせる)。
        const double target = active_ ? 1.0 : 0.0;
        const double step   = fir_wet_step_ * static_cast<double>(frames);
        if (fir_wet_ < target) fir_wet_ = fir_wet_ + step > target ? target : fir_wet_ + step;
        if (fir_wet_ > target) fir_wet_ = fir_wet_ - step < target ? target : fir_wet_ - step;

        if (state_ == FirState::kPrepare) {
            if (!want_start) {
                invalidateFir();  // まだ音に出ていない — 静かに捨てる
                break;
            }
            adoptCurveIfDirty();  // 準備中に新しい曲線が来たら組み直す (rebuilds++)
            // 入力側は常時回す。designer 完了 && FDL が今回で満ちるなら、
            // processBlock (押し込み込み) で尻尾も温めて出力は捨てる。
            if (designer_.done() && kernel_.fill() >= kernel_.partitions() - 1 &&
                !tail_warmed_) {
                kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
                tail_warmed_ = true;
            } else {
                kernel_.pushBlock(in);
                stepPrepare(frames);
            }
            if (designer_.done() && kernel_.ready() && tail_warmed_) {
                designer_.abort();  // 面は組み上がった — 状態機械としては空へ
                engine_v_ = 0.0;
                state_    = FirState::kFadeIn;
            }
        } else if (want_start) {
            stepPrepare(frames);  // setup 構築 → designer 起動 (state が kPrepare へ)
        }
        break;
    }
    case FirState::kFadeIn: {
        eq_.process(in, buf_bq_y_, frames, false);
        kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        const double dv = 1.0 / (kXfadeMs * fs_ / 1000.0);
        mixFirOut(in, out, frames, accumulate, buf_bq_y_, static_cast<float>(engine_v_),
                  static_cast<float>(dv));
        engine_v_ += dv * static_cast<double>(frames);
        if (engine_v_ >= 1.0) state_ = FirState::kFir;
        break;
    }
    case FirState::kFir: {
        adoptCurveIfDirty();
        if (designer_.running()) {
            const uint64_t t0 = now_ns_ != nullptr ? now_ns_() : 0;
            designer_.step(budget_ns_);
            if (now_ns_ != nullptr) {
                const uint64_t dt = now_ns_() - t0;
                if (dt > max_slice_ns_) max_slice_ns_ = dt;
            }
            if (designer_.failed()) {
                designer_.abort();
                have_curve_ = curve_dirty_;  // 新しい曲線が来ていれば次で試す
                fallbacks_++;
            } else if (designer_.done()) {
                designer_.abort();
                face_fading_ = true;
                face_w_      = 0.0;
            }
        }
        const double dw = 1.0 / (kXfadeMs * fs_ / 1000.0);
        if (face_fading_) {
            kernel_.processBlock(in, buf_fir_y_, active_face_, 1 - active_face_,
                                 static_cast<float>(face_w_), static_cast<float>(dw));
            face_w_ += dw * static_cast<double>(frames);
            if (face_w_ >= 1.0) {
                active_face_ = 1 - active_face_;
                face_fading_ = false;
                face_fades_++;
            }
        } else {
            kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        }
        mixFirOut(in, out, frames, accumulate, nullptr, 1.0f, 0.0f);
        if (!active_ && fir_wet_ == 0.0) {
            // 完全な素通しに到達 (Eq::idle 相当)。以後 process が来ない期間に履歴が
            // 腐るので、FDL はここで失効させる。次の有効化は準備からやり直し。
            // 音は既に dry なので「落下」ではない — 数えず、ゲートも要らない。
            state_ = FirState::kBiquad;
            invalidateFir();
        }
        break;
    }
    }
}

}  // namespace caeq
