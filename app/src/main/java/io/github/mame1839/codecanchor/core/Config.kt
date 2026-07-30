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
    val delayMs: Int = 1500,
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
        fun fromJson(o: JSONObject): DeviceProfile {
            val default = DeviceProfile(mac = o.optString("mac"))
            return DeviceProfile(
                mac = o.optString("mac").uppercase(),
                name = o.optString("name", ""),
                enabled = o.optBoolean("enabled", true),
                codecType = o.optInt("codecType", CodecKeys.KEEP_INT),
                sampleRate = o.optInt("sampleRate", CodecKeys.KEEP_MASK),
                bitsPerSample = o.optInt("bitsPerSample", CodecKeys.KEEP_MASK),
                channelMode = o.optInt("channelMode", CodecKeys.KEEP_MASK),
                codecSpecific1 = o.optLong("cs1", CodecKeys.KEEP_LONG),
                codecSpecific2 = o.optLong("cs2", CodecKeys.KEEP_LONG),
                codecSpecific3 = o.optLong("cs3", CodecKeys.KEEP_LONG),
                codecSpecific4 = o.optLong("cs4", CodecKeys.KEEP_LONG),
                force = o.optBoolean("force", false),
                viaSbc = o.optBoolean("viaSbc", false),
                autoEnableHd = o.optBoolean("autoEnableHd", true),
                delayMs = o.optInt("delayMs", default.delayMs),
                retries = o.optInt("retries", default.retries),
                retryDelayMs = o.optInt("retryDelayMs", default.retryDelayMs),
            )
        }
    }
}

data class AppConfig(
    val enabled: Boolean = true,
    val enforce: Boolean = true,
    val verbose: Boolean = false,
    val profiles: Map<String, DeviceProfile> = emptyMap(),
) {
    fun profileFor(mac: String?): DeviceProfile? = mac?.uppercase()?.let { profiles[it] }

    fun withProfile(profile: DeviceProfile): AppConfig =
        copy(profiles = profiles + (profile.mac.uppercase() to profile))

    fun withoutProfile(mac: String): AppConfig =
        copy(profiles = profiles - mac.uppercase())

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("enabled", enabled)
        put("enforce", enforce)
        put("verbose", verbose)
        put("profiles", JSONObject().also { obj ->
            profiles.forEach { (mac, p) -> obj.put(mac, p.toJson()) }
        })
    }

    fun encode(): String = toJson().toString()

    fun hash(): Int = encode().hashCode()

    companion object {
        const val VERSION = 1

        fun decode(json: String?): AppConfig {
            if (json.isNullOrBlank()) return AppConfig()
            return try {
                fromJson(JSONObject(json))
            } catch (t: Throwable) {
                AppConfig()
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
                profiles = profiles,
            )
        }
    }
}
