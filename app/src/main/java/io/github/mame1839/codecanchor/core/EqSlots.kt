package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

data class EqSlot(val id: String, val name: String, val eq: EqSettings) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("eq", eq.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): EqSlot? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val eq = o.optJSONObject("eq") ?: return null
            return EqSlot(id = id, name = o.optString("name"), eq = EqSettings.fromJson(eq))
        }
    }
}

data class DeviceSlots(
    val active: String = EqSlotBook.FLAT_ID,
    val slots: List<EqSlot> = emptyList(),
) {
    fun activeSlot(): EqSlot? = slots.firstOrNull { it.id == active }

    fun slot(id: String): EqSlot? = slots.firstOrNull { it.id == id }

    fun withNewSlot(eq: EqSettings, name: String = ""): DeviceSlots {
        val id = ((slots.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        return copy(active = id, slots = slots + EqSlot(id = id, name = name, eq = eq))
    }

    fun renamed(id: String, name: String): DeviceSlots =
        copy(slots = slots.map { if (it.id == id) it.copy(name = name) else it })

    fun without(id: String): DeviceSlots = copy(
        active = if (active == id) EqSlotBook.FLAT_ID else active,
        slots = slots.filterNot { it.id == id },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        put("slots", JSONArray().also { a -> slots.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): DeviceSlots = DeviceSlots(
            active = o.optString("active").ifEmpty { EqSlotBook.FLAT_ID },
            slots = o.optJSONArray("slots")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { EqSlot.fromJson(it) } }
            }.orEmpty().distinctBy { it.id },
        )
    }
}

data class EqSlotBook(val devices: Map<String, DeviceSlots> = emptyMap()) {

    fun without(mac: String): EqSlotBook = copy(devices = devices - mac.uppercase())

    fun of(mac: String): DeviceSlots = devices[mac.uppercase()] ?: DeviceSlots()

    fun mapDevice(mac: String, transform: (DeviceSlots) -> DeviceSlots): EqSlotBook {
        val key = mac.uppercase()
        return copy(devices = devices + (key to transform(devices[key] ?: DeviceSlots())))
    }

    fun reconciledWith(mac: String, eq: EqSettings): EqSlotBook {
        val key = mac.uppercase()
        val entry = devices[key] ?: DeviceSlots()
        val next = reconcile(entry, eq)
        if (next == entry && key in devices) return this
        return copy(devices = devices + (key to next))
    }

    fun reconciled(profiles: Map<String, DeviceProfile>): EqSlotBook {
        var book = copy(devices = devices.filterKeys { it in profiles })
        profiles.forEach { (mac, p) -> book = book.reconciledWith(mac, p.eq) }
        return book
    }

    private fun reconcile(entry: DeviceSlots, eq: EqSettings): DeviceSlots {
        val active = entry.activeSlot()
        return when {
            active != null ->
                if (active.eq == eq) entry
                else entry.copy(slots = entry.slots.map { if (it.id == entry.active) it.copy(eq = eq) else it })
            isNeutral(eq) -> if (entry.active == FLAT_ID) entry else entry.copy(active = FLAT_ID)
            else -> entry.withNewSlot(eq)
        }
    }

    fun encode(): String = JSONObject().apply {
        put("v", VERSION)
        put("devices", JSONObject().also { obj ->
            devices.forEach { (mac, d) -> obj.put(mac, d.toJson()) }
        })
    }.toString()

    companion object {
        const val FLAT_ID = "flat"

        private const val VERSION = 1

        fun isNeutral(eq: EqSettings): Boolean =
            eq.mode == EqMode.GRAPHIC &&
                eq.bands.all { it.gainDb10 == 0 } &&
                eq.preampDb10 == 0

        fun decode(json: String?): EqSlotBook {
            if (json.isNullOrBlank()) return EqSlotBook()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return EqSlotBook()
            if (o.optInt("v", 0) != VERSION) return EqSlotBook()
            val devicesJson = o.optJSONObject("devices") ?: return EqSlotBook()
            val devices = mutableMapOf<String, DeviceSlots>()
            devicesJson.keys().forEach { mac ->
                devicesJson.optJSONObject(mac)?.let { devices[mac.uppercase()] = DeviceSlots.fromJson(it) }
            }
            return EqSlotBook(devices)
        }
    }
}
