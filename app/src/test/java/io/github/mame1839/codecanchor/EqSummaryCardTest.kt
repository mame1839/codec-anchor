package io.github.mame1839.codecanchor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.core.DeviceSlots
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlot
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.ui.EqSummaryCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EqSummaryCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val on = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(100, 141, 0), EqBand(1_000, 141, 25), EqBand(10_000, 141, -30)),
    )

    @Test
    fun theRowShowsTheModeAndHowManyBands() {
        show(on)

        compose.onNodeWithText(string(R.string.eq_mode_parametric), substring = true).assertExists()
        compose.onNodeWithText(bands(3), substring = true).assertExists()
    }

    @Test
    fun theRowSaysOnlyOffWhileTheEqualiserIsOff() {
        show(on.copy(enabled = false))

        compose.onNodeWithText(string(R.string.eq_summary_off), substring = true).assertExists()
        compose.onNodeWithText(string(R.string.eq_mode_parametric), substring = true).assertDoesNotExist()
    }

    @Test
    fun theRowNamesTheSlotYouAreListeningTo() {
        show(on, slots = DeviceSlots(active = "2", slots = listOf(slot("1", "昼用"), slot("2", "夜用"))))

        compose.onNodeWithText("夜用", substring = true).assertExists()
        compose.onNodeWithText("昼用", substring = true).assertDoesNotExist()
    }

    @Test
    fun anUnnamedSlotShowsTheDefaultName() {
        show(on, slots = DeviceSlots(active = "2", slots = listOf(slot("1"), slot("2"))))

        compose.onNodeWithText(string(R.string.eq_slot_default, 2), substring = true).assertExists()
    }

    @Test
    fun theFlatSlotIsNamedToo() {
        show(on, slots = DeviceSlots(active = EqSlotBook.FLAT_ID, slots = listOf(slot("1", "夜用"))))

        compose.onNodeWithText(string(R.string.eq_slot_flat), substring = true).assertExists()
        compose.onNodeWithText("夜用", substring = true).assertDoesNotExist()
    }

    @Test
    fun theRowNamesNoSlotWhileTheEqualiserIsOff() {
        show(on.copy(enabled = false), slots = DeviceSlots(active = "1", slots = listOf(slot("1", "夜用"))))

        compose.onNodeWithText("夜用", substring = true).assertDoesNotExist()
    }

    @Test
    fun theRowOpensTheScreen() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                EqSummaryCard(
                    eq = on,
                    slots = DeviceSlots(),
                    onOpen = { opened = true },
                )
            }
        }

        compose.onNodeWithText(string(R.string.section_eq), substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertTrue(opened)
    }

    private fun slot(id: String, name: String = "") = EqSlot(id = id, name = name, eq = on)

    private fun show(eq: EqSettings, slots: DeviceSlots = DeviceSlots()) {
        compose.setContent {
            MaterialTheme {
                EqSummaryCard(eq = eq, slots = slots, onOpen = {})
            }
        }
    }

    private fun string(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private fun bands(count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.eq_summary_bands, count, count)
}
