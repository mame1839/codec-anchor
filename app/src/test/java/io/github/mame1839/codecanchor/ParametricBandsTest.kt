package io.github.mame1839.codecanchor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.ParametricBands
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * パラメトリックのバンドを開き閉じしても Compose のスロットがずれないこと。
 *
 * **値を見る単体テストでは捕まらない。** ずれるのは composition の側で、症状は
 * 「別のスロットに入っている値を別の型として読む」= 再構成の途中の `ClassCastException`。
 * ここでは本物の composition を回して、落ちずに中身が出ることを見る。
 *
 * **末尾のバンドでは再現しない** — ずれるのは後続の兄弟のスロットなので、後ろに誰も居なければ
 * 露見しない。開くのは必ず**先頭と中間**にする。
 *
 * 押すのに `performClick()` (座標を叩く) ではなく semantics の action を使うのは、
 * Robolectric の画面が狭くバンドを開くと後続の見出しが画面の外へ出るため。
 * 見ているのは composition の構造なので、画面に映っているかどうかは条件にしない。
 */
@RunWith(RobolectricTestRunner::class)
class ParametricBandsTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(
            EqBand(freqHz = 32, q100 = 141, gainDb10 = 15),
            EqBand(freqHz = 250, q100 = 141, gainDb10 = -20),
            EqBand(freqHz = 4_000, q100 = 141, gainDb10 = 30),
        ),
    )

    @Test
    fun openingBandsOtherThanTheLastOneDoesNotBreakTheComposition() {
        showBands()

        // 先頭 → 中間 → 先頭。開いたぶんのスロットが後ろのバンドへ流れていれば、
        // このどこかで再構成が落ちる。
        toggleBand("32 Hz")
        assertOneBandIsOpen()
        toggleBand("250 Hz")
        assertOneBandIsOpen()
        toggleBand("32 Hz")
        assertOneBandIsOpen()

        // 開き閉じしたあとも全部のバンドの見出しが残っていること。
        assertHeaderIsThere("32 Hz")
        assertHeaderIsThere("250 Hz")
        assertHeaderIsThere("4 kHz")
    }

    // 末尾から手前へ。**末尾だけを試すと通ってしまう**ので、末尾を開いた状態から
    // 手前へ移る道も通す。
    @Test
    fun movingFromTheLastBandToAnEarlierOneDoesNotBreakTheComposition() {
        showBands()

        toggleBand("4 kHz")
        assertOneBandIsOpen()
        toggleBand("250 Hz")
        assertOneBandIsOpen()
        toggleBand("32 Hz")
        assertOneBandIsOpen()
        // 同じ見出しをもう一度押すと閉じる。開いたぶんのスロットが消える向きも通す。
        toggleBand("32 Hz")
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertDoesNotExist()

        assertHeaderIsThere("32 Hz")
        assertHeaderIsThere("250 Hz")
        assertHeaderIsThere("4 kHz")
    }

    // 見出しは押せる行。同じ文字列はスライダーの値の側にも出るので、押せることで絞る。
    private fun header(title: String) = hasText(title, substring = true) and hasClickAction()

    private fun showBands() {
        val application = RuntimeEnvironment.getApplication()
        compose.setContent {
            MaterialTheme {
                Column {
                    ParametricBands(
                        vm = MainViewModel(application),
                        mac = "00:11:22:33:44:55",
                        eq = settings,
                        gainText = { "${it / 10.0} dB" },
                        frequencyText = { if (it >= 1_000) "${it / 1_000} kHz" else "$it Hz" },
                    )
                }
            }
        }
        // 最初はどれも閉じている。ここが崩れていると以降の判定の意味が変わる。
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertDoesNotExist()
    }

    private fun toggleBand(title: String) {
        compose.onNode(header(title)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // 開いたバンドにしか出ない行。ちょうど 1 つ出ていれば、開いたぶんが composition に載っている。
    private fun assertOneBandIsOpen() {
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertExists()
    }

    private fun assertHeaderIsThere(title: String) {
        compose.onNode(header(title)).assertExists()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
