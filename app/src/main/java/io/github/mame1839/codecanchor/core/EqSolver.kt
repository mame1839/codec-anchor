package io.github.mame1839.codecanchor.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object EqSolver {

    const val DEFAULT_FS = 48_000

    private const val SOLVED_GAIN_LIMIT_DB = 20.0
    private const val Q_ESCALATION = 1.5
    private const val Q_ESCALATION_TRIES = 4

    private const val SOLVE_ITERATIONS = 16
    private const val SOLVE_TOLERANCE_DB = 1e-9

    private const val REFINE_SWEEPS = 8

    private const val FIT_GRID_POINTS = 2000

    private const val FIT_ITERATIONS = 12
    private const val FIT_DAMPING = 0.6
    private const val FIT_REFERENCE_GAIN_DB = 6.0

    class Solution(val gainsDb: DoubleArray, val q: Double)

    fun defaultQ(bandCount: Int): Double = when (bandCount) {
        31 -> 1.41
        15 -> 0.7
        10 -> 0.5
        else -> 0.4
    }

    fun centerFrequencies(bandCount: Int): List<Int> = when (bandCount) {
        31 -> listOf(
            20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000, 6_300,
            8_000, 10_000, 12_500, 16_000, 20_000,
        )
        15 -> listOf(
            25, 40, 63, 100, 160, 250, 400, 630, 1_000, 1_600, 2_500, 4_000, 6_300, 10_000, 16_000,
        )
        10 -> listOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        else -> listOf(63, 250, 1_000, 4_000, 16_000)
    }

    fun solve(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int = DEFAULT_FS): Solution {
        var currentQ = q
        repeat(Q_ESCALATION_TRIES) {
            val solved = solveOnce(targetDb, freqs, currentQ, fs)
            if (solved.all { abs(it) <= SOLVED_GAIN_LIMIT_DB }) return Solution(solved, currentQ)
            currentQ *= Q_ESCALATION
        }
        return Solution(targetDb.copyOf(), q)
    }

    fun solveBands(
        targetDb: DoubleArray,
        freqs: List<Int>,
        q: Double,
        fs: Int = DEFAULT_FS,
    ): List<EqBand> {
        val solution = solve(targetDb, freqs, q, fs)
        val q100 = (solution.q * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
        val bands = freqs.mapIndexed { i, hz ->
            EqBand(
                freqHz = hz.coerceIn(EqBand.FREQ_RANGE),
                q100 = q100,
                gainDb10 = Math.round(solution.gainsDb[i] * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqBand.GAIN_RANGE),
            )
        }
        val targetDb10 = IntArray(targetDb.size) {
            Math.round(targetDb[it] * EqUnits.GAIN_SCALE).toInt()
        }
        return refineToTargets(bands, targetDb10, q100.toDouble() / EqUnits.Q_SCALE, fs)
    }

    class CurveFit(val bands: List<EqBand>, val offsetDb: Double)

    fun fitCurve(
        curveDb: (Double) -> Double,
        freqs: List<Int>,
        q: Double,
        fs: Int = DEFAULT_FS,
    ): CurveFit {
        if (freqs.isEmpty()) return CurveFit(emptyList(), 0.0)
        val hiHz = minOf(20_000.0, freqs.last().toDouble())
        val atHz = DoubleArray(FIT_GRID_POINTS) { i ->
            exp(ln(20.0) + (ln(hiHz) - ln(20.0)) * i / (FIT_GRID_POINTS - 1.0))
        }
        val target = DoubleArray(atHz.size) { curveDb(atHz[it]) }
        val offset = target.average()
        val shape = DoubleArray(atHz.size) { target[it] - offset }

        var currentQ = q
        repeat(Q_ESCALATION_TRIES) {
            val q100 = (currentQ * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
            val gains = fitShape(shape, atHz, freqs, q100.toDouble() / EqUnits.Q_SCALE, fs)
            if (gains != null) return quantizedFit(gains, q100, shape, atHz, freqs, offset, fs)
            currentQ *= Q_ESCALATION
        }
        val q100 = (q * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
        val naive = DoubleArray(freqs.size) { curveDb(freqs[it].toDouble()) - offset }
        return quantizedFit(naive, q100, shape, atHz, freqs, offset, fs)
    }

    private fun fitShape(
        shape: DoubleArray,
        atHz: DoubleArray,
        freqs: List<Int>,
        q: Double,
        fs: Int,
    ): DoubleArray? {
        val n = freqs.size
        val rows = atHz.size
        val grid = CentreGrid(atHz, freqs, q, fs)
        val basis = Array(rows) { i ->
            DoubleArray(n) { j -> grid.responseDb(i, j, FIT_REFERENCE_GAIN_DB) / FIT_REFERENCE_GAIN_DB }
        }
        val normal = Array(n) { DoubleArray(n) }
        for (r in 0 until rows) {
            val row = basis[r]
            for (i in 0 until n) {
                val ri = row[i]
                if (ri == 0.0) continue
                val ni = normal[i]
                for (j in i until n) ni[j] += ri * row[j]
            }
        }
        for (i in 0 until n) for (j in 0 until i) normal[i][j] = normal[j][i]

        fun lstsq(residual: DoubleArray): DoubleArray? {
            val rhs = DoubleArray(n)
            for (r in 0 until rows) {
                val w = residual[r]
                if (w == 0.0) continue
                val row = basis[r]
                for (j in 0 until n) rhs[j] += row[j] * w
            }
            return solveLinear(normal, rhs)
        }

        val gains = lstsq(shape) ?: return null
        for (j in 0 until n) {
            gains[j] = gains[j].coerceIn(-SOLVED_GAIN_LIMIT_DB, SOLVED_GAIN_LIMIT_DB)
        }
        for (iteration in 0 until FIT_ITERATIONS) {
            val residual = DoubleArray(rows) { i -> shape[i] - grid.combinedAt(i, gains) }
            val delta = lstsq(residual) ?: break
            for (j in 0 until n) {
                gains[j] = (gains[j] + FIT_DAMPING * delta[j])
                    .coerceIn(-SOLVED_GAIN_LIMIT_DB, SOLVED_GAIN_LIMIT_DB)
            }
        }
        return if (gains.all { abs(it) < SOLVED_GAIN_LIMIT_DB }) gains else null
    }

    private fun quantizedFit(
        gainsDb: DoubleArray,
        q100: Int,
        shape: DoubleArray,
        atHz: DoubleArray,
        freqs: List<Int>,
        offsetDb: Double,
        fs: Int,
    ): CurveFit {
        val bands = freqs.mapIndexed { i, hz ->
            EqBand(
                freqHz = hz.coerceIn(EqBand.FREQ_RANGE),
                q100 = q100,
                gainDb10 = Math.round(gainsDb[i] * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqBand.GAIN_RANGE),
            )
        }
        val grid = CentreGrid(atHz, freqs, q100.toDouble() / EqUnits.Q_SCALE, fs)
        val quantized = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        var maxErr = Double.NEGATIVE_INFINITY
        var minErr = Double.POSITIVE_INFINITY
        for (i in atHz.indices) {
            val err = grid.combinedAt(i, quantized) - shape[i]
            if (err > maxErr) maxErr = err
            if (err < minErr) minErr = err
        }
        return CurveFit(bands, offsetDb - (maxErr + minErr) / 2.0)
    }

    private fun refineToTargets(
        bands: List<EqBand>,
        targetDb10: IntArray,
        q: Double,
        fs: Int,
    ): List<EqBand> {
        if (bands.isEmpty()) return bands
        val grid = CentreGrid(bands.map { it.freqHz }, q, fs)
        val gains = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        val db10 = IntArray(bands.size) { bands[it].gainDb10 }
        repeat(REFINE_SWEEPS) {
            var moved = false
            for (i in db10.indices) {
                val error = targetDb10[i] -
                    Math.round(grid.combinedAt(i, gains) * EqUnits.GAIN_SCALE).toInt()
                if (error == 0) continue
                val next = (db10[i] + error).coerceIn(EqBand.GAIN_RANGE)
                if (next == db10[i]) continue
                db10[i] = next
                gains[i] = next.toDouble() / EqUnits.GAIN_SCALE
                moved = true
            }
            if (!moved) return bands.mapIndexed { i, b -> b.copy(gainDb10 = db10[i]) }
        }
        return bands.mapIndexed { i, b -> b.copy(gainDb10 = db10[i]) }
    }

    fun graphicTargetsDb10(bands: List<EqBand>, fs: Int = DEFAULT_FS): IntArray =
        IntArray(bands.size) { i ->
            Math.round(combinedResponseDb(bands, bands[i].freqHz.toDouble(), fs) * EqUnits.GAIN_SCALE)
                .toInt()
        }

    fun withGraphicTarget(
        bands: List<EqBand>,
        index: Int,
        targetDb10: Int,
        fs: Int = DEFAULT_FS,
    ): List<EqBand> {
        if (index !in bands.indices) return bands
        if (bands.size == 1) return listOf(bands[0].copy(gainDb10 = targetDb10.coerceIn(EqBand.GAIN_RANGE)))
        val freqs = bands.map { it.freqHz }
        val storedQ = bands[index].q100.toDouble() / EqUnits.Q_SCALE
        val grid = CentreGrid(freqs, storedQ, fs)
        val gains = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        val targets = DoubleArray(bands.size) {
            Math.round(grid.combinedAt(it, gains) * EqUnits.GAIN_SCALE).toDouble() / EqUnits.GAIN_SCALE
        }
        targets[index] = targetDb10.toDouble() / EqUnits.GAIN_SCALE
        return solveBands(targets, freqs, defaultQ(bands.size), fs)
    }

    internal class CentreGrid(atHz: DoubleArray, freqs: List<Int>, q: Double, val fs: Int) {
        constructor(freqs: List<Int>, q: Double, fs: Int) :
            this(DoubleArray(freqs.size) { freqs[it].toDouble() }, freqs, q, fs)

        val n = freqs.size
        private val rows = atHz.size
        private val p = DoubleArray(rows * n)
        private val u = DoubleArray(rows * n)
        private val r = DoubleArray(rows * n)
        private val v = DoubleArray(rows * n)
        private val alpha = DoubleArray(n)

        init {
            val cosW = DoubleArray(rows)
            val sinW = DoubleArray(rows)
            val cos2W = DoubleArray(rows)
            val sin2W = DoubleArray(rows)
            for (i in 0 until rows) {
                val w = 2.0 * Math.PI * atHz[i] / fs
                cosW[i] = cos(w)
                sinW[i] = sin(w)
                cos2W[i] = cos(2 * w)
                sin2W[i] = sin(2 * w)
            }
            for (j in 0 until n) {
                val w0 = 2.0 * Math.PI * freqs[j] / fs
                alpha[j] = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                for (i in 0 until rows) {
                    val k = i * n + j
                    p[k] = 1 - 2 * cosW0 * cosW[i] + cos2W[i]
                    u[k] = 1 - cos2W[i]
                    r[k] = 2 * cosW0 * sinW[i] - sin2W[i]
                    v[k] = sin2W[i]
                }
            }
        }

        fun responseDb(i: Int, j: Int, gainDb: Double): Double {
            if (gainDb == 0.0) return 0.0
            val a = 10.0.pow(gainDb / 40.0)
            val k = i * n + j
            val hi = alpha[j] * a
            val lo = alpha[j] / a
            val numRe = p[k] + hi * u[k]
            val numIm = r[k] + hi * v[k]
            val denRe = p[k] + lo * u[k]
            val denIm = r[k] + lo * v[k]
            val den = denRe * denRe + denIm * denIm
            if (den == 0.0) return 0.0
            return 10.0 * log10((numRe * numRe + numIm * numIm) / den)
        }

        fun combinedAt(i: Int, gains: DoubleArray): Double {
            var sum = 0.0
            for (j in 0 until n) sum += responseDb(i, j, gains[j])
            return sum
        }
    }

    private fun solveOnce(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int): DoubleArray {
        val n = freqs.size
        val grid = CentreGrid(freqs, q, fs)
        val gains = targetDb.copyOf()
        repeat(SOLVE_ITERATIONS) {
            val residual = DoubleArray(n) { i -> targetDb[i] - grid.combinedAt(i, gains) }
            if (residual.maxOf { abs(it) } < SOLVE_TOLERANCE_DB) return gains
            val m = Array(n) { i ->
                DoubleArray(n) { j ->
                    grid.responseDb(i, j, gains[j] + 0.5) - grid.responseDb(i, j, gains[j] - 0.5)
                }
            }
            val delta = solveLinear(m, residual) ?: return gains
            for (i in 0 until n) gains[i] += delta[i]
        }
        return gains
    }

    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (k in col..n) m[row][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    fun peakingResponseDb(atHz: Double, fcHz: Double, q: Double, gainDb: Double, fs: Int): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fcHz / fs
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1 + alpha * a
        val b1 = -2 * cos(w0)
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cos(w0)
        val a2 = 1 - alpha / a
        return biquadMagnitudeDb(b0, b1, b2, a0, a1, a2, atHz, fs)
    }

    fun shelfResponseDb(
        atHz: Double,
        fcHz: Double,
        q: Double,
        gainDb: Double,
        fs: Int,
        high: Boolean,
    ): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fcHz / fs
        val radicand = ((a + 1 / a) * (1 / q - 1) + 2).coerceAtLeast(0.0)
        val alpha = sin(w0) / 2.0 * sqrt(radicand)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val cosW0 = cos(w0)
        return if (high) {
            biquadMagnitudeDb(
                a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha),
                -2 * a * ((a - 1) + (a + 1) * cosW0),
                a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha),
                (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha,
                2 * ((a - 1) - (a + 1) * cosW0),
                (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha,
                atHz, fs,
            )
        } else {
            biquadMagnitudeDb(
                a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha),
                2 * a * ((a - 1) - (a + 1) * cosW0),
                a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha),
                (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha,
                -2 * ((a - 1) + (a + 1) * cosW0),
                (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha,
                atHz, fs,
            )
        }
    }

    private fun biquadMagnitudeDb(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
        atHz: Double,
        fs: Int,
    ): Double {
        val w = 2.0 * Math.PI * atHz / fs
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = a0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        if (den == 0.0) return 0.0
        return 20.0 * log10(num / den)
    }

    fun bandResponseDb(band: EqBand, atHz: Double, fs: Int = DEFAULT_FS): Double {
        val gain = band.gainDb10.toDouble() / EqUnits.GAIN_SCALE
        val q = band.q100.toDouble() / EqUnits.Q_SCALE
        return when (band.type) {
            EqBandType.LOW_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, false)
            EqBandType.HIGH_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, true)
            else -> peakingResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs)
        }
    }

    fun combinedResponseDb(bands: List<EqBand>, atHz: Double, fs: Int = DEFAULT_FS): Double =
        bands.sumOf { bandResponseDb(it, atHz, fs) }

    fun autoPreampDb10(bands: List<EqBand>, fs: Int = DEFAULT_FS): Int {
        if (bands.isEmpty()) return 0
        var peak = 0.0
        for (hz in EqCurveGrid.HZ) {
            val db = combinedResponseDb(bands, hz, fs)
            if (db > peak) peak = db
        }
        if (peak <= 0.0) return 0
        return -(peak * EqUnits.GAIN_SCALE).toInt()
    }
}
