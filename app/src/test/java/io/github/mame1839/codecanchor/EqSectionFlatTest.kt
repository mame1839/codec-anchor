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
import io.github.mame1839.codecanchor.ui.EqSection
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EqSectionFlatTest {

    @get:Rule
    val compose = createComposeRule()

    private val mac = "AA:BB:CC:DD:EE:03"

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun show(vm: MainViewModel) {
        compose.setContent {
            MaterialTheme {
                Column { EqSection(vm = vm, mac = mac, profile = vm.config.profileFor(mac)!!) }
            }
        }
    }

    private fun tap(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun viewModel(): MainViewModel = MainViewModel(app).also {
        it.ensureProfile(mac)
        it.updateEq(mac) {
            EqSettings(
                enabled = true,
                mode = EqMode.GRAPHIC,
                bandCount = 10,
                bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
            )
        }
    }

    @Test
    fun aCustomSlotShowsTheEditingRows() {
        show(viewModel())

        compose.onNodeWithText(string(R.string.eq_mode)).assertExists()
        compose.onNodeWithText(string(R.string.eq_band_count)).assertExists()
        compose.onNodeWithText(string(R.string.eq_preamp)).assertExists()
        compose.onNodeWithText(string(R.string.eq_reset)).assertExists()
    }

    @Test
    fun flatHidesEveryEditingRowButKeepsTheCurve() {
        show(viewModel())
        tap(string(R.string.eq_slot_flat))

        compose.onNodeWithText(string(R.string.eq_mode)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.eq_band_count)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.eq_preamp)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.eq_reset)).assertDoesNotExist()
        compose.onNode(hasContentDescription(string(R.string.eq_curve_desc))).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_flat)).assertExists()
        compose.onNodeWithText(string(R.string.eq_slot_default, 1)).assertExists()

        tap(string(R.string.eq_slot_default, 1))
        compose.onNodeWithText(string(R.string.eq_mode)).assertExists()
    }

    @Test
    fun turningTheEqualiserOffHidesTheSlotRow() {
        val vm = viewModel()
        show(vm)
        compose.onNodeWithText(string(R.string.eq_slot_flat)).assertExists()

        tap(string(R.string.eq_enabled))
        compose.onNodeWithText(string(R.string.eq_slot_flat)).assertDoesNotExist()
    }
}
