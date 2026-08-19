package io.github.mame1839.codecanchor

import android.icu.text.Bidi
import io.github.mame1839.codecanchor.ui.bidiIsolate
import io.github.mame1839.codecanchor.ui.eqFrequencyText
import io.github.mame1839.codecanchor.ui.eqGainText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** RTL で数字と単位の並びが崩れないこと。観点は bidi.md §6。 */
@RunWith(RobolectricTestRunner::class)
class BidiIsolateTest {

    private val db = "%1\$s dB"
    private val hz = "%1\$s Hz"
    private val kHz = "%1\$s kHz"

    @Test
    fun aValueWithAUnitFlipsWithoutTheIsolate() {
        assertEquals("Hz 32", visualInRtl("32 Hz"))
        assertEquals("dB 1.5+", visualInRtl("+1.5 dB"))
        assertEquals("dB 0.2-", visualInRtl("-0.2 dB"))
        assertEquals("kbps 990/909", visualInRtl("990/909 kbps"))
        assertEquals("D5:18:47:31:A4:38", visualInRtl("38:D5:18:47:31:A4"))
        assertEquals("debug-0.2.1", visualInRtl("0.2.1-debug"))
    }

    @Test
    fun theIsolateKeepsTheOrder() {
        for (value in VALUES) {
            assertEquals(value, visualInRtl(bidiIsolate(value)))
        }
    }

    @Test
    fun theIsolateSurvivesBeingPutInsideASentence() {
        val sentence = "الإصدار %s"
        for (value in VALUES) {
            val visual = visualInRtl(sentence.format(bidiIsolate(value)))
            assertEquals("「$value」が壊れた: $visual", true, visual.contains(value))
        }
    }

    @Test
    fun theEqFormattersAreIsolated() {
        assertEquals("+1.5 dB", visualInRtl(eqGainText(15, db)))
        assertEquals("-0.2 dB", visualInRtl(eqGainText(-2, db)))
        assertEquals("0.0 dB", visualInRtl(eqGainText(0, db)))
        assertEquals("32 Hz", visualInRtl(eqFrequencyText(32, hz, kHz)))
        assertEquals("1.25 kHz", visualInRtl(eqFrequencyText(1_250, hz, kHz)))
    }

    private fun visualInRtl(logical: String): String =
        Bidi(logical, Bidi.DIRECTION_RIGHT_TO_LEFT.toInt())
            .writeReordered(Bidi.DO_MIRRORING.toInt() or Bidi.REMOVE_BIDI_CONTROLS.toInt())

    private companion object {
        val VALUES = listOf(
            "32 Hz",
            "1.25 kHz",
            "+1.5 dB",
            "-0.2 dB",
            "990/909 kbps",
            "38:D5:18:47:31:A4",
            "0.2.1-debug",
            "LDAC · 48 kHz / 32 bit · 990/909 kbps",
        )
    }
}
