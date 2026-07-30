package io.github.mame1839.codecanchor.ui

import io.github.mame1839.codecanchor.core.CodecKeys
import io.github.mame1839.codecanchor.core.DeviceProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun codecLabel(codecType: Int, codecNames: Map<Int, String>): String =
    codecNames[codecType] ?: CodecKeys.FALLBACK_CODEC_NAMES[codecType] ?: "コーデック $codecType"

fun profileSummary(profile: DeviceProfile, codecNames: Map<Int, String>): String {
    if (!profile.hasAnyTarget()) return "固定する項目はまだありません"
    return buildList {
        if (profile.codecType != CodecKeys.KEEP_INT) add(codecLabel(profile.codecType, codecNames))
        if (profile.sampleRate != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(CodecKeys.SAMPLE_RATES, profile.sampleRate))
        }
        if (profile.bitsPerSample != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(CodecKeys.BIT_DEPTHS, profile.bitsPerSample))
        }
        if (profile.channelMode != CodecKeys.KEEP_MASK) {
            add(CodecKeys.maskToLabels(CodecKeys.CHANNEL_MODES, profile.channelMode))
        }
        if (profile.codecSpecific1 != CodecKeys.KEEP_LONG) {
            add(CodecKeys.ldacQualityLabel(profile.codecSpecific1).substringBefore(" ("))
        }
    }.joinToString(" · ")
}

fun millisLabel(ms: Int): String = when {
    ms <= 0 -> "すぐに"
    ms % 1000 == 0 -> "${ms / 1000} 秒"
    else -> String.format(Locale.US, "%.1f 秒", ms / 1000f)
}

fun clockLabel(timestamp: Long): String =
    if (timestamp <= 0) "-" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
