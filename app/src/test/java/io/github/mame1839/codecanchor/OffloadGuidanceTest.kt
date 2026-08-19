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

/**
 * **オフロードの切り方が、困っている画面のその場に出ること。**
 *
 * 直した不具合は文言そのものではなく**置き場**だった — 音響処理の画面が
 * 「切り方は機器一覧の案内に書いてあります」と別の画面を指しており、しかも
 * 指し先の名前が実物と違っていた (実際に出るのは状態タブ)。
 *
 * **ここが無いと、`OffloadTurnOffLines` の呼び出しを両方から消しても全部緑になる。**
 * 文言の入れ替えは既存のどのテストも見ていないので、**戻せてしまうことが問題。**
 */
@RunWith(RobolectricTestRunner::class)
class OffloadGuidanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun string(id: Int): String = app.getString(id)

    /** オフロードが効いている報告が 1 通届いた状態。これが無いと状態タブのカードごと出ない。 */
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

    /**
     * **音響処理の画面にも同じ案内が出ること。**ここが本題 — 以前はこの画面に
     * 「別の画面に書いてあります」しか無かった。
     */
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

    /**
     * 解放そのものを切っているときは「スコープに設定アプリを足せ」を出さない。
     * 足しても解放されないので、出すと外れた案内になる。
     */
    @Test
    fun theScopeHintIsHiddenWhenTheUserTurnedTheInterventionOff() {
        val vm = offloaded()
        vm.updateFreeOffloadSwitch(false)
        compose.setContent {
            MaterialTheme { StatusTab(vm = vm, contentPadding = PaddingValues(0.dp), onNotify = {}) }
        }
        // 切り方そのものは出したまま。消すのはスコープの行だけ。
        compose.onNodeWithText(string(R.string.offload_turn_off)).assertExists()
        compose.onNodeWithText(string(R.string.offload_hint_scope)).assertDoesNotExist()
    }

    /** 介入が入っていて、まだ名乗りが来ていないなら出す (これが出ないと上の試験が空振りになる)。 */
    @Test
    fun theScopeHintShowsWhileTheInterventionIsOn() {
        val vm = offloaded()
        compose.setContent {
            MaterialTheme { StatusTab(vm = vm, contentPadding = PaddingValues(0.dp), onNotify = {}) }
        }
        compose.onNodeWithText(string(R.string.offload_hint_scope)).assertExists()
    }
}
