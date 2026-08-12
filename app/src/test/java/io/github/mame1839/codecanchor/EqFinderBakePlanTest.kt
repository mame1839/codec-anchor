package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.eqFinderBakePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 結果画面の焼き込み計画 (バンド数の選択肢) の見張り。
 *
 * 中心は 2 つ。**摘みの折れ線がどのバンド数へも劣化なしに渡る** (真の応答を新中心で
 * サンプルすると、reband が直した「残差の焼き込み」がここに再発する) と、
 * **適用した設定の応答が試聴した応答からどれだけ離れるかに、実測に基づく上限がある**。
 * どちらも壊れても画面はもっともらしく動き続ける。
 *
 * ⚠️ **応答の評価は [EqReferenceResponse] (製品のコードを呼ばない独立経路) で行う。**
 * 製品の `eqFinderResponseDb` で測ると、実装が計算した値を実装と同じ関数で測り直すことに
 * なり、`x <= x` のトートロジーになる (2026-08-13 の検分で指摘。TERM.md の失敗 3)。
 *
 * ⚠️ **土台のバンドは `EqSolver.solveBands` を通さず直接組む。**入力まで実装が作ると、
 * 求解が壊れたときにテストの期待値も一緒に動いて誤差が相殺する。ここでは「グラフィックの
 * グリッドに乗った非平坦なバンド列」でありさえすればよい。
 */
class EqFinderBakePlanTest {

    private val axes = EqFinderAxes.default(false)

    /** 10 バンドのグリッドに乗った非平坦なバンド列 (ゲインは素の値。摘みの値ではない)。 */
    private val tenBandFreqs = listOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    private val bumpyGains = listOf(30, -20, 40, 0, -50, 20, 0, 10, -10, 20)

    private fun graphicBase(enabled: Boolean): EqSettings = EqSettings(
        enabled = enabled,
        mode = EqMode.GRAPHIC,
        bandCount = 10,
        bands = tenBandFreqs.mapIndexed { i, hz -> EqBand(hz, 50, bumpyGains[i]) },
    )

    /**
     * オーバーレイのバンドを軸の定義から直接組む (製品の `candidateBands` を通さない)。
     * 軸の定義が動いたら [axisDefinitionsAreWhatTheReferenceAssumes] が落ちる。
     */
    private fun refOverlayBands(overlay: List<Int>) = listOf(
        EqBand(105, 71, overlay[0], EqBandType.LOW_SHELF),
        EqBand(2_500, 71, overlay[1], EqBandType.HIGH_SHELF),
    )

    /** 試聴した応答。土台の規則はセッションと同じ (enabled なら bands、切ってあれば素の音)。 */
    private fun refHeardDb(base: EqSettings, overlay: List<Int>): DoubleArray =
        EqReferenceResponse.curveDb(
            (if (base.enabled) base.bands else emptyList()) + refOverlayBands(overlay),
        )

    /** 参照が前提にしている軸の形。ここが動いたら上限の実測値ごと引き直すこと。 */
    @Test
    fun axisDefinitionsAreWhatTheReferenceAssumes() {
        assertEquals(2, axes.size)
        assertEquals(105, axes[0].freqHz)
        assertEquals(71, axes[0].q100)
        assertEquals(EqBandType.LOW_SHELF, axes[0].type)
        assertEquals(2_500, axes[1].freqHz)
        assertEquals(71, axes[1].q100)
        assertEquals(EqBandType.HIGH_SHELF, axes[1].type)
    }

