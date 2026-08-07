package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.AXIS_HI_HZ
import io.github.mame1839.codecanchor.ui.AXIS_LO_HZ
import io.github.mame1839.codecanchor.ui.EqField
import io.github.mame1839.codecanchor.ui.EqPreviewTarget
import io.github.mame1839.codecanchor.ui.PLOT_RANGES_DB
import io.github.mame1839.codecanchor.ui.SAMPLES
import io.github.mame1839.codecanchor.ui.evenColumns
import io.github.mame1839.codecanchor.ui.graphicResponse
import io.github.mame1839.codecanchor.ui.parametricResponse
import io.github.mame1839.codecanchor.ui.pickLabels
import io.github.mame1839.codecanchor.ui.plotRange
import io.github.mame1839.codecanchor.ui.sumColumns
import io.github.mame1839.codecanchor.ui.withPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 絵が「実際に鳴る特性」を描いていることを見る。
 *
 * ここが狂うと、絵と音が食い違ったままもっともらしく表示され続ける — 描画そのものは
 * 目で見ても正しそうに見えるので、気づく手段が無くなる。
 */
class EqCurveTest {

    private fun flatBands(count: Int, gainDb10: Int, q100: Int = 141) =
        EqSolver.centerFrequencies(count).map { EqBand(freqHz = it, q100 = q100, gainDb10 = gainDb10) }

    // ---------------------------------------------------------------------
    // 曲線は目標値ではなく実現値
    // ---------------------------------------------------------------------

    /**
     * **この試験が本体。**
     *
     * スライダーを直接動かしたときは求解が走らず、指定した値がそのまま `gainDb10` に入る
     * (求解は `reband` と AutoEQ 取り込みのときだけ)。なので 10 バンド全部 +6.0 dB の
     * 合成応答は +6.0 dB ではなく +8.85 dB になる (tools/eq/05_geq_gain_solve_10band.py)。
     *
     * 曲線がバンドのゲインをなぞっているだけなら、ここは 6.0 に落ちる。
     */
    @Test
    fun curveShowsWhatIsHeardNotWhatWasTyped() {
        val bands = flatBands(10, 60)
        val peak = graphicResponse(bands).max()
        assertTrue("期待は約 8.85 dB、実際は $peak", peak in 8.0..9.5)
        assertNotEquals("バンドのゲインをそのまま描いている", 6.0, peak, 0.5)
    }

    /**
     * 曲線の値そのものを固定する。参照は `llmdocs/tools/eq/05_geq_gain_solve_10band.py` の
     * `rbj_peak` / `rdb` を、アプリの中心周波数 (32〜16k) と Q=1.41 で回したもの。
     *
     * **スライダーの表示と並べると食い違いがそのまま読める** — 32 Hz は「+8.0 dB」と出るが
     * 実際には +9.62 dB 鳴る。絵はこちらを描く。
     */
    @Test
    fun curveMatchesTheReferenceImplementation() {
        val freqs = EqSolver.centerFrequencies(10)

        val flat = flatBands(10, 60)
        val flatExpected = doubleArrayOf(
            7.4956, 8.6500, 8.8296, 8.8492, 8.8467, 8.8145, 8.7062, 8.3880, 7.6011, 6.5280,
        )
        assertCurveAt(flat, freqs, flatExpected)
        assertEquals("帯域内のピーク", 8.8492, graphicResponse(flat).max(), 1e-3)

        val sliders = intArrayOf(80, 70, 50, 30, 10, 0, -20, -40, -60, -60)
        val mixed = freqs.mapIndexed { i, hz -> EqBand(freqHz = hz, q100 = 141, gainDb10 = sliders[i]) }
        val mixedExpected = doubleArrayOf(
            9.6240, 9.7034, 7.2874, 4.4427, 1.7047, -0.1961, -2.8375, -5.3536, -7.0866, -6.4792,
        )
        assertCurveAt(mixed, freqs, mixedExpected)
    }

