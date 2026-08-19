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

        toggleBand("32 Hz")
        assertOneBandIsOpen()
        toggleBand("250 Hz")
        assertOneBandIsOpen()
        toggleBand("32 Hz")
        assertOneBandIsOpen()

        assertHeaderIsThere("32 Hz")
        assertHeaderIsThere("250 Hz")
        assertHeaderIsThere("4 kHz")
    }

    @Test
    fun movingFromTheLastBandToAnEarlierOneDoesNotBreakTheComposition() {
        showBands()

        toggleBand("4 kHz")
        assertOneBandIsOpen()
        toggleBand("250 Hz")
        assertOneBandIsOpen()
        toggleBand("32 Hz")
        assertOneBandIsOpen()
        toggleBand("32 Hz")
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertDoesNotExist()

        assertHeaderIsThere("32 Hz")
        assertHeaderIsThere("250 Hz")
        assertHeaderIsThere("4 kHz")
    }

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
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertDoesNotExist()
    }

    private fun toggleBand(title: String) {
        compose.onNode(header(title)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun assertOneBandIsOpen() {
        compose.onNodeWithText(string(R.string.eq_band_remove)).assertExists()
    }

    private fun assertHeaderIsThere(title: String) {
        compose.onNode(header(title)).assertExists()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
