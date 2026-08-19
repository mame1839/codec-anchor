package io.github.mame1839.codecanchor

import android.app.Application
import io.github.mame1839.codecanchor.bridge.SlotStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPreset
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.flatEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EqSlotSelectionTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    private val curveA = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val curveB = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = -25)),
        preampDb10 = -60,
    )

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun viewModel(): MainViewModel = MainViewModel(app).also { it.ensureProfile(mac) }

    private fun MainViewModel.eqOf(): EqSettings? = config.profileFor(mac)?.eq

    @Test
    fun theFlatCurveIsWhatTheLedgerCallsNeutral() {
        val bases = listOf(
            EqSettings(enabled = true),
            curveA,
            curveB,
            EqSettings(enabled = true, bandCount = 31, preampDb10 = -120),
        )
        for (base in bases) {
            val flat = flatEq(base)
            assertTrue("$base -> $flat が中立でない", EqSlotBook.isNeutral(flat))
            assertTrue(EqSolver.graphicTargetsDb10(flat.bands).all { it == 0 })
            assertEquals(base.enabled, flat.enabled)
        }
    }

    @Test
    fun switchingSlotsLeavesTheCurveYouCameFromAlone() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active

        vm.addSlot(mac)
        val second = vm.slotsOf(mac).active
        assertNotEquals(first, second)
        vm.updateEq(mac) { curveB }

        vm.selectSlot(mac, first)
        assertEquals(curveA, vm.eqOf())
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals(curveB, vm.slotsOf(mac).slot(second)?.eq)

        vm.selectSlot(mac, second)
        assertEquals(curveB, vm.eqOf())
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals(2, vm.slotsOf(mac).slots.size)
    }

    @Test
    fun selectingFlatSilencesTheSoundWithoutAddingASlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val custom = vm.slotsOf(mac).active

        vm.selectSlot(mac, EqSlotBook.FLAT_ID)
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)
        assertEquals(1, vm.slotsOf(mac).slots.size)
        assertEquals(curveA, vm.slotsOf(mac).slot(custom)?.eq)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        assertEquals(true, vm.eqOf()?.enabled)

        vm.selectSlot(mac, custom)
        assertEquals(curveA, vm.eqOf())
    }

    @Test
    fun addSlotStartsFlatAndSelectsItself() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active

        vm.addSlot(mac)
        val added = vm.slotsOf(mac).active
        assertNotEquals(first, added)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        assertEquals(vm.eqOf(), vm.slotsOf(mac).slot(added)?.eq)
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals("", vm.slotsOf(mac).slot(added)?.name)
    }

    @Test
    fun applyingAPresetLandsInANewSlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val working = vm.slotsOf(mac).active
        vm.savePreset("夜用", curveB)

        vm.applyPreset(mac, "夜用")
        val landed = vm.slotsOf(mac).active
        assertNotEquals(working, landed)
        assertEquals(curveB, vm.eqOf())
        assertEquals("夜用", vm.slotsOf(mac).slot(landed)?.name)
        assertEquals(curveA, vm.slotsOf(mac).slot(working)?.eq)
    }

    @Test
    fun landingAnExternalCurveNeverTurnsTheEqualiserOff() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        vm.savePreset("切れたまま", curveB.copy(enabled = false))

        vm.applyPreset(mac, "切れたまま")
        assertEquals(true, vm.eqOf()?.enabled)
        assertEquals(true, vm.slotsOf(mac).activeSlot()?.eq?.enabled)
    }

    @Test
    fun duplicatingCopiesTheCurveIntoANewUnnamedSlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val source = vm.slotsOf(mac).active
        vm.renameSlot(mac, source, "昼用")

        vm.duplicateSlot(mac, source)
        val copy = vm.slotsOf(mac).active
        assertNotEquals(source, copy)
        assertEquals(curveA, vm.slotsOf(mac).slot(copy)?.eq)
        assertEquals("", vm.slotsOf(mac).slot(copy)?.name)
        assertEquals("昼用", vm.slotsOf(mac).slot(source)?.name)
    }

    @Test
    fun renamingDoesNotTouchTheSound() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val id = vm.slotsOf(mac).active

        vm.renameSlot(mac, id, "  夜用  ")
        assertEquals("夜用", vm.slotsOf(mac).slot(id)?.name)
        assertEquals(curveA, vm.eqOf())

        vm.renameSlot(mac, id, "   ")
        assertEquals("", vm.slotsOf(mac).slot(id)?.name)
        assertEquals(curveA, vm.eqOf())
    }

    @Test
    fun deletingTheSlotYouAreOnFallsBackToFlat() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val id = vm.slotsOf(mac).active

        vm.deleteSlot(mac, id)
        assertNull(vm.slotsOf(mac).slot(id))
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        assertEquals(true, vm.eqOf()?.enabled)
    }

    @Test
    fun deletingAnotherSlotDoesNotChangeWhatIsPlaying() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val other = vm.slotsOf(mac).active
        vm.addSlot(mac)
        val current = vm.slotsOf(mac).active
        vm.updateEq(mac) { curveB }

        vm.deleteSlot(mac, other)
        assertEquals(current, vm.slotsOf(mac).active)
        assertEquals(curveB, vm.eqOf())
        assertEquals(1, vm.slotsOf(mac).slots.size)
    }

    @Test
    fun slotIdsAreNotReusedAfterADelete() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active
        vm.addSlot(mac)
        val second = vm.slotsOf(mac).active
        assertEquals(listOf("1", "2"), listOf(first, second))

        vm.deleteSlot(mac, first)
        assertEquals(listOf("2"), vm.slotsOf(mac).slots.map { it.id })
        vm.addSlot(mac)
        assertEquals(listOf("2", "3"), vm.slotsOf(mac).slots.map { it.id })
    }

    @Test
    fun theSelectionSurvivesARestart() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        vm.addSlot(mac)
        vm.updateEq(mac) { curveB }
        val active = vm.slotsOf(mac).active
        assertEquals(vm.slots, SlotStore(app).load())

        val restarted = MainViewModel(app)
        assertEquals(active, restarted.slotsOf(mac).active)
        assertEquals(curveA, restarted.slotsOf(mac).slot("1")?.eq)
        assertEquals(curveB, restarted.eqOf())
    }

    @Test
    fun savingASlotAsAPresetKeepsTheCurve() {
        val vm = viewModel()
        vm.updateEq(mac) { curveB }
        vm.savePreset("寝る前", vm.slotsOf(mac).activeSlot()!!.eq)
        assertEquals(EqPreset("寝る前", curveB), vm.presets.presets.single())
    }
}
