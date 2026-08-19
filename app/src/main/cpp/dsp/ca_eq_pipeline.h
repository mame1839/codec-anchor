// biquad (caeq::Eq) と最小位相 FIR (FirDesigner + FirKernel) を束ねる面。
// 混合の規約・遷移表・確保の方針は eq-dsp-internals.md §4。
#ifndef CA_EQ_PIPELINE_H_
#define CA_EQ_PIPELINE_H_

#include <cstdint>

#include "ca_eq_conv.h"
#include "ca_eq_curve.h"
#include "ca_eq_dsp.h"
#include "ca_eq_fir.h"

namespace caeq {

// ⚠️ 上限を下げる方向はハーネスの釘しか網が無い (literal で書くこと)。eq-dsp-internals.md §5。
inline constexpr int kMaxConvBlock = 4096;

// ⚠️ 実測の使用量より小さい値へ意図的に固定してある。正直な値 (1536) を申告すると
// この端末のフレームワークがエフェクトを生成しなくなる (実測済み)。変えないこと。
// 詳細は eq-fir-design.md §2、実際の使用量は `caeqstat` の `arena=%u KB` を見る。
inline constexpr unsigned kDeclaredMemoryKb = 64;

// スライス予算 = この割合 × ブロックの実時間 (P/fs)。選定根拠は eq-dsp-internals.md §3。
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
        kFadeOut = 4,  // FIR → biquad の出力クロスフェード中 (モード OFF。FIR は健在)
    };

    // --- 制御スレッド ------------------------------------------------------

    // Eq::configure と同じ約束 + arena。同じ (fs, ch) なら arena は触らない (冪等)。
    // fs が変わったら arena を組み直し、FIR の状態は捨てる (taps / M / FDL すべて fs 依存)。
    void configure(double sample_rate, int channels, Structure structure);

    // このインスタンスが FIR の作業領域 (arena) を持ってよいか。許可であって要求ではない。
    // いつ呼んでもよい (何度でも、順序不問。同じ値の呼び出しは no-op)。
    // ⚠️ 既定を false にしないこと。呼び忘れた `.so` では高精度が黙って効かなくなる。
    // 失敗する向きは必ず「動くが無駄」側へ倒す (eq-dsp-internals.md §4.4)。
    void setFirCapable(bool capable);

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

    // 目標曲線。memcpy + dirty だけ (poll から呼ばれるので重い検査を置かない。検査は
    // designer を再始動する瞬間にやる。eq-dsp-internals.md §4.5)。
    void setCurve(const float* db401, uint32_t generation);

    // --- オーディオ経路 ------------------------------------------------------

    void process(const float* in, float* out, int frames, bool accumulate = false);

    // --- 観測 ----------------------------------------------------------------

    FirState firState() const { return state_; }
    bool     idle() const;               // 両エンジンとも完全な素通しか (-ENODATA の合図)
    int      fdlFill() const { return kernel_.fill(); }
    // FDL が満ちる目標 (K = ceil(taps/P))。fill と並べると温めがどこまで進んだか分かる。
    int      fdlPartitions() const { return kernel_.partitions(); }
    // ⚠️ 不適の累計 (unfitSize/unfitBudget) とは別物 — あちらは「起きたことがある」、
    // こちらは「いま起きている」。
    bool     blockOk() const { return p_ok_; }
    uint32_t rebuilds() const { return rebuilds_; }          // designer の起動回数
    uint32_t faceFades() const { return face_fades_; }       // FIR→FIR の差し替え回数
    // 「鳴っていた FIR が端末側の都合で止まり biquad へ乗り換わった回数」。ユーザ操作や
    // 音の経路が変わらない事象は混ぜない (eq-dsp-internals.md §4.3)。
    uint32_t fallbacks() const { return fallbacks_; }
    uint32_t modeOffs() const { return mode_offs_; }         // ユーザがモードを切った回数
    uint32_t designFailures() const { return design_failures_; }  // 設計器が非有限で止まった回数
    uint32_t unfitSizeCount() const { return unfit_size_; }  // P が大きさで不適
    uint32_t unfitBudgetCount() const { return unfit_budget_; }  // P が予算で不適
    // 本番では上がらない — poll (dsp/ca_eq_poll.h) が curveValid を先に通すため。
    // poll を通らない呼び手 (ハーネス等) のための最後の砦 (eq-dsp-internals.md §4.3)。
    uint32_t curveRejected() const { return curve_rejected_; }
    bool     firCapable() const { return fir_capable_; }
    // ⚠️ 累積の designFailures() の代わりに使わない (eq-dsp-internals.md §4.3)。
    // こちらは新しい曲線が来れば解除される現在値。
    bool     curveFailed() const { return curve_failed_; }
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
    // ハーネス専用: 設計器へ直接触る (到達しない失敗経路を撃つため)。
    FirDesigner& designerForTest() { return designer_; }
    // ハーネス専用: arena の基底 (整列の不変条件を実測するため)。
    const void* arenaBaseForTest() const { return arena_; }
