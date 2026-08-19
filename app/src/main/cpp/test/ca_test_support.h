#ifndef CA_TEST_SUPPORT_H_
#define CA_TEST_SUPPORT_H_

#include <cmath>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "../dsp/ca_eq_dsp.h"

namespace catest {

constexpr double kPi = 3.14159265358979323846;

inline double dbOf(double x) { return 20.0 * std::log10(std::fabs(x) + 1e-300); }

class Rng {
public:
    explicit Rng(uint64_t seed) {
        s_[0] = seed ? seed : 0x9E3779B97F4A7C15ull;
        s_[1] = s_[0] ^ 0xBF58476D1CE4E5B9ull;
        for (int i = 0; i < 16; i++) next();
    }
    uint64_t next() {
        uint64_t x = s_[0];
        const uint64_t y = s_[1];
        s_[0] = y;
        x ^= x << 23;
        s_[1] = x ^ y ^ (x >> 17) ^ (y >> 26);
        return s_[1] + y;
    }
    double uniform() {
        return static_cast<double>(next() >> 11) * (2.0 / 9007199254740992.0) - 1.0;
    }

private:
    uint64_t s_[2];
};

inline std::vector<float> makeImpulse(size_t n, float amp = 1.0f) {
    std::vector<float> x(n, 0.0f);
    if (n) x[0] = amp;
    return x;
}

inline std::vector<float> makeLogSweep(size_t n, double fs, double f0, double f1, double amp) {
    std::vector<float> x(n);
    const double t_end = static_cast<double>(n) / fs;
    const double k = std::log(f1 / f0);
    for (size_t i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / fs;
        const double phase = 2.0 * kPi * f0 * t_end / k * (std::exp(k * t / t_end) - 1.0);
        x[i] = static_cast<float>(amp * std::sin(phase));
    }
    return x;
}

inline std::vector<float> makeNoise(size_t n, double amp, uint64_t seed) {
    Rng rng(seed);
    std::vector<float> x(n);
    for (size_t i = 0; i < n; i++) x[i] = static_cast<float>(amp * rng.uniform());
    return x;
}

inline std::vector<float> makeSilence(size_t n) { return std::vector<float>(n, 0.0f); }

inline std::vector<float> makeTones(size_t n, double fs) {
    std::vector<float> x(n);
    for (size_t i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / fs;
        x[i] = static_cast<float>(0.06 * std::cos(2.0 * kPi * 100.0 * t) +
                                  0.04 * std::cos(2.0 * kPi * 300.0 * t));
    }
    return x;
}

struct Sos {
    double b0, b1, b2, a1, a2;
};

inline std::vector<double> sosFilt(const std::vector<Sos>& secs, const std::vector<double>& x) {
    std::vector<double> y = x;
    for (const Sos& s : secs) {
        double s1 = 0.0, s2 = 0.0;
        for (size_t n = 0; n < y.size(); n++) {
            const double xn = y[n];
            const double yn = s.b0 * xn + s1;
            s1 = s.b1 * xn - s.a1 * yn + s2;
            s2 = s.b2 * xn - s.a2 * yn;
            y[n] = yn;
        }
    }
    return y;
}

inline std::vector<Sos> butterworthHighpass(int order, double fc, double fs) {
    std::vector<Sos> secs;
    const double w0 = 2.0 * kPi * fc / fs;
    const double cw = std::cos(w0), sw = std::sin(w0);
    for (int k = 0; k < order / 2; k++) {
        const double theta = kPi * (2.0 * k + order + 1.0) / (2.0 * order);
        const double q = -1.0 / (2.0 * std::cos(theta));
        const double al = sw / (2.0 * q);
        const double b0 = (1.0 + cw) / 2.0;
        const double b1 = -(1.0 + cw);
        const double b2 = (1.0 + cw) / 2.0;
        const double a0 = 1.0 + al;
        const double a1 = -2.0 * cw;
        const double a2 = 1.0 - al;
        secs.push_back(Sos{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0});
    }
    return secs;
}

class Fft {
public:
    explicit Fft(size_t n) : n_(n), cos_(n / 2), sin_(n / 2) {
        for (size_t k = 0; k < n / 2; k++) {
            const double a = -2.0 * kPi * static_cast<double>(k) / static_cast<double>(n);
            cos_[k] = std::cos(a);
            sin_[k] = std::sin(a);
        }
    }

