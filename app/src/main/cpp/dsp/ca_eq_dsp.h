// EQ の演算本体。**Android にも Xposed にも一切依存しない。**
//
// 分離の理由は設計上の要求。`.so` からは logcat が出ないので、実機だけで DSP を書くと
// 係数の 1 文字の間違いを共有メモリのピーク値だけで追うことになる。ここが純粋な C++ で
// あるかぎり、ホスト (Windows / Linux) でそのままビルドして test/ のハーネスに掛けられる。
//
// 使う側の約束:
//   - configure() / setParams() / setActive() / reset() は制御スレッド (command()) から。
//     AudioFlinger は command() と process() を相互排他するので、アトミックは要らない。
//   - process() は HAL の専用スレッドから。フレームワークはタイムアウト無しで待つので、
//     **確保・ロック・ログ・例外・システムコールを一切持ち込まない。**
#ifndef CA_EQ_DSP_H_
#define CA_EQ_DSP_H_

#include <cstdint>

// -ffast-math は NaN の検査ごと消す。ここは検査が生きていることが前提の実装なので、
// 付いていたらビルドを止める。FMA (-ffp-contract=fast) は逆に精度が上がるので歓迎。
#if defined(__FAST_MATH__)
#error "ca_eq_dsp は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

// 実測でチャンネル数は 2 と 12 の両方が来る。バンド数は 31 バンドのグラフィック EQ が上限。
inline constexpr int kMaxBands    = 32;
inline constexpr int kMaxChannels = 32;

// process() を刻む単位 (フレーム)。ブロックは実測で 512 / 960 / 1024 / 2048 あり、
// 係数の入れ替えをブロック境界だけに置くと瞬時の入れ替えと完全に同値になる
// (実測でどちらも -64.6 dBFS)。刻み目はブロック長ではなくランプの経過サンプル数で決める。
inline constexpr int kChunkFrames = 32;

// ランプ中に係数を組み直す間隔 (フレーム)。**毎サンプル。**
// ハーネスの実測 (10 ms のランプでゲインを -6 → +12 dB):
//   32 サンプルの階段 : クリック -84.7 dBFS / 156.7 ns/frame
//   毎サンプル        : クリック -114.3 dBFS / 234.9 ns/frame
// 定常が 151.5 ns/frame なので、増えるのはランプが走っている 10 ms のあいだの 55% だけ。
// 30 dB と引き換えにする理由が無い。
inline constexpr int kDefaultCoefStride = 1;

// 受け付けるパラメータの範囲。llmdocs/eq-spec.md §3 の検査表と同じ値にしてある。
// **1 つでも外れたら差し替えを丸ごと却下して前の設定を保つ。**部分適用はしない
// (半分だけ効いた状態は原因の切り分けを不可能にする)。
inline constexpr double kMinFcHz   = 1.0;
inline constexpr double kMinQ      = 0.1;
inline constexpr double kMaxQ      = 40.0;
inline constexpr double kMaxGainDb = 40.0;
inline constexpr double kMinPreampDb = -40.0;
inline constexpr double kMaxPreampDb = 12.0;

// **fc の上限だけがサンプルレートに依存する。**送り手 (アプリ) は fs を知らないので、
// ここが最後の砦になる。Nyquist を超えた fc は RBJ の式で sin(w0) < 0 を作り、
// 極が単位円の外へ出る (= 発散して爆音)。SVF なら tan が発散する。
inline constexpr double kValidFcRatio = 0.49;
// 係数を組むときのクランプ。検査を通った値には効かないが、検査を迂回する経路が
// できたときに Inf を作らないための二重化。
inline constexpr double kMaxFcRatio = 0.4995;

// 状態の合計がこれを下回り、かつ入力が完全な無音なら状態をゼロに落とす。
// 非正規化数 (1e-308 付近) に落ちる手前で刈るための線で、-4000 dBFS 相当なので音には出ない。
// FPCR (FTZ) には触らない — スレッドごとの状態なのでプロセス起動時に立てても届かず、
// 立てたままだと math ライブラリの正しさが保証されなくなる。
inline constexpr double kDenormalFloor = 1e-200;

enum class BandType : uint8_t {
    kPeaking   = 0,
    kLowShelf  = 1,
    kHighShelf = 2,
};

// 実装する構造。**採るのは転置形 II。**
// 両方が同じ伝達関数を別の状態変数で実現するので、ハーネスでは「係数の間違い」と
// 「構造の性質」を切り分ける物差しになる。SVF を残してあるのはそのため。
//
// 決め手はゲイン変更のクリック (ハーネス 7 節)。EQ でいちばん頻繁に動くのがゲインで、
// そこで SVF は 33 dB 負ける — 瞬時の入れ替えで -27.9 対 -64.6、10 ms のランプで
// -81.4 対 -114.3 dBFS。時変安定性では SVF が強いが、そちらは取り込みを process() 1 回に
// つき 1 回へ縛ることで構造的に塞いである (setParams の説明)。処理時間も 1.3〜1.5 倍。
enum class Structure : uint8_t {
    kTdf2 = 0,
    kSvf  = 1,
};

