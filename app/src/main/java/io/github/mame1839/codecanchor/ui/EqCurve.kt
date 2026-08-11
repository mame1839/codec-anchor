package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// ±12.0 dB を 0.1 dB 刻み。eq-spec.md の確定値。
// 絵の縦軸と EqSection のスライダーが同じ値を見る。2 箇所に書くと、片方だけ動かしたときに
// 摘みの可動域と絵の目盛りが黙ってずれる。
val EQ_GAIN_RANGE = -120..120
const val EQ_GAIN_STEP = 1

// 縦軸の候補。**連続に伸ばさない** — ドラッグのあいだ軸が毎フレーム動くと、
// 触っていないバンドの点まで揺れて、何が変わったのか読めなくなる。
internal val PLOT_RANGES_DB = listOf(12.0, 18.0, 24.0, 40.0)

// 対数軸の両端 (パラメトリック)。可聴帯域。
internal const val AXIS_LO_HZ = 20.0
internal const val AXIS_HI_HZ = 20_000.0

// 曲線の標本数。RBJ の応答は滑らかなので、これだけあれば折れ線に見えない。
// 31 バンドだと 1 フレームあたり 31 * 160 回の応答計算になるので、無闇に増やさない。
internal const val SAMPLES = 160

private val PLOT_HEIGHT = 132.dp
private val LABEL_GAP = 6.dp
private val DOT_RADIUS = 3.5.dp
private val DOT_RADIUS_ACTIVE = 5.dp
private val CURVE_WIDTH = 2.5.dp
private val BAND_WIDTH = 1.dp
private val COLUMN_WIDTH = 1.5.dp
// ラベル同士の最小の隙間。**広げすぎると 10 バンドで間引きが始まる** —
// 340 dp 幅なら 1 列 35 dp しかなく、"+10.5" が 27 dp を占める。
// 既定の 10 バンドは全部出るのが正しい状態なので、ここは詰める。
private val LABEL_MIN_GAP = 4.dp
private val PLOT_INSET = 10.dp

// 0 dB の基準線の濃さ。グラフィックとパラメトリックで同じ値を使う。
private const val ZERO_LINE_ALPHA = 0.28f

// 0 dB 以外の目盛り線。基準線より必ず薄くする (どれが 0 かが読めなくなる)。
private const val GRID_LINE_ALPHA = 0.14f

// バンドの縦線のうち、点より外側 (値を持たない側) の濃さ。
private const val COLUMN_GUIDE_ALPHA = 0.10f

/** どの欄を動かしているか。ドラッグ中の値を絵へ運ぶために使う。 */
/**
 * [GAIN] はパラメトリック用で、値がそのバンドのフィルタのゲイン。
 * [GRAPHIC_GAIN] はグラフィック用で、値は**そのバンド中心で鳴る音量 (目標値)**。
 * 分けているのは、ドラッグ中の絵を指を離した後と同じ形にするため — グラフィックで
 * 目標値をそのままゲインとして描くと、離した瞬間に曲線が干渉補正の分だけ跳ぶ。
 */
enum class EqField { FREQUENCY, Q, GAIN, GRAPHIC_GAIN }

/** ドラッグ中の値の宛先。 */
data class EqPreviewTarget(val bandIndex: Int, val field: EqField)

/**
 * ドラッグ中の値の受け皿。[EqSliderRow] が書き、[EqCurve] が読む。
 *
 * **設定そのものは書き換えない。** `MainViewModel.commit()` にデバウンスが無く、
 * 1 回の変更ごとに「設定全体を encode → 保存 → Bluetooth へ送信」が同期で走るので、
 * 指を動かしている間ずっとそれを叩くことになる。絵だけが先に追いつく形にしてある。
 */
class EqPreview {
    var target by mutableStateOf<EqPreviewTarget?>(null)
        private set
    var value by mutableIntStateOf(0)
        private set

    fun show(target: EqPreviewTarget, value: Int) {
        this.target = target
        this.value = value
    }

