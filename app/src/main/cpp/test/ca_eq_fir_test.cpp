#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <thread>
#include <vector>

#include "ca_test_support.h"
#include "ca_eq_fir_golden.h"
#include "../dsp/ca_eq_conv.h"
#include "../dsp/ca_eq_curve.h"
#include "../dsp/ca_eq_fft.h"
#include "../dsp/ca_eq_fir.h"
#include "../dsp/ca_eq_pipeline.h"
#include "../dsp/pffft/pffft.h"

using catest::Report;

namespace {

constexpr double kPi = 3.14159265358979323846;

struct AlignedBuf {
    std::vector<float> raw;
    float* p;
    explicit AlignedBuf(size_t n) : raw(n + 16, 0.0f) {
        p = reinterpret_cast<float*>(
            (reinterpret_cast<uintptr_t>(raw.data()) + 63u) & ~static_cast<uintptr_t>(63u));
    }
};

std::vector<float> lcgFloats(uint64_t seed, int n) {
    std::vector<float> out(static_cast<size_t>(n));
    uint64_t s = seed;
    for (int i = 0; i < n; i++) {
        s = s * 6364136223846793005ull + 1442695040888963407ull;
        const double v =
            static_cast<double>((s >> 40) & 0xFFFFFFull) / 8388608.0 - 1.0;
        out[static_cast<size_t>(i)] = static_cast<float>(v);
    }
    return out;
}

std::vector<float> curveFromKnobs(const double* knob_db) {
    std::vector<float> c(caeq::kCurvePoints);
    int seg = 0;
    for (int j = 0; j < caeq::kCurvePoints; j++) {
        while (seg + 2 < 31 && cagold::kKnobGridIdx[seg + 1] < j) seg++;
        const int    x0 = cagold::kKnobGridIdx[seg];
        const int    x1 = cagold::kKnobGridIdx[seg + 1];
        const double t  = static_cast<double>(j - x0) / static_cast<double>(x1 - x0);
        const double tt = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        c[static_cast<size_t>(j)] =
            static_cast<float>(knob_db[seg] + (knob_db[seg + 1] - knob_db[seg]) * tt);
    }
    return c;
}

double nowNs() {
    return std::chrono::duration<double, std::nano>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

template <typename F>
double benchNs(F&& fn, int iters, int repeats = 3) {
    double best = 1e30;
    for (int r = 0; r < repeats; r++) {
        const double t0 = nowNs();
        for (int i = 0; i < iters; i++) fn();
        const double per = (nowNs() - t0) / iters;
        if (per < best) best = per;
    }
    return best;
}

void checkFftWrapper(Report& r) {
    r.section("19. FFT ラッパ (vendored PFFFT、golden は numpy)");

    r.check(pffft_simd_size() == 4, "SIMD が有効 (pffft_simd_size = %d)", pffft_simd_size());

    {
        const int valid[] = {32, 96, 160, 480, 960, 1024, 1920, 2048, 4096, 32768, 65536};
        const int invalid[] = {0, -32, 31, 48, 62, 224, 896, 1000, (1 << 26) + 32};
        bool ok = true;
        for (int n : valid) ok = ok && caeq::fftSizeValid(n);
        r.check(ok, "合法サイズが通る (32/96/.../1920/65536)");
        ok = true;
        for (int n : invalid) ok = ok && !caeq::fftSizeValid(n);
        r.check(ok, "不正サイズが弾かれる (0/-32/31/48/62/224/896/1000/2^26+32)");
        r.check(!caeq::fftSizeValid(48) && caeq::fftSizeValid(96),
                "SIMD 幅の制約も述語が持つ (48 は不正、96 は合法)");
        ok = caeq::convBlockSizeValid(512) && caeq::convBlockSizeValid(960) &&
             caeq::convBlockSizeValid(1024) && caeq::convBlockSizeValid(2048) &&
             caeq::convBlockSizeValid(16);
        r.check(ok, "ブロック長 P の合法判定 (2P で見る): 16/512/960/1024/2048");
        ok = !caeq::convBlockSizeValid(448) && !caeq::convBlockSizeValid(500) &&
             !caeq::convBlockSizeValid(31) && !caeq::convBlockSizeValid(0);
        r.check(ok, "不正な P が弾かれる (448 = 2^6·7 / 500 / 31 / 0)");
    }

    for (int ci = 0; ci < cagold::kFftCaseCount; ci++) {
        const cagold::FftCase& c = cagold::kFftCases[ci];
        caeq::FftPlan plan;
        if (!plan.init(c.n)) {
            r.check(false, "plan.init(%d)", c.n);
            continue;
        }
        std::vector<float> x = lcgFloats(c.seed, c.n);
        AlignedBuf in(static_cast<size_t>(c.n)), out(static_cast<size_t>(c.n)),
            work(static_cast<size_t>(c.n));
        std::memcpy(in.p, x.data(), sizeof(float) * static_cast<size_t>(c.n));
        plan.forwardOrdered(in.p, out.p, work.p);
        double worst = 0.0;
        for (int b = 0; b < c.nbins; b++) {
            const cagold::FftBin& g = c.bins[b];
            double re, im;
            if (g.k == 0) {
                re = out.p[0];
                im = 0.0;
            } else if (g.k == c.n / 2) {
                re = out.p[1];
                im = 0.0;
            } else {
                re = out.p[2 * g.k];
                im = out.p[2 * g.k + 1];
            }
            const double d = std::hypot(re - g.re, im - g.im) / c.scale;
            if (d > worst) worst = d;
        }
        r.check(worst < 2e-7, "n=%-6d 順序付き前進 vs numpy: 最大相対差 %.2e", c.n, worst);
    }

    {
        const int n = 1920;
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> x = lcgFloats(21, n);
        AlignedBuf a(n), b(n), w(n);
        std::memcpy(a.p, x.data(), sizeof(float) * n);
        plan.forwardOrdered(a.p, b.p, w.p);
        plan.inverseOrdered(b.p, b.p, w.p);
        double worst = 0.0;
        for (int i = 0; i < n; i++) {
            const double d = std::fabs(b.p[i] / n - x[static_cast<size_t>(i)]);
            if (d > worst) worst = d;
        }
        r.check(worst < 1e-6, "往復 (n=1920): inverse(forward(x))/n = x、最大差 %.2e", worst);
    }

    {
        const int n = 1920;
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> x = lcgFloats(22, n);
        AlignedBuf a(n), b(n), w(n);
        std::memcpy(a.p, x.data(), sizeof(float) * n);
        std::memcpy(b.p, x.data(), sizeof(float) * n);
        plan.forwardOrdered(a.p, a.p, w.p);
        AlignedBuf c(n);
        plan.forwardOrdered(b.p, c.p, w.p);
        r.check(std::memcmp(a.p, c.p, sizeof(float) * n) == 0,
                "順序付き: in-place と out-of-place がビット同一");
        std::memcpy(a.p, x.data(), sizeof(float) * n);
        plan.forward(a.p, a.p, w.p);
        plan.forward(b.p, c.p, w.p);
        r.check(std::memcmp(a.p, c.p, sizeof(float) * n) == 0,
                "順序なし: in-place と out-of-place がビット同一");
    }

    {
        int checked = 0, mismatch = 0;
        std::vector<char> mem;
        for (int n = 32; n <= 65536; n += 32) {
            if (!caeq::fftSizeValid(n)) continue;
            if (n > 8192 && n != 16384 && n != 32768 && n != 65536) continue;
            caeq::FftPlan pa, pb;
            if (!pa.init(n)) {
                mismatch++;
                continue;
            }
            mem.assign(caeq::fftSetupBytes(n) + 64, 0);
            void* aligned = reinterpret_cast<void*>(
                (reinterpret_cast<uintptr_t>(mem.data()) + 63u) &
                ~static_cast<uintptr_t>(63u));
            if (!pb.initInPlace(n, aligned, caeq::fftSetupBytes(n))) {
                mismatch++;
                continue;
            }
            std::vector<float> x = lcgFloats(static_cast<uint64_t>(n), n);
            AlignedBuf ia(static_cast<size_t>(n)), oa(static_cast<size_t>(n)),
                ob(static_cast<size_t>(n)), w(static_cast<size_t>(n));
            std::memcpy(ia.p, x.data(), sizeof(float) * static_cast<size_t>(n));
            pa.forwardOrdered(ia.p, oa.p, w.p);
            pb.forwardOrdered(ia.p, ob.p, w.p);
            if (std::memcmp(oa.p, ob.p, sizeof(float) * static_cast<size_t>(n)) != 0) {
                mismatch++;
            }
            checked++;
        }
        r.check(mismatch == 0 && checked > 40,
                "malloc 版と in-place 版の setup が同一の出力 (%d サイズ、不一致 %d)", checked,
                mismatch);
    }

    {
        const int n = 96;
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> xa = lcgFloats(31, n), xb = lcgFloats(32, n);
        AlignedBuf fa(n), fb(n), acc(n), w(n), oa(n), ob(n), prod(n);
        std::memcpy(fa.p, xa.data(), sizeof(float) * n);
        std::memcpy(fb.p, xb.data(), sizeof(float) * n);

        plan.forward(fa.p, fa.p, w.p);
        plan.forward(fb.p, fb.p, w.p);
        std::memset(acc.p, 0, sizeof(float) * n);
        plan.convolveAccumulate(fa.p, fb.p, acc.p, 1.0f / static_cast<float>(n));
        plan.inverse(acc.p, acc.p, w.p);

        std::memcpy(oa.p, xa.data(), sizeof(float) * n);
        std::memcpy(ob.p, xb.data(), sizeof(float) * n);
        plan.forwardOrdered(oa.p, oa.p, w.p);
        plan.forwardOrdered(ob.p, ob.p, w.p);
        prod.p[0] = oa.p[0] * ob.p[0] / static_cast<float>(n);
        prod.p[1] = oa.p[1] * ob.p[1] / static_cast<float>(n);
        for (int k = 1; k < n / 2; k++) {
            const float ar = oa.p[2 * k], ai = oa.p[2 * k + 1];
            const float br = ob.p[2 * k], bi = ob.p[2 * k + 1];
            prod.p[2 * k]     = (ar * br - ai * bi) / static_cast<float>(n);
            prod.p[2 * k + 1] = (ar * bi + ai * br) / static_cast<float>(n);
        }
        plan.inverseOrdered(prod.p, prod.p, w.p);

        std::vector<double> ref(n, 0.0);
        for (int t = 0; t < n; t++) {
            double s = 0.0;
            for (int k = 0; k < n; k++) {
                s += static_cast<double>(xa[static_cast<size_t>(k)]) *
                     static_cast<double>(xb[static_cast<size_t>((t - k + n) % n)]);
            }
            ref[static_cast<size_t>(t)] = s;
        }
        double worst_ac = 0.0, worst_ab = 0.0;
        for (int i = 0; i < n; i++) {
            worst_ac = std::fmax(worst_ac, std::fabs(acc.p[i] - ref[static_cast<size_t>(i)]));
            worst_ab = std::fmax(worst_ab, std::fabs(acc.p[i] - prod.p[i]));
        }
        r.check(worst_ac < 1e-4, "zconvolve = 直接の巡回畳み込み (n=96、最大差 %.2e)",
                worst_ac);
        r.check(worst_ab < 1e-4,
                "zconvolve = 順序付きの複素積 (packed の DC/Nyquist の扱い含む、最大差 %.2e)",
                worst_ab);
    }

    {
        const int n = 1920, kThreads = 4, kIters = 64;
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> x = lcgFloats(41, n);
        AlignedBuf ref_in(n), ref_out(n), ref_w(n);
        std::memcpy(ref_in.p, x.data(), sizeof(float) * n);
        plan.forwardOrdered(ref_in.p, ref_out.p, ref_w.p);
        std::vector<int> bad(kThreads, 0);
        std::vector<std::thread> ths;
        for (int t = 0; t < kThreads; t++) {
            ths.emplace_back([&, t]() {
                AlignedBuf in(n), out(n), w(n);
                std::memcpy(in.p, x.data(), sizeof(float) * n);
                for (int i = 0; i < kIters; i++) {
                    plan.forwardOrdered(in.p, out.p, w.p);
                    if (std::memcmp(out.p, ref_out.p, sizeof(float) * n) != 0) {
                        bad[static_cast<size_t>(t)]++;
                    }
                }
            });
        }
        for (auto& th : ths) th.join();
        int total_bad = 0;
        for (int b : bad) total_bad += b;
        r.check(total_bad == 0,
                "1 つの setup を %d スレッドが並行に読んで全出力がビット同一 (%d 回)", kThreads,
                kThreads * kIters);
    }
}

void checkCurve(Report& r) {
    r.section("20. 目標曲線のグリッド (20 Hz〜20 kHz 対数 401 点)");

    r.check(caeq::kCurvePoints == 401 && caeq::kCurveMinHz == 20.0 &&
                caeq::kCurveMaxHz == 20000.0,
            "グリッド定数 (401 点 / 20 Hz / 20 kHz) — 段 2 の shm v4 と段 3 の Kotlin は"
            "この 3 定数に合わせる");
    r.check(std::fabs(caeq::curvePointHz(0) - 20.0) < 1e-12 &&
                std::fabs(caeq::curvePointHz(400) - 20000.0) < 1e-9,
            "端の点: f[0] = 20 / f[400] = 20000");
    r.check(std::fabs(caeq::curvePointHz(200) - 20.0 * std::sqrt(1000.0)) < 1e-9,
            "中点: f[200] = 20·√1000 = %.4f Hz", caeq::curvePointHz(200));

    {
        std::vector<float> c(caeq::kCurvePoints, 0.0f);
        for (int i = 0; i < caeq::kCurvePoints; i++) {
            c[static_cast<size_t>(i)] = static_cast<float>(i % 7) - 3.0f;
        }
        bool exact = true;
        for (int i : {0, 1, 57, 200, 399, 400}) {
            const double v = caeq::curveDbAt(c.data(), caeq::curvePointHz(i));
            if (std::fabs(v - static_cast<double>(c[static_cast<size_t>(i)])) > 1e-9) {
                exact = false;
            }
        }
        r.check(exact, "グリッド点そのものでは節点の値を返す");

        const double fm = std::sqrt(caeq::curvePointHz(10) * caeq::curvePointHz(11));
        const double want =
            0.5 * (static_cast<double>(c[10]) + static_cast<double>(c[11]));
        r.check(std::fabs(caeq::curveDbAt(c.data(), fm) - want) < 1e-9,
                "対数中点で線形 dB の平均");

        r.check(caeq::curveDbAt(c.data(), 5.0) == static_cast<double>(c[0]) &&
                    caeq::curveDbAt(c.data(), 0.0) == static_cast<double>(c[0]) &&
                    caeq::curveDbAt(c.data(), 21000.0) == static_cast<double>(c[400]) &&
                    caeq::curveDbAt(c.data(), 96000.0) == static_cast<double>(c[400]),
                "範囲外は端の値を保持 (20 Hz 未満・20 kHz 超〜Nyquist)");
        const double nan_f = std::nan("");
        const double v     = caeq::curveDbAt(c.data(), nan_f);
        r.check(v == static_cast<double>(c[0]), "f が NaN でも有限値 (端) を返す");
    }

    {
        std::vector<float> c(caeq::kCurvePoints, 12.0f);
        r.check(caeq::curveValid(c.data()), "|dB| ≤ 40 の曲線が通る");
        c[400] = 40.0f;
        c[0]   = -40.0f;
        r.check(caeq::curveValid(c.data()), "ちょうど ±40 dB は通る (境界は含む)");
        c[57] = 40.001f;
        r.check(!caeq::curveValid(c.data()), "+40.001 dB は 1 点でも曲線ごと弾く");
        c[57] = -40.001f;
        r.check(!caeq::curveValid(c.data()), "-40.001 dB も弾く");
        c[57] = std::nanf("");
        r.check(!caeq::curveValid(c.data()), "NaN を弾く");
        c[57] = std::numeric_limits<float>::infinity();
        r.check(!caeq::curveValid(c.data()), "Inf を弾く");
        r.check(!caeq::curveValid(nullptr), "nullptr を弾く");
    }

    {
        std::vector<float> c = curveFromKnobs(cagold::kDunuKnobDb);
        double worst = 0.0;
        for (int i = 0; i < cagold::kDunuCurveSpotCount; i++) {
            const cagold::CurveSpot& s = cagold::kDunuCurveSpots[i];
            worst = std::fmax(worst, std::fabs(static_cast<double>(
                                         c[static_cast<size_t>(s.idx)] - s.v)));
        }
        r.check(worst < 2e-6, "DUNU 摘み値からの折れ線再構成が golden と一致 (最大差 %.1e)",
                worst);
        r.check(caeq::curveValid(c.data()), "再構成した DUNU 曲線が検査を通る");
    }
}

struct FirBuild {
    caeq::FftPlan plan_m, plan_2p;
    AlignedBuf data, work, filt;
    caeq::FirDesigner d;
    int steps = 0;

    FirBuild(int m, int block, int taps)
        : data(static_cast<size_t>(m)),
          work(static_cast<size_t>(m)),
          filt(static_cast<size_t>(caeq::firPartitions(taps, block)) *
               static_cast<size_t>(2 * block)) {}

    bool build(const float* curve, double fs, int taps, int m, int block,
               caeq::FirWindow win = caeq::FirWindow::kTailTaper, int taper = -1,
               int64_t budget_ns = 1ll << 60) {
        if (taper < 0) taper = caeq::firDefaultTaper(taps);
        if (!plan_m.ready() && !plan_m.init(m)) return false;
        if (!plan_2p.ready() && !plan_2p.init(2 * block)) return false;
        caeq::FirDesignSpec s;
        s.curve_db = curve;
        s.fs       = fs;
        s.taps     = taps;
        s.m        = m;
        s.block    = block;
        s.window   = win;
        s.taper    = taper;
        if (!d.start(s, &plan_m, &plan_2p, data.p, work.p, filt.p)) return false;
        steps = 0;
        bool done = false;
        while (!done) {
            done = d.step(budget_ns);
            steps++;
            if (steps > 20000000) return false;
        }
        return d.done();
    }
};

std::vector<float> knobsCurve(const double* knobs) { return curveFromKnobs(knobs); }

void checkMinphase(Report& r) {
    r.section("21. 最小位相 IR (参照は 19_minphase_golden.py / numpy float64)");

    std::vector<double> flat(31, 12.0), alt(31);
    for (int i = 0; i < 31; i++) alt[static_cast<size_t>(i)] = (i % 2 == 0) ? 12.0 : -12.0;

    for (int ci = 0; ci < cagold::kMinphaseCaseCount; ci++) {
        const cagold::MinphaseCase& c = cagold::kMinphaseCases[ci];
        std::vector<float> curve;
        if (c.curve == 0) curve = knobsCurve(flat.data());
        if (c.curve == 1) curve = knobsCurve(alt.data());
        if (c.curve == 2) curve = knobsCurve(cagold::kDunuKnobDb);

        FirBuild fb(c.m, 960, c.taps);
        if (!fb.build(curve.data(), c.fs, c.taps, c.m, 960, caeq::FirWindow::kHalfHann,
                      0)) {
            r.check(false, "%s: build", c.name);
            continue;
        }
        double worst_tap = 0.0;
        for (int i = 0; i < c.ntaps_g; i++) {
            const double d =
                std::fabs(static_cast<double>(fb.d.ir()[c.taps_g[i].idx]) - c.taps_g[i].v);
            if (d > worst_tap) worst_tap = d;
        }
        r.check(worst_tap < 3e-5, "%-12s タップの golden 一致 (最大差 %.2e)", c.name,
                worst_tap);

        double worst_resp = 0.0;
        for (int i = 0; i < c.nresp; i++) {
            const double th = 2.0 * kPi * c.resp[i].hz / c.fs;
            const double cr = std::cos(th), ci_ = -std::sin(th);
            double zr = 1.0, zi = 0.0, sr = 0.0, si = 0.0;
            for (int n = 0; n < c.taps; n++) {
                const double h = static_cast<double>(fb.d.ir()[n]);
                sr += h * zr;
                si += h * zi;
                const double nzr = zr * cr - zi * ci_;
                zi = zr * ci_ + zi * cr;
                zr = nzr;
            }
            const double db = 20.0 * std::log10(std::hypot(sr, si) + 1e-300);
            const double d  = std::fabs(db - c.resp[i].db);
            if (d > worst_resp) worst_resp = d;
        }
        r.check(worst_resp < 2e-3, "%-12s 応答の golden 一致 (最大差 %.1e dB)", c.name,
                worst_resp);
    }

    {
        std::vector<float> curve = knobsCurve(cagold::kDunuKnobDb);
        FirBuild a(32768, 960, 8192), b(32768, 960, 8192), c(32768, 960, 8192);
        a.build(curve.data(), 48000.0, 8192, 32768, 960, caeq::FirWindow::kHalfHann, 0);
        b.build(curve.data(), 48000.0, 8192, 32768, 960, caeq::FirWindow::kHalfHann, 0,
                60000);
        c.build(curve.data(), 48000.0, 8192, 32768, 960, caeq::FirWindow::kHalfHann, 0,
                3000);
        const size_t fbytes = static_cast<size_t>(caeq::firPartitions(8192, 960)) * 1920 *
                              sizeof(float);
        r.check(std::memcmp(a.filt.p, b.filt.p, fbytes) == 0 &&
                    std::memcmp(a.filt.p, c.filt.p, fbytes) == 0 &&
                    std::memcmp(a.data.p, b.data.p, sizeof(float) * 8192) == 0,
                "予算の刻み方が違ってもビット同一 (一括 %d 回 / 60µs %d 回 / 3µs %d 回)",
                a.steps, b.steps, c.steps);
        r.check(b.steps > a.steps && c.steps > b.steps,
                "予算を絞ると呼び出し回数が増える (スライスとして機能している)");
    }

    {
        std::vector<float> curve = knobsCurve(cagold::kDunuKnobDb);
        FirBuild fb(32768, 960, 8192);
        fb.plan_m.init(32768);
        fb.plan_2p.init(1920);
        caeq::FirDesignSpec s;
        s.curve_db = curve.data();
        s.fs       = 48000.0;
        s.taps     = 8192;
        s.m        = 32768;
        s.block    = 960;
        auto try_start = [&](const caeq::FirDesignSpec& sp, float* data, float* work,
                             float* filt) {
            return fb.d.start(sp, &fb.plan_m, &fb.plan_2p, data, work, filt);
        };
        r.check(try_start(s, fb.data.p, fb.work.p, fb.filt.p), "正しい仕様は通る");
        fb.d.abort();
        caeq::FirDesignSpec bad = s;
        std::vector<float> nan_curve = curve;
        nan_curve[100] = std::nanf("");
        bad.curve_db = nan_curve.data();
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p), "NaN 曲線を弾く");
        bad = s;
        bad.m = 12800;
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p), "m < 2·taps を弾く");
        bad = s;
        bad.block = 448;
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p), "不正な P (448) を弾く");
        {
            caeq::FftPlan plan_8192;
            plan_8192.init(8192);
            caeq::FirDesignSpec edge = s;
            edge.block = 4096;
            AlignedBuf efilt(static_cast<size_t>(caeq::firPartitions(8192, 4096)) * 8192);
            r.check(fb.d.start(edge, &fb.plan_m, &plan_8192, fb.data.p, fb.work.p,
                               efilt.p),
                    "2P = taps は通る (境界)");
            fb.d.abort();
        }
        bad = s;
        bad.window = caeq::FirWindow::kTailTaper;
        bad.taper  = 0;
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p),
                "kTailTaper で taper=0 を弾く");
        r.check(!try_start(s, fb.data.p + 1, fb.work.p, fb.filt.p),
                "整列していないバッファを弾く");
        r.check(!fb.d.step(1000000), "start していない状態の step は何もしない (false)");
    }
}

