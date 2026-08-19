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
import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqCurveGrid
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

val EQ_GAIN_RANGE = -120..120
const val EQ_GAIN_STEP = 1

internal val PLOT_RANGES_DB = listOf(12.0, 18.0, 24.0, 40.0)

internal const val PLOT_OVERSHOOT_DB = 2.5

internal const val AXIS_LO_HZ = 20.0
internal const val AXIS_HI_HZ = 20_000.0

internal const val SAMPLES = 160

private val PLOT_HEIGHT = 132.dp
private val LABEL_GAP = 6.dp
private val DOT_RADIUS = 3.5.dp
private val DOT_RADIUS_ACTIVE = 5.dp
private val CURVE_WIDTH = 2.5.dp
private val BAND_WIDTH = 1.dp
private val COLUMN_WIDTH = 1.5.dp
private val LABEL_MIN_GAP = 4.dp
private val PLOT_INSET = 10.dp

private const val ZERO_LINE_ALPHA = 0.28f

private const val GRID_LINE_ALPHA = 0.14f

private const val COLUMN_GUIDE_ALPHA = 0.10f

enum class EqField { FREQUENCY, Q, GAIN, GRAPHIC_GAIN }

data class EqPreviewTarget(val bandIndex: Int, val field: EqField)

class EqPreview {
    var target by mutableStateOf<EqPreviewTarget?>(null)
        private set
    var value by mutableIntStateOf(0)
        private set

    fun show(target: EqPreviewTarget, value: Int) {
        this.target = target
        this.value = value
    }

    fun clear(target: EqPreviewTarget) {
        if (this.target == target) this.target = null
    }
}

val LocalEqPreview = staticCompositionLocalOf<EqPreview?> { null }

@Composable
fun ColumnScope.EqPreviewHost(content: @Composable ColumnScope.() -> Unit) {
    val scope = this
    val preview = remember { EqPreview() }
    CompositionLocalProvider(LocalEqPreview provides preview) { scope.content() }
}

@Composable
fun EqCurve(eq: EqSettings, modifier: Modifier = Modifier) {
    val preview = LocalEqPreview.current
    val bands = eq.bands.withPreview(preview?.target, preview?.value)
    if (bands.isEmpty()) return
    val active = preview?.target?.bandIndex ?: -1

    val description = stringResource(R.string.eq_curve_desc)
    val measurer = rememberTextMeasurer(cacheSize = 80)
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        textDirection = TextDirection.Ltr,
        letterSpacing = 0.sp,
        color = colors.onSurfaceVariant,
    )

    if (eq.mode == EqMode.GRAPHIC) {
        GraphicPlot(
            bands, eq.precision, active, measurer, labelStyle,
            colors.primary, colors.onSurfaceVariant, description, modifier,
        )
    } else {
        ParametricPlot(bands, active, measurer, labelStyle, colors.primary, colors.onSurfaceVariant, description, modifier)
    }
}

