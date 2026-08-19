
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "ca_test_support.h"
#include "../ca_eq_pick.h"
#include "../dsp/ca_eq_params.h"
#include "../dsp/ca_eq_stats.h"

using catest::Report;
using catest::Rng;
using caeq::Band;
using caeq::BandType;
using caeq::Eq;
using caeq::Interp;
using caeq::Params;
using caeq::Structure;

namespace {

constexpr double kFs = 48000.0;

struct Golden {
    BandType type;
    double fs;
    double fc;
    double q;
    double gain_db;
    double coef[5];
};

const Golden kGolden[] = {
    {BandType::kPeaking, 48000.0, 1000, 1, 6,
     {1.0439530869903351, -1.8953207239365961, 0.86772228475985658, -1.8953207239365961,
      0.91167537175019153}},
    {BandType::kPeaking, 48000.0, 20, 4, 6,
     {1.0002305234445641, -1.9995299061176359, 0.99930623497359916, -1.9995299061176359,
      0.99953675841816347}},
    {BandType::kPeaking, 48000.0, 12000, 0.69999999999999996, -9,
     {0.64819395186639894, -5.5687387333338343e-17, 0.26125009962844953,
      -5.5687387333338343e-17, -0.090555948505151584}},
    {BandType::kPeaking, 48000.0, 19000, 8, 12,
     {1.0557822453557513, 1.5570159954508365, 0.90679346508275982, 1.5570159954508365,
      0.96257571043851109}},
    {BandType::kLowShelf, 48000.0, 105, 0.70710678118654746, 6,
     {1.0033790517823855, -1.9835795594677885, 0.98046518586358311, -1.9836455718949166,
      0.98377822521884062}},
    {BandType::kHighShelf, 48000.0, 10000, 0.70710678118654746, -6,
     {0.67145424899515593, -0.072411623830529445, 0.11658430241312602, -0.50059844049230817,
      0.21622536807006063}},
    {BandType::kPeaking, 44100.0, 3150, 4.3200000000000003, -3,
     {0.98355072967822688, -1.7004478091596995, 0.90380395008843195, -1.7004478091596995,
      0.88735467976665883}},
    {BandType::kLowShelf, 96000.0, 60, 0.5, -12,
     {0.99724262736530955, -1.9889480828872015, 0.99171314178378611, -1.9889366262382746,
      0.98896722579802288}},
};

const char* typeName(BandType t) {
    switch (t) {
    case BandType::kPeaking: return "peak ";
    case BandType::kLowShelf: return "lowsh";
    case BandType::kHighShelf: return "hish ";
    }
    return "?";
}

void checkCoefficients(Report& r) {
    r.section("1. RBJ の係数 (参照は llmdocs/tools/eq の Python)");
    for (const Golden& g : kGolden) {
        const Band b{g.type, g.fc, g.q, g.gain_db};
        const caeq::Coef k = caeq::designTdf2(b, g.fs);
        double worst = 0.0;
        for (int i = 0; i < 5; i++) {
            const double d = std::fabs(k.c[i] - g.coef[i]);
            const double scale = std::fabs(g.coef[i]) > 1e-6 ? std::fabs(g.coef[i]) : 1.0;
            const double rel = d / scale;
            if (rel > worst) worst = rel;
        }
        r.check(worst < 1e-14, "%s fs=%-6.0f fc=%-7.1f Q=%-5.2f %+6.1f dB  最大相対差 %.2e",
                typeName(g.type), g.fs, g.fc, g.q, g.gain_db, worst);
    }

    for (const Golden& g : kGolden) {
        const Band b{g.type, g.fc, g.q, g.gain_db};
        const caeq::Coef k = caeq::designTdf2(b, g.fs);
        const double at_fc = catest::biquadDb(k, g.fc, g.fs);
        const double want = (g.type == BandType::kPeaking) ? g.gain_db : g.gain_db / 2.0;
        r.check(std::fabs(at_fc - want) < 1e-9, "%s fc=%-7.1f: |H(fc)| = %+.9f dB (設計 %+.1f)",
                typeName(g.type), g.fc, at_fc, want);
    }
}

Params makeParams(std::initializer_list<Band> bands, double preamp_db = 0.0) {
    Params p;
    p.preamp_db = preamp_db;
    p.band_count = 0;
    for (const Band& b : bands) {
        if (p.band_count >= caeq::kMaxBands) break;
        p.bands[p.band_count++] = b;
    }
    return p;
}

Params make31Band(double gain_scale) {
    static const double kIso[31] = {20,   25,   31.5, 40,   50,   63,   80,    100,  125,  160,
                                    200,  250,  315,  400,  500,  630,  800,   1000, 1250, 1600,
                                    2000, 2500, 3150, 4000, 5000, 6300, 8000,  10000, 12500,
                                    16000, 20000};
    Params p;
    p.band_count = 31;
    p.preamp_db = -3.0;
    for (int i = 0; i < 31; i++) {
        p.bands[i] = Band{BandType::kPeaking, kIso[i], 4.32,
                          gain_scale * ((i % 3 == 0) ? 1.0 : (i % 3 == 1 ? -1.0 : 0.5))};
    }
    return p;
}

std::vector<float> runMono(Eq& eq, const std::vector<float>& x, int block) {
    std::vector<float> y(x.size());
    size_t i = 0;
    while (i < x.size()) {
        int n = static_cast<int>(x.size() - i);
        if (n > block) n = block;
        eq.process(x.data() + i, y.data() + i, n);
        i += static_cast<size_t>(n);
    }
    return y;
}

Eq makeReady(Structure s, const Params& p, int channels = 1, double fs = kFs) {
    Eq eq;
    eq.configure(fs, channels, s);
    eq.snapParams(p);
    eq.setActive(true);
    eq.setFadeMillis(0.0);
    std::vector<float> zero(static_cast<size_t>(channels), 0.0f);
    eq.process(zero.data(), zero.data(), 1);
    eq.reset();
    return eq;
}

void checkResponse(Report& r) {
    r.section("2. インパルス応答の FFT と設計した振幅特性");

    const size_t kN = 1u << 18;
    catest::Fft fft(kN);

    struct Case {
        const char* name;
        Params p;
    };
    const Case cases[] = {
        {"peaking 1k Q1 +6",
         makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 6.0}})},
        {"peaking 20 Q4 +6",
         makeParams({Band{BandType::kPeaking, 20.0, 4.0, 6.0}})},
        {"lowshelf 105 +6 / highshelf 10k -6",
         makeParams({Band{BandType::kLowShelf, 105.0, 0.7071067811865476, 6.0},
                     Band{BandType::kHighShelf, 10000.0, 0.7071067811865476, -6.0}})},
        {"31 バンド (±6 dB, preamp -3)", make31Band(6.0)},
    };

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        for (const Case& c : cases) {
            Eq eq = makeReady(s, c.p);
            std::vector<float> imp = catest::makeImpulse(kN);
            std::vector<float> h = runMono(eq, imp, 960);

            std::vector<double> re(h.begin(), h.end()), im(kN, 0.0);
            fft.forward(re, im);

            double worst = 0.0;
            double worst_f = 0.0;
            for (double f = 20.0; f <= 20000.0; f *= 1.02) {
                const size_t bin = static_cast<size_t>(f / kFs * static_cast<double>(kN) + 0.5);
                const double fb = static_cast<double>(bin) * kFs / static_cast<double>(kN);
                const double got = catest::dbOf(std::sqrt(re[bin] * re[bin] + im[bin] * im[bin]));
                const double want = catest::cascadeDb(c.p, fb, kFs);
                const double d = std::fabs(got - want);
                if (d > worst) {
                    worst = d;
                    worst_f = fb;
                }
            }
            r.check(worst < 0.01, "%s %-38s 最大誤差 %.5f dB (%.0f Hz)", sname, c.name, worst,
                    worst_f);
        }
    }
}

