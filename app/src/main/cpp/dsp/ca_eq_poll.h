// 共有メモリの枠を 1 枚読んで演算層へ渡す判断。**Android に依存しない** —
// `.so` の process() の先頭から呼ばれるのと同じコードを、ホストのハーネスがそのまま回す。
//
// **ここを ca_eq.cpp に直接書かないこと。**あちらは mmap と android/log.h に依存するので
// ホストではビルドできず、共有メモリの読み手でいちばん間違えやすい部分
// (枠の添字・世代の突き合わせ・丸ごと捨てる規約) が無検査で残ることになる。
#ifndef CA_EQ_POLL_H_
#define CA_EQ_POLL_H_

#include "ca_eq_curve.h"
#include "ca_eq_params.h"
#include "ca_eq_pipeline.h"

namespace caeq {

// poll がブロックをまたいで持ち越すもの。**エフェクトのインスタンスが 1 つ持つ。**
struct PollState {
    // 最後に取り込んだ、あるいは丸ごと捨てた枠の版。**捨てた版も覚える** —
    // 覚えないと、同じ壊れた並びを毎ブロック検査し直して rejected だけが増え続ける。
    uint32_t param_gen = 0;
    uint32_t rejected  = 0;      // 検査に落ちて丸ごと捨てた回数
    bool     user_enabled  = false;  // 共有メモリ側の on/off。framework の ENABLE とは別
    bool     fir_requested = false;  // 高精度モードが指示されている

    // 枠の写し取り先。**2 KB 超あるので audio スレッドのスタックに置かない。**
    // インスタンスと同じ寿命の場所に置くための入れ物で、poll の外では意味を持たない。
    ca_eq_slot_t snap{};
};

// 「曲線を見たか」を PollState に持たないこと。持つと EqPipeline の pending_gen_ と
// 二重の真になり、arena を作り直して曲線が失われたとき (fs 変化) に
// **どちらも「もう渡した」と思ったまま二度と届かない。**判断は pipeline 側の 1 箇所。

// process() の先頭で 1 回だけ呼ぶ。**ブロックの途中で読み直さない** —
// 取り込みを process() 1 回につき 1 回に縛ることが、係数の変調速度に構造的な上限を
// 与えている (dsp/ca_eq_dsp.h の setParams を参照)。
//
// **確保・ロック・ログ・例外・システムコールを一切しない。**
//
// 検査に 1 つでも落ちたら**枠の更新を丸ごと捨てて前の設定を保つ。**bands も曲線も
// 同じ 1 枚のスナップショットから来ていて、送り手にとっては 1 つの設定なので、
// 片方だけ通すと「半分だけ効いた状態」になって原因の切り分けができなくなる。
inline void pollSlot(const ca_eq_slot_t* src, PollState* st, EqPipeline* dsp,
                     bool framework_enabled) {
    if (src == nullptr || st == nullptr || dsp == nullptr) return;

    // 世代が動いていなければ枠を写しもしない。**定常はここで終わる** (32 bit の読み 1 回)。
    // 途中まで書かれた値を掴んでも、下の seqlock が弾くので目安で足りる。
    const uint32_t gen = paramsGeneration(src);
    if (gen == 0u || gen == st->param_gen) return;

    ca_eq_slot_t* snap = &st->snap;
    if (!paramsRead(src, snap)) return;   // 掴めなければ次のブロックで
    if (snap->generation == 0u || snap->generation == st->param_gen) return;

    // 曲線の検査をここで通す理由は上の「丸ごと捨てる」。EqPipeline 側にも同じ検査があるが、
    // あちらは設計器を再始動する瞬間の最後の砦で、この poll を通らない呼び手のためのもの。
    // **検査の定義そのものは caeq::curveValid ただ 1 つ**なので、範囲が 2 箇所に散らない。
    const bool curve_ok = snap->curve_gen == 0u || curveValid(snap->curve_db);

    Params p;
    if (!paramsConvert(*snap, &p) || !curve_ok || !dsp->setParams(p)) {
        st->param_gen = snap->generation;
        st->rejected++;
        return;
    }

    st->param_gen     = snap->generation;
    st->user_enabled  = (snap->flags & CA_EQ_FLAG_ENABLED) != 0u;
    st->fir_requested = (snap->flags & CA_EQ_FLAG_HIGH_PRECISION) != 0u;
    dsp->setActive(framework_enabled && st->user_enabled);
    dsp->setFirEnabled(st->fir_requested);
    // **世代の突き合わせは pipeline に任せる** (同じ世代なら中で即座に戻る)。
    // curve_gen == 0 は「この枠に曲線が載っていない」= 版 4 を知らない書き手や、
    // まだ曲線を送っていない状態。渡してしまうと世代 0 が「載っている」ことになる。
    if (snap->curve_gen != 0u) dsp->setCurve(snap->curve_db, snap->curve_gen);
}

// 統計の枠へ FIR の診断を写す。**写し取りを 1 箇所にする** — `.so` と読み手が別々に
// 組み立てると、片方だけフィールドを足したときに気づけない。
// 呼び手は seqlock (ca_seq_begin / ca_seq_end) の内側で呼ぶこと。
inline void firStatsOf(const PollState& st, const EqPipeline& dsp, ca_slot_t* out) {
    out->fir_state       = static_cast<uint32_t>(dsp.firState());
    out->fir_flags       = (st.fir_requested ? CA_FIR_F_REQUESTED : 0u) |
                           (dsp.firAvailable() ? CA_FIR_F_ARENA : 0u) |
                           (dsp.blockOk() ? CA_FIR_F_BLOCK_OK : 0u) |
                           (dsp.firCapable() ? CA_FIR_F_ADDRESSABLE : 0u) |
                           (dsp.curveFailed() ? CA_FIR_F_CURVE_FAILED : 0u);
    out->fir_fill        = static_cast<uint32_t>(dsp.fdlFill());
    out->fir_partitions  = static_cast<uint32_t>(dsp.fdlPartitions());
    out->fir_rebuilds    = dsp.rebuilds();
    out->fir_face_fades  = dsp.faceFades();
    out->fir_fallbacks   = dsp.fallbacks();
    out->fir_mode_offs   = dsp.modeOffs();
    out->fir_design_failures = dsp.designFailures();
    out->fir_unfit_size      = dsp.unfitSizeCount();
    out->fir_unfit_budget    = dsp.unfitBudgetCount();
    out->fir_curve_rejected  = dsp.curveRejected();
    out->fir_curve_gen   = dsp.curveGeneration();
    out->fir_taps        = static_cast<uint32_t>(dsp.designTaps());
    out->fir_m           = static_cast<uint32_t>(dsp.designM());
    out->fir_arena_kb    = static_cast<uint32_t>(dsp.arenaBytes() / 1024u);
    out->fir_max_slice_ns = dsp.maxSliceNs();
    out->fir_scrubbed    = dsp.scrubbedSamples();
}

}  // namespace caeq

#endif  // CA_EQ_POLL_H_
