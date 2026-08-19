package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqParams
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import io.github.mame1839.codecanchor.ui.PREAMP_SCALE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * プリアンプの値が通る門はどこにあるか。
 *
 * 経路は
 * ```
 *   producer (クランプする) -> EqSettings.preampDb10 -> EqParams.arguments (素通し) -> .so
 * ```
 * で、**門は producer 側にしかない。**この 2 つを別々の釘にしてあるのは、
 * 1 本にまとめると「どちらが守っているのか」が読めなくなるため。
 *
 * `.so` 側の門 (`caeq::validate`) は
 * `app/src/main/cpp/test/ca_eq_preamp_boundary_test.cpp` の節 32 が実測で留めてある。
 * この 2 つは同じ数を両側から留める対なので、片方だけ動かすとどちらかが落ちる。
 *
 * Robolectric なのは [EqSettings.fromJson] が `org.json` を通るため
 * (android.jar のスタブでは `optJSONArray` が落ちる)。
 */
@RunWith(RobolectricTestRunner::class)
class EqPreampGateTest {

    /**
     * **適用経路は門ではない。**[EqParams.arguments] は [EqSettings.preampDb10] を
     * クランプも再計算もせずそのまま送る。
     *
     * 範囲外の値をわざと入れて素通しを確かめるのは、**ここを門だと誤解させないため。**
     * producer 側の `coerceIn` を 1 つ落としても arguments は受け止めない
     * (受け止めるようにする修正も誤り — 出どころが 2 つになる)。
     */
    @Test
    fun theApplyPathPassesThePreampThroughWithoutClamping() {
        fun sent(preampDb10: Int): String {
            val args = EqParams.arguments(
                EqSettings(enabled = true, bands = listOf(EqBand(1_000, 141, 0)), preampDb10 = preampDb10),
            )
            return args[args.indexOf("--preamp") + 1]
        }
        assertEquals("-40.0", sent(EqSettings.PREAMP_RANGE.first))
        assertEquals("12.0", sent(EqSettings.PREAMP_RANGE.last))
        // 範囲外もそのまま出る = arguments は検査していない。
        assertEquals("-47.9", sent(-479))
        assertEquals("99.9", sent(999))
    }

    /**
     * **門は producer 側にある。**[EqSettings.preampDb10] を作る経路を 1 つずつ撃って、
     * どれも [EqSettings.PREAMP_RANGE] へ丸めることを確かめる。
     *
     * **列挙であることを承知で列挙する** — 「全部クランプしている」は 1 本のテストでは
     * 証明できないので、**新しい producer を足したらここに 1 行足す**のが契約。
     * 素通しの [theApplyPathPassesThePreampThroughWithoutClamping] と対で読むこと。
     */
    @Test
    fun everyPreampProducerClampsToTheSavedRange() {
        val lo = EqSettings.PREAMP_RANGE.first
        val hi = EqSettings.PREAMP_RANGE.last

        // producer 1: 旧版からの移行 (pa=true)。上の測定どおり -479 が出る形を通す。
        val migrated = EqSettings.fromJson(
            JSONObject(
                """{"on":true,"mode":1,"pa":true,"b":[""" +
                    List(4) { """{"f":1000,"q":141,"g":120,"t":0}""" }.joinToString(",") +
                    """]}""",
            ),
        )
        assertEquals(-479, EqSolver.autoPreampDb10(migrated.bands))
        assertEquals("移行はクランプする", lo, migrated.preampDb10)

        // producer 2: 保存された手動値 (pdb)。両端の外を撃つ。
        assertEquals(lo, EqSettings.fromJson(JSONObject("""{"on":true,"pdb":-9999}""")).preampDb10)
        assertEquals(hi, EqSettings.fromJson(JSONObject("""{"on":true,"pdb":9999}""")).preampDb10)

        // producer 3: AutoEQ の取り込み (ParametricEQ の Preamp 行)。
        val imported = AutoEqParser.parse(
            "Preamp: -99.9 dB\n" +
                "Filter 1: ON PK Fc 1000 Hz Gain -3.0 dB Q 1.41\n",
        )
        assertTrue("取り込みが成功していること (実際: $imported)", imported is AutoEqResult.Ok)
        assertEquals(lo, (imported as AutoEqResult.Ok).settings.preampDb10)

        // producer 4: 好みの EQ の聴感等価。土台に大きな下駄を履かせて下端を割らせる。
        val weights = EqLoudness.defaultWeights()
        val loud = EqLoudness.preampDb10(
            bands = List(4) { EqBand(1_000, 141, 120) },
            weights = weights,
            baseLevelDb = -400.0,
        )
        assertEquals(lo, loud)
        assertEquals(
            hi,
            EqLoudness.preampDb10(bands = emptyList(), weights = weights, baseLevelDb = 400.0),
        )

        // producer 5: 画面の摘み。可動域そのものが PREAMP_RANGE。
        assertEquals(lo, PREAMP_SCALE.fromPosition(-1f))
        assertEquals(hi, PREAMP_SCALE.fromPosition(2f))
    }

    /**
     * 保存の範囲の端が、`.so` が受け付ける閉区間の端ちょうどであること。
     *
     * 相手側 (`caeq::validate` の `kMinPreampDb` / `kMaxPreampDb`) は
     * `app/src/main/cpp/test/ca_eq_preamp_boundary_test.cpp` の節 32 が
     * 「-40.0 は通る / その直下は却下」「+12.0 は通る / その直上は却下」と実測で留めてある。
     * **両側から同じ数を釘で留めるので、片方だけ動かすとどちらかが落ちる。**
     * 数は literal で書く (定数を動かしたら落ちるのが仕事)。
     */
    @Test
    fun theSavedRangeEndsExactlyWhereTheNativeGateOpens() {
        assertEquals(-400, EqSettings.PREAMP_RANGE.first)
        assertEquals(120, EqSettings.PREAMP_RANGE.last)
        assertEquals("-40.0", EqParams.decimal(EqSettings.PREAMP_RANGE.first, EqUnits.GAIN_SCALE))
        assertEquals("12.0", EqParams.decimal(EqSettings.PREAMP_RANGE.last, EqUnits.GAIN_SCALE))
    }
}