double realizedErrDb(const float* h, int taps, double fs, const float* curve,
                     double* worst_hz = nullptr, double* worst_hi40 = nullptr) {
    double worst = 0.0, whz = 0.0, hi40 = 0.0;
    for (int j = 0; j < 2000; j++) {
        const double f = 20.0 * std::pow(1000.0, static_cast<double>(j) / 1999.0);
        const double th = 2.0 * kPi * f / fs;
        const double cr = std::cos(th), ci = -std::sin(th);
        double zr = 1.0, zi = 0.0, sr = 0.0, si = 0.0;
        for (int n = 0; n < taps; n++) {
            const double hv = static_cast<double>(h[n]);
            sr += hv * zr;
            si += hv * zi;
            const double nzr = zr * cr - zi * ci;
            zi = zr * ci + zi * cr;
            zr = nzr;
        }
        const double got  = 20.0 * std::log10(std::hypot(sr, si) + 1e-300);
        const double want = caeq::curveDbAt(curve, f);
        const double d    = std::fabs(got - want);
        if (d > worst) {
            worst = d;
            whz   = f;
        }
        if (f >= 40.0 && d > hi40) hi40 = d;
    }
    if (worst_hz != nullptr) *worst_hz = whz;
    if (worst_hi40 != nullptr) *worst_hi40 = hi40;
    return worst;
}

struct GateCase {
    const char* name;
    std::vector<float> curve;
    bool floor_limited;
};