// 差し替えのときに何を補間するか。**採るのは係数補間。**
//   kCoef  — 係数を線形補間する。安い (バンドあたり 6 回の積和)
//   kParam — fc / Q / gain を補間して刻みごとに係数を組み直す。三角関数が要るぶん高い
//
// パラメータ補間のほうが「通る軌跡が実際のフィルタの列になる」ぶん有利に見えるが、
// 実信号のクリックでは差が付かないか係数補間のほうが良い (ハーネス 8 節。fc を 120→480 Hz、
// 10 ms のランプで -121.8 対 -117.9 dBFS)。費用は毎サンプル刻みで 234 対 1082 ns/frame。
enum class Interp : uint8_t {
    kCoef  = 0,
    kParam = 1,
};

inline constexpr Structure kDefaultStructure = Structure::kTdf2;
inline constexpr Interp    kDefaultInterp    = Interp::kCoef;

struct Band {
    BandType type    = BandType::kPeaking;
    double   fc      = 1000.0;
    double   q       = 1.0;
    double   gain_db = 0.0;
};

struct Params {
    int    band_count = 0;
    double preamp_db  = 0.0;
    Band   bands[kMaxBands] = {};
};

// 係数の入れ物。TDF2 は b0 b1 b2 a1 a2 (6 番目は未使用)、SVF は a1 a2 a3 m0 m1 m2。
// 1 つの並びに詰めておくと補間ループが構造によらず 1 本で済む。
struct Coef {
    double c[6] = {0, 0, 0, 0, 0, 0};
};

// RBJ の cookbook そのまま。AutoEQ の Peaking / LowShelf / HighShelf はこの逐語訳なので、
// ここを合わせると取り込んだプリセットがそのまま鳴る。シェルフは S ではなく Q で受ける
// (S = 1 が Q = 1/sqrt(2) に対応する)。
Coef designTdf2(const Band& band, double sample_rate);

// TPT / トラペゾイダル SVF (Zavalishin / Cytomic)。RBJ と厳密に同じ伝達関数になる。
Coef designSvf(const Band& band, double sample_rate);

Coef design(const Band& band, double sample_rate, Structure structure);

// 同じ fc / Q でゲインだけ 0 dB にした係数 (= 伝達関数が 1)。段を増やす・減らすときの
// 補間の端点に使う。極が目標とほぼ同じ場所にあるので、b0=1 の素通しから補間するより
// 通る軌跡が素直になる。
Coef unityFor(const Band& band, double sample_rate, Structure structure);

// 1 つでも外れたら false。sample_rate は fc の上限を決めるために要る。呼び出し側は
// 前の設定を保って診断カウンタを進める
// (黙って捨てると原因不明の「効かない」になる)。
bool validate(const Params& p, double sample_rate);

class Eq {
public:
    Eq();

    // --- 制御スレッド ---------------------------------------------------

    // サンプルレート・チャンネル数・構造。どれかが変わったら状態をゼロにして
    // 係数を組み直し、補間せずに即時反映する (状態がゼロなので段差にならない)。
    void configure(double sample_rate, int channels, Structure structure);

    void setInterp(Interp mode) { interp_ = mode; }
    Interp interp() const { return interp_; }

    // 係数補間の長さ。**サンプル数で保持する** — ブロック数だと 960 と 2048 の
    // スレッドで 2 倍違う長さになる。
    void setRampMillis(double ms);
    double rampMillis() const;

    // enable / disable のフェード長。無しだと -26.7 dBFS のクリックが出る (実測)。
    void setFadeMillis(double ms);
    double fadeMillis() const { return fade_ms_; }

    // ランプ中に係数を組み直す間隔 (フレーム)。1 なら毎サンプル、kChunkFrames なら
    // 32 サンプルの階段。細かいほどクリックが小さくなり、そのぶん計算量が増える。
    // どこで折り合うかはハーネスの実測で決める値なので、定数で焼かずに外へ出してある。
    void setCoefStride(int frames);
    int  coefStride() const { return coef_stride_; }

    // 差し替えを予約する。**実際に取り込まれるのは次の process() の先頭で 1 回だけ。**
    // これは仕様であってケチった実装ではない: 取り込みを process() 1 回につき 1 回に
    // 縛ることが、係数の変調速度に構造的な上限を与える (ブロック 960 なら 50 Hz、
    // 2048 なら 23 Hz)。時変不安定は係数が数百 Hz で振動し続けたときに起きるので、
    // ここで塞いでおくと発散の経路そのものが無くなる。
    // 却下したら false を返し、前の設定がそのまま残る。
    bool setParams(const Params& p);