void checkStructures(Report& r) {
    r.section("3. 転置形 II と SVF の出力差 (同じ伝達関数を別の状態変数で実現している)");

    struct Sig {
        const char* name;
        std::vector<float> x;
    };
    const size_t kN = 96000;
    std::vector<Sig> sigs;
    sigs.push_back({"対数スイープ 20 Hz-20 kHz", catest::makeLogSweep(kN, kFs, 20.0, 20000.0, 0.5)});
    sigs.push_back({"ホワイトノイズ", catest::makeNoise(kN, 0.3, 12345)});

    struct Case {
        const char* name;
        Params p;
    };
    const Case cases[] = {
        {"peaking 1k Q1 +6", makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 6.0}})},
        {"peaking 20 Q4 +6", makeParams({Band{BandType::kPeaking, 20.0, 4.0, 6.0}})},
        {"31 バンド ±6 dB", make31Band(6.0)},
    };

    for (const Case& c : cases) {
        for (const Sig& sig : sigs) {
            Eq a = makeReady(Structure::kTdf2, c.p);
            Eq b = makeReady(Structure::kSvf, c.p);
            const std::vector<float> ya = runMono(a, sig.x, 960);
            const std::vector<float> yb = runMono(b, sig.x, 960);

            double num = 0.0, den = 0.0;
            const size_t skip = kN / 4;
            for (size_t i = skip; i < kN; i++) {
                const double d = static_cast<double>(ya[i]) - static_cast<double>(yb[i]);
                num += d * d;
                den += static_cast<double>(ya[i]) * static_cast<double>(ya[i]);
            }
            const double rel = catest::dbOf(std::sqrt(num / den));
            r.check(rel < -130.0, "%-18s %-24s 相対差 RMS %.1f dB", c.name, sig.name, rel);
        }
    }

    {
        const Band band{BandType::kPeaking, 20.0, 4.0, 6.0};
        const caeq::Coef kt = caeq::designTdf2(band, kFs);
        const caeq::Coef ks = caeq::designSvf(band, kFs);
        const std::vector<float> xf = catest::makeNoise(kN, 0.3, 999);
        double s1 = 0, s2 = 0, ic1 = 0, ic2 = 0;
        double num = 0.0, den = 0.0;
        for (size_t i = 0; i < kN; i++) {
            const double x = xf[i];
            const double yt = kt.c[0] * x + s1;
            s1 = kt.c[1] * x - kt.c[3] * yt + s2;
            s2 = kt.c[2] * x - kt.c[4] * yt;
            const double v3 = x - ic2;
            const double v1 = ks.c[0] * ic1 + ks.c[1] * v3;
            const double v2 = ic2 + ks.c[1] * ic1 + ks.c[2] * v3;
            ic1 = 2.0 * v1 - ic1;
            ic2 = 2.0 * v2 - ic2;
            const double ys = ks.c[3] * x + ks.c[4] * v1 + ks.c[5] * v2;
            if (i > kN / 4) {
                num += (yt - ys) * (yt - ys);
                den += yt * yt;
            }
        }
        const double rel = catest::dbOf(std::sqrt(num / den));
        r.check(rel < -200.0, "double のまま (float に丸めない) 相対差 RMS %.1f dB "
                              "— Python の参照値は -232.5 dB", rel);
    }
}

void checkBlockLength(Report& r) {
    r.section("4. ブロック長を変えても出力が同一か (IIR なら依存しないはず)");

    const Params p = make31Band(6.0);
    const size_t kN = 49152;
    const std::vector<float> x = catest::makeLogSweep(kN, kFs, 20.0, 20000.0, 0.5);

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        Eq ref = makeReady(s, p);
        const std::vector<float> y_ref = runMono(ref, x, 512);
        for (int block : {960, 1024, 2048, 100, 7, 4096}) {
            Eq eq = makeReady(s, p);
            const std::vector<float> y = runMono(eq, x, block);
            size_t diff = 0;
            for (size_t i = 0; i < kN; i++) {
                if (std::memcmp(&y[i], &y_ref[i], sizeof(float)) != 0) diff++;
            }
            r.check(diff == 0, "%s block %-5d: 512 と違うサンプル %zu 個", sname, block, diff);
        }
    }

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        const Params p0 = makeParams({Band{BandType::kPeaking, 120.0, 1.0, -6.0}});
        const Params p1 = makeParams({Band{BandType::kPeaking, 400.0, 2.0, 12.0}});
        std::vector<float> y_ref;
        for (int block : {512, 960, 1024, 2048, 100}) {
            Eq eq = makeReady(s, p0);
            eq.setRampMillis(20.0);
            std::vector<float> y(kN);
            size_t i = 0;
            bool switched = false;
            while (i < kN) {
                int n = static_cast<int>(kN - i);
                if (n > block) n = block;
                if (!switched && i >= kN / 2) {
                    eq.setParams(p1);
                    switched = true;
                }
                eq.process(x.data() + i, y.data() + i, n);
                i += static_cast<size_t>(n);
            }
            if (block == 512) {
                y_ref = y;
                continue;
            }
            size_t diff = 0;
            for (size_t k = 0; k < kN; k++) {
                if (k >= kN / 2 - 4096 && k <= kN / 2 + 8192) continue;
                if (std::memcmp(&y[k], &y_ref[k], sizeof(float)) != 0) diff++;
            }
            r.check(diff == 0, "%s ランプ中 block %-5d: 512 と違うサンプル %zu 個", sname, block,
                    diff);
        }
    }
}

void checkChannels(Report& r) {
    r.section("5. チャンネル数 (実測で 2 と 12 が来る)");

    const Params p = make31Band(6.0);
    const size_t kN = 24000;

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        for (int ch : {2, 12}) {
            std::vector<std::vector<float>> mono;
            for (int c = 0; c < ch; c++) {
                mono.push_back(catest::makeNoise(kN, 0.2 + 0.05 * c, 1000 + c));
            }
            std::vector<float> inter(kN * static_cast<size_t>(ch));
            for (size_t i = 0; i < kN; i++) {
                for (int c = 0; c < ch; c++) inter[i * ch + c] = mono[c][i];
            }

            Eq eq = makeReady(s, p, ch);
            std::vector<float> out(inter.size());
            for (size_t i = 0; i < kN; i += 960) {
                const size_t n = (kN - i < 960) ? kN - i : 960;
                eq.process(inter.data() + i * ch, out.data() + i * ch, static_cast<int>(n));
            }

            double worst = 0.0;
            int worst_c = 0;
            for (int c = 0; c < ch; c++) {
                Eq one = makeReady(s, p, 1);
                const std::vector<float> ref = runMono(one, mono[c], 960);
                double num = 0.0, den = 0.0;
                for (size_t i = 0; i < kN; i++) {
                    const double d = static_cast<double>(out[i * ch + c]) -
                                     static_cast<double>(ref[i]);
                    num += d * d;
                    den += static_cast<double>(ref[i]) * static_cast<double>(ref[i]);
                }
                const double rel = std::sqrt(num / den);
                if (rel > worst) {
                    worst = rel;
                    worst_c = c;
                }
            }
            r.check(worst < 1e-12, "%s %2d ch: 1 ch の結果との最大相対差 %.2e (ch %d)", sname, ch,
                    worst, worst_c);
        }
    }
}

void checkNonFinite(Report& r) {
    r.section("6. NaN / Inf (IIR は 1 サンプルで恒久的に死ぬ)");

    const Params p = make31Band(6.0);
    const size_t kN = 9600;

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        std::vector<float> clean = catest::makeNoise(kN, 0.3, 4242);

        std::vector<float> dirty = clean;
        dirty[100] = std::nanf("");
        dirty[101] = std::numeric_limits<float>::infinity();
        dirty[102] = -std::numeric_limits<float>::infinity();

        Eq a = makeReady(s, p);
        Eq b = makeReady(s, p);
        const std::vector<float> y_clean = runMono(a, clean, 960);
        const std::vector<float> y_dirty = runMono(b, dirty, 960);

        r.check(catest::allFinite(y_dirty), "%s 出力に非有限値が残っていない", sname);
        r.check(b.scrubbedBlocks() > 0, "%s 入力の非有限値を検出した (%u ブロック)", sname,
                b.scrubbedBlocks());

        double worst = 0.0;
        for (size_t i = kN - 2400; i < kN; i++) {
            const double d = std::fabs(static_cast<double>(y_dirty[i]));
            if (d > worst) worst = d;
        }
        r.check(worst < 10.0, "%s 汚染後も出力が有界 (末尾 2400 サンプルのピーク %.4f)", sname,
                worst);
        r.check(a.scrubbedBlocks() == 0 && a.stateResets() == 0,
                "%s きれいな入力では検出器が誤発火しない", sname);
    }

