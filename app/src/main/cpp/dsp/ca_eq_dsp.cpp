#include "ca_eq_dsp.h"

#include <cmath>
#include <cstring>

namespace caeq {
namespace {

constexpr double kPi = 3.14159265358979323846;

inline double dbToLin(double db) { return std::pow(10.0, db / 20.0); }

inline double clampd(double v, double lo, double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline bool finite(double v) { return std::isfinite(v); }

// fc/fs は 0.5 に触れさせない。RBJ は cos/sin なので 0.5 でも有限だが、SVF の
// tan(pi*fc/fs) が発散して係数が Inf になる。両構造で同じ curve を出すために同じ場所で切る。
inline double warpRatio(double fc, double fs) {
    return clampd(fc / fs, kMinFcHz / 192000.0, kMaxFcRatio);
}

}  // namespace

Coef designTdf2(const Band& band, double fs) {
    const double A  = std::pow(10.0, band.gain_db / 40.0);
    const double w0 = 2.0 * kPi * warpRatio(band.fc, fs);
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double al = sw / (2.0 * band.q);

    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;
    switch (band.type) {
    case BandType::kPeaking:
        b0 = 1.0 + al * A;
        b1 = -2.0 * cw;
        b2 = 1.0 - al * A;
        a0 = 1.0 + al / A;
        a1 = -2.0 * cw;
        a2 = 1.0 - al / A;
        break;
    case BandType::kLowShelf: {
        const double tsa = 2.0 * std::sqrt(A) * al;
        b0 =       A * ((A + 1.0) - (A - 1.0) * cw + tsa);
        b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
        b2 =       A * ((A + 1.0) - (A - 1.0) * cw - tsa);
        a0 =           (A + 1.0) + (A - 1.0) * cw + tsa;
        a1 =    -2.0 * ((A - 1.0) + (A + 1.0) * cw);
        a2 =           (A + 1.0) + (A - 1.0) * cw - tsa;
        break;
    }
    case BandType::kHighShelf: {
        const double tsa = 2.0 * std::sqrt(A) * al;
        b0 =        A * ((A + 1.0) + (A - 1.0) * cw + tsa);
        b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
        b2 =        A * ((A + 1.0) + (A - 1.0) * cw - tsa);
        a0 =            (A + 1.0) - (A - 1.0) * cw + tsa;
        a1 =      2.0 * ((A - 1.0) - (A + 1.0) * cw);
        a2 =            (A + 1.0) - (A - 1.0) * cw - tsa;
        break;
    }
    }

    Coef k;
    k.c[0] = b0 / a0;
    k.c[1] = b1 / a0;
    k.c[2] = b2 / a0;
    k.c[3] = a1 / a0;
    k.c[4] = a2 / a0;
    k.c[5] = 0.0;
    return k;
}

Coef designSvf(const Band& band, double fs) {
    const double A = std::pow(10.0, band.gain_db / 40.0);
    const double t = std::tan(kPi * warpRatio(band.fc, fs));

    double g = t, k = 1.0 / band.q, m0 = 1.0, m1 = 0.0, m2 = 0.0;
    switch (band.type) {
    case BandType::kPeaking:
        // k に A が入るのが RBJ の peaking と一致する条件。m1 = k(A^2-1)。
        g  = t;
        k  = 1.0 / (band.q * A);
        m0 = 1.0;
        m1 = k * (A * A - 1.0);
        m2 = 0.0;
        break;
    case BandType::kLowShelf:
        // g を sqrt(A) で割ると、プリワープ後の s 平面で RBJ の低域シェルフと一致する。
        g  = t / std::sqrt(A);
        k  = 1.0 / band.q;
        m0 = 1.0;
        m1 = k * (A - 1.0);
        m2 = A * A - 1.0;
        break;
    case BandType::kHighShelf:
        g  = t * std::sqrt(A);
        k  = 1.0 / band.q;
        m0 = A * A;
        m1 = k * (1.0 - A) * A;
        m2 = 1.0 - A * A;
        break;
    }

    const double d = 1.0 / (1.0 + g * (g + k));
    Coef c;
    c.c[0] = d;          // a1
    c.c[1] = g * d;      // a2
    c.c[2] = g * g * d;  // a3
    c.c[3] = m0;
    c.c[4] = m1;
    c.c[5] = m2;
    return c;
}

Coef design(const Band& band, double fs, Structure structure) {
    return structure == Structure::kSvf ? designSvf(band, fs) : designTdf2(band, fs);
}

Coef unityFor(const Band& band, double fs, Structure structure) {
    Band flat = band;
    flat.gain_db = 0.0;
    return design(flat, fs, structure);
}

bool validate(const Params& p) {
    if (p.band_count < 0 || p.band_count > kMaxBands) return false;
    if (!finite(p.preamp_db) || p.preamp_db < -kMaxGainDb || p.preamp_db > kMaxGainDb) return false;
    for (int i = 0; i < p.band_count; i++) {
        const Band& b = p.bands[i];
        if (b.type != BandType::kPeaking && b.type != BandType::kLowShelf &&
            b.type != BandType::kHighShelf) {
            return false;
        }
        if (!finite(b.fc) || b.fc < kMinFcHz || b.fc > kMaxFcHz) return false;
        if (!finite(b.q) || b.q < kMinQ || b.q > kMaxQ) return false;
        if (!finite(b.gain_db) || b.gain_db < -kMaxGainDb || b.gain_db > kMaxGainDb) return false;
    }
    return true;
}

// ---------------------------------------------------------------------------

Eq::Eq() {
    setRampMillis(10.0);
    setFadeMillis(10.0);
    ramp_pos_ = ramp_len_;
}

void Eq::setRampMillis(double ms) {
    if (!finite(ms) || ms < 0.0) return;
    // 0 なら補間せずその場で入れ替える (比較用。実測でクリックが -64.6 dBFS まで上がる)。
    int64_t n = static_cast<int64_t>(ms * sr_ / 1000.0 + 0.5);
    if (n < 0) n = 0;
    const bool was_ramping = ramping();
    ramp_len_ = n;
    if (!was_ramping) ramp_pos_ = ramp_len_;
}

void Eq::setCoefStride(int frames) {
    if (frames < 1) frames = 1;
    if (frames > kChunkFrames) frames = kChunkFrames;
    coef_stride_ = frames;
}

double Eq::rampMillis() const {
    return static_cast<double>(ramp_len_) * 1000.0 / sr_;
}

void Eq::setFadeMillis(double ms) {
    if (!finite(ms) || ms < 0.0) return;
    fade_ms_ = ms;
    double n = ms * sr_ / 1000.0;
    if (n < 1.0) n = 1.0;
    wet_step_ = 1.0 / n;
}

void Eq::configure(double sample_rate, int channels, Structure structure) {
    if (!finite(sample_rate) || sample_rate <= 0.0) return;
    int ch = channels;
    if (ch < 1) ch = 1;
    if (ch > kMaxChannels) ch = kMaxChannels;

    const bool changed =
        sample_rate != sr_ || ch != ch_ || structure != structure_;
    const double ramp_ms = rampMillis();
    sr_ = sample_rate;
    ch_ = ch;
    structure_ = structure;
    setRampMillis(ramp_ms);
    setFadeMillis(fade_ms_);
    if (!changed) return;

    clearState();
    rebuildFromParams();
}

void Eq::rebuildFromParams() {
    for (int b = 0; b < params_.band_count; b++) {
        band_from_[b] = params_.bands[b];
        band_to_[b]   = params_.bands[b];
        band_cur_[b]  = params_.bands[b];
        const Coef k  = design(params_.bands[b], sr_, structure_);
        from_[b] = k;
        to_[b]   = k;
        cur_[b]  = k;
    }
    nb_run_    = params_.band_count;
    nb_target_ = params_.band_count;
    pre_from_ = pre_to_ = pre_cur_ = dbToLin(params_.preamp_db);
    ramp_pos_ = ramp_len_;
    has_pending_ = false;
}

bool Eq::setParams(const Params& p) {
    if (!validate(p)) {
        rejected_++;
        return false;
    }
    // 取り込みは次の process() の先頭で 1 回だけ。ここで上書きされた分は鳴らない。
    pending_ = p;
    has_pending_ = true;
    return true;
}

bool Eq::snapParams(const Params& p) {
    if (!validate(p)) {
        rejected_++;
        return false;
    }
    // 段が増えるなら、その枠の状態は前の設定の残りなので捨てる。
    for (int b = nb_run_; b < p.band_count; b++) clearBandState(b);
    params_ = p;
    rebuildFromParams();
    return true;
}

void Eq::applyPending() {
    if (!has_pending_) return;
    has_pending_ = false;
    const Params p = pending_;

    // 起点は「いま働いている係数」。ランプの途中で差し替えが来ても段差にならない。
    for (int b = 0; b < kMaxBands; b++) {
        from_[b] = cur_[b];
        band_from_[b] = band_cur_[b];
    }
    pre_from_ = pre_cur_;

    const int old_nb = nb_run_;
    for (int b = 0; b < p.band_count; b++) {
        band_to_[b] = p.bands[b];
        to_[b] = design(p.bands[b], sr_, structure_);
    }
    // 減った段は 0 dB へ向かわせ、ランプが終わってから外す。
    for (int b = p.band_count; b < old_nb; b++) {
        band_to_[b] = band_cur_[b];
        band_to_[b].gain_db = 0.0;
        to_[b] = unityFor(band_cur_[b], sr_, structure_);
    }
    // 増えた段は 0 dB から始める。状態は前の設定の残りなのでここで捨てる。
    for (int b = old_nb; b < p.band_count; b++) {
        clearBandState(b);
        band_from_[b] = p.bands[b];
        band_from_[b].gain_db = 0.0;
        band_cur_[b] = band_from_[b];
        from_[b] = unityFor(p.bands[b], sr_, structure_);
        cur_[b] = from_[b];
    }

    nb_run_    = old_nb > p.band_count ? old_nb : p.band_count;
    nb_target_ = p.band_count;
    pre_to_    = dbToLin(p.preamp_db);
    params_    = p;

    ramp_pos_ = 0;
    if (ramp_len_ <= 0) {
        finishRamp();
    } else {
        updateRampCoef();
    }
}

void Eq::updateRampCoef() {
    const double u = static_cast<double>(ramp_pos_) / static_cast<double>(ramp_len_);
    pre_cur_ = pre_from_ + (pre_to_ - pre_from_) * u;

    for (int b = 0; b < nb_run_; b++) {
        const Band& f = band_from_[b];
        const Band& t = band_to_[b];
        // 種別が変わる段はパラメータの軌跡が定義できないので、係数補間へ落とす。
        if (interp_ == Interp::kParam && f.type == t.type) {
            // fc と Q は対数、ゲインは dB で補間する。1 オクターブのスイープが
            // そのまま等速になる並べ方。
            Band& c = band_cur_[b];
            c.type    = t.type;
            c.fc      = f.fc * std::pow(t.fc / f.fc, u);
            c.q       = f.q * std::pow(t.q / f.q, u);
            c.gain_db = f.gain_db + (t.gain_db - f.gain_db) * u;
            cur_[b] = design(c, sr_, structure_);
        } else {
            // 係数補間のときはパラメータの軌跡を追わない (pow を 2 回払う意味が無い)。
            // 追跡値は目標に寄せておく — 次の差し替えの起点として使うだけ。
            band_cur_[b] = t;
            for (int i = 0; i < 6; i++) {
                cur_[b].c[i] = from_[b].c[i] + (to_[b].c[i] - from_[b].c[i]) * u;
            }
        }
    }
}

void Eq::finishRamp() {
    for (int b = 0; b < nb_run_; b++) {
        cur_[b] = to_[b];
        band_cur_[b] = band_to_[b];
    }
    pre_cur_  = pre_to_;
    nb_run_   = nb_target_;
    ramp_pos_ = ramp_len_;
}

void Eq::setActive(bool active) {
    active_ = active;
    wet_target_ = active ? 1.0 : 0.0;
}

void Eq::reset() { clearState(); }

void Eq::clearState() {
    std::memset(s1_, 0, sizeof(s1_));
    std::memset(s2_, 0, sizeof(s2_));
}

void Eq::clearBandState(int band) {
    if (band < 0 || band >= kMaxBands) return;
    const size_t off = static_cast<size_t>(band) * static_cast<size_t>(ch_);
    std::memset(s1_ + off, 0, sizeof(double) * static_cast<size_t>(ch_));
    std::memset(s2_ + off, 0, sizeof(double) * static_cast<size_t>(ch_));
}

double Eq::stateMagnitude() const {
    double acc = 0.0;
    const int n = nb_run_ * ch_;
    for (int i = 0; i < n; i++) acc += std::fabs(s1_[i]) + std::fabs(s2_[i]);
    return acc;
}

void Eq::warmUp() {
    // 係数・状態・ページを一通り触っておく。初回のページフォルトを process() の外へ出すのが目的。
    float buf[kChunkFrames * kMaxChannels] = {};
    const double keep_wet = wet_cur_;
    const double keep_target = wet_target_;
    const bool keep_pending = has_pending_;
    has_pending_ = false;
    wet_cur_ = 1.0;
    wet_target_ = 1.0;
    process(buf, buf, kChunkFrames, false);
    wet_cur_ = keep_wet;
    wet_target_ = keep_target;
    has_pending_ = keep_pending;
    clearState();
}

#ifdef CA_EQ_DSP_TEST_HOOKS
void Eq::injectState(double v) {
    const int n = (nb_run_ > 0 ? nb_run_ : 1) * ch_;
    for (int i = 0; i < n; i++) {
        s1_[i] = v;
        s2_[i] = v;
    }
}
#endif

void Eq::process(const float* in, float* out, int frames, bool accumulate) {
    if (in == nullptr || out == nullptr || frames <= 0) return;

    // 差し替えの取り込みはここ 1 回だけ。process() あたり 1 回に縛ることが、
    // 係数の変調速度の構造的な上限になる (時変不安定は数百 Hz の変調でしか起きない)。
    applyPending();

    // 完全な素通し。フェードも終わっているので状態を回す意味が無い。
    if (wet_cur_ == 0.0 && wet_target_ == 0.0) {
        // 音に出ない区間でランプを引きずらない。次に有効化されたときは目標の係数から始まる。
        if (ramping()) finishRamp();
        if (accumulate) {
            const size_t ns = static_cast<size_t>(frames) * static_cast<size_t>(ch_);
            for (size_t i = 0; i < ns; i++) out[i] += in[i];
        } else if (in != out) {
            std::memcpy(out, in, static_cast<size_t>(frames) * static_cast<size_t>(ch_) *
                                     sizeof(float));
        }
        return;
    }

    double acc = 0.0;
    int done = 0;
    while (done < frames) {
        int n = frames - done;
        if (n > kChunkFrames) n = kChunkFrames;
        if (ramping()) {
            // 刻み目はランプの経過サンプル数だけで決める。ここをブロック境界に合わせると
            // 512 と 960 で出力が変わってしまう。
            const int64_t to_edge = coef_stride_ - (ramp_pos_ % coef_stride_);
            if (n > to_edge) n = static_cast<int>(to_edge);
            if (ramp_pos_ % coef_stride_ == 0) updateRampCoef();
        }
        const size_t off = static_cast<size_t>(done) * static_cast<size_t>(ch_);
        acc += processChunk(in + off, out + off, n, accumulate);
        if (ramping()) {
            ramp_pos_ += n;
            if (ramp_pos_ >= ramp_len_) finishRamp();
        }
        done += n;
    }

    // ブロックごとに状態の有限性を検査する。IIR は NaN が 1 つ入るだけで以後の出力が
    // 永久に NaN になるので、ここが唯一の逃げ道になる。
    double st = 0.0;
    const int nstate = nb_run_ * ch_;
    for (int i = 0; i < nstate; i++) st += std::fabs(s1_[i]) + std::fabs(s2_[i]);
    if (!std::isfinite(st)) {
        clearState();
        state_resets_++;
        const size_t ns = static_cast<size_t>(frames) * static_cast<size_t>(ch_);
        for (size_t i = 0; i < ns; i++) {
            if (!std::isfinite(out[i])) out[i] = 0.0f;
        }
    } else if (acc == 0.0 && st > 0.0 && st < kDenormalFloor) {
        // 無音が続くと状態は指数的に小さくなり、いずれ非正規化数の領域に入る。
        // double なら 60 秒以上かかるが、そこで 1 サンプルあたりの実行時間が跳ねる
        // 実装がある。FPCR (FTZ) には触らない — スレッドごとの状態なのでプロセス起動時に
        // 立てても届かず、立てたままだと math ライブラリの正しさが保証されなくなる。
        clearState();
        denormal_flushes_++;
    }
}

double Eq::processChunk(const float* in, float* out, int n, bool accumulate) {
    const int ch = ch_;
    const int ns = n * ch;
    double* buf = scratch_;

    // 1 パス目: float -> double。プリアンプを掛けながら、合計で入力の健全性を見る。
    // 全部の絶対値を足すので、桁落ちで NaN が消えることも、double が溢れることも無い。
    const double pre = pre_cur_;
    double acc = 0.0;
    for (int i = 0; i < ns; i++) {
        const double raw = static_cast<double>(in[i]);
        dry_[i] = raw;
        const double x = raw * pre;
        buf[i] = x;
        acc += std::fabs(x);
    }

    if (!std::isfinite(acc)) {
        // NaN / Inf は IIR を恒久的に殺す (1 サンプル入るだけで以後の出力が永久に NaN)。
        // フィルタへ入れる前に潰す。
        for (int i = 0; i < ns; i++) {
            if (!std::isfinite(buf[i])) buf[i] = 0.0;
            if (!std::isfinite(dry_[i])) dry_[i] = 0.0;
        }
        scrubbed_++;
    }

    // 2 パス目: バンドの直列。段の間は double のまま (float に丸めると double 化の意味が消える)。
    if (structure_ == Structure::kSvf) {
        for (int b = 0; b < nb_run_; b++) {
            const double a1 = cur_[b].c[0], a2 = cur_[b].c[1], a3 = cur_[b].c[2];
            const double m0 = cur_[b].c[3], m1 = cur_[b].c[4], m2 = cur_[b].c[5];
            double* ic1 = s1_ + static_cast<size_t>(b) * static_cast<size_t>(ch);
            double* ic2 = s2_ + static_cast<size_t>(b) * static_cast<size_t>(ch);
            for (int f = 0; f < n; f++) {
                double* p = buf + static_cast<size_t>(f) * static_cast<size_t>(ch);
                for (int c = 0; c < ch; c++) {
                    const double v0 = p[c];
                    const double v3 = v0 - ic2[c];
                    const double v1 = a1 * ic1[c] + a2 * v3;
                    const double v2 = ic2[c] + a2 * ic1[c] + a3 * v3;
                    ic1[c] = 2.0 * v1 - ic1[c];
                    ic2[c] = 2.0 * v2 - ic2[c];
                    p[c] = m0 * v0 + m1 * v1 + m2 * v2;
                }
            }
        }
    } else {
        for (int b = 0; b < nb_run_; b++) {
            const double b0 = cur_[b].c[0], b1 = cur_[b].c[1], b2 = cur_[b].c[2];
            const double a1 = cur_[b].c[3], a2 = cur_[b].c[4];
            double* s1 = s1_ + static_cast<size_t>(b) * static_cast<size_t>(ch);
            double* s2 = s2_ + static_cast<size_t>(b) * static_cast<size_t>(ch);
            for (int f = 0; f < n; f++) {
                double* p = buf + static_cast<size_t>(f) * static_cast<size_t>(ch);
                for (int c = 0; c < ch; c++) {
                    const double x = p[c];
                    const double y = b0 * x + s1[c];
                    s1[c] = b1 * x - a1 * y + s2[c];
                    s2[c] = b2 * x - a2 * y;
                    p[c] = y;
                }
            }
        }
    }

    // 3 パス目: wet / dry を混ぜて float へ戻す。
    if (wet_cur_ != 1.0 || wet_target_ != 1.0) {
        for (int f = 0; f < n; f++) {
            const double w = wet_cur_;
            if (wet_cur_ < wet_target_) {
                wet_cur_ += wet_step_;
                if (wet_cur_ > wet_target_) wet_cur_ = wet_target_;
            } else if (wet_cur_ > wet_target_) {
                wet_cur_ -= wet_step_;
                if (wet_cur_ < wet_target_) wet_cur_ = wet_target_;
            }
            const size_t base = static_cast<size_t>(f) * static_cast<size_t>(ch);
            for (int c = 0; c < ch; c++) {
                const size_t i = base + static_cast<size_t>(c);
                buf[i] = dry_[i] + (buf[i] - dry_[i]) * w;
            }
        }
    }

    if (accumulate) {
        for (int i = 0; i < ns; i++) out[i] += static_cast<float>(buf[i]);
    } else {
        for (int i = 0; i < ns; i++) out[i] = static_cast<float>(buf[i]);
    }
    return acc;
}

}  // namespace caeq
