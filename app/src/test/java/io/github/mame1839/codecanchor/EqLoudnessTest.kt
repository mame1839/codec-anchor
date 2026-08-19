package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

class EqLoudnessTest {

    private fun bass(gainDb10: Int) =
        EqBand(freqHz = 105, q100 = 71, gainDb10 = gainDb10, type = EqBandType.LOW_SHELF)

    private fun treble(gainDb10: Int) =
        EqBand(freqHz = 2_500, q100 = 71, gainDb10 = gainDb10, type = EqBandType.HIGH_SHELF)

    private fun presence(gainDb10: Int) =
        EqBand(freqHz = 3_000, q100 = 100, gainDb10 = gainDb10)

    private fun flat() = listOf(bass(0), treble(0), presence(0))

    private fun presentedLevelDb(bands: List<EqBand>, weights: DoubleArray, baseLevelDb: Double): Double =
        EqLoudness.preampDb10(bands, weights, baseLevelDb) / 10.0 +
            EqLoudness.perceivedGainDb(bands, weights)

    @Test
    fun gridIsLogSpacedFrom20HzTo20kHz() {
        val grid = EqLoudness.GRID_HZ
        assertEquals(401, grid.size)
        assertEquals(20.0, grid[0], 1e-9)
        assertEquals(20_000.0, grid[400], 1e-6)
        val ratio = grid[1] / grid[0]
        for (i in 1 until grid.size) {
            assertEquals("点 $i の間隔", ratio, grid[i] / grid[i - 1], 1e-9)
        }
    }

    @Test
    fun kWeightingFollowsTheBs1770Shape() {
        assertEquals(0.0, EqLoudness.kWeightingDb(1_000.0), 0.0)
        assertEquals(-13.97, EqLoudness.kWeightingDb(20.0), 0.05)
        assertEquals(-3.60, EqLoudness.kWeightingDb(60.0), 0.02)
        assertEquals(-1.83, EqLoudness.kWeightingDb(100.0), 0.02)
        assertEquals(2.37, EqLoudness.kWeightingDb(2_000.0), 0.02)
        assertEquals(3.34, EqLoudness.kWeightingDb(10_000.0), 0.02)
        assertTrue("低域は明確に負", EqLoudness.kWeightingDb(60.0) < -2.0)
        assertTrue("10 kHz は正", EqLoudness.kWeightingDb(10_000.0) > 2.0)
    }

    @Test
    fun flatBandsHaveZeroPerceivedGainAndInheritTheBaseLevel() {
        val w = EqLoudness.defaultWeights()
        assertEquals(0.0, EqLoudness.perceivedGainDb(flat(), w), 0.0)
        assertEquals(0, EqLoudness.preampDb10(flat(), w, 0.0))
        assertEquals(-25, EqLoudness.preampDb10(flat(), w, -2.5))
    }

    @Test
    fun bassShelfLoudnessIsFarBelowItsPeak() {
        val bands = listOf(bass(60))
        val w = EqLoudness.defaultWeights()
        val perceived = EqLoudness.perceivedGainDb(bands, w)
        assertEquals(0.670, perceived, 0.02)
        assertTrue("+6 dB シェルフの聴感は +6 よりはるかに小さい", perceived < 1.5)

        assertEquals(-58, EqSolver.autoPreampDb10(bands))
        assertEquals(-7, EqLoudness.preampDb10(bands, w, 0.0))

        val peakBasedLevel = EqSolver.autoPreampDb10(bands) / 10.0 + perceived
        assertEquals(-5.13, peakBasedLevel, 0.05)
    }

    @Test
    fun anyTwoCandidatesPresentAtTheSameLevel() {
        val candidates = listOf(
            flat(),
            listOf(bass(40)),
            listOf(bass(-40)),
            listOf(treble(40)),
            listOf(treble(-40)),
            listOf(bass(20), treble(-40)),
            listOf(bass(40), treble(40), presence(20)),
            listOf(presence(-60)),
            listOf(bass(-120), treble(-120)),
            listOf(bass(120), treble(-120)),
        )
        val w = EqLoudness.defaultWeights()
        val baseLevel = -3.2
        val levels = candidates.map { presentedLevelDb(it, w, baseLevel) }
        levels.forEachIndexed { i, level ->
            assertEquals("候補 $i の提示レベル", baseLevel, level, 0.05 + 1e-9)
        }
        val spread = levels.maxOrNull()!! - levels.minOrNull()!!
        assertTrue("候補間の開き $spread", spread <= 0.1 + 1e-9)
    }

    @Test
    fun noneIsFlatBandsPlusEquivalentPreamp() {
        val none = flat()
        val candidates = listOf(none, listOf(bass(40)), listOf(treble(-40)))
        val w = EqLoudness.defaultWeights()
        val baseLevel = -1.7
        assertEquals(Math.round(baseLevel * 10).toInt(), EqLoudness.preampDb10(none, w, baseLevel))
        val noneLevel = presentedLevelDb(none, w, baseLevel)
        candidates.forEachIndexed { i, c ->
            assertEquals("なしと候補 $i のレベル差", noneLevel, presentedLevelDb(c, w, baseLevel), 0.1 + 1e-9)
        }
    }

