package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.ui.EqScale
import io.github.mame1839.codecanchor.ui.GAIN_SCALE
import io.github.mame1839.codecanchor.ui.PREAMP_SCALE
import io.github.mame1839.codecanchor.ui.reband
import io.github.mame1839.codecanchor.ui.resetToZero
import io.github.mame1839.codecanchor.ui.toParametric
import io.github.mame1839.codecanchor.ui.withGraphicGrid
import io.github.mame1839.codecanchor.ui.withStartingBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqSectionTest {

    private val gain = GAIN_SCALE
    private val preamp = PREAMP_SCALE
    private val frequency = EqScale.Log(20..20_000)
    private val q = EqScale.Log(10..1_000)

    @Test
    fun linearScaleHitsBothEnds() {
        assertEquals(-120, gain.fromPosition(0f))
        assertEquals(120, gain.fromPosition(1f))
        assertEquals(0f, gain.toPosition(-120), 1e-6f)
        assertEquals(1f, gain.toPosition(120), 1e-6f)
    }

    @Test
    fun thePreampSliderReachesEveryStorableValue() {
        assertEquals(EqSettings.PREAMP_RANGE.first, preamp.fromPosition(0f))
        assertEquals(EqSettings.PREAMP_RANGE.last, preamp.fromPosition(1f))
        assertTrue("正のプリアンプに届かない", preamp.fromPosition(1f) > 0)
    }

    @Test
    fun gainSlidersRoundTripEveryStoredValue() {
        for (v in -120..120) assertEquals(v, gain.fromPosition(gain.toPosition(v)))
        for (v in EqSettings.PREAMP_RANGE) assertEquals(v, preamp.fromPosition(preamp.toPosition(v)))
    }

    @Test
    fun logScaleHitsBothEnds() {
        assertEquals(20, frequency.fromPosition(0f))
        assertEquals(20_000, frequency.fromPosition(1f))
        assertEquals(10, q.fromPosition(0f))
        assertEquals(1_000, q.fromPosition(1f))
    }

    @Test
    fun logScaleNeverGoesBackwards() {
        var previous = 0
        for (i in 0..1_000) {
            val value = frequency.fromPosition(i / 1_000f)
            assertTrue("位置 ${i / 1_000f} で $previous -> $value", value >= previous)
            previous = value
        }
    }

    @Test
    fun logScaleRoundsToThreeSignificantDigits() {
        for (i in 0..1_000) {
            val value = frequency.fromPosition(i / 1_000f)
            val unit = when {
                value < 1_000 -> 1
                value < 10_000 -> 10
                else -> 100
            }
            assertEquals("$value が $unit Hz 刻みでない", 0, value % unit)
        }
    }

    @Test
    fun logScaleGivesLowFrequenciesRoom() {
        val at200 = frequency.toPosition(200)
        assertEquals(1f / 3f, at200, 0.02f)
    }

    @Test
    fun rebandKeepsTheShapeOfTheCurve() {
        val before = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
                EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i < 5) 60 else -40)
            },
        )
        val after = reband(before, 31)
        assertEquals(31, after.bandCount)
        assertEquals(31, after.bands.size)
        for (hz in listOf(50.0, 100.0, 250.0, 1_000.0, 4_000.0, 12_000.0)) {
            val original = EqSolver.combinedResponseDb(before.bands, hz)
            val rebanded = EqSolver.combinedResponseDb(after.bands, hz)
            assertTrue(
                "$hz Hz で $original -> $rebanded",
                abs(original - rebanded) <= 3.0,
            )
        }
    }

    @Test
    fun flatKnobsSurviveEveryBandCountSwitch() {
        var s = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, EqSolver.centerFrequencies(10), EqSolver.defaultQ(10)),
        )
        for (count in listOf(5, 10, 31, 15, 5, 31, 10)) {
            s = reband(s, count)
            val knobs = EqSolver.graphicTargetsDb10(s.bands)
            assertTrue(
                "$count バンドへ切り替えたら摘みが ${knobs.toList()}",
                knobs.all { it == 120 },
            )
        }
    }

    @Test
    fun parametricShapesAreReadFromTheTrueCurve() {
        val shelf = EqBand(freqHz = 1_000, q100 = 70, gainDb10 = 60, type = EqBandType.HIGH_SHELF)
        val before = EqSettings(enabled = true, mode = EqMode.GRAPHIC, bandCount = 10, bands = listOf(shelf))
        val after = reband(before, 10)
        for (hz in EqSolver.centerFrequencies(10)) {
            val original = EqSolver.combinedResponseDb(listOf(shelf), hz.toDouble())
            val rebanded = EqSolver.combinedResponseDb(after.bands, hz.toDouble())
            assertEquals("$hz Hz", original, rebanded, 0.15)
        }
        assertTrue(
            "シェルフの高域が消えた: ${EqSolver.combinedResponseDb(after.bands, 16_000.0)}",
            EqSolver.combinedResponseDb(after.bands, 16_000.0) > 5.0,
        )
    }

    @Test
    fun rebandNormalisesTheBandCount() {
        val result = reband(EqSettings(enabled = true), 7)
        assertEquals(10, result.bandCount)
        assertEquals(10, result.bands.size)
    }

    @Test
    fun rebandFromNothingIsFlat() {
        val result = reband(EqSettings(enabled = true), 15)
        assertEquals(15, result.bands.size)
        assertTrue(result.bands.all { it.gainDb10 == 0 })
        assertEquals(EqSolver.centerFrequencies(15), result.bands.map { it.freqHz })
    }

    @Test
    fun graphicGridIsBuiltWhenBandsAreMissing() {
        val filled = withGraphicGrid(EqSettings(enabled = true, bandCount = 10))
        assertEquals(EqSolver.centerFrequencies(10), filled.bands.map { it.freqHz })
    }

    @Test
    fun graphicGridLeavesAMatchingGridAlone() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 35) },
        )
        assertEquals(settings, withGraphicGrid(settings))
    }

    @Test
    fun graphicGridLeavesParametricAlone() {
        val settings = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(105, 70, -65), EqBand(3_150, 141, 25)),
        )
        assertEquals(settings, withGraphicGrid(settings))
    }

    @Test
    fun toParametricKeepsACurveAsItIs() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 10,
            bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
                EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i < 5) 60 else -40)
            },
        )
        val after = toParametric(settings)
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertEquals(settings.bands, after.bands)
    }

    @Test
    fun toParametricKeepsEveryBandWhenOneIsMoved() {
        val bands = EqSolver.centerFrequencies(10).mapIndexed { i, hz ->
            EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i == 3) -5 else 0)
        }
        val after = toParametric(EqSettings(enabled = true, bandCount = 10, bands = bands))
        assertEquals(bands, after.bands)
    }

    @Test
    fun toParametricStartsFromThreeBandsWhenNothingWasMade() {
        val flat = withStartingBands(EqSettings(enabled = true, bandCount = 10))
        val after = toParametric(flat)
        assertEquals(listOf(100, 1_000, 10_000), after.bands.map { it.freqHz })
        assertTrue(after.bands.all { it.gainDb10 == 0 })
    }

    @Test
    fun startingBandsFillAnEmptyParametric() {
        val filled = withStartingBands(EqSettings(enabled = true, mode = EqMode.PARAMETRIC))
        assertEquals(listOf(100, 1_000, 10_000), filled.bands.map { it.freqHz })
    }

    @Test
    fun startingBandsLeaveSilentParametricBandsAlone() {
        val settings = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(105, 70, 0), EqBand(3_150, 141, 0)),
        )
        assertEquals(settings, withStartingBands(settings))
    }

    @Test
    fun startingBandsBuildTheGraphicGrid() {
        val filled = withStartingBands(EqSettings(enabled = true, bandCount = 15))
        assertEquals(EqSolver.centerFrequencies(15), filled.bands.map { it.freqHz })
    }

    @Test
    fun graphicGridRebuildsWhenTheCountDoesNotMatch() {
        val settings = EqSettings(
            enabled = true,
            bandCount = 31,
            bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 20) },
        )
        val fixed = withGraphicGrid(settings)
        assertNotEquals(settings, fixed)
        assertEquals(EqSolver.centerFrequencies(31), fixed.bands.map { it.freqHz })
    }

    @Test
    fun resetRebuildsAFlatGraphicGrid() {
        val curved = reband(
            EqSettings(enabled = true, bandCount = 10, preampDb10 = -45),
            10,
        ).let { it.copy(bands = EqSolver.withGraphicTarget(it.bands, 3, -80)) }
        val after = resetToZero(curved)
        assertEquals(withStartingBands(EqSettings(enabled = true, bandCount = 10)).bands, after.bands)
        assertTrue(EqSolver.graphicTargetsDb10(after.bands).all { it == 0 })
        assertEquals(0, after.preampDb10)
    }

    @Test
    fun resetKeepsParametricBandsWhereTheyAre() {
        val before = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(
                EqBand(105, 70, -65, EqBandType.LOW_SHELF),
                EqBand(3_150, 141, 25),
            ),
            preampDb10 = -120,
        )
        val after = resetToZero(before)
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertTrue(after.bands.all { it.gainDb10 == 0 })
        assertEquals(before.bands.map { it.copy(gainDb10 = 0) }, after.bands)
        assertEquals(0, after.preampDb10)
    }

    @Test
    fun resetZeroesThePreampWhicheverWayItWasSet() {
        for (preamp in listOf(-95, -400, 120)) {
            val after = resetToZero(
                EqSettings(
                    enabled = true,
                    mode = EqMode.PARAMETRIC,
                    bands = listOf(EqBand(1_000, 100, 80)),
                    preampDb10 = preamp,
                ),
            )
            assertEquals("$preamp から戻らなかった", 0, after.preampDb10)
        }
    }
}
