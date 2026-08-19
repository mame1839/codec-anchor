package io.github.mame1839.codecanchor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

sealed interface EqScale {
    fun toPosition(value: Int): Float

    fun fromPosition(position: Float): Int

    class Linear(private val range: IntRange, private val step: Int) : EqScale {
        override fun toPosition(value: Int): Float =
            ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)

        override fun fromPosition(position: Float): Int {
            val raw = range.first + position * (range.last - range.first)
            return ((raw / step).roundToInt() * step).coerceIn(range)
        }
    }

    class Log(private val range: IntRange) : EqScale {
        private val lo = range.first.toDouble()
        private val span = ln(range.last.toDouble() / lo)

        override fun toPosition(value: Int): Float =
            (ln(value.toDouble() / lo) / span).toFloat().coerceIn(0f, 1f)

        override fun fromPosition(position: Float): Int =
            significant3(lo * Math.E.pow(span * position)).coerceIn(range)

        private fun significant3(value: Double): Int {
            val unit = when {
                value < 1_000 -> 1
                value < 10_000 -> 10
                else -> 100
            }
            return (value / unit).roundToInt() * unit
        }
    }
}

@Composable
fun EqSliderRow(
    label: String,
    value: Int,
    scale: EqScale,
    valueText: (Int) -> String,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    previewKey: EqPreviewTarget? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember(value) { mutableIntStateOf(value) }
    val shown = if (dragging) local else value
    val preview = LocalEqPreview.current

    if (previewKey != null && preview != null) {
        DisposableEffect(previewKey) { onDispose { preview.clear(previewKey) } }
    }

    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText(shown),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = scale.toPosition(shown),
            onValueChange = { position ->
                dragging = true
                local = scale.fromPosition(position)
                if (previewKey != null) preview?.show(previewKey, local)
            },
            onValueChangeFinished = {
                dragging = false
                if (previewKey != null) preview?.clear(previewKey)
                if (local != value) onCommit(local)
            },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
