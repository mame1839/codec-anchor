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
import io.github.mame1839.codecanchor.ui.PLOT_RANGES_DB
import io.github.mame1839.codecanchor.ui.SAMPLES
import io.github.mame1839.codecanchor.ui.centreGainsDb10
import io.github.mame1839.codecanchor.ui.drawGraphicPlot
import io.github.mame1839.codecanchor.ui.drawParametricPlot
import io.github.mame1839.codecanchor.ui.eqFrequencyShort
import io.github.mame1839.codecanchor.ui.eqGainNumber
import io.github.mame1839.codecanchor.ui.eqGainTick
import io.github.mame1839.codecanchor.ui.evenColumns
import io.github.mame1839.codecanchor.ui.graphicResponse
import io.github.mame1839.codecanchor.ui.gridValues
import io.github.mame1839.codecanchor.ui.holdRange
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

/**
 * 絵が「実際に鳴る特性」を描いていることを見る。
 *
 * ここが狂うと、絵と音が食い違ったままもっともらしく表示され続ける — 描画そのものは
 * 目で見ても正しそうに見えるので、気づく手段が無くなる。
 *
 * Robolectric なのは、後半の試験が**本物のビットマップへ描いて画素を数える**ため
 * (`android.graphics` の実装が要る)。前半の計算だけの試験には影響しない。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqCurveTest {

    // 既定の Q は製品と同じ (`EqSolver.defaultQ`)。以前は 141 に固定していたが、それは
    // tools/eq/05 の実行条件で、旧参照値と対だった — 製品の 10 バンドは Q=1.0 なので、
    // グラフィック EQ を代表するテストはこちらで組む。
    private fun flatBands(count: Int, gainDb10: Int, q100: Int = (EqSolver.defaultQ(count) * 100).toInt()) =
        EqSolver.centerFrequencies(count).map { EqBand(freqHz = it, q100 = q100, gainDb10 = gainDb10) }

    // ---------------------------------------------------------------------
    // 曲線は目標値ではなく実現値
    // ---------------------------------------------------------------------

    /**
     * **この試験が本体。**
     *
     * `bands[].gainDb10` はフィルタに渡すゲインで、曲線はその合成応答を描く。
     * フィルタのゲインを 10 バンド全部 +6.0 dB にすると、合成は +6.0 dB ではなく
     * +10.86 dB になる (Q=1.0 の隣どうしの重なり)。
     *
     * 曲線がバンドのゲインをなぞっているだけなら、ここは 6.0 に落ちる。
     */
    @Test
    fun curveShowsWhatIsHeardNotWhatWasTyped() {
        val bands = flatBands(10, 60)
        val peak = graphicResponse(bands).max()
        assertTrue("期待は約 10.86 dB、実際は $peak", peak in 10.0..11.5)
        assertNotEquals("バンドのゲインをそのまま描いている", 6.0, peak, 0.5)
    }

    /**
     * 曲線の値そのものを固定する。参照は独立実装 (numpy、`tools/eq/05_geq_gain_solve_10band.py` の
     * `rbj_peak` / `rdb` と同式) をアプリの中心周波数 (32〜16k)・製品の Q=1.0・fs=48000 で回したもの。
     * 検算: 同じ実装を Q=1.41 で回すと旧参照値 (8.8492 / 7.4956 / 8.6500) がそのまま再現される
     * ことを確認済み — 新旧の参照値は同じ系統から出ている。
     *
     * **固定しているのは「バンドのゲイン (`gainDb10`) → 曲線」の計算で、そこは仕様変更の前後で
     * 変わっていない。**グラフィックの摘みはこの値ではない — 摘みが指すのは「その中心で実際に
     * 鳴る音量」で (2026-08-11 決定、eq-spec.md §7)、摘みを動かすと `EqSolver.withGraphicTarget()`
     * がゲインを解き直して摘みが曲線の上に乗る。ここで直接組んでいる並びは、解いた後の
     * `gainDb10` に相当する。
     */
    @Test
    fun curveMatchesTheReferenceImplementation() {
        val freqs = EqSolver.centerFrequencies(10)

        val flat = flatBands(10, 60)
        val flatExpected = doubleArrayOf(
            8.5392, 10.4372, 10.8052, 10.8595, 10.8573, 10.8020, 10.6203, 10.1039, 8.8388, 6.9864,
        )
        assertCurveAt(flat, freqs, flatExpected)
        assertEquals("帯域内のピーク", 10.8595, graphicResponse(flat).max(), 1e-3)

        val sliders = intArrayOf(80, 70, 50, 30, 10, 0, -20, -40, -60, -60)
        val mixed = freqs.mapIndexed { i, hz -> EqBand(freqHz = hz, q100 = 100, gainDb10 = sliders[i]) }
        val mixedExpected = doubleArrayOf(
            10.7088, 11.4430, 8.8549, 5.4808, 2.2183, -0.3329, -3.4378, -6.3146, -7.9309, -6.8905,
        )
        assertCurveAt(mixed, freqs, mixedExpected)
    }

    /**
     * 隣が 0 なら食い違いは出ない。1 本だけ動かしたときは曲線が摘みの値をきっちり通る。
     *
     * **上のテストと対で読むこと。**ずれるのは干渉があるときだけで、
     * **計算そのものは合っている**ことをこちらが示している。
     * 片方だけ見て「曲線が間違っている」と直しに行かないための組。
     */
    @Test
    fun aLoneBandMeetsItsOwnGainExactly() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 100, gainDb10 = if (it == 1_000) 120 else 0) }
        val response = graphicResponse(bands)
        val step = (response.size - 1) / (freqs.size - 1)
        assertEquals(12.0, response[freqs.indexOf(1_000) * step], 1e-9)
        assertEquals(12.0, response.max(), 1e-9)
        // 隣のバンド中心には裾だけが漏れる。参照実装と同じ値。
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

    /**
     * 干渉補正を掛けた並びなら、曲線は目標に一致する。上の試験の裏返し。
     * 許容 0.05 は保存の刻み (0.1 dB) の半分 — solver が量子化後の値で詰めるので、ここまで寄る。
     */
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
    // 段の固定 — ドラッグ中に軸が縮むと、指と逆に絵が動く
    // ---------------------------------------------------------------------

    /**
     * 段が落ちる状態。**125 Hz と 250 Hz だけ**を持ち上げると、2 本の重なりで合成が
     * 12 dB をわずかに越えて段が 18 dB になる (Q=1.0、+9.0 の 2 本でピーク 12.154 dB)。
     * 片方を 0.5 dB 下げるとピークが 12 を割り、段が 12 dB へ戻る
     * (段 12 dB は摘みの可動域 ±12 dB が下から支えている)。
     */
    private fun raisedPair(neighbour: Int, dragged: Int): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(10)
        val at = freqs.indexOf(125)
        return freqs.mapIndexed { i, hz ->
            val gain = when (i) {
                at -> dragged
                at + 1 -> neighbour
                else -> 0
            }
            EqBand(freqHz = hz, q100 = 100, gainDb10 = gain)
        }
    }

    /**
     * **この試験が 1 番目の修正の本体。**
     *
     * 段は合成のピークで決まるので、摘みを下げると段まで下がることがある。段が下がると
     * 絵が拡大するので、**指を下げているのに点が上がる。**数字は下がり、絵は上がる。
     */
    @Test
    fun theAxisIsHeldWhileAKnobIsMoving() {
        val before = raisedPair(neighbour = 90, dragged = 90)
        val after = raisedPair(neighbour = 90, dragged = 85)
        val resting = plotRange(graphicResponse(before), before)
        val shrunk = plotRange(graphicResponse(after), after)

        // 前提。ここが崩れたら以下は何も見ていないので、先に落とす。
        assertEquals("触る前の段", 18.0, resting, 0.0)
        assertEquals("0.5 dB 下げたときの段", 12.0, shrunk, 0.0)

        // 縦位置は 0 が上端、1 が下端。下げたのだから、値は増えなければならない。
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

        // 指を離したら本来の段へ戻す。戻さないと軸が広がりっぱなしになる。
        assertEquals(shrunk, holdRange(shrunk, resting, dragging = false), 0.0)
    }

    /**
     * 段が落ちる状態は珍しくない。**隣り合う 2 本を持ち上げるだけで作れる**ので、
     * 1 つの例だけでなく、作れる範囲を全部見て「固定すれば 1 件も上がらない」ことを見る。
     */
    @Test
    fun noAxisShrinkMovesThePointAgainstTheFinger() {
        val freqs = EqSolver.centerFrequencies(10)
        var shrinks = 0
        var rises = 0
        var risesWhileHeld = 0
        for (pair in 0 until freqs.size - 1) {
            for (g in 5..120 step 5) {
                val base = freqs.mapIndexed { i, hz ->
                    EqBand(freqHz = hz, q100 = 100, gainDb10 = if (i == pair || i == pair + 1) g else 0)
                }
                val dragged = base.mapIndexed { i, b -> if (i == pair) b.copy(gainDb10 = g - 5) else b }
                val resting = plotRange(graphicResponse(base), base)
                val shrunk = plotRange(graphicResponse(dragged), dragged)
                if (shrunk >= resting) continue
                shrinks++
                val was = plotFraction(g / 10.0, resting)
                if (plotFraction((g - 5) / 10.0, shrunk) < was) rises++
                if (plotFraction((g - 5) / 10.0, holdRange(shrunk, resting, dragging = true)) < was) risesWhileHeld++
            }
        }
        // 見張るものが本当にあることを、同じ試験の中で確かめる。
        assertTrue("段が落ちる組み合わせが 1 つも無い", shrinks > 0)
        assertEquals("段が落ちても点が上がらない = 症状が再現していない", shrinks, rises)
        assertEquals("段を固定したのに点が上がった", 0, risesWhileHeld)
    }

    /** 上げるほうは止めない。止めると曲線が枠から出て、上が切れたまま描かれる。 */
    @Test
    fun theAxisStillGrowsWhileAKnobIsMoving() {
        assertEquals(24.0, holdRange(computed = 24.0, held = 18.0, dragging = true), 0.0)
        assertEquals(40.0, holdRange(computed = 40.0, held = 12.0, dragging = true), 0.0)
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

    /**
     * グラフィックの摘み ([EqField.GRAPHIC_GAIN]) は目標値で、ドラッグ中の絵も解いた結果を描く。
     * 素朴に `gainDb10` へ複写すると、ドラッグ中と指を離した後で別の曲線になり、
     * 離した瞬間に干渉補正の分だけ絵が跳ぶ。
     */
    @Test
    fun aGraphicPreviewSolvesTheTargetInsteadOfCopyingIt() {
        val bands = flatBands(10, 40)
        val preview = bands.withPreview(EqPreviewTarget(3, EqField.GRAPHIC_GAIN), 90)
        assertEquals("解いた結果と一致しない", EqSolver.withGraphicTarget(bands, 3, 90), preview)
        val copied = bands.mapIndexed { i, b -> if (i == 3) b.copy(gainDb10 = 90) else b }
        assertNotEquals("GAIN と同じ素朴な複写になっている", copied, preview)
    }

    // ---------------------------------------------------------------------
    // 絵そのもの — 本物のビットマップへ描いて画素を数える
    //
    // composable ごと動かす道は使えない (`captureToImage()` は Robolectric では
    // 描画の完了を待ちきれず 2 秒で時間切れになる)。代わりに、**アプリの Canvas が呼ぶのと
    // 同じ描画関数**を ImageBitmap へ流す。出ていないものは、ここでだけ出ていないと分かる。
    //
    // 色は役割ごとに分ける。混ぜると、画素の値から「何が描かれたのか」を決められない。
    // ---------------------------------------------------------------------

    private val density = Density(3f)
    private val measurer by lazy {
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)
    }

    /** 曲線・塗り・点・バンドの縦線。 */
    private val accent = Color.Red

    /** 目盛り線と案内線。 */
    private val muted = Color.Blue

    /** 縦軸の数字。 */
    private val tickText = Color.Green

    /** バンドのゲインと周波数の数字。赤を含むので、緑の有無で目盛りの数字と切り分ける。 */
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

        /** 縦 1 列を上から見て、条件に合う画素の連なりを返す。 */
        fun runsDown(x: Int, hit: (Color) -> Boolean): List<IntRange> = runs { y -> hit(pixels[x, y]) }

        /** 「その行のどこかに条件に合う画素がある」を上から見た連なり。 */
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

    /**
     * グラフィックの絵と、検査に要る幾何 (枠と列の位置)。ラベルの作り方は composable と、
     * 幾何は `drawGraphicPlot` と同じ式で組む。定数はあちらの private 定数の写し
     * (LABEL_GAP 6dp / PLOT_INSET 10dp / 絵 132dp)。あちらを変えるとここの検査が
     * 枠を外れて落ちるので、そのとき合わせる。
     */
    private inner class GraphicScene(bands: List<EqBand>) {
        val response = graphicResponse(bands)
        val range = plotRange(response, bands)
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
        val range = plotRange(composite, bands)
        val height = (132f + 6f) * density.density + rowHeight * 2f
        return Painted(984, height.toInt()) {
            drawParametricPlot(
                bands, -1, perBand, composite, range, ticksFor(range),
                labels, rowHeight, accent, muted,
            )
        }
    }

    /** 5 本のうち真ん中が 0 dB。0 dB の線の位置を絵の側から引く。 */
    private fun Painted.zeroLine(): IntRange {
        val lines = runsDown(width - 1, ::isGrid)
        assertEquals("目盛り線の本数", 5, lines.size)
        return lines[2]
    }

    /**
     * **この試験が 2 番目の修正の本体。**
     *
     * グラフィックには 0 dB の基準線 1 本しか無く、**段が 12 / 18 / 24 / 40 dB と変わっても
     * 画面のどこにも数字が出ていなかった。**段が変わったことと線の形が変わったことを
     * 区別する手掛かりが 1 つも無い状態で、絵が縦に潰れて見える原因になっていた。
     */
    @Test
    fun theGraphicPlotDrawsEveryDbTickWithItsNumber() {
        val painted = paintGraphic(flatBands(10, 120))

        // 枠の右端には曲線も塗りもバンドの縦線も届かない (どれも PLOT_INSET だけ内側で終わる)。
        // この 1 列に見えるものは目盛り線だけなので、連なりの数がそのまま本数になる。
        val lines = painted.runsDown(painted.width - 1, ::isGrid)
        assertEquals("目盛り線が ${lines.size} 本しか無い", gridValues(24.0).size, lines.size)

        // 0 dB は基準線として濃く引く。同じ濃さだと、どれが 0 かが読めない。
        val strength = lines.map { run -> run.maxOf { painted.at(painted.width - 1, it).alpha } }
        assertEquals("一番濃い線が真ん中 (0 dB) でない: $strength", 2, strength.indexOf(strength.max()))

        assertEquals("縦軸の数字の数", gridValues(24.0).size, painted.rowsWith(::isTickText).size)
    }

    /** パラメトリックも同じ目盛りを出す。**同じ関数を通っている**ことをこちらで押さえる。 */
    @Test
    fun theParametricPlotDrawsTheSameDbTicks() {
        val painted = paintParametric(listOf(EqBand(1_000, 141, 120), EqBand(4_000, 141, -60)))
        assertEquals(gridValues(12.0).size, painted.rowsWith(::isTickText).size)
    }

    /**
     * **この試験が 3 番目の修正の本体。**
     *
     * 塗りは曲線と 0 dB のあいだ。**下端に閉じていたときは、塗りの面積が「値」ではなく
     * 「値 + 軸の幅」になっていた** — 全部を持ち上げても、0 dB より下に枠の半分ぶんの
     * 塗りが残る。バンドの縦線も同じで、下端まで伸ばすと長さが値を表さない。
     */
    @Test
    fun theFillIsClosedAtZeroDbNotAtTheEdgeOfTheFrame() {
        // 全部 +12 dB。曲線も点も 0 dB より上だけにあるので、下には 1 画素も無いのが正しい。
        val up = paintGraphic(flatBands(10, 120))
        val upZero = up.zeroLine()
        assertTrue("0 dB より上に絵が無い", up.count(0 until upZero.first - 2, ::isAccent) > 0)
        assertEquals(
            "0 dB より下に塗りか縦線が残っている",
            0,
            up.count(upZero.last + 3 until up.height, ::isAccent),
        )

        // 下げ側も同じ。上端に閉じる書き方をしたら、こちらが落ちる。
        val down = paintGraphic(flatBands(10, -120))
        val downZero = down.zeroLine()
        assertEquals(
            "0 dB より上に塗りか縦線が残っている",
            0,
            down.count(0 until downZero.first - 2, ::isAccent),
        )
        assertTrue("0 dB より下に絵が無い", down.count(downZero.last + 3 until down.height, ::isAccent) > 0)
    }

    // ---------------------------------------------------------------------
    // 点とラベル — 摘みと同じ「その中心で実際に鳴る音量」を指す
    // ---------------------------------------------------------------------

    /**
     * 数値ラベルの値は摘みと同じ「その中心で実際に鳴る音量」。
     *
     * 目標どおりに解けた並びなら全ラベルが目標そのものになる (求解が「丸めた応答 = 目標」まで
     * 詰めるため)。フィルタのゲインを出すと、全部 +12.0 の摘みの上に解いたゲイン
     * (+6.3〜+10.7) が並び、摘みと絵の不一致がグラフの中に残る。
     */
    @Test
    fun centreLabelsShowTheKnobValueNotTheFilterGain() {
        val freqs = EqSolver.centerFrequencies(10)
        val solved = EqSolver.solveBands(DoubleArray(freqs.size) { 12.0 }, freqs, EqSolver.defaultQ(10))
        val labels = centreGainsDb10(graphicResponse(solved), solved.size)
        assertTrue("ラベルが目標 +12.0 に一致しない: ${labels.toList()}", labels.all { it == 120 })
        assertNotEquals("フィルタのゲインをそのまま出している", solved.map { it.gainDb10 }, labels.toList())

        // 丸めの検算。フィルタゲイン直置きの flat +6 では、中心の応答 (上の参照値) を丸めた値。
        assertEquals(
            listOf(85, 104, 108, 109, 109, 108, 106, 101, 88, 70),
            centreGainsDb10(graphicResponse(flatBands(10, 60)), 10).toList(),
        )
    }

    /**
     * 点は曲線の上 (= その中心で実際に鳴る音量) にあり、フィルタのゲインの高さには無い。
     *
     * 目標 +12 で解いた並びでは曲線が各中心で +12 を通り、解いたゲインはそれより下に
     * 散らばる。点をゲインに置く古い描き方だと、平らな曲線の下に点がばらばらに浮く。
     */
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

            // 曲線や点の直径と重なる近さでは「無い」を言えない。離れているバンドだけ見る。
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

    /** 点 (不透明の accent) の有無。案内線 (45%) や塗り (16%) は重なっても不透明にならない。 */
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

