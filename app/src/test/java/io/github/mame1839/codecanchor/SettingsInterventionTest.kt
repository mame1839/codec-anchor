package io.github.mame1839.codecanchor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.mame1839.codecanchor.bridge.SettingsStore
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsInterventionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int): String = app.getString(id)

    private fun sentToSettingsHook() =
        shadowOf(app).broadcastIntents.filter { it.action == Bridge.ACTION_PUSH_SETTINGS_HOOK }

    @Test
    fun theInterventionIsOnUntilItIsTurnedOff() {
        assertTrue("既定で介入しないと、Xiaomi の端末ではトグルが塞がれたままになる", MainViewModel(app).freeOffloadSwitch)
    }

    @Test
    fun theSwitchInTheSettingsTabTurnsItOff() {
        val vm = MainViewModel(app)
        compose.setContent {
            MaterialTheme { SettingsTab(vm = vm, contentPadding = PaddingValues(0.dp)) }
        }

        compose.onNode(hasText(string(R.string.toggle_free_offload_switch)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertFalse("画面のトグルが ViewModel に届いていない", vm.freeOffloadSwitch)
        assertFalse(
            "保存されていない。設定アプリのプロセスが立ち上がり直したときに元に戻る",
            SettingsStore(app).freeOffloadSwitch(),
        )
    }

    @Test
    fun onlyTheFlagReachesTheSettingsApp() {
        MainViewModel(app).updateFreeOffloadSwitch(false)

        val sent = sentToSettingsHook()
        assertEquals("設定アプリのフック宛ての合図が 1 通ではない", 1, sent.size)
        assertEquals(Bridge.SETTINGS_PACKAGE, sent[0].`package`)
        assertFalse(sent[0].getBooleanExtra(Bridge.EXTRA_FREE_OFFLOAD_SWITCH, true))
        assertNull("設定の JSON が設定アプリへ流れている", sent[0].getStringExtra(Bridge.EXTRA_JSON))
    }

    @Test
    fun theSameValueIsNotSentTwice() {
        val vm = MainViewModel(app)
        vm.updateFreeOffloadSwitch(false)
        vm.updateFreeOffloadSwitch(false)
        assertEquals(1, sentToSettingsHook().size)
    }

    @Test
    fun theAppConfigJsonKeysAreUnchanged() {
        val keys = AppConfig().toJson().keys().asSequence().toSet()
        assertEquals(
            "AppConfig にキーが増えている。Bluetooth 側が使う値でなければ、独立した鍵に置くこと " +
                "(Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH と同じ形)",
            setOf("v", "enabled", "enforce", "verbose", "notify", "profiles"),
            keys,
        )
    }
}
