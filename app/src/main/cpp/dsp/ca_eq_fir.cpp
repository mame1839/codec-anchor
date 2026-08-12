#include "ca_eq_fir.h"

#include <cmath>
#include <limits>

namespace caeq {
namespace {

constexpr double kPi   = 3.14159265358979323846;
constexpr double kLn10 = 2.30258509299404568402;

inline int minInt(int a, int b) { return a < b ? a : b; }

}  // namespace

bool FirDesigner::start(const FirDesignSpec& spec, const FftPlan* plan_m,
                        const FftPlan* plan_2p, float* data, float* work, float* filt) {
    phase_  = Phase::kIdle;
    failed_ = false;

    if (!curveValid(spec.curve_db)) return false;
    if (!std::isfinite(spec.fs) || spec.fs < 8000.0 || spec.fs > 768000.0) return false;
    if (spec.taps <= 0) return false;
    if (!fftSizeValid(spec.m) || spec.m < 2 * spec.taps) return false;
    if (!convBlockSizeValid(spec.block)) return false;
    // 分割セグメントの詰め替え先を data の末尾 2P 本に取る (kPartition の説明)。
    // 2P ≤ taps ≤ m/2 なら完成した IR (先頭 taps 本) と重ならない。
    if (2 * spec.block > spec.taps) return false;
    if (spec.window == FirWindow::kTailTaper &&
        (spec.taper <= 0 || spec.taper > spec.taps)) {
        return false;
    }
    if (plan_m == nullptr || plan_m->size() != spec.m) return false;
    if (plan_2p == nullptr || plan_2p->size() != 2 * spec.block) return false;
    if (data == nullptr || work == nullptr || filt == nullptr) return false;
    if (!fftAligned(data) || !fftAligned(work) || !fftAligned(filt)) return false;

    spec_    = spec;
    plan_m_  = plan_m;
    plan_2p_ = plan_2p;
    data_    = data;
    work_    = work;
    filt_    = filt;
    k_total_ = firPartitions(spec.taps, spec.block);
    pos_     = 0;
    phase_   = Phase::kResample;
    return true;
}

void FirDesigner::abort() {
    phase_  = Phase::kIdle;
    failed_ = false;
    pos_    = 0;
}

// 曲線 → 大 FFT の線形ビン。参照実装 (11_fir_vs_biquad.py) は
// 10^(dB/20) を作ってから log(mag + 1e-300) を取るが、log(10^(dB/20)) = dB·ln10/20
// なので直接掛ける (double では相対 1e-16 で同一。+1e-300 の柵は dB の床 -100 が
// あるかぎり mag ≥ 1e-5 なので届かない)。床の -100 も検査済み曲線 (|dB| ≤ 40) では
// 発火しないが、参照実装と同じ式であることを崩さないために残す。
void FirDesigner::resampleRange(int from, int to) {
    const int    half = spec_.m / 2;
    const double step = spec_.fs / static_cast<double>(spec_.m);
    for (int k = from; k < to; k++) {
        double db = curveDbAt(spec_.curve_db, static_cast<double>(k) * step);
        if (db < -100.0) db = -100.0;
        const float lnmag = static_cast<float>(db * (kLn10 / 20.0));
        // 順序付き実スペクトルの並び: [X0, XM/2, Re X1, Im X1, ...]。log|H| は実数なので
        // 虚部は 0。
        if (k == 0) {
            data_[0] = lnmag;
        } else if (k == half) {
            data_[1] = lnmag;
        } else {
            data_[2 * k]     = lnmag;
            data_[2 * k + 1] = 0.0f;
        }
    }
}

// 実ケプストラムの折り返し + 逆変換の 1/M。cep[0] と cep[M/2] はそのまま (×1/M)、
// cep[1..M/2-1] は 2 倍 (×2/M)、上半分はゼロ。M が 2 の冪なら 1/M も 2/M も
// f32 で厳密なので、この工程は丸めを足さない。
void FirDesigner::foldRange(int from, int to) {
    const int   half = spec_.m / 2;
    const float inv  = 1.0f / static_cast<float>(spec_.m);
    const float two  = 2.0f / static_cast<float>(spec_.m);
    for (int i = from; i < to; i++) {
        if (i == 0 || i == half) {
            data_[i] *= inv;
        } else if (i < half) {
            data_[i] *= two;
        } else {
            data_[i] = 0.0f;
        }
    }
}

// 複素 exp。ビン 0 と M/2 は実数 (packed の先頭 2 本)。
void FirDesigner::expRange(int from, int to) {
    const int half = spec_.m / 2;
    for (int k = from; k < to; k++) {
        if (k == 0) {
            data_[0] = static_cast<float>(std::exp(static_cast<double>(data_[0])));
        } else if (k == half) {
            data_[1] = static_cast<float>(std::exp(static_cast<double>(data_[1])));
        } else {
            const double re = static_cast<double>(data_[2 * k]);
            const double im = static_cast<double>(data_[2 * k + 1]);
            const double e  = std::exp(re);
            data_[2 * k]     = static_cast<float>(e * std::cos(im));
            data_[2 * k + 1] = static_cast<float>(e * std::sin(im));
        }
    }
}

// 窓 + 逆変換の 1/M + 有限性の検査。検査済み曲線からは数学的に NaN が出ないが、
// ここが FIR 側で唯一の「完成品を耳に出す前の門」なので安い保険を置く
// (biquad 側の「ブロックごとの状態検査」に対応する位置)。
void FirDesigner::windowRange(int from, int to) {
    const double taps = static_cast<double>(spec_.taps);
    const float  inv  = 1.0f / static_cast<float>(spec_.m);
    for (int n = from; n < to; n++) {
        double w = 1.0;
        switch (spec_.window) {
        case FirWindow::kHalfHann:
            w = 0.5 * (1.0 + std::cos(kPi * static_cast<double>(n) / taps));
            break;
        case FirWindow::kRect:
            w = 1.0;
            break;
        case FirWindow::kTailTaper: {
            const int flat = spec_.taps - spec_.taper;
            if (n >= flat) {
                w = 0.5 * (1.0 + std::cos(kPi * static_cast<double>(n - flat) /
                                          static_cast<double>(spec_.taper)));
            }
            break;
        }
        }
        float h = static_cast<float>(w) * data_[n] * inv;
#ifdef CA_EQ_DSP_TEST_HOOKS
        // **1 回だけ**撃つ。撃ちっぱなしにすると次の設計も必ず失敗し、
        // 「新しい曲線が来たら再挑戦できる」を試験できなくなる。
        if (inject_nan_ && n == 0) {
            h = std::numeric_limits<float>::quiet_NaN();
            inject_nan_ = false;
        }
#endif
        if (!std::isfinite(h)) failed_ = true;
        data_[n] = h;
    }
}

// セグメント k を 2P へゼロ詰めして順序なし実 FFT。**畳み込みの 1/(2P) はここで
// タップに焼く** — process() 側の MAC (zconvolve) は scaling = 1 で回り、
// どの分割数でも正規化がちょうど 1 回になる。
void FirDesigner::partitionOne(int k) {
    const int    p     = spec_.block;
    const int    n     = 2 * p;
    const float  scale = 1.0f / static_cast<float>(n);
    float*       stage = data_ + (spec_.m - n);
    const int    base  = k * p;
    const int    len   = minInt(p, spec_.taps - base);
    for (int i = 0; i < len; i++) stage[i] = data_[base + i] * scale;
    for (int i = len; i < n; i++) stage[i] = 0.0f;
    plan_2p_->forward(stage, filt_ + static_cast<size_t>(k) * static_cast<size_t>(n), work_);
}

bool FirDesigner::step(int64_t budget_ns) {
    last_step_ns_ = 0;
    if (phase_ == Phase::kIdle) return false;
    if (phase_ == Phase::kDone) return true;

    const int64_t start_left = budget_ns;
    int64_t left  = budget_ns;
    bool    first = true;
    // 抜けるときに「モデル上いくら使ったか」を残す (ハーネス 26 節の会計)。
    struct Spend {
        int64_t* out;
        const int64_t* left;
        int64_t start;
        ~Spend() { *out = start - *left; }
    } spend{&last_step_ns_, &left, start_left};

    // 予算が尽きるまで工程を進める。不可分工程は「この呼び出しでまだ何もしていない」か
    // 「残り予算に収まる」ときだけ実行する — 予算の過小をスライスの肥大ではなく
    // 呼び出し回数の増加に倒す。
    while (phase_ != Phase::kDone) {
        switch (phase_) {
        case Phase::kResample: {
            const int total = spec_.m / 2 + 1;
            // int に落とす前に残数で頭打ちにする (巨大な予算で int が溢れる)。
            const int64_t want64 = left / fircost::kResampleNsPerBin;
            int want = want64 > total ? total : static_cast<int>(want64);
            if (want < 1) {
                if (!first) return false;
                want = 1;
            }
            const int n = minInt(total - pos_, want);
            resampleRange(pos_, pos_ + n);
            pos_ += n;
            left -= static_cast<int64_t>(n) * fircost::kResampleNsPerBin;
            first = false;
            if (pos_ >= total) {
                phase_ = Phase::kCepFft;
                pos_   = 0;
            }
            break;
        }
        case Phase::kCepFft:
        case Phase::kSpecFft:
        case Phase::kIrFft: {
            const int64_t cost = fircost::fftNs(spec_.m);
            if (!first && left < cost) return false;
            // in == out は pffft が work 経由で扱う (ca_eq_fft.h)。
            if (phase_ == Phase::kSpecFft) {
                plan_m_->forwardOrdered(data_, data_, work_);
            } else {
                plan_m_->inverseOrdered(data_, data_, work_);
            }
            left -= cost;
            first = false;
            pos_  = 0;
            phase_ = (phase_ == Phase::kCepFft)
                         ? Phase::kFold
                         : (phase_ == Phase::kSpecFft ? Phase::kExp : Phase::kWindow);
            break;
        }
        case Phase::kFold: {
            const int total = spec_.m;
            const int64_t want64 = left / fircost::kFoldNsPerElem;
            int want = want64 > total ? total : static_cast<int>(want64);
            if (want < 1) {
                if (!first) return false;
                want = 1;
            }
            const int n = minInt(total - pos_, want);
            foldRange(pos_, pos_ + n);
            pos_ += n;
            left -= static_cast<int64_t>(n) * fircost::kFoldNsPerElem;
            first = false;
            if (pos_ >= total) {
                phase_ = Phase::kSpecFft;
                pos_   = 0;
            }
            break;
        }
        case Phase::kExp: {
            const int total = spec_.m / 2 + 1;
            const int64_t want64 = left / fircost::kExpNsPerBin;
            int want = want64 > total ? total : static_cast<int>(want64);
            if (want < 1) {
                if (!first) return false;
                want = 1;
            }
            const int n = minInt(total - pos_, want);
            expRange(pos_, pos_ + n);
            pos_ += n;
            left -= static_cast<int64_t>(n) * fircost::kExpNsPerBin;
            first = false;
            if (pos_ >= total) {
                phase_ = Phase::kIrFft;
                pos_   = 0;
            }
            break;
        }
        case Phase::kWindow: {
            const int total = spec_.taps;
            const int64_t want64 = left / fircost::kWindowNsPerTap;
            int want = want64 > total ? total : static_cast<int>(want64);
            if (want < 1) {
                if (!first) return false;
                want = 1;
            }
            const int n = minInt(total - pos_, want);
            windowRange(pos_, pos_ + n);
            pos_ += n;
            left -= static_cast<int64_t>(n) * fircost::kWindowNsPerTap;
            first = false;
            // **非有限を見つけたらそこで止める。**先へ進めても NaN を面へ FFT するだけで、
            // その面は使えない (ヘッダの「失敗として止まる」はこの分岐が担保する)。
            if (failed_) {
                phase_ = Phase::kDone;
                pos_   = 0;
                return true;
            }
            if (pos_ >= total) {
                phase_ = Phase::kPartition;
                pos_   = 0;
            }
            break;
        }
        case Phase::kPartition: {
            const int64_t cost = fircost::fftNs(2 * spec_.block) +
                                 static_cast<int64_t>(2 * spec_.block) *
                                     fircost::kCopyNsPerElem;
            if (!first && left < cost) return false;
            partitionOne(pos_);
            pos_++;
            left -= cost;
            first = false;
            if (pos_ >= k_total_) {
                phase_ = Phase::kDone;
                pos_   = 0;
            }
            break;
        }
        case Phase::kIdle:
        case Phase::kDone:
            break;
        }
    }
    return true;
}

}  // namespace caeq
