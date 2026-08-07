package io.github.mame1839.codecanchor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.mame1839.codecanchor.core.EqAvailability
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.ui.EqSummaryCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 詳細画面の行が、開かなくても状態を伝えること。
 *
 * **これは見た目ではなく要求。**中身を別の画面へ移した以上、行が「音響処理 >」だけになると、
 * 押す前に見に行く必要があるかを判断できない。移したことで失われやすいのはここなので、
 * 文言ではなく**要約が出ていること**を見る。
 */
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

    // 切ってあるときにモードやバンド数を出すと、効いていない値を読ませることになる。
    @Test
    fun theRowSaysOnlyOffWhileTheEqualiserIsOff() {
        show(on.copy(enabled = false))

        compose.onNodeWithText(string(R.string.eq_summary_off), substring = true).assertExists()
        compose.onNodeWithText(string(R.string.eq_mode_parametric), substring = true).assertDoesNotExist()
    }

    // 理由は開いた先が出す。行が言うのは「使えない」ことだけ。
    @Test
    fun theRowSaysWhenItCannotBeUsed() {
        show(on, EqAvailability.OFFLOAD_ENABLED)

        compose.onNodeWithText(string(R.string.eq_summary_unavailable), substring = true).assertExists()
        compose.onNodeWithText(string(R.string.eq_unavailable_offload), substring = true).assertDoesNotExist()
    }

    @Test
    fun theRowDoesNotSayThatWhileItWorks() {
        show(on)

        compose.onNodeWithText(string(R.string.eq_summary_unavailable), substring = true).assertDoesNotExist()
    }

    @Test
    fun theRowOpensTheScreen() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                EqSummaryCard(eq = on, availability = EqAvailability.OK, onOpen = { opened = true })
            }
        }

        compose.onNodeWithText(string(R.string.section_eq), substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertTrue(opened)
    }

    private fun show(eq: EqSettings, availability: EqAvailability = EqAvailability.OK) {
        compose.setContent {
            MaterialTheme {
                EqSummaryCard(eq = eq, availability = availability, onOpen = {})
            }
        }
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun bands(count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.eq_summary_bands, count, count)
}