    // 補間もフェードも介さずその場で反映する。create / configure 直後にだけ使う。
    bool snapParams(const Params& p);

    // 有効・無効。フェードを掛けながら wet を動かす。
    // DISABLE の後もフレームワークは 10 秒 process() を呼び続けるので、
    // フェードを持たないと「OFF にしてから 10 秒掛かったまま」になる。
    void setActive(bool active);
    bool active() const { return active_; }
    // フェードが終わって完全な素通しになったか。ABI 側が -ENODATA を返してよい合図。
    bool idle() const { return !active_ && wet_cur_ == 0.0; }

    // 状態をゼロにする。**EFFECT_CMD_RESET と ENABLE のときだけ呼ぶ。**
    // パラメータ変更のたびにゼロにするとクリックが 27 dB 悪化する (実測 -64.6 → -37.7 dBFS)。
    void reset();

    // 空打ち。初回のページフォルトを process() の外へ出す。create のときに 1 回。
    void warmUp();

    // --- オーディオ経路 --------------------------------------------------

    // インタリーブされた float を frames 分。in == out (in-place) でよい。
    // accumulate が true なら out に足す (EFFECT_BUFFER_ACCESS_ACCUMULATE)。
    // 上書きすると spatializer のチェーンで他のトラックの音を消す。
    void process(const float* in, float* out, int frames, bool accumulate = false);

    // --- 観測 -----------------------------------------------------------

    bool     ramping() const { return ramp_pos_ < ramp_len_; }
    uint32_t rejectedCount() const { return rejected_; }
    uint32_t scrubbedBlocks() const { return scrubbed_; }
    uint32_t stateResets() const { return state_resets_; }
    uint32_t denormalFlushes() const { return denormal_flushes_; }
    int      activeBands() const { return nb_run_; }
    double   sampleRate() const { return sr_; }
    int      channels() const { return ch_; }
    Structure structure() const { return structure_; }
    // 状態の絶対値の合計。診断とハーネス用。
    double   stateMagnitude() const;

#ifdef CA_EQ_DSP_TEST_HOOKS
    // ハーネス専用。Android のビルドではこのマクロを定義しないので `.so` には無い。
    void injectState(double v);
#endif

private:
    void applyPending();
    void updateRampCoef();
    void finishRamp();
    // 戻り値は入力の絶対値の合計。NaN / Inf の検出と無音判定を兼ねる。
    double processChunk(const float* in, float* out, int n, bool accumulate);
    void clearState();
    void clearBandState(int band);
    void rebuildFromParams();

    double    sr_        = 48000.0;
    int       ch_        = 2;
    Structure structure_ = kDefaultStructure;
    Interp    interp_    = kDefaultInterp;

    Params  params_{};       // いま鳴っている設定
    Params  pending_{};      // 次の process() で取り込む設定
    bool    has_pending_ = false;

    int     nb_run_    = 0;  // いま走らせている段数 (ランプ中は max(旧, 新))
    int     nb_target_ = 0;

    // 係数補間の端点と、いま使っている値。
    Coef    from_[kMaxBands]{};
    Coef    to_[kMaxBands]{};
    Coef    cur_[kMaxBands]{};
    // パラメータ補間 (Interp::kParam) の端点と現在位置。kCoef のときも維持しておく —
    // ランプの途中で次の差し替えが来たとき、起点になるのはここ。
    Band    band_from_[kMaxBands]{};
    Band    band_to_[kMaxBands]{};
    Band    band_cur_[kMaxBands]{};

    double  pre_from_ = 1.0;
    double  pre_to_   = 1.0;
    double  pre_cur_  = 1.0;

    int64_t ramp_len_    = 480;   // 10 ms @ 48 kHz
    int64_t ramp_pos_    = 480;   // pos >= len でランプ終了
    int     coef_stride_ = kDefaultCoefStride;

    bool    active_    = false;
    double  wet_cur_   = 0.0;
    double  wet_target_ = 0.0;
    double  wet_step_  = 1.0;  // 1 サンプルあたりの wet の増減
    double  fade_ms_   = 10.0;

    // 状態。並びは [band][ch] で、内部ループがチャンネル (バンドの連鎖は直列、
    // チャンネルは独立) になるようにしてある。stride は実行時のチャンネル数。
    double  s1_[kMaxBands * kMaxChannels]{};
    double  s2_[kMaxBands * kMaxChannels]{};
    // 段の間で float に丸めないための作業領域。process() では確保しない。
    double  scratch_[kChunkFrames * kMaxChannels]{};
    double  dry_[kChunkFrames * kMaxChannels]{};

    uint32_t rejected_         = 0;
    uint32_t scrubbed_         = 0;
    uint32_t state_resets_     = 0;
    uint32_t denormal_flushes_ = 0;
};

}  // namespace caeq

#endif  // CA_EQ_DSP_H_