#ifdef CA_EQ_DSP_TEST_HOOKS
    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        Eq eq = makeReady(s, p);
        std::vector<float> x = catest::makeNoise(4800, 0.3, 77);
        std::vector<float> y(x.size());
        eq.process(x.data(), y.data(), 960);
        eq.injectState(std::nan(""));
        eq.process(x.data() + 960, y.data() + 960, 960);
        const bool blk2_bad = !std::isfinite(y[960 + 500]);
        eq.process(x.data() + 1920, y.data() + 1920, 960);
        bool rest_ok = true;
        for (size_t i = 1920; i < 4800; i++) {
            if (!std::isfinite(y[i])) rest_ok = false;
        }
        r.check(eq.stateResets() == 1 && rest_ok,
                "%s 状態に NaN を撃ち込んでも次のブロックで復帰する (壊れたブロック=%d, "
                "リセット %u 回)", sname, blk2_bad ? 1 : 0, eq.stateResets());
    }
#endif
}

double clickDb(const std::vector<float>& y, size_t t) {
    static const std::vector<catest::Sos> hp = catest::butterworthHighpass(8, 6000.0, kFs);
    const std::vector<double> res = catest::sosFilt(hp, catest::toDouble(y));
    return catest::dbOf(catest::peakAbs(res, t - 2400, t + 4800));
}

struct ClickSetup {
    Structure structure = Structure::kTdf2;
    Interp interp = Interp::kCoef;
    int stride = caeq::kDefaultCoefStride;
    double ramp_ms = 10.0;
    int block = 960;
};

double measureClick(const ClickSetup& s, const Params& p0, const Params& p1,
                    bool zero_state_on_change = false) {
    const size_t kN = 48000;
    const size_t kT = kN / 2;
    const std::vector<float> x = catest::makeTones(kN, kFs);

    Eq eq = makeReady(s.structure, p0);
    eq.setInterp(s.interp);
    eq.setCoefStride(s.stride);
    eq.setRampMillis(s.ramp_ms);

    std::vector<float> y(kN);
    size_t i = 0;
    bool switched = false;
    while (i < kN) {
        int n = static_cast<int>(kN - i);
        if (n > s.block) n = s.block;
        if (!switched && i >= kT) {
            eq.setParams(p1);
            if (zero_state_on_change) eq.reset();
            switched = true;
        }
        eq.process(x.data() + i, y.data() + i, n);
        i += static_cast<size_t>(n);
    }
    return clickDb(y, kT);
}

void checkClick(Report& r) {
    r.section("7. パラメータ変更のクリック (信号 100+300 Hz の純音、-20 dBFS)");

    const Params p0 = makeParams({Band{BandType::kPeaking, 120.0, 1.0, -6.0}});
    const Params p1 = makeParams({Band{BandType::kPeaking, 120.0, 1.0, 12.0}});

    ClickSetup base;
    base.block = 24000;

    {
        ClickSetup s = base;
        s.ramp_ms = 10.0;
        const double floor_db = measureClick(s, p0, p0);
        r.note("床 (パラメータを変えない): %.1f dBFS  ← float 出力の量子化で決まる", floor_db);
    }

    r.note("ゲイン変更 -6 -> +12 dB (fc / Q は固定)");
    r.note("  ramp     TDF2/stride32   TDF2/stride1   SVF/stride32   SVF/stride1  (dBFS)");
    for (double ms : {0.0, 1.0, 5.0, 10.0, 20.0, 50.0}) {
        double v[4];
        int k = 0;
        for (Structure st : {Structure::kTdf2, Structure::kSvf}) {
            for (int stride : {32, 1}) {
                ClickSetup s = base;
                s.structure = st;
                s.stride = stride;
                s.ramp_ms = ms;
                v[k++] = measureClick(s, p0, p1);
            }
        }
        r.note("  %5.0f ms %13.1f %14.1f %14.1f %13.1f", ms, v[0], v[1], v[2], v[3]);
    }

    {
        ClickSetup s = base;
        s.ramp_ms = 0.0;
        const double instant = measureClick(s, p0, p1);
        r.check(std::fabs(instant - (-64.6)) < 1.5,
                "瞬時の入れ替え %.1f dBFS (Python の参照値 -64.6)", instant);

        s.ramp_ms = 10.0;
        s.stride = 1;
        const double per_sample = measureClick(s, p0, p1);
        r.check(std::fabs(per_sample - (-114.3)) < 2.0,
                "10 ms / 毎サンプル補間 %.1f dBFS (Python の参照値 -114.3)", per_sample);

        s.stride = 32;
        const double per_32 = measureClick(s, p0, p1);
        r.check(std::fabs(per_32 - (-84.7)) < 2.0,
                "10 ms / 32 サンプルの階段 %.1f dBFS (Python の参照値 -84.7)", per_32);
    }

    {
        ClickSetup s = base;
        s.ramp_ms = 0.0;
        const double keep = measureClick(s, p0, p1, false);
        const double zeroed = measureClick(s, p0, p1, true);
        r.check(std::fabs(zeroed - (-37.7)) < 2.0,
                "変更時に状態をゼロにすると %.1f dBFS / 保つと %.1f dBFS "
                "(Python の参照値 -37.7 / -64.6)", zeroed, keep);
    }
}

void checkFcSweep(Report& r) {
    r.section("8. fc を動かしたとき — 係数補間とパラメータ補間の差");

    const Params p0 = makeParams({Band{BandType::kPeaking, 120.0, 4.0, 12.0}});
    const Params p1 = makeParams({Band{BandType::kPeaking, 480.0, 4.0, 12.0}});

    ClickSetup base;
    base.block = 24000;

    r.note("  fc 120 -> 480 Hz (Q=4, +12 dB 固定)。刻みは既定 (%d フレーム)",
           caeq::kDefaultCoefStride);
    r.note("  ramp    TDF2/係数  TDF2/パラメータ   SVF/係数  SVF/パラメータ  (dBFS)");
    for (double ms : {1.0, 5.0, 10.0, 20.0, 50.0}) {
        double v[4];
        int k = 0;
        for (Structure st : {Structure::kTdf2, Structure::kSvf}) {
            for (Interp ip : {Interp::kCoef, Interp::kParam}) {
                ClickSetup s = base;
                s.structure = st;
                s.interp = ip;
                s.ramp_ms = ms;
                v[k++] = measureClick(s, p0, p1);
            }
        }
        r.note("  %5.0f ms %10.1f %14.1f %13.1f %13.1f", ms, v[0], v[1], v[2], v[3]);
    }

    const Params g0 = makeParams({Band{BandType::kPeaking, 120.0, 1.0, -6.0}});
    const Params g1 = makeParams({Band{BandType::kPeaking, 120.0, 1.0, 12.0}});
    ClickSetup s = base;
    s.ramp_ms = 10.0;
    s.interp = Interp::kCoef;
    const double gc = measureClick(s, g0, g1);
    s.interp = Interp::kParam;
    const double gp = measureClick(s, g0, g1);
    r.check(std::fabs(gc - gp) < 3.0,
            "ゲインだけなら補間方式の差は小さい: 係数 %.1f / パラメータ %.1f dBFS", gc, gp);

    for (Structure st : {Structure::kTdf2, Structure::kSvf}) {
        for (Interp ip : {Interp::kCoef, Interp::kParam}) {
            const size_t kN = 48000, kT = kN / 2;
            const std::vector<float> x = catest::makeTones(kN, kFs);
            Eq eq = makeReady(st, p0);
            eq.setInterp(ip);
            eq.setRampMillis(10.0);
            std::vector<float> y(kN);
            eq.process(x.data(), y.data(), static_cast<int>(kT));
            eq.setParams(p1);
            eq.process(x.data() + kT, y.data() + kT, static_cast<int>(kN - kT));

            const std::vector<double> yd = catest::toDouble(y);
            const double during = catest::peakAbs(yd, kT, kT + 1440);
            const double steady = catest::peakAbs(yd, kN - 9600, kN);
            r.note("  オーバーシュート %s/%s: ランプ中 %.4f / 定常 %.4f = %+.2f dB",
                   st == Structure::kTdf2 ? "TDF2" : "SVF ",
                   ip == Interp::kCoef ? "係数    " : "パラメータ", during, steady,
                   catest::dbOf(during / steady));
        }
    }
}