#endif

private:
    // FIR を畳む理由。カウンタの意味を決めるのはここ 1 箇所 (eq-dsp-internals.md §4.3)。
    enum class Drop : uint8_t {
        kHard,     // 端末側の都合 (P/fs/ch 変化・RESET・arena 喪失)。落下として数える + 起こす
        kHandoff,  // このインスタンスが FIR の宿主でなくなった。数えないが**起こす**
        kQuiet,    // 音の経路が変わらない (準備中の取り止め・DISABLE 完了・モード OFF 完了)
    };

    void releaseArena();
    void reserveArena();
    // ⚠️ configure と setFirCapable の両方から呼ぶ。1 箇所に集めないと、後から確保した
    // 経路でだけ初期化が抜ける (eq-dsp-internals.md §4.4)。
    void reserveForCurrent();
    void invalidateFir(Drop reason);    // FDL 失効 → kBiquad (準備からやり直し)
    bool evaluateBlock(int frames);     // P の合法/予算判定。変化時だけ判定し直す
    void adoptCurveIfDirty();           // dirty なら検査 → designer 再始動
    void startDesigner(int face);
    void stepPrepare(int frames);       // 準備中のスライス (setup 構築 / designer)
    void stepDesigner();                // kFir 中のスライス (失敗の後始末を 1 箇所に)
    double fadeStep() const;            // 1 フレームあたりのフェードの進み (Eq から引く)
    // 混合の規約は eq-dsp-internals.md §4.1。bq == nullptr なら「biquad の分け前ゼロ」。
    void mixFirOut(const float* in, float* out, int frames, bool accumulate,
                   const float* bq, double mix0, double dmix);

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
    bool fir_capable_ = true;  // 既定 true (setFirCapable 参照)
    bool p_ok_        = false;
    int  p_cur_       = 0;      // 0 = まだブロックを見ていない
    int64_t budget_ns_ = 0;

    // 曲線 (401 点 ×2: poll が書く pending と、designer が読む active)
    float    pending_curve_[kCurvePoints] = {};
    float    active_curve_[kCurvePoints]  = {};
    uint32_t pending_gen_  = 0;   // poll が置いた最新
    uint32_t adopting_gen_ = 0;   // designer がいま組んでいる世代
    uint32_t active_gen_   = 0;   // **鳴っている**世代 (フェード完了で昇格。0 = FIR 未稼働)
    bool     curve_dirty_  = false;
    bool     have_curve_   = false;
    bool     curve_failed_ = false;  // 新しい世代が来るまで再挑戦しない (失敗ループを塞ぐ)

    int  active_face_    = 0;
    bool face_fading_    = false;
    bool tail_warmed_    = false;
    double fir_mix_ = 0.0;  // 2 エンジン間の乗り移り。biquad 側の分け前は Eq の wet が持つ
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

}  // namespace caeq

#endif  // CA_EQ_PIPELINE_H_
