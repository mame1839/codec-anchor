// 目標曲線のグリッド。**Android にも Xposed にも依存しない。**
//
// 「高精度」(最小位相 FIR) が運ぶのは IR ではなく目標曲線で、その標本点がこのグリッド。
// アプリの solver (EqSolver.autoPreampDb10 / EqLoudness.GRID_HZ) が使っている
// 20 Hz〜20 kHz 対数等間隔 401 点と同じ格子に固定する。
//
// **グリッドを定義する 3 定数 (kCurveMinHz / kCurveMaxHz / kCurvePoints) の置き場は
// ここ 1 箇所。**共有メモリ v4 (段 2) と Kotlin の送信側 (段 3) はこれに合わせ、
// 一致を見張るテストが付く。値を動かすと保存済みの曲線と読み手の解釈がずれる。
//
// 補間の規則 (= 「描いた曲線」の定義。送り手の絵とここが同じ式であることが製品の芯):
//   - 対数 f・線形 dB の折れ線
//   - 20 Hz 未満は 20 Hz の値を保持、20 kHz 超も 20 kHz の値を保持 (Nyquist まで)
#ifndef CA_EQ_CURVE_H_
#define CA_EQ_CURVE_H_

#include <cmath>

// -ffast-math は NaN の検査ごと消す (ca_eq_dsp.h と同じ柵)。
#if defined(__FAST_MATH__)
#error "ca_eq_curve は -ffast-math ではビルドできない (NaN / Inf の検査が消える)"
#endif

namespace caeq {

inline constexpr int    kCurvePoints = 401;
inline constexpr double kCurveMinHz  = 20.0;
inline constexpr double kCurveMaxHz  = 20000.0;

// 検査の上限。演算層のゲイン上限 (kMaxGainDb = 40) と同じ値だが、あちらは biquad の
// パラメータ、こちらは曲線の縦軸で、別の量。たまたま同じ 40 なだけなので参照しない。
inline constexpr float kCurveMaxAbsDb = 40.0f;

// i 番目の標本点の周波数。f[i] = 20 · 1000^(i/400)。
inline double curvePointHz(int i) {
    return kCurveMinHz *
           std::pow(kCurveMaxHz / kCurveMinHz,
                    static_cast<double>(i) / static_cast<double>(kCurvePoints - 1));
}

// 各点が有限かつ |dB| ≤ 40。1 点でも外れたら曲線ごと不採用 (部分適用はしない)。
inline bool curveValid(const float* db401) {
    if (db401 == nullptr) return false;
    for (int i = 0; i < kCurvePoints; i++) {
        const float v = db401[i];
        if (!std::isfinite(v) || v < -kCurveMaxAbsDb || v > kCurveMaxAbsDb) return false;
    }
    return true;
}

// 折れ線の評価。グリッドが対数で厳密に等間隔なことを使い、節点の f を持ち歩かない
// (定義は上の 3 定数だけ — 節点表を別に持つと 2 箇所目になる)。
// 位置と補間は double で計算する (dB→振幅の変換を f32 に落とすのは使う側の最後の 1 回)。
// f_hz が NaN のときも範囲外と同じく端に落ちる (比較が false になる) ので有限値を返す。
inline double curveDbAt(const float* db401, double f_hz) {
    if (!(f_hz > kCurveMinHz)) return static_cast<double>(db401[0]);
    if (f_hz >= kCurveMaxHz) return static_cast<double>(db401[kCurvePoints - 1]);
    const double x = static_cast<double>(kCurvePoints - 1) * std::log(f_hz / kCurveMinHz) /
                     std::log(kCurveMaxHz / kCurveMinHz);
    int i = static_cast<int>(x);
    // 数値誤差で x が 400.0 に触れたときの柵 (上の f_hz >= kCurveMaxHz が先に取るはずだが)。
    if (i > kCurvePoints - 2) i = kCurvePoints - 2;
    const double t = x - static_cast<double>(i);
    return (1.0 - t) * static_cast<double>(db401[i]) +
           t * static_cast<double>(db401[i + 1]);
}

}  // namespace caeq

#endif  // CA_EQ_CURVE_H_
