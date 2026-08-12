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

/**
 * スロット台帳と MainViewModel の配線 (`llmdocs/eq-slot-design.md` §2)。
 *
 * 一番の見張りは**起動の移行・和解が音を変えない**こと。音を変える経路は commit (設定の保存と
 * フックへのブロードキャスト) と共有メモリへの push しか無いので、「起動で PUSH_CONFIG が
 * 1 通も飛ばない」+「profile.eq が据え置き」で塞がっていることを確かめる。ブロードキャストの
 * 観測自体が生きていることは、updateEq が飛ばす正の対照で確かめる。
 */
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
        preampAuto = false,
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
        // 音の実体 (profile.eq) は据え置き
        assertEquals(curve, vm.config.profileFor(mac)?.eq)
        // 写しが選択中スロットに入る
        assertEquals(curve, vm.slots.devices[mac]?.activeSlot()?.eq)
        // 移行は commit / updateEq を通らない — 設定のブロードキャストが 1 通も出ない
        assertEquals(0, pushConfigCount())
        // 台帳は保存済み (次回の起動は移行なしで同じ形)
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
        // 鳴っている側 (profile.eq) は据え置きで、スロットがそちらへ合わせる。名前は保つ。
        assertEquals(curve, vm.config.profileFor(mac)?.eq)
        assertEquals(EqSlot("1", "前の名前", curve), vm.slots.devices[mac]?.activeSlot())
        assertEquals(0, pushConfigCount())
    }

    @Test
    fun updateEqWritesThroughToTheActiveSlot() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        // updateEq 後は profile.eq と選択中スロットが一致する
        assertEquals(vm.config.profileFor(mac)?.eq, vm.slots.devices[mac]?.activeSlot()?.eq)
        // 続けての編集は同じスロットを上書きする (編集のたびに増えない)
        vm.updateEq(mac) { other }
        assertEquals(other, vm.slots.devices[mac]?.activeSlot()?.eq)
        assertEquals(1, vm.slots.devices[mac]?.slots?.size)
        // 台帳は保存済み
        assertEquals(vm.slots, SlotStore(app).load())
        // 正の対照: updateEq は PUSH_CONFIG を飛ばす。起動系のテストの「0 通」は
        // この観測が生きているうえでの 0 だと分かる。
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
