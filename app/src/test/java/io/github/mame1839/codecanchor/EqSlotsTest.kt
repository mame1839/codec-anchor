package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlot
import io.github.mame1839.codecanchor.core.EqSlotBook
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EqSlotsTest {

    private val macA = "AA:BB:CC:DD:EE:01"
    private val macB = "AA:BB:CC:DD:EE:02"

    private val curveA = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val curveB = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = -25)),
        preampDb10 = -60,
    )

    @Test
    fun bookRoundTrips() {
        val book = EqSlotBook(
            mapOf(
                macA to DeviceSlots(),
                macB to DeviceSlots(
                    active = "2",
                    slots = listOf(EqSlot("1", "バスブースト", curveA), EqSlot("2", "", curveB)),
                ),
            ),
        )
        assertEquals(book, EqSlotBook.decode(book.encode()))
    }

    @Test
    fun brokenStorageFallsBackToEmpty() {
        assertEquals(EqSlotBook(), EqSlotBook.decode(null))
        assertEquals(EqSlotBook(), EqSlotBook.decode(""))
        assertEquals(EqSlotBook(), EqSlotBook.decode("{"))
        assertEquals(EqSlotBook(), EqSlotBook.decode("""{"other":1}"""))
    }

    @Test
    fun unknownVersionIsRejected() {
        val book = EqSlotBook(mapOf(macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "x", curveA)))))
        val next = JSONObject(book.encode()).put("v", 2).toString()
        assertEquals(EqSlotBook(), EqSlotBook.decode(next))
    }

    @Test
    fun slotsMissingIdOrEqAreDropped() {
        val json = """
            {"v":1,"devices":{"$macA":{"active":"2","slots":[
                {"name":"id なし","eq":${curveA.toJson()}},
                {"id":"2","name":"eq なし"},
                {"id":"3","name":"生きている","eq":${curveB.toJson()}}
            ]}}}
        """.trimIndent()
        val slots = EqSlotBook.decode(json).devices[macA]!!.slots
        assertEquals(listOf("3"), slots.map { it.id })
    }

    @Test
    fun untouchedSettingsAreNeutral() {
        assertTrue(EqSlotBook.isNeutral(EqSettings()))
    }

    @Test
    fun zeroGainGraphicIsNeutralRegardlessOfShape() {
        val zeroBands = listOf(EqBand(freqHz = 31, q100 = 50, gainDb10 = 0), EqBand(freqHz = 16_000, q100 = 50, gainDb10 = 0))
        assertTrue(EqSlotBook.isNeutral(EqSettings(enabled = true, bandCount = 31, bands = zeroBands)))
        assertTrue(EqSlotBook.isNeutral(EqSettings(enabled = false, bands = zeroBands)))
        assertTrue(EqSlotBook.isNeutral(EqSettings(preampDb10 = 0)))
    }

    @Test
    fun audibleSettingsAreNotNeutral() {
        assertFalse(EqSlotBook.isNeutral(curveA))
        assertFalse(EqSlotBook.isNeutral(EqSettings(preampDb10 = -30)))
    }

    @Test
    fun zeroGainParametricIsNotNeutral() {
        val wip = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 0), EqBand(freqHz = 2_500, q100 = 300, gainDb10 = 0)),
        )
        assertFalse(EqSlotBook.isNeutral(wip))
    }

    @Test
    fun migrationSendsNeutralDevicesToFlat() {
        val book = EqSlotBook().reconciledWith(macA, EqSettings())
        assertEquals(DeviceSlots(), book.devices[macA])
    }

    @Test
    fun migrationSeedsACustomSlotForAudibleSettings() {
        val entry = EqSlotBook().reconciledWith(macA, curveB).devices[macA]!!
        val slot = entry.activeSlot()!!
        assertEquals(curveB, slot.eq)
        assertEquals("", slot.name)
        assertEquals(1, entry.slots.size)
    }

    @Test
    fun reconcileCopiesProfileEqIntoTheActiveSlot() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(
                    active = "2",
                    slots = listOf(EqSlot("1", "残す", curveA), EqSlot("2", "選択中", curveB)),
                ),
            ),
        )
        val entry = before.reconciledWith(macA, curveA).devices[macA]!!
        assertEquals(EqSlot("2", "選択中", curveA), entry.activeSlot())
        assertEquals(EqSlot("1", "残す", curveA), entry.slots[0])
        assertEquals(2, entry.slots.size)
    }

    @Test
    fun reconcileLeavesAMatchingBookUnchanged() {
        val book = EqSlotBook(mapOf(macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "x", curveA)))))
        assertEquals(book, book.reconciledWith(macA, curveA))
    }

    @Test
    fun editingWhileFlatLandsInANewSlot() {
        val before = EqSlotBook(
            mapOf(macA to DeviceSlots(active = EqSlotBook.FLAT_ID, slots = listOf(EqSlot("1", "残す", curveA)))),
        )
        val entry = before.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals(EqSlot("1", "残す", curveA), entry.slots[0])
        assertEquals(curveB, entry.activeSlot()!!.eq)
        assertEquals(2, entry.slots.size)
    }

    @Test
    fun danglingActiveIsRebuilt() {
        val orphaned = EqSlotBook(
            mapOf(macA to DeviceSlots(active = "9", slots = listOf(EqSlot("1", "残す", curveA)))),
        )
        val flat = orphaned.reconciledWith(macA, EqSettings()).devices[macA]!!
        assertEquals(EqSlotBook.FLAT_ID, flat.active)
        assertEquals(1, flat.slots.size)

        val seeded = orphaned.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals(curveB, seeded.activeSlot()!!.eq)
        assertEquals(2, seeded.slots.size)
    }

    @Test
    fun seededIdsDoNotCollide() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(
                    active = EqSlotBook.FLAT_ID,
                    slots = listOf(EqSlot("1", "", curveA), EqSlot("3", "", curveA)),
                ),
            ),
        )
        val entry = before.reconciledWith(macA, curveB).devices[macA]!!
        assertEquals("4", entry.active)
        assertEquals(3, entry.slots.size)
    }

    @Test
    fun startupReconcileMigratesEveryProfileAndDropsOrphans() {
        val before = EqSlotBook(
            mapOf(
                macA to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "ずれている", curveA))),
                macB to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "孤児", curveA))),
            ),
        )
        val profiles = mapOf(
            macA to DeviceProfile(mac = macA, eq = curveB),
            "AA:BB:CC:DD:EE:03" to DeviceProfile(mac = "AA:BB:CC:DD:EE:03", eq = curveB),
        )
        val after = before.reconciled(profiles)
        assertEquals(setOf(macA, "AA:BB:CC:DD:EE:03"), after.devices.keys)
        assertEquals(curveB, after.devices[macA]!!.activeSlot()!!.eq)
        assertEquals("ずれている", after.devices[macA]!!.activeSlot()!!.name)
        assertEquals(curveB, after.devices["AA:BB:CC:DD:EE:03"]!!.activeSlot()!!.eq)
        assertNull(after.devices[macB])
    }
}
