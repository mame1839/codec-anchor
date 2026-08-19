package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqFir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EqFirTest {

    private val real = "CA_EQ_FIR why=block_unfit rate=44100 block=896 frames=1834496 age_ms=18"

    @Test
    fun theRealLineIsParsed() {
        assertEquals("block_unfit", EqFir.whyOf(real))
        assertTrue(EqFir.fellBackToStandard(real))
    }

    @Test
    fun keyOrderDoesNotMatter() {
        val shuffled = "CA_EQ_FIR age_ms=-1 rate=48000 why=no_arena block=960 frames=0"
        assertEquals("no_arena", EqFir.whyOf(shuffled))
        assertTrue(EqFir.fellBackToStandard(shuffled))
    }

    @Test
    fun theLineIsFoundAmongOtherOutput() {
        val stdout = listOf(
            "CA_EQ_SET_BEGIN",
            "枠 0 を選んだ (統計の枠 0 / イヤホン以外 0 / 残骸 1)",
            "残骸の枠を 1 個回収した",
            "CA_EQ_FIR why=not_addressable rate=48000 block=960 frames=12345 age_ms=3",
            "枠 0 に書いた: gen=7 enabled 高精度 bands=10 preamp=-3.00 dB 曲線 gen=2 (fs=48000 Hz で検査済み)",
        ).joinToString("\n")
        assertEquals("not_addressable", EqFir.whyOf(stdout))
        assertTrue(EqFir.fellBackToStandard(stdout))
    }

    @Test
    fun crlfAndLeadingWhitespaceAreTolerated() {
        assertEquals("block_unfit", EqFir.whyOf("CA_EQ_SET_BEGIN\r\n  $real\r\n"))
    }

    @Test
    fun theLastLineWins() {
        val old = "CA_EQ_FIR why=block_unfit rate=44100 block=896 frames=1 age_ms=1"
        val new = "CA_EQ_FIR why=running rate=48000 block=960 frames=2 age_ms=2"
        assertEquals("running", EqFir.whyOf("$old\n$new"))
        assertFalse(EqFir.fellBackToStandard("$old\n$new"))
        assertEquals("block_unfit", EqFir.whyOf("$new\n$old"))
    }

    @Test
    fun aDuplicateKeyTakesTheLaterValue() {
        assertEquals("running", EqFir.whyOf("CA_EQ_FIR why=block_unfit why=running"))
    }

    @Test
    fun exactlyTheseThreeAreReportable() {
        assertEquals(setOf("not_addressable", "no_arena", "block_unfit"), EqFir.REPORTABLE)
    }

    @Test
    fun quietSpellingsDoNotReport() {
        for (why in listOf("running", "no_audio", "not_requested", "no_curve", "design_failed", "warming", "almost")) {
            assertFalse(why, EqFir.fellBackToStandard("CA_EQ_FIR why=$why rate=48000 block=960 frames=1 age_ms=0"))
        }
    }

    @Test
    fun unknownSpellingsAreIgnoredNotReported() {
        val line = "CA_EQ_FIR why=some_future_reason rate=48000 block=960 frames=1 age_ms=0"
        assertEquals("some_future_reason", EqFir.whyOf(line))
        assertFalse(EqFir.fellBackToStandard(line))
    }

    @Test
    fun brokenLinesNeverReportAndNeverThrow() {
        val broken = listOf(
            "",
            "\n\n",
            "CA_EQ_FIR",
            "CA_EQ_FIR ",
            "CA_EQ_FIR why",
            "CA_EQ_FIR why rate=48000",
            "CA_EQ_FIR =block_unfit",
            "CA_EQ_FIR = why=",
            "CA_EQ_FIR rate=44100 block=896",
            "CA_EQ_FIRX why=block_unfit",
            "XCA_EQ_FIR why=block_unfit",
            "ca_eq_fir why=block_unfit",
            "CA_EQ_SET_BEGIN",
            "エフェクトのインスタンスが 1 度も作られていない",
        )
        for (stdout in broken) {
            assertFalse("入力: \"$stdout\"", EqFir.fellBackToStandard(stdout))
        }
    }

    @Test
    fun aLineWithoutTheKeyYieldsNull() {
        assertNull(EqFir.whyOf("CA_EQ_FIR rate=44100 block=896 frames=1 age_ms=0"))
        assertNull(EqFir.whyOf("枠 0 に書いた"))
        assertNull(EqFir.whyOf(""))
    }

    @Test
    fun anEmptyWhyDoesNotReport() {
        assertFalse(EqFir.fellBackToStandard("CA_EQ_FIR why= rate=44100"))
    }

    @Test
    fun reportableSpellingsExistInTheNativeHeader() {
        val header = repoFile("app/src/main/cpp/ca_eq_pick.h").readText()
        val native = Regex("""case FirWhy::k\w+:\s*return "([a-z_]+)";""")
            .findAll(header)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            "ca_eq_pick.h の firWhyToken から綴りを読めなかった。書き方を変えたなら、この検査の読み方も直すこと",
            native.isNotEmpty(),
        )
        for (spelling in EqFir.REPORTABLE) {
            assertTrue("\"$spelling\" が firWhyToken に無い", spelling in native)
        }
    }

    @Test
    fun theLineTagMatchesTheNativeWriter() {
        val cpp = repoFile("app/src/main/cpp/caeqset.cpp").readText()
        assertTrue(
            "caeqset.cpp に \"${EqFir.LINE_TAG} ${EqFir.WHY_KEY}=%s\" の printf が無い",
            cpp.contains("${EqFir.LINE_TAG} ${EqFir.WHY_KEY}=%s"),
        )
        assertEquals("CA_EQ_FIR", EqFir.LINE_TAG)
        assertEquals("why", EqFir.WHY_KEY)
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = File(dir, relative)
            if (here.isFile) return here
            dir = dir.parentFile
        }
        throw AssertionError(
            "$relative が見つからない (作業ディレクトリ ${File("").absolutePath})。" +
                "移動したなら、この検査の探し方も直すこと",
        )
    }
}
