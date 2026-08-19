package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

data class CodecInfo(
    val codecType: Int,
    val codecName: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channelMode: Int,
    val codecSpecific1: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", codecType)
        put("name", codecName)
        put("sr", sampleRate)
        put("bps", bitsPerSample)
        put("cm", channelMode)
        put("cs1", codecSpecific1)
    }

    fun displayName(): String = codecName.ifBlank { CodecKeys.fallbackName(codecType) }

    fun summary(): String = buildString {
        val name = displayName()
        append(name)
        val sr = CodecKeys.SAMPLE_RATES.firstOrNull { it.first == sampleRate }?.second
        val bps = CodecKeys.BIT_DEPTHS.firstOrNull { it.first == bitsPerSample }?.second
        if (sr != null) append(" · ").append(sr)
        if (bps != null) append(" / ").append(bps)
        if (CodecKeys.isLdac(name) && codecSpecific1 > 0) {
            val q = CodecKeys.LDAC_QUALITIES.firstOrNull { it.first == codecSpecific1 }?.second
            if (q != null) append(" · ").append(q.substringBefore(" ("))
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = CodecInfo(
            codecType = o.optInt("type", CodecKeys.KEEP_INT),
            codecName = o.optString("name", ""),
            sampleRate = o.optInt("sr", 0),
            bitsPerSample = o.optInt("bps", 0),
            channelMode = o.optInt("cm", 0),
            codecSpecific1 = o.optLong("cs1", 0),
        )
    }
}

enum class ApplyOutcome {
    NONE, APPLIED, FAILED, UNDECIDED;

    companion object {
        fun from(name: String?): ApplyOutcome = entries.firstOrNull { it.name == name } ?: NONE
    }
}

data class DeviceStatus(
    val mac: String,
    val name: String = "",
    val connected: Boolean = false,
    val active: Boolean = false,
    val current: CodecInfo? = null,
    val ldacQualityMode: String = "",
    val ldacBitrateKbps: Int = 0,
    val selectable: List<CodecInfo> = emptyList(),
    val local: List<CodecInfo> = emptyList(),
    val outcome: ApplyOutcome = ApplyOutcome.NONE,
    val outcomeValue: String = "",
    val updatedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mac", mac)
        put("name", name)
        put("connected", connected)
        put("active", active)
        current?.let { put("current", it.toJson()) }
        put("ldacMode", ldacQualityMode)
        put("ldacKbps", ldacBitrateKbps)
        put("selectable", JSONArray().also { a -> selectable.forEach { a.put(it.toJson()) } })
        put("local", JSONArray().also { a -> local.forEach { a.put(it.toJson()) } })
        put("outcome", outcome.name)
        put("outcomeValue", outcomeValue)
        put("updatedAt", updatedAt)
    }

    fun capabilityOf(codecType: Int): CodecInfo? = selectable.firstOrNull { it.codecType == codecType }

    companion object {
        fun fromJson(o: JSONObject) = DeviceStatus(
            mac = o.optString("mac").uppercase(),
            name = o.optString("name", ""),
            connected = o.optBoolean("connected", false),
            active = o.optBoolean("active", false),
            current = o.optJSONObject("current")?.let { CodecInfo.fromJson(it) },
            ldacQualityMode = o.optString("ldacMode", ""),
            ldacBitrateKbps = o.optInt("ldacKbps", 0),
            selectable = o.optJSONArray("selectable").toCodecList(),
            local = o.optJSONArray("local").toCodecList(),
            outcome = ApplyOutcome.from(o.optString("outcome")),
            outcomeValue = o.optString("outcomeValue", ""),
            updatedAt = o.optLong("updatedAt", 0),
        )

        private fun JSONArray?.toCodecList(): List<CodecInfo> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { CodecInfo.fromJson(it) } }
        }
    }
}

data class StatusReport(
    val moduleVersion: String = "",
    val hostPackage: String = "",
    val configHash: Int = 0,
    val configLoaded: Boolean = false,
    val a2dpOffloadEnabled: Boolean = false,
    val eqSchema: Int = 0,
    val devices: List<DeviceStatus> = emptyList(),
    val codecNames: Map<Int, String> = emptyMap(),
    val timestamp: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("moduleVersion", moduleVersion)
        put("hostPackage", hostPackage)
        put("configHash", configHash)
        put("configLoaded", configLoaded)
        put("a2dpOffload", a2dpOffloadEnabled)
        put("eqSchema", eqSchema)
        put("ts", timestamp)
        put("devices", JSONArray().also { a -> devices.forEach { a.put(it.toJson()) } })
        put("codecNames", JSONObject().also { obj -> codecNames.forEach { (k, v) -> obj.put(k.toString(), v) } })
    }

    fun encode(): String = toJson().toString()

    companion object {
        fun decode(json: String?): StatusReport? {
            if (json.isNullOrBlank()) return null
            return try {
                fromJson(JSONObject(json))
            } catch (t: Throwable) {
                null
            }
        }

        fun fromJson(o: JSONObject): StatusReport {
            val devices = o.optJSONArray("devices")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { DeviceStatus.fromJson(it) } }
            } ?: emptyList()
            val names = mutableMapOf<Int, String>()
            o.optJSONObject("codecNames")?.let { obj ->
                obj.keys().forEach { k -> k.toIntOrNull()?.let { names[it] = obj.optString(k) } }
            }
            return StatusReport(
                moduleVersion = o.optString("moduleVersion", ""),
                hostPackage = o.optString("hostPackage", ""),
                configHash = o.optInt("configHash", 0),
                configLoaded = o.optBoolean("configLoaded", false),
                a2dpOffloadEnabled = o.optBoolean("a2dpOffload", false),
                eqSchema = o.optInt("eqSchema", 0),
                devices = devices,
                codecNames = names,
                timestamp = o.optLong("ts", 0),
            )
        }
    }
}
