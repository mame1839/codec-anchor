package io.github.mame1839.codecanchor.core

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

object EqLoudness {

    val GRID_HZ: DoubleArray = EqCurveGrid.HZ

    private const val SPECTRUM_FLOOR_DOWN_DB = 100.0

    private const val K_FS = 48_000.0
    private const val PRE_B0 = 1.53512485958697
    private const val PRE_B1 = -2.69169618940638
    private const val PRE_B2 = 1.19839281085285
    private const val PRE_A1 = -1.69065929318241
    private const val PRE_A2 = 0.73248077421585
    private const val RLB_B0 = 1.0
    private const val RLB_B1 = -2.0
    private const val RLB_B2 = 1.0
    private const val RLB_A1 = -1.99004745483398
    private const val RLB_A2 = 0.99007225036621

    private val kRawDbAt1kHz = kRawDb(1_000.0)

    fun kWeightingDb(hz: Double): Double = kRawDb(hz) - kRawDbAt1kHz

    private val kDbOnGrid = DoubleArray(GRID_HZ.size) { kWeightingDb(GRID_HZ[it]) }
    private val pinkPsdDb = DoubleArray(GRID_HZ.size) { -10.0 * log10(GRID_HZ[it]) }

    fun defaultWeights(): DoubleArray = weightsFromSpectrumDb(pinkPsdDb)

    fun weightsFromSpectrumDb(spectrumDb: DoubleArray): DoubleArray {
        require(spectrumDb.size == GRID_HZ.size) {
            "スペクトルは GRID_HZ と同じ ${GRID_HZ.size} 点であること (実際: ${spectrumDb.size})"
        }
        var maxDb = Double.NEGATIVE_INFINITY
        for (s in spectrumDb) if (s.isFinite() && s > maxDb) maxDb = s
        if (maxDb == Double.NEGATIVE_INFINITY) return defaultWeights()
        val floorDb = maxDb - SPECTRUM_FLOOR_DOWN_DB
        return DoubleArray(GRID_HZ.size) { i ->
            val s = spectrumDb[i]
            val clamped = if (s.isFinite()) max(s, floorDb) else floorDb
            10.0.pow((clamped - maxDb + kDbOnGrid[i]) / 10.0) * GRID_HZ[i]
        }
    }

    fun perceivedGainDb(
        bands: List<EqBand>,
        weights: DoubleArray,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Double {
        require(weights.size == GRID_HZ.size) {
            "重みは GRID_HZ と同じ ${GRID_HZ.size} 点であること (実際: ${weights.size})"
        }
        var num = 0.0
        var den = 0.0
        for (i in GRID_HZ.indices) {
            num += weights[i] * 10.0.pow(EqSolver.combinedResponseDb(bands, GRID_HZ[i], fs) / 10.0)
            den += weights[i]
        }
        require(den > 0.0 && den.isFinite()) { "重みの合計が正の有限値であること (実際: $den)" }
        return 10.0 * log10(num / den)
    }

    fun baseLevelDb(
        base: EqSettings,
        weights: DoubleArray,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Double {
        if (!base.enabled) return 0.0
        return base.preampDb10.toDouble() / EqUnits.GAIN_SCALE +
            perceivedGainDb(base.bands, weights, fs)
    }

    fun preampDb10(
        bands: List<EqBand>,
        weights: DoubleArray,
        baseLevelDb: Double,
        fs: Int = EqSolver.DEFAULT_FS,
    ): Int {
        val perceived = perceivedGainDb(bands, weights, fs)
        return Math.round((baseLevelDb - perceived) * EqUnits.GAIN_SCALE).toInt()
            .coerceIn(EqSettings.PREAMP_RANGE)
    }

    private fun kRawDb(hz: Double): Double =
        biquadMagnitudeDb(PRE_B0, PRE_B1, PRE_B2, PRE_A1, PRE_A2, hz) +
            biquadMagnitudeDb(RLB_B0, RLB_B1, RLB_B2, RLB_A1, RLB_A2, hz)

    private fun biquadMagnitudeDb(
        b0: Double,
        b1: Double,
        b2: Double,
        a1: Double,
        a2: Double,
        hz: Double,
    ): Double {
        val w = 2.0 * Math.PI * hz / K_FS
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val den = denRe * denRe + denIm * denIm
        if (den == 0.0) return 0.0
        return 10.0 * log10((numRe * numRe + numIm * numIm) / den)
    }
}