    /**
     * 全摘み +12・オーバーレイ 0 は、どのバンド数へ焼いても全摘み +12 のまま。
     *
     * 旧バンドの真の応答を新しい中心でサンプルする実装だと、中心間の起伏 (解の残差) が
     * 目標に紛れ込み、+12 が 12.4 のような摘みになる (reband が折れ線方式で直した壊れ方)。
     */
    @Test
    fun flatKnobsSurviveBakingAtEveryBandCount() {
        val base = EqSettings(
            enabled = true,
            mode = EqMode.GRAPHIC,
            bandCount = 10,
            bands = EqSolver.solveBands(
                DoubleArray(10) { 12.0 },
                EqSolver.centerFrequencies(10),
                EqSolver.defaultQ(10),
            ),
        )
        val plan = eqFinderBakePlan(base, axes, listOf(0, 0))
        for (count in EqSettings.BAND_COUNTS) {
            val baked = plan[count]
            assertNotNull("$count バンドが焼けていない", baked)
            val knobs = EqSolver.graphicTargetsDb10(baked!!.settings.bands)
            assertTrue(
                "$count バンドで摘みが +12 から崩れた: ${knobs.toList()}",
                knobs.all { it == 120 },
            )
        }
    }

    /**
     * **非平坦なカーブでも摘みの折れ線が運ばれる** ([flatKnobsSurviveBakingAtEveryBandCount] の
     * 平坦カーブでは、折れ線と真の応答サンプルの区別が付きにくい区間が残る)。
     *
     * オーバーレイ 0 なら、焼き込み後のバンド中心での応答は「元の摘みの対数線形補間」に
     * 一致するのが reband の規約。**参照側の補間も応答評価も製品を通さない。**
     *
     * 上限 0.15 dB は 0.1 dB 量子化 + 詰めの残差から。実測 (2026-08-13、下の bumpy カーブ) は
     * 5→0.079 / 10→0.066 / 15→0.079 / 31→0.095 dB。reband を外して真の応答を新中心で
     * サンプルする実装にすると 15→0.506 / 31→0.599 dB へ跳ね、この上限で落ちる (確認済み)。
     */
    @Test
    fun theKnobPolylineIsCarriedToEveryBandCount() {
        val base = graphicBase(enabled = true)
        val knobPoints = tenBandFreqs.map { hz ->
            hz.toDouble() to EqReferenceResponse.combinedDb(base.bands, hz.toDouble())
        }
        val plan = eqFinderBakePlan(base, axes, listOf(0, 0))
        for (count in EqSettings.BAND_COUNTS) {
            val bands = plan[count]!!.settings.bands
            for (band in bands) {
                val hz = band.freqHz.toDouble()
                assertEquals(
                    "$count バンド $hz Hz: 摘みが折れ線から外れた (残差の焼き込み)",
                    EqReferenceResponse.interpolateLog(knobPoints, hz),
                    EqReferenceResponse.combinedDb(bands, hz),
                    0.15,
                )
            }
        }
    }

    /** baked.bands はちょうど選んだ数のグリッドに乗り、bandCount も一緒に更新される。 */
    @Test
    fun bakedBandsSitOnTheChosenGridWithMatchingCount() {
        val plan = eqFinderBakePlan(graphicBase(enabled = true), axes, listOf(35, -20))
        assertEquals(EqSettings.BAND_COUNTS.toSet(), plan.keys)
        for (count in EqSettings.BAND_COUNTS) {
            val settings = plan[count]!!.settings
            assertEquals("bandCount が並びと食い違う", count, settings.bandCount)
            assertEquals(EqSolver.centerFrequencies(count), settings.bands.map { it.freqHz })
        }
    }

