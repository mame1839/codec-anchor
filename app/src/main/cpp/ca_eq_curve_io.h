#ifndef CA_EQ_CURVE_IO_H_
#define CA_EQ_CURVE_IO_H_

#include <cstdio>
#include <cstdlib>

#include "dsp/ca_eq_curve.h"

namespace caeq {

enum class CurveRead {
    kOk = 0,
    kOpenFailed,
    kWrongCount,
};

struct CurveReadResult {
    CurveRead status = CurveRead::kOk;
    int       count  = 0;
};

inline CurveReadResult readCurveStream(std::FILE* f, float* out) {
    CurveReadResult r;
    if (f == nullptr) { r.status = CurveRead::kOpenFailed; return r; }
    char line[128];
    while (std::fgets(line, sizeof(line), f) != nullptr) {
        const char* s = line;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == '\0' || *s == '\n' || *s == '\r' || *s == '#') continue;
        if (r.count < kCurvePoints) out[r.count] = static_cast<float>(std::atof(s));
        r.count++;
        if (r.count > kCurvePoints) break;
    }
    if (r.count != kCurvePoints) r.status = CurveRead::kWrongCount;
    return r;
}

inline CurveReadResult readCurveFile(const char* path, float* out) {
    std::FILE* f = std::fopen(path, "r");
    if (f == nullptr) return CurveReadResult{CurveRead::kOpenFailed, 0};
    const CurveReadResult r = readCurveStream(f, out);
    std::fclose(f);
    return r;
}

}

#endif
