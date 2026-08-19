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

internal object EqReferenceResponse {

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

    fun curveDb(bands: List<EqBand>, fs: Int = 48_000): DoubleArray =
        DoubleArray(GRID_HZ.size) { combinedDb(bands, GRID_HZ[it], fs) }

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
