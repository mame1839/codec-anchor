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

/**
 * 戻るの筋道が段どおりに動くこと。
 *
 * 見張っているのは **「有効な `BackHandler` は常に 1 つ」** という不変条件で、
 * 破れ方は**画面を見ても分からない。**値を見る単体テストでも捕まらない —
 * 壊れるのは composition の側なので、**本物の composition を組んで本物の
 * `OnBackPressedDispatcher` を叩く。**
 *
 * **系へ抜けたことは、先に登録しておいた callback が呼ばれたかで見る。**
 * `OnBackPressedDispatcher` は後から足したものから順に呼ぶので、
 * **先に登録したこれは Compose 側の誰も消費しなかったときにだけ呼ばれる。**
 *
 * ⚠️ **「詳細画面を開いている間にタブの `BackHandler` が横取りする」は、ここでは書けない。**
 * 詳細を開けるのは機器タブだけで、開いている間はタブを変えられないので、
 * **`tab != DEVICES` かつ詳細が開いている状態に到達する道が無い。**
 * 到達できない状態を試したことにしないため、代わりに**段を 1 つずつ**確かめている。
 */
@RunWith(RobolectricTestRunner::class)
class AppNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Compose の誰も消費しなかった戻るの数。 */
    private var fellThrough = 0

    private val mac = "00:11:22:33:44:55"

    @Test
    fun backReturnsToTheFirstTabAndOnlyThenLeavesTheApp() {
        show()

        openTab(R.string.tab_settings)
        assertShowing(R.string.section_backup)

        // 設定タブ → 機器タブ。ここで系へ渡してしまうと、タブを見ただけでアプリが終わる。
        pressBack()
        assertShowing(R.string.section_audio_devices)
        assertEquals("タブから戻るときに系へ抜けた", 0, fellThrough)

        // 機器タブでの戻るは系へ渡す = アプリが終わる。
        // enabled を落とし忘れると、ここで 0 のままになり**アプリを終われなくなる。**
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

    /**
     * 音響処理からの戻るは**一覧ではなく詳細へ**返る。
     *
     * 段を 1 つ飛ばす形 (`selectedMac = null` を一緒にやってしまう) は、
     * 画面としては自然に見えるので目視では気づけない。
     */
    @Test
    fun backFromTheEqScreenReturnsToTheDevice() {
        show()
        openDevice()
        openEq()

        pressBack()
        // 詳細にしか無い行。一覧まで飛んでいればここで落ちる。
        assertShowing(R.string.section_target)
        assertEquals(0, fellThrough)

        pressBack()
        assertShowing(R.string.section_audio_devices)
        assertEquals(0, fellThrough)
    }

    /**
     * 画面を回しても、開いていたタブと機器が残ること。
     *
     * **`rememberSaveable` に enum を渡せるかは、普通に組んだだけでは分からない。**
     * 保存も復元も起きないので、**保存できない型でもテストは緑のまま通り、実機で回した瞬間に
     * 先頭のタブへ戻る** (あるいは落ちる)。ここだけは本物の保存と復元を挟む。
     */
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
        // タブ名そのものは題と下部ナビの両方に出るので、**状態タブの中身にしかない行**で見る。
        //
        // この行が出るのは `AppNavigation` が `vm.refresh()` を呼ばず moduleState が CHECKING の
        // ままだから。**取り直しを `AppNavigation` 側へ移すとここが別の行に変わって落ちる** —
        // 落ちること自体は正しいので、そのときは見る行を差し替える。
        assertShowing(R.string.module_checking)

        // 機器のほうも同じ持ち方なので、一緒に確かめる。
        openTab(R.string.tab_devices)
        openDevice()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertShowing(R.string.section_target)
    }

    private fun show() {
        // 先に登録しておく。後から足す Compose 側が消費しなかったときだけ、これが呼ばれる。
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
        // ボンド済みの機器は Robolectric では出ないので、設定だけがある機器として一覧に出す。
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

    // 押すのに座標ではなく semantics の action を使うのは、Robolectric の画面が狭く、
    // 下部ナビや一覧の行が画面の外に出ることがあるため。見ているのは筋道であって配置ではない。
    private fun click(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun openTab(label: Int) = click(hasText(string(label)) and hasClickAction())

    private fun openDevice() {
        // MAC は bidiIsolate で囲まれているので部分一致で拾う。
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
