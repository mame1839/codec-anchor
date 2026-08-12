// biquad (caeq::Eq) と最小位相 FIR (FirDesigner + FirKernel) を束ねる面。
// **Android にも Xposed にも依存しない。**`.so` (段 2) は Eq の代わりにこれを持つ。
//
// 分担:
//   - caeq::Eq は無改造。標準モード・FIR の interim (再構築中の音)・不適時の受け皿
//   - FirDesigner が曲線 → 分割スペクトルを audio スレッド上のスライス償却で組む
//     (worker スレッドは作らない — eq-fir-design.md §3)
//   - FirKernel が畳み込る。このクラスは遷移・予算・診断・混合だけを持つ
//
// 遷移 (フェードはすべて 10 ms):
//   biquad ──(FIR 要求 ∧ P 合法 ∧ 予算内 ∧ 曲線あり)──> 準備
//     準備中: biquad が鳴る。入力側 (FDL push) は常時回し、designer をスライスで進める
//   準備 ──(designer 完了 ∧ FDL fill = K ∧ 尻尾温め 1 ブロック)──> 出力クロスフェード ──> FIR
//     **AND 条件が揃うまで出力へ混ぜない** — FDL が浅いまま鳴らすと畳み込みの
//     途中結果 (履歴ゼロ扱い) が耳に出る
//   FIR ──(曲線変更)──> designer を裏の面へ再始動 → 完成後に面クロスフェード (FDL は共有)
//   FIR ──(P 変化 / fs 変化 / RESET / idle 復帰)──> FDL 失効 → biquad へ落下
//     (FIR の出力は即座に作れなくなるので、biquad を**ゼロ状態 + 10 ms フェードイン**で
//     立ち上げる。落下のクリックはハーネスで実測する)
//
// 確保は configure() (制御スレッド) の arena 1 本だけ。**ch ≤ 2 のときだけ確保する**
// — 12ch の spatializer インスタンスには作らない (FIR フラグが来ても biquad + カウンタ)。
// 区画は 64 B 整列で切り出し、大きさは pffft_setup_bytes と最悪の合法 P から実行時に計算する
// (バイト数を手で焼かない)。process() では確保・ロック・ログ・例外なし。
#ifndef CA_EQ_PIPELINE_H_
#define CA_EQ_PIPELINE_H_

#include <cstdint>

#include "ca_eq_conv.h"
#include "ca_eq_curve.h"
#include "ca_eq_dsp.h"
#include "ca_eq_fir.h"

namespace caeq {

// ブロック長の上限。2P がこれを超えると fftSetupBytes の予約の外に出る。
inline constexpr int kMaxConvBlock = 4096;

// スライス予算 = この割合 × ブロックの実時間 (P/fs)。ホスト実測 (ハーネス 26 節) では
// 設計全体が ~0.5 ms で、P ≥ 512 なら 1 スライスに収まり、完成までの時間は FDL の
// 温め (K ブロック) が支配する — FIR 開始まで実測 12 ブロック ≈ 240 ms @ P=960。
// 0.25 は実機 (数倍遅い想定) でも最大の不可分クォンタム (M の FFT) が P=128@48k 級の
// 予算に収まる線。実機での再較正は段 4。
inline constexpr double kSliceBudgetFrac = 0.25;

class EqPipeline {
public:
    EqPipeline() = default;
    ~EqPipeline();
    EqPipeline(const EqPipeline&) = delete;
    EqPipeline& operator=(const EqPipeline&) = delete;

    enum class FirState : uint8_t {
        kBiquad  = 0,  // FIR なし (未要求 / 不適 / 曲線待ち)
        kPrepare = 1,  // biquad が鳴っていて、designer + FDL 温めが走っている
        kFadeIn  = 2,  // biquad → FIR の出力クロスフェード中
        kFir     = 3,  // FIR が鳴っている (面フェード含む)
    };

    // --- 制御スレッド ------------------------------------------------------

    // Eq::configure と同じ約束 + arena。同じ (fs, ch) なら arena は触らない (冪等)。
    // fs が変わったら arena を組み直し、FIR の状態は捨てる (taps / M / FDL すべて fs 依存)。
    void configure(double sample_rate, int channels, Structure structure);

    bool setParams(const Params& p);   // Eq へ転送 + FIR 側 preamp の追跡 (10 ms ランプ)
    bool snapParams(const Params& p);  // Eq へ転送 + preamp 即時反映
    void setActive(bool active);       // Eq へ転送 + FIR 側 wet (Eq と同じ意味論)
    void reset();                      // Eq::reset + FDL 失効
    void warmUp();                     // Eq::warmUp + arena のページを触る

    // 時計 (診断のスライス実測用)。CLOCK_MONOTONIC を返す関数を渡す。null で無効。
    // 決定性に影響しない — 測るだけで、進め方は費用モデル (fircost) が決める。
    void setClock(uint64_t (*now_ns)());

    // チューニング系の転送用。**setParams / setActive / reset / process は必ず
    // EqPipeline 経由** — ここから直接呼ぶと遷移の前提が崩れる。
    Eq& biquad() { return eq_; }
    const Eq& biquad() const { return eq_; }