std::vector<GateCase> gateCases() {
    std::vector<GateCase> cs;
    std::vector<double> k(31);

    for (int i = 0; i < 31; i++) k[static_cast<size_t>(i)] = 12.0;
    cs.push_back({"(a) 平ら +12", curveFromKnobs(k.data()), false});

    for (int i = 0; i < 31; i++) k[static_cast<size_t>(i)] = (i % 2 == 0) ? 12.0 : -12.0;
    cs.push_back({"(b) 交互 ±12", curveFromKnobs(k.data()), true});

    cs.push_back({"(c) DUNU フィット", curveFromKnobs(cagold::kDunuKnobDb), false});

    for (int seed = 0; seed < 3; seed++) {
        std::vector<float> u = lcgFloats(static_cast<uint64_t>(101 + seed), 7);
        for (int i = 0; i < 31; i++) {
            const int    seg = i / 5 < 6 ? i / 5 : 5;
            const double t   = (i - seg * 5) / 5.0;
            const double v0  = static_cast<double>(u[static_cast<size_t>(seg)]) * 10.0;
            const double v1  = static_cast<double>(u[static_cast<size_t>(seg + 1)]) * 10.0;
            k[static_cast<size_t>(i)] = v0 + (v1 - v0) * t;
        }
        static const char* names[] = {"(d) 滑らか乱数 1", "(d) 滑らか乱数 2",
                                      "(d) 滑らか乱数 3"};
        cs.push_back({names[seed], curveFromKnobs(k.data()), false});
    }

    {
        std::vector<float> u = lcgFloats(104, 31);
        for (int i = 0; i < 31; i++) {
            k[static_cast<size_t>(i)] = static_cast<double>(u[static_cast<size_t>(i)]) * 10.0;
        }
        cs.push_back({"(d') ギザギザ乱数", curveFromKnobs(k.data()), true});
    }

    for (int i = 0; i < 31; i++) k[static_cast<size_t>(i)] = 0.0;
    k[0] = 12.0;
    cs.push_back({"(e) 20 Hz だけ +12", curveFromKnobs(k.data()), true});
    k[0]  = 0.0;
    k[30] = 12.0;
    cs.push_back({"(e) 20 kHz だけ +12", curveFromKnobs(k.data()), false});
    return cs;
}

void checkGate(Report& r) {
    r.section("22. 実現誤差 (製品の折れ線目標に対する。これがゲートの数字)");

    std::vector<GateCase> cases = gateCases();

    double worst_practical[3] = {0, 0, 0};
    double worst_floor[3]     = {0, 0, 0};
    double worst_hi40_all[3]  = {0, 0, 0};
    const double fss[3]       = {44100.0, 48000.0, 96000.0};
    r.note("本表 (M = firDefaultM、後端テーパ窓 = 製品の既定):");
    for (int fi = 0; fi < 3; fi++) {
        const double fs   = fss[fi];
        const int    taps = caeq::firTapsFor(fs);
        const int    m    = caeq::firDefaultM(fs);
        for (const GateCase& c : cases) {
            FirBuild fb(m, 960, taps);
            if (!fb.build(c.curve.data(), fs, taps, m, 960)) {
                r.check(false, "%s @%.1fk build", c.name, fs / 1000.0);
                continue;
            }
            double whz = 0.0, hi40 = 0.0;
            const double e =
                realizedErrDb(fb.d.ir(), taps, fs, c.curve.data(), &whz, &hi40);
            r.note("  %5.1fk %-20s 最大 %7.4f dB (%5.0f Hz) / 40 Hz 以上 %7.4f dB",
                   fs / 1000.0, c.name, e, whz, hi40);
            if (c.floor_limited) {
                worst_floor[fi] = std::fmax(worst_floor[fi], e);
            } else {
                worst_practical[fi] = std::fmax(worst_practical[fi], e);
            }
            worst_hi40_all[fi] = std::fmax(worst_hi40_all[fi], hi40);
        }
    }
    for (int fi = 0; fi < 3; fi++) {
        r.note("  %5.1fk まとめ: 実用 %7.4f dB / 床 (b, d', e-20Hz) %7.4f dB / "
               "全曲線の 40 Hz 以上 %7.4f dB",
               fss[fi] / 1000.0, worst_practical[fi], worst_floor[fi],
               worst_hi40_all[fi]);
    }
    {
        const double wp =
            std::fmax(worst_practical[0], std::fmax(worst_practical[1], worst_practical[2]));
        const double w40 = std::fmax(worst_hi40_all[0] > worst_hi40_all[1]
                                         ? worst_hi40_all[0]
                                         : worst_hi40_all[1],
                                     worst_hi40_all[2]);
        (void)w40;
        double hi40_practical = 0.0;
        for (int fi = 0; fi < 3; fi++) {
            const double fs   = fss[fi];
            const int    taps = caeq::firTapsFor(fs);
            const int    m    = caeq::firDefaultM(fs);
            for (const GateCase& c : cases) {
                if (c.floor_limited) continue;
                FirBuild fb(m, 960, taps);
                fb.build(c.curve.data(), fs, taps, m, 960);
                double hi40 = 0.0;
                realizedErrDb(fb.d.ir(), taps, fs, c.curve.data(), nullptr, &hi40);
                hi40_practical = std::fmax(hi40_practical, hi40);
            }
        }
        r.check(wp < 0.30,
                "実用曲線の全帯域: 44.1k %.4f / 48k %.4f / 96k %.4f dB (床は 20〜40 Hz)",
                worst_practical[0], worst_practical[1], worst_practical[2]);
        r.check(hi40_practical < 0.16,
                "実用曲線の 40 Hz 以上: 最大 %.4f dB — ±0.2 dB 級を余裕で満たす",
                hi40_practical);
    }
    r.check(worst_floor[0] < 8.0 && worst_floor[1] < 8.0 && worst_floor[2] < 8.0,
            "分解能の床 (20〜32 Hz は摘み間隔 5〜6 Hz < IR 170 ms の分解能): "
            "最大 %.2f dB — 0.2 dB を名乗れない領域として正直に別枠",
            std::fmax(worst_floor[0], std::fmax(worst_floor[1], worst_floor[2])));

    r.note("M の選定 (fs=48k taps=8192、および 96k taps=16384):");
    for (const GateCase& c : cases) {
        char row[256];
        int  at = std::snprintf(row, sizeof(row), "  48k %-20s", c.name);
        for (int m : {16384, 32768, 65536}) {
            FirBuild fb(m, 960, 8192);
            fb.build(c.curve.data(), 48000.0, 8192, m, 960);
            const double e = realizedErrDb(fb.d.ir(), 8192, 48000.0, c.curve.data());
            at += std::snprintf(row + at, sizeof(row) - static_cast<size_t>(at),
                                "  M=%d: %7.4f", m, e);
        }
        r.note("%s", row);
    }
    for (const GateCase& c : cases) {
        char row[256];
        int  at = std::snprintf(row, sizeof(row), "  96k %-20s", c.name);
        for (int m : {32768, 65536}) {
            FirBuild fb(m, 960, 16384);
            fb.build(c.curve.data(), 96000.0, 16384, m, 960);
            const double e = realizedErrDb(fb.d.ir(), 16384, 96000.0, c.curve.data());
            at += std::snprintf(row + at, sizeof(row) - static_cast<size_t>(at),
                                "  M=%d: %7.4f", m, e);
        }
        r.note("%s", row);
    }
    {
        double worst_gap = 0.0;
        for (const GateCase& c : cases) {
            if (c.floor_limited) continue;
            FirBuild fa(caeq::firDefaultM(48000.0), 960, 8192), fb(65536, 960, 8192);
            fa.build(c.curve.data(), 48000.0, 8192, caeq::firDefaultM(48000.0), 960);
            fb.build(c.curve.data(), 48000.0, 8192, 65536, 960);
            const double ea = realizedErrDb(fa.d.ir(), 8192, 48000.0, c.curve.data());
            const double eb = realizedErrDb(fb.d.ir(), 8192, 48000.0, c.curve.data());
            worst_gap = std::fmax(worst_gap, ea - eb);
        }
        r.check(worst_gap < 0.02,
                "既定 M = 2·taps と M=65536 の差は実用曲線で %.4f dB — 既定で足りる",
                worst_gap);
    }

    r.note("窓の比較 (fs=48k、M=16384。テーパ長は taps 比):");
    for (const GateCase& c : cases) {
        double e[5];
        struct W {
            caeq::FirWindow win;
            int taper;
        } ws[5] = {{caeq::FirWindow::kHalfHann, 0},
                   {caeq::FirWindow::kRect, 0},
                   {caeq::FirWindow::kTailTaper, 8192 / 16},
                   {caeq::FirWindow::kTailTaper, 8192 / 8},
                   {caeq::FirWindow::kTailTaper, 8192 / 4}};
        for (int wi = 0; wi < 5; wi++) {
            FirBuild fb(16384, 960, 8192);
            fb.build(c.curve.data(), 48000.0, 8192, 16384, 960, ws[wi].win, ws[wi].taper);
            e[wi] = realizedErrDb(fb.d.ir(), 8192, 48000.0, c.curve.data());
        }
        r.note("  %-20s 全長 half-Hann %7.4f / 窓なし %7.4f / 後端 1/16 %7.4f / "
               "1/8 %7.4f / 1/4 %7.4f",
               c.name, e[0], e[1], e[2], e[3], e[4]);
    }
}

void loadFace(caeq::FftPlan& plan, float* face, const float* h, int taps, int block) {
    const int n = 2 * block;
    AlignedBuf stage(static_cast<size_t>(n)), work(static_cast<size_t>(n));
    const int k_total = caeq::firPartitions(taps, block);
    for (int k = 0; k < k_total; k++) {
        const int base = k * block;
        const int len  = std::min(block, taps - base);
        for (int i = 0; i < len; i++) {
            stage.p[i] = h[base + i] / static_cast<float>(n);
        }
        std::memset(stage.p + len, 0, sizeof(float) * static_cast<size_t>(n - len));
        plan.forward(stage.p, face + static_cast<size_t>(k) * static_cast<size_t>(n),
                     work.p);
    }
}

struct KernelRig {
    caeq::FftPlan plan;
    AlignedBuf fdl, filt0, filt1, tail, stage, acc, acc2, work;
    caeq::FirKernel k;
    int block, taps, ch;

    KernelRig(int block_, int taps_, int ch_)
        : fdl(static_cast<size_t>(ch_) *
              static_cast<size_t>(caeq::firPartitions(taps_, block_)) *
              static_cast<size_t>(2 * block_)),
          filt0(static_cast<size_t>(caeq::firPartitions(taps_, block_)) *
                static_cast<size_t>(2 * block_)),
          filt1(static_cast<size_t>(caeq::firPartitions(taps_, block_)) *
                static_cast<size_t>(2 * block_)),
          tail(static_cast<size_t>(ch_) * static_cast<size_t>(block_)),
          stage(static_cast<size_t>(2 * block_)),
          acc(static_cast<size_t>(2 * block_)),
          acc2(static_cast<size_t>(2 * block_)),
          work(static_cast<size_t>(2 * block_)),
          block(block_),
          taps(taps_),
          ch(ch_) {
        plan.init(2 * block_);
    }

    bool bind() {
        caeq::FirKernel::Buffers b;
        b.fdl     = fdl.p;
        b.filt[0] = filt0.p;
        b.filt[1] = filt1.p;
        b.tail    = tail.p;
        b.stage   = stage.p;
        b.acc     = acc.p;
        b.acc2    = acc2.p;
        b.work    = work.p;
        return k.bind(&plan, block, taps, ch, b);
    }
};