    @Test
    fun theZeroOverlayCandidateInheritsTheBasePreampExactly() {
        val w = EqLoudness.defaultWeights()
        val curve = listOf(bass(45), treble(-25))
        for (preamp in listOf(0, -60, -400, 120)) {
            val base = EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bands = curve,
                preampDb10 = preamp,
            )
            val level = EqLoudness.baseLevelDb(base, w)
            val none = curve + listOf(bass(0), treble(0))
            assertEquals("preamp=$preamp", preamp, EqLoudness.preampDb10(none, w, level))
        }
    }

    @Test
    fun aDisabledBaseContributesNeitherCurveNorPreamp() {
        val w = EqLoudness.defaultWeights()
        val off = EqSettings(
            enabled = false,
            mode = EqMode.PARAMETRIC,
            bands = listOf(bass(60), treble(-40)),
            preampDb10 = -200,
        )
        assertEquals(0.0, EqLoudness.baseLevelDb(off, w), 0.0)
        assertEquals(0, EqLoudness.preampDb10(listOf(bass(0), treble(0)), w, 0.0))
    }

    @Test
    fun theBaseLevelIsItsPreampPlusItsPerceivedGain() {
        val w = EqLoudness.defaultWeights()
        val curve = listOf(bass(60))
        val base = EqSettings(
            enabled = true,
            mode = EqMode.GRAPHIC,
            bands = curve,
            preampDb10 = -50,
        )
        assertEquals(-5.0 + 0.670, EqLoudness.baseLevelDb(base, w), 0.02)
    }

    @Test
    fun weightsSurviveBrokenSpectra() {
        val allNan = DoubleArray(EqLoudness.GRID_HZ.size) { Double.NaN }
        assertArrayEquals(EqLoudness.defaultWeights(), EqLoudness.weightsFromSpectrumDb(allNan), 1e-12)

        val spectrum = DoubleArray(EqLoudness.GRID_HZ.size)
        spectrum[100] = Double.NaN
        spectrum[200] = -500.0
        spectrum[300] = Double.NEGATIVE_INFINITY
        val w = EqLoudness.weightsFromSpectrumDb(spectrum)
        assertTrue("全重みが正の有限値", w.all { it.isFinite() && it > 0.0 })
        for (i in intArrayOf(100, 200, 300)) {
            val floored = 10.0.pow((-100.0 + EqLoudness.kWeightingDb(EqLoudness.GRID_HZ[i])) / 10.0) *
                EqLoudness.GRID_HZ[i]
            assertEquals("点 $i は床に落ちる", floored, w[i], floored * 1e-9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EqLoudness.weightsFromSpectrumDb(DoubleArray(400))
        }
    }

    @Test
    fun pinkPsdReproducesTheDefaultWeights() {
        val grid = EqLoudness.GRID_HZ
        val pinkShifted = DoubleArray(grid.size) { -10.0 * log10(grid[it]) + 37.0 }
        val w = EqLoudness.weightsFromSpectrumDb(pinkShifted)
        val d = EqLoudness.defaultWeights()
        for (i in grid.indices) {
            assertEquals("点 $i", d[i], w[i], d[i] * 1e-9)
        }
        val scale = d[0] / 10.0.pow(EqLoudness.kWeightingDb(grid[0]) / 10.0)
        for (i in grid.indices) {
            val k = 10.0.pow(EqLoudness.kWeightingDb(grid[i]) / 10.0)
            assertEquals("点 $i の形", scale, d[i] / k, scale * 1e-9)
        }
    }

    @Test
    fun preampClampsToThePreampRange() {
        val w = EqLoudness.defaultWeights()
        assertEquals(EqSettings.PREAMP_RANGE.first, EqLoudness.preampDb10(flat(), w, -100.0))
        val deepCut = listOf(bass(-400), treble(-400), presence(-400))
        assertEquals(EqSettings.PREAMP_RANGE.last, EqLoudness.preampDb10(deepCut, w, 0.0))
    }

    @Test
    fun theOverlayCornersDefineTheRangeTheSliderMustCover() {
        val w = EqLoudness.defaultWeights()
        val values = cornerOverlays().map { EqLoudness.preampDb10(it, w, 0.0) }
        assertEquals(27, values.size)
        assertEquals("最も静かな候補", -107, values.minOrNull()!!)
        assertEquals("最も大きい候補", 61, values.maxOrNull()!!)
        assertTrue("正のプリアンプが出ない", values.maxOrNull()!! > 0)
        assertTrue("摘みの上限に収まらない", values.maxOrNull()!! <= EqSettings.PREAMP_RANGE.last)
    }

    @Test
    fun aDeepBasePreampPushesTheLoudestCandidatesIntoTheClamp() {
        val w = EqLoudness.defaultWeights()
        val corners = cornerOverlays()
        val atMinusThirty = corners.map { EqLoudness.preampDb10(it, w, -30.0) }
        assertEquals(EqSettings.PREAMP_RANGE.first, atMinusThirty.minOrNull()!!)
        assertEquals(-239, atMinusThirty.maxOrNull()!!)
        val atMinusTwentyNine = corners.map { EqLoudness.preampDb10(it, w, -29.0) }
        assertTrue(
            "−29.0 dB でもう張り付いている: ${atMinusTwentyNine.minOrNull()}",
            atMinusTwentyNine.minOrNull()!! > EqSettings.PREAMP_RANGE.first,
        )
    }

    private fun cornerOverlays(): List<List<EqBand>> {
        val shelfGains = listOf(-120, 0, 120)
        val presenceGains = listOf(-60, 0, 60)
        return shelfGains.flatMap { b ->
            shelfGains.flatMap { t ->
                presenceGains.map { p -> listOf(bass(b), treble(t), presence(p)) }
            }
        }
    }
}
