package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

object EqUnits {
    const val GAIN_SCALE = 10
    const val Q_SCALE = 100
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
        val Q_RANGE = 10..4_000
        val GAIN_RANGE = -400..400

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
    val preampDb10: Int = 0,
    val precision: Int = EqPrecision.STANDARD,
) {
    val firRequested: Boolean
        get() = enabled && mode == EqMode.GRAPHIC && precision == EqPrecision.HIGH

    fun toJson(): JSONObject = JSONObject().apply {
        put("on", enabled)
        put("mode", mode)
        put("n", bandCount)
        put("pdb", preampDb10)
        put("prec", precision)
        put("b", JSONArray().also { a -> bands.forEach { a.put(it.toJson()) } })
    }

    companion object {
        val BAND_COUNTS = listOf(5, 10, 15, 31)
        const val MAX_BANDS = 31
        val PREAMP_RANGE = -400..120

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
                preampDb10 = preampFrom(o, bands),
                precision = EqPrecision.normalize(o.optInt("prec", EqPrecision.STANDARD)),
            )
        }

        private fun preampFrom(o: JSONObject, bands: List<EqBand>): Int =
            if (o.has("pa") && o.optBoolean("pa", false)) {
                EqSolver.autoPreampDb10(bands).coerceIn(PREAMP_RANGE)
            } else {
                o.optInt("pdb", 0).coerceIn(PREAMP_RANGE)
            }

        fun normalizeBandCount(value: Int): Int =
            if (value in BAND_COUNTS) value else 10
    }
}