void checkKernel(Report& r) {
    r.section("23. 一様分割畳み込み (追加遅延 0 の証明と端数分割)");

    for (int p : {512, 960, 1024, 2048}) {
        const int taps = 8192;
        std::vector<float> curve = curveFromKnobs(cagold::kDunuKnobDb);
        FirBuild fb(32768, p, taps);
        fb.build(curve.data(), 48000.0, taps, 32768, p);

        KernelRig rig(p, taps, 1);
        rig.bind();
        std::memcpy(rig.filt0.p, fb.filt.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        const int nblk = caeq::firPartitions(taps, p) + 3;
        std::vector<float> in(static_cast<size_t>(p), 0.0f);
        std::vector<float> out(static_cast<size_t>(p));
        std::vector<float> got;
        for (int b = 0; b < nblk; b++) {
            std::fill(in.begin(), in.end(), 0.0f);
            if (b == 0) {
                in[0] = 1.0f;
                in[static_cast<size_t>(p - 1)] = 0.5f;
            }
            rig.k.processBlock(in.data(), out.data(), 0, -1, 0.0f, 0.0f);
            got.insert(got.end(), out.begin(), out.end());
        }
        double scale = 0.0;
        for (int i = 0; i < taps; i++) {
            scale = std::fmax(scale, std::fabs(static_cast<double>(fb.d.ir()[i])));
        }
        double worst = 0.0;
        for (int i = 0; i < nblk * p; i++) {
            const double h1 = i < taps ? static_cast<double>(fb.d.ir()[i]) : 0.0;
            const double h2 = (i >= p - 1 && i - (p - 1) < taps)
                                  ? 0.5 * static_cast<double>(fb.d.ir()[i - (p - 1)])
                                  : 0.0;
            worst = std::fmax(worst, std::fabs(static_cast<double>(
                                         got[static_cast<size_t>(i)]) -
                                     (h1 + h2)));
        }
        const int k_total = caeq::firPartitions(taps, p);
        r.check(worst / scale < 3e-6,
                "P=%-5d K=%-3d%s インパルス (先頭 + 末尾)→設計 h と一致 (相対 %.1e)、遅延 0",
                p, k_total, (taps % p) ? " (端数)" : "      ", worst / scale);
    }

    {
        const int p = 512, taps = 1500, nblk = 6;
        std::vector<float> h = lcgFloats(51, taps);
        KernelRig rig(p, taps, 1);
        rig.bind();
        loadFace(rig.plan, rig.filt0.p, h.data(), taps, p);
        std::vector<float> x = lcgFloats(52, p * nblk);
        std::vector<float> out(static_cast<size_t>(p));
        std::vector<float> got;
        for (int b = 0; b < nblk; b++) {
            rig.k.processBlock(x.data() + static_cast<size_t>(b) * p, out.data(), 0, -1,
                               0.0f, 0.0f);
            got.insert(got.end(), out.begin(), out.end());
        }
        double worst = 0.0, scale = 0.0;
        for (int t = 0; t < p * nblk; t++) {
            double want = 0.0;
            for (int k = 0; k <= t && k < taps; k++) {
                want += static_cast<double>(h[static_cast<size_t>(k)]) *
                        static_cast<double>(x[static_cast<size_t>(t - k)]);
            }
            scale = std::fmax(scale, std::fabs(want));
            worst = std::fmax(worst, std::fabs(static_cast<double>(
                                         got[static_cast<size_t>(t)]) -
                                     want));
        }
        r.check(worst / scale < 1e-5,
                "乱数 h (1500 タップ、K=3 端数) × 乱数入力 = 直接畳み込み (相対 %.1e)",
                worst / scale);
    }

    {
        const int p = 512, taps = 1500;
        std::vector<float> h = lcgFloats(53, taps);
        KernelRig a(p, taps, 1), b(p, taps, 1);
        a.bind();
        b.bind();
        loadFace(a.plan, a.filt0.p, h.data(), taps, p);
        std::memcpy(b.filt0.p, a.filt0.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        std::vector<float> x1 = lcgFloats(54, p * 5), x2 = lcgFloats(55, p * 4);
        std::vector<float> oa(static_cast<size_t>(p)), ob(static_cast<size_t>(p));
        for (int blk = 0; blk < 5; blk++) {
            a.k.processBlock(x1.data() + static_cast<size_t>(blk) * p, oa.data(), 0, -1,
                             0.0f, 0.0f);
        }
        a.k.reset();
        bool same = true;
        for (int blk = 0; blk < 4; blk++) {
            a.k.processBlock(x2.data() + static_cast<size_t>(blk) * p, oa.data(), 0, -1,
                             0.0f, 0.0f);
            b.k.processBlock(x2.data() + static_cast<size_t>(blk) * p, ob.data(), 0, -1,
                             0.0f, 0.0f);
            if (std::memcmp(oa.data(), ob.data(), sizeof(float) * static_cast<size_t>(p)) !=
                0) {
                same = false;
            }
        }
        r.check(same, "reset (fill=0) 後の出力が新品の kernel とビット同一 — FDL の残骸が"
                      "漏れない");
    }

    {
        const int p = 512, taps = 1500, nblk = 10;
        std::vector<float> ha = lcgFloats(61, taps), hb = lcgFloats(62, taps);
        KernelRig mix(p, taps, 1), half(p, taps, 1), ra(p, taps, 1), rb(p, taps, 1);
        mix.bind();
        half.bind();
        ra.bind();
        rb.bind();
        loadFace(mix.plan, mix.filt0.p, ha.data(), taps, p);
        loadFace(mix.plan, mix.filt1.p, hb.data(), taps, p);
        std::memcpy(half.filt0.p, mix.filt0.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        std::memcpy(half.filt1.p, mix.filt1.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        loadFace(ra.plan, ra.filt0.p, ha.data(), taps, p);
        loadFace(rb.plan, rb.filt0.p, hb.data(), taps, p);
        std::vector<float> x = lcgFloats(63, p * nblk);
        std::vector<float> om(static_cast<size_t>(p)), oh(static_cast<size_t>(p)),
            oa(static_cast<size_t>(p)), ob(static_cast<size_t>(p));
        const int fade_from = 3;
        const float dw      = 1.0f / 480.0f;
        float w0            = 0.0f;
        double worst_half = 0.0, worst_end = 0.0, scale = 0.0, peak_mid = 0.0;
        bool   pre_same = true;
        int    fade_done_at = -1;
        for (int blk = 0; blk < nblk; blk++) {
            const float* xin = x.data() + static_cast<size_t>(blk) * p;
            ra.k.processBlock(xin, oa.data(), 0, -1, 0.0f, 0.0f);
            rb.k.processBlock(xin, ob.data(), 0, -1, 0.0f, 0.0f);
            half.k.processBlock(xin, oh.data(), 0, 1, 0.5f, 0.0f);
            for (int i = 0; i < p && blk >= 1; i++) {
                const double want = 0.5 * (static_cast<double>(oa[static_cast<size_t>(i)]) +
                                           static_cast<double>(ob[static_cast<size_t>(i)]));
                scale      = std::fmax(scale, std::fabs(want));
                worst_half = std::fmax(worst_half,
                                       std::fabs(static_cast<double>(oh[static_cast<size_t>(i)]) - want));
            }
            if (blk < fade_from) {
                mix.k.processBlock(xin, om.data(), 0, -1, 0.0f, 0.0f);
                if (std::memcmp(om.data(), oa.data(), sizeof(float) * static_cast<size_t>(p)) != 0) {
                    pre_same = false;
                }
            } else {
                mix.k.processBlock(xin, om.data(), 0, 1, w0, dw);
                if (w0 >= 1.0f && fade_done_at < 0) fade_done_at = blk;
                if (fade_done_at >= 0 && blk > fade_done_at) {
                    for (int i = 0; i < p; i++) {
                        worst_end = std::fmax(
                            worst_end, std::fabs(static_cast<double>(om[static_cast<size_t>(i)]) -
                                                 static_cast<double>(ob[static_cast<size_t>(i)])));
                    }
                }
                for (int i = 0; i < p; i++) {
                    peak_mid = std::fmax(peak_mid,
                                         std::fabs(static_cast<double>(om[static_cast<size_t>(i)])));
                }
                w0 += static_cast<float>(p) * dw;
                if (w0 > 1.0f) w0 = 1.0f;
            }
        }
        r.check(pre_same, "フェード前は面 A とビット同一");
        r.check(worst_half / scale < 2e-5,
                "定常の半々混合 = (A+B)/2 (尻尾も同じ重み、相対 %.1e)", worst_half / scale);
        r.check(fade_done_at >= 0 && worst_end / scale < 2e-5,
                "フェード完了後は面 B と一致 (相対 %.1e)", worst_end / scale);
        r.check(std::isfinite(peak_mid) && peak_mid < scale * 3.0,
                "ランプ中の出力が有界 (peak %.2f / scale %.2f)", peak_mid, scale);
    }

    {
        const int p = 512, taps = 1500;
        std::vector<float> h = lcgFloats(71, taps);
        KernelRig st(p, taps, 2), mono_l(p, taps, 1), mono_r(p, taps, 1);
        st.bind();
        mono_l.bind();
        mono_r.bind();
        loadFace(st.plan, st.filt0.p, h.data(), taps, p);
        std::memcpy(mono_l.filt0.p, st.filt0.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        std::memcpy(mono_r.filt0.p, st.filt0.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        std::vector<float> xl = lcgFloats(72, p * 4), xr = lcgFloats(73, p * 4);
        std::vector<float> inter(static_cast<size_t>(2 * p)), ol(static_cast<size_t>(p)),
            orr(static_cast<size_t>(p));
        bool same = true;
        for (int blk = 0; blk < 4; blk++) {
            for (int i = 0; i < p; i++) {
                inter[static_cast<size_t>(2 * i)] = xl[static_cast<size_t>(blk * p + i)];
                inter[static_cast<size_t>(2 * i + 1)] = xr[static_cast<size_t>(blk * p + i)];
            }
            st.k.processBlock(inter.data(), inter.data(), 0, -1, 0.0f, 0.0f);
            mono_l.k.processBlock(xl.data() + static_cast<size_t>(blk) * p, ol.data(), 0,
                                  -1, 0.0f, 0.0f);
            mono_r.k.processBlock(xr.data() + static_cast<size_t>(blk) * p, orr.data(), 0,
                                  -1, 0.0f, 0.0f);
            for (int i = 0; i < p; i++) {
                if (inter[static_cast<size_t>(2 * i)] != ol[static_cast<size_t>(i)] ||
                    inter[static_cast<size_t>(2 * i + 1)] != orr[static_cast<size_t>(i)]) {
                    same = false;
                }
            }
        }
        r.check(same, "ステレオ (in-place) = モノ 2 本とビット同一 — チャンネルが混ざらない");

        std::vector<float> bad(static_cast<size_t>(p), 0.1f);
        bad[10] = std::nanf("");
        bad[20] = std::numeric_limits<float>::infinity();
        mono_l.k.processBlock(bad.data(), ol.data(), 0, -1, 0.0f, 0.0f);
        r.check(mono_l.k.scrubbedSamples() == 2 && catest::allFinite(ol),
                "NaN / Inf を入り口で 0 に潰して数える (scrubbed=%u)、出力は有限",
                mono_l.k.scrubbedSamples());
    }

    {
        const int p = 512, taps = 1500;
        std::vector<float> h = lcgFloats(81, taps);
        KernelRig warm(p, taps, 1), full(p, taps, 1);
        warm.bind();
        full.bind();
        loadFace(warm.plan, warm.filt0.p, h.data(), taps, p);
        std::memcpy(full.filt0.p, warm.filt0.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(taps, p)) *
                        static_cast<size_t>(2 * p));
        std::vector<float> x = lcgFloats(82, p * 8);
        std::vector<float> ow(static_cast<size_t>(p)), of(static_cast<size_t>(p));
        bool same = true;
        for (int blk = 0; blk < 8; blk++) {
            const float* xin = x.data() + static_cast<size_t>(blk) * p;
            full.k.processBlock(xin, of.data(), 0, -1, 0.0f, 0.0f);
            if (blk < 3) {
                warm.k.pushBlock(xin);
            } else if (blk == 3) {
                warm.k.processBlock(xin, ow.data(), 0, -1, 0.0f, 0.0f);
            } else {
                warm.k.processBlock(xin, ow.data(), 0, -1, 0.0f, 0.0f);
                if (std::memcmp(ow.data(), of.data(),
                                sizeof(float) * static_cast<size_t>(p)) != 0) {
                    same = false;
                }
            }
        }
        r.check(same, "push 温め + 尻尾温め 1 ブロックで、以後は連続運転とビット同一");
    }

    {
        KernelRig rig(512, 1500, 1);
        caeq::FirKernel::Buffers b;
        b.fdl     = rig.fdl.p;
        b.filt[0] = rig.filt0.p;
        b.filt[1] = rig.filt1.p;
        b.tail    = rig.tail.p;
        b.stage   = rig.stage.p;
        b.acc     = rig.acc.p;
        b.acc2    = rig.acc2.p;
        b.work    = rig.work.p;
        caeq::FirKernel k;
        r.check(!k.bind(&rig.plan, 448, 1500, 1, b), "P=448 (2^6·7) を弾く");
        r.check(!k.bind(&rig.plan, 500, 1500, 1, b), "P=500 (2P が 32 の倍数でない) を弾く");
        r.check(!k.bind(&rig.plan, 512, 900, 1, b), "2P > taps を弾く");
        r.check(!k.bind(&rig.plan, 512, 1500, 3, b), "ch=3 を弾く (FIR は device 枠 ≤2ch)");
        r.check(!k.bind(&rig.plan, 960, 1500, 1, b), "plan と P の不一致を弾く");
        caeq::FirKernel::Buffers bad = b;
        bad.acc = nullptr;
        r.check(!k.bind(&rig.plan, 512, 1500, 1, bad), "null バッファを弾く");
        bad     = b;
        bad.acc = rig.acc.p + 1;
        r.check(!k.bind(&rig.plan, 512, 1500, 1, bad), "整列していないバッファを弾く");
    }
}

uint64_t testClock() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

struct PipeDriver {
    caeq::EqPipeline& pl;
    int p, ch;
    std::vector<float> in, out;
    uint64_t seed = 900;
    bool flying_start = false;

    PipeDriver(caeq::EqPipeline& pl_, int p_, int ch_)
        : pl(pl_), p(p_), ch(ch_), in(static_cast<size_t>(p_ * ch_)),
          out(static_cast<size_t>(p_ * ch_)) {}

    void noiseBlock() {
        std::vector<float> x = lcgFloats(seed++, p * ch);
        for (size_t i = 0; i < in.size(); i++) in[i] = 0.1f * x[i];
        step();
    }

    void silentBlock() {
        std::fill(in.begin(), in.end(), 0.0f);
        step();
    }

    void step(bool accumulate = false) {
        pl.process(in.data(), out.data(), p, accumulate);
        const auto st = pl.firState();
        if ((st == caeq::EqPipeline::FirState::kFadeIn ||
             st == caeq::EqPipeline::FirState::kFir) &&
            pl.fdlFill() < caeq::firPartitions(pl.designTaps(), p)) {
            flying_start = true;
        }
    }

    int runUntil(caeq::EqPipeline::FirState want, int max_blocks) {
        for (int i = 0; i < max_blocks; i++) {
            noiseBlock();
            if (pl.firState() == want) return i + 1;
        }
        return -1;
    }
};

void checkPipeline(Report& r) {
    r.section("24. EqPipeline (biquad ⇄ FIR の遷移と診断)");

    std::vector<float> dunu = curveFromKnobs(cagold::kDunuKnobDb);
    std::vector<double> altk(31);
    for (int i = 0; i < 31; i++) altk[static_cast<size_t>(i)] = (i % 2 == 0) ? 6.0 : -6.0;
    std::vector<float> alt = curveFromKnobs(altk.data());

    caeq::Params flat;
    flat.band_count = 0;
    flat.preamp_db  = 0.0;

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&testClock);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.warmUp();
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        r.check(pl.firAvailable(), "arena が確保されている (%zu KB, ch=2 fs=48k)",
                pl.arenaBytes() / 1024);

        PipeDriver drv(pl, 960, 2);
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 60);
        r.check(reached > 0, "FIR まで到達 (%d ブロック = %.0f ms)", reached,
                reached * 960.0 / 48.0);
        r.check(!drv.flying_start,
                "AND 条件: fill < K のまま出力へ混ざった瞬間が無い (flying start なし)");
        r.check(pl.rebuilds() == 1 && pl.fallbacks() == 0,
                "designer 1 回、落下 0 (rebuilds=%u fallbacks=%u)", pl.rebuilds(),
                pl.fallbacks());

        for (int b = 0; b < 12; b++) drv.silentBlock();
        FirBuild ref(caeq::firDefaultM(48000.0), 960, 8192);
        ref.build(dunu.data(), 48000.0, 8192, caeq::firDefaultM(48000.0), 960);
        std::vector<float> got_l, got_r;
        for (int b = 0; b < 10; b++) {
            std::fill(drv.in.begin(), drv.in.end(), 0.0f);
            if (b == 0) drv.in[0] = 0.5f;
            drv.step();
            for (int i = 0; i < 960; i++) {
                got_l.push_back(drv.out[static_cast<size_t>(2 * i)]);
                got_r.push_back(drv.out[static_cast<size_t>(2 * i + 1)]);
            }
        }
        double worst = 0.0, worst_r = 0.0, scale = 0.0;
        for (int t = 0; t < 9600; t++) {
            const double want =
                t < 8192 ? 0.5 * static_cast<double>(ref.d.ir()[t]) : 0.0;
            scale   = std::fmax(scale, std::fabs(want));
            worst   = std::fmax(worst, std::fabs(static_cast<double>(
                                         got_l[static_cast<size_t>(t)]) -
                                     want));
            worst_r = std::fmax(worst_r,
                                std::fabs(static_cast<double>(got_r[static_cast<size_t>(t)])));
        }
        r.check(worst / scale < 1e-5 && worst_r == 0.0,
                "FIR 稼働中のインパルス応答 = 設計 h (相対 %.1e)、R チャンネルは無音のまま",
                worst / scale);

        pl.setCurve(alt.data(), 2);
        bool dipped = false;
        for (int b = 0; b < 60 && pl.faceFades() == 0; b++) {
            drv.noiseBlock();
            if (pl.firState() != caeq::EqPipeline::FirState::kFir) dipped = true;
        }
        r.check(pl.faceFades() == 1 && !dipped && pl.rebuilds() == 2,
                "曲線変更 → 面フェード 1 回、biquad へ落ちない (faceFades=%u rebuilds=%u)",
                pl.faceFades(), pl.rebuilds());
        r.check(pl.curveGeneration() == 2, "鳴っている世代が 2 (取り込みが見える)");

        FirBuild ref2(caeq::firDefaultM(48000.0), 960, 8192);
        ref2.build(alt.data(), 48000.0, 8192, caeq::firDefaultM(48000.0), 960);
        got_l.clear();
        for (int b = 0; b < 12; b++) drv.silentBlock();
        for (int b = 0; b < 10; b++) {
            std::fill(drv.in.begin(), drv.in.end(), 0.0f);
            if (b == 0) drv.in[0] = 0.5f;
            drv.step();
            for (int i = 0; i < 960; i++) {
                got_l.push_back(drv.out[static_cast<size_t>(2 * i)]);
            }
        }
        worst = 0.0;
        scale = 0.0;
        for (int t = 0; t < 9600; t++) {
            const double want =
                t < 8192 ? 0.5 * static_cast<double>(ref2.d.ir()[t]) : 0.0;
            scale = std::fmax(scale, std::fabs(want));
            worst = std::fmax(worst, std::fabs(static_cast<double>(
                                         got_l[static_cast<size_t>(t)]) -
                                     want));
        }
        r.check(worst / scale < 1e-5, "差し替え後の応答 = 新しい h (相対 %.1e)",
                worst / scale);

        std::vector<float> bad = dunu;
        bad[100] = std::nanf("");
        pl.setCurve(bad.data(), 3);
        for (int b = 0; b < 4; b++) drv.noiseBlock();
        r.check(pl.curveRejected() == 1 &&
                    pl.firState() == caeq::EqPipeline::FirState::kFir &&
                    pl.curveGeneration() == 2,
                "NaN 曲線は棄却して前の曲線のまま鳴り続ける (rejected=%u)",
                pl.curveRejected());

        for (int b = 0; b < 12; b++) drv.silentBlock();
        std::fill(drv.in.begin(), drv.in.end(), 0.0f);
        std::fill(drv.out.begin(), drv.out.end(), 0.25f);
        drv.step(true);
        bool acc_ok = true;
        for (float v : drv.out) {
            if (v != 0.25f) acc_ok = false;
        }
        r.check(acc_ok, "accumulate: 無音入力で out が保存される (上書きしていない)");

        const uint32_t fb_before = pl.fallbacks();
        pl.setActive(false);
        int idle_at = -1;
        for (int b = 0; b < 10; b++) {
            drv.noiseBlock();
            if (pl.idle()) {
                idle_at = b;
                break;
            }
        }
        r.check(idle_at >= 0 && pl.fallbacks() == fb_before &&
                    pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                "DISABLE はフェードして idle へ (%d ブロック目)。落下に数えない", idle_at + 1);

        pl.setActive(true);
        const int again = drv.runUntil(caeq::EqPipeline::FirState::kFir, 60);
        r.check(again > 0 && !drv.flying_start, "再有効化で FIR まで戻る (%d ブロック)",
                again);

        PipeDriver drv2(pl, 1024, 2);
        drv2.noiseBlock();
        r.check(pl.fallbacks() == fb_before + 1 &&
                    pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                "P 960→1024 で落下 (fallbacks=%u)、biquad が鳴っている", pl.fallbacks());
        const int back = drv2.runUntil(caeq::EqPipeline::FirState::kFir, 80);
        r.check(back > 0 && !drv2.flying_start, "P=1024 で組み直して FIR へ戻る (%d ブロック)",
                back);
        r.check(pl.maxSliceNs() > 0, "スライスの実測が録れている (最大 %.1f µs、ホスト x86)",
                pl.maxSliceNs() / 1000.0);
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 448, 2);
        for (int b = 0; b < 6; b++) drv.noiseBlock();
        r.check(pl.unfitSizeCount() == 1 && pl.unfitBudgetCount() == 0 &&
                    pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                "P=448 は大きさの不適 1 回 (毎ブロックは数えない)、biquad のまま");
        bool finite = true;
        for (float v : drv.out) finite = finite && std::isfinite(v);
        r.check(finite, "不適でも出力は健全 (biquad が鳴る)");

        caeq::EqPipeline pl9;
        pl9.configure(96000.0, 2, caeq::Structure::kTdf2);
        pl9.snapParams(flat);
        pl9.setActive(true);
        pl9.setFirEnabled(true);
        pl9.setCurve(dunu.data(), 1);
        PipeDriver drv9(pl9, 32, 2);
        for (int b = 0; b < 6; b++) drv9.noiseBlock();
        r.check(pl9.unfitBudgetCount() == 1 && pl9.unfitSizeCount() == 0 &&
                    pl9.firState() == caeq::EqPipeline::FirState::kBiquad,
                "96k の P=32 (合法サイズ) は予算の不適 — 2 種のカウンタが別々に動く");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 12, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        r.check(!pl.firAvailable(), "ch=12 は arena なし (FIR フラグが来ても biquad)");
        std::vector<float> io(12 * 960, 0.01f);
        pl.process(io.data(), io.data(), 960, false);
        r.check(pl.firState() == caeq::EqPipeline::FirState::kBiquad &&
                    catest::allFinite(io),
                "ch=12 は biquad のまま健全");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 960, 2);
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 60);
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        drv.noiseBlock();
        r.check(reached > 0 && pl.firState() == caeq::EqPipeline::FirState::kFir,
                "同じ fs/ch の configure で FIR が生き残る (冪等)");
        pl.configure(44100.0, 2, caeq::Structure::kTdf2);
        drv.noiseBlock();
        r.check(pl.firState() != caeq::EqPipeline::FirState::kFir,
                "fs 変更で FIR 状態を捨てる (taps/M/FDL すべて fs 依存)");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Eq eq;
        eq.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Params p31;
        p31.band_count = 8;
        p31.preamp_db  = -2.0;
        for (int i = 0; i < 8; i++) {
            p31.bands[i] = caeq::Band{caeq::BandType::kPeaking, 100.0 * (i + 1) * 2.0, 2.0,
                                      (i % 2) ? 4.0 : -3.0};
        }
        pl.snapParams(p31);
        eq.snapParams(p31);
        pl.setActive(true);
        eq.setActive(true);
        bool same = true;
        std::vector<float> a(2 * 960), b(2 * 960);
        for (int blk = 0; blk < 6; blk++) {
            std::vector<float> x = lcgFloats(300 + static_cast<uint64_t>(blk), 2 * 960);
            std::memcpy(a.data(), x.data(), sizeof(float) * a.size());
            std::memcpy(b.data(), x.data(), sizeof(float) * b.size());
            pl.process(a.data(), a.data(), 960);
            eq.process(b.data(), b.data(), 960);
            if (std::memcmp(a.data(), b.data(), sizeof(float) * a.size()) != 0) same = false;
        }
        r.check(same, "FIR 未要求の pipeline = 素の Eq とビット同一 (既存経路は無傷)");
    }

    {
        std::vector<double> k12(31, 12.0);
        std::vector<float> flat12 = curveFromKnobs(k12.data());
        caeq::EqPipeline pl;
        pl.configure(48000.0, 1, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(flat12.data(), 1);
        PipeDriver drv(pl, 960, 1);
        r.check(drv.runUntil(caeq::EqPipeline::FirState::kFir, 60) > 0, "(準備) FIR 到達");

        caeq::Eq eq;
        eq.configure(48000.0, 1, caeq::Structure::kTdf2);
        caeq::Params pre12 = flat;
        pre12.preamp_db    = 12.0;
        eq.snapParams(pre12);
        eq.setActive(true);
        std::vector<float> warm(960, 0.0f);
        eq.process(warm.data(), warm.data(), 960);

        std::vector<float> tone = catest::makeTones(960 * 4, 48000.0);
        std::vector<float> oa(960), ob(960);
        pl.setActive(false);
        eq.setActive(false);
        double worst = 0.0;
        for (int blk = 0; blk < 4; blk++) {
            std::memcpy(oa.data(), tone.data() + blk * 960, sizeof(float) * 960);
            std::memcpy(ob.data(), tone.data() + blk * 960, sizeof(float) * 960);
            pl.process(oa.data(), oa.data(), 960);
            eq.process(ob.data(), ob.data(), 960);
            for (int i = 0; i < 960; i++) {
                worst = std::fmax(worst, std::fabs(static_cast<double>(oa[static_cast<size_t>(i)]) -
                                                   static_cast<double>(ob[static_cast<size_t>(i)])));
            }
        }
        r.check(worst < 2e-4,
                "DISABLE フェードの軌跡が Eq と一致 (ユニティ biquad、平ら +12 dB 相当で"
                "最大差 %.1e)",
                worst);
    }

    {
        caeq::EqPipeline pl;
        pl.configure(44100.0, 2, caeq::Structure::kTdf2);
        caeq::Params bq;
        bq.band_count = 31;
        bq.preamp_db  = 0.0;
        for (int i = 0; i < 31; i++) {
            bq.bands[i] = caeq::Band{caeq::BandType::kPeaking,
                                     caeq::curvePointHz(cagold::kKnobGridIdx[i]), 4.32,
                                     cagold::kDunuKnobDb[i]};
        }
        pl.snapParams(bq);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 512, 2);
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 120);
        pl.setActive(false);
        double worst_after = 0.0;
        for (int b = 0; b < 8; b++) {
            std::vector<float> x = lcgFloats(770 + static_cast<uint64_t>(b), 512 * 2);
            std::vector<float> dry = x;
            pl.process(x.data(), x.data(), 512, false);
            if (b == 0) continue;
            for (size_t i = 0; i < x.size(); i++) {
                worst_after = std::fmax(worst_after,
                                        std::fabs(static_cast<double>(x[i]) -
                                                  static_cast<double>(dry[i])));
            }
        }
        r.check(reached > 0 && worst_after < 1e-6,
                "31 バンドの biquad でも DISABLE 後は完全な素通し (44.1k P=512、最大差 %.1e)",
                worst_after);
    }

    {
        for (int big : {caeq::kMaxConvBlock * 2, caeq::kMaxConvBlock + 32, 8192, 6144}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            caeq::Params bq;
            bq.band_count = 31;
            bq.preamp_db  = 0.0;
            for (int i = 0; i < 31; i++) {
                bq.bands[i] = caeq::Band{caeq::BandType::kPeaking,
                                         caeq::curvePointHz(cagold::kKnobGridIdx[i]), 4.32,
                                         cagold::kDunuKnobDb[i]};
            }
            pl.snapParams(bq);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(dunu.data(), 1);
            PipeDriver drv(pl, 960, 2);
            const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 80);
            std::vector<float> x(static_cast<size_t>(big) * 2, 0.01f);
            std::vector<float> y(x.size(), 0.0f);
            pl.process(x.data(), y.data(), big, false);
            bool finite = true;
            for (float v : y) finite = finite && std::isfinite(v);
            r.check(reached > 0 && finite &&
                        pl.firState() == caeq::EqPipeline::FirState::kBiquad &&
                        pl.unfitSizeCount() >= 1,
                    "P=%-5d (> 上限 %d) が来ても区画外へ出ない。biquad が鳴り、"
                    "大きさの不適に数える",
                    big, caeq::kMaxConvBlock);
        }
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 128, 2);
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);
        pl.setFirEnabled(false);
        drv.noiseBlock();
        const bool fading = pl.firState() == caeq::EqPipeline::FirState::kFadeOut;
        int settled = -1;
        for (int b = 0; b < 20; b++) {
            drv.noiseBlock();
            if (pl.firState() == caeq::EqPipeline::FirState::kBiquad) {
                settled = b;
                break;
            }
        }
        r.check(reached > 0 && fading && settled >= 0,
                "モード OFF は kFadeOut を経て %d ブロック (%.1f ms) で biquad へ",
                settled + 2, (settled + 2) * 128000.0 / 48000.0);
        r.check(pl.modeOffs() == 1 && pl.fallbacks() == 0,
                "モード OFF は modeOffs=1 / fallbacks=0 (診断の意味を混ぜない)");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 960, 2);
        drv.noiseBlock();
        pl.designerForTest().injectNonFinite();
        for (int b = 0; b < 30; b++) drv.noiseBlock();
        r.check(pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                "設計器が失敗したら kPrepare から出る (state=%d)",
                static_cast<int>(pl.firState()));
        r.check(pl.designFailures() == 1 && pl.fallbacks() == 0 && pl.modeOffs() == 0,
                "設計器の失敗は designFailures だけ (音の経路は変わっていない)");
        const uint32_t rb = pl.rebuilds();
        for (int b = 0; b < 20; b++) drv.noiseBlock();
        r.check(pl.rebuilds() == rb,
                "同じ曲線では組み直さない (成果ゼロの FFT を焼き続けない。rebuilds=%u)", rb);
        pl.setCurve(alt.data(), 77);
        const int back = drv.runUntil(caeq::EqPipeline::FirState::kFir, 80);
        r.check(back > 0 && pl.rebuilds() > rb, "新しい曲線が来たら再挑戦して FIR に戻る");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 11);
        PipeDriver drv(pl, 128, 2);
        drv.noiseBlock();
        const uint32_t during_prepare = pl.curveGeneration();
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);
        const uint32_t after_fade = pl.curveGeneration();
        pl.setCurve(alt.data(), 22);
        uint32_t during_face = 0;
        for (int b = 0; b < 40; b++) {
            drv.noiseBlock();
            during_face = pl.curveGeneration();
            if (pl.faceFades() > 0) break;
            if (b >= 1) break;
        }
        for (int b = 0; b < 80 && pl.faceFades() == 0; b++) drv.noiseBlock();
        const uint32_t after_face = pl.curveGeneration();
        r.check(reached > 0 && during_prepare == 0 && after_fade == 11,
                "準備中は 0、鳴り始めたら 11 (準備中 %u / 到達後 %u)", during_prepare,
                after_fade);
        r.check(during_face == 11 && after_face == 22,
                "面フェード中は前の世代のまま、鳴り切ってから 22 (%u -> %u)", during_face,
                after_face);
    }

    {
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            pl.biquad().setFadeMillis(fade_ms);
            caeq::Params p12;
            p12.band_count = 0;
            p12.preamp_db  = 12.0;
            pl.snapParams(p12);
            pl.setActive(true);

            PipeDriver drv(pl, 128, 1);
            for (int b = 0; b < 60; b++) drv.noiseBlock();

            pl.setFirEnabled(true);
            pl.setCurve(flat0.data(), 1);
            double worst = 0.0;
            int    blocks = 0;
            for (int b = 0; b < 400; b++) {
                std::vector<float> x = lcgFloats(8100 + static_cast<uint64_t>(b), 128);
                std::vector<float> y = x;
                pl.process(y.data(), y.data(), 128, false);
                for (int i = 0; i < 128; i++) {
                    const double want = static_cast<double>(x[static_cast<size_t>(i)]) * kGain;
                    worst = std::fmax(worst, std::fabs(
                        static_cast<double>(y[static_cast<size_t>(i)]) - want));
                }
                blocks = b;
                if (pl.firState() == caeq::EqPipeline::FirState::kFir) break;
            }
            worst_all = std::fmax(worst_all, worst);
            r.note("  Eq フェード %5.1f ms: 乗り移り全区間の max|out − dry×3.981| = %.5f "
                   "(%d ブロック)",
                   fade_ms, worst, blocks + 1);
        }
        r.check(worst_all < 1e-5,
                "[乗り移り 昇り] フェード長を変えても和が 1 (最大 %.5f) — "
                "mix の傾きが Eq に追随",
                worst_all);
    }

    {
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        caeq::Params p12;
        p12.band_count = 0;
        p12.preamp_db  = 12.0;
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            pl.biquad().setFadeMillis(fade_ms);
            pl.snapParams(p12);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(flat0.data(), 1);
            PipeDriver drv(pl, 128, 1);
            drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);

            pl.setFirEnabled(false);
            double worst = 0.0;
            int    blocks = 0;
            for (int b = 0; b < 120; b++) {
                std::vector<float> x = lcgFloats(8500 + static_cast<uint64_t>(b), 128);
                std::vector<float> y = x;
                pl.process(y.data(), y.data(), 128, false);
                for (int i = 0; i < 128; i++) {
                    const double want = static_cast<double>(x[static_cast<size_t>(i)]) * kGain;
                    worst = std::fmax(worst, std::fabs(
                        static_cast<double>(y[static_cast<size_t>(i)]) - want));
                }
                blocks = b;
                if (pl.firState() == caeq::EqPipeline::FirState::kBiquad) break;
            }
            worst_all = std::fmax(worst_all, worst);
            r.note("  Eq フェード %5.1f ms: 降りる全区間の max|out − dry×3.981| = %.5f "
                   "(%d ブロックで biquad へ)",
                   fade_ms, worst, blocks + 1);
        }
        r.check(worst_all < 1e-5,
                "[乗り移り 降り] kFadeOut も厳密なクロスフェード (最大 %.5f)", worst_all);
    }

    {
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        caeq::Params p12;
        p12.band_count = 0;
        p12.preamp_db  = 12.0;
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            pl.biquad().setFadeMillis(fade_ms);
            pl.snapParams(p12);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(flat0.data(), 1);
            PipeDriver drv(pl, 128, 1);
            const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);

            caeq::Eq eq;
            eq.configure(48000.0, 1, caeq::Structure::kTdf2);
            eq.setFadeMillis(fade_ms);
            eq.snapParams(p12);
            eq.setActive(true);
            std::vector<float> warm(128, 0.0f);
            for (int b = 0; b < 60; b++) eq.process(warm.data(), warm.data(), 128);

            pl.setActive(false);
            eq.setActive(false);
            double worst = 0.0;
            for (int b = 0; b < 60; b++) {
                std::vector<float> x = lcgFloats(8300 + static_cast<uint64_t>(b), 128);
                std::vector<float> a = x, c = x;
                pl.process(a.data(), a.data(), 128, false);
                eq.process(c.data(), c.data(), 128, false);
                for (int i = 0; i < 128; i++) {
                    worst = std::fmax(worst,
                                      std::fabs(static_cast<double>(a[static_cast<size_t>(i)]) -
                                                static_cast<double>(c[static_cast<size_t>(i)])));
                }
            }
            worst_all = std::fmax(worst_all, worst);
            r.note("  Eq フェード %5.1f ms: DISABLE の軌跡 max|pipeline − 素の Eq| = %.5f "
                   "(FIR 到達 %d ブロック、gain %.3f)",
                   fade_ms, worst, reached, kGain);
        }
        r.check(worst_all < 1e-5,
                "[DISABLE] フェード長を変えても FIR 側 wet が Eq と同じ軌跡 (最大 %.5f) — "
                "wet の歩幅を焼かない",
                worst_all);
    }

    {
        auto clickDb = [&](auto&& trigger, const char* label) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            caeq::Params bq;
            bq.band_count = 31;
            bq.preamp_db  = 0.0;
            for (int i = 0; i < 31; i++) {
                bq.bands[i] =
                    caeq::Band{caeq::BandType::kPeaking,
                               caeq::curvePointHz(cagold::kKnobGridIdx[i]), 4.32,
                               cagold::kDunuKnobDb[i]};
            }
            pl.snapParams(bq);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(dunu.data(), 1);
            std::vector<float> tone = catest::makeTones(960 * 120, 48000.0);
            std::vector<float> out;
            out.reserve(tone.size());
            std::vector<float> blk(960);
            int fir_at = -1;
            for (int b = 0; b < 120; b++) {
                std::memcpy(blk.data(), tone.data() + b * 960, sizeof(float) * 960);
                const bool was_fir = pl.firState() == caeq::EqPipeline::FirState::kFir;
                if (was_fir && fir_at < 0) fir_at = b;
                if (fir_at >= 0 && b == fir_at + 20) trigger(pl);
                pl.process(blk.data(), blk.data(), 960);
                out.insert(out.end(), blk.begin(), blk.end());
            }
            if (fir_at < 0) return -999.0;
            std::vector<double> d = catest::toDouble(out);
            std::vector<double> hp =
                catest::sosFilt(catest::butterworthHighpass(8, 3000.0, 48000.0), d);
            const size_t ev = static_cast<size_t>((fir_at + 20) * 960);
            const double peak = catest::peakAbs(hp, ev, ev + 48000 * 4 / 100);
            r.note("  %s: %.1f dBFS (tones 0.06/0.04、ホスト)", label, catest::dbOf(peak));
            return catest::dbOf(peak);
        };
        const double c1 = clickDb([&](caeq::EqPipeline& pl) { pl.setCurve(alt.data(), 9); },
                                  "面フェード (曲線差し替え)");
        const double c2 = clickDb(
            [&](caeq::EqPipeline& pl) {
                std::vector<float> one(1 * 512, 0.0f);
                pl.process(one.data(), one.data(), 512, false);
            },
            "落下 (P 変化 → biquad 立ち上げ)");
        const double c3 = clickDb([&](caeq::EqPipeline&) {}, "定常 (イベントなし、床)");
        r.check(c1 < -55.0 && c2 < -20.0,
                "クリックの記録 (面フェード %.1f / 落下 %.1f / 床 %.1f dBFS)", c1, c2, c3);
    }
}

