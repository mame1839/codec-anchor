// 書式: 1 行 1 値の dB を kCurvePoints 行。周波数は書かない — グリッドは
// dsp/ca_eq_curve.h ただ 1 箇所で定義され、書くと定義が 2 箇所になる。
#ifndef CA_EQ_CURVE_IO_H_
#define CA_EQ_CURVE_IO_H_

#include <cstdio>
#include <cstdlib>

#include "dsp/ca_eq_curve.h"

namespace caeq {

enum class CurveRead {
    kOk = 0,
    kOpenFailed,
    // 点数が kCurvePoints ちょうどでない。多くても少なくても失敗 — 足りない分を
    // 0 dB で埋めると、送り手が意図していない平坦部が黙って鳴る。
    kWrongCount,
};

struct CurveReadResult {
    CurveRead status = CurveRead::kOk;
    int       count  = 0;
};

// 開いてある FILE* から読む。out は kCurvePoints 個ぶん。
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

}  // namespace caeq

#endif  // CA_EQ_CURVE_IO_H_