void checkFade(Report& r) {
    r.section("9. enable / disable のフェード");

    const Params p = makeParams({Band{BandType::kPeaking, 120.0, 1.0, 12.0}});
    const size_t kN = 48000, kT = kN / 2;
    const std::vector<float> x = catest::makeTones(kN, kFs);

    for (double ms : {0.0, 5.0, 20.0}) {
        Eq eq;
        eq.configure(kFs, 1, Structure::kTdf2);
        eq.snapParams(p);
        eq.setFadeMillis(0.0);
        eq.setActive(true);
        std::vector<float> y(kN);
        eq.process(x.data(), y.data(), static_cast<int>(kT));
        eq.setFadeMillis(ms);
        eq.setActive(false);
        eq.process(x.data() + kT, y.data() + kT, static_cast<int>(kN - kT));
        const double click = clickDb(y, kT);
        const double want = (ms == 0.0) ? -26.7 : (ms == 5.0 ? -77.8 : -89.9);
        r.check(std::fabs(click - want) < 3.0, "%4.0f ms のフェードで OFF: %.1f dBFS "
                                               "(Python の参照値 %.1f)", ms, click, want);
    }

    {
        Eq eq;
        eq.configure(kFs, 1, Structure::kTdf2);
        eq.snapParams(p);
        eq.setFadeMillis(10.0);
        eq.setActive(true);
        std::vector<float> buf(960, 0.0f);
        eq.process(buf.data(), buf.data(), 960);
        eq.setActive(false);
        const bool idle_before = eq.idle();
        eq.process(buf.data(), buf.data(), 960);
        r.check(!idle_before && eq.idle(), "DISABLE の直後は idle でなく、1 ブロック後に idle");
    }
}

double rawGrowthDbPerS(Structure s, const Band& ba, const Band& bb, double fmod, double secs) {
    const caeq::Coef ka = caeq::design(ba, kFs, s);
    const caeq::Coef kb = caeq::design(bb, kFs, s);
    double s1 = 1e-6, s2 = 1e-6;
    const size_t n = static_cast<size_t>(secs * kFs);
    const size_t win = static_cast<size_t>(0.25 * kFs);
    const double w = 2.0 * catest::kPi * fmod / kFs;
    double first = 0.0, last = 0.0, peak = 0.0;
    double t_first = 0.0, t_last = 0.0;
    for (size_t i = 0; i < n; i++) {
        const double u = 0.5 - 0.5 * std::cos(w * static_cast<double>(i));
        double c[6];
        for (int j = 0; j < 6; j++) c[j] = ka.c[j] + (kb.c[j] - ka.c[j]) * u;
        if (s == Structure::kSvf) {
            const double v3 = -s2;
            const double v1 = c[0] * s1 + c[1] * v3;
            const double v2 = s2 + c[1] * s1 + c[2] * v3;
            s1 = 2.0 * v1 - s1;
            s2 = 2.0 * v2 - s2;
        } else {
            const double y = s1;
            const double ns1 = -c[3] * y + s2;
            s2 = -c[4] * y;
            s1 = ns1;
        }
        const double m = std::fabs(s1) > std::fabs(s2) ? std::fabs(s1) : std::fabs(s2);
        if (m > peak) peak = m;
        if (!(m < 1e30)) return 1e30;
        if ((i + 1) % win == 0) {
            if (first == 0.0) {
                first = peak;
                t_first = static_cast<double>(i + 1) / kFs;
            }
            last = peak;
            t_last = static_cast<double>(i + 1) / kFs;
            peak = 0.0;
        }
    }
    if (first <= 0.0 || last <= 0.0 || t_last == t_first) return -1e30;
    return 20.0 * std::log10(last / first) / (t_last - t_first);
}

void checkStability(Report& r) {
    r.section("10. 時変安定性 (入力ゼロ、状態を 1e-6 から。成長率 dB/s)");

    const Band a{BandType::kPeaking, 100.0, 8.0, -12.0};
    const Band b{BandType::kPeaking, 100.0, 8.0, 12.0};
    const Band fa{BandType::kPeaking, 30.0, 8.0, 12.0};
    const Band fb{BandType::kPeaking, 120.0, 8.0, 12.0};

    r.note("  素の係数ループ (API を通さない。理論側の上界を見るための物差し)");
    r.note("  変調                  10 Hz    60 Hz   200 Hz   500 Hz  1000 Hz  2000 Hz");
    for (int which = 0; which < 2; which++) {
        for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
            double v[6];
            int k = 0;
            for (double f : {10.0, 60.0, 200.0, 500.0, 1000.0, 2000.0}) {
                v[k++] = rawGrowthDbPerS(s, which ? fa : a, which ? fb : b, f, 1.0);
            }
            char row[512];
            std::snprintf(row, sizeof(row), "  %-12s %s", which ? "fc 30-120" : "gain -12..+12",
                          s == Structure::kTdf2 ? "TDF2" : "SVF ");
            std::string line(row);
            for (int i = 0; i < 6; i++) {
                char cell[32];
                if (v[i] >= 1e29) {
                    std::snprintf(cell, sizeof(cell), "%9s", "BLOWUP");
                } else if (v[i] <= -1e29) {
                    std::snprintf(cell, sizeof(cell), "%9s", "----");
                } else {
                    std::snprintf(cell, sizeof(cell), "%+9.1f", v[i]);
                }
                line += cell;
            }
            r.note("%s", line.c_str());
        }
    }

    for (int block : {512, 960, 2048}) {
        const double max_hz = kFs / (2.0 * block);
        for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
            Eq eq;
            eq.configure(kFs, 1, s);
            eq.setFadeMillis(0.0);
            Params pa = makeParams({a});
            Params pb = makeParams({b});
            eq.snapParams(pa);
            eq.setActive(true);
            eq.setRampMillis(1000.0 * block / kFs);
            std::vector<float> zero(static_cast<size_t>(block), 0.0f);
            std::vector<float> out(static_cast<size_t>(block));
            eq.process(zero.data(), out.data(), block);
#ifdef CA_EQ_DSP_TEST_HOOKS
            eq.injectState(1e-6);
#endif
            const double start = eq.stateMagnitude();
            const int blocks = static_cast<int>(2.0 * kFs / block);
            for (int i = 0; i < blocks; i++) {
                eq.setParams((i % 2) ? pa : pb);
                eq.process(zero.data(), out.data(), block);
            }
            const double end = eq.stateMagnitude();
            const double growth = (start > 0.0 && end > 0.0)
                                      ? 20.0 * std::log10(end / start) / 2.0
                                      : -1e30;
            r.check(std::isfinite(end) && end < start * 1e3,
                    "%s block %-5d (変調 %.1f Hz): 状態 %.2e -> %.2e, %+.1f dB/s",
                    s == Structure::kTdf2 ? "TDF2" : "SVF ", block, max_hz, start, end,
                    growth <= -1e29 ? -999.0 : growth);
        }
    }

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const size_t kN = 480000;
        const std::vector<float> x = catest::makeNoise(kN, 0.3, 31337);
        Eq eq = makeReady(s, makeParams({a}));
        eq.setRampMillis(5.0);
        std::vector<float> y(kN);
        Params pa = makeParams({a});
        Params pb = makeParams({b});
        size_t i = 0;
        int toggle = 0;
        while (i < kN) {
            const size_t n = (kN - i < 960) ? kN - i : 960;
            eq.setParams((toggle++ % 2) ? pa : pb);
            eq.process(x.data() + i, y.data() + i, static_cast<int>(n));
            i += n;
        }
        const std::vector<double> yd = catest::toDouble(y);
        const double pk = catest::peakAbs(yd, 0, kN);
        r.check(std::isfinite(pk) && pk < 8.0,
                "%s ±12 dB を 5 ms ランプで 10 秒振り続けても発散しない (出力ピーク %.3f, "
                "状態リセット %u 回)", s == Structure::kTdf2 ? "TDF2" : "SVF ", pk,
                eq.stateResets());
    }
}

double nsPerFrame(Eq& eq, int ch, int block, int iters) {
    std::vector<float> buf(static_cast<size_t>(block) * static_cast<size_t>(ch));
    Rng rng(7);
    for (float& v : buf) v = static_cast<float>(0.2 * rng.uniform());
    std::vector<float> out(buf.size());
    eq.process(buf.data(), out.data(), block);
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) eq.process(buf.data(), out.data(), block);
    const auto t1 = std::chrono::steady_clock::now();
    const double ns = std::chrono::duration<double, std::nano>(t1 - t0).count();
    return ns / (static_cast<double>(iters) * block);
}