void checkFftTiming(Report& r) {
    r.section("25. pffft の所要時間 (ホスト x86-64。実機は段 4 で測り直す)");

    r.note("合法 2P 系列 (P = ブロック長、n = 2P の実 FFT 1 回):");
    for (int p = 16; p <= 4096; p += 16) {
        if (!caeq::convBlockSizeValid(p)) continue;
        const int n = 2 * p;
        const bool interesting = (p == 16 || p == 32 || p == 96 || p == 480 || p == 512 ||
                                  p == 960 || p == 1024 || p == 2048 || p == 4096);
        if (!interesting) continue;
        caeq::FftPlan plan;
        plan.init(n);
        AlignedBuf a(static_cast<size_t>(n)), b(static_cast<size_t>(n)),
            w(static_cast<size_t>(n));
        std::vector<float> x = lcgFloats(7, n);
        std::memcpy(a.p, x.data(), sizeof(float) * static_cast<size_t>(n));
        const int iters = n >= 8192 ? 200 : 1000;
        const double fwd = benchNs([&] { plan.forward(a.p, b.p, w.p); }, iters);
        const double ord = benchNs([&] { plan.forwardOrdered(a.p, b.p, w.p); }, iters);
        const double zc  = benchNs(
            [&] { plan.convolveAccumulate(a.p, b.p, w.p, 1.0f); }, iters);
        r.note("  P=%-5d n=%-5d 順序なし %7.2f µs / 順序付き %7.2f µs / zconvolve %6.3f µs",
               p, n, fwd / 1000.0, ord / 1000.0, zc / 1000.0);
    }

    r.note("大 FFT (M) と setup 構築 (in-place):");
    double m32768_ord = 0.0;
    for (int m : {16384, 32768, 65536}) {
        caeq::FftPlan plan;
        plan.init(m);
        AlignedBuf a(static_cast<size_t>(m)), b(static_cast<size_t>(m)),
            w(static_cast<size_t>(m));
        std::vector<float> x = lcgFloats(8, m);
        std::memcpy(a.p, x.data(), sizeof(float) * static_cast<size_t>(m));
        const double ord = benchNs([&] { plan.forwardOrdered(a.p, b.p, w.p); }, 100);
        const double inv = benchNs([&] { plan.inverseOrdered(a.p, b.p, w.p); }, 100);
        if (m == 32768) m32768_ord = ord;
        std::vector<char> mem(caeq::fftSetupBytes(m) + 64);
        void* aligned = reinterpret_cast<void*>(
            (reinterpret_cast<uintptr_t>(mem.data()) + 63u) &
            ~static_cast<uintptr_t>(63u));
        const double setup = benchNs(
            [&] {
                caeq::FftPlan p2;
                p2.initInPlace(m, aligned, mem.size());
            },
            20);
        r.note("  M=%-6d 順序付き前進 %7.1f µs / 逆 %7.1f µs / setup %7.1f µs "
               "(setup %zu KB)",
               m, ord / 1000.0, inv / 1000.0, setup / 1000.0,
               caeq::fftSetupBytes(m) / 1024);
    }

    r.note("2P の setup 構築 (落下からの復帰でスライス 1 回に載る量):");
    for (int p : {512, 960, 1024, 2048, 4096}) {
        const int n = 2 * p;
        std::vector<char> mem(caeq::fftSetupBytes(n) + 64);
        void* aligned = reinterpret_cast<void*>(
            (reinterpret_cast<uintptr_t>(mem.data()) + 63u) &
            ~static_cast<uintptr_t>(63u));
        const double setup = benchNs(
            [&] {
                caeq::FftPlan p2;
                p2.initInPlace(n, aligned, mem.size());
            },
            50);
        r.note("  P=%-5d setup(2P=%-5d) %7.1f µs", p, n, setup / 1000.0);
    }

    r.note("スライス予算 (kSliceBudgetFrac = %.2f) と最大クォンタム (M=32768 の実測):",
           caeq::kSliceBudgetFrac);
    for (int p : {128, 512, 960, 1024, 2048}) {
        const double budget_us =
            caeq::kSliceBudgetFrac * static_cast<double>(p) / 48000.0 * 1e6;
        r.note("  P=%-5d 予算 %7.1f µs → M FFT %.1f µs は%s", p, budget_us,
               m32768_ord / 1000.0, budget_us > m32768_ord / 1000.0 ? "収まる" : "収まらない");
    }

    {
        bool conservative = true;
        for (int n : {1024, 1920, 4096, 16384, 32768, 65536}) {
            caeq::FftPlan plan;
            plan.init(n);
            AlignedBuf a(static_cast<size_t>(n)), b(static_cast<size_t>(n)),
                w(static_cast<size_t>(n));
            const double meas = benchNs([&] { plan.forwardOrdered(a.p, b.p, w.p); },
                                        n >= 16384 ? 50 : 300);
            if (meas > static_cast<double>(caeq::fircost::fftNs(n))) conservative = false;
        }
        r.note("  費用モデル fircost::fftNs はホスト実測の%s (計装ビルドでは破れてよい)",
               conservative ? "上界" : "**下**回り — 較正がずれている");
    }
}

