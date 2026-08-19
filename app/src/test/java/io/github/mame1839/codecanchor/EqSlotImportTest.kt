package io.github.mame1839.codecanchor

import android.app.Application
import android.net.Uri
import android.os.Looper
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EqSlotImportTest {

    private val mac = "AA:BB:CC:DD:EE:04"

    private val working = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val autoEqText: String =
        checkNotNull(javaClass.getResourceAsStream("/autoeq/DUNU Titan S GraphicEQ.txt")) {
            "テストリソースが無い"
        }.bufferedReader().use { it.readText() }

    private fun settle(vm: MainViewModel) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.pendingMessage != null) return
            Thread.sleep(10)
        }
        fail("取り込みが終わらなかった")
    }

    @Test
    fun importingAutoEqLandsInANewSlot() {
        val uri = Uri.parse("content://io.github.mame1839.codecanchor.test/GraphicEQ.txt")
        shadowOf(app.contentResolver).registerInputStream(uri, autoEqText.byteInputStream())

        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { working }
        val before = vm.slotsOf(mac).active

        vm.importAutoEq(uri, mac, bandCount = 10)
        settle(vm)

        assertEquals(R.string.msg_autoeq_imported, vm.pendingMessage)
        val landed = vm.slotsOf(mac).active
        assertNotEquals(before, landed)
        assertEquals(2, vm.slotsOf(mac).slots.size)
        assertEquals(working, vm.slotsOf(mac).slot(before)?.eq)
        val imported = vm.config.profileFor(mac)?.eq
        assertEquals(imported, vm.slotsOf(mac).slot(landed)?.eq)
        assertEquals(EqMode.GRAPHIC, imported?.mode)
        assertTrue(imported!!.bands.any { it.gainDb10 != 0 })
        assertEquals("", vm.slotsOf(mac).slot(landed)?.name)
    }
}
