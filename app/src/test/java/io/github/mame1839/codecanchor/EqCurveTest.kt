package io.github.mame1839.codecanchor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.AXIS_HI_HZ
import io.github.mame1839.codecanchor.ui.AXIS_LO_HZ
import io.github.mame1839.codecanchor.ui.EqField
import io.github.mame1839.codecanchor.ui.EqPreviewTarget
import io.github.mame1839.codecanchor.ui.PLOT_OVERSHOOT_DB
import io.github.mame1839.codecanchor.ui.PLOT_RANGES_DB
import io.github.mame1839.codecanchor.ui.SAMPLES
import io.github.mame1839.codecanchor.ui.centreGainsDb10
import io.github.mame1839.codecanchor.ui.drawGraphicPlot
import io.github.mame1839.codecanchor.ui.drawParametricPlot
import io.github.mame1839.codecanchor.ui.eqFrequencyShort
import io.github.mame1839.codecanchor.ui.eqGainNumber
import io.github.mame1839.codecanchor.ui.eqGainTick
import io.github.mame1839.codecanchor.ui.evenColumns
import io.github.mame1839.codecanchor.ui.graphicPlotRange
import io.github.mame1839.codecanchor.ui.graphicResponse
import io.github.mame1839.codecanchor.ui.gridValues
import io.github.mame1839.codecanchor.ui.holdRange
import io.github.mame1839.codecanchor.ui.parametricPlotRange
import io.github.mame1839.codecanchor.ui.parametricResponse
import io.github.mame1839.codecanchor.ui.pickLabels
import io.github.mame1839.codecanchor.ui.plotFraction
import io.github.mame1839.codecanchor.ui.plotRange
import io.github.mame1839.codecanchor.ui.rememberPlotRange
import io.github.mame1839.codecanchor.ui.sumColumns
import io.github.mame1839.codecanchor.ui.withPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqCurveTest {

    private fun flatBands(count: Int, gainDb10: Int, q100: Int = (EqSolver.defaultQ(count) * 100).toInt()) =
        EqSolver.centerFrequencies(count).map { EqBand(freqHz = it, q100 = q100, gainDb10 = gainDb10) }

    @Test
    fun curveShowsWhatIsHeardNotWhatWasTyped() {
        val bands = flatBands(10, 60)
        val peak = graphicResponse(bands).max()
        assertTrue("期待は約 17.31 dB、実際は $peak", peak in 16.8..17.8)
        assertNotEquals("バンドのゲインをそのまま描いている", 6.0, peak, 0.5)
    }

    @Test
    fun curveMatchesTheReferenceImplementation() {
        val freqs = EqSolver.centerFrequencies(10)

        val flat = flatBands(10, 60)
        val flatExpected = doubleArrayOf(
            11.8561, 15.6468, 16.9741, 17.2894, 17.3096, 17.1641, 16.7186, 15.6155, 13.2752, 8.9665,
        )
        assertCurveAt(flat, freqs, flatExpected)
        assertEquals("帯域内のピーク", 17.3137, graphicResponse(flat).max(), 5e-3)

        val sliders = intArrayOf(80, 70, 50, 30, 10, 0, -20, -40, -60, -60)
        val mixed = freqs.mapIndexed { i, hz -> EqBand(freqHz = hz, q100 = 100, gainDb10 = sliders[i]) }
        val mixedExpected = doubleArrayOf(
            10.7088, 11.4430, 8.8549, 5.4808, 2.2183, -0.3329, -3.4378, -6.3146, -7.9309, -6.8905,
        )
        assertCurveAt(mixed, freqs, mixedExpected)
    }

    @Test
    fun aLoneBandMeetsItsOwnGainExactly() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 100, gainDb10 = if (it == 1_000) 120 else 0) }
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        assertEquals(12.0, response[freqs.indexOf(1_000) * step], 1e-9)
        assertEquals(12.0, response.max(), 1e-9)
        assertEquals(3.9558, response[freqs.indexOf(500) * step], 1e-3)
        assertEquals(3.9307, response[freqs.indexOf(2_000) * step], 1e-3)
    }

    private fun assertCurveAt(bands: List<EqBand>, freqs: List<Int>, expected: DoubleArray) {
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        freqs.indices.forEach { i ->
            assertEquals("${freqs[i]} Hz", expected[i], response[i * step], 1e-3)
        }
    }

    @Test
    fun solvedBandsMakeTheCurveMeetTheTarget() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = EqSolver.solveBands(DoubleArray(freqs.size) { 6.0 }, freqs, EqSolver.defaultQ(10))
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        freqs.indices.forEach { i ->
            assertEquals("バンド ${freqs[i]} Hz", 6.0, response[i * step], 0.05)
        }
    }

    @Test
    fun samplesLandExactlyOnBandCentres() {
        for (n in listOf(5, 10, 15, 31)) {
            val bands = flatBands(n, 40)
            val response = graphicResponse(bands)
            assertTrue("標本が足りない (n=$n)", response.size >= SAMPLES)
            assertEquals("バンド間が割り切れていない (n=$n)", 0, (response.size - 1) % (n - 1))
            val step = (response.size - 1) / (n - 1)
            bands.forEachIndexed { i, band ->
                val expected = EqSolver.combinedResponseDb(bands, band.freqHz.toDouble())
                assertEquals("n=$n バンド ${band.freqHz} Hz", expected, response[i * step], 1e-9)
            }
        }
    }

    @Test
    fun curveStartsAndEndsOnTheOuterBands() {
        val bands = flatBands(10, 60)
        val response = graphicResponse(bands)
        assertEquals(EqSolver.combinedResponseDb(bands, bands.first().freqHz.toDouble()), response.first(), 1e-9)
        assertEquals(EqSolver.combinedResponseDb(bands, bands.last().freqHz.toDouble()), response.last(), 1e-9)
    }

    @Test
    fun perBandCurvesAddUpToTheCompositeCurve() {
        val bands = listOf(
            EqBand(freqHz = 105, q100 = 70, gainDb10 = -65, type = 1),
            EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 60),
            EqBand(freqHz = 6_300, q100 = 242, gainDb10 = 40),
            EqBand(freqHz = 12_000, q100 = 70, gainDb10 = 55, type = 2),
        )
        val composite = sumColumns(parametricResponse(bands))
        val span = ln(AXIS_HI_HZ / AXIS_LO_HZ)
        composite.indices.forEach { i ->
            val hz = exp(ln(AXIS_LO_HZ) + span * i / (SAMPLES - 1))
            assertEquals(EqSolver.combinedResponseDb(bands, hz), composite[i], 1e-9)
        }
    }

    @Test
    fun emptyAndSingleBandStayDrawable() {
        assertTrue(sumColumns(parametricResponse(emptyList())).all { it == 0.0 })
        val one = graphicResponse(listOf(EqBand(1_000, 141, 60)))
        assertTrue("1 点だと描画側で 0 除算になる", one.size >= 2)
        assertTrue(one.all { it.isFinite() })
    }

    @Test
    fun axisStaysAtTheSliderRangeWhenNothingExceedsIt() {
        assertEquals(12.0, plotRange(knobPeakDb = 0.0, curvePeakDb = 0.0), 0.0)
        assertEquals(12.0, plotRange(knobPeakDb = 12.0, curvePeakDb = 11.9), 0.0)
    }

    @Test
    fun fullTiltKnobsUseTheTwelveDbStep() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val q = EqSolver.defaultQ(n)
            val shapes = listOf(
                DoubleArray(n) { 12.0 },
                DoubleArray(n) { -12.0 },
                DoubleArray(n) { if (it % 2 == 0) 12.0 else -12.0 },
                DoubleArray(n) { if (it == n / 2 - 1 || it == n / 2) 12.0 else 0.0 },
            )
            for (targets in shapes) {
                val response = graphicResponse(EqSolver.solveBands(targets, freqs, q))
                assertEquals(
                    "n=$n targets=${targets.toList()}",
                    12.0,
                    graphicPlotRange(response, n),
                    0.0,
                )
                val peak = response.maxOf { abs(it) }
                assertTrue("膨らみが枠から出る: $peak", peak <= 12.0 + PLOT_OVERSHOOT_DB)
            }
        }
    }

    @Test
    fun axisGrowsInStepsToHoldTheCurve() {
        val bands = flatBands(10, 120)
        val response = graphicResponse(bands)
        val range = graphicPlotRange(response, bands.size)
        assertTrue(
            "曲線が枠から出ている: ${response.max()} > $range + $PLOT_OVERSHOOT_DB",
            response.max() <= range + PLOT_OVERSHOOT_DB,
        )
        assertTrue("段以外の値が出た", range in PLOT_RANGES_DB)
        assertTrue("35 dB の曲線が段 12 に居座っている", range > 12.0)
    }

    @Test
    fun axisHasATopStep() {
        val bands = listOf(EqBand(1_000, 141, 400))
        assertEquals(PLOT_RANGES_DB.last(), parametricPlotRange(doubleArrayOf(999.0), bands), 0.0)
    }

    @Test
    fun theAxisIgnoresInvisibleSolverGains() {
        val freqs = EqSolver.centerFrequencies(5)
        val q = EqSolver.defaultQ(5)
        fun bandsAt(x: Double) =
            EqSolver.solveBands(doubleArrayOf(12.0, 12.0, x, 12.0, 12.0), freqs, q)

        val before = bandsAt(-8.3)
        val after = bandsAt(-8.4)
        val gainBefore = before.maxOf { abs(it.gainDb10) }
        val gainAfter = after.maxOf { abs(it.gainDb10) }
        assertTrue("前提が崩れた: |g|max=$gainBefore (18 dB 超のはず)", gainBefore > 180)
        assertTrue("前提が崩れた: |g|max=$gainAfter (18 dB 未満のはず)", gainAfter < 180)

        assertEquals(12.0, graphicPlotRange(graphicResponse(before), 5), 0.0)
        assertEquals(12.0, graphicPlotRange(graphicResponse(after), 5), 0.0)

        for (x10 in 0 downTo -120) {
            assertEquals(
                "x=${x10 / 10.0} で軸が跳ねた",
                12.0,
                graphicPlotRange(graphicResponse(bandsAt(x10 / 10.0)), 5),
                0.0,
            )
        }
    }

    @Test
    fun theAxisGrowsExactlyWhenInkWouldLeaveTheFrame() {
        val edge = 12.0 + PLOT_OVERSHOOT_DB
        assertEquals(12.0, plotRange(knobPeakDb = 12.0, curvePeakDb = edge), 0.0)
        assertEquals("枠のちょうど上端に写るはず", 0.0, plotFraction(edge, 12.0).toDouble(), 1e-6)
        assertEquals("枠のちょうど下端に写るはず", 1.0, plotFraction(-edge, 12.0).toDouble(), 1e-6)
        assertEquals(18.0, plotRange(knobPeakDb = 12.0, curvePeakDb = edge + 0.01), 0.0)

        assertEquals(18.0, plotRange(knobPeakDb = 12.1, curvePeakDb = 0.0), 0.0)

        val framed = EqSolver.solveBands(
            doubleArrayOf(-12.0, 12.0, 12.0, -12.0, 0.0),
            EqSolver.centerFrequencies(5),
            EqSolver.defaultQ(5),
        )
        val response = graphicResponse(framed)
        assertTrue("前提: この形は許容を超えて膨らむ (実測 15.01 dB)", response.max() > edge)
        assertEquals(18.0, graphicPlotRange(response, 5), 0.0)
    }

    private fun stackedPair(draggedDb10: Int): List<EqBand> =
        listOf(EqBand(1_000, 141, 120), EqBand(1_000, 141, draggedDb10))

    private fun parametricRangeOf(bands: List<EqBand>): Double =
        parametricPlotRange(sumColumns(parametricResponse(bands)), bands)

    @Test
    fun theAxisIsHeldWhileAKnobIsMoving() {
        val before = stackedPair(90)
        val after = stackedPair(85)
        val resting = parametricRangeOf(before)
        val shrunk = parametricRangeOf(after)

        assertEquals("触る前の段", 24.0, resting, 0.0)
        assertEquals("0.5 dB 下げたときの段", 18.0, shrunk, 0.0)

        val was = plotFraction(9.0, resting)
        assertTrue(
            "段を固定しないと点が上がる (これが症状): $was -> ${plotFraction(8.5, shrunk)}",
            plotFraction(8.5, shrunk) < was,
        )
        val held = holdRange(shrunk, resting, dragging = true)
        assertTrue(
            "段を固定したのに点が上がった: $was -> ${plotFraction(8.5, held)}",
            plotFraction(8.5, held) > was,
        )
        assertTrue(
            "触っていない点まで上がる (これも症状)",
            plotFraction(12.0, shrunk) < plotFraction(12.0, resting),
        )

        assertEquals(shrunk, holdRange(shrunk, resting, dragging = false), 0.0)
    }

    @Test
    fun noAxisShrinkMovesThePointAgainstTheFinger() {
        var shrinks = 0
        var rises = 0
        var risesWhileHeld = 0
        for (fc in EqSolver.centerFrequencies(10)) {
            for (g in 10..120 step 5) {
                val base = listOf(EqBand(fc, 141, 120), EqBand(fc, 141, g))
                val dragged = listOf(base[0], base[1].copy(gainDb10 = g - 5))
                val resting = parametricRangeOf(base)
                val shrunk = parametricRangeOf(dragged)
                if (shrunk >= resting) continue
                shrinks++
                val was = plotFraction(g / 10.0, resting)
                if (plotFraction((g - 5) / 10.0, shrunk) < was) rises++
                if (plotFraction((g - 5) / 10.0, holdRange(shrunk, resting, dragging = true)) < was) risesWhileHeld++
            }
        }
        assertTrue("段が落ちる組み合わせが 1 つも無い", shrinks > 0)
        assertTrue("段が落ちても点が上がらない = 症状が再現していない", rises > 0)
        assertEquals("段を固定したのに点が上がった", 0, risesWhileHeld)
    }

    @Test
    fun theAxisStillGrowsWhileAKnobIsMoving() {
        assertEquals(24.0, holdRange(computed = 24.0, held = 18.0, dragging = true), 0.0)
        assertEquals(40.0, holdRange(computed = 40.0, held = 12.0, dragging = true), 0.0)
    }

    @Test
    fun everyLabelIsKeptWhenTheyAllFit() {
        val xs = evenColumns(10, 0f, 1_000f)
        val shown = pickLabels(xs, FloatArray(10) { 40f }, 8f, -1)
        assertTrue("10 個なら全部出るはず", shown.all { it })
    }

    @Test
    fun crowdedLabelsAreThinnedWithoutOverlap() {
        val xs = evenColumns(31, 0f, 1_000f)
        val widths = FloatArray(31) { 40f }
        val shown = pickLabels(xs, widths, 8f, -1)
        assertTrue("31 個が素通しになっている", shown.count { it } < 31)
        assertTrue(shown.count { it } >= 5)
        val kept = xs.indices.filter { shown[it] }
        kept.zipWithNext { a, b ->
            assertTrue("$a と $b が重なっている", xs[b] - xs[a] >= (widths[a] + widths[b]) / 2f + 8f)
        }
        assertTrue("両端が消えている", shown.first() && shown.last())
    }

    @Test
    fun theBandBeingDraggedKeepsItsLabel() {
        val xs = evenColumns(31, 0f, 1_000f)
        val widths = FloatArray(31) { 40f }
        assertFalse("前提が崩れている: 17 は本来間引かれる", pickLabels(xs, widths, 8f, -1)[17])
        val shown = pickLabels(xs, widths, 8f, 17)
        assertTrue(shown[17])
        val kept = xs.indices.filter { shown[it] }
        kept.zipWithNext { a, b ->
            assertTrue("$a と $b が重なっている", xs[b] - xs[a] >= (widths[a] + widths[b]) / 2f + 8f)
        }
    }

    @Test
    fun columnsSpanTheWholePlot() {
        val xs = evenColumns(5, 10f, 110f)
        assertArrayEquals(floatArrayOf(10f, 35f, 60f, 85f, 110f), xs)
        assertArrayEquals(floatArrayOf(60f), evenColumns(1, 10f, 110f))
    }

    @Test
    fun previewTouchesOnlyTheBandAndFieldBeingDragged() {
        val bands = listOf(EqBand(100, 141, 20), EqBand(1_000, 141, 30), EqBand(8_000, 141, 40))
        val moved = bands.withPreview(EqPreviewTarget(1, EqField.FREQUENCY), 2_500)
        assertEquals(2_500, moved[1].freqHz)
        assertEquals(30, moved[1].gainDb10)
        assertEquals(141, moved[1].q100)
        assertEquals(bands[0], moved[0])
        assertEquals(bands[2], moved[2])

        assertEquals(80, bands.withPreview(EqPreviewTarget(2, EqField.GAIN), 80)[2].gainDb10)
        assertEquals(700, bands.withPreview(EqPreviewTarget(0, EqField.Q), 700)[0].q100)
    }

    @Test
    fun previewForAMissingBandIsIgnored() {
        val bands = listOf(EqBand(100, 141, 20))
        assertEquals(bands, bands.withPreview(EqPreviewTarget(5, EqField.GAIN), 100))
        assertEquals(bands, bands.withPreview(null, 100))
        assertEquals(bands, bands.withPreview(EqPreviewTarget(0, EqField.GAIN), null))
    }

    @Test
    fun theCurveFollowsTheDraggedValue() {
        val bands = flatBands(10, 0)
        val still = graphicResponse(bands).max()
        val dragged = graphicResponse(bands.withPreview(EqPreviewTarget(5, EqField.GAIN), 100)).max()
        assertEquals("触る前は平ら", 0.0, still, 1e-9)
        assertTrue("絵が追従していない: $dragged", dragged > 9.0)
    }

    @Test
    fun aGraphicPreviewSolvesTheTargetInsteadOfCopyingIt() {
        val bands = flatBands(10, 40)
        val preview = bands.withPreview(EqPreviewTarget(3, EqField.GRAPHIC_GAIN), 90)
        assertEquals("解いた結果と一致しない", EqSolver.withGraphicTarget(bands, 3, 90), preview)
        val copied = bands.mapIndexed { i, b -> if (i == 3) b.copy(gainDb10 = 90) else b }
        assertNotEquals("GAIN と同じ素朴な複写になっている", copied, preview)
    }

    private val density = Density(3f)
    private val measurer by lazy {
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)
    }

    private val accent = Color.Red
    private val muted = Color.Blue
    private val tickText = Color.Green
    private val bandText = Color.Yellow

    private fun isGrid(c: Color) = c.blue > 0f && c.red == 0f && c.green == 0f
    private fun isTickText(c: Color) = c.green > 0f && c.red == 0f && c.blue == 0f
    private fun isAccent(c: Color) = c.red > 0f && c.green == 0f

    private inner class Painted(val width: Int, val height: Int, block: DrawScope.() -> Unit) {
        private val pixels = ImageBitmap(width, height).also { bitmap ->
            CanvasDrawScope().draw(
                density,
                LayoutDirection.Ltr,
                Canvas(bitmap),
                Size(width.toFloat(), height.toFloat()),
                block,
            )
        }.toPixelMap()

        fun at(x: Int, y: Int): Color = pixels[x, y]

        fun runsDown(x: Int, hit: (Color) -> Boolean): List<IntRange> = runs { y -> hit(pixels[x, y]) }

        fun rowsWith(hit: (Color) -> Boolean): List<IntRange> =
            runs { y -> (0 until width).any { hit(pixels[it, y]) } }

        fun count(rows: IntRange, hit: (Color) -> Boolean): Int =
            rows.sumOf { y -> (0 until width).count { hit(pixels[it, y]) } }

        private fun runs(hit: (Int) -> Boolean): List<IntRange> {
            val out = mutableListOf<IntRange>()
            var start = -1
            for (y in 0 until height) {
                if (hit(y)) {
                    if (start < 0) start = y
                } else if (start >= 0) {
                    out += start until y
                    start = -1
                }
            }
            if (start >= 0) out += start until height
            return out
        }
    }

    private fun ticksFor(range: Double) = gridValues(range).map { db ->
        db to measurer.measure(eqGainTick(db.toInt()), TextStyle(fontSize = 10.sp, color = tickText))
    }

    private inner class GraphicScene(bands: List<EqBand>) {
        val response = graphicResponse(bands)
        val range = graphicPlotRange(response, bands.size)
        private val ticks = ticksFor(range)
        private val style = TextStyle(fontSize = 10.sp, color = bandText)
        private val gains = centreGainsDb10(response, bands.size).map { measurer.measure(eqGainNumber(it), style) }
        private val freqs = bands.map { measurer.measure(eqFrequencyShort(it.freqHz, "%s k"), style) }
        private val rowHeight = (gains + freqs).maxOf { it.size.height }.toFloat()

        private val gap = 6f * density.density
        private val inset = 10f * density.density
        val width = 984
        val height = ((132f + 6f * 2f) * density.density + rowHeight * 2f).toInt()
        val plotTop = rowHeight + gap
        val plotBottom = height - rowHeight - gap
        val plotLeft = ticks.maxOf { it.second.size.width } + gap
        val xs = evenColumns(bands.size, plotLeft + inset, width - inset)

        val painted = Painted(width, height) {
            drawGraphicPlot(bands, -1, response, range, ticks, gains, freqs, rowHeight, accent, muted)
        }

        fun yOf(db: Double): Float = plotTop + plotFraction(db, range) * (plotBottom - plotTop)
    }

    private fun paintGraphic(bands: List<EqBand>): Painted = GraphicScene(bands).painted

    private fun paintParametric(bands: List<EqBand>): Painted {
        val style = TextStyle(fontSize = 10.sp, color = bandText)
        val labels = listOf(20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000)
            .map { measurer.measure(eqFrequencyShort(it, "%s k"), style) }
        val rowHeight = labels.maxOf { it.size.height }.toFloat()
        val perBand = parametricResponse(bands)
        val composite = sumColumns(perBand)
        val range = parametricPlotRange(composite, bands)
        val height = (132f + 6f) * density.density + rowHeight * 2f
        return Painted(984, height.toInt()) {
            drawParametricPlot(
                bands, -1, perBand, composite, range, ticksFor(range),
                labels, rowHeight, accent, muted,
            )
        }
    }

    private fun Painted.zeroLine(): IntRange {
        val lines = runsDown(width - 1, ::isGrid)
        assertEquals("目盛り線の本数", 5, lines.size)
        return lines[2]
    }

    @Test
    fun theGraphicPlotDrawsEveryDbTickWithItsNumber() {
        val painted = paintGraphic(flatBands(10, 120))

        val lines = painted.runsDown(painted.width - 1, ::isGrid)
        assertEquals("目盛り線が ${lines.size} 本しか無い", gridValues(24.0).size, lines.size)

        val strength = lines.map { run -> run.maxOf { painted.at(painted.width - 1, it).alpha } }
        assertEquals("一番濃い線が真ん中 (0 dB) でない: $strength", 2, strength.indexOf(strength.max()))

        assertEquals("縦軸の数字の数", gridValues(24.0).size, painted.rowsWith(::isTickText).size)
    }

    @Test
    fun theParametricPlotDrawsTheSameDbTicks() {
        val painted = paintParametric(listOf(EqBand(1_000, 141, 120), EqBand(4_000, 141, -60)))
        assertEquals(gridValues(12.0).size, painted.rowsWith(::isTickText).size)
    }

    @Test
    fun theFillIsClosedAtZeroDbNotAtTheEdgeOfTheFrame() {
        val up = paintGraphic(flatBands(10, 120))
        val upZero = up.zeroLine()
        assertTrue("0 dB より上に絵が無い", up.count(0 until upZero.first - 2, ::isAccent) > 0)
        assertEquals(
            "0 dB より下に塗りか縦線が残っている",
            0,
            up.count(upZero.last + 3 until up.height, ::isAccent),
        )

        val down = paintGraphic(flatBands(10, -120))
        val downZero = down.zeroLine()
        assertEquals(
            "0 dB より上に塗りか縦線が残っている",
            0,
            down.count(0 until downZero.first - 2, ::isAccent),
        )
        assertTrue("0 dB より下に絵が無い", down.count(downZero.last + 3 until down.height, ::isAccent) > 0)
    }

    @Test
    fun aFullTiltPlotKeepsItsInkInsideTheFrame() {
        val freqs = EqSolver.centerFrequencies(10)
        val scene = GraphicScene(
            EqSolver.solveBands(DoubleArray(10) { 12.0 }, freqs, EqSolver.defaultQ(10)),
        )
        assertEquals("前提: 全 +12 は段 12", 12.0, scene.range, 0.0)

        val plotH = scene.plotBottom - scene.plotTop
        val lines = scene.painted.runsDown(scene.width - 1, ::isGrid)
        assertEquals("目盛り線の本数", 5, lines.size)
        assertTrue(
            "+12 の目盛りが枠の縁に張り付いている: y=${lines.first().first} 枠上端=${scene.plotTop}",
            lines.first().first >= scene.plotTop + plotH * 0.05f,
        )
        assertTrue(
            "-12 の目盛りが枠の縁に張り付いている: y=${lines.last().last} 枠下端=${scene.plotBottom}",
            lines.last().last <= scene.plotBottom - plotH * 0.05f,
        )

        assertEquals(
            "絵が枠の上へ食み出した",
            0,
            scene.painted.count(0 until scene.plotTop.toInt(), ::isAccent),
        )
        assertTrue(
            "膨らみ (+12.44 dB) が +12 の目盛りより上の余白に描かれていない",
            scene.painted.count(scene.plotTop.toInt() + 2 until lines.first().first, ::isAccent) > 0,
        )
    }

    @Test
    fun centreLabelsShowTheKnobValueNotTheFilterGain() {
        val freqs = EqSolver.centerFrequencies(10)
        val solved = EqSolver.solveBands(DoubleArray(freqs.size) { 12.0 }, freqs, EqSolver.defaultQ(10))
        val labels = centreGainsDb10(graphicResponse(solved), solved.size)
        assertTrue("ラベルが目標 +12.0 に一致しない: ${labels.toList()}", labels.all { it == 120 })
        assertNotEquals("フィルタのゲインをそのまま出している", solved.map { it.gainDb10 }, labels.toList())

        assertEquals(
            listOf(119, 156, 170, 173, 173, 172, 167, 156, 133, 90),
            centreGainsDb10(graphicResponse(flatBands(10, 60)), 10).toList(),
        )
    }

    @Test
    fun theDotsSitOnTheCurveNotAtTheFilterGains() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = EqSolver.solveBands(DoubleArray(freqs.size) { 12.0 }, freqs, EqSolver.defaultQ(10))
        val scene = GraphicScene(bands)
        val step = (scene.response.size - 1) / (bands.size - 1)
        val dotR = 3.5f * density.density

        var checked = 0
        bands.forEachIndexed { i, band ->
            val x = scene.xs[i].toInt()
            val centreY = scene.yOf(scene.response[i * step])
            assertTrue("バンド $i の中心 (曲線の上) に点が無い", hasOpaqueAccent(scene.painted, x, centreY, dotR + 2f))

            val gainY = scene.yOf(band.gainDb10 / 10.0)
            if (abs(gainY - centreY) < dotR * 2 + 8f) return@forEachIndexed
            checked++
            assertFalse(
                "バンド $i: フィルタのゲイン (${band.gainDb10 / 10.0} dB) の高さに点がある",
                hasOpaqueAccent(scene.painted, x, gainY, 4f),
            )
        }
        assertTrue("ゲインと中心が離れたバンドが少なすぎて、何も確かめていない: $checked", checked >= 5)
    }

    private fun hasOpaqueAccent(p: Painted, x: Int, centerY: Float, halfWindow: Float): Boolean {
        val lo = (centerY - halfWindow).toInt().coerceAtLeast(0)
        val hi = (centerY + halfWindow).toInt().coerceAtMost(p.height - 1)
        for (y in lo..hi) {
            for (xx in (x - 2).coerceAtLeast(0)..(x + 2).coerceAtMost(p.width - 1)) {
                val c = p.at(xx, y)
                if (c.alpha > 0.9f && c.red > 0.5f && c.green < 0.1f && c.blue < 0.1f) return true
            }
        }
        return false
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertTrue(abs(expected[it] - actual[it]) < 1e-3f) }
    }
}

@RunWith(RobolectricTestRunner::class)
class EqAxisHoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHeldAxisSurvivesRecomposition() {
        var gain by mutableStateOf(120)
        var dragging by mutableStateOf(false)
        val seen = mutableListOf<Double>()

        compose.setContent {
            val bands = listOf(EqBand(1_000, 141, 120), EqBand(1_000, 141, gain))
            seen += rememberPlotRange(
                parametricPlotRange(sumColumns(parametricResponse(bands)), bands),
                dragging,
            )
        }
        compose.waitForIdle()
        assertEquals("触る前の段", 24.0, seen.last(), 0.0)

        dragging = true
        gain = 85
        compose.waitForIdle()
        assertEquals("ドラッグの 1 フレーム目で段が動いた", 24.0, seen.last(), 0.0)

        gain = 20
        compose.waitForIdle()
        assertEquals("ドラッグを続けるうちに段が動いた", 24.0, seen.last(), 0.0)

        dragging = false
        compose.waitForIdle()
        assertEquals("指を離しても段が戻らない", 12.0, seen.last(), 0.0)
    }
}