void checkPerf(Report& r) {
    r.section("11. 処理時間 (ホストの x86-64。実機の AArch64 とは別物、比だけを見る)");

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const char* sname = (s == Structure::kTdf2) ? "TDF2" : "SVF ";
        for (int ch : {2, 12}) {
            Eq eq = makeReady(s, make31Band(6.0), ch);
            r.note("  %s 31 バンド %2d ch: %.2f ns/frame (= %.1f%% @ 48 kHz 1 コア)", sname, ch,
                   nsPerFrame(eq, ch, 960, 200),
                   nsPerFrame(eq, ch, 960, 200) * 48000.0 / 1e7);
        }
    }

    const Params p0 = make31Band(4.0);
    const Params p1 = make31Band(-4.0);
    for (Interp ip : {Interp::kCoef, Interp::kParam}) {
        for (int stride : {32, 8, 1}) {
            Eq eq = makeReady(Structure::kTdf2, p0, 2);
            eq.setInterp(ip);
            eq.setCoefStride(stride);
            eq.setRampMillis(10.0);
            std::vector<float> buf(960 * 2, 0.01f);
            std::vector<float> out(buf.size());
            const auto t0 = std::chrono::steady_clock::now();
            const int iters = 400;
            for (int i = 0; i < iters; i++) {
                eq.setParams((i % 2) ? p1 : p0);
                eq.process(buf.data(), out.data(), 960);
            }
            const auto t1 = std::chrono::steady_clock::now();
            const double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() /
                              (static_cast<double>(iters) * 960);
            r.note("  ランプ中 TDF2 31 バンド 2 ch %s stride %-2d: %.2f ns/frame",
                   ip == Interp::kCoef ? "係数    " : "パラメータ", stride, ns);
        }
    }
}

void checkDenormal(Report& r) {
    r.section("12. 非正規化数 (ホストの x86-64 での実測。AArch64 の数字ではない)");

    const Band band{BandType::kPeaking, 1000.0, 1.0, 6.0};
    const caeq::Coef k = caeq::designTdf2(band, kFs);
    const int kIters = 2000000;

    auto run = [&](double xin) {
        double s1 = xin, s2 = xin;
        const auto t0 = std::chrono::steady_clock::now();
        double sink = 0.0;
        for (int i = 0; i < kIters; i++) {
            const double x = xin;
            const double y = k.c[0] * x + s1;
            s1 = k.c[1] * x - k.c[3] * y + s2;
            s2 = k.c[2] * x - k.c[4] * y;
            sink += y;
        }
        const auto t1 = std::chrono::steady_clock::now();
        const double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / kIters;
        if (!std::isfinite(sink)) std::printf("(非有限)\n");
        return ns;
    };

    const double normal = run(1e-3);
    const double denorm = run(1e-315);
    r.note("  通常の値 (1e-3):        %.3f ns/sample", normal);
    r.note("  非正規化数 (1e-315):    %.3f ns/sample  (比 %.1fx)", denorm, denorm / normal);
    r.note("  ※ 無音が続くと状態はここまで落ちる。入力が 0 のままなので自力では出られない");

    Eq eq = makeReady(Structure::kTdf2, makeParams({band}));
#ifdef CA_EQ_DSP_TEST_HOOKS
    eq.injectState(1e-250);
    std::vector<float> zero(960, 0.0f);
    std::vector<float> out(960);
    eq.process(zero.data(), out.data(), 960);
    r.check(eq.denormalFlushes() == 1 && eq.stateMagnitude() == 0.0,
            "無音 + 極小の状態でゼロクリアが働く (%u 回)", eq.denormalFlushes());

    Eq eq2 = makeReady(Structure::kTdf2, makeParams({band}));
    eq2.injectState(1e-3);
    eq2.process(zero.data(), out.data(), 960);
    r.check(eq2.denormalFlushes() == 0, "通常の減衰中はゼロクリアしない");
#endif
}

void checkParamGuards(Report& r) {
    r.section("13. パラメータの検査と取り込み");

    Eq eq;
    eq.configure(kFs, 2, Structure::kTdf2);
    eq.setActive(true);
    eq.setFadeMillis(0.0);
    const Params good = makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 6.0}});
    r.check(eq.snapParams(good), "正常なパラメータは通る");

    struct Bad {
        const char* name;
        Params p;
    };
    Params nan_fc = good;
    nan_fc.bands[0].fc = std::nan("");
    Params inf_gain = good;
    inf_gain.bands[0].gain_db = std::numeric_limits<double>::infinity();
    Params huge_gain = good;
    huge_gain.bands[0].gain_db = 60.0;
    Params zero_q = good;
    zero_q.bands[0].q = 0.0;
    Params neg_fc = good;
    neg_fc.bands[0].fc = -100.0;
    Params too_many = good;
    too_many.band_count = caeq::kMaxBands + 1;
    Params bad_preamp = good;
    bad_preamp.preamp_db = std::nan("");

    const Bad bads[] = {{"fc が NaN", nan_fc},   {"ゲインが Inf", inf_gain},
                        {"ゲインが範囲外", huge_gain}, {"Q が 0", zero_q},
                        {"fc が負", neg_fc},     {"バンド数が上限超え", too_many},
                        {"プリアンプが NaN", bad_preamp}};
    for (const Bad& b : bads) {
        const uint32_t before = eq.rejectedCount();
        const bool ok = eq.setParams(b.p);
        r.check(!ok && eq.rejectedCount() == before + 1, "%s を却下する", b.name);
    }
    r.check(eq.activeBands() == 1, "却下しても前の設定が残っている (%d バンド)", eq.activeBands());

    {
        Eq e2;
        e2.configure(kFs, 1, Structure::kTdf2);
        e2.setActive(true);
        e2.setFadeMillis(0.0);
        e2.setRampMillis(0.0);
        e2.snapParams(makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 0.0}}));
        e2.setParams(makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 3.0}}));
        e2.setParams(makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 6.0}}));
        e2.setParams(makeParams({Band{BandType::kPeaking, 2000.0, 1.0, 9.0},
                                 Band{BandType::kPeaking, 4000.0, 1.0, -9.0}}));
        std::vector<float> buf(64, 0.0f);
        e2.process(buf.data(), buf.data(), 64);
        r.check(e2.activeBands() == 2,
                "process() ごとに 1 回だけ取り込む (最後の 1 つが載る: %d バンド)",
                e2.activeBands());
    }

    for (Structure s : {Structure::kTdf2, Structure::kSvf}) {
        const size_t kN = 96000;
        const std::vector<float> x = catest::makeTones(kN, kFs);
        Eq e3 = makeReady(s, make31Band(6.0));
        e3.setRampMillis(10.0);
        std::vector<float> y(kN);
        size_t i = 0;
        int step = 0;
        while (i < kN) {
            const size_t n = (kN - i < 960) ? kN - i : 960;
            if (i % 4800 == 0) {
                Params p = make31Band(6.0);
                p.band_count = 1 + (step++ % 31);
                e3.setParams(p);
            }
            e3.process(x.data() + i, y.data() + i, static_cast<int>(n));
            i += n;
        }
        const double pk = catest::peakAbs(catest::toDouble(y), 0, kN);
        r.check(std::isfinite(pk) && pk < 4.0,
                "%s バンド数を 1..31 で動かしても発散しない (ピーク %.4f)",
                s == Structure::kTdf2 ? "TDF2" : "SVF ", pk);
    }
}

void fillGeneration(ca_eq_slot_t* s, uint32_t gen) {
    s->generation = gen;
    s->flags = CA_EQ_FLAG_ENABLED;
    s->band_count = CA_EQ_MAX_BANDS;
    s->preamp_db = -static_cast<float>(gen % 13);
    s->writer_pid = gen;
    for (uint32_t i = 0; i < CA_EQ_MAX_BANDS; i++) {
        s->band[i].fc_hz = 100.0f + static_cast<float>(gen % 97);
        s->band[i].q = 1.0f + static_cast<float>(gen % 7);
        s->band[i].gain_db = static_cast<float>(gen % 11) - 5.0f;
        s->band[i].type = CA_EQ_BAND_PEAKING;
    }
    s->curve_gen = gen;
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        s->curve_db[i] = static_cast<float>((gen + static_cast<uint32_t>(i)) % 23) - 11.0f;
    }
}

bool matchesGeneration(const ca_eq_slot_t& s) {
    ca_eq_slot_t want{};
    fillGeneration(&want, s.generation);
    if (s.flags != want.flags || s.band_count != want.band_count ||
        s.preamp_db != want.preamp_db || s.writer_pid != want.writer_pid ||
        s.curve_gen != want.curve_gen) {
        return false;
    }
    for (uint32_t i = 0; i < CA_EQ_MAX_BANDS; i++) {
        if (s.band[i].fc_hz != want.band[i].fc_hz || s.band[i].q != want.band[i].q ||
            s.band[i].gain_db != want.band[i].gain_db || s.band[i].type != want.band[i].type) {
            return false;
        }
    }
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        if (s.curve_db[i] != want.curve_db[i]) return false;
    }
    return true;
}

