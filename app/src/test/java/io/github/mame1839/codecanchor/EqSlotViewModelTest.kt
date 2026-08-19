package io.github.mame1839.codecanchor

import android.app.Application
import io.github.mame1839.codecanchor.bridge.SettingsStore
import io.github.mame1839.codecanchor.bridge.SlotStore
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.core.DeviceProfile
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlot
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EqSlotViewModelTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    private val curve = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val other = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = -25)),
        preampDb10 = -60,
    )

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun seedConfig(eq: EqSettings) {
        SettingsStore(app).save(AppConfig().withProfile(DeviceProfile(mac = mac, name = "Seed", eq = eq)))
    }

    private fun pushConfigCount(): Int =
        shadowOf(app).broadcastIntents.count { it.action == Bridge.ACTION_PUSH_CONFIG }

    @Test
    fun startupMigrationSeedsASlotWithoutChangingTheSound() {
        seedConfig(curve)
        val vm = MainViewModel(app)
        assertEquals(curve, vm.config.profileFor(mac)?.eq)
        assertEquals(curve, vm.slots.devices[mac]?.activeSlot()?.eq)
        assertEquals(0, pushConfigCount())
        assertEquals(vm.slots, SlotStore(app).load())
    }

    @Test
    fun startupMigrationSendsNeutralSettingsToFlat() {
        seedConfig(EqSettings())
        val vm = MainViewModel(app)
        assertEquals(DeviceSlots(), vm.slots.devices[mac])
        assertEquals(0, pushConfigCount())
    }

    @Test
    fun startupReconcilePrefersTheSoundingSide() {
        seedConfig(curve)
        SlotStore(app).save(
            EqSlotBook(mapOf(mac to DeviceSlots(active = "1", slots = listOf(EqSlot("1", "前の名前", other))))),
        )
        val vm = MainViewModel(app)
        assertEquals(curve, vm.config.profileFor(mac)?.eq)
        assertEquals(EqSlot("1", "前の名前", curve), vm.slots.devices[mac]?.activeSlot())
        assertEquals(0, pushConfigCount())
    }

    @Test
    fun updateEqWritesThroughToTheActiveSlot() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        assertEquals(vm.config.profileFor(mac)?.eq, vm.slots.devices[mac]?.activeSlot()?.eq)
        vm.updateEq(mac) { other }
        assertEquals(other, vm.slots.devices[mac]?.activeSlot()?.eq)
        assertEquals(1, vm.slots.devices[mac]?.slots?.size)
        assertEquals(vm.slots, SlotStore(app).load())
        assertTrue(pushConfigCount() > 0)
    }

    @Test
    fun removeProfileDropsTheDeviceSlots() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        vm.removeProfile(mac)
        assertNull(vm.slots.devices[mac])
        assertNull(SlotStore(app).load().devices[mac])
    }
}
