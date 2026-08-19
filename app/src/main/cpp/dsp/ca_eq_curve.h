#ifndef CA_EQ_CURVE_H_
#define CA_EQ_CURVE_H_

#include <cmath>

#if defined(__FAST_MATH__)
#error "ca_eq_curve は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

inline constexpr int    kCurvePoints = 401;
inline constexpr double kCurveMinHz  = 20.0;
inline constexpr double kCurveMaxHz  = 20000.0;

inline constexpr float kCurveMaxAbsDb = 40.0f;

inline double curvePointHz(int i) {
    return kCurveMinHz *
           std::pow(kCurveMaxHz / kCurveMinHz,
                    static_cast<double>(i) / static_cast<double>(kCurvePoints - 1));
}

inline bool curveValid(const float* db401) {
    if (db401 == nullptr) return false;
    for (int i = 0; i < kCurvePoints; i++) {
        const float v = db401[i];
        if (!std::isfinite(v) || v < -kCurveMaxAbsDb || v > kCurveMaxAbsDb) return false;
    }
    return true;
}

inline double curveDbAt(const float* db401, double f_hz) {
    if (!(f_hz > kCurveMinHz)) return static_cast<double>(db401[0]);
    if (f_hz >= kCurveMaxHz) return static_cast<double>(db401[kCurvePoints - 1]);
    const double x = static_cast<double>(kCurvePoints - 1) * std::log(f_hz / kCurveMinHz) /
                     std::log(kCurveMaxHz / kCurveMinHz);
    int i = static_cast<int>(x);
    if (i > kCurvePoints - 2) i = kCurvePoints - 2;
    const double t = x - static_cast<double>(i);
    return (1.0 - t) * static_cast<double>(db401[i]) +
           t * static_cast<double>(db401[i + 1]);
}

}

#endif
