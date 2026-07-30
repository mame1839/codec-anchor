package io.github.mame1839.codecanchor.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// CodecKeys.LDAC_QUALITIES の値。注釈の訳文を UI 側で足すために参照する。
private const val LDAC_QUALITY_HIGHEST = 1000L
private const val LDAC_QUALITY_LOWEST = 1002L
private const val LDAC_QUALITY_ABR = 1003L

fun codecLabel(codecType: Int, codecNames: Map<Int, String>): String =
    codecNames[codecType] ?: CodecKeys.fallbackName(codecType)

fun channelModes(res: Resources): List<Pair<Int, String>> = listOf(
    CodecKeys.CHANNEL_MODE_MONO to res.getString(R.string.channel_mono),
    CodecKeys.CHANNEL_MODE_STEREO to res.getString(R.string.channel_stereo),
)

fun ldacQualityLabel(res: Resources, value: Long, base: String): String {
    val hint = when (value) {
        LDAC_QUALITY_HIGHEST -> res.getString(R.string.ldac_hint_quality)
        LDAC_QUALITY_LOWEST -> res.getString(R.string.ldac_hint_connection)
        LDAC_QUALITY_ABR -> res.getString(R.string.ldac_hint_adaptive)
        else -> return base
    }
    return res.getString(R.string.ldac_option_annotated, base, hint)
}

fun profileSummary(res: Resources, profile: DeviceProfile, codecNames: Map<Int, String>): String {
    if (!profile.hasAnyTarget()) return res.getString(R.string.profile_no_target)
    return buildList {
        if (profile.codecType != CodecKeys.KEEP_INT) add(codecLabel(profile.codecType, codecNames))
        if (profile.sampleRate != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(CodecKeys.SAMPLE_RATES, profile.sampleRate))
        }
        if (profile.bitsPerSample != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(CodecKeys.BIT_DEPTHS, profile.bitsPerSample))
        }
        if (profile.channelMode != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(channelModes(res), profile.channelMode))
        }
        if (profile.codecSpecific1 != CodecKeys.KEEP_LONG) {
            add(CodecKeys.ldacQualityLabel(profile.codecSpecific1))
        }
    }.joinToString(" · ")
}

// 整数と小数で書式が変わると、固有数字のロケールで同じ画面に別表記が混ざる。
fun millisLabel(res: Resources, ms: Int): String = when {
    ms <= 0 -> res.getString(R.string.value_immediately)
    else -> res.getString(R.string.value_seconds, secondsLabel(ms / 1000.0, if (ms % 1000 == 0) 0 else 1))
}

private fun secondsLabel(seconds: Double, digits: Int): String =
    NumberFormat.getInstance(Locale.getDefault()).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }.format(seconds)

fun retryLabel(res: Resources, count: Int): String =
    res.getQuantityString(R.plurals.retry_count, count, count)

// 適用結果が起きた時刻なので秒まで出す。12/24 時間の設定と語順は端末に合わせる。
fun clockLabel(context: Context, timestamp: Long): String {
    if (timestamp <= 0) return "-"
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
