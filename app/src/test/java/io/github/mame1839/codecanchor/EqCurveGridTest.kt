package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqCurveGrid
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import io.github.mame1839.codecanchor.ui.centreGainsDb10
import io.github.mame1839.codecanchor.ui.graphicResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class EqCurveGridTest {

    @Test
    fun theGridMatchesTheCppHeader() {
        val header = readCppFile("app/src/main/cpp/dsp/ca_eq_curve.h")
        assertEquals(
            "kCurvePoints が Kotlin と食い違う",
            EqCurveGrid.POINTS,
            constantInt(header, "kCurvePoints"),
        )
        assertEquals(
            "kCurveMinHz が Kotlin と食い違う",
            EqCurveGrid.MIN_HZ,
            constantDouble(header, "kCurveMinHz"),
            0.0,
        )
        assertEquals(
            "kCurveMaxHz が Kotlin と食い違う",
            EqCurveGrid.MAX_HZ,
            constantDouble(header, "kCurveMaxHz"),
            0.0,
        )
        assertEquals(
            "kCurveMaxAbsDb が Kotlin と食い違う",
            EqCurveGrid.MAX_ABS_DB,
            constantDouble(header, "kCurveMaxAbsDb"),
            0.0,
        )
    }

    @Test
    fun theSharedMemorySlotHasRoomForExactlyThisManyPoints() {
        val shm = readCppFile("app/src/main/cpp/ca_eq_shm.h")

        assertTrue(
            "curve_db の長さが caeq::kCurvePoints から引かれていない (点数が 2 箇所になる)",
            Regex("""float\s+curve_db\[\s*caeq::kCurvePoints\s*]""").containsMatchIn(shm),
        )
        assertTrue(
            "ca_shm_t.curve_points が caeq::kCurvePoints から書かれていない",
            Regex("""curve_points\s*=\s*static_cast<uint32_t>\(caeq::kCurvePoints\)""")
                .containsMatchIn(readCppFile("app/src/main/cpp/ca_eq.cpp")),
        )

        val slotBytes = defineInt(shm, "CA_EQ_PARAM_SLOT_BYTES")
        val pad = Regex("""uint8_t\s+pad\[(\d+)];\s*\n}\s*ca_eq_slot_t;""")
            .find(shm)?.groupValues?.get(1)?.toInt()
        assertNotNull("ca_eq_slot_t の pad が固定長で見つからない", pad)
        val curveGenOffset = Regex("""offsetof\(ca_eq_slot_t,\s*curve_gen\)\s*==\s*(\d+)""")
            .find(shm)?.groupValues?.get(1)?.toInt()
        assertNotNull("curve_gen のオフセットの釘が見つからない", curveGenOffset)

        assertEquals(
            "枠の並びと格子の点数が食い違う",
            slotBytes,
            curveGenOffset!! + 4 + 4 * EqCurveGrid.POINTS + pad!!,
        )
    }

    @Test
    fun theCurveFileHasOneLinePerGridPoint() {
        val lines = EqCurveGrid.encode(EqCurveGrid.graphicCurveDb(tilted())).trimEnd('\n').split("\n")
        assertEquals(EqCurveGrid.POINTS, lines.size)
        assertTrue("行に周波数を書かない", lines.all { Regex("""^-?\d+\.\d\d$""").matches(it) })
    }

    @Test
    fun theCurveFileDoesNotFollowTheLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertFalse(EqCurveGrid.encode(EqCurveGrid.graphicCurveDb(tilted())).contains(','))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun theEndsSitExactlyOnTheGrid() {
        assertEquals(EqCurveGrid.MIN_HZ, EqCurveGrid.hzAt(0), 1e-9)
        assertEquals(EqCurveGrid.MAX_HZ, EqCurveGrid.hzAt(EqCurveGrid.POINTS - 1), 1e-6)
        assertEquals(EqCurveGrid.POINTS, EqCurveGrid.HZ.size)
    }

    @Test
    fun nearestIndexRoundsToTheClosestPoint() {
        for (i in listOf(0, 1, 200, EqCurveGrid.POINTS - 1)) {
            assertEquals(i, EqCurveGrid.nearestIndex(EqCurveGrid.hzAt(i)))
        }
        assertEquals(0, EqCurveGrid.nearestIndex(1.0))
        assertEquals(EqCurveGrid.POINTS - 1, EqCurveGrid.nearestIndex(96_000.0))
    }

    @Test
    fun snappingMovesAVertexByAtMostHalfAStep() {
        val step = ln(EqCurveGrid.MAX_HZ / EqCurveGrid.MIN_HZ) / (EqCurveGrid.POINTS - 1)
        for (hz in EqSolver.centerFrequencies(31).map { it.toDouble() }) {
            val moved = abs(ln(EqCurveGrid.snapHz(hz) / hz))
            assertTrue("$hz Hz のずれ $moved", moved <= step / 2 + 1e-12)
        }
    }

    @Test
    fun theCurvePassesThroughEveryKnobExactly() {
        val bands = tilted()
        val curve = EqCurveGrid.graphicCurveDb(bands)
        val knobs = EqSolver.graphicTargetsDb10(bands)
        bands.forEachIndexed { i, band ->
            val at = curve[EqCurveGrid.nearestIndex(band.freqHz.toDouble())]
            assertEquals(
                "${band.freqHz} Hz の摘み",
                knobs[i].toDouble() / EqUnits.GAIN_SCALE,
                at,
                1e-9,
            )
        }
    }

    @Test
    fun withoutSnappingTheZigzagPeaksWouldBeCutOff() {
        val bands = zigzag()
        val knobs = EqSolver.graphicTargetsDb10(bands)
        val raw = bands.mapIndexed { i, b ->
            b.freqHz.toDouble() to knobs[i].toDouble() / EqUnits.GAIN_SCALE
        }
        var worst = 0.0
        bands.forEachIndexed { i, band ->
            val sampled = AutoEqParser.interpolate(
                raw,
                EqCurveGrid.hzAt(EqCurveGrid.nearestIndex(band.freqHz.toDouble())),
            )
            worst = maxOf(worst, abs(sampled - knobs[i].toDouble() / EqUnits.GAIN_SCALE))
        }
        assertTrue("吸着なしの丸まりが $worst dB しかない (この形では効き目が測れない)", worst > 0.5)

        val curve = EqCurveGrid.graphicCurveDb(bands)
        bands.forEachIndexed { i, band ->
            assertEquals(
                knobs[i].toDouble() / EqUnits.GAIN_SCALE,
                curve[EqCurveGrid.nearestIndex(band.freqHz.toDouble())],
                1e-9,
            )
        }
    }

    @Test
    fun theCurveIsFlatOutsideTheBandRange() {
        val bands = tilted()
        val curve = EqCurveGrid.graphicCurveDb(bands)
        val knobs = EqSolver.graphicTargetsDb10(bands)
        val lowest = EqCurveGrid.nearestIndex(bands.first().freqHz.toDouble())
        val highest = EqCurveGrid.nearestIndex(bands.last().freqHz.toDouble())
        for (i in 0..lowest) {
            assertEquals(knobs.first().toDouble() / EqUnits.GAIN_SCALE, curve[i], 1e-9)
        }
        for (i in highest until EqCurveGrid.POINTS) {
            assertEquals(knobs.last().toDouble() / EqUnits.GAIN_SCALE, curve[i], 1e-9)
        }
    }

    @Test
    fun everyCurveWeSendPassesTheSameCheckAsTheSo() {
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(tilted())))
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(zigzag())))
        assertTrue(EqCurveGrid.valid(EqCurveGrid.graphicCurveDb(emptyList())))
        assertFalse(EqCurveGrid.valid(DoubleArray(EqCurveGrid.POINTS) { Double.NaN }))
        assertFalse(EqCurveGrid.valid(DoubleArray(EqCurveGrid.POINTS - 1)))
        assertFalse(
            EqCurveGrid.valid(
                DoubleArray(EqCurveGrid.POINTS).also { it[7] = EqCurveGrid.MAX_ABS_DB + 0.1 },
            ),
        )
    }

    @Test
    fun extremeBandsAreClampedIntoTheAllowedRange() {
        val loud = EqSolver.centerFrequencies(31).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 400) }
        val curve = EqCurveGrid.graphicCurveDb(loud)
        assertTrue(EqCurveGrid.valid(curve))
        assertTrue("上限まで持ち上がっている", curve.max() >= EqCurveGrid.MAX_ABS_DB - 1e-9)
    }

    @Test
    fun theHighPrecisionPlotDrawsExactlyWhatWeSend() {
        for (bands in listOf(tilted(), zigzag())) {
            val drawn = graphicResponse(bands, EqPrecision.HIGH)
            val vertices = EqCurveGrid.knobPolyline(bands)
            val last = bands.size - 1
            val perBand = (drawn.size - 1) / last
            for (s in drawn.indices) {
                val i = (s / perBand).coerceAtMost(last - 1)
                val t = (s - i * perBand).toDouble() / perBand
                val hz = exp(ln(vertices[i].first) * (1 - t) + ln(vertices[i + 1].first) * t)
                assertEquals("標本 $s", AutoEqParser.interpolate(vertices, hz), drawn[s], 1e-12)
            }
        }
    }

    @Test
    fun theKnobDotsDoNotMoveBetweenTheTwoModes() {
        for (bands in listOf(tilted(), zigzag())) {
            val standard = centreGainsDb10(graphicResponse(bands, EqPrecision.STANDARD), bands.size)
            val high = centreGainsDb10(graphicResponse(bands, EqPrecision.HIGH), bands.size)
            assertEquals(EqSolver.graphicTargetsDb10(bands).toList(), standard.toList())
            assertEquals(standard.toList(), high.toList())
        }
    }

    @Test
    fun theStandardPlotStillDrawsTheBiquadResponse() {
        val bands = tilted()
        val drawn = graphicResponse(bands, EqPrecision.STANDARD)
        val last = bands.size - 1
        val perBand = (drawn.size - 1) / last
        val lnFreqs = bands.map { ln(it.freqHz.toDouble()) }
        for (s in drawn.indices) {
            val i = (s / perBand).coerceAtMost(last - 1)
            val t = (s - i * perBand).toDouble() / perBand
            val hz = exp(lnFreqs[i] * (1 - t) + lnFreqs[i + 1] * t)
            assertEquals("標本 $s", EqSolver.combinedResponseDb(bands, hz), drawn[s], 1e-12)
        }
        assertEquals(drawn.toList(), graphicResponse(bands).toList())
    }

    private fun tilted(): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(10)
        val target = DoubleArray(freqs.size) { -6.0 + 12.0 * it / (freqs.size - 1) }
        return EqSolver.solveBands(target, freqs, EqSolver.defaultQ(freqs.size))
    }

    private fun zigzag(): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 12.0 else -12.0 }
        return EqSolver.solveBands(target, freqs, EqSolver.defaultQ(freqs.size))
    }

    private companion object {

        fun readCppFile(relative: String): String {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val f = File(dir, relative)
                if (f.isFile) return f.readText()
                dir = dir.parentFile
            }
            throw AssertionError("$relative が見つからない (user.dir=${System.getProperty("user.dir")})")
        }

        fun constantInt(src: String, name: String): Int =
            Regex("""\b$name\s*=\s*(-?\d+)\s*;""").find(src)?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("$name が読めない")

        fun constantDouble(src: String, name: String): Double =
            Regex("""\b$name\s*=\s*(-?[\d.]+)f?\s*;""").find(src)?.groupValues?.get(1)?.toDouble()
                ?: throw AssertionError("$name が読めない")

        fun defineInt(src: String, name: String): Int =
            Regex("""#define\s+$name\s+(\d+)""").find(src)?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("$name が読めない")
    }
}
