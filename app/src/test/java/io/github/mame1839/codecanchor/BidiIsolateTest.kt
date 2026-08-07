package io.github.mame1839.codecanchor

import android.icu.text.Bidi
import io.github.mame1839.codecanchor.ui.bidiIsolate
import io.github.mame1839.codecanchor.ui.eqFrequencyText
import io.github.mame1839.codecanchor.ui.eqGainText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RTL のロケールで、数字と単位の並びが入れ替わらないこと。
 *
 * **段落の向きは RTL のまま固定して測る。** Compose の `Text` は `textDirection` を指定しないと
 * 段落方向を `LocalLayoutDirection` に強制するので (`Unspecified` は「内容から決める」ではない)、
 * アラビア語の画面では**中身が何であれ段落は RTL**。ここでも同じ条件を作る。
 *
 * 並べ替えは `android.icu.text.Bidi` に任せる。`StaticLayout` が使うのと同じ ICU の実装なので、
 * 実機の描画と同じ規則で並ぶ。**画面に映る形そのものではないので、実機での目視の代わりにはならない** —
 * ここが見張るのは「囲みを外したら並びが崩れる」という関係だけ。
 */
@RunWith(RobolectricTestRunner::class)
class BidiIsolateTest {

    // アラビア語の書式は translatable="false" なので、値の書式は既定のものと同じ。
    private val db = "%1\$s dB"
    private val hz = "%1\$s Hz"
    private val kHz = "%1\$s kHz"

    @Test
    fun aValueWithAUnitFlipsWithoutTheIsolate() {
        assertEquals("Hz 32", visualInRtl("32 Hz"))
        assertEquals("dB 1.5+", visualInRtl("+1.5 dB"))
        // 符号が末尾へ回ると +0.2 と -0.2 が読み分けられない。ここが一番重い。
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

    // 周りにアラビア語の文があっても、値の中身は動かないこと。埋め込みが本番の使われ方
    // (「バージョン %1$s」「自動: %1$s」)。
    @Test
    fun theIsolateSurvivesBeingPutInsideASentence() {
        val sentence = "الإصدار %s"
        for (value in VALUES) {
            val visual = visualInRtl(sentence.format(bidiIsolate(value)))
            assertEquals("「$value」が壊れた: $visual", true, visual.contains(value))
        }
    }

    // 実際に画面へ出る書式が、囲みを通っていること。
    @Test
    fun theEqFormattersAreIsolated() {
        assertEquals("+1.5 dB", visualInRtl(eqGainText(15, db)))
        assertEquals("-0.2 dB", visualInRtl(eqGainText(-2, db)))
        assertEquals("0.0 dB", visualInRtl(eqGainText(0, db)))
        assertEquals("32 Hz", visualInRtl(eqFrequencyText(32, hz, kHz)))
        assertEquals("1.25 kHz", visualInRtl(eqFrequencyText(1_250, hz, kHz)))
    }

    // 段落を RTL に固定して並べ替え、制御文字を落として「画面に見える並び」にする。
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
