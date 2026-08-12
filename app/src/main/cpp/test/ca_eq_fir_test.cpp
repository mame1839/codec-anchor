// 「高精度」(最小位相 FIR) 経路のハーネス。ca_eq_test.cpp の main から呼ばれる。
//
// 参照は llmdocs/tools/eq/19_minphase_golden.py が生成した ca_eq_fir_golden.h
// (numpy, float64)。C 側は float32 なので、許容はテスト側が持つ。

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
#include "../dsp/pffft/pffft.h"  // pffft_simd_size (前提の記録) と setup_bytes の直接検分

using catest::Report;

namespace {

constexpr double kPi = 3.14159265358979323846;

// --- 道具 -------------------------------------------------------------------

// 64 B 整列の float バッファ (pffft は 16 B 要求。arena と同じ 64 で切る)。
struct AlignedBuf {
    std::vector<float> raw;
    float* p;
    explicit AlignedBuf(size_t n) : raw(n + 16, 0.0f) {
        p = reinterpret_cast<float*>(
            (reinterpret_cast<uintptr_t>(raw.data()) + 63u) & ~static_cast<uintptr_t>(63u));
    }
};

// 19_minphase_golden.py の lcg_floats と同じ列。24 bit なので float で厳密。
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

// 摘み値 (グリッド添字 kKnobGridIdx 上の頂点) から 401 点曲線を組む。
// 添字空間の線形補間 = 対数 f・線形 dB (グリッドが対数等間隔なので)。
// 19_minphase_golden.py の curve_from_knobs と同じ式。
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

// 最良値 (最小) を採る。ベンチはばらつきの下限が実力。
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

// --------------------------------------------------------------------------
// 19. FFT ラッパ (vendored PFFFT rev 09796885cd5b)
// --------------------------------------------------------------------------

void checkFftWrapper(Report& r) {
    r.section("19. FFT ラッパ (vendored PFFFT、golden は numpy)");

    // ホストの前提の記録: x86-64 (SSE) で SIMD_SZ = 4。実機 (NEON) も 4。
    // スカラビルドに落ちると 1 になるが、合法判定は自前の述語なので挙動は変わらない。
    r.check(pffft_simd_size() == 4, "SIMD が有効 (pffft_simd_size = %d)", pffft_simd_size());

    {   // 合法判定は自前の述語。「32 の倍数かつ 2^a·3^b·5^c」
        const int valid[] = {32, 96, 160, 480, 960, 1024, 1920, 2048, 4096, 32768, 65536};
        const int invalid[] = {0, -32, 31, 48, 62, 224, 896, 1000, (1 << 26) + 32};
        bool ok = true;
        for (int n : valid) ok = ok && caeq::fftSizeValid(n);
        r.check(ok, "合法サイズが通る (32/96/.../1920/65536)");
        ok = true;
        for (int n : invalid) ok = ok && !caeq::fftSizeValid(n);
        r.check(ok, "不正サイズが弾かれる (0/-32/31/48/62/224/896/1000/2^26+32)");
        // 48 = 2^4·3 は 5-smooth だが 32 の倍数でない — SIMD 幅の制約が述語に入っている
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

    // 順序付き前進変換の golden (numpy rfft)。並びは [X0, XN/2, ReX1, ImX1, ...]
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

    {   // 往復 (正規化は 1/n を 1 回)
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

    {   // in == out の別名。pffft.h の宣言 (input and output may alias) の実測
        const int n = 1920;
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> x = lcgFloats(22, n);
        AlignedBuf a(n), b(n), w(n);
        std::memcpy(a.p, x.data(), sizeof(float) * n);
        std::memcpy(b.p, x.data(), sizeof(float) * n);
        plan.forwardOrdered(a.p, a.p, w.p);  // in-place
        AlignedBuf c(n);
        plan.forwardOrdered(b.p, c.p, w.p);  // out-of-place
        r.check(std::memcmp(a.p, c.p, sizeof(float) * n) == 0,
                "順序付き: in-place と out-of-place がビット同一");
        std::memcpy(a.p, x.data(), sizeof(float) * n);
        plan.forward(a.p, a.p, w.p);
        plan.forward(b.p, c.p, w.p);
        r.check(std::memcmp(a.p, c.p, sizeof(float) * n) == 0,
                "順序なし: in-place と out-of-place がビット同一");
    }

    {   // malloc 版 setup と in-place 版が全合法サイズでビット同一の出力
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

    {   // 順序なし + zconvolve = 順序付きの複素積 = 直接の巡回畳み込み
        const int n = 96;  // 直接畳み込みは O(n^2) なので小さい合法サイズで
        caeq::FftPlan plan;
        plan.init(n);
        std::vector<float> xa = lcgFloats(31, n), xb = lcgFloats(32, n);
        AlignedBuf fa(n), fb(n), acc(n), w(n), oa(n), ob(n), prod(n);
        std::memcpy(fa.p, xa.data(), sizeof(float) * n);
        std::memcpy(fb.p, xb.data(), sizeof(float) * n);

        // 経路 A: 順序なし + zconvolve (scaling = 1/n) + 逆変換
        plan.forward(fa.p, fa.p, w.p);
        plan.forward(fb.p, fb.p, w.p);
        std::memset(acc.p, 0, sizeof(float) * n);
        plan.convolveAccumulate(fa.p, fb.p, acc.p, 1.0f / static_cast<float>(n));
        plan.inverse(acc.p, acc.p, w.p);

        // 経路 B: 順序付きの複素積 (packed の先頭 2 本は実数として掛ける)
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

        // 経路 C: 直接の巡回畳み込み (double)
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

    {   // 同じ setup を並行に読む (rev 09796885 のソース確認の実測面)
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

// --------------------------------------------------------------------------
// 20. 目標曲線のグリッド
// --------------------------------------------------------------------------

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

    {   // 折れ線の評価
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

        // 隣接 2 点の中間 (対数で) は算術平均になる (線形 dB)
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

    {   // 検査
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

    {   // DUNU 摘み → 折れ線の再構成が Python と一致 (曲線の定義が 2 箇所にならない見張り)
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

// --------------------------------------------------------------------------
// 21. 最小位相 IR (golden は numpy float64)
// --------------------------------------------------------------------------

// 設計器を一括で回す道具。budget_ns を小さくすると刻みが増える。
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

    // win/taper の既定は製品の既定 (FirDesignSpec と同じ)。golden は kHalfHann を明示する。
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
            if (steps > 20000000) return false;  // 進まないバグの脱出口
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

        // ブロックは 960 (端数分割の代表)。IR は block に依存しない —
        // block が効くのは分割スペクトル化だけ。窓は golden (numpy) と同じ half-Hann。
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
            // 直接 DFT (double の回転漸化式)
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

    {   // 刻み方に出力が依存しない (決定性)。一括 vs 60 µs 刻み vs 3 µs 刻み
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

    {   // 不正入力は start() が弾く
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
        bad.m = 12800;  // 8192·1.5625 — 2^a·5^c だが m < 2·taps
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p), "m < 2·taps を弾く");
        bad = s;
        bad.block = 448;
        r.check(!try_start(bad, fb.data.p, fb.work.p, fb.filt.p), "不正な P (448) を弾く");
        {   // 2P = taps ちょうどは通る (境界)。P=4096 の plan が要る
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

// --------------------------------------------------------------------------
// 22. 実現誤差のゲート (製品の折れ線目標に対して)
// --------------------------------------------------------------------------

// h の応答を対数 2000 点で直接 DFT 評価し、401 点グリッドの折れ線補間
// (= .so が見るのと同じ規則の目標) との差の最大を返す。係数和は double。
// worst_hi40 には 40 Hz 以上に限った最大を返す — 最低域 (20〜32 Hz) は摘み間隔が
// 5〜6 Hz で IR 170 ms の分解能 (≈5.9 Hz) と同じ桁なので、そこだけ物理の床が違う。
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
    // 分解能の床に当たる形 (隣接摘みの段差が最低域の分解能より狭い) か。
    // 「不合格」ではなく、0.2 dB 級を名乗れない領域として別集計する。
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

    // (d) 滑らかな乱数: 節を 5 摘みごと (≈1.15 oct) に置いて間を折れ線で埋める。
    // 実在のイヤホン補正はこの緩さ (DUNU の隣接摘み差は最大 3 dB 級)。
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

    // (d') 摘みごと独立の乱数 = ギザギザ。最低域では (b) と同じく床に当たる
    {
        std::vector<float> u = lcgFloats(104, 31);
        for (int i = 0; i < 31; i++) {
            k[static_cast<size_t>(i)] = static_cast<double>(u[static_cast<size_t>(i)]) * 10.0;
        }
        cs.push_back({"(d') ギザギザ乱数", curveFromKnobs(k.data()), true});
    }

    // (e) 端の摘み 1 本だけ +12。20 Hz 側は幅 5 Hz の三角 (分解能未満)、
    // 20 kHz 側は端の保持で Nyquist まで平ら (44.1k では 0.91·Nyquist)
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

    // --- 本表: fs × 曲線 (M・窓は製品の既定) ----------------------------------
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
    // ゲート: 実用曲線 (実在系の滑らかさ) で ±0.2 dB 級を名乗れるか。実測の答え:
    //   40 Hz 以上は < 0.13 dB (実在の DUNU は < 0.03)、最下端 20〜40 Hz にだけ
    //   0.1〜0.25 dB の床が残る (IR ≈170 ms の分解能 5.9 Hz と摘み間隔 5〜6 Hz が同じ桁)。
    // 閾値は実測に余裕を載せた回帰の釘 (44.1k 0.2441 / 48k 0.1724 / hi40 0.129 が実測)。
    {
        const double wp =
            std::fmax(worst_practical[0], std::fmax(worst_practical[1], worst_practical[2]));
        const double w40 = std::fmax(worst_hi40_all[0] > worst_hi40_all[1]
                                         ? worst_hi40_all[0]
                                         : worst_hi40_all[1],
                                     worst_hi40_all[2]);
        (void)w40;
        double hi40_practical = 0.0;
        // 実用曲線だけの 40 Hz 以上を集計し直す (本表の値から)
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

    // --- M の選定 (誤差 vs M。窓は既定) ---------------------------------------
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
    {   // 既定 M (= 2·taps) が M=65536 と実用曲線で同等であることの釘
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

    // --- 窓の比較 (製品の既定を決めた根拠。ca_eq_fir.h のコメントと対) ----------
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

// --------------------------------------------------------------------------
// 23. 一様分割畳み込み (FirKernel)
// --------------------------------------------------------------------------

// 任意の h からフィルタ面を手で組む (designer を経由しない — 検査対象を分ける)。
// 1/(2P) をタップに焼くのは designer と同じ規約。
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

// kernel 一式のバッファ持ち。
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

    // インパルスを通すと設計 h がそのまま出る。h[0] が同じブロックの先頭に出る = 遅延 0。
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
        // インパルスを 2 発: ブロック先頭 (t=0) と末尾 (t=p-1)。先頭は「h[0] が同じ
        // ブロックの先頭に出る = 遅延 0」の証明、末尾は h がブロック境界を越えて
        // 尻尾 (OLA) 経路を通ることの証明 — 先頭のインパルスはセグメント畳み込みの
        // 後半がゼロになり、尻尾を捨てても通ってしまう (壊し実験で確認した穴)。
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

    {   // 直接畳み込み (double) との突き合わせ。任意の h、端数分割、乱数入力
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

    {   // fill カウンタ: reset 後は「履歴ゼロ」と同じ = 新品と同じ出力
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
        a.k.reset();  // FDL は memset しない — fill カウンタだけで無効化
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

    {   // 面クロスフェード。定常の混合は (1-w)·A + w·B と厳密に一致し (尻尾も同じ重みで
        // 混ざる)、ランプ中は 1 ブロックだけ「尻尾が前の重みを引きずる」— 前のブロックで
        // 書いた尻尾は書いた時点の外挿重みで混ざっている。その 1 ブロック分の遅れが
        // フェードの形をなだらかにするだけで、境界の連続性は保たれる (クリックは 24 節で
        // 実測 -60 dBFS 台)。ここでは端点の厳密さと定常混合の厳密さを見る。
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
        const float dw      = 1.0f / 480.0f;  // 10 ms @48k
        float w0            = 0.0f;
        double worst_half = 0.0, worst_end = 0.0, scale = 0.0, peak_mid = 0.0;
        bool   pre_same = true;
        int    fade_done_at = -1;
        for (int blk = 0; blk < nblk; blk++) {
            const float* xin = x.data() + static_cast<size_t>(blk) * p;
            ra.k.processBlock(xin, oa.data(), 0, -1, 0.0f, 0.0f);
            rb.k.processBlock(xin, ob.data(), 0, -1, 0.0f, 0.0f);
            // 定常の半々混合 (w0=0.5, dw=0)。1 ブロック目の尻尾が落ち着いたら厳密
            half.k.processBlock(xin, oh.data(), 0, 1, 0.5f, 0.0f);
            for (int i = 0; i < p && blk >= 1; i++) {
                const double want = 0.5 * (static_cast<double>(oa[static_cast<size_t>(i)]) +
                                           static_cast<double>(ob[static_cast<size_t>(i)]));
                scale      = std::fmax(scale, std::fabs(want));
                worst_half = std::fmax(worst_half,
                                       std::fabs(static_cast<double>(oh[static_cast<size_t>(i)]) - want));
            }
            // ランプするフェード: 前は A とビット同一、完了 +1 ブロックで B と一致
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

    {   // in-place / ステレオの独立 / NaN の scrub
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
            st.k.processBlock(inter.data(), inter.data(), 0, -1, 0.0f, 0.0f);  // in-place
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

    {   // push だけで温めてから 1 ブロック捨てると、以後は連続運転と同一
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
                warm.k.pushBlock(xin);  // 入力側だけ回す (FDL 温め)
            } else if (blk == 3) {
                warm.k.processBlock(xin, ow.data(), 0, -1, 0.0f, 0.0f);  // 尻尾温め (捨てる)
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

    {   // 不正な bind
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

// --------------------------------------------------------------------------
// 24. EqPipeline (遷移・落下・診断)
// --------------------------------------------------------------------------

uint64_t testClock() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

// ステレオのブロックを流す。戻りは「kFadeIn 以降に fill < K の瞬間があったか」。
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
        // AND 条件の見張り: 出力へ混ざる状態 (kFadeIn/kFir) に fill < K で入っていないか。
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

    {   // 基本の遷移: biquad → 準備 → (designer 完了 ∧ fill=K ∧ 尻尾温め) → フェード → FIR
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

        // E2E: インパルス (L だけ) → 設計 h がそのまま出る。R は無音のまま。
        // FDL に残っている準備中の雑音を流し切ってから測る (K=9 + 尻尾 + 余裕)。
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

        // 曲線の差し替え: 裏の面に組んで面フェード。biquad へ落ちない
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
        for (int b = 0; b < 12; b++) drv.silentBlock();  // 雑音の残りを流し切る
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

        // 検査に落ちる曲線は棄却して前のまま
        std::vector<float> bad = dunu;
        bad[100] = std::nanf("");
        pl.setCurve(bad.data(), 3);
        for (int b = 0; b < 4; b++) drv.noiseBlock();
        r.check(pl.curveRejected() == 1 &&
                    pl.firState() == caeq::EqPipeline::FirState::kFir &&
                    pl.curveGeneration() == 2,
                "NaN 曲線は棄却して前の曲線のまま鳴り続ける (rejected=%u)",
                pl.curveRejected());

        // accumulate: FIR 稼働中の out += が上書きしないこと。無音を FDL の深さ (K=9)
        // より長く流して履歴を流し切る — FIR 出力が厳密に 0 になり、+= 0 で sentinel が
        // 保存される。K 未満で見ると古い雑音が尻尾と FDL から漏れて偽陽性になる。
        for (int b = 0; b < 12; b++) drv.silentBlock();
        std::fill(drv.in.begin(), drv.in.end(), 0.0f);
        std::fill(drv.out.begin(), drv.out.end(), 0.25f);
        drv.step(true);  // accumulate
        bool acc_ok = true;
        for (float v : drv.out) {
            if (v != 0.25f) acc_ok = false;
        }
        r.check(acc_ok, "accumulate: 無音入力で out が保存される (上書きしていない)");

        // DISABLE → wet フェード → 素通し → idle → FDL 失効 (落下には数えない)
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

        // 再有効化 → 準備からやり直して FIR へ戻る
        pl.setActive(true);
        const int again = drv.runUntil(caeq::EqPipeline::FirState::kFir, 60);
        r.check(again > 0 && !drv.flying_start, "再有効化で FIR まで戻る (%d ブロック)",
                again);

        // P 変化 → 落下 (fallbacks++) → 新しい P で FIR へ戻る
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

    {   // 不適な P: 大きさで落ちる (448) / 予算で落ちる (96k の P=32)
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

    {   // ch > 2 では arena を作らない (12ch spatializer)
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

    {   // configure の冪等性: 同じ fs/ch では arena もカウンタも動かない
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 960, 2);
        const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 60);
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);  // SET_CONFIG は重複して来る
        drv.noiseBlock();
        r.check(reached > 0 && pl.firState() == caeq::EqPipeline::FirState::kFir,
                "同じ fs/ch の configure で FIR が生き残る (冪等)");
        pl.configure(44100.0, 2, caeq::Structure::kTdf2);  // fs 変更は全部やり直し
        drv.noiseBlock();
        r.check(pl.firState() != caeq::EqPipeline::FirState::kFir,
                "fs 変更で FIR 状態を捨てる (taps/M/FDL すべて fs 依存)");
    }

    {   // FIR なしの pipeline は素の Eq とビット同一 (壊していないことの証明)
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

    {   // wet フェードの意味論が Eq と同一 (フェード曲線の突き合わせ)。
        // Eq: preamp +12 dB / バンドなし → 出力 = dry + (3.981·dry − dry)·wet
        // pipeline: FIR 平ら +12 dB 曲線 / preamp 0 → 同じ式。フェード軌跡を直接比べる。
        //
        // ⚠️ **この試験の biquad はユニティ (band_count = 0) で回っている。**
        // 「FIR 側の wet が Eq の wet と同じ軌跡か」だけを見るのが目的で、biquad が
        // 鳴っていると比較対象に biquad の応答が混ざって式が成り立たないため。
        // **受け渡し (biquad が EQ を鳴らし直さないか) はこの構成では原理的に見えない** —
        // そちらは下の「意味のある biquad での DISABLE」と 28.1 が見る。
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
        eq.process(warm.data(), warm.data(), 960);  // フェードを 1 に到達させる

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

    {   // **意味のある biquad での DISABLE。**上の試験がユニティで回っている穴を埋める
        // (28.1 と同じ性質を、こちらは fs/P を変えた条件で張る)。DISABLE のフェードが
        // 終わった後は、biquad が鳴っていても出力が dry と厳密に一致すること。
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
            if (b == 0) continue;  // フェードそのもののブロック
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

    {   // **arena の区画を超えるブロック長。**検分でここが穴だった (kFir 中に
        // 上限超えのブロックが来ると、落下経路の biquad が arena の区画へ
        // frames·ch を書いて外へ出た)。いまは biquad だけの経路が out へ直接書くので
        // arena に触れない。上限の前後を跨いで釘を打つ。
        //
        // ⚠️ **この釘が破れたときの見え方は「FAIL の行」ではない。**
        // 区画外への書き込みは `process()` の中で heap を壊すので、**その先の
        // r.check() には到達しない。この節が途中で沈黙してハーネスごと落ちたら、
        // それがこの釘の FAIL** である (幽霊を追わないこと)。
        // 期待される署名: 素のビルドで 0xC0000374 (STATUS_HEAP_CORRUPTION)、
        // ASan ビルド (CA_EQ_ASAN_FIR=ON) なら heap-buffer-overflow として
        // **名前付きで報告される** — この節は ASan ターゲットにも入っている。
        // さらに正確な位置が要るときは検分の手口 —— arena をガードページ付きで
        // 確保し直した別バイナリ (scratchpad/attack、`attack.exe oob`) —— を使う。
        // あちらは逸脱の 1 バイト目で 0xC0000005 になる。
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
            // 鳴っている FIR に、上限を超えるブロックを叩き込む
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

    {   // モード OFF は対称なクロスフェードで降りる (落下ではない)。
        // **P=128 (2.7 ms) で回す** — 10 ms のフェードが 1 ブロックに収まると
        // 途中の状態を観測できず、「フェードしている」ことを試験できない。
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

    {   // 設計器の失敗 (検査済み曲線からは到達しない潜在経路をハーネスから撃つ)。
        // kPrepare に留まり続けない・落下に数えない・同じ曲線で回し続けない。
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.snapParams(flat);
        pl.setActive(true);
        pl.setFirEnabled(true);
        pl.setCurve(dunu.data(), 1);
        PipeDriver drv(pl, 960, 2);
        drv.noiseBlock();  // setup 構築 → designer 起動
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
        // 新しい世代が来たら再挑戦する
        pl.setCurve(alt.data(), 77);
        const int back = drv.runUntil(caeq::EqPipeline::FirState::kFir, 80);
        r.check(back > 0 && pl.rebuilds() > rb, "新しい曲線が来たら再挑戦して FIR に戻る");
    }

    {   // active_gen_ は「鳴っている世代」。準備中や面フェード中に先走らない。
        // ここも P=128 — 面フェード (10 ms) の途中を観測するため。
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
        // 面フェードが始まってから、完了する前に読む
        uint32_t during_face = 0;
        for (int b = 0; b < 40; b++) {
            drv.noiseBlock();
            during_face = pl.curveGeneration();
            if (pl.faceFades() > 0) break;   // 完了したら抜ける (読むのは完了前の値)
            if (b >= 1) break;               // 設計は 1〜2 ブロックで終わる
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

    {   // **フェード長を変えてもクロスフェードの和が 1 のままか。**
        //
        // 乗り移りは「Eq の wet が降りる + fir_mix が昇る」の和で出来ているので、
        // 両者の長さが食い違うと和が 1 でなくなり、**両エンジンが同じ応答でも**
        // 途中で音量が動く。pipeline がフェード長を自前で持っていると
        // `biquad().setFadeMillis()` で食い違いが作れてしまう (いまは Eq から毎回引く)。
        //
        // 観測できる形にするのが要点: **両エンジンを厳密に同じ伝達関数にする。**
        //   biquad = バンド無し + preamp +12 dB → 平ら 3.981 倍
        //   FIR    = 0 dB 平らの曲線 (IR は単位インパルス) → pre_cur_ だけで 3.981 倍
        // 正しければ乗り移りの全区間で出力が dry×3.981 から動かない。
        // ここをユニティ biquad で組むと 24 節と同じ罠 (差が出ない構成) にはまる。
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            // **configure の後で**変える。前に呼ぶと、フェード長を configure 時に
            // 1 回だけ確定する実装でも通ってしまい、釘が弱くなる。
            pl.biquad().setFadeMillis(fade_ms);
            caeq::Params p12;
            p12.band_count = 0;
            p12.preamp_db  = 12.0;
            pl.snapParams(p12);
            pl.setActive(true);

            PipeDriver drv(pl, 128, 1);
            for (int b = 0; b < 60; b++) drv.noiseBlock();  // Eq の wet を 1 へ

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

    {   // **降りる向き (kFadeOut) の釣り合い。**
        //
        // いまの実装は昇りと降りで同じ式 (`d = ±fadeStep()`) を使うので、上の釘が
        // 落ちれば降りも落ちる = 結合の確認としては 1 本で足りる。それでもここを
        // 別に測るのは 2 つ理由がある:
        //   1. **kFadeOut が厳密なクロスフェードであること自体、これまで測っていない。**
        //      モード OFF のクリック (−60.8 dBFS) は両エンジンがわざと違う構成での
        //      測定なので、「和が 1 か」までは言えていなかった (推測のままだった)
        //   2. 昇りと降りで式を分ける改修が入ったとき、降り側が無防備になる
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        caeq::Params p12;
        p12.band_count = 0;
        p12.preamp_db  = 12.0;
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            pl.biquad().setFadeMillis(fade_ms);   // configure の後で (上の説明)
            pl.snapParams(p12);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(flat0.data(), 1);
            PipeDriver drv(pl, 128, 1);
            drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);

            pl.setFirEnabled(false);   // → kFadeOut (FIR は健在なので対称に降りる)
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

    {   // **同じ結合の、もう 1 本の釘。上の釘とは互いに相手を見られない。**
        //
        //   上 (乗り移り)   : kFadeIn の mix の傾きを測る。あの時点で fir_wet は既に
        //                     1.0 まで上がりきっているので、**wet の歩幅のずれには無反応**
        //   ここ (DISABLE)  : fir_wet の歩幅を測る。kFir にいるので mix は 1.0 で固定、
        //                     **mix の傾きのずれには無反応**
        //
        // つまり片方だけ直しても、もう片方の釘しか落ちない。フェード長を pipeline 側に
        // 焼くと (configure で 1 回だけ確定する形) ここが落ちる — **だから
        // setFadeMillis は configure の後で呼ぶ。**前に呼ぶと焼いた実装でも通ってしまう。
        //
        // 測り方: 両エンジンを厳密に同じ応答にすると
        //   pipeline = dry·(1 + 2.981·fir_wet) / 素の Eq = dry·(1 + 2.981·eq_wet)
        // なので、差はそのまま (fir_wet − eq_wet) に比例する。
        const double kGain = std::pow(10.0, 12.0 / 20.0);
        std::vector<float> flat0(caeq::kCurvePoints, 0.0f);
        caeq::Params p12;
        p12.band_count = 0;
        p12.preamp_db  = 12.0;
        double worst_all = 0.0;
        for (double fade_ms : {5.0, 10.0, 20.0, 50.0}) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            pl.biquad().setFadeMillis(fade_ms);   // **configure の後で** (上の説明)
            pl.snapParams(p12);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(flat0.data(), 1);
            PipeDriver drv(pl, 128, 1);
            const int reached = drv.runUntil(caeq::EqPipeline::FirState::kFir, 400);

            // 参照: 素の Eq を同じ設定・同じフェード長で
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

    {   // クリックの実測 (記録)。tones を流し、イベント後を 3 kHz HP で見る
        auto clickDb = [&](auto&& trigger, const char* label) {
            caeq::EqPipeline pl;
            pl.configure(48000.0, 1, caeq::Structure::kTdf2);
            // biquad 側は DUNU の摘みを 31 バンド Q=4.32 の peaking にした近似
            // (製品の対応関係と同じ) — 落下のクリックは両エンジンの差そのもの
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
            // イベント (fir_at+20 ブロック) の前後 40 ms を高域通過して残差を見る
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
                pl.process(one.data(), one.data(), 512, false);  // P 変化 → 落下
            },
            "落下 (P 変化 → biquad 立ち上げ)");
        // biquad→FIR のフェードインは開始 20 ブロックに含まれている。別途:
        const double c3 = clickDb([&](caeq::EqPipeline&) {}, "定常 (イベントなし、床)");
        // 落下 (P 変化) は「FIR の音 → dry → biquad 立ち上げ」の乗り換えで、両エンジンの
        // 差がそのまま出る。DUNU 級の曲線で -25 dBFS 前後 — まれな事象 (経路の再構成) の
        // 費用として記録する。設計の代替 (biquad 常時並走) は CPU と引き換えで不採用
        // (eq-fir-design.md §3)。
        r.check(c1 < -55.0 && c2 < -20.0,
                "クリックの記録 (面フェード %.1f / 落下 %.1f / 床 %.1f dBFS)", c1, c2, c3);
    }
}

// --------------------------------------------------------------------------
// 25. pffft の所要時間 (スライス予算の根拠)
// --------------------------------------------------------------------------

void checkFftTiming(Report& r) {
    r.section("25. pffft の所要時間 (ホスト x86-64。実機は段 4 で測り直す)");

    r.note("合法 2P 系列 (P = ブロック長、n = 2P の実 FFT 1 回):");
    for (int p = 16; p <= 4096; p += 16) {
        if (!caeq::convBlockSizeValid(p)) continue;
        const int n = 2 * p;
        // 表は代表点だけ出す (全部出すと 50 行になる)
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

    // 予算との突き合わせ (48 kHz)。最大の不可分クォンタムは M の FFT 1 回。
    r.note("スライス予算 (kSliceBudgetFrac = %.2f) と最大クォンタム (M=32768 の実測):",
           caeq::kSliceBudgetFrac);
    for (int p : {128, 512, 960, 1024, 2048}) {
        const double budget_us =
            caeq::kSliceBudgetFrac * static_cast<double>(p) / 48000.0 * 1e6;
        r.note("  P=%-5d 予算 %7.1f µs → M FFT %.1f µs は%s", p, budget_us,
               m32768_ord / 1000.0, budget_us > m32768_ord / 1000.0 ? "収まる" : "収まらない");
    }

    // 費用モデル (fircost) がホストで上界になっていることの会計。
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
        // **check ではなく note。**これは環境依存の較正値で、計装ビルドや遅いマシンでは
        // 正しいコードでも破れる (ASan で実際に破れた)。会計の正しさは 26 節が
        // 決定的な量 (モデル消費) で見ているので、ここは較正のずれを人が読むための値。
        r.note("  費用モデル fircost::fftNs はホスト実測の%s (計装ビルドでは破れてよい)",
               conservative ? "上界" : "**下**回り — 較正がずれている");
    }
}

// --------------------------------------------------------------------------
// 26. スライス予算の会計と定常性能
// --------------------------------------------------------------------------

void checkAccounting(Report& r) {
    r.section("26. スライス予算の会計と定常性能 (数字はすべてホスト x86 実測)");

    std::vector<float> dunu = curveFromKnobs(cagold::kDunuKnobDb);

    // --- 1. 会計 (決定的・環境非依存) ---------------------------------------
    //
    // **壁時計に対する assert は置かない。**正しいコードでも計装ビルド (ASan) や
    // 負荷で落ち、逆に速いマシンならモデルが狂っていても通る = 両方向に外れる。
    // 見張るのは「コードが実際に制御している量」= 費用モデル上の消費。
    //   モデル消費 ≤ 予算 + 不可分クォンタム 1 個
    // が step() の契約 (予算を使い切っていても、始めた不可分工程は必ず終える)。
    r.note("設計器の会計 (モデル上の消費。決定的な値):");
    bool model_ok = true;
    struct AcctCase { double fs; int taps; int m; };
    const AcctCase accts[] = {{48000.0, 8192, caeq::firDefaultM(48000.0)},
                              {96000.0, 16384, caeq::firDefaultM(96000.0)}};
    for (const AcctCase& ac : accts) {
        for (int p : {128, 512, 960, 1024, 2048}) {
            const int64_t budget =
                static_cast<int64_t>(caeq::kSliceBudgetFrac * p / ac.fs * 1e9);
            // 最大の不可分クォンタム: M の FFT か、分割 1 つ (2P の FFT + 詰め替え)
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

    // --- 2. 実行時の可否判定を釘付け (時計を使わない) -------------------------
    //
    // 「最大の不可分クォンタムが予算に収まるか」は evaluateBlock が実行時に判定して
    // いるので、そこを直接叩く。時計ではなくカウンタ (unfitBudget) で見る。
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
        // 48k: 合法 P の下限付近を掃いて境界を出す
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
        // 実測ブロック長 (512/960/1024/2048) は全部この下限より上にいること。
        r.check(lowest_ok_48 > 0 && lowest_ok_48 <= 512 && lowest_ok_96 > 0 &&
                    lowest_ok_96 <= 512,
                "実測で来るブロック長 (512 以上) はすべて予算内 — 最大クォンタムが収まる");
        r.check(!budgetFit(96000.0, 32) && budgetFit(96000.0, 512),
                "小さすぎる P は予算で弾き、実用域は通す (判定が実際に効いている)");
    }

    // --- 3. 壁時計は参考値 (note) + 桁違いの退行だけ拾う緩い天井 ---------------
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
    // 天井は桁違いの退行だけを拾う値。計装ビルドの 2〜10 倍では鳴らない。
    r.check(worst_ratio < 20.0,
            "壁時計が予算の 20 倍を超えない (桁違いの退行だけを拾う緩い天井。実測 %.2fx)",
            worst_ratio);

    // 畳み込みの定常 ns/frame (2ch)。これが「聴いているあいだずっと」の値。
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
        // 定常化 (fill = K) してから測る
        for (int b = 0; b < caeq::firPartitions(8192, p) + 2; b++) {
            rig.k.processBlock(io.data(), io.data(), 0, -1, 0.0f, 0.0f);
        }
        const double ns = benchNs(
            [&] { rig.k.processBlock(io.data(), io.data(), 0, -1, 0.0f, 0.0f); }, 50);
        r.note("  P=%-5d K=%-3d %7.1f ns/frame (= %.2f%% @48k 1 コア)  面フェード中はほぼ 2 倍",
               p, caeq::firPartitions(8192, p), ns / p, ns / p * 48000.0 / 1e7);
    }

    {   // pipeline 定常 (kFir、混合込み) の ns/frame
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

// --------------------------------------------------------------------------
// 27. ファズ (乱数曲線と乱れた運転)
// --------------------------------------------------------------------------

void checkFuzz(Report& r) {
    r.section("27. ファズ");

    {   // 乱数の有効曲線 → 常に有限な IR と応答
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

    {   // 乱数の不正曲線 → 全部検査で落ちる
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

    {   // pipeline の乱れた運転: 有効/無効・曲線・P・reset をランダムに 400 ブロック
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
                // 4 本に 1 本は汚す。全部を汚すと有効な曲線が採用されず FIR が一度も
                // 立ち上がらない (= ファズが興味のある状態を素通りする) ので、大半は有効。
                if ((b % 4) == 3) c[rnd() % 401] = std::nanf("");
                pl.setCurve(c.data(), ++gen);
            }
            const int p = ps[(b / 25) % 4];  // 数十ブロックごとに P が化ける
            std::vector<float> x = lcgFloats(6000 + static_cast<uint64_t>(b), 2 * p);
            if ((rnd() % 16) == 0) x[rnd() % static_cast<uint32_t>(2 * p)] = std::nanf("");
            // 見張る性質: 「入力が非有限だった位置以外は必ず有限」。idle の Eq は
            // ビット厳密な素通し (仕様) なので、注入した NaN 自体はそのまま出てよい。
            // NaN が処理経路に入って**増殖しない**ことを見ている。
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
        // ファズが興味のある状態を実際に通ったことの見張り。ここが 0 のままだと
        // 「何も起きない運転」を眺めて通過したことになる (最初にそれをやった)。
        r.check(pl.rebuilds() >= 2 && pl.fallbacks() >= 1 && pl.unfitSizeCount() >= 1 &&
                    pl.curveRejected() >= 1,
                "ファズが FIR の稼働と落下を実際に通った (rebuilds=%u fallbacks=%u "
                "unfitSize=%u rejected=%u scrubbed=%u)",
                pl.rebuilds(), pl.fallbacks(), pl.unfitSizeCount(), pl.curveRejected(),
                pl.scrubbedSamples());
    }
}

// --------------------------------------------------------------------------
// 29. 整列の不変条件 (pffft の assert に到達しないこと)
// --------------------------------------------------------------------------

void checkAlignment(Report& r) {
    r.section("29. 整列の不変条件 (pffft は process 経路でも assert で見る)");

    r.note("PFFFT は pffft_transform_internal / pffft_zconvolve_accumulate でも");
    r.note("  assert(VALIGNED(...)) を持つ。**この製品は NDEBUG を定義しない**ので、");
    r.note("  整列が崩れると audio HAL が abort() = 端末全体が無音。到達しないことを");
    r.note("  こちら側の不変条件で保証する (dsp/ca_eq_fft.h の「整列の不変条件」)。");

    {   // 1. 合法サイズなら要素数の倍数のずれが必ず 16 B 整列を保つ
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

    {   // 2. 実際に走っている kernel / designer の派生ポインタを全部見る
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
        // designer が詰め替えに使う data 末尾
        if (!caeq::fftAligned(fb.data.p + (m - n))) aligned = false;
        derived++;
        r.check(aligned, "稼働中の派生ポインタ %d 本すべてが 16 B 整列 (基底 + k·n + m−n)",
                derived);
    }

    {   // 3. 崩れていたら「使わない」— 落ちるのではなく biquad へ落ちること
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
            *slots[i] = orig[i] + 1;  // 4 バイトずらす = 整列が崩れる
            caeq::FirKernel k;
            if (!k.bind(&rig.plan, p, taps, 1, b)) refused++;
            *slots[i] = orig[i];
        }
        r.check(refused == 7,
                "FFT に渡す 7 本のどれが崩れても bind が断る (%d/7) — assert へ行かせない",
                refused);

        // designer 側も同じ (基底 3 本)
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

    {   // 4. 崩れた状態で FIR を要求しても、音は biquad で鳴り続ける
        //    (arena は pipeline が自前で 64 B 整列に切るので、ここは kernel/designer の
        //     契約が守られていることの確認 = 上の 3 が担保)。
        r.note("pipeline の arena は 64 B 整列で切り出す (reserveArena の align64)。");
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        r.check(pl.firAvailable() && caeq::fftAligned(pl.arenaBaseForTest()),
                "arena の基底が 16 B 整列 (実測)");
    }
}

}  // namespace

// ca_eq_test.cpp の main から呼ばれる入口。**節は番号順に印字する** —
// 29 (整列) は検分の 28 節より後なので、あちらを挟んでから別の入口で呼ぶ。
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

// 29 節。main が 28 節 (ca_eq_fir_gate_test.cpp) の後に呼ぶ。
void runFirAlignmentSection(Report& r) { checkAlignment(r); }
