#include "ca_eq_conv.h"

#include <cmath>
#include <cstring>

namespace caeq {

bool FirKernel::bind(const FftPlan* plan, int block, int taps, int channels,
                     const Buffers& b) {
    bound_ = false;
    if (!convBlockSizeValid(block)) return false;
    if (taps <= 0 || 2 * block > taps) return false;
    if (channels < 1 || channels > 2) return false;  // FIR が乗るのは device 枠 (≤ 2ch) だけ
    if (plan == nullptr || plan->size() != 2 * block) return false;
    if (b.fdl == nullptr || b.filt[0] == nullptr || b.filt[1] == nullptr ||
        b.tail == nullptr || b.stage == nullptr || b.acc == nullptr || b.acc2 == nullptr ||
        b.work == nullptr) {
        return false;
    }
    if (!fftAligned(b.fdl) || !fftAligned(b.filt[0]) || !fftAligned(b.filt[1]) ||
        !fftAligned(b.stage) || !fftAligned(b.acc) || !fftAligned(b.acc2) ||
        !fftAligned(b.work)) {
        return false;
    }

    plan_    = plan;
    b_       = b;
    block_   = block;
    n_       = 2 * block;
    taps_    = taps;
    ch_      = channels;
    k_total_ = firPartitions(taps, block);
    head_    = 0;
    fill_    = 0;
    bound_   = true;
    std::memset(b_.tail, 0, sizeof(float) * static_cast<size_t>(ch_) *
                                static_cast<size_t>(block_));
    return true;
}

void FirKernel::reset() {
    if (!bound_) return;
    head_ = 0;
    fill_ = 0;
    std::memset(b_.tail, 0, sizeof(float) * static_cast<size_t>(ch_) *
                                static_cast<size_t>(block_));
}

// 入力 1 ブロックを FDL へ。チャンネル c の集約 → ゼロ詰め → 実 FFT →
// リングの head 位置。**NaN / Inf はここで 0 に潰す** — FFT は 1 本の NaN で
// スペクトル全体を汚し、FDL に入ると K ブロックのあいだ出力に残り続けるので、
// 入り口で止めるのが唯一の安い場所 (biquad 側の scrub と同じ理屈)。
void FirKernel::pushInput(const float* in) {
    head_ = (head_ + 1) % k_total_;
    for (int c = 0; c < ch_; c++) {
        const float* src = in + c;
        for (int i = 0; i < block_; i++) {
            const float v = src[static_cast<size_t>(i) * static_cast<size_t>(ch_)];
            if (std::isfinite(v)) {
                b_.stage[i] = v;
            } else {
                b_.stage[i] = 0.0f;
                scrubbed_++;
            }
        }
        std::memset(b_.stage + block_, 0, sizeof(float) * static_cast<size_t>(block_));
        float* slot = b_.fdl + (static_cast<size_t>(c) * static_cast<size_t>(k_total_) +
                                static_cast<size_t>(head_)) *
                                   static_cast<size_t>(n_);
        plan_->forward(b_.stage, slot, b_.work);
    }
    if (fill_ < k_total_) fill_++;
}

void FirKernel::pushBlock(const float* in) {
    if (!bound_ || in == nullptr) return;
    pushInput(in);
}

// 面 face の全分割を MAC する。分割 k は k ブロック前の入力に当たる。
// 正規化 (1/(2P)) はフィルタスペクトルに焼いてあるので scaling は 1。
void FirKernel::macFace(int ch_index, int face, float* acc) const {
    const int valid = fill_ < k_total_ ? fill_ : k_total_;
    const float* fdl_ch = b_.fdl + static_cast<size_t>(ch_index) *
                                       static_cast<size_t>(k_total_) *
                                       static_cast<size_t>(n_);
    for (int k = 0; k < valid; k++) {
        const int slot = (head_ - k + k_total_) % k_total_;
        plan_->convolveAccumulate(
            fdl_ch + static_cast<size_t>(slot) * static_cast<size_t>(n_),
            b_.filt[face] + static_cast<size_t>(k) * static_cast<size_t>(n_), acc, 1.0f);
    }
}

void FirKernel::processBlock(const float* in, float* out, int face, int fade_face,
                             float fade_w0, float fade_dw) {
    if (!bound_ || in == nullptr || out == nullptr) return;
    if (face < 0 || face > 1 || fade_face > 1) return;

    pushInput(in);

    const size_t nbytes = sizeof(float) * static_cast<size_t>(n_);
    for (int c = 0; c < ch_; c++) {
        std::memset(b_.acc, 0, nbytes);
        macFace(c, face, b_.acc);
        plan_->inverse(b_.acc, b_.acc, b_.work);

        if (fade_face >= 0) {
            std::memset(b_.acc2, 0, nbytes);
            macFace(c, fade_face, b_.acc2);
            plan_->inverse(b_.acc2, b_.acc2, b_.work);
            // 時間領域のクロスフェード。尻尾 (i ≥ P) は次のブロックで鳴る時刻の
            // 外挿重みで混ぜる (ヘッダの説明)。
            for (int i = 0; i < n_; i++) {
                float w = fade_w0 + static_cast<float>(i) * fade_dw;
                if (w < 0.0f) w = 0.0f;
                if (w > 1.0f) w = 1.0f;
                b_.acc[i] += (b_.acc2[i] - b_.acc[i]) * w;
            }
        }

        float* tail = b_.tail + static_cast<size_t>(c) * static_cast<size_t>(block_);
        float* dst  = out + c;
        for (int i = 0; i < block_; i++) {
            dst[static_cast<size_t>(i) * static_cast<size_t>(ch_)] = b_.acc[i] + tail[i];
            tail[i] = b_.acc[block_ + i];
        }
    }
}

}  // namespace caeq
