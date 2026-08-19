#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "ca_test_support.h"
#include "ca_eq_fir_golden.h"
#include "../dsp/ca_eq_curve.h"
#include "../dsp/ca_eq_pipeline.h"

using catest::Report;

namespace {

std::vector<float> knobCurve(const double* knob_db) {
    std::vector<float> c(caeq::kCurvePoints);
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        int s = 0;
        while (s < 29 && cagold::kKnobGridIdx[s + 1] <= i) s++;
        const double span = cagold::kKnobGridIdx[s + 1] - cagold::kKnobGridIdx[s];
        const double t    = (i - cagold::kKnobGridIdx[s]) / span;
        c[static_cast<size_t>(i)] =
            static_cast<float>(knob_db[s] + (knob_db[s + 1] - knob_db[s]) * t);
    }
    return c;
}

caeq::Params dunuBiquad() {
    caeq::Params p;
    p.band_count = 31;
    p.preamp_db  = 0.0;
    for (int i = 0; i < 31; i++) {
        p.bands[i] = caeq::Band{caeq::BandType::kPeaking,
                                caeq::curvePointHz(cagold::kKnobGridIdx[i]), 4.32,
                                cagold::kDunuKnobDb[i]};
    }
    return p;
}

void runToFir(caeq::EqPipeline& pl, int p, int ch, int max_blocks = 200) {
    std::vector<float> in(static_cast<size_t>(p * ch)), out(static_cast<size_t>(p * ch));
    catest::Rng rng(900);
    for (int b = 0; b < max_blocks; b++) {
        for (size_t i = 0; i < in.size(); i++) in[i] = static_cast<float>(0.1 * rng.uniform());
        pl.process(in.data(), out.data(), p, false);
        if (pl.firState() == caeq::EqPipeline::FirState::kFir) return;
    }
}

}

void runFirGateSections(Report& r);

void runFirGateSections(Report& r) {
    r.section("28. 検分が足した見張り (遷移の受け渡しと診断カウンタ)");

    const std::vector<float> dunu = knobCurve(cagold::kDunuKnobDb);

    {
        auto tailAfterDisable = [&](bool fir, int nbands) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            caeq::Params bq = dunuBiquad();
            bq.band_count   = nbands;
            pl.snapParams(bq);
            pl.setActive(true);
            if (fir) {
                pl.setFirEnabled(true);
                pl.setCurve(dunu.data(), 1);
                runToFir(pl, 960, 1);
            } else {
                std::vector<float> w(960, 0.0f);
                for (int i = 0; i < 5; i++) pl.process(w.data(), w.data(), 960);
            }
            const std::vector<float> tone = catest::makeTones(960 * 6, 48000.0);
            std::vector<float> blk(960);
            pl.setActive(false);
            double worst_after = 0.0;
            for (int b = 0; b < 6; b++) {
                std::memcpy(blk.data(), tone.data() + static_cast<size_t>(b) * 960,
                            sizeof(float) * 960);
                std::vector<float> dry(blk.begin(), blk.end());
                pl.process(blk.data(), blk.data(), 960);
                if (b == 0) continue;
                for (int i = 0; i < 960; i++) {
                    worst_after = std::fmax(
                        worst_after, std::fabs(static_cast<double>(blk[static_cast<size_t>(i)]) -
                                               static_cast<double>(dry[static_cast<size_t>(i)])));
                }
            }
            return worst_after;
        };
        const double with_fir  = tailAfterDisable(true, 31);
        const double no_fir    = tailAfterDisable(false, 31);
        const double unity_bq  = tailAfterDisable(true, 0);
        r.note("DISABLE 完了後の max|out - dry|: FIR あり %.5f / FIR なし %.5f / "
               "biquad ユニティ %.5f",
               with_fir, no_fir, unity_bq);
        r.check(no_fir < 1e-6,
                "(対照) FIR を通さない DISABLE は 1 ブロックで素通しに着く (%.1e)", no_fir);
        r.check(unity_bq < 1e-6,
                "(対照) biquad がユニティなら FIR 経由でも見えない (%.1e) "
                "— 24 節が見逃した理由",
                unity_bq);
        r.check(with_fir < 1e-6,
                "DISABLE が着いた後に biquad が EQ を鳴らし直さない (%.5f)", with_fir);
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Params flat;
        flat.band_count = 0;
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);

        std::vector<float> in(960 * 2, 0.01f), out(960 * 2, 0.0f);
        for (int i = 0; i < 3; i++) pl.process(in.data(), out.data(), 960, false);
        pl.setFirEnabled(false);
        pl.process(in.data(), out.data(), 960, false);
        r.check(pl.fallbacks() == 0 && pl.modeOffs() == 0,
                "準備中の取り止めはどちらにも数えない (fallbacks=%u modeOffs=%u)",
                pl.fallbacks(), pl.modeOffs());

        pl.setFirEnabled(true);
        runToFir(pl, 960, 2);
        const uint32_t fb_before = pl.fallbacks();
        const uint32_t mo_before = pl.modeOffs();
        pl.setFirEnabled(false);
        pl.process(in.data(), out.data(), 960, false);
        r.check(pl.modeOffs() == mo_before + 1 && pl.fallbacks() == fb_before,
                "鳴っている FIR からのモード OFF は modeOffs だけ進む "
                "(modeOffs %u->%u / fallbacks %u->%u)",
                mo_before, pl.modeOffs(), fb_before, pl.fallbacks());

        pl.setFirEnabled(true);
        runToFir(pl, 960, 2);
        const uint32_t fb2 = pl.fallbacks();
        const uint32_t mo2 = pl.modeOffs();
        std::vector<float> in1024(1024 * 2, 0.01f), out1024(1024 * 2, 0.0f);
        pl.process(in1024.data(), out1024.data(), 1024, false);
        r.check(pl.fallbacks() == fb2 + 1 && pl.modeOffs() == mo2,
                "P 変化による落下は fallbacks だけ進む (fallbacks %u->%u / modeOffs %u->%u)",
                fb2, pl.fallbacks(), mo2, pl.modeOffs());
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Params bq = dunuBiquad();
        pl.snapParams(bq);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        runToFir(pl, 960, 2);
        const bool reached = pl.firState() == caeq::EqPipeline::FirState::kFir;

        std::vector<float> in(static_cast<size_t>(caeq::kMaxConvBlock) * 2, 0.01f);
        std::vector<float> out(in.size(), 0.0f);
        pl.process(in.data(), out.data(), caeq::kMaxConvBlock, false);
        bool finite = true;
        for (float v : out) {
            if (!std::isfinite(v)) finite = false;
        }
        r.check(reached && finite && pl.fallbacks() == 1,
                "落下と同時に上限ちょうど (P=%d) のブロックが来ても健全 "
                "(fallbacks=%u 出力有限=%d)",
                caeq::kMaxConvBlock, pl.fallbacks(), finite ? 1 : 0);
    }
}
