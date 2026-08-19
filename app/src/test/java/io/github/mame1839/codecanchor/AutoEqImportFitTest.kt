package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class AutoEqImportFitTest {

    private val text: String =
        checkNotNull(javaClass.getResourceAsStream("/autoeq/DUNU Titan S GraphicEQ.txt")) {
            "テストリソースが無い"
        }.bufferedReader().use { it.readText() }

    private val points: List<Pair<Double, Double>> =
        text.substringAfter(':').split(';').map { entry ->
            val (hz, db) = entry.trim().split(Regex("\\s+"))
            hz.toDouble() to db.toDouble()
        }.sortedBy { it.first }

    private val evalHz = DoubleArray(2000) { i ->
        exp(ln(20.0) + (ln(20_000.0) - ln(20.0)) * i / 1999.0)
    }

    private fun realizedErrorDb(bandCount: Int): Pair<Double, Double> {
        val r = AutoEqParser.parse(text, bandCount) as AutoEqResult.Ok
        assertEquals(EqMode.GRAPHIC, r.settings.mode)
        assertEquals(bandCount, r.settings.bands.size)
        val preamp = r.settings.preampDb10.toDouble() / EqUnits.GAIN_SCALE
        var maxFull = 0.0
        var max10k = 0.0
        for (hz in evalHz) {
            val realized = EqSolver.combinedResponseDb(r.settings.bands, hz) + preamp
            val err = abs(realized - AutoEqParser.interpolate(points, hz))
            if (err > maxFull) maxFull = err
            if (hz <= 10_000.0 && err > max10k) max10k = err
        }
        return maxFull to max10k
    }

    @Test
    fun thirtyOneBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(31)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 1.8)
        assertTrue("<=10 kHz: $low dB", low <= 0.5)
    }

    @Test
    fun fifteenBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(15)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 7.5)
        assertTrue("<=10 kHz: $low dB", low <= 0.85)
    }

    @Test
    fun tenBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(10)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 4.3)
        assertTrue("<=10 kHz: $low dB", low <= 3.1)
    }

    @Test
    fun fiveBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(5)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 9.4)
        assertTrue("<=10 kHz: $low dB", low <= 5.6)
    }

    @Test
    fun aRealPresetDoesNotEscalateTheQ() {
        val r = AutoEqParser.parse(text, 31) as AutoEqResult.Ok
        val defaultQ100 = (EqSolver.defaultQ(31) * EqUnits.Q_SCALE).toInt()
        assertTrue(r.settings.bands.all { it.q100 == defaultQ100 })
    }

    @Test
    fun preampCarriesTheCurveOffsetOnly() {
        val r = AutoEqParser.parse(text, 31) as AutoEqResult.Ok
        assertTrue("preampDb10=${r.settings.preampDb10}", r.settings.preampDb10 in -90..-50)
    }
}
