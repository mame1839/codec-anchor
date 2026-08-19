// 規約は eq-shm-abi.md §4。ここを ca_eq.cpp に直接書かないこと (ホストで検証できなくなる)。
#ifndef CA_EQ_POLL_H_
#define CA_EQ_POLL_H_

#include "ca_eq_curve.h"
#include "ca_eq_params.h"
#include "ca_eq_pipeline.h"

namespace caeq {

struct PollState {
    // 捨てた版も覚える — 覚えないと、同じ壊れた並びを毎ブロック検査し直す。
    uint32_t param_gen = 0;
    uint32_t rejected  = 0;
    bool     user_enabled  = false;  // 共有メモリ側の on/off。framework の ENABLE とは別
    bool     fir_requested = false;

    // 2 KB 超あるので audio スレッドのスタックに置かない。
    ca_eq_slot_t snap{};
};

// process() の先頭で 1 回だけ呼ぶ。ブロックの途中で読み直さない。
// ⚠️ 確保・ロック・ログ・例外・システムコールを一切しない。
inline void pollSlot(const ca_eq_slot_t* src, PollState* st, EqPipeline* dsp,
                     bool framework_enabled) {
    if (src == nullptr || st == nullptr || dsp == nullptr) return;

    const uint32_t gen = paramsGeneration(src);
    if (gen == 0u || gen == st->param_gen) return;

    ca_eq_slot_t* snap = &st->snap;
    if (!paramsRead(src, snap)) return;
    if (snap->generation == 0u || snap->generation == st->param_gen) return;

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
    if (snap->curve_gen != 0u) dsp->setCurve(snap->curve_db, snap->curve_gen);
}

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
