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

// CodecKeys.LDAC_QUALITIES の値。注釈の訳文を UI 側で足すために参照する。
private const val LDAC_QUALITY_HIGHEST = 1000L
private const val LDAC_QUALITY_LOWEST = 1002L
private const val LDAC_QUALITY_ABR = 1003L

// 囲まない — 戻り値は表示だけでなく CodecKeys.isLdac() の判定にも渡る。
// コーデック名はラテン文字だけなので、囲まなくても並びは崩れない。
fun codecLabel(codecType: Int, codecNames: Map<Int, String>): String =
    codecNames[codecType] ?: CodecKeys.fallbackName(codecType)

fun channelModes(res: Resources): List<Pair<Int, String>> = listOf(
    CodecKeys.CHANNEL_MODE_MONO to res.getString(R.string.channel_mono),
    CodecKeys.CHANNEL_MODE_STEREO to res.getString(R.string.channel_stereo),
)

// "990/909 kbps" のような、数字と単位でできた値。核となる表記は core が持っているので、
// 画面へ出す入口はここ 1 つにして囲む。
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

// 訳文 (チャンネル) と数字の値が同じ 1 行に並ぶので、値の側だけを囲む。
// 全体を囲むと訳文まで LTR に引きずられる。
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

// 端末が今つないでいるコーデックの一行 ("LDAC · 48 kHz / 32 bit · 990/909 kbps")。
// 訳文が混ざらないので、丸ごと 1 つの塊にしてよい。
fun codecSummary(info: CodecInfo): String = bidiIsolate(info.summary())

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

// \u6570\u5b57\u3068\u5358\u4f4d\u30fb\u533a\u5207\u308a\u306e\u4e26\u3073\u3092\u3001\u5468\u308a\u306e\u6587\u7ae0\u304b\u3089\u5207\u308a\u96e2\u3057\u30661\u3064\u306e\u584a\u306b\u3059\u308b (RTL \u30ed\u30b1\u30fc\u30eb\u3067\u8981\u308b\u3002
// \u8a73\u7d30\u306a\u6a5f\u5e8f\u306f bidi.md)\u3002\u26a0\ufe0f \u5024\u305d\u306e\u3082\u306e\u3092\u66f8\u304d\u63db\u3048\u306a\u3044\u3053\u3068 (\u898b\u3048\u306a\u3044\u6587\u5b57\u304c\u4fdd\u5b58\u30fb\u30ed\u30b0\u30fb\u30d5\u30a1\u30a4\u30eb\u540d\u30fb
// \u6bd4\u8f03\u3078\u7d1b\u308c\u8fbc\u3080)\u3002\u56f2\u3080\u306e\u306f\u753b\u9762\u3078\u51fa\u3059\u76f4\u524d\u3060\u3051\u3002
fun bidiIsolate(text: String): String = if (text.isEmpty()) text else "\u2068$text\u2069"

// EQ の値の書式。単位 (dB / Hz / kHz) は訳語の要らない純粋な書式文字列なので translatable="false"
// にしてあり、呼び出し側が stringResource で取った書式をここへ渡す。
// 書式を Resources から直接引かないのは、新しい Composable のファイルで
// LocalContext.current.resources を書くと lint の LocalContextResourcesRead が新規の警告になり、
// baseline (ファイルパスで照合する) に無いのでビルドが落ちるため。

/** 0.1 dB 単位の値。既定では正の値にだけ + を付ける (差の大きさのような無符号量は signed=false)。 */
fun eqGainText(db10: Int, format: String, signed: Boolean = db10 > 0): String =
    bidiIsolate(format.format(Locale.getDefault(), decimal(db10 / 10.0, 1, 1, signed = signed)))

/** 1 kHz 以上は kHz にする。1250 なら "1.25 kHz"、16000 なら "16 kHz"。 */
fun eqFrequencyText(hz: Int, hzFormat: String, kiloFormat: String): String =
    bidiIsolate(
        if (hz >= 1_000) {
            kiloFormat.format(Locale.getDefault(), decimal(hz / 1_000.0, 0, 2))
        } else {
            hzFormat.format(Locale.getDefault(), decimal(hz.toDouble(), 0, 0))
        },
    )

// Q とバンド数は数字だけなので囲まない — 数字の並びは 1 つの塊のままで、入れ替わる相手がいない。
// 単位を付けるなら、そのときに囲むこと。

/**
 * 曲線の上端に並べるゲイン。単位を付けない。
 *
 * 幅が惜しいのが第一だが、単位を外すと RTL で数値と単位が入れ替わる余地も同時に消える。
 * 単位が要る軸のラベルは [eqGainTick] を使い、描画側で向きを固定する。
 */
fun eqGainNumber(db10: Int): String = decimal(db10 / 10.0, 1, 1, signed = db10 > 0)

/** 目盛りの dB。整数で、正の値にだけ + を付ける。[format] を渡したときだけ単位を添える。 */
fun eqGainTick(db: Int, format: String? = null): String {
    val text = decimal(db.toDouble(), 0, 0, signed = db > 0)
    return format?.format(Locale.getDefault(), text) ?: text
}

/** 軸に並べる周波数。単位を書かず、1 kHz 以上は "1k" のように詰める。 */
fun eqFrequencyShort(hz: Int, kiloFormat: String): String =
    if (hz >= 1_000) {
        kiloFormat.format(Locale.getDefault(), decimal(hz / 1_000.0, 0, 2))
    } else {
        decimal(hz.toDouble(), 0, 0)
    }

/** Q は 0.01 単位。無次元なので単位を付けない。 */
fun eqQText(q100: Int): String = decimal(q100 / 100.0, 2, 2)

/** バンド数のような、単位を持たない整数。 */
fun eqCountText(value: Int): String = decimal(value.toDouble(), 0, 0)

private fun decimal(value: Double, minDigits: Int, maxDigits: Int, signed: Boolean = false): String =
    NumberFormat.getInstance(Locale.getDefault()).apply {
        minimumFractionDigits = minDigits
        maximumFractionDigits = maxDigits
        // 0 には符号を付けない。"+0.0 dB" は持ち上げているようにしか読めない。
        if (signed && this is DecimalFormat) positivePrefix = "+"
    }.format(value)

// 適用結果が起きた時刻なので秒まで出す。12/24 時間の設定と語順は端末に合わせる。
fun clockLabel(context: Context, timestamp: Long): String {
    if (timestamp <= 0) return "-"
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hms"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
