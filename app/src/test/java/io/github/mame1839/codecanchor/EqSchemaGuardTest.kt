package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `EqSettings` の JSON のキーと [EqSupport.SCHEMA] を一緒に動かすための仕掛け。
 *
 * ### 何が壊れるのか
 *
 * フックは設定を decode して再 encode し、その文字列の hashCode を返す
 * (`AppConfig.hash()`)。アプリはそれと自分の値を突き合わせて「設定が届いた」を判定する。
 * **キーを 1 つ足すと、それを知らない古いフックは再 encode で落とす** — hash は永久に
 * 食い違い、アプリは理由を言えないまま「設定が届いていません」と出し続ける。
 * [EqSupport.SCHEMA] を上げてあれば「フックが古い」と名指しできる。
 *
 * ### この表が保証すること・しないこと
 *
 * **保証する**: いまのキーの集合が、いま宣言している版の欄と一致すること。キーを足して
 * 版を上げ忘れれば落ちる。**保証しない**: 誰かが最新の欄を書き換えて版を据え置くこと
 * (できてしまう)。**気づかずには通れない形**にするのがここの役目で、証明ではない。
 */
@RunWith(RobolectricTestRunner::class)
class EqSchemaGuardTest {

    /**
     * **上げた版が実際にフックから送られること**は `StatusReportSchemaTest.theHookStampsItsSchema`
     * が見張っている (`A2dpHook.buildReport` を実際に呼んで `eqSchema` を確かめる形)。
     * ここはその重複ではなく、**キーの集合と版を一緒に動かす**ほうを持つ。
     * 2 つ揃って初めて「版を上げた」と「上げた版が届く」の両方が閉じる。
     */

    /** 版ごとの `EqSettings` の JSON のキー。**行を書き換えず、必ず足すこと。** */
    private val keysBySchema = mapOf(
        1 to setOf("on", "mode", "n", "pa", "pdb", "b"),
        2 to setOf("on", "mode", "n", "pa", "pdb", "prec", "b"),
    )

    private fun currentKeys(): Set<String> {
        val json = EqSettings().toJson()
        return json.keys().asSequence().toSet()
    }

    @Test
    fun theKeySetMatchesTheDeclaredSchema() {
        val expected = keysBySchema[EqSupport.SCHEMA]
        assertNotNull(
            "EqSupport.SCHEMA = ${EqSupport.SCHEMA} に対応する行が表にない。" +
                "キーを増やしたなら版を上げて行を足すこと",
            expected,
        )
        assertEquals(
            "EqSettings のキーが変わっている。EqSupport.SCHEMA を上げて表に行を足すこと " +
                "(古いフックは知らないキーを落として再 encode するので hash が永久に食い違う)",
            expected,
            currentKeys(),
        )
    }

    /** 版は増える一方で、古い版のキーは必ず残る (減らすと古いフックとの読み合いが別の壊れ方をする)。 */
    @Test
    fun everySchemaOnlyAddsKeys() {
        assertEquals("表の最大の版が宣言と食い違う", EqSupport.SCHEMA, keysBySchema.keys.max())
        keysBySchema.keys.sorted().zipWithNext { older, newer ->
            assertTrue(
                "版 $older のキーが版 $newer で消えている",
                keysBySchema.getValue(newer).containsAll(keysBySchema.getValue(older)),
            )
        }
    }

    /** 版 1 の設定 (`prec` が無い JSON) は標準として読めること。 */
    @Test
    fun settingsWrittenBeforeThisVersionReadAsStandard() {
        val old = EqSettings(enabled = true, bands = listOf(EqBand(1_000, 141, 30))).toJson()
        old.remove("prec")
        assertEquals(EqPrecision.STANDARD, EqSettings.fromJson(old).precision)
    }

    /** 表に無い値は標準へ落とす (壊れた設定で未知の方式を要求しない)。 */
    @Test
    fun unknownPrecisionValuesFallBackToStandard() {
        assertEquals(EqPrecision.STANDARD, EqPrecision.normalize(7))
        assertEquals(EqPrecision.STANDARD, EqPrecision.normalize(-1))
        assertEquals(EqPrecision.HIGH, EqPrecision.normalize(EqPrecision.HIGH))
    }

    /** 往復で落ちないこと。落ちると保存のたびに方式が標準へ戻る。 */
    @Test
    fun precisionSurvivesTheRoundTrip() {
        val eq = EqSettings(enabled = true, precision = EqPrecision.HIGH)
        assertEquals(EqPrecision.HIGH, EqSettings.fromJson(eq.toJson()).precision)
    }
}
