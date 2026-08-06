package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

// プリセットは AppConfig に載せない。フックが必要とするのは「いま適用中の EQ」だけで、
// 載せると hash の契約が広がり、ブロードキャストが Binder のトランザクション上限へ近づく。
data class EqPreset(val name: String, val settings: EqSettings) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("eq", settings.toJson())
    }

    companion object {
        const val FORMAT = "codec-anchor-eq-preset"
        const val FORMAT_VERSION = 1

        fun fromJson(o: JSONObject): EqPreset? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            return EqPreset(name = name, settings = EqSettings.fromJson(o.optJSONObject("eq")))
        }

        /** 1 つ分の書き出し。読み込み側が「Codec Anchor のプリセットか」を判定できる形にする。 */
        fun encodeSingle(preset: EqPreset): String = JSONObject().apply {
            put("format", FORMAT)
            put("v", FORMAT_VERSION)
            put("preset", preset.toJson())
        }.toString(2)

        fun decodeSingle(text: String): EqPreset? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (o.optString("format") != FORMAT) return null
            return o.optJSONObject("preset")?.let { fromJson(it) }
        }
    }
}

data class EqPresetBook(val presets: List<EqPreset> = emptyList()) {
    fun encode(): String = JSONObject().apply {
        put("presets", JSONArray().also { a -> presets.forEach { a.put(it.toJson()) } })
    }.toString()

    fun with(preset: EqPreset): EqPresetBook =
        copy(presets = presets.filterNot { it.name == preset.name } + preset)

    fun without(name: String): EqPresetBook = copy(presets = presets.filterNot { it.name == name })

    companion object {
        fun decode(json: String?): EqPresetBook {
            if (json.isNullOrBlank()) return EqPresetBook()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return EqPresetBook()
            val a = o.optJSONArray("presets") ?: return EqPresetBook()
            return EqPresetBook(
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { EqPreset.fromJson(it) } },
            )
        }
    }
}