@Composable
private fun GraphicPlot(
    bands: List<EqBand>,
    precision: Int,
    active: Int,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    accent: Color,
    muted: Color,
    description: String,
    modifier: Modifier,
) {
    val kiloShort = stringResource(R.string.eq_unit_khz_short)

    val response = remember(bands, precision) { graphicResponse(bands, precision) }

    val gainLabels = remember(bands, precision, labelStyle) {
        centreGainsDb10(response, bands.size).map { measurer.measure(eqGainNumber(it), labelStyle) }
    }
    val freqLabels = remember(bands, labelStyle, kiloShort) {
        bands.map { measurer.measure(eqFrequencyShort(it.freqHz, kiloShort), labelStyle) }
    }
    val rowHeight = (gainLabels + freqLabels).maxOf { it.size.height }

    val density = LocalDensity.current
    val totalHeight = PLOT_HEIGHT + LABEL_GAP * 2 + with(density) { (rowHeight * 2).toDp() }

    val range = rememberPlotRange(graphicPlotRange(response, bands.size), dragging = active >= 0)
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

    val fill = Path().apply {
        addPath(curve)
        lineTo(plot.right - inset, zero)
        lineTo(plot.left + inset, zero)
        close()
    }
    drawPath(fill, accent.copy(alpha = 0.16f))

    drawDbTicks(ticks, plot, muted, yOf)

    val pitch = if (xs.size > 1) xs[1] - xs[0] else plot.width
    val dotR = min(DOT_RADIUS.toPx(), pitch * 0.22f)
    val dotRActive = min(DOT_RADIUS_ACTIVE.toPx(), pitch * 0.32f)

    val step = if (bands.size > 1) (response.size - 1) / (bands.size - 1) else 0
    bands.indices.forEach { i ->
        val x = xs[i]
        val y = yOf(response[i * step])
        val hot = i == active
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
    val range = rememberPlotRange(parametricPlotRange(composite, bands), dragging = active >= 0)

    val ticks = rememberDbTicks(range, measurer, labelStyle)
    val tickLabels = remember(labelStyle, kiloShort) {
        AXIS_TICKS_HZ.map { measurer.measure(eqFrequencyShort(it, kiloShort), labelStyle) }
    }
    val rowHeight = tickLabels.maxOf { it.size.height }

    val density = LocalDensity.current
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
    val fill = Path().apply {
        addPath(curve)
        lineTo(plot.right, zero)
        lineTo(plot.left, zero)
        close()
    }
    drawPath(fill, accent.copy(alpha = 0.16f))

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

private val AXIS_TICKS_HZ = listOf(20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000)

@Composable
private fun rememberDbTicks(
    range: Double,
    measurer: TextMeasurer,
    style: TextStyle,
): List<Pair<Double, TextLayoutResult>> {
    val dbUnit = stringResource(R.string.eq_unit_db)
    return remember(range, style, dbUnit) {
        gridValues(range).map { db ->
            db to measurer.measure(eqGainTick(db.toInt(), if (db == range) dbUnit else null), style)
        }
    }
}

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

internal fun plotFraction(db: Double, range: Double): Float =
    ((1.0 - db / (range + PLOT_OVERSHOOT_DB)) / 2.0).toFloat()

internal fun holdRange(computed: Double, held: Double, dragging: Boolean): Double =
    if (dragging) max(computed, held) else computed

@Composable
internal fun rememberPlotRange(computed: Double, dragging: Boolean): Double {
    val held = remember { DoubleArray(1) { computed } }
    return holdRange(computed, held[0], dragging).also { held[0] = it }
}

private fun DrawScope.drawCentered(layout: TextLayoutResult, centerX: Float, top: Float) {
    val x = (centerX - layout.size.width / 2f).coerceIn(0f, max(0f, size.width - layout.size.width))
    drawText(layout, topLeft = Offset(x, top))
}

internal fun evenColumns(count: Int, left: Float, right: Float): FloatArray =
    FloatArray(count) { if (count == 1) (left + right) / 2f else left + (right - left) * it / (count - 1f) }

private fun highPrecisionSampler(vertices: List<Pair<Double, Double>>): (Double) -> Double =
    { hz -> AutoEqParser.interpolate(vertices, hz) }

internal fun graphicResponse(
    bands: List<EqBand>,
    precision: Int = EqPrecision.STANDARD,
): DoubleArray {
    val vertices = if (precision == EqPrecision.HIGH) EqCurveGrid.knobPolyline(bands) else emptyList()
    val at: (Double) -> Double =
        if (precision == EqPrecision.HIGH) highPrecisionSampler(vertices)
        else { hz -> EqSolver.combinedResponseDb(bands, hz) }
    val freqs =
        if (precision == EqPrecision.HIGH) vertices.map { ln(it.first) }
        else bands.map { ln(it.freqHz.toDouble()) }
    val last = bands.size - 1
    if (last == 0) return DoubleArray(2) { at(exp(freqs[0])) }
    val perBand = (SAMPLES + last - 1) / last
    return DoubleArray(last * perBand + 1) { s ->
        val i = (s / perBand).coerceAtMost(last - 1)
        val t = (s - i * perBand).toDouble() / perBand
        at(exp(freqs[i] * (1 - t) + freqs[i + 1] * t))
    }
}

internal fun centreGainsDb10(response: DoubleArray, bandCount: Int): IntArray {
    val step = if (bandCount > 1) (response.size - 1) / (bandCount - 1) else 0
    return IntArray(bandCount) { Math.round(response[it * step] * EqUnits.GAIN_SCALE).toInt() }
}

internal fun parametricResponse(bands: List<EqBand>): List<DoubleArray> {
    val span = ln(AXIS_HI_HZ / AXIS_LO_HZ)
    val hz = DoubleArray(SAMPLES) { exp(ln(AXIS_LO_HZ) + span * it / (SAMPLES - 1)) }
    return bands.map { band -> DoubleArray(SAMPLES) { EqSolver.bandResponseDb(band, hz[it]) } }
}

internal fun sumColumns(series: List<DoubleArray>): DoubleArray =
    DoubleArray(SAMPLES) { i -> series.sumOf { it[i] } }

internal fun plotRange(knobPeakDb: Double, curvePeakDb: Double): Double =
    PLOT_RANGES_DB.firstOrNull { knobPeakDb <= it && curvePeakDb <= it + PLOT_OVERSHOOT_DB }
        ?: PLOT_RANGES_DB.last()

internal fun graphicPlotRange(response: DoubleArray, bandCount: Int): Double = plotRange(
    knobPeakDb = (centreGainsDb10(response, bandCount).maxOfOrNull { abs(it) } ?: 0) /
        EqUnits.GAIN_SCALE.toDouble(),
    curvePeakDb = response.maxOfOrNull { abs(it) } ?: 0.0,
)

internal fun parametricPlotRange(composite: DoubleArray, bands: List<EqBand>): Double = plotRange(
    knobPeakDb = bands.maxOfOrNull { abs(it.gainDb10.toDouble()) / EqUnits.GAIN_SCALE } ?: 0.0,
    curvePeakDb = composite.maxOfOrNull { abs(it) } ?: 0.0,
)

internal fun gridValues(range: Double): List<Double> = listOf(range, range / 2, 0.0, -range / 2, -range)

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
                EqField.GRAPHIC_GAIN -> band
            }
        }
    }
}
