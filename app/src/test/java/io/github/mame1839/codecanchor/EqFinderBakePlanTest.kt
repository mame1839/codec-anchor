package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.eqFinderBakePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqFinderBakePlanTest {

    private val axes = EqFinderAxes.default(false)

    private val weights = EqLoudness.defaultWeights()

    private fun planOf(base: EqSettings, overlay: List<Int>) =
        eqFinderBakePlan(base, axes, overlay, weights, EqLoudness.baseLevelDb(base, weights))

    private val tenBandFreqs = listOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    private val bumpyGains = listOf(30, -20, 40, 0, -50, 20, 0, 10, -10, 20)

    private fun graphicBase(enabled: Boolean): EqSettings = EqSettings(
        enabled = enabled,
        mode = EqMode.GRAPHIC,
        bandCount = 10,
        bands = tenBandFreqs.mapIndexed { i, hz -> EqBand(hz, 50, bumpyGains[i]) },
    )

    private fun refOverlayBands(overlay: List<Int>) = listOf(
        EqBand(105, 71, overlay[0], EqBandType.LOW_SHELF),
        EqBand(2_500, 71, overlay[1], EqBandType.HIGH_SHELF),
    )

    private fun refHeardDb(base: EqSettings, overlay: List<Int>): DoubleArray =
        EqReferenceResponse.curveDb(
            (if (base.enabled) base.bands else emptyList()) + refOverlayBands(overlay),
        )

    @Test
    fun axisDefinitionsAreWhatTheReferenceAssumes() {
        assertEquals(2, axes.size)
        assertEquals(105, axes[0].freqHz)
        assertEquals(71, axes[0].q100)
        assertEquals(EqBandType.LOW_SHELF, axes[0].type)
        assertEquals(2_500, axes[1].freqHz)
        assertEquals(71, axes[1].q100)
        assertEquals(EqBandType.HIGH_SHELF, axes[1].type)
    }

    @Test
    fun flatKnobsSurviveBakingAtEveryBandCount() {
        val base = EqSettings(
            enabled = true,
            mode = EqMode.GRAPHIC,
            bandCount = 10,
            bands = EqSolver.solveBands(
                DoubleArray(10) { 12.0 },
                EqSolver.centerFrequencies(10),
                EqSolver.defaultQ(10),
            ),
        )
        val plan = planOf(base, listOf(0, 0))
        for (count in EqSettings.BAND_COUNTS) {
            val baked = plan[count]
            assertNotNull("$count バンドが焼けていない", baked)
            val knobs = EqSolver.graphicTargetsDb10(baked!!.settings.bands)
            assertTrue(
                "$count バンドで摘みが +12 から崩れた: ${knobs.toList()}",
                knobs.all { it == 120 },
            )
        }
    }

    @Test
    fun theKnobPolylineIsCarriedToEveryBandCount() {
        val base = graphicBase(enabled = true)
        val knobPoints = tenBandFreqs.map { hz ->
            hz.toDouble() to EqReferenceResponse.combinedDb(base.bands, hz.toDouble())
        }
        val plan = planOf(base, listOf(0, 0))
        for (count in EqSettings.BAND_COUNTS) {
            val bands = plan[count]!!.settings.bands
            for (band in bands) {
                val hz = band.freqHz.toDouble()
                assertEquals(
                    "$count バンド $hz Hz: 摘みが折れ線から外れた (残差の焼き込み)",
                    EqReferenceResponse.interpolateLog(knobPoints, hz),
                    EqReferenceResponse.combinedDb(bands, hz),
                    0.15,
                )
            }
        }
    }

    @Test
    fun bakedBandsSitOnTheChosenGridWithMatchingCount() {
        val plan = planOf(graphicBase(enabled = true), listOf(35, -20))
        assertEquals(EqSettings.BAND_COUNTS.toSet(), plan.keys)
        for (count in EqSettings.BAND_COUNTS) {
            val settings = plan[count]!!.settings
            assertEquals("bandCount が並びと食い違う", count, settings.bandCount)
            assertEquals(EqSolver.centerFrequencies(count), settings.bands.map { it.freqHz })
        }
    }

    @Test
    fun theAppliedResponseStaysWithinTheMeasuredErrorOfTheAudition() {
        val limitDb = mapOf(5 to 4.0, 10 to 1.2, 15 to 1.5, 31 to 0.8)
        val overlay = listOf(35, -20)
        for (enabled in listOf(true, false)) {
            val base = graphicBase(enabled)
            val heard = refHeardDb(base, overlay)
            val plan = planOf(base, overlay)
            for (count in EqSettings.BAND_COUNTS) {
                val baked = plan[count]!!
                val got = EqReferenceResponse.curveDb(baked.settings.bands)
                val worst = heard.indices.maxOf { abs(heard[it] - got[it]) }
                assertEquals(
                    "enabled=$enabled count=$count: 副題の忠実度が実際の乖離と違う",
                    worst,
                    baked.maxErrorDb,
                    1e-9,
                )
                assertTrue(
                    "enabled=$enabled count=$count: 乖離 $worst dB が上限 ${limitDb[count]} dB を超えた",
                    worst <= limitDb.getValue(count),
                )
            }
            val same = plan[base.bandCount]!!.settings
            for (hz in tenBandFreqs) {
                assertEquals(
                    "enabled=$enabled $hz Hz",
                    EqReferenceResponse.combinedDb(
                        (if (enabled) base.bands else emptyList()) + refOverlayBands(overlay),
                        hz.toDouble(),
                    ),
                    EqReferenceResponse.combinedDb(same.bands, hz.toDouble()),
                    0.2,
                )
            }
        }
    }

    @Test
    fun parametricPlansMatchTheAuditionExactly() {
        val bands = listOf(
            EqBand(freqHz = 200, q100 = 141, gainDb10 = 25),
            EqBand(freqHz = 4_000, q100 = 200, gainDb10 = -30),
        )
        val overlay = listOf(40, -20)
        for (enabled in listOf(true, false)) {
            val base = EqSettings(enabled = enabled, mode = EqMode.PARAMETRIC, bands = bands)
            val plan = planOf(base, overlay)
            assertEquals(setOf(base.bandCount), plan.keys)
            val baked = plan[base.bandCount]!!
            assertEquals(0.0, baked.maxErrorDb, 0.0)
            val heard = EqReferenceResponse.curveDb(
                (if (enabled) bands else emptyList()) + refOverlayBands(overlay),
            )
            val got = EqReferenceResponse.curveDb(baked.settings.bands)
            heard.indices.forEach { assertEquals(heard[it], got[it], 1e-12) }
        }
    }

    @Test
    fun everyBandCountBakesTheHeardPreamp() {
        val bare = EqSettings(enabled = false, mode = EqMode.GRAPHIC, bandCount = 10)
        val plan = planOf(bare, listOf(40, 0))
        for (count in EqSettings.BAND_COUNTS) {
            assertEquals("$count バンド", -4, plan[count]!!.settings.preampDb10)
        }

        val bumpyPlan = planOf(graphicBase(enabled = true), listOf(35, -20))
        for (count in EqSettings.BAND_COUNTS) {
            assertEquals("$count バンド (非平坦な土台)", 5, bumpyPlan[count]!!.settings.preampDb10)
        }

        val on = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(
                EqBand(105, 71, 45, EqBandType.LOW_SHELF),
                EqBand(2_500, 71, -25, EqBandType.HIGH_SHELF),
            ),
            preampDb10 = -60,
        )
        assertEquals(-69, planOf(on, listOf(40, 0))[on.bandCount]!!.settings.preampDb10)
    }

    @Test
    fun anOverfullParametricPlanCarriesNullInsteadOfLying() {
        val base = EqSettings(
            enabled = false,
            mode = EqMode.PARAMETRIC,
            bands = List(31) { EqBand(freqHz = 50 + it * 100, q100 = 141, gainDb10 = 10) },
        )
        val plan = planOf(base, listOf(40, 0))
        assertEquals(setOf(base.bandCount), plan.keys)
        assertEquals(null, plan[base.bandCount])
    }
}
