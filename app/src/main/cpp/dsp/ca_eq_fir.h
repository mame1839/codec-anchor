// 最小位相 FIR の設計器。**Android にも Xposed にも依存しない。**
//
// 数学は llmdocs/tools/eq/11_fir_vs_biquad.py の minphase_fir と同一:
//   401 点曲線 → 大 FFT (M) の M/2+1 本の線形ビンへ再標本化 (対数 f・線形 dB の折れ線、
//   端は保持、dB の床 -100) → mag = 10^(dB/20) → 実ケプストラム cep = irfft(log(mag))
//   → 折り返し (cep[1..M/2-1] を 2 倍、上半分ゼロ) → h = irfft(exp(rfft(cep)))
//   → 先頭 taps 本に窓 → 一様分割の各セグメントを 2P の実 FFT でスペクトル化。
//
// **全工程 f32** (FFT が f32 なので)。FIR は帰還が無いので f32 で足りる —
// double 統一規則の根拠は IIR の極の係数感度 (係数の 1 ulp が極の位置を動かし、
// 低域では Q が大きく化ける) で、畳み込みには当てはまらない。タップの f32 化による
// 振幅誤差は 1e-6 dB 級 (eq-fir-design.md §2)。曲線の評価 (dB) と ln・exp・窓の
// スカラ計算だけ double でやってから f32 に落とす (安いところで精度を捨てない)。
//
// **工程を刻める状態機械。**process() のスライス償却 (1 ブロックあたり budget_ns) で
// 数十ブロックかけて完成させるための形で、要素ごとの工程 (再標本化・折り返し・exp・窓)
// は範囲で刻み、大 FFT (M で 3 回) と分割スペクトル化 (2P で K 回) はそれぞれ 1 工程 =
// 不可分。**出力は刻み方に依存しない** (同じ入力なら budget の与え方が違っても
// ビット同一) — 各要素の計算がスライス境界を参照しないため。
//
// **確保はしない。**バッファ (data / work = 各 M 点、filt = K·2P 点) と FftPlan は
// すべて呼び出し側から渡す。スレッドも知らない — 呼ぶ側が 1 本のスレッドから
// start() / step() を回す (audio スレッドのスライス、またはハーネスの一括実行)。
#ifndef CA_EQ_FIR_H_
#define CA_EQ_FIR_H_

#include <cstdint>

#include "ca_eq_conv.h"  // firPartitions — designer は kernel のスペクトル形式へ書く側
#include "ca_eq_curve.h"
#include "ca_eq_fft.h"

namespace caeq {

// タップ数は「IR の実時間 ≈ 170 ms」を一定に: 44.1/48 kHz → 8192、それ以上 → 16384。
// 境界を 64 kHz に置くのは 88.2/96 kHz を上に、48 kHz 系を下に分けるため
// (88.2k で 8192 のままだと IR が 93 ms に縮んで最低域の分解能が落ちる)。
inline int firTapsFor(double fs) { return fs >= 64000.0 ? 16384 : 8192; }

// 大 FFT の既定は M = 2·taps。ゲートの実測 (誤差 vs M、ハーネス 22 節) で決めた:
//   実用曲線では M=2·taps と M=65536 の差が最大 0.0002 dB (48k、96k とも) —
//   ケプストラムのエイリアシングは実用曲線の誤差床 (最下端の分解能) の 3 桁下。
//   隣接摘み ±12 の病的曲線だけ M=2·taps: 5.5 dB / M=4·taps: 3.5 dB と差が出るが、
//   どちらも分解能の床の中で、setup とバッファが倍 (数百 KB) 増えるのに見合わない。
inline int firDefaultM(double fs) { return firTapsFor(fs) * 2; }

// 窓。**既定は kTailTaper (後端 taps/8)。**ゲートの実測 (ハーネス 22 節、fs=48k、
// M=16384) で比較した結果で、参照実装 (EqualizerAPO) の全長 half-Hann とは違う判断:
//   - 全長 half-Hann は IR の実効長を縮め、最低域 (20〜32 Hz は摘み間隔が 5〜6 Hz で
//     IR ≈170 ms の分解能 5.9 Hz と同じ桁) の誤差を 2〜3 倍にする
//     (DUNU 実曲線 0.20 dB、後端テーパなら 0.07 dB)
//   - 実用曲線では窓なし・後端 1/16・1/8・1/4 の差は 0.03 dB 未満
//   - 病的曲線 (隣接摘み ±12) では後端 1/8 が谷: 窓なし 7.8 / 1/16 7.4 / 1/8 5.5 /
//     1/4 9.7 dB。短いと打ち切り縁の漏れ、長いと分解能の食い潰しに倒れる
// kHalfHann は golden (18_minphase_golden.py との突き合わせ) と比較実測のために残す。
enum class FirWindow : uint8_t {
    kHalfHann  = 0,  // w[n] = 0.5·(1 + cos(πn/taps))。参照実装と golden の突き合わせ用
    kRect      = 1,  // 窓なし (打ち切りのみ)。比較実測用
    kTailTaper = 2,  // 先頭は 1、後端 taper 本だけ raised cosine で 0 へ。**製品の既定**
};

// 後端テーパの既定長 (上の実測の谷)。
inline int firDefaultTaper(int taps) { return taps / 8; }

// --- スライス償却の費用モデル -----------------------------------------------
//
// step() は時計を読まない (決定性のため)。代わりにこの表で「予算 (ns) → 進める量」を
// 決める。値は**ホスト (x86-64, mingw -O2) の実測を上に丸めた保守値**で、実機
// (AArch64) は段 4 の実測で再較正する。予算に対して過大評価に倒してある —
// 過小評価はスライスが予算を超える方向に外れる (会計テスト = ハーネス 25 節)。
namespace fircost {

inline constexpr int64_t kResampleNsPerBin = 60;   // 実測 ~21 ns/bin (log + 折れ線補間)
inline constexpr int64_t kFoldNsPerElem    = 4;    // 実測 ~0.4 ns/elem
inline constexpr int64_t kExpNsPerBin      = 40;   // 実測 ~14 ns/bin (exp + cos + sin)
inline constexpr int64_t kWindowNsPerTap   = 30;   // 実測 ~7 ns/tap (cos + 検査)
inline constexpr int64_t kCopyNsPerElem    = 4;    // 分割セグメントの詰め替え

// 実 FFT 1 回 (順序付き/なし共通の上界)。n·log2(n) 比例でモデル化する。
// 係数はホスト実測 (32768 点で ~66 µs = 0.13 ns/unit) の約 3 倍を取ってある —
// 実機の劣化と、順序付き (zreorder が足される) の分を覆う。
inline constexpr double kFftNsPerUnit = 0.40;

inline int64_t fftNs(int n) {
    int lg = 0;
    for (int v = n; v > 1; v >>= 1) lg++;
    return static_cast<int64_t>(kFftNsPerUnit * static_cast<double>(n) *
                                static_cast<double>(lg));
}

// 設計全体で最も大きい不可分工程 (M の FFT)。ブロック長の実行可能判定
// (最大クォンタム ≤ 予算) は EqPipeline がこれで行う。
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

    // 直近の step() が**費用モデル上**消費した ns。決定的で環境に依らないので、
    // 「1 スライスが予算を超えない」の会計はこの値で行う (壁時計は環境で揺れる)。
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
