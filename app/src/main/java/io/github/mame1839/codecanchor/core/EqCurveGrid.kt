package io.github.mame1839.codecanchor.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

object EqCurveGrid {

    const val POINTS = 401
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    const val MAX_ABS_DB = 40.0

    private val LN_MIN = ln(MIN_HZ)
    private val LN_SPAN = ln(MAX_HZ / MIN_HZ)

    fun hzAt(i: Int): Double = exp(LN_MIN + LN_SPAN * i / (POINTS - 1))

    val HZ: DoubleArray = DoubleArray(POINTS) { hzAt(it) }

    fun nearestIndex(hz: Double): Int =
        ((ln(hz) - LN_MIN) / LN_SPAN * (POINTS - 1)).roundToInt().coerceIn(0, POINTS - 1)

    fun snapHz(hz: Double): Double = hzAt(nearestIndex(hz))

    fun graphicCurveDb(bands: List<EqBand>, fs: Int = EqSolver.DEFAULT_FS): DoubleArray {
        if (bands.isEmpty()) return DoubleArray(POINTS)
        val vertices = knobPolyline(bands, fs)
        return DoubleArray(POINTS) { i ->
            AutoEqParser.interpolate(vertices, hzAt(i)).coerceIn(-MAX_ABS_DB, MAX_ABS_DB)
        }
    }

    fun knobPolyline(bands: List<EqBand>, fs: Int = EqSolver.DEFAULT_FS): List<Pair<Double, Double>> {
        val knobs = EqSolver.graphicTargetsDb10(bands, fs)
        return bands.mapIndexed { i, band ->
            snapHz(band.freqHz.toDouble()) to knobs[i].toDouble() / EqUnits.GAIN_SCALE
        }.sortedBy { it.first }
    }

    fun valid(curveDb: DoubleArray): Boolean =
        curveDb.size == POINTS && curveDb.all { it.isFinite() && it >= -MAX_ABS_DB && it <= MAX_ABS_DB }

    fun encode(curveDb: DoubleArray): String =
        buildString {
            for (v in curveDb) {
                append(EqParams.decimal(Math.round(v * CURVE_SCALE).toInt(), CURVE_SCALE))
                append('\n')
            }
        }

    const val CURVE_SCALE = 100
}
