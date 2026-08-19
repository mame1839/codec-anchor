package io.github.mame1839.codecanchor

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.ui.AppNavigation
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var fellThrough = 0

    private val mac = "00:11:22:33:44:55"

    @Test
    fun backReturnsToTheFirstTabAndOnlyThenLeavesTheApp() {
        show()

        openTab(R.string.tab_settings)
        assertShowing(R.string.section_backup)

        pressBack()
        assertShowing(R.string.section_audio_devices)
        assertEquals("タブから戻るときに系へ抜けた", 0, fellThrough)

        pressBack()
        assertEquals("機器タブで戻るが系へ渡らなかった", 1, fellThrough)
    }

    @Test
    fun theStatusTabAlsoReturnsToTheFirstTab() {
        show()

        openTab(R.string.tab_status)
        pressBack()

        assertShowing(R.string.section_audio_devices)
        assertEquals(0, fellThrough)
    }

    @Test
    fun backClosesTheDeviceBeforeLeavingTheApp() {
        show()
        openDevice()

        pressBack()
        assertShowing(R.string.section_audio_devices)
        assertEquals("詳細を閉じるときに系へ抜けた", 0, fellThrough)

        pressBack()
        assertEquals(1, fellThrough)
    }

    @Test
    fun backFromTheEqScreenReturnsToTheDevice() {
        show()
        openDevice()
        openEq()

        pressBack()
        assertShowing(R.string.section_target)
        assertEquals(0, fellThrough)

        pressBack()
        assertShowing(R.string.section_audio_devices)
        assertEquals(0, fellThrough)
    }

    @Test
    fun theOpenTabAndDeviceSurviveARotation() {
        val restoration = StateRestorationTester(compose)
        val application = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(application)
        vm.ensureProfile(mac)
        restoration.setContent {
            MaterialTheme {
                AppNavigation(
                    vm = vm,
                    snackbarHostState = SnackbarHostState(),
                    onRequestPermission = {},
                    onNotify = {},
                )
            }
        }

        openTab(R.string.tab_status)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertShowing(R.string.module_checking)

        openTab(R.string.tab_devices)
        openDevice()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertShowing(R.string.section_target)
    }

    private fun show() {
        compose.activity.onBackPressedDispatcher.addCallback(
            compose.activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    fellThrough++
                }
            },
        )
        val application = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(application)
        vm.ensureProfile(mac)
        compose.setContent {
            MaterialTheme {
                AppNavigation(
                    vm = vm,
                    snackbarHostState = SnackbarHostState(),
                    onRequestPermission = {},
                    onNotify = {},
                )
            }
        }
        assertShowing(R.string.section_audio_devices)
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun click(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun openTab(label: Int) = click(hasText(string(label)) and hasClickAction())

    private fun openDevice() {
        click(hasText(mac, substring = true) and hasClickAction())
        assertShowing(R.string.section_target)
    }

    private fun openEq() {
        click(hasText(string(R.string.section_eq), substring = true) and hasClickAction())
        assertShowing(R.string.eq_enabled)
    }

    private fun assertShowing(id: Int) {
        compose.onNodeWithText(string(id), substring = true).assertExists()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
