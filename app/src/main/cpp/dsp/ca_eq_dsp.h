// EQ の演算本体。設計根拠は eq-spec.md §3。
//
// 使う側の約束:
//   - configure() / setParams() / setActive() / reset() は制御スレッド (command()) から。
//     AudioFlinger は command() と process() を相互排他するので、アトミックは要らない。
//   - process() は HAL の専用スレッドから。⚠️ 確保・ロック・ログ・例外・システムコールを
//     一切持ち込まない。
#ifndef CA_EQ_DSP_H_
#define CA_EQ_DSP_H_

#include <cstdint>

#if defined(__FAST_MATH__)
#error "ca_eq_dsp は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

inline constexpr int kMaxBands    = 32;
inline constexpr int kMaxChannels = 32;

inline constexpr int kChunkFrames = 32;

inline constexpr int kDefaultCoefStride = 1;

// 検査範囲は eq-spec.md §3。1 つでも外れたら差し替えを丸ごと却下して前の設定を保つ。
inline constexpr double kMinFcHz   = 1.0;
inline constexpr double kMinQ      = 0.1;
inline constexpr double kMaxQ      = 40.0;
inline constexpr double kMaxGainDb = 40.0;
inline constexpr double kMinPreampDb = -40.0;
inline constexpr double kMaxPreampDb = 12.0;

inline constexpr double kValidFcRatio = 0.49;
// ⚠️ 検査を迂回する経路ができたときに Inf を作らないための二重化 (検査を通った値には効かない)。
inline constexpr double kMaxFcRatio = 0.4995;

// ⚠️ FPCR (FTZ) には触らない (プロセス起動時に立てても届かず、math ライブラリの正しさが崩れる)。
inline constexpr double kDenormalFloor = 1e-200;

enum class BandType : uint8_t {
    kPeaking   = 0,
    kLowShelf  = 1,
    kHighShelf = 2,
};

// 採用は転置形 II。SVF を残すのは、ハーネスで「係数の間違い」と「構造の性質」を
// 切り分ける物差しにするため。選定根拠は eq-spec.md §3「なぜ転置形 II か」。
enum class Structure : uint8_t {
    kTdf2 = 0,
    kSvf  = 1,
};

// 差し替え時の補間対象。採用は係数補間。選定根拠は eq-spec.md §3「補間するのは係数」。
//   kCoef  — 係数を線形補間する
//   kParam — fc / Q / gain を補間して刻みごとに係数を組み直す
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

// RBJ の cookbook そのまま。シェルフは S ではなく Q で受ける (S = 1 が Q = 1/sqrt(2) に対応)。
Coef designTdf2(const Band& band, double sample_rate);

// TPT / トラペゾイダル SVF (Zavalishin / Cytomic)。RBJ と厳密に同じ伝達関数になる。
Coef designSvf(const Band& band, double sample_rate);

Coef design(const Band& band, double sample_rate, Structure structure);

// 同じ fc / Q でゲインだけ 0 dB にした係数。段の増減時の補間の端点に使う
// (極が目標とほぼ同じ場所にあるので、b0=1 の素通しから補間するより軌跡が素直になる)。
Coef unityFor(const Band& band, double sample_rate, Structure structure);

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

    void setRampMillis(double ms);
    double rampMillis() const;

    void setFadeMillis(double ms);
    double fadeMillis() const { return fade_ms_; }

    void setCoefStride(int frames);
    int  coefStride() const { return coef_stride_; }

    // ⚠️ 実際に取り込まれるのは次の process() の先頭で 1 回だけ (eq-spec.md §3「時変安定性」)。
    // 却下したら false を返し、前の設定がそのまま残る。
    bool setParams(const Params& p);

    // 補間もフェードも介さずその場で反映する。create / configure 直後にだけ使う。
    bool snapParams(const Params& p);

    void setActive(bool active);
    bool active() const { return active_; }
    // フェードが終わって完全な素通しになったか。ABI 側が -ENODATA を返してよい合図。
    bool idle() const { return !active_ && wet_cur_ == 0.0; }

    // ⚠️ EFFECT_CMD_RESET と ENABLE のときだけ呼ぶ (パラメータ変更のたびに呼ぶとクリックが
    // 悪化する。eq-spec.md §3)。
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
    double   stateMagnitude() const;

#ifdef CA_EQ_DSP_TEST_HOOKS
    // ハーネス専用。Android のビルドではこのマクロを定義しないので `.so` には無い。
    void injectState(double v);
#endif

private:
    void applyPending();
    void updateRampCoef();
    void finishRamp();
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
    // パラメータ補間 (Interp::kParam) の端点と現在位置。kCoef のときも維持する (eq-dsp-internals.md §7)。
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