void fillStatSlot(ca_slot_t* s, uint32_t gen) {
    s->frames = static_cast<uint64_t>(gen) * 1000ull;
    s->sample_rate = 48000u + gen;
    s->channels = 2u;
    s->block_frames = gen;
    s->state = CA_STATE_ENABLED | CA_STATE_CONFIGURED;
    s->gain_mb = -static_cast<int32_t>(gen % 100u);
    s->io_id = static_cast<int32_t>(gen);
    s->pid = static_cast<uint64_t>(gen) + 7ull;
    s->ctx = static_cast<uint64_t>(gen) * 3ull;
    s->last_ns = static_cast<uint64_t>(gen) * 1000000ull;
    s->in_peak = static_cast<float>(gen % 17u) / 16.0f;
    s->out_peak = static_cast<float>(gen % 19u) / 18.0f;
    s->in_peak_i32 = gen;
    s->out_peak_i32 = gen + 1u;
    s->param_slot = gen % CA_SHM_SLOTS;
    s->param_gen = gen;
    s->param_rejected = gen / 2u;
    s->session_id = CA_AUDIO_SESSION_DEVICE;
    s->fir_state = gen % 5u;
    s->fir_flags = gen % 32u;
    s->fir_fill = gen;
    s->fir_partitions = gen + 2u;
    s->fir_rebuilds = gen + 3u;
    s->fir_face_fades = gen + 4u;
    s->fir_fallbacks = gen + 5u;
    s->fir_mode_offs = gen + 6u;
    s->fir_design_failures = gen + 7u;
    s->fir_unfit_size = gen + 8u;
    s->fir_unfit_budget = gen + 9u;
    s->fir_curve_rejected = gen + 10u;
    s->fir_curve_gen = gen + 11u;
    s->fir_taps = gen + 12u;
    s->fir_m = gen + 13u;
    s->fir_arena_kb = gen + 14u;
    s->fir_max_slice_ns = gen + 15u;
    s->fir_scrubbed = static_cast<uint64_t>(gen) + 16ull;
}

bool matchesStatSlot(const ca_slot_t& s) {
    ca_slot_t want{};
    fillStatSlot(&want, static_cast<uint32_t>(s.frames / 1000ull));
    want.seq = s.seq;
    want.in_use = s.in_use;
    return std::memcmp(&s, &want, sizeof(ca_slot_t)) == 0;
}

void checkSeqlock(Report& r) {
    r.section("15. 共有メモリの seqlock (書き手を別スレッドで走らせる)");

    r.check(sizeof(ca_shm_t) == CA_SHM_BYTES,
            "ca_shm_t = %zu バイト。post-fs-data.sh が作る大きさと一致 (%d)", sizeof(ca_shm_t),
            CA_SHM_BYTES);
    r.check(sizeof(ca_eq_slot_t) == CA_EQ_PARAM_SLOT_BYTES && sizeof(ca_eq_band_t) == 16,
            "並びが固定 (ca_eq_slot_t %zu / ca_eq_band_t %zu)", sizeof(ca_eq_slot_t),
            sizeof(ca_eq_band_t));

    {
        auto* slot = new ca_eq_slot_t{};
        std::atomic<bool> stop{false};
        std::atomic<uint64_t> writes{0};

        std::thread writer([&] {
            uint32_t gen = 1;
            while (!stop.load(std::memory_order_relaxed)) {
                caeq::paramsBeginWrite(slot);
                fillGeneration(slot, gen);
                caeq::paramsEndWrite(slot);
                gen++;
                writes.fetch_add(1, std::memory_order_relaxed);
            }
        });

        uint64_t ok = 0, gave_up = 0, torn = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(600);
        while (std::chrono::steady_clock::now() < deadline) {
            ca_eq_slot_t snap;
            if (caeq::paramsRead(slot, &snap)) {
                ok++;
                if (snap.generation != 0 && !matchesGeneration(snap)) torn++;
            } else {
                gave_up++;
            }
        }
        stop.store(true);
        writer.join();

        r.check(torn == 0, "千切れた並びを採用した回数 %llu / 成功 %llu / 諦め %llu "
                           "(書き込み %llu 回)",
                static_cast<unsigned long long>(torn), static_cast<unsigned long long>(ok),
                static_cast<unsigned long long>(gave_up),
                static_cast<unsigned long long>(writes.load()));
        r.check(ok > 0, "書き込みと同時でも読めている");
        delete slot;
    }

    {
        ca_eq_slot_t slot{};
        fillGeneration(&slot, 7);
        caeq::paramsBeginWrite(&slot);
        ca_eq_slot_t snap;
        const auto t0 = std::chrono::steady_clock::now();
        const bool got = caeq::paramsRead(&slot, &snap);
        const double us = std::chrono::duration<double, std::micro>(
                              std::chrono::steady_clock::now() - t0).count();
        r.check(!got && us < 1000.0,
                "書き手が途中で死んでも %.1f us で諦める (スピンしない)", us);
    }

    {
        Eq eq;
        eq.configure(kFs, 2, Structure::kTdf2);
        ca_eq_slot_t slot{};
        fillGeneration(&slot, 3);
        Params p;
        r.check(caeq::paramsConvert(slot, &p) && eq.setParams(p), "正常な並びは通る");

        slot.band_count = CA_EQ_MAX_BANDS + 1;
        r.check(!caeq::paramsConvert(slot, &p), "band_count が上限超えなら変換しない");

        fillGeneration(&slot, 3);
        slot.band[5].type = 99;
        r.check(!caeq::paramsConvert(slot, &p), "未知の type を弾く");

        fillGeneration(&slot, 3);
        slot.band[9].fc_hz = 30000.0f;
        const uint32_t before = eq.rejectedCount();
        r.check(caeq::paramsConvert(slot, &p) && !eq.setParams(p) &&
                    eq.rejectedCount() == before + 1,
                "48 kHz で fc 30 kHz の並びを丸ごと却下する");
    }

    {
        auto* slot = new ca_slot_t{};
        slot->in_use = CA_SHM_MAGIC;
        std::atomic<bool> stop{false};
        std::atomic<uint64_t> writes{0};

        std::thread writer([&] {
            uint32_t gen = 1;
            while (!stop.load(std::memory_order_relaxed)) {
                caeq::statsBeginWrite(slot);
                fillStatSlot(slot, gen);
                caeq::statsEndWrite(slot);
                gen++;
                writes.fetch_add(1, std::memory_order_relaxed);
            }
        });

        uint64_t ok = 0, gave_up = 0, torn = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(600);
        while (std::chrono::steady_clock::now() < deadline) {
            ca_slot_t snap;
            if (caeq::statsRead(slot, &snap)) {
                ok++;
                if (snap.frames != 0 && !matchesStatSlot(snap)) torn++;
            } else {
                gave_up++;
            }
        }
        stop.store(true);
        writer.join();

        r.check(torn == 0, "統計: 千切れた枠を採用した回数 %llu / 成功 %llu / 諦め %llu "
                           "(書き込み %llu 回)",
                static_cast<unsigned long long>(torn), static_cast<unsigned long long>(ok),
                static_cast<unsigned long long>(gave_up),
                static_cast<unsigned long long>(writes.load()));
        r.check(ok > 0, "統計: 書き込みと同時でも読めている");
        delete slot;
    }

    {
        ca_slot_t slot{};
        slot.in_use = CA_SHM_MAGIC;
        fillStatSlot(&slot, 5);
        caeq::statsBeginWrite(&slot);
        ca_slot_t snap{};
        snap.frames = 0xDEADBEEFull;
        const bool got = caeq::statsRead(&slot, &snap, 8);
        r.check(!got && snap.frames == 5000ull,
                "統計: 書き手が途中で死んでも諦めて返り、その枠の中身は呼び手に届く");
    }
}

void checkRateChange(Report& r) {
    r.section("16. サンプルレートが変わったとき");

    Eq eq;
    eq.configure(48000.0, 1, Structure::kTdf2);
    eq.setActive(true);
    eq.setFadeMillis(0.0);
    r.check(eq.snapParams(makeParams({Band{BandType::kPeaking, 20000.0, 2.0, 6.0},
                                      Band{BandType::kPeaking, 1000.0, 1.0, 3.0}})),
            "48 kHz では 20 kHz のバンドが通る");
    r.check(eq.activeBands() == 2, "2 バンドが載っている");

    const uint32_t before = eq.rejectedCount();
    eq.configure(32000.0, 1, Structure::kTdf2);
    r.check(eq.activeBands() == 0 && eq.rejectedCount() == before + 1,
            "32 kHz に変わったら丸ごと捨てて平坦に戻す (勝手に fc を動かさない)");

    r.check(eq.snapParams(makeParams({Band{BandType::kPeaking, 12000.0, 2.0, 6.0}})) &&
                eq.activeBands() == 1,
            "32 kHz で収まる設定は通る");

    std::vector<float> x = catest::makeNoise(4800, 0.3, 8);
    std::vector<float> y = runMono(eq, x, 960);
    r.check(catest::allFinite(y), "レート変更後の出力が有限");
}

