// 「高精度」(最小位相 FIR) 経路のハーネス。ca_eq_test.cpp の main から呼ばれる。
//
// 参照は llmdocs/tools/eq/18_minphase_golden.py が生成した ca_eq_fir_golden.h
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

// 18_minphase_golden.py の lcg_floats と同じ列。24 bit なので float で厳密。
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
// 18_minphase_golden.py の curve_from_knobs と同じ式。
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
        r.check(conservative,
                "費用モデル fircost::fftNs がホスト実測の上界 (予算の会計が安全側)");
    }
}

}  // namespace

// ca_eq_test.cpp の main から呼ばれる入口。
void runFirSections(Report& r) {
    checkFftWrapper(r);
    checkCurve(r);
    checkFftTiming(r);
}
