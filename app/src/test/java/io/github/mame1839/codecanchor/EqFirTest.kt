package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqFir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `CA_EQ_FIR` の行の読み方。
 *
 * **壊れた行・未知の綴り・欠けたキーで落ちないことが主対象** — この行の書き手 (`caeqset`) は
 * 綴りを増やす前提で作られている (`ca_eq_pick.h` の `firWhyToken` が `default:` を持たない)
 * ので、読み手が「知らない = 異常」に倒れると、ネイティブ側の追加のたびにアプリが壊れる。
 */
class EqFirTest {

    /** caeqset が実際に出す形 (caeqset.cpp の printf と同じ並び)。 */
    private val real = "CA_EQ_FIR why=block_unfit rate=44100 block=896 frames=1834496 age_ms=18"

    // --- 正常系 -------------------------------------------------------------

    @Test
    fun theRealLineIsParsed() {
        assertEquals("block_unfit", EqFir.whyOf(real))
        assertTrue(EqFir.fellBackToStandard(real))
    }

    /** キー名で引く。caeqset が並びを変えても読める。 */
    @Test
    fun keyOrderDoesNotMatter() {
        val shuffled = "CA_EQ_FIR age_ms=-1 rate=48000 why=no_arena block=960 frames=0"
        assertEquals("no_arena", EqFir.whyOf(shuffled))
        assertTrue(EqFir.fellBackToStandard(shuffled))
    }

    /** 実際の stdout は印・枠の選択・書いた内容の行に埋まっている。 */
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

    /** 1 回の実行に高々 1 行だが、複数あれば後の行 (新しい状態) を採る。 */
    @Test
    fun theLastLineWins() {
        val old = "CA_EQ_FIR why=block_unfit rate=44100 block=896 frames=1 age_ms=1"
        val new = "CA_EQ_FIR why=running rate=48000 block=960 frames=2 age_ms=2"
        assertEquals("running", EqFir.whyOf("$old\n$new"))
        assertFalse(EqFir.fellBackToStandard("$old\n$new"))
        assertEquals("block_unfit", EqFir.whyOf("$new\n$old"))
    }

    /** 同じキーが 1 行に 2 度あれば後が勝つ (どちらでも壊れないことが本題)。 */
    @Test
    fun aDuplicateKeyTakesTheLaterValue() {
        assertEquals("running", EqFir.whyOf("CA_EQ_FIR why=block_unfit why=running"))
    }

    // --- 画面に出す 3 つだけが出る -------------------------------------------

    /**
     * **literal の釘。**[EqFir.REPORTABLE] を定数参照で書くと、定数の綴りを変えたとき
     * 釘も一緒に動いて永久に落ちない。3 つの選定理由は EqFir の KDoc。
     */
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

    /** ネイティブ側が綴りを足しても、こちらは黙って無視する (嘘の警告を出さない)。 */
    @Test
    fun unknownSpellingsAreIgnoredNotReported() {
        val line = "CA_EQ_FIR why=some_future_reason rate=48000 block=960 frames=1 age_ms=0"
        assertEquals("some_future_reason", EqFir.whyOf(line))
        assertFalse(EqFir.fellBackToStandard(line))
    }

    // --- 壊れた入力 ---------------------------------------------------------

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

    /** `why=` (値が空) は「未知の綴り」と同じ扱い — 出さない側に倒れる。 */
    @Test
    fun anEmptyWhyDoesNotReport() {
        assertFalse(EqFir.fellBackToStandard("CA_EQ_FIR why= rate=44100"))
    }

    // --- ネイティブ側との突き合わせ ------------------------------------------

    /**
     * **同じ綴りが 2 箇所にある** (`ca_eq_pick.h` の `firWhyToken` と [EqFir] の定数)。
     * git は片方だけの変更を衝突と報告しない。食い違うと、`.so` は正しく報告しているのに
     * アプリが二度と警告を出さなくなる — 例外もログも出ない、いちばん追えない形。
     */
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

    /** 行の接頭辞とキー名も caeqset.cpp の printf と対で持っている。 */
    @Test
    fun theLineTagMatchesTheNativeWriter() {
        val cpp = repoFile("app/src/main/cpp/caeqset.cpp").readText()
        assertTrue(
            "caeqset.cpp に \"${EqFir.LINE_TAG} ${EqFir.WHY_KEY}=%s\" の printf が無い",
            cpp.contains("${EqFir.LINE_TAG} ${EqFir.WHY_KEY}=%s"),
        )
        // 定数側の綴りは literal で釘を打つ (定数と printf を同時に変えても、ここが残る)。
        assertEquals("CA_EQ_FIR", EqFir.LINE_TAG)
        assertEquals("why", EqFir.WHY_KEY)
    }

    // EqParamsTest と同じ探し方。単体テストの作業ディレクトリは app/ にもリポジトリ直下にもなる
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