    /** 指を離したときと、ドラッグ中に行ごと消えたとき (バンドの削除) の両方から呼ぶ。 */
    fun clear(target: EqPreviewTarget) {
        if (this.target == target) this.target = null
    }
}

val LocalEqPreview = staticCompositionLocalOf<EqPreview?> { null }

/**
 * 絵とスライダーを同じ [EqPreview] の下に置く。レイアウトは足さないので、
 * 中身は呼び出し元の `Column` へそのまま並ぶ。
 */
@Composable
fun ColumnScope.EqPreviewHost(content: @Composable ColumnScope.() -> Unit) {
    val scope = this
    val preview = remember { EqPreview() }
    CompositionLocalProvider(LocalEqPreview provides preview) { scope.content() }
}

/**
 * いまの設定がどういう周波数特性になるかの絵。**表示専用で、タッチには反応しない。**
 *
 * 操作は既存のスライダーのまま。掴めそうに見える形を置くと「掴めるのに動かない」になるので、
 * 点は小さく、影も立体感も付けない。
 *
 * **描くのは目標値ではなく実際に鳴る特性。** `EqSettings.bands` の `gainDb10` は
 * フィルタに渡すゲインで (`EqSolver.solveBands()` が解をここへ書き、`autoPreampDb10()` も
 * これを合成応答として読む)、目標値はモデルのどこにも保存されていない。
 * 曲線は `EqSolver.combinedResponseDb()` をそのまま引く — 応答の式をここで書き直すと、
 * 片方だけ直したときに絵と音が黙ってずれる。
 *
 * **プリアンプは含めない。** 既定の `preampAuto` はプリアンプ = −(合成ピーク) なので、
 * 含めるとどの設定でも曲線の最大が 0 dB に貼り付き、バンドを上げても曲線が下がるだけになる。
 * プリアンプは音色ではなく音量で、曲線を平行移動するだけなので形の情報は増えない。
 */
@Composable
fun EqCurve(eq: EqSettings, modifier: Modifier = Modifier) {
    val preview = LocalEqPreview.current
    val bands = eq.bands.withPreview(preview?.target, preview?.value)
    if (bands.isEmpty()) return
    val active = preview?.target?.bandIndex ?: -1

    val description = stringResource(R.string.eq_curve_desc)
    // **既定のキャッシュは 8 件しかない。**31 バンドはラベルが 62 枚あるので、
    // 既定のままだと毎フレーム全部が測り直しになる (ドラッグ中に変わる文字列は 1 枚だけなのに)。
    val measurer = rememberTextMeasurer(cacheSize = 80)
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        // 数値と単位が入れ替わるのは、段落の向きが RTL のときに数字が中立文字として
        // 流されるため。軸のラベルは常に左→右で読むものなので、向きを固定して切り離す。
        textDirection = TextDirection.Ltr,
        // labelSmall の字間は本文向け。数字の並びでは幅を食うだけで、
        // 1 列ぶんの幅が惜しい 31 バンドではそのまま間引きの本数に効く。
        letterSpacing = 0.sp,
        color = colors.onSurfaceVariant,
    )

    if (eq.mode == EqMode.GRAPHIC) {
        GraphicPlot(bands, active, measurer, labelStyle, colors.primary, colors.onSurfaceVariant, description, modifier)
    } else {
        ParametricPlot(bands, active, measurer, labelStyle, colors.primary, colors.onSurfaceVariant, description, modifier)
    }
}

// ---------------------------------------------------------------------------
// グラフィック — バンドが等間隔に並ぶ。縦線 + 先端の点 + 曲線 + グラデーション。
// ---------------------------------------------------------------------------

