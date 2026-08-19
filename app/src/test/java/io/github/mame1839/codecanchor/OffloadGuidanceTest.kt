package io.github.mame1839.codecanchor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.StatusReport
import io.github.mame1839.codecanchor.ui.EqUnavailableNotice
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.StatusTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OffloadGuidanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int): String = app.getString(id)

    private fun offloaded(): MainViewModel = MainViewModel(app).also {
        it.onReport(StatusReport(a2dpOffloadEnabled = true, timestamp = System.currentTimeMillis()))
    }

    @Test
    fun theStatusTabSaysHowToTurnOffloadOff() {
        val vm = offloaded()
        compose.setContent {
            MaterialTheme { StatusTab(vm = vm, contentPadding = PaddingValues(0.dp), onNotify = {}) }
        }
        compose.onNodeWithText(string(R.string.offload_turn_off)).assertExists()
    }

    @Test
    fun theAudioProcessingNoticeSaysHowToTurnOffloadOff() {
        val vm = MainViewModel(app)
        compose.setContent {
            MaterialTheme {
                Column { EqUnavailableNotice(vm = vm, availability = EqAvailability.OFFLOAD_ENABLED) }
            }
        }
        compose.onNodeWithText(string(R.string.eq_unavailable_offload)).assertExists()
        compose.onNodeWithText(string(R.string.offload_turn_off)).assertExists()
    }

    @Test
    fun theScopeHintIsHiddenWhenTheUserTurnedTheInterventionOff() {
        val vm = offloaded()
        vm.updateFreeOffloadSwitch(false)
        compose.setContent {
            MaterialTheme { StatusTab(vm = vm, contentPadding = PaddingValues(0.dp), onNotify = {}) }
        }
        compose.onNodeWithText(string(R.string.offload_turn_off)).assertExists()
        compose.onNodeWithText(string(R.string.offload_hint_scope)).assertDoesNotExist()
    }

    @Test
    fun theScopeHintShowsWhileTheInterventionIsOn() {
        val vm = offloaded()
        compose.setContent {
            MaterialTheme { StatusTab(vm = vm, contentPadding = PaddingValues(0.dp), onNotify = {}) }
        }
        compose.onNodeWithText(string(R.string.offload_hint_scope)).assertExists()
    }
}
