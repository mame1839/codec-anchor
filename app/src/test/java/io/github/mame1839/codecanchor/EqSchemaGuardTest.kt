package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqPrecision
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqSupport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        3 to setOf("on", "mode", "n", "pdb", "prec", "b"),
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

    /**
     * 表の最大の版が宣言と一致し、隣り合う版のキーの集合が必ず違うこと。
     *
     * **「キーは増える一方」ではない** — 版 3 で `pa` (自動プリアンプ) を消した。
     * 減らす側も hash を食い違わせる (古いフックは知らないキーを落とし、消したキーは
     * 自分の既定で書き足す) ので、版を上げる条件は「増えた」ではなく「変わった」。
     * 逆に、キーが変わっていないのに版だけ上がっていたら、それは版の無駄遣い
     * (フックを「古い」と誤って名指しする)。
     */
    @Test
    fun everySchemaChangesTheKeySet() {
        assertEquals("表の最大の版が宣言と食い違う", EqSupport.SCHEMA, keysBySchema.keys.max())
        keysBySchema.keys.sorted().zipWithNext { older, newer ->
            assertNotEquals(
                "版 $older と版 $newer でキーの集合が同じ",
                keysBySchema.getValue(older),
                keysBySchema.getValue(newer),
            )
        }
    }

    /**
     * **キーを消しても [theKeySetMatchesTheDeclaredSchema] は落ちる。**表の行は「その版の
     * キーの集合ちょうど」で、包含ではなく等号で突き合わせているため — 追加も削除も同じ形で
     * 引っ掛かる。ここはその性質そのものを撃つ (見張りが片側しか見ていない、を防ぐ)。
     */
    @Test
    fun theGuardCatchesARemovedKeyToo() {
        val declared = keysBySchema.getValue(EqSupport.SCHEMA)
        assertNotEquals("キーを 1 つ落とした集合が現在の集合と等しい", declared, declared - "pdb")
        assertNotEquals("キーを 1 つ足した集合が現在の集合と等しい", declared, declared + "pa")
    }

    // ---- 自動プリアンプ (`pa`) の廃止と移行 -------------------------------

    /**
     * **`pa: true` を書いた旧 JSON は、旧版が実際に鳴らしていた値へ焼き直される。**
     * 旧版の `pdb` は `pa` が真のあいだ使われていなかった値 (初期値のままか古い手動値) なので、
     * そのまま読むと全プロファイルの音量が予測不能な向きに動く。
     *
     * **新旧を 1 本ずつ与えて結果が違うことを見る。**往復 (`toJson` → `fromJson`) の同値だけを
     * 見る形は、移行の側と保存の側が同時に壊れると素通りする。
     */
    @Test
    fun theAutoPreampFlagIsBakedIntoTheStoredValue() {
        val bands = EqSolver.centerFrequencies(10).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 60) }
        // 旧版が焼いていた値。10 バンドのゲインを全部 +6 dB にした曲線で、Q = 1.0 なら
        // 合成ピークは +10.86 dB (製品の既定 Q = defaultQ(10) = 0.5 なら +17.31 dB)。
        assertEquals(-108, EqSolver.autoPreampDb10(bands))

        val old = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
            .put("pa", true) // 旧版の toJson は必ず書いていた
        assertEquals(-108, EqSettings.fromJson(old).preampDb10)

        // `pa` を書かない新形式は `pdb` をそのまま読む。同じ bands・同じ pdb で結果が違う。
        val new = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
        assertFalse("新形式に pa が残っている", new.has("pa"))
        assertEquals(-30, EqSettings.fromJson(new).preampDb10)

        // 旧版の `pa: false` は手動の値なので、そのまま残る。
        val oldManual = EqSettings(enabled = true, bands = bands, preampDb10 = -30).toJson()
            .put("pa", false)
        assertEquals(-30, EqSettings.fromJson(oldManual).preampDb10)
    }

    /**
     * **移行が「音が変わらない」のはピークが [EqSettings.PREAMP_RANGE] に収まるところまで。**
     * 合成ピークが 40 dB を超える保存値では下限に張り付き、移行の前後で音が変わる
     * (旧: 素の値が `caeqset` へ / 新: −40.0 dB)。
     *
     * クランプ自体は外せない — アプリが範囲外の値を持ったまま `toJson` で書くと、
     * それを読んだフック側の `fromJson` がクランプして再 encode し、`AppConfig.hash()` が
     * 永久に食い違う。ここはその代償を明示するための釘。
     */
    @Test
    fun theMigrationStopsAtTheEdgeOfTheStorableRange() {
        val loud = EqSolver.centerFrequencies(31).map { EqBand(freqHz = it, q100 = 100, gainDb10 = 200) }
        assertTrue(
            "題材のピークが浅くてクランプ域に届いていない",
            EqSolver.autoPreampDb10(loud) < EqSettings.PREAMP_RANGE.first,
        )
        val old = EqSettings(enabled = true, bandCount = 31, bands = loud).toJson().put("pa", true)
        assertEquals(EqSettings.PREAMP_RANGE.first, EqSettings.fromJson(old).preampDb10)
    }

    /** 既定は 0 dB。`-30` のままだと新規機器の平らな設定が中立を外れ、スロットが実体化する。 */
    @Test
    fun theDefaultPreampIsZero() {
        assertEquals(0, EqSettings().preampDb10)
        assertEquals(0, EqSettings.fromJson(JSONObject()).preampDb10)
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
