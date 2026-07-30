package io.github.mame1839.codecanchor.core

import org.json.JSONObject

// codecType は -1、マスク系は 0、codecSpecific は -1 が「変更しない」。
data class DeviceProfile(
    val mac: String,
    val name: String = "",
    val enabled: Boolean = true,
    val codecType: Int = CodecKeys.KEEP_INT,
    val sampleRate: Int = CodecKeys.KEEP_MASK,
    val bitsPerSample: Int = CodecKeys.KEEP_MASK,
    val channelMode: Int = CodecKeys.KEEP_MASK,
    val codecSpecific1: Long = CodecKeys.KEEP_LONG,
    val codecSpecific2: Long = CodecKeys.KEEP_LONG,
    val codecSpecific3: Long = CodecKeys.KEEP_LONG,
    val codecSpecific4: Long = CodecKeys.KEEP_LONG,
    val force: Boolean = false,
    val viaSbc: Boolean = false,
    val autoEnableHd: Boolean = true,
    val delayMs: Int = 3000,
    val retries: Int = 3,
    val retryDelayMs: Int = 1500,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("name", name)
        put("enabled", enabled)
        put("codecType", codecType)
        put("sampleRate", sampleRate)
        put("bitsPerSample", bitsPerSample)
        put("channelMode", channelMode)
        put("cs1", codecSpecific1)
        put("cs2", codecSpecific2)
        put("cs3", codecSpecific3)
        put("cs4", codecSpecific4)
        put("force", force)
        put("viaSbc", viaSbc)
        put("autoEnableHd", autoEnableHd)
        put("delayMs", delayMs)
        put("retries", retries)
        put("retryDelayMs", retryDelayMs)
    }

    fun hasAnyTarget(): Boolean =
        codecType != CodecKeys.KEEP_INT ||
            sampleRate != CodecKeys.KEEP_MASK ||
            bitsPerSample != CodecKeys.KEEP_MASK ||
            channelMode != CodecKeys.KEEP_MASK ||
            codecSpecific1 != CodecKeys.KEEP_LONG

    companion object {
        val DELAY_MS_RANGE = 0..60_000
        val RETRIES_RANGE = 1..10
        val RETRY_DELAY_MS_RANGE = 300..30_000

        private val CODEC_TYPE_RANGE = 0..255

        fun fromJson(o: JSONObject): DeviceProfile {
            val default = DeviceProfile(mac = o.optString("mac"))
            return DeviceProfile(
                mac = o.optString("mac").uppercase(),
                name = o.optString("name", ""),
                enabled = o.optBoolean("enabled", true),
                codecType = codecType(o.optInt("codecType", CodecKeys.KEEP_INT)),
                sampleRate = mask(o.optInt("sampleRate", CodecKeys.KEEP_MASK), CodecKeys.SAMPLE_RATE_BITS),
                bitsPerSample = mask(o.optInt("bitsPerSample", CodecKeys.KEEP_MASK), CodecKeys.BIT_DEPTH_BITS),
                channelMode = mask(o.optInt("channelMode", CodecKeys.KEEP_MASK), CodecKeys.CHANNEL_MODE_BITS),
                codecSpecific1 = specific(o.optLong("cs1", CodecKeys.KEEP_LONG)),
                codecSpecific2 = specific(o.optLong("cs2", CodecKeys.KEEP_LONG)),
                codecSpecific3 = specific(o.optLong("cs3", CodecKeys.KEEP_LONG)),
                codecSpecific4 = specific(o.optLong("cs4", CodecKeys.KEEP_LONG)),
                force = o.optBoolean("force", false),
                viaSbc = o.optBoolean("viaSbc", false),
                autoEnableHd = o.optBoolean("autoEnableHd", true),
                delayMs = o.optInt("delayMs", default.delayMs).coerceIn(DELAY_MS_RANGE),
                retries = o.optInt("retries", default.retries).coerceIn(RETRIES_RANGE),
                retryDelayMs = o.optInt("retryDelayMs", default.retryDelayMs).coerceIn(RETRY_DELAY_MS_RANGE),
            )
        }

        // 「変更しない」でも有効値でもない中間の値は、適用も一致判定も通らないまま再試行を使い切るので、
        // 読み込みの時点で「変更しない」側に寄せる。
        private fun mask(value: Int, known: Int): Int =
            if (value < 0) CodecKeys.KEEP_MASK else value and known

        private fun codecType(value: Int): Int =
            if (value in CODEC_TYPE_RANGE) value else CodecKeys.KEEP_INT

        private fun specific(value: Long): Long =
            if (value < 0L) CodecKeys.KEEP_LONG else value
    }
}

data class AppConfig(
    val enabled: Boolean = true,
    val enforce: Boolean = true,
    val verbose: Boolean = false,
    val notifyChanges: Boolean = true,
    val profiles: Map<String, DeviceProfile> = emptyMap(),
) {
    fun profileFor(mac: String?): DeviceProfile? = mac?.uppercase()?.let { profiles[it] }

    // 鍵と profile.mac が食い違うと encode の結果がフック側の再 encode と一致しなくなる
    fun withProfile(profile: DeviceProfile): AppConfig {
        val key = profile.mac.uppercase()
        return copy(profiles = profiles + (key to profile.copy(mac = key)))
    }

    fun withoutProfile(mac: String): AppConfig =
        copy(profiles = profiles - mac.uppercase())

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("enabled", enabled)
        put("enforce", enforce)
        put("verbose", verbose)
        put("notify", notifyChanges)
        put("profiles", JSONObject().also { obj ->
            profiles.forEach { (mac, p) -> obj.put(mac, p.toJson()) }
        })
    }

    fun encode(): String = toJson().toString()

    fun hash(): Int = encode().hashCode()

    companion object {
        const val VERSION = 1

        // 読めなかったときは null。既定値を返すと、呼び出し側が「設定が無い」と区別できず上書きしてしまう。
        fun decode(json: String?): AppConfig? {
            if (json.isNullOrBlank()) return AppConfig()
            return try {
                fromJson(JSONObject(json))
            } catch (t: Throwable) {
                null
            }
        }

        fun fromJson(o: JSONObject): AppConfig {
            val profiles = mutableMapOf<String, DeviceProfile>()
            o.optJSONObject("profiles")?.let { obj ->
                obj.keys().forEach { key ->
                    obj.optJSONObject(key)?.let { child ->
                        val p = DeviceProfile.fromJson(child)
                        val mac = p.mac.ifEmpty { key }.uppercase()
                        profiles[mac] = p.copy(mac = mac)
                    }
                }
            }
            return AppConfig(
                enabled = o.optBoolean("enabled", true),
                enforce = o.optBoolean("enforce", true),
                verbose = o.optBoolean("verbose", false),
                notifyChanges = o.optBoolean("notify", true),
                profiles = profiles,
            )
        }
    }
}