@Composable
private fun GraphicPlot(
    bands: List<EqBand>,
    active: Int,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    accent: Color,
    muted: Color,
    description: String,
    modifier: Modifier,
) {
    val kiloShort = stringResource(R.string.eq_unit_khz_short)

    // 実際に鳴る特性を、バンド中心が等間隔に並ぶ軸の上で測る。
    // 中心と中心のあいだは対数周波数で補間するので、目盛りは歪むが曲線は本物のまま。
    val response = remember(bands) { graphicResponse(bands) }

    // 数値ラベルは摘みと同じ「その中心で実際に鳴る音量」([centreGainsDb10])。
    // `gainDb10` (フィルタのゲイン) を出すと、全バンド +12.0 の摘みの上に解いたゲイン
    // (+6.3〜+10.7) が並び、直したはずの「摘みと絵の不一致」がグラフの中に残る。
    val gainLabels = remember(bands, labelStyle) {
        centreGainsDb10(response, bands.size).map { measurer.measure(eqGainNumber(it), labelStyle) }
    }
    val freqLabels = remember(bands, labelStyle, kiloShort) {
        bands.map { measurer.measure(eqFrequencyShort(it.freqHz, kiloShort), labelStyle) }
    }
    val rowHeight = (gainLabels + freqLabels).maxOf { it.size.height }

    val density = LocalDensity.current
    val totalHeight = PLOT_HEIGHT + LABEL_GAP * 2 + with(density) { (rowHeight * 2).toDp() }

    val range = rememberPlotRange(response, bands, dragging = active >= 0)
    val ticks = rememberDbTicks(range, measurer, labelStyle)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(totalHeight)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
    ) {
        drawGraphicPlot(
            bands, active, response, range, ticks,
            gainLabels, freqLabels, rowHeight.toFloat(), accent, muted,
        )
    }
}

/**
 * グラフィックの絵。**描くのはここだけで、composable 側は測って渡すだけ。**
 *
 * 分けてあるのは試験のため。ビットマップへ同じ関数を流せば、目盛りが出ているか・
 * 塗りがどこに閉じているかを画素で確かめられる (`EqCurveTest`)。
 * **Canvas のラムダの中に書くと、この 2 つはどんな試験からも見えない** —
 * 実際、目盛りが 1 本も無い状態と塗りが下端に閉じた状態が、緑のまま実機まで出ている。
 */
