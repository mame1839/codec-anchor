// 最小位相 FIR の設計器。仕様は eq-fir-design.md、実装根拠は eq-dsp-internals.md §3。
#ifndef CA_EQ_FIR_H_
#define CA_EQ_FIR_H_

#include <cstdint>

#include "ca_eq_conv.h"  // firPartitions — designer は kernel のスペクトル形式へ書く側
#include "ca_eq_curve.h"
#include "ca_eq_fft.h"

namespace caeq {

// 選定根拠 (タップ数・M・窓) は eq-fir-design.md §2。
inline int firTapsFor(double fs) { return fs >= 64000.0 ? 16384 : 8192; }

inline int firDefaultM(double fs) { return firTapsFor(fs) * 2; }

enum class FirWindow : uint8_t {
    kHalfHann  = 0,  // w[n] = 0.5·(1 + cos(πn/taps))。参照実装と golden の突き合わせ用
    kRect      = 1,  // 窓なし (打ち切りのみ)。比較実測用
    kTailTaper = 2,  // 先頭は 1、後端 taper 本だけ raised cosine で 0 へ。**製品の既定**
};

inline int firDefaultTaper(int taps) { return taps / 8; }

// スライス償却の費用モデル。⚠️ 過大評価に倒してある (根拠は eq-dsp-internals.md §3)。
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

}  // namespace fircost

struct FirDesignSpec {
    // 401 点の目標曲線 (dB)。**done() まで生存し、内容を書き換えないこと** —
    // 世代管理 (途中で新しい曲線が来たら作り直し) は呼び出し側が持つ。
    const float* curve_db = nullptr;
    double    fs     = 48000.0;
    int       taps   = 8192;   // firTapsFor(fs)
    int       m      = 16384;  // 大 FFT。fftSizeValid ∧ m ≥ 2·taps。既定は firDefaultM
    int       block  = 0;      // P。convBlockSizeValid ∧ 2P ≤ taps
    FirWindow window = FirWindow::kTailTaper;
    int       taper  = 1024;   // kTailTaper のときだけ使う (0 < taper ≤ taps)。firDefaultTaper
};

class FirDesigner {
public:
    FirDesigner() = default;
    FirDesigner(const FirDesigner&) = delete;
    FirDesigner& operator=(const FirDesigner&) = delete;

    // 工程 (観測用。順序はこの並びで一方向)
    enum class Phase : uint8_t {
        kIdle = 0,
        kResample,   // 曲線 → M/2+1 ビンの log|H| (packed 実スペクトル並び)
        kCepFft,     // irfft → 実ケプストラム (不可分)
        kFold,       // 折り返し + 1/M
        kSpecFft,    // rfft (不可分)
        kExp,        // 複素 exp
        kIrFft,      // irfft → h (不可分)
        kWindow,     // 窓 + 1/M + 有限性検査
        kPartition,  // セグメント k を 2P の実 FFT でスペクトル化 (1 分割 = 不可分)
        kDone,
    };

    // 検査して工程を巻き戻す。不正なら false (状態は kIdle のまま)。
    //   plan_m:  spec.m の FftPlan / plan_2p: 2·spec.block の FftPlan
    //   data:    m floats (作業と結果。完成後の先頭 taps 本が IR)
    //   work:    m floats (FFT の work)
    //   filt:    K·2P floats (分割スペクトルの出力先。畳み込み器の「面」)
    // すべて 16 B 整列。
    bool start(const FirDesignSpec& spec, const FftPlan* plan_m, const FftPlan* plan_2p,
               float* data, float* work, float* filt);

    // budget_ns ぶんだけ進める。done なら true。呼ぶたびに最低 1 単位は進む
    // (予算が不可分工程より小さくても、その工程が次の呼び出しの先頭で実行される)。
    // 完成した IR が非有限だったときは失敗として止まり true を返す (failed() で区別)。
    bool step(int64_t budget_ns);

    void abort();

    bool running() const { return phase_ != Phase::kIdle && phase_ != Phase::kDone; }
    bool done() const { return phase_ == Phase::kDone && !failed_; }
    bool failed() const { return failed_; }
    Phase phase() const { return phase_; }
    int  partitions() const { return k_total_; }

    // 直近の step() が費用モデル上消費した ns (壁時計ではない。test-harness.md §11)。
    int64_t lastStepModelNs() const { return last_step_ns_; }

#ifdef CA_EQ_DSP_TEST_HOOKS
    // ハーネス専用。**検査を通った曲線からは到達しない**非有限の経路を実際に撃つ。
    // 窓の工程で NaN を作らせ、本物の検出経路 (windowRange の isfinite) を通す。
    void injectNonFinite() { inject_nan_ = true; }
#endif

    // 完成した IR の先頭 taps 本 (= data の先頭)。done() のときだけ意味を持つ。
    // kPartition 工程は data の末尾 2P 本しか触らない (m ≥ 2·taps ∧ 2P ≤ taps なので
    // 重ならない) から、完成後も残っている。ハーネスの照合と診断用。
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
    int   pos_     = 0;  // いまの工程内の位置 (ビン / 要素 / タップ / 分割)
    int   k_total_ = 0;
    int64_t last_step_ns_ = 0;
#ifdef CA_EQ_DSP_TEST_HOOKS
    bool  inject_nan_ = false;
#endif
};

}  // namespace caeq

#endif  // CA_EQ_FIR_H_