void checkAccumulate(Report& r) {
    r.section("14. ACCUMULATE と素通し");

    const Params p = makeParams({Band{BandType::kPeaking, 1000.0, 1.0, 6.0}});
    const std::vector<float> x = catest::makeNoise(960, 0.3, 5);

    Eq a = makeReady(Structure::kTdf2, p);
    std::vector<float> w(960, 0.0f);
    a.process(x.data(), w.data(), 960, false);

    Eq b = makeReady(Structure::kTdf2, p);
    std::vector<float> acc(960);
    for (size_t i = 0; i < 960; i++) acc[i] = 0.25f;
    b.process(x.data(), acc.data(), 960, true);

    double worst = 0.0;
    for (size_t i = 0; i < 960; i++) {
        const double d = std::fabs(static_cast<double>(acc[i]) -
                                   (0.25 + static_cast<double>(w[i])));
        if (d > worst) worst = d;
    }
    r.check(worst < 1e-6, "ACCUMULATE は上書きせずに足す (最大差 %.2e)", worst);

    Eq c;
    c.configure(kFs, 1, Structure::kTdf2);
    c.snapParams(p);
    std::vector<float> out(960, 0.0f);
    c.process(x.data(), out.data(), 960, false);
    bool same = true;
    for (size_t i = 0; i < 960; i++) {
        if (std::memcmp(&out[i], &x[i], sizeof(float)) != 0) same = false;
    }
    r.check(same, "無効なあいだは入力をそのまま返す");
}

struct FakePids {
    uint64_t alive[8];
    int count;
};

bool fakePidAlive(uint64_t pid, void* user) {
    const FakePids* p = static_cast<const FakePids*>(user);
    for (int i = 0; i < p->count; i++) {
        if (p->alive[i] == pid) return true;
    }
    return false;
}

void putSlot(ca_shm_t* m, uint32_t i, uint64_t pid, int32_t session) {
    ca_slot_t& s = m->slots[i];
    s.in_use = CA_SHM_MAGIC;
    s.pid = pid;
    s.session_id = session;
    s.param_slot = i;
    s.sample_rate = 48000;
}

ca_shm_t* newShm() {
    auto* m = new ca_shm_t{};
    m->magic = CA_SHM_MAGIC;
    m->version = CA_SHM_VERSION;
    m->slot_count = CA_SHM_SLOTS;
    m->slot_size = static_cast<uint32_t>(sizeof(ca_slot_t));
    m->param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t));
    m->curve_points = static_cast<uint32_t>(caeq::kCurvePoints);
    return m;
}

void checkSlotPick(Report& r) {
    r.section("17. 枠の選択 (書き手が「どの枠に書くか」を決める規則)");

    r.check(offsetof(ca_slot_t, session_id) == 124 && sizeof(ca_slot_t) == CA_SLOT_BYTES,
            "session_id は版 3 の 128 B の末尾のまま (offset %zu / 大きさ %zu)",
            offsetof(ca_slot_t, session_id), sizeof(ca_slot_t));
    r.check(CA_AUDIO_SESSION_DEVICE == -2, "AUDIO_SESSION_DEVICE は -2");

    {
        auto* zero = new ca_shm_t{};
        r.check(caeq::shmState(zero) == caeq::ShmState::kNotInitialised,
                "ゼロ埋めの共有メモリは「未初期化」— **版ずれではない**");
        delete zero;
    }

    {
        ca_shm_t* m = newShm();
        r.check(caeq::shmState(m) == caeq::ShmState::kOk, "magic と版が揃っていれば通る");

        m->version = CA_SHM_VERSION - 1u;
        r.check(caeq::shmState(m) == caeq::ShmState::kVersionMismatch,
                "magic が正しくて版が古いのが**本物の版ずれ** (版 %u の .so)", m->version);

        m->magic = 0u;
        r.check(caeq::shmState(m) == caeq::ShmState::kNotInitialised,
                "magic が 0 なら、版が何であれ「未初期化」");

        m->magic = 0xDEADBEEFu;
        r.check(caeq::shmState(m) == caeq::ShmState::kForeign,
                "magic が別の値なら「我々のファイルではない」(未接続と混ぜない)");
        delete m;
    }

    r.check(caeq::shmState(nullptr) == caeq::ShmState::kForeign, "nullptr を渡しても落ちない");

    FakePids pids{{100, 200, 300, 0, 0, 0, 0, 0}, 3};

    {
        ca_shm_t* m = newShm();
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone && p.live_count == 0,
                "枠が 1 つも使われていなければ「無い」");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 3, 100, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kOk && p.param_slot == 3 && p.stats_slot == 3,
                "生きた DEVICE の枠が 1 つなら、それを選ぶ (枠 %u)", p.param_slot);
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 100, 0);
        putSlot(m, 1, 100, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kOk && p.param_slot == 1 && p.other_count == 1,
                "DEVICE でない枠が同時に居ても、DEVICE の枠だけを選ぶ (枠 %u / 他 %u)",
                p.param_slot, p.other_count);
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 100, 0);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone && p.other_count == 1,
                "DEVICE でない枠しか無ければ「無い」— スピーカーには書かない");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 999, CA_AUDIO_SESSION_DEVICE);
        putSlot(m, 2, 200, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kOk && p.param_slot == 2 && p.stale_count == 1,
                "pid が死んでいる残骸は数えない (選んだ枠 %u / 残骸 %u)",
                p.param_slot, p.stale_count);
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 999, CA_AUDIO_SESSION_DEVICE);
        putSlot(m, 1, 998, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone && p.stale_count == 2,
                "残骸しか無ければ「無い」(in_use が立っていても)");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 100, CA_AUDIO_SESSION_DEVICE);
        putSlot(m, 1, 200, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kAmbiguous && p.live_count == 2,
                "生きた DEVICE の枠が 2 つなら選ばない (推測すると別のイヤホンに掛かる)");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 0, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone && p.stale_count == 0,
                "pid が 0 の枠 (attach の途中) は数えない");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 100, CA_AUDIO_SESSION_DEVICE);
        m->slots[0].param_slot = CA_PARAM_SLOT_NONE;
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone,
                "param_slot が未割り当ての枠は選ばない (書いても適用されない)");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 1, 100, CA_AUDIO_SESSION_DEVICE);
        m->slots[1].param_slot = 5;
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kOk && p.param_slot == 5 && p.stats_slot == 1,
                "書き込み先は param_slot (統計の添字とは別。fs は統計の枠 %u から引く)",
                p.stats_slot);
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        m->slot_count = 2;
        putSlot(m, 4, 100, CA_AUDIO_SESSION_DEVICE);
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone, "slot_count の先は読まない");
        delete m;
    }

    {
        ca_shm_t* m = newShm();
        putSlot(m, 0, 100, CA_AUDIO_SESSION_DEVICE);
        m->slots[0].in_use = 0;
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(m, fakePidAlive, &pids);
        r.check(p.status == caeq::SlotPick::kNone, "in_use が立っていない枠は見ない");
        delete m;
    }
}