void checkAccounting(Report& r) {
    r.section("26. スライス予算の会計と定常性能 (数字はすべてホスト x86 実測)");

    std::vector<float> dunu = curveFromKnobs(cagold::kDunuKnobDb);

    r.note("設計器の会計 (モデル上の消費。決定的な値):");
    bool model_ok = true;
    struct AcctCase { double fs; int taps; int m; };
    const AcctCase accts[] = {{48000.0, 8192, caeq::firDefaultM(48000.0)},
                              {96000.0, 16384, caeq::firDefaultM(96000.0)}};
    for (const AcctCase& ac : accts) {
        for (int p : {128, 512, 960, 1024, 2048}) {
            const int64_t budget =
                static_cast<int64_t>(caeq::kSliceBudgetFrac * p / ac.fs * 1e9);
            const int64_t q_m = caeq::fircost::fftNs(ac.m);
            const int64_t q_p = caeq::fircost::fftNs(2 * p) +
                                static_cast<int64_t>(2 * p) * caeq::fircost::kCopyNsPerElem;
            const int64_t quantum = q_m > q_p ? q_m : q_p;
            FirBuild fb(ac.m, p, ac.taps);
            fb.plan_m.init(ac.m);
            fb.plan_2p.init(2 * p);
            caeq::FirDesignSpec s;
            s.curve_db = dunu.data();
            s.fs       = ac.fs;
            s.taps     = ac.taps;
            s.m        = ac.m;
            s.block    = p;
            s.taper    = caeq::firDefaultTaper(ac.taps);
            fb.d.start(s, &fb.plan_m, &fb.plan_2p, fb.data.p, fb.work.p, fb.filt.p);
            int64_t worst_model = 0, total_model = 0;
            int     steps       = 0;
            bool    done        = false;
            while (!done) {
                done = fb.d.step(budget);
                const int64_t spent = fb.d.lastStepModelNs();
                if (spent > worst_model) worst_model = spent;
                total_model += spent;
                steps++;
            }
            const bool fit = worst_model <= budget + quantum;
            model_ok = model_ok && fit;
            r.note("  %5.1fk P=%-5d 予算 %7.1f µs + クォンタム %6.1f µs: %2d step、"
                   "最大 %7.1f µs、設計全体 %6.2f ms%s",
                   ac.fs / 1000.0, p, budget / 1000.0, quantum / 1000.0, steps,
                   worst_model / 1000.0, static_cast<double>(total_model) / 1e6,
                   fit ? "" : "  ← 超過");
        }
    }
    r.check(model_ok,
            "モデル消費 ≤ 予算 + 不可分クォンタム 1 個 (step() の契約。環境に依らない)");

    {
        std::vector<float> curve = dunu;
        auto budgetFit = [&](double fs, int p) {
            caeq::EqPipeline pl;
            pl.configure(fs, 2, caeq::Structure::kTdf2);
            caeq::Params fl;
            pl.snapParams(fl);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(curve.data(), 1);
            std::vector<float> io(static_cast<size_t>(p) * 2, 0.01f);
            pl.process(io.data(), io.data(), p, false);
            return pl.unfitBudgetCount() == 0;
        };
        int lowest_ok_48 = 0;
        for (int p = 16; p <= 4096; p += 16) {
            if (!caeq::convBlockSizeValid(p)) continue;
            if (budgetFit(48000.0, p)) {
                lowest_ok_48 = p;
                break;
            }
        }
        int lowest_ok_96 = 0;
        for (int p = 16; p <= 4096; p += 16) {
            if (!caeq::convBlockSizeValid(p)) continue;
            if (budgetFit(96000.0, p)) {
                lowest_ok_96 = p;
                break;
            }
        }
        r.note("  予算が通る最小の P: 48k で %d (%.2f ms) / 96k で %d (%.2f ms)",
               lowest_ok_48, lowest_ok_48 * 1000.0 / 48000.0, lowest_ok_96,
               lowest_ok_96 * 1000.0 / 96000.0);
        r.check(lowest_ok_48 > 0 && lowest_ok_48 <= 512 && lowest_ok_96 > 0 &&
                    lowest_ok_96 <= 512,
                "実測で来るブロック長 (512 以上) はすべて予算内 — 最大クォンタムが収まる");
        r.check(!budgetFit(96000.0, 32) && budgetFit(96000.0, 512),
                "小さすぎる P は予算で弾き、実用域は通す (判定が実際に効いている)");
    }

    {
        auto blockOkAt = [&](int p) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            caeq::Params fl;
            pl.snapParams(fl);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(dunu.data(), 1);
            std::vector<float> io(static_cast<size_t>(p) * 2, 0.01f);
            pl.process(io.data(), io.data(), p, false);
            return pl.blockOk();
        };
        r.check(blockOkAt(4096), "上限 P = 4096 のブロック長を通す (下げると黙って FIR が死ぬ)");
        r.check(!blockOkAt(8192), "上限を超える P = 8192 は弾く (arena の区画を守る門)");
        r.check(caeq::convBlockSizeValid(8192),
                "8192 は合法な P — 弾いているのは大きさの上限であって形ではない");
    }

    r.note("壁時計の実測 (**環境で揺れる。ASan や負荷で数倍になる**。参考値):");
    double worst_ratio = 0.0;
    for (int p : {128, 512, 960, 1024, 2048}) {
        const int64_t budget =
            static_cast<int64_t>(caeq::kSliceBudgetFrac * p / 48000.0 * 1e9);
        FirBuild fb(caeq::firDefaultM(48000.0), p, 8192);
        fb.plan_m.init(caeq::firDefaultM(48000.0));
        fb.plan_2p.init(2 * p);
        caeq::FirDesignSpec s;
        s.curve_db = dunu.data();
        s.fs       = 48000.0;
        s.taps     = 8192;
        s.m        = caeq::firDefaultM(48000.0);
        s.block    = p;
        s.taper    = caeq::firDefaultTaper(8192);
        fb.d.start(s, &fb.plan_m, &fb.plan_2p, fb.data.p, fb.work.p, fb.filt.p);
        double worst_step = 0.0, total = 0.0;
        int    steps      = 0;
        bool   done       = false;
        while (!done) {
            const double t0 = nowNs();
            done            = fb.d.step(budget);
            const double dt = nowNs() - t0;
            worst_step      = std::fmax(worst_step, dt);
            total += dt;
            steps++;
        }
        worst_ratio = std::fmax(worst_ratio, worst_step / static_cast<double>(budget));
        r.note("  P=%-5d 予算 %7.1f µs: %2d step、最大 %7.1f µs (予算比 %.2fx)、"
               "合計 %6.2f ms",
               p, budget / 1000.0, steps, worst_step / 1000.0,
               worst_step / static_cast<double>(budget), total / 1e6);
    }
    r.check(worst_ratio < 20.0,
            "壁時計が予算の 20 倍を超えない (桁違いの退行だけを拾う緩い天井。実測 %.2fx)",
            worst_ratio);

    r.note("畳み込みの定常 (taps=8192, 2ch。比較: biquad 31 バンド 2ch は既存 11 節):");
    for (int p : {512, 960, 1024, 2048}) {
        KernelRig rig(p, 8192, 2);
        rig.bind();
        FirBuild fb(16384, p, 8192);
        fb.build(dunu.data(), 48000.0, 8192, 16384, p);
        std::memcpy(rig.filt0.p, fb.filt.p,
                    sizeof(float) * static_cast<size_t>(caeq::firPartitions(8192, p)) *
                        static_cast<size_t>(2 * p));
        std::vector<float> io = lcgFloats(400, 2 * p);
        for (int b = 0; b < caeq::firPartitions(8192, p) + 2; b++) {
            rig.k.processBlock(io.data(), io.data(), 0, -1, 0.0f, 0.0f);
        }
        const double ns = benchNs(
            [&] { rig.k.processBlock(io.data(), io.data(), 0, -1, 0.0f, 0.0f); }, 50);
        r.note("  P=%-5d K=%-3d %7.1f ns/frame (= %.2f%% @48k 1 コア)  面フェード中はほぼ 2 倍",
               p, caeq::firPartitions(8192, p), ns / p, ns / p * 48000.0 / 1e7);
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Params flat;
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 960, 2);
        if (drv.runUntil(caeq::EqPipeline::FirState::kFir, 60) > 0) {
            std::vector<float> io = lcgFloats(401, 2 * 960);
            const double ns =
                benchNs([&] { pl.process(io.data(), io.data(), 960, false); }, 50);
            r.note("  pipeline 定常 (kFir P=960 2ch): %.1f ns/frame (= %.2f%% @48k 1 コア)",
                   ns / 960.0, ns / 960.0 * 48000.0 / 1e7);
        }
    }
}