/**
 * 段の固定が**再構成をまたいで**効いていること。
 *
 * `holdRange` 単体の試験では足りない — 覚えておく側 (`remember`) に鍵を付けると
 * **毎フレーム忘れて素通しになるのに、単体の試験は通ったまま**になる。絵ももっともらしい
 * ままなので、気づく手段が他に無い。ここだけ本物の composition を回す。
 *
 * 組み立てるものが `Canvas` すら無いので `EqCurveTest` とは別のクラスにしてある
 * (compose のルールは同じクラスの全部の試験に掛かる)。
 */
@RunWith(RobolectricTestRunner::class)
class EqAxisHoldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHeldAxisSurvivesRecomposition() {
        val freqs = EqSolver.centerFrequencies(10)
        val at = freqs.indexOf(125)
        var gain by mutableStateOf(90)
        var dragging by mutableStateOf(false)
        val seen = mutableListOf<Double>()

        compose.setContent {
            val bands = freqs.mapIndexed { i, hz ->
                val value = when (i) {
                    at -> gain
                    at + 1 -> 90
                    else -> 0
                }
                EqBand(freqHz = hz, q100 = 100, gainDb10 = value)
            }
            seen += rememberPlotRange(graphicResponse(bands), bands, dragging)
        }
        compose.waitForIdle()
        assertEquals("触る前の段", 18.0, seen.last(), 0.0)

        dragging = true
        gain = 85
        compose.waitForIdle()
        assertEquals("ドラッグの 1 フレーム目で段が動いた", 18.0, seen.last(), 0.0)

        gain = 60
        compose.waitForIdle()
        assertEquals("ドラッグを続けるうちに段が動いた", 18.0, seen.last(), 0.0)

        dragging = false
        compose.waitForIdle()
        assertEquals("指を離しても段が戻らない", 12.0, seen.last(), 0.0)
    }
}
