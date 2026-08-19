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

// 摘みの位置 (0..1) と値の対応。値を整数のまま扱うのは hash() の往復一致のため (core/Eq.kt)。
// 位置を分けるのは周波数のため — 線形だと下の 2 オクターブが摘みの数ピクセルに潰れる。
sealed interface EqScale {
    fun toPosition(value: Int): Float

    fun fromPosition(position: Float): Int

    /** 等間隔。ゲインとプリアンプ。[step] は値の刻み (ゲインなら 1 = 0.1 dB)。 */
    class Linear(private val range: IntRange, private val step: Int) : EqScale {
        override fun toPosition(value: Int): Float =
            ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)

        override fun fromPosition(position: Float): Int {
            val raw = range.first + position * (range.last - range.first)
            return ((raw / step).roundToInt() * step).coerceIn(range)
        }
    }

    /** 対数。周波数と Q。 */
    class Log(private val range: IntRange) : EqScale {
        private val lo = range.first.toDouble()
        private val span = ln(range.last.toDouble() / lo)

        override fun toPosition(value: Int): Float =
            (ln(value.toDouble() / lo) / span).toFloat().coerceIn(0f, 1f)

        override fun fromPosition(position: Float): Int =
            significant3(lo * Math.E.pow(span * position)).coerceIn(range)

        // 有効数字 3 桁に丸める。丸めないと 20 kHz 側では摘みの 1 ピクセルが 20 Hz 以上を
        // またぐので、「12483 Hz」のような読み取れない値がそのまま出る。
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

/**
 * 値を 1 つ持つスライダーの行。
 *
 * ⚠️ ドラッグ中は [onCommit] を呼ばない — commit() にデバウンスが無く、変更ごとに
 * 保存 + Bluetooth 送信が同期に走る。[previewKey] は絵 ([EqCurve]) だけをドラッグ中の値に
 * 追従させ、設定は書き換えない。
 */
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

    // 指を離す前に行ごと消えることがある (バンド削除等)。掛けっぱなしだと絵が固まる。
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
        // 目盛り (steps) は付けない — 0.1 dB 刻みだと 239 本並んで帯にしか見えない。丸めは fromPosition 側。
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