void checkFuzz(Report& r) {
    r.section("27. ファズ");

    {
        int bad = 0;
        for (int it = 0; it < 40; it++) {
            std::vector<float> u = lcgFloats(1000 + static_cast<uint64_t>(it), 31);
            std::vector<double> k(31);
            for (int i = 0; i < 31; i++) {
                k[static_cast<size_t>(i)] = static_cast<double>(u[static_cast<size_t>(i)]) * 38.0;
            }
            std::vector<float> curve = curveFromKnobs(k.data());
            if (!caeq::curveValid(curve.data())) {
                bad++;
                continue;
            }
            FirBuild fb(16384, 960, 8192);
            if (!fb.build(curve.data(), 48000.0, 8192, 16384, 960)) {
                bad++;
                continue;
            }
            for (int i = 0; i < 8192; i++) {
                if (!std::isfinite(fb.d.ir()[i])) bad++;
            }
        }
        r.check(bad == 0, "±38 dB の乱数摘み 40 本: 全部組めて IR が有限");
    }

    {
        int passed = 0;
        for (int it = 0; it < 100; it++) {
            std::vector<float> u = lcgFloats(2000 + static_cast<uint64_t>(it), 31);
            std::vector<double> k(31);
            for (int i = 0; i < 31; i++) {
                k[static_cast<size_t>(i)] = static_cast<double>(u[static_cast<size_t>(i)]) * 10.0;
            }
            std::vector<float> curve = curveFromKnobs(k.data());
            std::vector<float> pos = lcgFloats(3000 + static_cast<uint64_t>(it), 2);
            const int at = static_cast<int>((static_cast<double>(pos[0]) * 0.5 + 0.5) * 400.0);
            switch (it % 4) {
            case 0: curve[static_cast<size_t>(at)] = std::nanf(""); break;
            case 1: curve[static_cast<size_t>(at)] = std::numeric_limits<float>::infinity(); break;
            case 2: curve[static_cast<size_t>(at)] = 40.5f; break;
            case 3: curve[static_cast<size_t>(at)] = -41.0f; break;
            }
            if (caeq::curveValid(curve.data())) passed++;
        }
        r.check(passed == 0, "汚した曲線 100 本が全部検査で落ちる");
    }

    {
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        caeq::Params flat;
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        std::vector<float> dunu = curveFromKnobs(cagold::kDunuKnobDb);
        pl.setCurve(dunu.data(), 1);
        uint64_t s = 4242;
        auto rnd = [&s]() {
            s = s * 6364136223846793005ull + 1442695040888963407ull;
            return static_cast<uint32_t>(s >> 33);
        };
        const int ps[4] = {512, 960, 1024, 448};
        bool finite = true;
        uint32_t gen = 10;
        for (int b = 0; b < 400; b++) {
            const uint32_t roll = rnd() % 100;
            if (roll < 4) pl.setActive((rnd() & 1) != 0);
            if (roll >= 4 && roll < 8) pl.setFirEnabled((rnd() & 1) != 0);
            if (roll == 10) pl.reset();
            if (roll >= 12 && roll < 16) {
                std::vector<float> u = lcgFloats(5000 + b, 31);
                std::vector<double> k(31);
                for (int i = 0; i < 31; i++) {
                    k[static_cast<size_t>(i)] =
                        static_cast<double>(u[static_cast<size_t>(i)]) * 30.0;
                }
                std::vector<float> c = curveFromKnobs(k.data());
                if ((b % 4) == 3) c[rnd() % 401] = std::nanf("");
                pl.setCurve(c.data(), ++gen);
            }
            const int p = ps[(b / 25) % 4];
            std::vector<float> x = lcgFloats(6000 + static_cast<uint64_t>(b), 2 * p);
            if ((rnd() % 16) == 0) x[rnd() % static_cast<uint32_t>(2 * p)] = std::nanf("");
            std::vector<float> in_copy = x;
            if ((rnd() & 1) != 0) {
                std::vector<float> acc(static_cast<size_t>(2 * p), 0.0f);
                pl.process(x.data(), acc.data(), p, true);
                for (int i = 0; i < 2 * p; i++) {
                    if (!std::isfinite(acc[static_cast<size_t>(i)]) &&
                        std::isfinite(in_copy[static_cast<size_t>(i)])) {
                        finite = false;
                    }
                }
            } else {
                pl.process(x.data(), x.data(), p, false);
                for (int i = 0; i < 2 * p; i++) {
                    if (!std::isfinite(x[static_cast<size_t>(i)]) &&
                        std::isfinite(in_copy[static_cast<size_t>(i)])) {
                        finite = false;
                    }
                }
            }
        }
        r.check(finite, "乱れた運転 400 ブロック (P 変化・NaN 注入・reset・トグル): "
                        "非有限が入力位置の外へ増殖しない");
        r.check(pl.rebuilds() >= 2 && pl.fallbacks() >= 1 && pl.unfitSizeCount() >= 1 &&
                    pl.curveRejected() >= 1,
                "ファズが FIR の稼働と落下を実際に通った (rebuilds=%u fallbacks=%u "
                "unfitSize=%u rejected=%u scrubbed=%u)",
                pl.rebuilds(), pl.fallbacks(), pl.unfitSizeCount(), pl.curveRejected(),
                pl.scrubbedSamples());
    }
}

