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
) {
    // put は必ず全部呼ぶ。「既定値なら省略」をやるとアプリとフックでキーの数が変わって
    // hash が食い違う。
    fun toJson(): JSONObject = JSONObject().apply {
        put("on", enabled)
        put("mode", mode)
        put("n", bandCount)
        put("pa", preampAuto)
        put("pdb", preampDb10)
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
                bandCount = bandCount(o.optInt("n", 10)),
                bands = bands,
                preampAuto = o.optBoolean("pa", true),
                preampDb10 = o.optInt("pdb", -30).coerceIn(PREAMP_RANGE),
            )
        }

        private fun bandCount(value: Int): Int =
            if (value in BAND_COUNTS) value else 10
    }
}