    void forward(std::vector<double>& re, std::vector<double>& im) const {
        const size_t n = n_;
        for (size_t i = 1, j = 0; i < n; i++) {
            size_t bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (size_t len = 2; len <= n; len <<= 1) {
            const size_t half = len >> 1;
            const size_t step = n / len;
            for (size_t i = 0; i < n; i += len) {
                for (size_t k = 0; k < half; k++) {
                    const double wr = cos_[k * step];
                    const double wi = sin_[k * step];
                    const size_t a = i + k, b = i + k + half;
                    const double vr = re[b] * wr - im[b] * wi;
                    const double vi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - vr;
                    im[b] = im[a] - vi;
                    re[a] += vr;
                    im[a] += vi;
                }
            }
        }
    }

    size_t size() const { return n_; }

private:
    size_t n_;
    std::vector<double> cos_, sin_;
};

inline double biquadDb(const caeq::Coef& k, double f, double fs) {
    const double w = 2.0 * kPi * f / fs;
    const double zr = std::cos(-w), zi = std::sin(-w);
    const double z2r = zr * zr - zi * zi, z2i = 2.0 * zr * zi;
    const double nr = k.c[0] + k.c[1] * zr + k.c[2] * z2r;
    const double ni = k.c[1] * zi + k.c[2] * z2i;
    const double dr = 1.0 + k.c[3] * zr + k.c[4] * z2r;
    const double di = k.c[3] * zi + k.c[4] * z2i;
    const double num = std::sqrt(nr * nr + ni * ni);
    const double den = std::sqrt(dr * dr + di * di);
    return 20.0 * std::log10(num / den);
}

inline double cascadeDb(const caeq::Params& p, double f, double fs) {
    double acc = p.preamp_db;
    for (int i = 0; i < p.band_count; i++) {
        acc += biquadDb(caeq::designTdf2(p.bands[i], fs), f, fs);
    }
    return acc;
}

inline double rms(const std::vector<double>& x, size_t from = 0) {
    double acc = 0.0;
    size_t n = 0;
    for (size_t i = from; i < x.size(); i++) {
        acc += x[i] * x[i];
        n++;
    }
    return n ? std::sqrt(acc / static_cast<double>(n)) : 0.0;
}

inline double peakAbs(const std::vector<double>& x, size_t from, size_t to) {
    double m = 0.0;
    for (size_t i = from; i < to && i < x.size(); i++) {
        const double a = std::fabs(x[i]);
        if (a > m) m = a;
    }
    return m;
}

inline std::vector<double> toDouble(const std::vector<float>& x) {
    return std::vector<double>(x.begin(), x.end());
}

inline bool allFinite(const std::vector<float>& x) {
    for (float v : x) {
        if (!std::isfinite(v)) return false;
    }
    return true;
}

class Report {
public:
    void section(const char* name) { std::printf("\n== %s ==\n", name); }

    void check(bool ok, const char* fmt, ...) {
        total_++;
        if (!ok) failed_++;
        std::printf("%s ", ok ? "  ok  " : "  FAIL");
        va_list ap;
        va_start(ap, fmt);
        std::vprintf(fmt, ap);
        va_end(ap);
        std::printf("\n");
    }

    void note(const char* fmt, ...) {
        std::printf("       ");
        va_list ap;
        va_start(ap, fmt);
        std::vprintf(fmt, ap);
        va_end(ap);
        std::printf("\n");
    }

    int failures() const { return failed_; }
    int total() const { return total_; }

private:
    int failed_ = 0;
    int total_ = 0;
};

class TempFile {
public:
    explicit TempFile(const std::string& text) {
        static int counter = 0;
        char name[64];
        std::snprintf(name, sizeof(name), "ca_eq_test_tmp_%d.txt", counter++);
        path_ = name;
        std::FILE* f = std::fopen(path_.c_str(), "wb");
        if (f != nullptr) {
            std::fwrite(text.data(), 1, text.size(), f);
            std::fclose(f);
        }
    }
    ~TempFile() { std::remove(path_.c_str()); }
    TempFile(const TempFile&) = delete;
    TempFile& operator=(const TempFile&) = delete;

    const char* path() const { return path_.c_str(); }

private:
    std::string path_;
};

}

#endif