@Suppress("LongParameterList")
internal fun DrawScope.drawGraphicPlot(
    bands: List<EqBand>,
    active: Int,
    response: DoubleArray,
    range: Double,
    ticks: List<Pair<Double, TextLayoutResult>>,
    gainLabels: List<TextLayoutResult>,
    freqLabels: List<TextLayoutResult>,
    rowHeight: Float,
    accent: Color,
    muted: Color,
) {
    val gapPx = LABEL_GAP.toPx()
    val gutter = ticks.maxOf { it.second.size.width }
    val plot = Rect(gutter + gapPx, rowHeight + gapPx, size.width, size.height - rowHeight - gapPx)
    val inset = PLOT_INSET.toPx()
    val xs = evenColumns(bands.size, plot.left + inset, plot.right - inset)
    val yOf = { db: Double -> plot.top + plotFraction(db, range) * plot.height }
    val zero = yOf(0.0)

    // ドラッグ中のバンドは、間引きの対象でも必ず出す。「いま何を触っているか」が主目的。
    val shown = pickLabels(
        xs,
        FloatArray(bands.size) { max(gainLabels[it].size.width, freqLabels[it].size.width).toFloat() },
        LABEL_MIN_GAP.toPx(),
        active,
    )

    val curve = Path()
    for (i in response.indices) {
        val x = plot.left + inset + (plot.width - inset * 2) * i / (response.size - 1f)
        val y = yOf(response[i])
        if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
    }

    // 曲線と 0 dB のあいだを塗る。**下端ではなく 0 dB に閉じる** — パラメトリックと同じ。
    // 下端に閉じていたときは、塗りの面積が「値」ではなく「値 + 軸の幅」になっていて、
    // -12 dB のバンドの下にも枠の 1/4 の塗りが残っていた。摘みを上下対称に振っても
    // 絵は上に偏ったままになり、線の形そのものが歪んで見える原因になっていた。
    val fill = Path().apply {
        addPath(curve)
        lineTo(plot.right - inset, zero)
        lineTo(plot.left + inset, zero)
        close()
    }
    drawPath(fill, accent.copy(alpha = 0.16f))

    // 目盛り。0 dB は基準線として濃く引く (どれが 0 かが読めないと上げ下げが分からない)。
    drawDbTicks(ticks, plot, muted, yOf)

    // 点の大きさは列の間隔で頭を押さえる。31 バンドだと間隔が 11 dp しかなく、
    // 既定の半径のままでは 0 dB に並んだ点がつながって 1 本の帯に見える。
    val pitch = if (xs.size > 1) xs[1] - xs[0] else plot.width
    val dotR = min(DOT_RADIUS.toPx(), pitch * 0.22f)
    val dotRActive = min(DOT_RADIUS_ACTIVE.toPx(), pitch * 0.32f)

    // 点は「その中心で実際に鳴る音量」= 摘みの値の高さに置く。曲線は中心を必ず通るので
    // (`samplesLandExactlyOnBandCentres`)、点は曲線の上に乗る。`gainDb10` (フィルタのゲイン)
    // に置くと、全バンド +12 の平らな曲線の下に解いたゲインの点が散らばって、摘みと絵が
    // 食い違う。丸める前の値なのは点を曲線から 1 px も浮かさないため (ラベルは丸めた値)。
    val step = if (bands.size > 1) (response.size - 1) / (bands.size - 1) else 0
    bands.indices.forEach { i ->
        val x = xs[i]
        val y = yOf(response[i * step])
        val hot = i == active
        // 枠いっぱいの案内線を薄く引いてから、**0 dB と点のあいだ**だけを濃くする。
        // 濃い側を下端まで伸ばすと長さが値を表さない — -12 dB のバンドが枠の 2/3 の
        // 長さの棒になり、+12 dB との差が 3 倍ではなく 1.5 倍にしか見えなかった。
        drawLine(muted.copy(alpha = COLUMN_GUIDE_ALPHA), Offset(x, plot.top), Offset(x, plot.bottom), COLUMN_WIDTH.toPx())
        drawLine(accent.copy(alpha = if (hot) 0.85f else 0.45f), Offset(x, y), Offset(x, zero), COLUMN_WIDTH.toPx())
        drawCircle(accent, if (hot) dotRActive else dotR, Offset(x, y))
    }

    drawPath(curve, accent, style = Stroke(width = CURVE_WIDTH.toPx()))

    bands.indices.forEach { i ->
        if (!shown[i]) return@forEach
        drawCentered(gainLabels[i], xs[i], 0f)
        drawCentered(freqLabels[i], xs[i], size.height - rowHeight)
    }
}

// ---------------------------------------------------------------------------
// パラメトリック — 対数の周波数軸。合成の曲線が主役、バンドごとの寄与は細い線。
// ---------------------------------------------------------------------------

@Composable
private fun ParametricPlot(
    bands: List<EqBand>,
    active: Int,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    accent: Color,
    muted: Color,
    description: String,
    modifier: Modifier,
) {
    val kiloShort = stringResource(R.string.eq_unit_khz_short)

    val perBand = remember(bands) { parametricResponse(bands) }
    val composite = remember(perBand) { sumColumns(perBand) }
    val range = rememberPlotRange(composite, bands, dragging = active >= 0)

    val ticks = rememberDbTicks(range, measurer, labelStyle)
    val tickLabels = remember(labelStyle, kiloShort) {
        AXIS_TICKS_HZ.map { measurer.measure(eqFrequencyShort(it, kiloShort), labelStyle) }
    }
    val rowHeight = tickLabels.maxOf { it.size.height }

    val density = LocalDensity.current
    // 上下の目盛りのラベルは枠線の高さに中央合わせで置くので、半分ずつはみ出す。
    // その分を確保しないと "+12 dB" の上半分が切れる。
    val totalHeight = PLOT_HEIGHT + LABEL_GAP + with(density) { (rowHeight * 2).toDp() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(totalHeight)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
    ) {
        drawParametricPlot(
            bands, active, perBand, composite, range, ticks,
            tickLabels, rowHeight.toFloat(), accent, muted,
        )
    }
}

