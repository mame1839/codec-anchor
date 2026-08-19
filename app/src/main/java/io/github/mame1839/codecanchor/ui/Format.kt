package io.github.mame1839.codecanchor.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import io.github.mame1839.codecanchor.R
import io.github.mame1839.codecanchor.core.CodecInfo
import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LDAC_QUALITY_HIGHEST = 1000L
private const val LDAC_QUALITY_LOWEST = 1002L
private const val LDAC_QUALITY_ABR = 1003L

fun codecLabel(codecType: Int, codecNames: Map<Int, String>): String =
    codecNames[codecType] ?: CodecKeys.fallbackName(codecType)

fun channelModes(res: Resources): List<Pair<Int, String>> = listOf(
    CodecKeys.CHANNEL_MODE_MONO to res.getString(R.string.channel_mono),
    CodecKeys.CHANNEL_MODE_STEREO to res.getString(R.string.channel_stereo),
)

fun ldacQualityText(value: Long): String = bidiIsolate(CodecKeys.ldacQualityLabel(value))

fun ldacQualityLabel(res: Resources, value: Long, base: String): String {
    val quality = bidiIsolate(base)
    val hint = when (value) {
        LDAC_QUALITY_HIGHEST -> res.getString(R.string.ldac_hint_quality)
        LDAC_QUALITY_LOWEST -> res.getString(R.string.ldac_hint_connection)
        LDAC_QUALITY_ABR -> res.getString(R.string.ldac_hint_adaptive)
        else -> return quality
    }
    return res.getString(R.string.ldac_option_annotated, quality, hint)
}

fun profileSummary(res: Resources, profile: DeviceProfile, codecNames: Map<Int, String>): String {
    if (!profile.hasAnyTarget()) return res.getString(R.string.profile_no_target)
    return buildList {
        if (profile.codecType != CodecKeys.KEEP_INT) add(codecLabel(profile.codecType, codecNames))
        if (profile.sampleRate != CodecKeys.KEEP_MASK) {
            add(bidiIsolate(CodecKeys.maskToLabels(CodecKeys.SAMPLE_RATES, profile.sampleRate)))
        }
        if (profile.bitsPerSample != CodecKeys.KEEP_MASK) {
            add(bidiIsolate(CodecKeys.maskToLabels(CodecKeys.BIT_DEPTHS, profile.bitsPerSample)))
        }
        if (profile.channelMode != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(channelModes(res), profile.channelMode))
        }
        if (profile.codecSpecific1 != CodecKeys.KEEP_LONG) {
            add(ldacQualityText(profile.codecSpecific1))
        }
    }.joinToString(" · ")
}

fun codecSummary(info: CodecInfo): String = bidiIsolate(info.summary())

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

fun bidiIsolate(text: String): String = if (text.isEmpty()) text else "\u2068$text\u2069"

fun eqGainText(db10: Int, format: String, signed: Boolean = db10 > 0): String =
    bidiIsolate(format.format(Locale.getDefault(), decimal(db10 / 10.0, 1, 1, signed = signed)))

fun eqFrequencyText(hz: Int, hzFormat: String, kiloFormat: String): String =
    bidiIsolate(
        if (hz >= 1_000) {
            kiloFormat.format(Locale.getDefault(), decimal(hz / 1_000.0, 0, 2))
        } else {
            hzFormat.format(Locale.getDefault(), decimal(hz.toDouble(), 0, 0))
        },
    )

fun eqGainNumber(db10: Int): String = decimal(db10 / 10.0, 1, 1, signed = db10 > 0)

fun eqGainTick(db: Int, format: String? = null): String {
    val text = decimal(db.toDouble(), 0, 0, signed = db > 0)
    return format?.format(Locale.getDefault(), text) ?: text
}

fun eqFrequencyShort(hz: Int, kiloFormat: String): String =
    if (hz >= 1_000) {
        kiloFormat.format(Locale.getDefault(), decimal(hz / 1_000.0, 0, 2))
    } else {
        decimal(hz.toDouble(), 0, 0)
    }

fun eqQText(q100: Int): String = decimal(q100 / 100.0, 2, 2)

fun eqCountText(value: Int): String = decimal(value.toDouble(), 0, 0)

private fun decimal(value: Double, minDigits: Int, maxDigits: Int, signed: Boolean = false): String =
    NumberFormat.getInstance(Locale.getDefault()).apply {
        minimumFractionDigits = minDigits
        maximumFractionDigits = maxDigits
        if (signed && this is DecimalFormat) positivePrefix = "+"
    }.format(value)

fun clockLabel(context: Context, timestamp: Long): String {
    if (timestamp <= 0) return "-"
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