    /**
     * 適用 = 試聴。**参照経路で測った真の乖離**に上限を置く。3 段で見る:
     *
     * - 副題に出す忠実度が、参照で測った乖離と一致する (数字が嘘でない)
     * - その乖離が、バンド数ごとの絶対上限に収まる
     * - 同じバンド数への焼き込みは、そのバンド中心で試聴した応答に厳密に合う
     *
     * 上限は実測 (2026-08-13、下の bumpy カーブ・オーバーレイ +3.5/−2.0 dB、enabled 両方)
     * から 2〜3 割の余裕で置いた: 5→3.21 / 10→1.00 / 15→1.23 / 31→0.65 dB。
     * バンド数が少ないほど大きいのは中心間を埋めきれないため (5 バンド = 2 oct 間隔)。
     *
     * ⚠️ **この上限は reband → bake の順を壊しても落ちない。**その形にすると乖離はむしろ
     * 小さくなる (31 バンドで 0.65 → 0.18 dB) — 折れ線を運ぶ目的は応答の忠実度ではなく
     * **摘みの値の保存**だから。順序の見張りは [theKnobPolylineIsCarriedToEveryBandCount] と
     * [flatKnobsSurviveBakingAtEveryBandCount] が持つ。ここを「順序も見ている」と読まないこと。
     */
    @Test
    fun theAppliedResponseStaysWithinTheMeasuredErrorOfTheAudition() {
        val limitDb = mapOf(5 to 4.0, 10 to 1.2, 15 to 1.5, 31 to 0.8)
        val overlay = listOf(35, -20)
        for (enabled in listOf(true, false)) {
            val base = graphicBase(enabled)
            val heard = refHeardDb(base, overlay)
            val plan = eqFinderBakePlan(base, axes, overlay)
            for (count in EqSettings.BAND_COUNTS) {
                val baked = plan[count]!!
                val got = EqReferenceResponse.curveDb(baked.settings.bands)
                val worst = heard.indices.maxOf { abs(heard[it] - got[it]) }
                // 副題の数字が独立の物差しと一致する (製品の応答評価そのものの検算にもなる)。
                assertEquals(
                    "enabled=$enabled count=$count: 副題の忠実度が実際の乖離と違う",
                    worst,
                    baked.maxErrorDb,
                    1e-9,
                )
                assertTrue(
                    "enabled=$enabled count=$count: 乖離 $worst dB が上限 ${limitDb[count]} dB を超えた",
                    worst <= limitDb.getValue(count),
                )
            }
            // 同じバンド数なら、バンド中心では試聴した応答に厳密に合う。
            val same = plan[base.bandCount]!!.settings
            for (hz in tenBandFreqs) {
                assertEquals(
                    "enabled=$enabled $hz Hz",
                    EqReferenceResponse.combinedDb(
                        (if (enabled) base.bands else emptyList()) + refOverlayBands(overlay),
                        hz.toDouble(),
                    ),
                    EqReferenceResponse.combinedDb(same.bands, hz.toDouble()),
                    0.2,
                )
            }
        }
    }

    /**
     * パラメトリックは 1 通りだけで、応答は試聴と**厳密に**一致する (ゲイン 0 のバンドは
     * 応答が定義から 0 dB なので、fc/Q を残しても音は変わらない)。忠実度も厳密に 0。
     */
    @Test
    fun parametricPlansMatchTheAuditionExactly() {
        val bands = listOf(
            EqBand(freqHz = 200, q100 = 141, gainDb10 = 25),
            EqBand(freqHz = 4_000, q100 = 200, gainDb10 = -30),
        )
        val overlay = listOf(40, -20)
        for (enabled in listOf(true, false)) {
            val base = EqSettings(enabled = enabled, mode = EqMode.PARAMETRIC, bands = bands)
            val plan = eqFinderBakePlan(base, axes, overlay)
            assertEquals(setOf(base.bandCount), plan.keys)
            val baked = plan[base.bandCount]!!
            assertEquals(0.0, baked.maxErrorDb, 0.0)
            val heard = EqReferenceResponse.curveDb(
                (if (enabled) bands else emptyList()) + refOverlayBands(overlay),
            )
            val got = EqReferenceResponse.curveDb(baked.settings.bands)
            heard.indices.forEach { assertEquals(heard[it], got[it], 1e-12) }
        }
    }

    /** 満杯のパラメトリックは焼けない (値が null)。選択肢の構造はそれでも壊れない。 */
    @Test
    fun anOverfullParametricPlanCarriesNullInsteadOfLying() {
        val base = EqSettings(
            enabled = false,
            mode = EqMode.PARAMETRIC,
            bands = List(31) { EqBand(freqHz = 50 + it * 100, q100 = 141, gainDb10 = 10) },
        )
        val plan = eqFinderBakePlan(base, axes, listOf(40, 0))
        assertEquals(setOf(base.bandCount), plan.keys)
        assertEquals(null, plan[base.bandCount])
    }
}
