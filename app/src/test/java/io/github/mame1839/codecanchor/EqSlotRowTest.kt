package io.github.mame1839.codecanchor

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.ui.EqSlotRow
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EqSlotRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val mac = "AA:BB:CC:DD:EE:02"

    private val curve = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun show(vm: MainViewModel) {
        compose.setContent {
            MaterialTheme {
                Column { EqSlotRow(vm = vm, mac = mac) }
            }
        }
    }

    private fun tap(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun unnamedSlotsShowTheirIdAsTheDefaultName() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        vm.addSlot(mac)
        show(vm)

        compose.onNodeWithText(string(R.string.eq_slot_flat)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_default, 1)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_default, 2)).assertExists()
    }

    @Test
    fun deletingASlotDoesNotRenumberTheOthers() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        vm.addSlot(mac)
        vm.deleteSlot(mac, "1")
        show(vm)

        compose.onNodeWithText(string(R.string.eq_slot_default, 2)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_default, 1)).assertDoesNotExist()
    }

    @Test
    fun aNamedSlotShowsItsName() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        vm.renameSlot(mac, "1", "夜用")
        show(vm)

        compose.onNodeWithText("夜用").assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_default, 1)).assertDoesNotExist()
    }

    @Test
    fun oneTapSwitchesTheSlot() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        show(vm)
        assertEquals("1", vm.slotsOf(mac).active)

        tap(string(R.string.eq_slot_flat))
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)

        tap(string(R.string.eq_slot_default, 1))
        assertEquals("1", vm.slotsOf(mac).active)
        assertEquals(curve, vm.config.profileFor(mac)?.eq)
    }

    @Test
    fun theAddChipMakesANewSlot() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        show(vm)

        compose.onNode(hasContentDescription(string(R.string.cd_eq_slot_add)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(2, vm.slotsOf(mac).slots.size)
        assertEquals("2", vm.slotsOf(mac).active)
        compose.onNodeWithText(string(R.string.eq_slot_default, 2)).assertExists()
    }

    @Test
    fun onlyTheSelectedCustomChipOpensTheMenu() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        show(vm)

        compose.onNode(hasContentDescription(string(R.string.cd_eq_slot_menu))).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_rename)).assertDoesNotExist()

        tap(string(R.string.eq_slot_default, 1))
        compose.onNodeWithText(string(R.string.eq_slot_rename)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_duplicate)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_save_preset)).assertExists()
        compose.onNodeWithText(string(R.string.action_delete)).assertExists()
    }

    @Test
    fun theFlatChipHasNoEditingEntrance() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        show(vm)
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)

        compose.onNode(hasContentDescription(string(R.string.cd_eq_slot_menu))).assertDoesNotExist()
        tap(string(R.string.eq_slot_flat))
        compose.onNodeWithText(string(R.string.eq_slot_rename)).assertDoesNotExist()
    }

    @Test
    fun deletingAsksFirst() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        show(vm)

        tap(string(R.string.eq_slot_default, 1))
        tap(string(R.string.action_delete))
        compose.onNodeWithText(string(R.string.eq_slot_delete_title)).assertExists()
        assertEquals(1, vm.slotsOf(mac).slots.size)

        tap(string(R.string.action_cancel))
        assertEquals(1, vm.slotsOf(mac).slots.size)

        tap(string(R.string.eq_slot_default, 1))
        tap(string(R.string.action_delete))
        tap(string(R.string.action_delete))
        assertEquals(0, vm.slotsOf(mac).slots.size)
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)
    }

    @Test
    fun duplicatingFromTheMenuAddsACopy() {
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { curve }
        show(vm)

        tap(string(R.string.eq_slot_default, 1))
        tap(string(R.string.eq_slot_duplicate))
        assertEquals(2, vm.slotsOf(mac).slots.size)
        assertEquals(curve, vm.slotsOf(mac).slot("2")?.eq)
        assertEquals("2", vm.slotsOf(mac).active)
    }
}
