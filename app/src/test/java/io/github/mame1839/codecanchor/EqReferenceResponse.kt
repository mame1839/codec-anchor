package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * テスト専用の応答評価。**製品のコード ([io.github.mame1839.codecanchor.core.EqSolver]) を
 * 一切呼ばない**独立経路。
 *
 * これが要るのは、「適用した音は試聴した音と同じか」を製品の関数で測ると
 * `x <= x` のトートロジーになるため。参照を別に持って初めて、乖離に意味のある上限を置ける。
 *
 * ### 実装との違い (「書き写し」にならないようにしている点)
 *
 * 係数は RBJ Audio EQ Cookbook の定義そのもの (製品もそこから来ているので同じ)。**違うのは
 * 評価の仕方**で、こちらは H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (a0 + a1·z⁻¹ + a2·z⁻²) を
 * z = e^{jω} の複素数演算でそのまま評価する。EqSolver は実部・虚部を三角関数の和へ手で
 * 展開した形 (`numRe = b0 + b1·cosω + b2·cos2ω` 等) なので、展開の誤りはこちらに伝播しない。
 *
 * 参照そのものの正しさは [EqReferenceResponseTest] が**解析的に分かっている値**
 * (peaking は中心でゲインちょうど・DC と Nyquist で 0 dB、シェルフは通過域でゲイン) で
 * 確かめる。製品と突き合わせて確かめるのでは、独立性が失われる。
 */
internal object EqReferenceResponse {

    /** 評価格子。[io.github.mame1839.codecanchor.ui.eqFinderResponseDb] と同じ 20 Hz〜20 kHz 120 点。 */
    val GRID_HZ: DoubleArray = DoubleArray(120) { exp(ln(20.0) + ln(20_000.0 / 20.0) * it / 119.0) }

    fun bandDb(band: EqBand, atHz: Double, fs: Int = 48_000): Double {
        val gainDb = band.gainDb10 / 10.0
        if (gainDb == 0.0) return 0.0
        val q = band.q100 / 100.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * band.freqHz / fs
        val cosW0 = cos(w0)
        return when (band.type) {
            EqBandType.PEAKING -> {
                val alpha = sin(w0) / (2.0 * q)
                biquadDb(
                    doubleArrayOf(1 + alpha * a, -2 * cosW0, 1 - alpha * a),
                    doubleArrayOf(1 + alpha / a, -2 * cosW0, 1 - alpha / a),
                    atHz, fs,
                )
            }

            else -> {
                // RBJ のシェルフは Q をスロープとして使う。根号の中は Q ≤ 1 なら必ず正で、
                // セッションの軸は Q 0.71 固定。負になる形は参照の想定外なので黙って丸めない。
                val radicand = (a + 1 / a) * (1 / q - 1) + 2
                require(radicand >= 0.0) { "参照の想定外のシェルフ (Q ${q}, ゲイン $gainDb dB)" }
                val alpha = sin(w0) / 2.0 * sqrt(radicand)
                val t = 2 * sqrt(a) * alpha
                if (band.type == EqBandType.LOW_SHELF) {
                    biquadDb(
                        doubleArrayOf(
                            a * ((a + 1) - (a - 1) * cosW0 + t),
                            2 * a * ((a - 1) - (a + 1) * cosW0),
                            a * ((a + 1) - (a - 1) * cosW0 - t),
                        ),
                        doubleArrayOf(
                            (a + 1) + (a - 1) * cosW0 + t,
                            -2 * ((a - 1) + (a + 1) * cosW0),
                            (a + 1) + (a - 1) * cosW0 - t,
                        ),
                        atHz, fs,
                    )
                } else {
                    biquadDb(
                        doubleArrayOf(
                            a * ((a + 1) + (a - 1) * cosW0 + t),
                            -2 * a * ((a - 1) + (a + 1) * cosW0),
                            a * ((a + 1) + (a - 1) * cosW0 - t),
                        ),
                        doubleArrayOf(
                            (a + 1) - (a - 1) * cosW0 + t,
                            2 * ((a - 1) - (a + 1) * cosW0),
                            (a + 1) - (a - 1) * cosW0 - t,
                        ),
                        atHz, fs,
                    )
                }
            }
        }
    }

    fun combinedDb(bands: List<EqBand>, atHz: Double, fs: Int = 48_000): Double =
        bands.sumOf { bandDb(it, atHz, fs) }

    /** [GRID_HZ] 上の合成応答。 */
    fun curveDb(bands: List<EqBand>, fs: Int = 48_000): DoubleArray =
        DoubleArray(GRID_HZ.size) { combinedDb(bands, GRID_HZ[it], fs) }

    /** 対数周波数上の線形補間 (範囲外は端の値)。摘みの折れ線の参照。 */
    fun interpolateLog(points: List<Pair<Double, Double>>, hz: Double): Double {
        if (hz <= points.first().first) return points.first().second
        if (hz >= points.last().first) return points.last().second
        for (i in 0 until points.size - 1) {
            val (f0, v0) = points[i]
            val (f1, v1) = points[i + 1]
            if (hz in f0..f1) return v0 + (v1 - v0) * (ln(hz / f0) / ln(f1 / f0))
        }
        return points.last().second
    }

    private fun biquadDb(b: DoubleArray, a: DoubleArray, atHz: Double, fs: Int): Double {
        val w = 2.0 * Math.PI * atHz / fs
        val z1 = Complex(cos(-w), sin(-w))
        val z2 = z1 * z1
        val num = Complex(b[0], 0.0) + Complex(b[1], 0.0) * z1 + Complex(b[2], 0.0) * z2
        val den = Complex(a[0], 0.0) + Complex(a[1], 0.0) * z1 + Complex(a[2], 0.0) * z2
        return 20.0 * log10((num / den).magnitude)
    }

    private class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)

        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)

        operator fun div(o: Complex): Complex {
            val d = o.re * o.re + o.im * o.im
            return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }

        val magnitude: Double get() = hypot(re, im)
    }
}
