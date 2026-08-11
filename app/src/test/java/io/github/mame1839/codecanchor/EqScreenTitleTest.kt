package io.github.mame1839.codecanchor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import io.github.mame1839.codecanchor.ui.EQ_TITLE_BAR_HEIGHT
import io.github.mame1839.codecanchor.ui.EqScreenTitle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 音響処理の画面の題が 2 行 (音響処理 + イヤホン名) でも収まること。
 *
 * **バーの高さは中身では伸びない。**`TopAppBar` は `expandedHeight` の高さで組むので、
 * 2 行を積むと**下の行が黙って切れる。**画面には 1 行目だけがきれいに出るので、
 * **目視でも「題が出ている」ようにしか見えない。**
 *
 * ⚠️ Material3 1.4.0 には `subtitle` を受け取る `TopAppBar` があるが **internal で呼べない。**
 * (JVM の署名は public に見えるので、バイトコードだけ見ると使えると誤読する。)
 * だから自前で積み、高さをこちらで決めている。
 *
 * **既定の高さでは入らないことも一緒に見る** — 入らないことを確かめずに高さを足すと、
 * 「元から入っていた」のか「足したから入った」のか分からないまま数字だけが残る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class EqScreenTitleTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "WH-1000XM5 のとても長い名前のイヤホン"

    @Test
    fun bothLinesFitInsideTheBarWeAskFor() {
        showTitle(EQ_TITLE_BAR_HEIGHT)

        val bottom = compose.onNodeWithText(name).getUnclippedBoundsInRoot().bottom
        assertTrue(
            "2 行目がバーからはみ出している: $bottom > $EQ_TITLE_BAR_HEIGHT",
            bottom <= EQ_TITLE_BAR_HEIGHT,
        )
    }

    // 既定の高さのままだと、この 2 行目がバーの外へ出る = 切れる。
    // これが落ちるようになったら Material3 側で高さが変わったということなので、
    // EQ_TITLE_BAR_HEIGHT を測り直す。
    @Test
    fun theDefaultHeightIsNotEnoughForTwoLines() {
        showTitle(TopAppBarDefaults.TopAppBarExpandedHeight)

        val bottom = compose.onNodeWithText(name).getUnclippedBoundsInRoot().bottom
        assertTrue(
            "既定の高さで 2 行が収まっている。EQ_TITLE_BAR_HEIGHT を足す理由が無くなった: $bottom",
            bottom > TopAppBarDefaults.TopAppBarExpandedHeight,
        )
    }

    private fun showTitle(height: Dp) {
        compose.setContent {
            MaterialTheme {
                // 実物と同じものを組む。写すと、片方だけ直したときに測る対象がずれる。
                TopAppBar(title = { EqScreenTitle(name) }, expandedHeight = height)
            }
        }
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