    // --- audio スレッド (process() の先頭の poll から) -----------------------

    // 高精度モードの要求 (共有メモリ v4 の flags から)。
    void setFirEnabled(bool enabled);

    // 目標曲線。**memcpy + dirty だけ** (poll から呼ばれるので重い検査を置かない)。
    // 検査 (有限 ∧ |dB| ≤ 40) は designer を再始動する瞬間にやり、落ちたら丸ごと棄却して
    // 前の曲線のまま + カウンタ。世代が同じなら何もしない。
    void setCurve(const float* db401, uint32_t generation);

    // --- オーディオ経路 ------------------------------------------------------

    void process(const float* in, float* out, int frames, bool accumulate = false);

    // --- 観測 ----------------------------------------------------------------

    FirState firState() const { return state_; }
    bool     idle() const;               // 両エンジンとも完全な素通しか (-ENODATA の合図)
    int      fdlFill() const { return kernel_.fill(); }
    uint32_t rebuilds() const { return rebuilds_; }          // designer の起動回数
    uint32_t faceFades() const { return face_fades_; }       // FIR→FIR の差し替え回数
    uint32_t fallbacks() const { return fallbacks_; }        // FIR→biquad の落下回数
    uint32_t unfitSizeCount() const { return unfit_size_; }  // P が大きさで不適
    uint32_t unfitBudgetCount() const { return unfit_budget_; }  // P が予算で不適
    uint32_t curveRejected() const { return curve_rejected_; }
    uint32_t scrubbedSamples() const { return kernel_.scrubbedSamples(); }
    uint64_t maxSliceNs() const { return max_slice_ns_; }
    uint32_t curveGeneration() const { return active_gen_; }  // いま鳴っている曲線の世代
    bool     firAvailable() const { return arena_ != nullptr; }
    size_t   arenaBytes() const { return arena_bytes_; }
    int      designTaps() const { return taps_; }
    int      designM() const { return m_; }

#ifdef CA_EQ_DSP_TEST_HOOKS
    // ハーネス専用: FIR 側 wet の現在値 (フェード曲線の同一性テストが読む)。
    double firWet() const { return fir_wet_; }
#endif

private:
    void releaseArena();
    void reserveArena();
    void invalidateFir();               // FDL 失効 → kBiquad (準備からやり直し)
    bool evaluateBlock(int frames);     // P の合法/予算判定。変化時だけ判定し直す
    void adoptCurveIfDirty();           // dirty なら検査 → designer 再始動
    void startDesigner(int face);
    void stepPrepare(int frames);       // 準備中のスライス (setup 構築 / designer)
    void mixFirOut(const float* in, float* out, int frames, bool accumulate,
                   const float* bq, float v0, float dv);
    // biquad 経路 + 落下後の 10 ms 立ち上げ (bq_gate_ < 1 のあいだ dry と混ぜる)
    void processBiquadGated(const float* in, float* out, int frames, bool accumulate);

    Eq eq_;
    FirKernel   kernel_;
    FirDesigner designer_;
    FftPlan     plan_m_;
    FftPlan     plan_2p_;

    // arena (configure で 1 回)。span は float 単位のオフセットで持つ。
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
    bool p_ok_        = false;
    int  p_cur_       = 0;      // 0 = まだブロックを見ていない
    int64_t budget_ns_ = 0;

    // 曲線 (401 点 ×2: poll が書く pending と、designer が読む active)
    float    pending_curve_[kCurvePoints] = {};
    float    active_curve_[kCurvePoints]  = {};
    uint32_t pending_gen_ = 0;
    uint32_t active_gen_  = 0;
    bool     curve_dirty_ = false;
    bool     have_curve_  = false;

    int  active_face_    = 0;
    bool face_fading_    = false;
    bool tail_warmed_    = false;
    // フェードの位置 (フレーム単位で進める)。v: biquad→FIR、w: 面 A→B、
    // bq_gate_: FIR からの落下後に biquad をゼロ状態から立ち上げるゲート
    double engine_v_ = 0.0;
    double face_w_   = 0.0;
    double bq_gate_  = 1.0;

    // FIR 側の wet / preamp。**Eq と同じ意味論** (wet は 1/n 刻みの線形、n = ms·fs/1000)。
    // 同一性はハーネス 26 節が Eq の実出力と突き合わせる。
    bool   active_     = false;
    double fir_wet_    = 0.0;
    double fir_wet_step_ = 1.0;
    double pre_cur_  = 1.0;
    double pre_from_ = 1.0;
    double pre_to_   = 1.0;
    int64_t pre_pos_ = 0;
    int64_t pre_len_ = 0;

    uint64_t (*now_ns_)() = nullptr;
    uint64_t max_slice_ns_ = 0;

    uint32_t rebuilds_       = 0;
    uint32_t face_fades_     = 0;
    uint32_t fallbacks_      = 0;
    uint32_t unfit_size_     = 0;
    uint32_t unfit_budget_   = 0;
    uint32_t curve_rejected_ = 0;
};

}  // namespace caeq

#endif  // CA_EQ_PIPELINE_H_
