// biquad (caeq::Eq) と最小位相 FIR (FirDesigner + FirKernel) を束ねる面。
// **Android にも Xposed にも依存しない。**`.so` (段 2) は Eq の代わりにこれを持つ。
//
// 分担:
//   - caeq::Eq は無改造。標準モード・FIR の interim (再構築中の音)・不適時の受け皿
//   - FirDesigner が曲線 → 分割スペクトルを audio スレッド上のスライス償却で組む
//     (worker スレッドは作らない — eq-fir-design.md §3)
//   - FirKernel が畳み込る。このクラスは遷移・予算・診断・混合だけを持つ
//
// **混合の規約 (ここが遷移の芯。2 つの wet を別々に持たない根拠):**
//
//   out = eq_out + (fir·preamp − dry) · fir_wet_ · fir_mix_
//
//   - `eq_out` は Eq の出力そのもの。Eq は内部で out = dry + (bq − dry)·eq_wet を作るので、
//     **biquad の分け前は Eq が持つ wet ただ 1 つ**で表される。pipeline は Eq の wet を
//     複製せず、`eq_.setActive()` で向きを指示するだけ。
//   - `fir_wet_` は FIR 経路の enable/disable (Eq の wet と同じ意味論・同じ長さ)。
//   - `fir_mix_` は 2 つのエンジンの間の乗り移り (0 = biquad だけ、1 = FIR だけ)。
//
//   フェード中は eq_wet が 1→0 に降り、fir_mix_ が 0→1 に昇る。両方 10 ms の線形なので
//   和はそのままクロスフェードになる (中点で 0.5·bq + 0.5·fir)。
//
//   **この形にした理由 (検分で 2 件の不具合として現れた):**
//   pipeline 側に biquad 用の wet を別に持つと、kFir のあいだ Eq の内部 wet が 1.0 で
//   止まったまま置き去りになる。DISABLE で素通しに着いた後に biquad へ渡すと、
//   Eq がそこから自前のフェードを 1→0 で始めて**消えたはずの EQ が丸ごと戻る。**
//   真を 1 つ (Eq の wet) に決め、pipeline は向きだけ指示することで原理的に消える。
//
// 遷移 (フェードはすべて 10 ms):
//   biquad ──(FIR 要求 ∧ P 合法 ∧ 予算内 ∧ 曲線あり)──> 準備
//     準備中: biquad が鳴る。入力側 (FDL push) は常時回し、designer をスライスで進める
//   準備 ──(designer 完了 ∧ FDL fill = K ∧ 尻尾温め 1 ブロック)──> kFadeIn ──> FIR
//     **AND 条件が揃うまで出力へ混ぜない** — FDL が浅いまま鳴らすと畳み込みの
//     途中結果 (履歴ゼロ扱い) が耳に出る。入口で eq_.setActive(false) を出し、
//     Eq 自身のフェードアウトが biquad 側の降りを担う
//   FIR ──(曲線変更)──> designer を裏の面へ再始動 → 完成後に面クロスフェード (FDL は共有)
//   FIR ──(モード OFF)──> kFadeOut (**対称なクロスフェード**)。ここでは FIR が健在なので
//     作れる。入口で eq_.setActive(active_) + eq_.reset() を出し、Eq の立ち上がりが
//     biquad 側の昇りを担う。**落下ではないので fallbacks_ に数えない** (mode_offs_)
//   FIR ──(P 変化 / fs 変化 / ch 変化 / RESET)──> FDL 失効 → biquad へ落下 (fallbacks_)
//     FIR の出力は即座に作れないので対称フェードは作れない。Eq をゼロ状態から
//     自身の wet で立ち上げる。落下のクリックはハーネスで実測する
//   FIR ──(DISABLE が素通しに着いた)──> biquad。**Eq は既に wet 0 で止まっている**ので
//     受け渡しで音が動かない。落下でもモード OFF でもないので何も数えない
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

