#include <cmath>
#include <vector>

#include "ca_test_support.h"
#include "../dsp/ca_eq_dsp.h"

using catest::Report;
using caeq::Band;
using caeq::BandType;
using caeq::Eq;
using caeq::Params;
using caeq::Structure;

namespace {

constexpr double kFs = 48000.0;

Params paramsWithPreamp(double preamp_db) {
    Params p;
    p.band_count = 1;
    p.bands[0] = Band{BandType::kPeaking, 1000.0, 1.41, 6.0};
    p.preamp_db = preamp_db;
    return p;
}

}  // namespace

void runPreampBoundarySection(Report& r) {
    r.section("33. validate のプリアンプ境界 (アプリ側の PREAMP_RANGE と同じ数であること)");

    struct Probe {
        double preamp;
        bool pass;
        const char* note;
    };
    const Probe probes[] = {
        {-47.9, false, "移行がクランプしなければこの値になる形 (1 kHz +12 x4 の autoPreampDb10 = -479)"},
        {-40.1, false, "アプリの刻み 0.1 dB で最初に範囲外になる値"},
        {std::nextafter(-40.0, -1e9), false, "double で表せる -40.0 の直下"},
        {-40.0, true, "下側の境界ちょうど (境界は閉区間)"},
        {-39.9, true, "下側の境界の内側"},
        {12.0, true, "上側の境界ちょうど (境界は閉区間)"},
        {std::nextafter(12.0, 1e9), false, "double で表せる +12.0 の直上"},
        {12.1, false, "上側の境界の外"},
    };
    for (const Probe& p : probes) {
        const bool ok = caeq::validate(paramsWithPreamp(p.preamp), kFs);
        r.check(ok == p.pass, "preamp %+.17g dB は%s — %s", p.preamp,
                p.pass ? "通る" : "却下", p.note);
    }

    r.check(caeq::validate(paramsWithPreamp(static_cast<double>(static_cast<float>(-40.0))), kFs),
            "float の枠を往復しても -40.0 は境界のまま通る");
    r.check(!caeq::validate(paramsWithPreamp(static_cast<double>(static_cast<float>(-40.1))), kFs),
            "float の枠を往復しても -40.1 は却下のまま");

    {
        const Params good = paramsWithPreamp(-3.0);
        Eq a;
        Eq b;
        for (Eq* e : {&a, &b}) {
            e->configure(kFs, 1, Structure::kTdf2);
            e->setActive(true);
            e->setFadeMillis(0.0);
            e->snapParams(good);
        }
        const uint32_t before = b.rejectedCount();
        const bool accepted = b.setParams(paramsWithPreamp(-47.9));
        r.check(!accepted && b.rejectedCount() == before + 1,
                "-47.9 dB は setParams の経路でも却下される (rejectedCount %u -> %u)",
                before, b.rejectedCount());

        const std::vector<float> x = catest::makeTones(4800, kFs);
        std::vector<float> ya(x.size());
        std::vector<float> yb(x.size());
        size_t i = 0;
        while (i < x.size()) {
            const size_t n = (x.size() - i < 480) ? x.size() - i : 480;
            a.process(x.data() + i, ya.data() + i, static_cast<int>(n));
            b.process(x.data() + i, yb.data() + i, static_cast<int>(n));
            i += n;
        }
        bool same = true;
        for (size_t k = 0; k < x.size(); k++) {
            if (ya[k] != yb[k]) {
                same = false;
                break;
            }
        }
        r.check(same, "却下の後も前の設定のまま鳴る (4800 サンプルの出力がビット同一)");
    }
}
