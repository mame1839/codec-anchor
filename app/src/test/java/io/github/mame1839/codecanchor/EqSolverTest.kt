package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class EqSolverTest {

    @Test
    fun naiveCascadeOvershoots() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 60) }
        var peak = 0.0
        for (i in 0..400) {
            val hz = 20.0 * Math.pow(1000.0, i / 400.0)
            peak = maxOf(peak, EqSolver.combinedResponseDb(bands, hz))
        }
        assertTrue("期待は約 8.85 dB、実際は $peak", peak in 8.0..9.5)
    }

    @Test
    fun solvedGainsHitTheTarget() {
        val freqs = EqSolver.centerFrequencies(10)
        val target = DoubleArray(freqs.size) { 6.0 }
        val q = EqSolver.defaultQ(10)
        val bands = EqSolver.solveBands(target, freqs, q)
        freqs.forEachIndexed { i, hz ->
            val realized = EqSolver.combinedResponseDb(bands, hz.toDouble())
            assertEquals("バンド $hz Hz", 6.0, realized, 0.05)
        }
    }

    @Test
    fun solvedGainsHitTheTargetAtLargeGainsToo() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val target = DoubleArray(n) { 12.0 }
            val bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(n))
            freqs.forEachIndexed { i, hz ->
                val realized = EqSolver.combinedResponseDb(bands, hz.toDouble())
                assertEquals("n=$n バンド $hz Hz", 12.0, realized, 0.05)
            }
        }
    }

    @Test
    fun allBandsAtTheSameTargetGiveAFlatCurve() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, freqs, EqSolver.defaultQ(10))
        val ripple = rippleDb(bands, 125.0, 8_000.0)
        assertTrue("補正後のうねりが $ripple dB (期待は 1.5 dB 以下)", ripple <= 1.5)

        val naive = freqs.map { EqBand(freqHz = it, q100 = 100, gainDb10 = 120) }
        assertTrue("補正なしのうねりが ${rippleDb(naive, 125.0, 8_000.0)} dB しかない", rippleDb(naive, 125.0, 8_000.0) > 3.0)
    }

    @Test
    fun flatTargetsStayFlatBetweenTheCentres() {
        val limits = mapOf(5 to 1.2, 10 to 0.6, 15 to 0.6, 31 to 3.3)
        for ((n, limit) in limits) {
            val freqs = EqSolver.centerFrequencies(n)
            val bands = EqSolver.solveBands(DoubleArray(n) { 12.0 }, freqs, EqSolver.defaultQ(n))
            val whole = maxDeviationDb(bands, freqs.first().toDouble(), freqs.last().toDouble(), 12.0)
            assertTrue("n=$n の中心間のずれが $whole dB (上限 $limit)", whole <= limit)
        }
        val freqs = EqSolver.centerFrequencies(31)
        val bands = EqSolver.solveBands(DoubleArray(31) { 12.0 }, freqs, EqSolver.defaultQ(31))
        val audible = maxDeviationDb(bands, 20.0, 16_000.0, 12.0)
        assertTrue("31 バンドの 16 kHz までのずれが $audible dB (上限 1.0)", audible <= 1.0)
    }

    private fun maxDeviationDb(bands: List<EqBand>, loHz: Double, hiHz: Double, targetDb: Double): Double {
        var worst = 0.0
        for (i in 0..2000) {
            val hz = loHz * Math.pow(hiHz / loHz, i / 2000.0)
            val d = abs(EqSolver.combinedResponseDb(bands, hz) - targetDb)
            if (d > worst) worst = d
        }
        return worst
    }

    @Test
    fun escalatedQAnnealsBackWhenTheTargetCalmsDown() {
        val n = 31
        val defaultQ100 = (EqSolver.defaultQ(n) * 100).toInt()
        var bands = EqSolver.solveBands(DoubleArray(n) { 0.0 }, EqSolver.centerFrequencies(n), EqSolver.defaultQ(n))
        bands = EqSolver.withGraphicTarget(bands, 15, 120)
        assertTrue("前提が崩れた: スパイクで Q が上がっていない", bands.first().q100 > defaultQ100)
        assertEquals(120, EqSolver.graphicTargetsDb10(bands)[15])

        bands = EqSolver.withGraphicTarget(bands, 15, 0)
        assertEquals("平らに戻したのに Q が残っている", defaultQ100, bands.first().q100)
        assertTrue(EqSolver.graphicTargetsDb10(bands).all { it == 0 })
    }

    private fun rippleDb(bands: List<EqBand>, loHz: Double, hiHz: Double): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0..800) {
            val hz = loHz * Math.pow(hiHz / loHz, i / 800.0)
            val db = EqSolver.combinedResponseDb(bands, hz)
            lo = minOf(lo, db)
            hi = maxOf(hi, db)
        }
        return hi - lo
    }

    @Test
    fun graphicTargetsRoundTripThroughTheStoredGains() {
        for (n in listOf(5, 10, 15, 31)) {
            var bands = EqSolver.solveBands(DoubleArray(n) { 0.0 }, EqSolver.centerFrequencies(n), EqSolver.defaultQ(n))
            for (value in listOf(120, -120, 55, -35, 0)) {
                for (index in bands.indices) {
                    bands = EqSolver.withGraphicTarget(bands, index, value)
                    val read = EqSolver.graphicTargetsDb10(bands)[index]
                    assertEquals("n=$n band=$index に $value を入れた", value, read)
                }
            }
        }
    }

    @Test
    fun solvingIsFastEnoughForOneDragFrame() {
        for (n in listOf(10, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val bands = EqSolver.solveBands(DoubleArray(n) { 4.0 }, freqs, EqSolver.defaultQ(n))
            repeat(20) { EqSolver.withGraphicTarget(bands, n / 2, 60) }
            val started = System.nanoTime()
            val rounds = 50
            repeat(rounds) { EqSolver.withGraphicTarget(bands, n / 2, 60) }
            val perCallMs = (System.nanoTime() - started) / 1e6 / rounds
            println("withGraphicTarget n=$n: %.3f ms/回".format(perCallMs))
            assertTrue("n=$n で 1 回 $perCallMs ms かかっている", perCallMs < 2.0)
        }
    }

    @Test
    fun movingOneBandLeavesTheOthersWhereTheyWere() {
        val freqs = EqSolver.centerFrequencies(10)
        var bands = EqSolver.solveBands(doubleArrayOf(3.0, -2.0, 0.0, 5.0, -6.0, 1.0, 4.0, -1.0, 2.0, 0.0), freqs, EqSolver.defaultQ(10))
        val before = EqSolver.graphicTargetsDb10(bands)
        bands = EqSolver.withGraphicTarget(bands, 4, 120)
        val after = EqSolver.graphicTargetsDb10(bands)
        assertEquals("触ったバンド", 120, after[4])
        after.indices.filter { it != 4 }.forEach {
            assertEquals("バンド ${freqs[it]} Hz が動いた", before[it], after[it])
        }
    }

    @Test
    fun repeatedEditsDoNotDrift() {
        val freqs = EqSolver.centerFrequencies(10)
        var bands = EqSolver.solveBands(DoubleArray(10) { 0.0 }, freqs, EqSolver.defaultQ(10))
        val rng = java.util.Random(7)
        repeat(400) {
            val index = rng.nextInt(10)
            val value = (rng.nextInt(49) - 24) * 5
            bands = EqSolver.withGraphicTarget(bands, index, value)
            assertEquals("直後に読み戻せない", value, EqSolver.graphicTargetsDb10(bands)[index])
        }
        val settled = EqSolver.withGraphicTarget(bands, 3, 60)
        assertEquals(settled, EqSolver.withGraphicTarget(settled, 3, 60))
    }

    @Test
    fun theFastCentreGridAgreesWithTheReferenceFormula() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            for (q in listOf(0.4, 0.5, 0.7, 1.0, 1.41, 2.0, 3.0)) {
                val grid = EqSolver.CentreGrid(freqs, q, EqSolver.DEFAULT_FS)
                for (i in freqs.indices) {
                    for (j in freqs.indices) {
                        for (gain in listOf(0.0, 1.0, -1.0, 6.0, -6.0, 12.0, -12.0, 24.0, -24.0)) {
                            val fast = grid.responseDb(i, j, gain)
                            val slow = EqSolver.peakingResponseDb(
                                freqs[i].toDouble(), freqs[j].toDouble(), q, gain, EqSolver.DEFAULT_FS,
                            )
                            assertEquals("n=$n q=$q i=$i j=$j gain=$gain", slow, fast, 1e-9)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rebandingRepeatedlyDoesNotGrowTheCurve() {
        val freqs = EqSolver.centerFrequencies(10)
        val q = EqSolver.defaultQ(10)
        var bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, freqs, q)
        val first = EqSolver.graphicTargetsDb10(bands)
        repeat(40) {
            val target = DoubleArray(10) { i ->
                EqSolver.combinedResponseDb(bands, freqs[i].toDouble())
            }
            bands = EqSolver.solveBands(target, freqs, q)
        }
        val after = EqSolver.graphicTargetsDb10(bands)
        first.indices.forEach { i ->
            assertEquals("バンド ${freqs[i]} Hz が 40 往復で動いた", first[i], after[i])
        }
    }

    @Test
    fun aSingleBandTakesTheTargetDirectly() {
        val one = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 0))
        assertEquals(75, EqSolver.withGraphicTarget(one, 0, 75).first().gainDb10)
        assertEquals(one, EqSolver.withGraphicTarget(one, 5, 75))
    }

    @Test
    fun wildSolutionsAreClamped() {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 6.0 else -6.0 }
        val solved = EqSolver.solve(target, freqs, EqSolver.defaultQ(31)).gainsDb
        assertTrue("解が暴れている: ${solved.maxOf { abs(it) }}", solved.all { abs(it) <= 20.0 })
    }

    @Test
    fun solvedBandsCarryTheQThatWasActuallyUsed() {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 6.0 else -6.0 }
        val baseQ = EqSolver.defaultQ(31)
        val solution = EqSolver.solve(target, freqs, baseQ)
        assertTrue("この目標は Q を上げないと収まらないはず", solution.q > baseQ)

        val bands = EqSolver.solveBands(target, freqs, baseQ)
        assertEquals((solution.q * 100).toInt(), bands.first().q100)
    }

    @Test
    fun autoPreampUsesCombinedPeakNotSum() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 60) }
        val preamp = EqSolver.autoPreampDb10(bands)
        assertTrue("期待は -80 〜 -95、実際は $preamp", preamp in -95..-80)
    }

    @Test
    fun flatEqNeedsNoHeadroom() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 0) }
        assertEquals(0, EqSolver.autoPreampDb10(bands))
    }

    @Test
    fun bandCountsProduceDistinctAscendingFrequencies() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            assertEquals(n, freqs.size)
            assertEquals(freqs.sorted(), freqs)
            assertEquals(n, freqs.distinct().size)
            assertTrue(freqs.first() >= 20 && freqs.last() <= 20_000)
        }
    }

    @Test
    fun allBandCountsSolveWithinLimits() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val target = DoubleArray(n) { 4.0 }
            val solved = EqSolver.solve(target, freqs, EqSolver.defaultQ(n)).gainsDb
            assertTrue(solved.all { it.isFinite() && abs(it) <= 20.0 })
        }
    }

    @Test
    fun shelfStaysFiniteOutsideItsDomain() {
        val db = EqSolver.shelfResponseDb(1_000.0, 100.0, 4.0, 40.0, EqSolver.DEFAULT_FS, false)
        assertTrue("シェルフが NaN を返した", db.isFinite())
        val bands = listOf(EqBand(freqHz = 100, q100 = 400, gainDb10 = 400, type = 1))
        assertTrue(EqSolver.autoPreampDb10(bands) <= 0)
    }

    @Test
    fun fitReproducesARealizableCurve() {
        val freqs = EqSolver.centerFrequencies(31)
        val trueBands = freqs.mapIndexed { i, hz ->
            val bump = 3.0 * exp(-((i - 10.0) / 4.0).let { it * it }) -
                2.0 * exp(-((i - 22.0) / 5.0).let { it * it })
            EqBand(freqHz = hz, q100 = 141, gainDb10 = Math.round(bump * 10).toInt())
        }
        val curve = { hz: Double -> EqSolver.combinedResponseDb(trueBands, hz) - 7.0 }
        val fit = EqSolver.fitCurve(curve, freqs, EqSolver.defaultQ(31))
        var worst = 0.0
        for (i in 0..400) {
            val hz = exp(ln(20.0) + (ln(20_000.0) - ln(20.0)) * i / 400.0)
            val realized = EqSolver.combinedResponseDb(fit.bands, hz) + fit.offsetDb
            worst = maxOf(worst, abs(realized - curve(hz)))
        }
        assertTrue("最悪 $worst dB", worst <= 0.2)
        assertTrue("offset=${fit.offsetDb}", abs(fit.offsetDb + 7.0) <= 1.5)
    }

    @Test
    fun fitEscalatesTheQWhenTheSolutionWouldExceedTheLimit() {
        val freqs = EqSolver.centerFrequencies(31)
        val points = freqs.mapIndexed { i, hz ->
            hz.toDouble() to if (i % 2 == 0) 12.0 else -12.0
        }
        val fit = EqSolver.fitCurve(
            { hz -> AutoEqParser.interpolate(points, hz) },
            freqs,
            EqSolver.defaultQ(31),
        )
        val defaultQ100 = (EqSolver.defaultQ(31) * 100).toInt()
        assertTrue("q100=${fit.bands.first().q100}", fit.bands.all { it.q100 > defaultQ100 })
        assertTrue(fit.bands.all { abs(it.gainDb10) <= 200 })
    }

    @Test
    fun fitFallsBackToNaiveSamplingWhenEscalationFails() {
        val freqs = EqSolver.centerFrequencies(31)
        val points = freqs.mapIndexed { i, hz ->
            hz.toDouble() to if (i % 2 == 0) 25.0 else -25.0
        }
        val fit = EqSolver.fitCurve(
            { hz -> AutoEqParser.interpolate(points, hz) },
            freqs,
            EqSolver.defaultQ(31),
        )
        assertEquals((EqSolver.defaultQ(31) * 100).toInt(), fit.bands.first().q100)
        assertTrue(fit.bands.any { abs(it.gainDb10) > 200 })
    }

    @Test
    fun theExpandedBandResponseAgreesWithTheComplexReference() {
        val freqs = listOf(20, 105, 1_000, 2_500, 8_000, 16_000)
        val gains = listOf(-400, -120, -35, 40, 120, 400)
        var worst = 0.0
        var worstLabel = ""
        var points = 0
        for (type in listOf(EqBandType.PEAKING, EqBandType.LOW_SHELF, EqBandType.HIGH_SHELF)) {
            val q100s = if (type == EqBandType.PEAKING) {
                listOf(10, 50, 71, 141, 400, 1_000, 4_000)
            } else {
                listOf(10, 50, 71, 100)
            }
            for (freqHz in freqs) {
                for (q100 in q100s) {
                    for (gainDb10 in gains) {
                        val band = EqBand(freqHz, q100, gainDb10, type)
                        for (hz in EqReferenceResponse.GRID_HZ) {
                            val diff =
                                abs(EqReferenceResponse.bandDb(band, hz) - EqSolver.bandResponseDb(band, hz))
                            points++
                            if (diff > worst) {
                                worst = diff
                                worstLabel = "type=%d f=%d q100=%d g=%d at=%.1f Hz"
                                    .format(type, freqHz, q100, gainDb10, hz)
                            }
                        }
                    }
                }
            }
        }
        println("展開 vs 複素評価: %d 点の最大差 %.3e dB (%s)".format(points, worst, worstLabel))
        assertTrue("展開と複素評価の最大差 $worst dB ($worstLabel)", worst < 1e-8)
    }

    @Test
    fun aStackedCurveAgreesWithTheComplexReference() {
        val stack = listOf(
            EqBand(105, 71, 40, EqBandType.LOW_SHELF),
            EqBand(2_500, 71, -20, EqBandType.HIGH_SHELF),
            EqBand(32, 50, 30),
            EqBand(250, 50, -50),
            EqBand(1_000, 141, 120),
            EqBand(4_000, 400, -120),
            EqBand(16_000, 50, 20),
        )
        val reference = EqReferenceResponse.curveDb(stack)
        EqReferenceResponse.GRID_HZ.forEachIndexed { i, hz ->
            assertEquals("%.1f Hz".format(hz), reference[i], EqSolver.combinedResponseDb(stack, hz), 1e-9)
        }
    }

    @Test
    fun theReferenceRefusesTheShelfDomainThatTheProductClamps() {
        val outOfDomain = EqBand(100, 400, 400, EqBandType.LOW_SHELF)
        val fromReference = runCatching { EqReferenceResponse.bandDb(outOfDomain, 1_000.0) }
        assertTrue(
            "参照が想定外のシェルフを黙って返した: ${fromReference.getOrNull()}",
            fromReference.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            "製品側はクランプして有限値を返すこと",
            EqSolver.bandResponseDb(outOfDomain, 1_000.0).isFinite(),
        )
    }
}