void checkAlignment(Report& r) {
    r.section("29. 整列の不変条件 (pffft は process 経路でも assert で見る)");

    r.note("PFFFT は pffft_transform_internal / pffft_zconvolve_accumulate でも");
    r.note("  assert(VALIGNED(...)) を持つ。**この製品は NDEBUG を定義しない**ので、");
    r.note("  整列が崩れると audio HAL が abort() = 端末全体が無音。到達しないことを");
    r.note("  こちら側の不変条件で保証する (dsp/ca_eq_fft.h の「整列の不変条件」)。");

    {
        bool ok = true;
        int checked = 0;
        for (int p = 16; p <= 4096; p += 16) {
            if (!caeq::convBlockSizeValid(p)) continue;
            const int n = 2 * p;
            if ((static_cast<size_t>(n) * sizeof(float)) % 16u != 0) ok = false;
            checked++;
        }
        r.check(ok && checked > 40,
                "合法 P すべてで 2P·sizeof(float) が 16 の倍数 (%d 通り) — "
                "FDL/分割スペクトルの k·n ずらしが整列を壊さない",
                checked);
        ok = true;
        for (int m : {8192, 16384, 32768, 65536}) {
            if (!caeq::fftSizeValid(m)) ok = false;
            if ((static_cast<size_t>(m) * sizeof(float)) % 16u != 0) ok = false;
        }
        r.check(ok, "M も同様 (data 末尾 m−n の詰め替え先が整列)");
    }

    {
        const int p = 960, taps = 8192, m = caeq::firDefaultM(48000.0);
        std::vector<float> curve = curveFromKnobs(cagold::kDunuKnobDb);
        FirBuild fb(m, p, taps);
        fb.build(curve.data(), 48000.0, taps, m, p);
        KernelRig rig(p, taps, 2);
        rig.bind();
        const int n = 2 * p;
        const int k = caeq::firPartitions(taps, p);
        bool aligned = caeq::fftAligned(fb.data.p) && caeq::fftAligned(fb.work.p) &&
                       caeq::fftAligned(fb.filt.p) && caeq::fftAligned(rig.fdl.p) &&
                       caeq::fftAligned(rig.acc.p) && caeq::fftAligned(rig.acc2.p) &&
                       caeq::fftAligned(rig.stage.p) && caeq::fftAligned(rig.work.p);
        int derived = 0;
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < k; i++) {
                const float* slot = rig.fdl.p + (static_cast<size_t>(c) *
                                                     static_cast<size_t>(k) +
                                                 static_cast<size_t>(i)) *
                                                    static_cast<size_t>(n);
                if (!caeq::fftAligned(slot)) aligned = false;
                derived++;
            }
        }
        for (int i = 0; i < k; i++) {
            const float* seg =
                fb.filt.p + static_cast<size_t>(i) * static_cast<size_t>(n);
            if (!caeq::fftAligned(seg)) aligned = false;
            derived++;
        }
        if (!caeq::fftAligned(fb.data.p + (m - n))) aligned = false;
        derived++;
        r.check(aligned, "稼働中の派生ポインタ %d 本すべてが 16 B 整列 (基底 + k·n + m−n)",
                derived);
    }

    {
        const int p = 512, taps = 8192;
        KernelRig rig(p, taps, 1);
        caeq::FirKernel::Buffers b;
        b.fdl     = rig.fdl.p;
        b.filt[0] = rig.filt0.p;
        b.filt[1] = rig.filt1.p;
        b.tail    = rig.tail.p;
        b.stage   = rig.stage.p;
        b.acc     = rig.acc.p;
        b.acc2    = rig.acc2.p;
        b.work    = rig.work.p;
        int refused = 0;
        float* const orig[7] = {b.fdl, b.filt[0], b.filt[1], b.stage, b.acc, b.acc2, b.work};
        float** const slots[7] = {&b.fdl, &b.filt[0], &b.filt[1], &b.stage,
                                  &b.acc, &b.acc2,    &b.work};
        for (int i = 0; i < 7; i++) {
            *slots[i] = orig[i] + 1;
            caeq::FirKernel k;
            if (!k.bind(&rig.plan, p, taps, 1, b)) refused++;
            *slots[i] = orig[i];
        }
        r.check(refused == 7,
                "FFT に渡す 7 本のどれが崩れても bind が断る (%d/7) — assert へ行かせない",
                refused);

        caeq::FftPlan pm, p2;
        pm.init(caeq::firDefaultM(48000.0));
        p2.init(2 * p);
        std::vector<float> curve = curveFromKnobs(cagold::kDunuKnobDb);
        AlignedBuf data(static_cast<size_t>(caeq::firDefaultM(48000.0)) + 4),
            work(static_cast<size_t>(caeq::firDefaultM(48000.0)) + 4),
            filt(static_cast<size_t>(caeq::firPartitions(taps, p)) *
                     static_cast<size_t>(2 * p) + 4);
        caeq::FirDesignSpec s;
        s.curve_db = curve.data();
        s.fs       = 48000.0;
        s.taps     = taps;
        s.m        = caeq::firDefaultM(48000.0);
        s.block    = p;
        s.taper    = caeq::firDefaultTaper(taps);
        caeq::FirDesigner d;
        int drefused = 0;
        if (!d.start(s, &pm, &p2, data.p + 1, work.p, filt.p)) drefused++;
        if (!d.start(s, &pm, &p2, data.p, work.p + 1, filt.p)) drefused++;
        if (!d.start(s, &pm, &p2, data.p, work.p, filt.p + 1)) drefused++;
        r.check(drefused == 3, "designer も 3 本すべてで断る (%d/3)", drefused);
    }

    {
        r.note("pipeline の arena は 64 B 整列で切り出す (reserveArena の align64)。");
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        r.check(pl.firAvailable() && caeq::fftAligned(pl.arenaBaseForTest()),
                "arena の基底が 16 B 整列 (実測)");
    }
}

}

void runFirSections(Report& r) {
    checkFftWrapper(r);
    checkCurve(r);
    checkMinphase(r);
    checkGate(r);
    checkKernel(r);
    checkPipeline(r);
    checkFftTiming(r);
    checkAccounting(r);
    checkFuzz(r);
}

void runFirAlignmentSection(Report& r) { checkAlignment(r); }