    /** 隣が 0 なら食い違いは出ない。1 本だけ動かしたときは曲線が摘みの値をきっちり通る。 */
    @Test
    fun aLoneBandMeetsItsOwnGainExactly() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = if (it == 1_000) 120 else 0) }
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        assertEquals(12.0, response[freqs.indexOf(1_000) * step], 1e-9)
        assertEquals(12.0, response.max(), 1e-9)
        // 隣のバンド中心には裾だけが漏れる。参照実装と同じ値。
        assertEquals(2.5208, response[freqs.indexOf(500) * step], 1e-3)
        assertEquals(2.5014, response[freqs.indexOf(2_000) * step], 1e-3)
    }

    private fun assertCurveAt(bands: List<EqBand>, freqs: List<Int>, expected: DoubleArray) {
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        freqs.indices.forEach { i ->
            assertEquals("${freqs[i]} Hz", expected[i], response[i * step], 1e-3)
        }
    }

    /** 干渉補正を掛けた並びなら、曲線は目標に一致する。上の試験の裏返し。 */
    @Test
    fun solvedBandsMakeTheCurveMeetTheTarget() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = EqSolver.solveBands(DoubleArray(freqs.size) { 6.0 }, freqs, EqSolver.defaultQ(10))
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        freqs.indices.forEach { i ->
            assertEquals("バンド ${freqs[i]} Hz", 6.0, response[i * step], 0.2)
        }
    }

    /**
     * 標本がバンド中心をちょうど通ること。外すと、点の真上の曲線の値が
     * 「その周波数の実際の応答」ではなくなる。
     */
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

    /** 端は最初と最後のバンド中心。手前で切れていると、両端の点が曲線から外れる。 */
    @Test
    fun curveStartsAndEndsOnTheOuterBands() {
        val bands = flatBands(10, 60)
        val response = graphicResponse(bands)
        assertEquals(EqSolver.combinedResponseDb(bands, bands.first().freqHz.toDouble()), response.first(), 1e-9)
        assertEquals(EqSolver.combinedResponseDb(bands, bands.last().freqHz.toDouble()), response.last(), 1e-9)
    }

    /**
     * バンドごとの寄与を足すと合成応答になること。
     *
     * パラメトリックの絵は、細い線 (1 本ずつ) と主役の曲線 (合計) を同じ標本から作る。
     * 両者が別の式から来ていると、線が曲線と噛み合わない絵になる。
     */
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

    /** 空の並びでも落ちないこと (パラメトリックでバンドを全部消した直後)。 */
    @Test
    fun emptyAndSingleBandStayDrawable() {
        assertTrue(sumColumns(parametricResponse(emptyList())).all { it == 0.0 })
        val one = graphicResponse(listOf(EqBand(1_000, 141, 60)))
        assertTrue("1 点だと描画側で 0 除算になる", one.size >= 2)
        assertTrue(one.all { it.isFinite() })
    }

    // ---------------------------------------------------------------------
    // 縦軸
    // ---------------------------------------------------------------------

    /** 既定は ±12 dB。スライダーの範囲と同じで、平らな設定で軸が縮まないこと。 */
    @Test
    fun axisStaysAtTheSliderRangeWhenNothingExceedsIt() {
        assertEquals(12.0, plotRange(doubleArrayOf(0.0), flatBands(10, 0)), 0.0)
        assertEquals(12.0, plotRange(doubleArrayOf(11.9, -3.0), flatBands(10, 100)), 0.0)
    }

    /** 収まらなくなったら段で広げる。曲線が枠の外で切れたままにしない。 */
    @Test
    fun axisGrowsInStepsToHoldTheCurve() {
        val bands = flatBands(10, 120)
        val response = graphicResponse(bands)
        val range = plotRange(response, bands)
        assertTrue("曲線が枠から出ている: ${response.max()} > $range", response.max() <= range)
        assertTrue("段以外の値が出た", range in PLOT_RANGES_DB)
    }

    /** 取り込んだプリセットが強くても、段の一番上で受け止めること。 */
    @Test
    fun axisHasATopStep() {
        val bands = listOf(EqBand(1_000, 141, 400))
        assertEquals(PLOT_RANGES_DB.last(), plotRange(doubleArrayOf(999.0), bands), 0.0)
    }

    // ---------------------------------------------------------------------
    // ラベルの間引き
    // ---------------------------------------------------------------------

    @Test
    fun everyLabelIsKeptWhenTheyAllFit() {
        val xs = evenColumns(10, 0f, 1_000f)
        val shown = pickLabels(xs, FloatArray(10) { 40f }, 8f, -1)
        assertTrue("10 個なら全部出るはず", shown.all { it })
    }

    /** 31 バンドは必ず間引かれ、残ったものは重ならないこと。 */
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

    /** 触っているバンドは、間引きの対象でも必ず出る。 */
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

    // ---------------------------------------------------------------------
    // ドラッグ中の値
    // ---------------------------------------------------------------------

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

    /** 消えたバンドを指したまま残ることがある (削除・モード切り替え)。落ちずに素通しすること。 */
    @Test
    fun previewForAMissingBandIsIgnored() {
        val bands = listOf(EqBand(100, 141, 20))
        assertEquals(bands, bands.withPreview(EqPreviewTarget(5, EqField.GAIN), 100))
        assertEquals(bands, bands.withPreview(null, 100))
        assertEquals(bands, bands.withPreview(EqPreviewTarget(0, EqField.GAIN), null))
    }

    /** ドラッグ中の値が曲線に効いていること。効かないと絵が指に付いてこない。 */
    @Test
    fun theCurveFollowsTheDraggedValue() {
        val bands = flatBands(10, 0)
        val still = graphicResponse(bands).max()
        val dragged = graphicResponse(bands.withPreview(EqPreviewTarget(5, EqField.GAIN), 100)).max()
        assertEquals("触る前は平ら", 0.0, still, 1e-9)
        assertTrue("絵が追従していない: $dragged", dragged > 9.0)
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertTrue(abs(expected[it] - actual[it]) < 1e-3f) }
    }
}