void printDeviceChecklist(Report& r) {
    r.section("18. 実機で確かめること (ホストでは検証できない項目の一覧)");

    Eq def;
    const double ramp_ms = def.rampMillis();
    const double fade_ms = def.fadeMillis();

    r.note("A. 入れる前 — 共有メモリの版が上がっている (1152 -> %d バイト)", CA_SHM_BYTES);
    r.note("   ls -l /data/vendor/audio/ca_eq_stats.bin");
    r.note("   期待: %d バイト。post-fs-data.sh が作り直す", CA_SHM_BYTES);
    r.note("   1152 のままなら: モジュールだけ古い。**.so は logcat に stats file too small を");
    r.note("     出して統計を諦める (音は素通しで正常)。SIGBUS で無音にならないことがこの経路の要点**");
    r.note("   caeqstat も同じ検査をするので too small で止まる");
    r.note("   **大きさが同じでも版が %u でなければ駄目** (caeqset --show の version=)。", CA_SHM_VERSION);
    r.note("     版 2 の .so は session_id を書かないので、**繋がっているのに");
    r.note("     「イヤホン側の枠が無い」という嘘の理由が出る。**caeqset は版が違えば専用の値で落ちる");
    r.note("");

    r.note("B. 生成と経路 — 枠は取れたが、まだ誰にも宛てられていない状態");
    r.note("   caeqstat の param: 行");
    r.note("   期待: 「枠=N 適用済み gen=0 / 共有メモリ gen=0」+「書き手がまだ一度も書いていない」");
    r.note("   **この状態で processedFrames が進み、in_peak == out_peak なら、**");
    r.note("     **「宛てられるまで素通し」が実機で成立している** (gen==0 では何も適用しない)");
    r.note("   進まないなら: エフェクトが音声経路に入っていない。param 経路とは別の問題");
    r.note("");

    r.note("C. 前と変わる 3 点 — 再実証のときに混乱しないための予測");
    r.note("   1) SET_PARAM (id=%d) のゲインが %.0f ms のランプになった", CA_PARAM_ID_GAIN, ramp_ms);
    r.note("      期待: in/out 比は %.0f ms 後に落ち着く。caeqstat を 2 回読めば同じ値", ramp_ms);
    r.note("      **10 ms は 1 ブロック (20 ms) より短いので、観測できないのが正しい**");
    r.note("      比が中途半端なまま止まるなら: ランプが終わっていない = process() が回っていない");
    r.note("   2) DISABLE のあと 1 ブロックだけ余分に回る (%.0f ms のフェードを終わらせるため)",
           fade_ms);
    r.note("      期待: processedFrames が block_frames ぶんちょうど 1 回増えて止まる");
    r.note("      **増えずに止まるなら: -ENODATA を idle() より先に返している = フェードが効かない**");
    r.note("      増え続けるなら: idle() に到達していない");
    r.note("   3) ENABLE で状態をゼロにする");
    r.note("      **直接は観測できない。**クリックが減ることでしか分からないので、耳で確かめる");
    r.note("   4) SET_PARAM のゲインの下限が -6000 -> -4000 mB");
    r.note("      期待: -6000 を送っても -4000 mB として記録される (caeqstat の gain_mB)");
    r.note("");

    r.note("D. 新しく分かること — ログと caeqstat に出るようになった値");
    r.note("   1) SET_CONFIG のログに access=N が出る (0=WRITE 1=READ 2=ACCUMULATE)");
    r.note("      **2 が来るなら出力を上書きしてはいけない経路。**実装済みだが実測がまだ");
    r.note("   2) SET_CONFIG のログから frames= を外した (常に 0 だったので判断に使えない)");
    r.note("   3) チャンネル数の上限は %d。実測の 12 ch は passthrough_only にならないはず",
           caeq::kMaxChannels);
    r.note("      12 ch で PASSTHROUGH_ONLY が立つなら: format が float でない側の理由");
    r.note("   4) fc の上限は %.2f x fs — 48 kHz で %.0f Hz、96 kHz (LDAC) で %.0f Hz",
           caeq::kValidFcRatio, caeq::kValidFcRatio * 48000.0, caeq::kValidFcRatio * 96000.0);
    r.note("      **サンプルレートが下がって前の設定が収まらなくなったら丸ごと捨てて平坦に戻る。**");
    r.note("      caeqstat の param 行で却下が増える");
    r.note("");

    r.note("E. バンドを実機で鳴らす — 書き手は libcaeqset.so (アプリが su 経由で呼ぶのと同じもの)");
    r.note("   **何もしなければ gen=0 のまま素通し。**これは正常な状態であって不具合ではない");
    r.note("   caeqset は .so と同じ seqlock の定義 (dsp/ca_eq_params.h) を使い、");
    r.note("     **書く前に演算層と同じ検査を通す** — 通ったものは必ず .so にも通る");
    r.note("   手順:");
    r.note("     caeqset --show                        # どの枠が生きていて、どれがイヤホン側か");
    r.note("     caeqset --auto-slot --dry-run         # 選ぶところまで。**書かずに終了コードだけ**");
    r.note("     caeqset --auto-slot --preamp -3 --band 100:1:6 --band 4000:2:-4 --band 10000:0.7:3:hs");
    r.note("     caeqstat                              # 適用済み gen が上がっていれば通っている");
    r.note("     caeqset --auto-slot --off             # enabled を落とす (フェードして素通しへ)");
    r.note("   期待: **適用済み gen == 共有メモリ gen、却下=0、out_peak が変わる**");
    r.note("   **枠を選ぶのは caeqset の中。**アプリはテキストを解析しない (--slot は手で調べるとき用)");
    r.note("   終了コード: 0 成功 / 10 使い方 / 11 共有メモリ無し / 12 版ずれ /");
    r.note("     13 生きた枠が無い (未接続。**異常ではない**) / 14 生きた枠が複数 / 15 検査に落ちた");
    r.note("   適用済み gen が上がらないなら: その枠を読んでいるエフェクトがいない (--show で確認)");
    r.note("   却下が増えるなら: fs が想定と違う。caeqset は統計側の rate を見て検査するので、");
    r.note("     **SET_CONFIG より先に書くと 48000 Hz として検査してしまう** (注意が出る)");
    r.note("   検査範囲: fc %.0f Hz 以上 / Q %.1f〜%.0f / gain ±%.0f dB / preamp %.0f〜+%.0f dB",
           caeq::kMinFcHz, caeq::kMinQ, caeq::kMaxQ, caeq::kMaxGainDb, caeq::kMinPreampDb,
           caeq::kMaxPreampDb);
    r.note("   SET_PARAM id=%d で枠を上書きできる (保持者がいるときの経路)", CA_PARAM_ID_SLOT);
    r.note("");

    r.note("F. アプリから caeqset を呼ぶ経路 — **ホストでは 1 つも確かめられない**");
    r.note("   1) **APK の中の実行ファイルが展開されているか。**これが前提で、外すと全部死ぬ");
    r.note("      adb shell run-as <pkg> ls -l lib/arm64/libcaeqset.so   (0755 で見えること)");
    r.note("      **見えないなら app/build.gradle.kts の jniLibs.useLegacyPackaging が false。**");
    r.note("      その場合 .so は APK の中に置かれたままで、dlopen はできても exec はできない");
    r.note("   2) **root マネージャの許可が「毎回聞く」になっていないか。**先に見ること。");
    r.note("      そこが「毎回聞く」なら、背面から押す形は通る通らない以前に成立しない");
    r.note("   3) **アプリ自身の uid から呼べるか。**`adb shell su` は答えにならない");
    r.note("      (許可は uid ごと)。**アプリのコードから呼ばれること**が条件");
    r.note("   4) 印 (CA_EQ_SET_BEGIN) が stdout に出ているか");
    r.note("      出ていないのに終了コードが非 0 = **su 自身の値。表と突き合わせてはいけない**");
    r.note("");

    r.note("G. ホストで既に押さえてあるので実機で測り直さないもの");
    r.note("   RBJ の係数 / 振幅特性 / ブロック長とチャンネル数への非依存 / NaN からの復帰 /");
    r.note("   クリックの大きさ / 時変安定性 / seqlock の千切れ — すべてこのハーネスで検証済み");
    r.note("   **実機で見るのは「経路」と「フレームワークの挙動」だけ。**演算の正しさは見ない");
}

}

void runFirSections(catest::Report& r);
void runFirAlignmentSection(catest::Report& r);
void runFirGateSections(catest::Report& r);
void runShmSections(catest::Report& r);
void runSlotLifecycleSection(catest::Report& r);
void runPreampBoundarySection(catest::Report& r);

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    std::printf("Codec Anchor EQ — オフラインハーネス\n");
    std::printf("参照実装: llmdocs/tools/eq/*.py\n");

    Report r;
    checkCoefficients(r);
    checkResponse(r);
    checkStructures(r);
    checkBlockLength(r);
    checkChannels(r);
    checkNonFinite(r);
    checkClick(r);
    checkFcSweep(r);
    checkFade(r);
    checkStability(r);
    checkPerf(r);
    checkDenormal(r);
    checkParamGuards(r);
    checkAccumulate(r);
    checkSeqlock(r);
    checkRateChange(r);
    checkSlotPick(r);
    printDeviceChecklist(r);
    runFirSections(r);
    runFirGateSections(r);
    runFirAlignmentSection(r);
    runShmSections(r);
    runSlotLifecycleSection(r);
    runPreampBoundarySection(r);

    std::printf("\n%d / %d 件が通った。\n", r.total() - r.failures(), r.total());
    return r.failures() == 0 ? 0 : 1;
}
