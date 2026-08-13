package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

// 値を整数で持つのは表示の都合ではなく同期の契約。
// AppConfig.hash() は encode() の文字列の hashCode で、アプリとフックが同じ文字列を作れることが
// 「設定が届いた」の判定になっている。Float / Double を入れると JSON の表記が処理系や
// 経路で揺れて往復が崩れ、再現しにくい「届いていません」になる。
// 既存の DeviceProfile の 17 フィールドに浮動小数が 1 つも無いのも同じ理由。
object EqUnits {
    const val GAIN_SCALE = 10 // gainDb10  : dB * 10
    const val Q_SCALE = 100 // q100      : Q  * 100
}

object EqBandType {
    const val PEAKING = 0
    const val LOW_SHELF = 1
    const val HIGH_SHELF = 2

    fun normalize(value: Int): Int = if (value in PEAKING..HIGH_SHELF) value else PEAKING
}

object EqMode {
    const val GRAPHIC = 0
    const val PARAMETRIC = 1

    fun normalize(value: Int): Int = if (value == PARAMETRIC) PARAMETRIC else GRAPHIC
}

/**
 * 曲線をどう鳴らすか。**音の内容ではなく作り方の選択**なので、バンドとは別の欄に持つ。
 *
 * - [STANDARD] … バンドを biquad のカスケードで鳴らす (これまでの唯一の方式)
 * - [HIGH] … 摘みの折れ線そのものを目標にした最小位相 FIR。中心と中心のあいだの
 *   ずれ (biquad の裾の重なりで出る) が消える
 *
 * **既定は [STANDARD]。**追加遅延はどちらも 0 で、差は曲線の再現精度だけ
 * (eq-fir-design.md §0)。
 */
object EqPrecision {
    const val STANDARD = 0
    const val HIGH = 1

    fun normalize(value: Int): Int = if (value == HIGH) HIGH else STANDARD
}

data class EqBand(
    val freqHz: Int,
    val q100: Int,
    val gainDb10: Int,
    val type: Int = EqBandType.PEAKING,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("f", freqHz)
        put("q", q100)
        put("g", gainDb10)
        put("t", type)
    }

    companion object {
        val FREQ_RANGE = 1..192_000
        val Q_RANGE = 10..4_000 // 0.10 〜 40.00
        val GAIN_RANGE = -400..400 // -40.0 〜 +40.0 dB

        fun fromJson(o: JSONObject): EqBand = EqBand(
            freqHz = o.optInt("f", 1_000).coerceIn(FREQ_RANGE),
            q100 = o.optInt("q", 141).coerceIn(Q_RANGE),
            gainDb10 = o.optInt("g", 0).coerceIn(GAIN_RANGE),
            type = EqBandType.normalize(o.optInt("t", EqBandType.PEAKING)),
        )
    }
}

data class EqSettings(
    val enabled: Boolean = false,
    val mode: Int = EqMode.GRAPHIC,
    val bandCount: Int = 10,
    val bands: List<EqBand> = emptyList(),
    val preampAuto: Boolean = true,
    val preampDb10: Int = -30,
    val precision: Int = EqPrecision.STANDARD,
) {
    /**
     * 「高精度」を実際に要求するか。**画面に方式の行を出す条件と、`caeqset` へ `--hp` を
     * 渡す条件は、この 1 つの述語。**分けると「切り替えたのに送っていない」が作れる。
     *
     * グラフィックのときだけ。パラメトリックは fc と Q をユーザが決めていて、biquad が
     * 定義どおりの厳密値なので、FIR にしても近似が入るだけ (eq-fir-design.md §0)。
     * **パラメトリックへ移っても選択は消さない** — 戻ったときに選び直させるほうが煩わしい。
     */
    val firRequested: Boolean
        get() = enabled && mode == EqMode.GRAPHIC && precision == EqPrecision.HIGH

    // put は必ず全部呼ぶ。「既定値なら省略」をやるとアプリとフックでキーの数が変わって
    // hash が食い違う。**キーを増やしたら EqSupport.SCHEMA を上げること**
    // (古いフックは知らないキーを落として再 encode するので、hash が永久に食い違う。
    //  EqSchemaGuardTest がこの 2 つを一緒に動かすよう縛っている)。
    fun toJson(): JSONObject = JSONObject().apply {
        put("on", enabled)
        put("mode", mode)
        put("n", bandCount)
        put("pa", preampAuto)
        put("pdb", preampDb10)
        put("prec", precision)
        put("b", JSONArray().also { a -> bands.forEach { a.put(it.toJson()) } })
    }

    companion object {
        val BAND_COUNTS = listOf(5, 10, 15, 31)
        const val MAX_BANDS = 31
        val PREAMP_RANGE = -400..120 // -40.0 〜 +12.0 dB

        fun fromJson(o: JSONObject?): EqSettings {
            if (o == null) return EqSettings()
            val bands = o.optJSONArray("b")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { EqBand.fromJson(it) } }
            }.orEmpty().take(MAX_BANDS)
            return EqSettings(
                enabled = o.optBoolean("on", false),
                mode = EqMode.normalize(o.optInt("mode", EqMode.GRAPHIC)),
                bandCount = normalizeBandCount(o.optInt("n", 10)),
                bands = bands,
                preampAuto = o.optBoolean("pa", true),
                preampDb10 = o.optInt("pdb", -30).coerceIn(PREAMP_RANGE),
                precision = EqPrecision.normalize(o.optInt("prec", EqPrecision.STANDARD)),
            )
        }

        // bandCount に BAND_COUNTS 以外を入れると fromJson がここで 10 に書き換えるので、
        // アプリの encode とフックの再 encode が食い違って hash の往復が永久に壊れる。
        // 値を作る側 (取り込み・バンド数の変更) は必ずここを通す。
        // バンドの本数そのものは bands.size であって、この値ではない
        // (パラメトリックのときは無関係な本数になる)。
        fun normalizeBandCount(value: Int): Int =
            if (value in BAND_COUNTS) value else 10
    }
}
