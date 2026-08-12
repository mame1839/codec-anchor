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

/**
 * AutoEQ の取り込みも**新しいスロットに着地する** (`llmdocs/eq-slot-design.md` §1)。
 *
 * プリセットの適用と同じ規則だが、**同じ 1 本を通っていることは値では見えない**ので、
 * 取り込みの経路そのものを走らせる。ここが `updateEq` に戻されると、AutoEQ を試した瞬間に
 * 作りかけの曲線が消える。
 */
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

    /**
     * 取り込みは `viewModelScope` + `Dispatchers.IO` / `Dispatchers.Default` を渡り歩くので、
     * main looper を回しながら終わりを待つ。実際の解析は数ミリ秒で、上限は保険。
     */
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
        // 作りかけは無傷で、チップ 1 タップで戻れる
        assertEquals(working, vm.slotsOf(mac).slot(before)?.eq)
        // 取り込んだほうが鳴っている
        val imported = vm.config.profileFor(mac)?.eq
        assertEquals(imported, vm.slotsOf(mac).slot(landed)?.eq)
        assertEquals(EqMode.GRAPHIC, imported?.mode)
        assertTrue(imported!!.bands.any { it.gainDb10 != 0 })
        // 既定名で出せるよう未命名にしておく (訳文をデータに焼かない)
        assertEquals("", vm.slotsOf(mac).slot(landed)?.name)
    }
}
