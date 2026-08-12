#include "ca_eq_pipeline.h"

#include <cmath>
#include <cstring>
#include <new>

namespace caeq {
namespace {

// FIR → FIR の面クロスフェード長。**これだけは Eq と無関係** (両側とも FIR なので
// Eq の wet が関与しない)。エンジン間の乗り移りは Eq のフェード長に従う (fadeStep)。
constexpr double kFaceFadeMs = 10.0;

inline size_t align64(size_t v) { return (v + 63u) & ~static_cast<size_t>(63u); }

inline double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

// 2P の setup 構築 (in-place) の費用モデル。sincos が支配する O(N)。
// 係数はホスト実測を上に丸めた保守値 (fircost と同じ流儀。会計はハーネス 25 節)。
constexpr int64_t kSetupNsPerElem = 120;
inline int64_t setupBuildNs(int n) { return static_cast<int64_t>(n) * kSetupNsPerElem; }

}  // namespace

// 1 フレームあたりのフェードの進み。**Eq のフェード長から毎回引く** — pipeline 側に
// 長さを持つと `biquad().setFadeMillis()` で変えられたときに食い違い、
// クロスフェードの和が 1 でなくなる (両エンジンが同じ応答でも音量が動く)。
// **configure() で焼かないこと** — configure 後の setFadeMillis に追随しなくなる。
// 式は Eq::setFadeMillis と同一にしてあること。
double EqPipeline::fadeStep() const {
    double n = eq_.fadeMillis() * fs_ / 1000.0;
    if (n < 1.0) n = 1.0;
    return 1.0 / n;
}

EqPipeline::~EqPipeline() { releaseArena(); }

void EqPipeline::releaseArena() {
    // 順序が要点: invalidateFir (中の kernel.reset が tail へ memset する) は
    // arena が生きているうちに。その後 unbind してから解放する — 逆にすると
    // 解放済みの領域へ書いて heap を壊す (実際に踏んだ)。
    // **arena が消えるのは端末側の都合 (fs/ch 変化) なので落下扱い。**
    invalidateFir(Drop::kHard);
    kernel_.unbind();
    plan_m_.release();
    plan_2p_.release();
    if (arena_ != nullptr) {
        ::operator delete(arena_, std::align_val_t(64));
        arena_ = nullptr;
    }
    arena_bytes_ = 0;
    // **区画へのポインタを残さない。**残すと解放済みの領域を指したままになり、
    // 「null なら触らない」という書き方の柵が全部死んだ枝になる。
    mem_setup_m_  = nullptr;
    mem_setup_2p_ = nullptr;
    bytes_setup_2p_ = 0;
    buf_data_    = nullptr;
    buf_work_    = nullptr;
    buf_fdl_     = nullptr;
    buf_filt_[0] = nullptr;
    buf_filt_[1] = nullptr;
    buf_tail_    = nullptr;
    buf_stage_   = nullptr;
    buf_acc_     = nullptr;
    buf_acc2_    = nullptr;
    buf_fir_y_   = nullptr;
    buf_bq_y_    = nullptr;
    have_curve_  = false;
    curve_dirty_ = false;
}

// 最悪の合法 P に対する K·2P (floats)。FDL とフィルタ面の予約はこれで測る。
// K·2P = ceil(taps/P)·2P は P に対して単調でない (端数のゼロ詰め分が波打つ) ので、
// 上限 4096 までの合法 P を全部歩く。数十個なので configure() で回してよい。
static size_t worstPartitionFloats(int taps) {
    size_t worst = 0;
    for (int p = 16; p <= kMaxConvBlock; p += 16) {
        if (!convBlockSizeValid(p)) continue;
        const size_t kn = static_cast<size_t>(firPartitions(taps, p)) *
                          static_cast<size_t>(2 * p);
        if (kn > worst) worst = kn;
    }
    return worst;
}

void EqPipeline::reserveArena() {
    // 区画の大きさはすべて実行時に計算する (pffft_setup_bytes / 合法 P の列挙)。
    // バイト数の literal を焼くと、pffft の構造体が変わったときに黙ってずれる。
    const size_t setup_m_bytes  = fftSetupBytes(m_);
    const size_t setup_2p_bytes = fftSetupBytes(2 * kMaxConvBlock);  // 単調なので最大で予約
    const size_t max_n  = static_cast<size_t>(2 * kMaxConvBlock);
    const size_t max_kn = worstPartitionFloats(taps_);
    const size_t chs    = static_cast<size_t>(ch_);
    const size_t blk    = static_cast<size_t>(kMaxConvBlock);

    size_t off = 0;
    auto carve = [&off](size_t bytes) {
        const size_t at = align64(off);
        off = at + bytes;
        return at;
    };
    const size_t o_setup_m  = carve(setup_m_bytes);
    const size_t o_setup_2p = carve(setup_2p_bytes);
    const size_t o_data  = carve(sizeof(float) * static_cast<size_t>(m_));
    const size_t o_work  = carve(sizeof(float) * static_cast<size_t>(m_));
    const size_t o_fdl   = carve(sizeof(float) * chs * max_kn);
    const size_t o_filt0 = carve(sizeof(float) * max_kn);
    const size_t o_filt1 = carve(sizeof(float) * max_kn);
    const size_t o_tail  = carve(sizeof(float) * chs * blk);
    const size_t o_stage = carve(sizeof(float) * max_n);
    const size_t o_acc   = carve(sizeof(float) * max_n);
    const size_t o_acc2  = carve(sizeof(float) * max_n);
    const size_t o_fir_y = carve(sizeof(float) * chs * blk);
    const size_t o_bq_y  = carve(sizeof(float) * chs * blk);
    const size_t total   = align64(off);

    arena_ = ::operator new(total, std::align_val_t(64), std::nothrow);
    if (arena_ == nullptr) {
        arena_bytes_ = 0;
        return;  // FIR なしで生きる (firAvailable() = false、biquad は無傷)
    }
    arena_bytes_ = total;
    char* base = static_cast<char*>(arena_);
    mem_setup_m_    = base + o_setup_m;
    mem_setup_2p_   = base + o_setup_2p;
    bytes_setup_2p_ = setup_2p_bytes;
    buf_data_    = reinterpret_cast<float*>(base + o_data);
    buf_work_    = reinterpret_cast<float*>(base + o_work);
    buf_fdl_     = reinterpret_cast<float*>(base + o_fdl);
    buf_filt_[0] = reinterpret_cast<float*>(base + o_filt0);
    buf_filt_[1] = reinterpret_cast<float*>(base + o_filt1);
    buf_tail_    = reinterpret_cast<float*>(base + o_tail);
    buf_stage_   = reinterpret_cast<float*>(base + o_stage);
    buf_acc_     = reinterpret_cast<float*>(base + o_acc);
    buf_acc2_    = reinterpret_cast<float*>(base + o_acc2);
    buf_fir_y_   = reinterpret_cast<float*>(base + o_fir_y);
    buf_bq_y_    = reinterpret_cast<float*>(base + o_bq_y);

    if (!plan_m_.initInPlace(m_, mem_setup_m_, setup_m_bytes)) {
        // m_ は firDefaultM が返す合法サイズなのでここには来ないはずだが、
        // 来たら FIR を丸ごと諦める (biquad は無傷)。
        releaseArena();
    }
}

void EqPipeline::reserveForCurrent() {
    taps_ = firTapsFor(fs_);
    m_    = firDefaultM(fs_);
    reserveArena();
    pre_len_ = static_cast<int64_t>(eq_.rampMillis() * fs_ / 1000.0 + 0.5);
    // **`p_cur_` を 0 に戻すことが要点。**process() は `frames != p_cur_` のときしか
    // evaluateBlock を呼ばないので、戻さないと `p_ok_` が false のまま据え置かれ、
    // arena はあるのに FIR が永久に始まらない。
    p_cur_ = 0;
    p_ok_  = false;
}

void EqPipeline::configure(double sample_rate, int channels, Structure structure) {
    eq_.configure(sample_rate, channels, structure);
    // Eq と同じ受け方で、FIR 側の可否だけここで決める。
    if (!std::isfinite(sample_rate) || sample_rate <= 0.0) return;
    int ch = channels;
    if (ch < 1) ch = 1;

    // arena を持つのは「宿主になれる」インスタンスの ch ≤ 2 のときだけ
    // (eq-fir-design.md §2 — 12ch の spatializer には作らない)。
    // **`fs_` / `ch_` はこの経路でも必ず入れる** — 後から setFirCapable(true) が
    // 来たときに、ここが空だとその場で確保できない。
    if (ch > 2 || !fir_capable_) {
        releaseArena();
        fs_ = sample_rate;
        ch_ = ch;
        return;
    }
    if (arena_ != nullptr && sample_rate == fs_ && ch == ch_) {
        return;  // 冪等 — SET_CONFIG は同じ値で何度も来る
    }
    releaseArena();
    fs_ = sample_rate;
    ch_ = ch;
    reserveForCurrent();
}

// **`process()` も遷移の状態機械も 1 行も変えない。**保つ不変条件は
// 「`!fir_capable_` ⟹ `arena_ == nullptr`」の 1 本だけで、`can_hold` が既に
// `arena_ != nullptr` を見ているので、オーディオ経路には条件が 1 つも増えない。
// (段 1 のブロッカー 1 は「区画を触る経路が検査済み側に寄っていなかった」ことが
//  原因だった。経路を増やさない形が一番安全。)
void EqPipeline::setFirCapable(bool capable) {
    // **`false → false` で下へ落とさないこと。**`releaseArena()` は arena だけでなく
    // `curve_dirty_` / `have_curve_` も落とすので、**まだ採用していない曲線が捨てられる。**
    // fs 変化には SET_CONFIG → `PollState::param_gen = 0` の戻しがあるが、capability の
    // 変更には無いので **poll は再送せず (世代が同じなら早期 return)、ユーザが曲線を
    // 触るまで高精度が黙って戻らない。**釘は 30 節 (7)。
    //
    // (`true → true` 側は下の `arena_ == nullptr` で弾かれるので、こちらは冗長。
    //  変異試験でも通ってしまうので、そちらを load-bearing と書かないこと。)
    //
    // ⚠️ 落とし穴の根は `releaseArena()` が「作業領域を解放する」と「曲線を忘れる」を
    // 束ねていること。曲線は arena と独立したデータなので概念的には分けられるが、
    // fs 変化の往復 (31 節) が今の束ね方の上で通っているので、いま解くのは割に合わない。
    //
    // ⚠️ **本物の降格 (`true → false → true`) は、届いていた曲線をそのまま落とす。**
    // `false` で `releaseArena()` が曲線を忘れ、`true` に戻しても poll は世代が同じなら
    // 早期 return するので**再送されない。**いまこれを踏まないのは運ではなく構造で、
    // **`.so` が `setFirCapable` を撃つのは create で 1 回 (session 判定) と
    // `EFFECT_CMD_SET_PARAM` の `CA_PARAM_ID_SLOT` (常に `true` へ引き上げ) の 2 箇所だけ**
    // — `false` へ落とす経路が存在しない。**「いまは踏めない」ではなく「経路が無い」。**
    //
    // したがって **`false` へ落とす呼び手を足した瞬間に踏む。**具体的には
    // `llmdocs/hold-process.md` §7 の退路段 — 保持プロセスが `SET_PARAM` で枠を
    // 振り直す構成では、宛先が動くたびに `capable` が両方向へ動きうる。
    // そこを実装するなら、先に `releaseArena()` から「曲線を忘れる」を切り離すこと。
    if (capable == fir_capable_) return;
    fir_capable_ = capable;
    if (!capable) {
        // 鳴っていたなら Eq を起こしてから畳む。**落下 (fallbacks_) には数えない** —
        // あのカウンタは「この機種では FIR が保てない」を読むための値で、
        // 枠の宛先が動いたのはそれではない。
        invalidateFir(Drop::kHandoff);
        releaseArena();
        return;
    }
    // configure より先に呼ばれたら fs_ がまだ無い。フラグだけ持って configure に任せる。
    // **後から呼ばれた場合はここで確保する** — configure は同じ fs/ch なら冪等に
    // return するので、次の configure を待つと永久に確保されない (順序依存の罠)。
    if (fs_ > 0.0 && ch_ >= 1 && ch_ <= 2 && arena_ == nullptr) reserveForCurrent();
}

bool EqPipeline::setParams(const Params& p) {
    const bool ok = eq_.setParams(p);
    if (ok) {
        // FIR 側の preamp は IR に焼かず、スカラを 10 ms でランプする
        // (プリアンプだけの変更で再構築を走らせない — eq-fir-design.md §2)。
        pre_from_ = pre_cur_;
        pre_to_   = std::pow(10.0, p.preamp_db / 20.0);
        pre_pos_  = 0;
    }
    return ok;
}

bool EqPipeline::snapParams(const Params& p) {
    const bool ok = eq_.snapParams(p);
    if (ok) {
        pre_cur_ = pre_from_ = pre_to_ = std::pow(10.0, p.preamp_db / 20.0);
        pre_pos_ = pre_len_;
    }
    return ok;
}

void EqPipeline::setActive(bool active) {
    active_ = active;
    // kFir / kFadeIn のあいだ Eq は「降りる側」として止めてある。ここで起こすと
    // FIR と biquad が二重に鳴るので、向きの指示は遷移側に任せる
    // (DISABLE は fir_wet_ が担い、素通しに着いてから Eq へ渡る)。
    if (state_ == FirState::kBiquad || state_ == FirState::kPrepare ||
        state_ == FirState::kFadeOut) {
        eq_.setActive(active);
    }
}

void EqPipeline::reset() {
    // EFFECT_CMD_RESET。履歴は全部無効になるので FIR も畳む。**端末側の都合なので落下。**
    // 止めてある Eq を起こすのは invalidateFir(kHard) がやる (鳴っていた場合だけ)。
    eq_.reset();
    invalidateFir(Drop::kHard);
}

void EqPipeline::warmUp() {
    eq_.warmUp();
    // 初回のページフォルトを process() の外へ。arena を 1 ページごとに触る。
    if (arena_ != nullptr) {
        volatile char* p = static_cast<volatile char*>(arena_);
        for (size_t i = 0; i < arena_bytes_; i += 4096) p[i] = p[i];
    }
}

void EqPipeline::setClock(uint64_t (*now_ns)()) { now_ns_ = now_ns; }

void EqPipeline::setFirEnabled(bool enabled) { fir_enabled_ = enabled; }

void EqPipeline::setCurve(const float* db401, uint32_t generation) {
    if (db401 == nullptr) return;
    // ⚠️ **`have_curve_` を条件から外さないこと。**`releaseArena()` は `have_curve_` を
    // 落とすが `pending_gen_` は残すので、fs や ch が変わって arena を組み直した後も
    // 世代だけは同じに見える。世代の一致だけで戻ると、**同じ曲線を渡し直しても受け取らず、
    // 曲線が変わるまで FIR が戻らない。**
    // 渡し直す側の経路は SET_CONFIG → `PollState::param_gen = 0` → 次の poll で再読み
    // (dsp/ca_eq_poll.h)。ハーネス 31 節の「fs が変わっても FIR が戻る」がこの往復を見ている。
    if (generation == pending_gen_ && have_curve_) return;
    std::memcpy(pending_curve_, db401, sizeof(pending_curve_));
    pending_gen_ = generation;
    curve_dirty_ = true;
}

bool EqPipeline::idle() const {
    // 完全な素通しか。**両方のエンジンが黙っていること**が条件 —
    // 片方だけ見ると、もう片方のフェードが残っているうちに -ENODATA を返す。
    if (state_ == FirState::kBiquad) return eq_.idle();
    return !active_ && fir_wet_ == 0.0 && eq_.idle();
}

void EqPipeline::invalidateFir(Drop reason) {
    // FIR の分け前が実際に音に出ていたか。**カウンタも Eq の起こし方もここで決まる。**
    // **「数える」と「起こす」は別の問い。**kQuiet だけが起こさない —
    // あれは Eq が既に自分で降りている場面 (DISABLE 完了・モード OFF 完了) か、
    // まだ音に出ていない場面 (準備中の取り止め) なので、起こすと二重に鳴る。
    // 逆に kHandoff で起こさないと、**駐機中 (wet 0) の Eq へ渡して素通しの段差になる。**
    const bool was_audible = (state_ == FirState::kFadeIn || state_ == FirState::kFir ||
                              state_ == FirState::kFadeOut);
    if (reason == Drop::kHard && was_audible) fallbacks_++;
    if (reason != Drop::kQuiet && was_audible) {
        // kFir では Eq を 1 度も回していないので内部状態が古い。ゼロにしてから
        // 自身の wet で立ち上げる (pipeline 側にゲートを持たない — 真は Eq の wet 1 つ)。
        if (state_ == FirState::kFir) eq_.reset();
        eq_.setActive(active_);
    }
    kernel_.reset();
    designer_.abort();
    tail_warmed_ = false;
    face_fading_ = false;
    fir_mix_     = 0.0;
    face_w_      = 0.0;
    active_gen_  = 0;   // 何も鳴っていない
    state_       = FirState::kBiquad;
}

// P (ブロック長) が変わった。合法か・予算に収まるかを判定し直す。
//
// **arena の区画 (fir_y / bq_y / stage / acc は kMaxConvBlock を上限に予約してある) を
// 守る不変条件はここが唯一の門。**`p_ok_` が立っているあいだだけ arena のバッファに
// frames 分を書いてよい。上限を超える frames は必ずここで p_ok_ を落とすので、
// biquad だけの経路 (out へ直接書く) に落ちて arena には触れない。
bool EqPipeline::evaluateBlock(int frames) {
    // 履歴は P に紐づく — 丸ごと失効。**大きさが理由なので落下 (端末側の都合) 扱い。**
    if (state_ != FirState::kBiquad) invalidateFir(Drop::kHard);
    p_cur_ = frames;
    p_ok_  = false;
    if (arena_ == nullptr) return false;
    if (frames > kMaxConvBlock || !convBlockSizeValid(frames)) {
        unfit_size_++;
        return false;
    }
    budget_ns_ = static_cast<int64_t>(kSliceBudgetFrac * static_cast<double>(frames) /
                                      fs_ * 1e9);
    const int64_t q_fft   = fircost::maxQuantumNs(m_);
    const int64_t q_setup = setupBuildNs(2 * frames);
    const int64_t q_max   = q_fft > q_setup ? q_fft : q_setup;
    if (q_max > budget_ns_) {
        unfit_budget_++;
        return false;
    }
    p_ok_ = true;
    return true;
}

void EqPipeline::adoptCurveIfDirty() {
    if (!curve_dirty_ || face_fading_) return;  // 面フェード中は両面が使用中 — 終わってから
    curve_dirty_ = false;
    if (!curveValid(pending_curve_)) {
        curve_rejected_++;
        return;  // 前の曲線 (と鳴っている面) を保つ
    }
    std::memcpy(active_curve_, pending_curve_, sizeof(active_curve_));
    adopting_gen_ = pending_gen_;
    have_curve_   = true;
    curve_failed_ = false;  // 新しい曲線なので再挑戦してよい
    // 再始動 (FDL は生かす)。鳴っている面があるなら裏の面へ、無ければ同じ面へ組み直す。
    const int face = (state_ == FirState::kFir || state_ == FirState::kFadeIn ||
                      state_ == FirState::kFadeOut)
                         ? 1 - active_face_
                         : active_face_;
    startDesigner(face);
}

// 設計器を 1 スライス進め、失敗したら後始末する。**失敗は「音の経路が変わらない」事象**
// (準備中なら biquad のまま、kFir なら表の面が鳴り続ける) なので落下には数えない。
// 同じ曲線での再挑戦も封じる — 数学的に同じ結果になるので、回し続けると成果ゼロのまま
// 毎ブロック FFT を焼き続けることになる (電池と診断の両方を壊す)。
void EqPipeline::stepDesigner() {
    if (!designer_.running()) return;
    const uint64_t t0 = now_ns_ != nullptr ? now_ns_() : 0;
    designer_.step(budget_ns_);
    if (now_ns_ != nullptr) {
        const uint64_t dt = now_ns_() - t0;
        if (dt > max_slice_ns_) max_slice_ns_ = dt;
    }
    if (designer_.failed()) {
        designer_.abort();
        design_failures_++;
        curve_failed_ = true;
    }
}

void EqPipeline::startDesigner(int face) {
    FirDesignSpec spec;
    spec.curve_db = active_curve_;
    spec.fs       = fs_;
    spec.taps     = taps_;
    spec.m        = m_;
    spec.block    = p_cur_;
    spec.window   = FirWindow::kTailTaper;   // 既定の根拠は ca_eq_fir.h (ゲート実測)
    spec.taper    = firDefaultTaper(taps_);
    designer_.abort();
    if (designer_.start(spec, &plan_m_, &plan_2p_, buf_data_, buf_work_, buf_filt_[face])) {
        rebuilds_++;
    }
}

// 準備中のスライス 1 回分。2P の setup 構築は 1 クォンタム (このブロックはそれで終わり)。
void EqPipeline::stepPrepare(int frames) {
    const uint64_t t0 = now_ns_ != nullptr ? now_ns_() : 0;

    if (!plan_2p_.ready() || plan_2p_.size() != 2 * frames) {
        if (plan_2p_.initInPlace(2 * frames, mem_setup_2p_, bytes_setup_2p_)) {
            FirKernel::Buffers kb;
            kb.fdl     = buf_fdl_;
            kb.filt[0] = buf_filt_[0];
            kb.filt[1] = buf_filt_[1];
            kb.tail    = buf_tail_;
            kb.stage   = buf_stage_;
            kb.acc     = buf_acc_;
            kb.acc2    = buf_acc2_;
            kb.work    = buf_work_;
            if (!kernel_.bind(&plan_2p_, frames, taps_, ch_, kb)) {
                p_ok_ = false;  // 予約の想定外 — FIR を諦める (biquad は無傷)
                unfit_size_++;
            }
        } else {
            p_ok_ = false;
            unfit_size_++;
        }
    } else if (designer_.running()) {
        stepDesigner();
    } else if (!designer_.done()) {
        adoptCurveIfDirty();
        if (!designer_.running() && have_curve_ && !curve_failed_) {
            startDesigner(active_face_);
        }
    }
    // **準備を続ける根拠が無くなったら kPrepare から出る。**設計器が失敗して
    // 再挑戦も封じられている状態で留まると、毎ブロック FDL に 2P FFT を ch 本
    // 焼き続けながら永久に完成しない (かつ firState() が「準備中」と嘘をつく)。
    if (designer_.running() || designer_.done()) {
        state_ = FirState::kPrepare;
    } else if (curve_failed_ || !have_curve_) {
        state_ = FirState::kBiquad;
    }

    if (now_ns_ != nullptr) {
        const uint64_t dt = now_ns_() - t0;
        if (dt > max_slice_ns_) max_slice_ns_ = dt;
    }
}

// FIR 側の出力の混合。bq が非 null なら biquad → FIR のクロスフェード中で、
// v = v0 + i·dv (0→1) で乗り移る。wet / preamp は Eq と同じ意味論で 1 フレームずつ進める。
// 混合の規約 (ファイル冒頭):
//   out = eq_out + (fir·preamp − dry) · fir_wet_ · fir_mix_
// bq == nullptr は「biquad の分け前ゼロ」= eq_out が dry そのもの (kFir。Eq は wet 0 で
// 止めてあり、回してすらいない)。mix は mix0 + i·dmix を [0,1] に留めた値。
void EqPipeline::mixFirOut(const float* in, float* out, int frames, bool accumulate,
                           const float* bq, double mix0, double dmix) {
    const int ch = ch_;
    for (int i = 0; i < frames; i++) {
        // preamp のランプ (Eq の pre_cur_ 補間と同じ線形)
        if (pre_pos_ < pre_len_) {
            const double u = static_cast<double>(pre_pos_) / static_cast<double>(pre_len_);
            pre_cur_ = pre_from_ + (pre_to_ - pre_from_) * u;
            pre_pos_++;
        } else {
            pre_cur_ = pre_to_;
        }
        // FIR 経路の wet (Eq の processChunk と同じ「使ってから進める」)
        const double wet    = fir_wet_;
        const double target = active_ ? 1.0 : 0.0;
        const double step   = fadeStep();
        if (fir_wet_ < target) {
            fir_wet_ += step;
            if (fir_wet_ > target) fir_wet_ = target;
        } else if (fir_wet_ > target) {
            fir_wet_ -= step;
            if (fir_wet_ < target) fir_wet_ = target;
        }
        const double mix   = clamp01(mix0 + static_cast<double>(i) * dmix);
        const double share = wet * mix;

        const size_t base = static_cast<size_t>(i) * static_cast<size_t>(ch);
        for (int c = 0; c < ch; c++) {
            const size_t at  = base + static_cast<size_t>(c);
            // dry 側の NaN を潰す。kernel は FDL に入る前に潰している (= y は有限) が、
            // share < 1 の混合で dry がそのまま出る経路が残る。
            const float raw  = in[at];
            const double dry = std::isfinite(raw) ? static_cast<double>(raw) : 0.0;
            const double y   = static_cast<double>(buf_fir_y_[at]) * pre_cur_;
            // 土台は Eq の出力 (既に dry + (bq − dry)·eq_wet が入っている)。
            const double eq_out = (bq != nullptr) ? static_cast<double>(bq[at]) : dry;
            const double mixed  = eq_out + (y - dry) * share;
            if (accumulate) {
                out[at] += static_cast<float>(mixed);
            } else {
                out[at] = static_cast<float>(mixed);
            }
        }
    }
}

void EqPipeline::process(const float* in, float* out, int frames, bool accumulate) {
    if (in == nullptr || out == nullptr || frames <= 0) return;

    // **arena の区画を守る門。**ここを通った後、p_ok_ が立っていれば frames は
    // kMaxConvBlock 以下であることが保証される (evaluateBlock の説明)。
    if (frames != p_cur_) evaluateBlock(frames);

    // 「FIR を保てるか」と「新たに始めてよいか」を分ける。DISABLE (active_ が false) は
    // 即座に畳まず、fir_wet_ のフェードアウトを完走させてから静かに降りる。
    const bool can_hold = arena_ != nullptr && p_ok_;
    const bool want_fir = can_hold && fir_enabled_;
    // 使える曲線: 採用済みで失敗していないもの、または**まだ採用していない新着**
    // (採用は準備の中で行う。ここで have_curve_ だけを見ると、最初の 1 本が
    // 「採用されないと始まらない / 始まらないと採用されない」で永久に立ち上がらない)。
    const bool curve_usable = (have_curve_ && !curve_failed_) || curve_dirty_;
    const bool can_start    = want_fir && active_ && curve_usable;

    // 端末側の都合で保てなくなった (P/fs/ch 変化・arena 喪失)。鳴っていたなら落下。
    if (!can_hold && state_ != FirState::kBiquad) invalidateFir(Drop::kHard);
    // ユーザがモードを切った。FIR は健在なので**対称なフェードで降りる。**
    if (can_hold && !fir_enabled_) {
        if (state_ == FirState::kFir || state_ == FirState::kFadeIn) {
            mode_offs_++;
            eq_.reset();              // kFir では回していないので状態が古い
            eq_.setActive(active_);   // Eq 自身の wet が biquad 側の昇りを担う
            // **kFadeOut は単面で回すので、進行中の面フェード (FIR→FIR) は中断される。**
            // 中断された側は次に FIR を組み直すときに作り直されるだけで、残骸は
            // 残らない。曲線変更から 10 ms 以内にモード OFF が要るので実運用では
            // 起きない — 経路を増やす価値が無いと判断した (検分の指摘 B)。
            state_  = FirState::kFadeOut;
        } else if (state_ == FirState::kPrepare) {
            invalidateFir(Drop::kQuiet);  // まだ音に出ていない — 静かに捨てる
        }
    }

    switch (state_) {
    case FirState::kBiquad:
    case FirState::kPrepare: {
        // **biquad だけの経路は out へ直接書く。**arena のバッファを経由しないので、
        // frames がどれだけ大きくても区画外へ出ない (ブロッカー 1 の根本)。
        eq_.process(in, out, frames, accumulate);
        // fir_wet_ は FIR の混合ループでしか進まないので、ここでも軌跡を揃えておく
        // (次に FIR 経路へ入るときの初期値)。
        const double target = active_ ? 1.0 : 0.0;
        const double step   = fadeStep() * static_cast<double>(frames);
        if (fir_wet_ < target) fir_wet_ = fir_wet_ + step > target ? target : fir_wet_ + step;
        if (fir_wet_ > target) fir_wet_ = fir_wet_ - step < target ? target : fir_wet_ - step;

        if (state_ == FirState::kPrepare) {
            if (!can_start) {
                invalidateFir(Drop::kQuiet);  // まだ音に出ていない — 静かに捨てる
                break;
            }
            adoptCurveIfDirty();  // 準備中に新しい曲線が来たら組み直す (rebuilds++)
            // 入力側は常時回す。designer 完了 && FDL が今回で満ちるなら、
            // processBlock (押し込み込み) で尻尾も温めて出力は捨てる。
            if (designer_.done() && kernel_.fill() >= kernel_.partitions() - 1 &&
                !tail_warmed_) {
                kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
                tail_warmed_ = true;
            } else {
                kernel_.pushBlock(in);
                stepPrepare(frames);
            }
            if (designer_.done() && kernel_.ready() && tail_warmed_) {
                designer_.abort();  // 面は組み上がった — 状態機械としては空へ
                fir_mix_ = 0.0;
                // Eq 自身のフェードアウトが biquad 側の降りを担う。**pipeline 側に
                // biquad 用の wet を持たない**ので、kFir で置き去りになる状態が無い。
                eq_.setActive(false);
                state_ = FirState::kFadeIn;
            }
        } else if (can_start) {
            stepPrepare(frames);  // setup 構築 → designer 起動 (state が kPrepare へ)
        }
        break;
    }
    case FirState::kFadeIn:
    case FirState::kFadeOut: {
        const bool in_ = state_ == FirState::kFadeIn;
        eq_.process(in, buf_bq_y_, frames, false);
        kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        // **Eq のフェード長に従う。**片側 (Eq の wet) と傾きが違うと和が 1 にならず、
        // 両エンジンが同じ応答でも乗り移りの途中で音量が動く。
        // 昇りと降りで同じ式を使う (分けると片側だけの退行が起きうる)。
        const double d = (in_ ? 1.0 : -1.0) * fadeStep();
        mixFirOut(in, out, frames, accumulate, buf_bq_y_, fir_mix_, d);
        fir_mix_ += d * static_cast<double>(frames);
        if (in_ && fir_mix_ >= 1.0) {
            fir_mix_ = 1.0;
            // Eq は自身のフェードで wet 0 に着いている。**ここで状態をゼロにして
            // park する** — 次に起こすとき (落下・モード OFF) 古い状態から始めない。
            if (eq_.idle()) {
                eq_.reset();
                active_gen_ = adopting_gen_;  // この世代が鳴り始めた
                state_      = FirState::kFir;
            }
        } else if (!in_ && fir_mix_ <= 0.0) {
            // 対称フェード完了。音は完全に biquad — 落下ではないので数えない。
            invalidateFir(Drop::kQuiet);
        }
        break;
    }
    case FirState::kFir: {
        adoptCurveIfDirty();
        stepDesigner();
        if (designer_.done()) {
            designer_.abort();
            face_fading_ = true;
            face_w_      = 0.0;
        }
        const double dw = 1.0 / (kFaceFadeMs * fs_ / 1000.0);
        if (face_fading_) {
            kernel_.processBlock(in, buf_fir_y_, active_face_, 1 - active_face_,
                                 static_cast<float>(face_w_), static_cast<float>(dw));
            face_w_ += dw * static_cast<double>(frames);
            if (face_w_ >= 1.0) {
                active_face_ = 1 - active_face_;
                face_fading_ = false;
                face_fades_++;
                active_gen_  = adopting_gen_;  // 新しい面が鳴り切った
            }
        } else {
            kernel_.processBlock(in, buf_fir_y_, active_face_, -1, 0.0f, 0.0f);
        }
        mixFirOut(in, out, frames, accumulate, nullptr, 1.0, 0.0);
        if (!active_ && fir_wet_ == 0.0) {
            // 完全な素通しに到達 (Eq::idle 相当)。以後 process が来ない期間に履歴が
            // 腐るので、FDL はここで失効させる。次の有効化は準備からやり直し。
            // **Eq は wet 0 で止まっている**ので、受け渡しで音が動かない (ブロッカー 2)。
            invalidateFir(Drop::kQuiet);
        }
        break;
    }
    }
}

}  // namespace caeq