/** パラメトリックの絵。分けてある理由は [drawGraphicPlot] と同じ。 */
@Suppress("LongParameterList")
internal fun DrawScope.drawParametricPlot(
    bands: List<EqBand>,
    active: Int,
    perBand: List<DoubleArray>,
    composite: DoubleArray,
    range: Double,
    ticks: List<Pair<Double, TextLayoutResult>>,
    tickLabels: List<TextLayoutResult>,
    rowHeight: Float,
    accent: Color,
    muted: Color,
) {
    val gutterPx = ticks.maxOf { it.second.size.width } + LABEL_GAP.toPx()
    val plot = Rect(gutterPx, rowHeight / 2f, size.width, size.height - rowHeight * 1.5f - LABEL_GAP.toPx())
    val yOf = { db: Double -> plot.top + plotFraction(db, range) * plot.height }
    val xAt = { i: Int -> plot.left + plot.width * i / (SAMPLES - 1f) }

    drawDbTicks(ticks, plot, muted, yOf)

    val zero = yOf(0.0)
    val curve = Path()
    for (i in composite.indices) {
        val x = xAt(i)
        val y = yOf(composite[i])
        if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
    }
    // 曲線と 0 dB のあいだを塗る。上に出れば持ち上げ、下に落ちれば削り。
    // 0 dB をまたぐと自己交差するが、NonZero なのでどちらの側も塗られる。
    val fill = Path().apply {
        addPath(curve)
        lineTo(plot.right, zero)
        lineTo(plot.left, zero)
        close()
    }
    drawPath(fill, accent.copy(alpha = 0.16f))

    // バンドごとの寄与。主役を埋めないよう細く薄く。触っているものだけ濃くする。
    perBand.forEachIndexed { b, series ->
        val hot = b == active
        val path = Path()
        for (i in series.indices) {
            val x = xAt(i)
            val y = yOf(series[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            if (hot) accent.copy(alpha = 0.9f) else muted.copy(alpha = 0.38f),
            style = Stroke(width = (if (hot) CURVE_WIDTH / 2 else BAND_WIDTH).toPx()),
        )
    }

    drawPath(curve, accent, style = Stroke(width = CURVE_WIDTH.toPx()))

    // 点はそのバンド自身の応答の上に置く。シェルフは fc でゲインの約半分なので、
    // (fc, gain) に置くと自分の曲線から浮く。
    bands.forEachIndexed { b, band ->
        val hz = band.freqHz.toDouble()
        if (hz < AXIS_LO_HZ || hz > AXIS_HI_HZ) return@forEachIndexed
        val x = plot.left + (ln(hz / AXIS_LO_HZ) / ln(AXIS_HI_HZ / AXIS_LO_HZ)).toFloat() * plot.width
        val y = yOf(EqSolver.bandResponseDb(band, hz))
        drawCircle(accent, (if (b == active) DOT_RADIUS_ACTIVE else DOT_RADIUS).toPx(), Offset(x, y))
    }

    val xs = FloatArray(AXIS_TICKS_HZ.size) {
        plot.left + (ln(AXIS_TICKS_HZ[it] / AXIS_LO_HZ) / ln(AXIS_HI_HZ / AXIS_LO_HZ)).toFloat() * plot.width
    }
    val shown = pickLabels(xs, FloatArray(xs.size) { tickLabels[it].size.width.toFloat() }, LABEL_MIN_GAP.toPx(), -1)
    xs.indices.forEach { if (shown[it]) drawCentered(tickLabels[it], xs[it], size.height - rowHeight) }
}

// ---------------------------------------------------------------------------

private val AXIS_TICKS_HZ = listOf(20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000)

/**
 * 縦軸の目盛り。**グラフィックとパラメトリックで同じものを出す。**
 *
 * 段 ([plotRange]) は設定によって 12 / 18 / 24 / 40 dB と変わる。**数字が無いと、
 * 段が変わったことと線の形が変わったことが画面上で区別できない** — 摘みを ±12 dB まで
 * 振り切っても、軸が ±24 dB なら点は上半分の真ん中までしか来ない。
 * 以前はグラフィックだけ 0 dB の線 1 本で、絵が縦に潰れて見える原因になっていた。
 */
@Composable
private fun rememberDbTicks(
    range: Double,
    measurer: TextMeasurer,
    style: TextStyle,
): List<Pair<Double, TextLayoutResult>> {
    // 単位は一番上の 1 本だけ。5 本すべてに付けると左の余白が広がって絵が痩せる。
    val dbUnit = stringResource(R.string.eq_unit_db)
    return remember(range, style, dbUnit) {
        gridValues(range).map { db ->
            db to measurer.measure(eqGainTick(db.toInt(), if (db == range) dbUnit else null), style)
        }
    }
}

/** 目盛り線と、左の余白に置く数字。線は枠の全幅に引く。 */
private fun DrawScope.drawDbTicks(
    ticks: List<Pair<Double, TextLayoutResult>>,
    plot: Rect,
    muted: Color,
    yOf: (Double) -> Float,
) {
    val gap = LABEL_GAP.toPx()
    ticks.forEach { (db, layout) ->
        val y = yOf(db)
        drawLine(
            muted.copy(alpha = if (db == 0.0) ZERO_LINE_ALPHA else GRID_LINE_ALPHA),
            Offset(plot.left, y),
            Offset(plot.right, y),
            1.dp.toPx(),
        )
        drawText(layout, topLeft = Offset(plot.left - gap - layout.size.width, y - layout.size.height / 2f))
    }
}

/**
 * dB を枠の中の縦位置に直す。0 が上端、1 が下端。
 *
 * **2 つの絵と試験がこれ 1 つを見る。**式を写して置くと、片方だけ直したときに
 * 目盛りと曲線が別の軸で描かれる (見た目はもっともらしいままなので気づけない)。
 */
internal fun plotFraction(db: Double, range: Double): Float = ((1.0 - db / range) / 2.0).toFloat()

/**
 * ドラッグ中は段を下げない。
 *
 * 段は曲線のピークで決まるので、**摘みを下げると段も下がることがある。**段が下がると
 * 絵全体が拡大するので、**指を下げているのに線と点が上へ動く。**
 *
 * ホストで確かめた再現 (`EqCurveTest.theAxisIsHeldWhileAKnobIsMoving`。製品の Q=1.0):
 *
 * ```
 * 10 バンド Q=1.0、125 Hz と 250 Hz だけ +9.0 dB (他は 0 dB)
 *   合成のピーク 12.154 dB          → 段 18 dB
 *   125 Hz を +8.5 dB へ下げる
 *   合成のピーク 11.956 dB          → 段 12 dB
 *   点の縦位置 0.250 → 0.146 = 枠の 10.4% ぶん上へ (132 dp の絵で 14 dp)
 * ```
 *
 * **よく出るのは 18 → 12。**段 12 dB は摘みの可動域 (±12 dB) が下から支えているので、
 * 合成のピークが 12 を割った瞬間に必ず一段落ちる。無作為な 10 バンドの状態から 1 本を
 * 0.5 dB 下げる試行を 20 万回まわすと、段が落ちるのが 1401 件、**そのうち 1187 件 (85%) で
 * 点が指と逆に動いた。**
 *
 * 上げるほうは止めない — 止めると曲線が枠から出る。指を離した時点で本来の段へ戻す。
 */
internal fun holdRange(computed: Double, held: Double, dragging: Boolean): Double =
    if (dragging) max(computed, held) else computed

/**
 * ⚠️ `internal` なのは試験のため。**固定が効くかどうかは再構成をまたいで初めて決まる**ので、
 * [holdRange] 単体では見張れない ([remember] に鍵を付けると毎フレーム忘れて素通しになるが、
 * 絵はもっともらしいまま)。`EqAxisHoldTest` が本物の composition で回している。
 */
@Composable
internal fun rememberPlotRange(response: DoubleArray, bands: List<EqBand>, dragging: Boolean): Double {
    val computed = plotRange(response, bands)
    // **観測される状態にしない。**構成の中で書き戻すので、mutableStateOf だと書いた時点で
    // 再構成が予約されて回り続ける。段を変える再構成はドラッグの値そのものが起こすので、
    // ここは前回の値を覚えておくだけでよい。
    val held = remember { DoubleArray(1) { computed } }
    return holdRange(computed, held[0], dragging).also { held[0] = it }
}

private fun DrawScope.drawCentered(layout: TextLayoutResult, centerX: Float, top: Float) {
    // 端のラベルは中央合わせのままだと画面の外へ出る。幅より狭い canvas でも
    // coerceIn が落ちないよう、上限を 0 で止めてから寄せる。
    val x = (centerX - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width))
    drawText(layout, topLeft = Offset(x, top))
}

/** バンドを等間隔に並べたときの x。1 本しか無いときは真ん中。 */
internal fun evenColumns(count: Int, left: Float, right: Float): FloatArray =
    FloatArray(count) { if (count == 1) (left + right) / 2f else left + (right - left) * it / (count - 1f) }

/*
 * 応答は毎フレーム全部を計算し直している。**これは意図した形。**
 * 走るのは呼び出し側が remember を外すドラッグ中だけで、ホストの JVM で
 * 5 バンド 126 us / 31 バンド 810 us (バンド数 x 標本数に線形)。
 *
 * バンドごとの応答を持っておけば、ドラッグ中に動くのは 1 本なので 31 バンドで 1/31 になる。
 * **実機で要ると分かるまで入れない** — 速くできることと速くする必要があることは別で、
 * 確かめずに持つと要らない状態を抱えるだけになる。
 *
 * **⚠️ もし持たせるなら、無効化の鍵に fc / Q / 種別 / ゲイン / バンド数 / 標本の格子と
 * モードが全部要る。**1 つ落とすと古い曲線が残り、**もっともらしい絵なので誰も気づかない。**
 * 「ドラッグ中に変わるのは 1 本だけ」という前提自体も鍵の一部で、
 * モードの切り替えとバンドの追加・削除で崩れる。
 */

/**
 * バンド中心が等間隔に並ぶ軸の上での、実際に鳴る特性。
 *
 * 中心と中心のあいだは対数周波数で補間する。ISO の中心周波数はほぼ対数等間隔なので、
 * 見た目は対数軸とほとんど変わらないまま、点の x が必ず自分のバンドの真上に来る。
 */
internal fun graphicResponse(bands: List<EqBand>): DoubleArray {
    val freqs = bands.map { ln(it.freqHz.toDouble()) }
    val last = bands.size - 1
    // 1 本しかなくても 2 点返す。1 点だと描画側の (size - 1) が 0 になって x が NaN になる。
    if (last == 0) return DoubleArray(2) { EqSolver.combinedResponseDb(bands, exp(freqs[0])) }
    // バンド 1 つぶんを割り切れる数で刻む。**端数にすると標本がバンド中心を外す** —
    // 曲線が点のすぐ横を通るだけになり、「この列の実際の値」が絵から読めなくなる。
    val perBand = (SAMPLES + last - 1) / last
    return DoubleArray(last * perBand + 1) { s ->
        val i = (s / perBand).coerceAtMost(last - 1)
        val t = (s - i * perBand).toDouble() / perBand
        EqSolver.combinedResponseDb(bands, exp(freqs[i] * (1 - t) + freqs[i + 1] * t))
    }
}

/**
 * バンド中心での実際の応答 (dB10、表示の刻みに丸め)。**グラフィックの点と数値ラベルの
 * 共通の出どころ。**摘みの値もこれ (eq-spec.md §7 の「その中心で実際に鳴る音量」)。
 *
 * 丸めは求解側 (`EqSolver.withGraphicTarget`) と同じ `Math.round`。求解が
 * 「丸めた応答 = 目標」まで詰めるので、目標どおりに解けた並びではラベルが
 * スライダーの表示と桁まで一致する。
 */
internal fun centreGainsDb10(response: DoubleArray, bandCount: Int): IntArray {
    val step = if (bandCount > 1) (response.size - 1) / (bandCount - 1) else 0
    return IntArray(bandCount) { Math.round(response[it * step] * EqUnits.GAIN_SCALE).toInt() }
}

/** バンドごとの応答を対数の周波数軸で標本化する。合成はこれを足して作る。 */
internal fun parametricResponse(bands: List<EqBand>): List<DoubleArray> {
    val span = ln(AXIS_HI_HZ / AXIS_LO_HZ)
    val hz = DoubleArray(SAMPLES) { exp(ln(AXIS_LO_HZ) + span * it / (SAMPLES - 1)) }
    return bands.map { band -> DoubleArray(SAMPLES) { EqSolver.bandResponseDb(band, hz[it]) } }
}

internal fun sumColumns(series: List<DoubleArray>): DoubleArray =
    DoubleArray(SAMPLES) { i -> series.sumOf { it[i] } }

/**
 * 縦軸の範囲。曲線とバンドのゲインの両方が収まる段を選ぶ。
 *
 * 段にしてあるのは、連続に伸ばすとドラッグのあいだ軸が毎フレーム動いて、
 * 触っていないバンドの点まで揺れるため。既定の ±12 dB を超えるのは、
 * 隣り合うバンドが重なって合成が持ち上がったときと、取り込んだプリセットが強いときだけ。
 */
internal fun plotRange(response: DoubleArray, bands: List<EqBand>): Double {
    val peak = max(
        response.maxOfOrNull { abs(it) } ?: 0.0,
        bands.maxOfOrNull { abs(it.gainDb10.toDouble() / EqUnits.GAIN_SCALE) } ?: 0.0,
    )
    return PLOT_RANGES_DB.firstOrNull { peak <= it } ?: PLOT_RANGES_DB.last()
}

/** 横線を引く dB。0 を必ず含めた 5 本。 */
internal fun gridValues(range: Double): List<Double> = listOf(range, range / 2, 0.0, -range / 2, -range)

/**
 * 重なるラベルを落として、出せるものだけを返す。
 *
 * 端と [forced] (ドラッグ中のバンド) を先に置き、残りを左から詰める。
 * 幅は実測なので、バンド数だけでなく文字寸法の設定や訳文の長さにも追従する。
 */
internal fun pickLabels(centers: FloatArray, widths: FloatArray, gap: Float, forced: Int): BooleanArray {
    val n = centers.size
    val shown = BooleanArray(n)
    val placed = ArrayList<Int>(n)
    val priority = buildList {
        if (forced in 0 until n) add(forced)
        if (n > 0) add(0)
        if (n > 1) add(n - 1)
        addAll(0 until n)
    }
    for (i in priority) {
        if (shown[i]) continue
        val fits = placed.none { abs(centers[i] - centers[it]) < (widths[i] + widths[it]) / 2f + gap }
        if (!fits) continue
        shown[i] = true
        placed.add(i)
    }
    return shown
}

/** ドラッグ中の値を重ねた並び。設定そのものは変えない。 */
internal fun List<EqBand>.withPreview(target: EqPreviewTarget?, value: Int?): List<EqBand> {
    if (target == null || value == null || target.bandIndex !in indices) return this
    if (target.field == EqField.GRAPHIC_GAIN) {
        return EqSolver.withGraphicTarget(this, target.bandIndex, value)
    }
    return mapIndexed { i, band ->
        if (i != target.bandIndex) {
            band
        } else {
            when (target.field) {
                EqField.FREQUENCY -> band.copy(freqHz = value)
                EqField.Q -> band.copy(q100 = value)
                EqField.GAIN -> band.copy(gainDb10 = value)
                EqField.GRAPHIC_GAIN -> band // 上で返している
            }
        }
    }
}