// `.so` の descriptor.memoryUsage に申告する値 (KB)。**ここが唯一の出どころで、
// ハーネス 30 節が全サンプルレートの arena を実測して超えないことを見張る。**
// フレームワークはこの値を予約には使わない (dumpsys に出る申告値) が、実際に 1 MB
// 使うものを 64 KB と申告すると、あとから見た人が別の場所を疑うことになる。
inline constexpr unsigned kDeclaredMemoryKb = 1536;

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
        kFadeOut = 4,  // FIR → biquad の出力クロスフェード中 (モード OFF。FIR は健在)
    };

    // --- 制御スレッド ------------------------------------------------------

    // Eq::configure と同じ約束 + arena。同じ (fs, ch) なら arena は触らない (冪等)。
    // fs が変わったら arena を組み直し、FIR の状態は捨てる (taps / M / FDL すべて fs 依存)。
    void configure(double sample_rate, int channels, Structure structure);

    // このインスタンスが FIR の作業領域 (arena) を持ってよいか。
    // **許可であって要求ではない** — true にしても FIR は始まらない
    // (始めるのは共有メモリの高精度フラグ = setFirEnabled)。
    //
    // **いつ呼んでもよい。**configure の前でも後でも、何度でも。順序に依存しない。
    // 同じ値での呼び出しは完全な no-op なので、鳴っている FIR が畳まれることはない。
    //
    // ⚠️ **既定を false にしないこと。**呼び忘れた `.so` では高精度が黙って効かなくなる。
    // 既定 true なら呼び忘れは「使わない arena を持つ」だけで、機能は死なない。
    // **失敗する向きは必ず「動くが無駄」側へ倒す** (TERM.md: 自分を無効化する型を作らない)。
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
    // FDL が満ちる目標 (K = ceil(taps/P))。fill と並べると温めがどこまで進んだか分かる。
    int      fdlPartitions() const { return kernel_.partitions(); }
    // いまのブロック長で FIR を回せるか。**不適の累計 (unfitSize/unfitBudget) とは別物** —
    // あちらは「起きたことがある」、こちらは「いま起きている」。現地診断で
    // 「このスレッドのブロック長では始まらない」を 1 目で読むために要る。
    bool     blockOk() const { return p_ok_; }
    uint32_t rebuilds() const { return rebuilds_; }          // designer の起動回数
    uint32_t faceFades() const { return face_fades_; }       // FIR→FIR の差し替え回数
    // **鳴っていた FIR が端末側の都合で止まり biquad へ乗り換わった回数。**
    // 現地診断で「この機種では FIR が保てない」を読むための値なので、
    // ユーザ操作 (モード OFF) も、音の経路が変わらない事象 (設計器の失敗・準備中の
    // 取り止め・DISABLE の完了) も**混ぜない。**混ざるとこのカウンタの役目が消える。
    uint32_t fallbacks() const { return fallbacks_; }
    uint32_t modeOffs() const { return mode_offs_; }         // ユーザがモードを切った回数
    uint32_t designFailures() const { return design_failures_; }  // 設計器が非有限で止まった回数
    uint32_t unfitSizeCount() const { return unfit_size_; }  // P が大きさで不適
    uint32_t unfitBudgetCount() const { return unfit_budget_; }  // P が予算で不適
    // 曲線が検査に落ちて採用されなかった回数。
    // **本番では上がらない** — 共有メモリの読み手 (dsp/ca_eq_poll.h) が、枠の更新を
    // 丸ごと採るか丸ごと捨てるかを決めるために `curveValid` を先に通すため。
    // ここは**その poll を通らない呼び手 (ハーネス・将来の別経路) のための最後の砦**で、
    // 死んだコードではない。検査の定義は `caeq::curveValid` ただ 1 つで、
    // 呼び出し側が 2 つあるだけ (数えている事象が違う —
    // poll 側 = 枠の更新ごと棄却、こちら = 曲線の採用直前)。
    uint32_t curveRejected() const { return curve_rejected_; }
    // FIR の作業領域を持ってよいインスタンスか (setFirCapable の現在値)。診断が読む。
    bool     firCapable() const { return fir_capable_; }
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
    // FIR を畳む理由。**カウンタの意味を決めるのはここ 1 箇所。**
    // 「数えるか」と「Eq を起こすか」は独立していて、下の 3 通りで組み合わせが尽きる。
    enum class Drop : uint8_t {
        kHard,     // 端末側の都合 (P/fs/ch 変化・RESET・arena 喪失)。落下として数える + 起こす
        kHandoff,  // このインスタンスが FIR の宿主でなくなった。数えないが**起こす**
        kQuiet,    // 音の経路が変わらない (準備中の取り止め・DISABLE 完了・モード OFF 完了)
    };

    void releaseArena();
    void reserveArena();
    // いまの fs_ / ch_ で arena を組む。**configure と setFirCapable の両方から呼ぶ** —
    // 組み直しに要るものを 1 箇所に集めておかないと、後から確保した経路でだけ
    // 初期化が抜ける (`p_cur_` を戻し忘れると evaluateBlock が走らず FIR が永久に始まらない)。
    void reserveForCurrent();
    void invalidateFir(Drop reason);    // FDL 失効 → kBiquad (準備からやり直し)
    bool evaluateBlock(int frames);     // P の合法/予算判定。変化時だけ判定し直す
    void adoptCurveIfDirty();           // dirty なら検査 → designer 再始動
    void startDesigner(int face);
    void stepPrepare(int frames);       // 準備中のスライス (setup 構築 / designer)
    void stepDesigner();                // kFir 中のスライス (失敗の後始末を 1 箇所に)
    double fadeStep() const;            // 1 フレームあたりのフェードの進み (Eq から引く)
    // 混合の規約はファイル冒頭。bq == nullptr なら「biquad の分け前ゼロ」= 素の dry が土台。
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
    // **既定 true。**setFirCapable の説明を参照 — 呼び忘れが「黙って効かない」に
    // ならない向きへ倒してある。
    bool fir_capable_ = true;
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
    // 採用した曲線で設計器が失敗した。**同じ曲線で組み直しても同じ結果**なので、
    // 新しい世代が来るまで再挑戦しない (失敗ループを構造で塞ぐ)。
    bool     curve_failed_ = false;

    int  active_face_    = 0;
    bool face_fading_    = false;
    bool tail_warmed_    = false;
    // 2 つのエンジンの間の乗り移り (0 = biquad だけ、1 = FIR だけ)。biquad 側の分け前は
    // Eq が持つ wet なので、ここには**片側だけ**を持つ (両側を持つと同期が要る = 不具合 2)。
    double fir_mix_ = 0.0;
    double face_w_  = 0.0;

    // FIR 側の wet / preamp。**Eq と同じ意味論** (wet は 1/n 刻みの線形、n = ms·fs/1000)。
    // 同一性はハーネス 26 節が Eq の実出力と突き合わせる。
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
