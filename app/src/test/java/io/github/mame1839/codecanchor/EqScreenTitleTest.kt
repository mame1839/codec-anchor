package io.github.mame1839.codecanchor

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import io.github.mame1839.codecanchor.ui.EqScreenTitle
import io.github.mame1839.codecanchor.ui.eqTitleBarHeight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 音響処理の画面の題が 2 行でも収まること。バーが中身で伸びない理由・dp/sp の罠は ui-notes.md §1。
 *
 * ⚠️ 端末の文字サイズを変えて測る — 既定サイズだけで測ると、大きくした端末で破れるものを
 * 「収まっている」と書いてしまう。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class EqScreenTitleTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "WH-1000XM5 のとても長い名前のイヤホン"

    @Test
    fun bothLinesFitAtTheDefaultFontSize() = assertBothLinesFit()

    @Config(fontScale = 1.3f)
    @Test
    fun bothLinesFitWithLargerText() = assertBothLinesFit()

    // Android の文字サイズの上限。ここが通れば、間の大きさでも成り立つ。
    @Config(fontScale = 2.0f)
    @Test
    fun bothLinesFitAtTheLargestText() = assertBothLinesFit()

    /** 既定の高さのままだと 2 行目が切れることの確認 (高さを足す理由が実在する証明)。 */
    @Test
    fun theDefaultBarHeightIsNotEnoughForTwoLines() {
        val bottom = showTitleAndMeasure { TopAppBarDefaults.TopAppBarExpandedHeight }

        assertTrue(
            "既定の高さで 2 行が収まっている。高さを広げる理由が無くなった: $bottom",
            bottom > TopAppBarDefaults.TopAppBarExpandedHeight,
        )
    }

    private fun assertBothLinesFit() {
        // 高さは composable の中でしか読めないので、組みながら控える。
        val asked = mutableStateOf(Dp.Unspecified)
        val bottom = showTitleAndMeasure {
            eqTitleBarHeight.also { asked.value = it }
        }

        assertTrue(
            "2 行目がバーからはみ出している: $bottom > ${asked.value}",
            bottom <= asked.value,
        )
    }

    /** 実物と同じ題を [height] のバーに組み、2 行目の下端を返す。 */
    private fun showTitleAndMeasure(height: @androidx.compose.runtime.Composable () -> Dp): Dp {
        compose.setContent {
            MaterialTheme {
                // 実物と同じものを組む。写すと、片方だけ直したときに測る対象がずれる。
                TopAppBar(title = { EqScreenTitle(name) }, expandedHeight = height())
            }
        }
        return compose.onNodeWithText(name).getUnclippedBoundsInRoot().bottom
    }
}
