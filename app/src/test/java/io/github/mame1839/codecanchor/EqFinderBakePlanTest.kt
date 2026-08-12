package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqFinderMaterialize
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.eqFinderBakePlan
import io.github.mame1839.codecanchor.ui.eqFinderResponseDb
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
 * **適用した設定の応答が試聴した応答と一致する** (副題の忠実度がその乖離を正しく上から
 * 押さえる)。どちらも壊れても画面はもっともらしく動き続ける。
 */
class EqFinderBakePlanTest {

    private val axes = EqFinderAxes.default(false)

    private fun graphicBase(targetsDb: DoubleArray, enabled: Boolean, bandCount: Int = 10): EqSettings {
        val freqs = EqSolver.centerFrequencies(bandCount)
        require(targetsDb.size == freqs.size)
        return EqSettings(
            enabled = enabled,
            mode = EqMode.GRAPHIC,
            bandCount = bandCount,
            bands = EqSolver.solveBands(targetsDb, freqs, EqSolver.defaultQ(bandCount)),
        )
    }

    /** 試聴した応答。土台の規則はセッションと同じ (enabled なら bands、切ってあれば素の音)。 */
    private fun heardDb(base: EqSettings, overlay: List<Int>): DoubleArray =
        eqFinderResponseDb(
            EqFinderMaterialize.candidateBands(
                if (base.enabled) base.bands else emptyList(),
                axes,
                overlay,
            ),
        )

    /**
     * 全摘み +12・オーバーレイ 0 は、どのバンド数へ焼いても全摘み +12 のまま。
     *
     * 旧バンドの真の応答を新しい中心でサンプルする実装だと、中心間の起伏 (解の残差) が
     * 目標に紛れ込み、+12 が 12.4 のような摘みになる (reband が折れ線方式で直した壊れ方)。
     */
    @Test
    fun flatKnobsSurviveBakingAtEveryBandCount() {
        val base = graphicBase(DoubleArray(10) { 12.0 }, enabled = true)
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

    /** baked.bands はちょうど選んだ数のグリッドに乗り、bandCount も一緒に更新される。 */
    @Test
    fun bakedBandsSitOnTheChosenGridWithMatchingCount() {
        val base = graphicBase(doubleArrayOf(3.0, -2.0, 4.0, 0.0, -5.0, 2.0, 0.0, 1.0, -1.0, 2.0), enabled = true)
        val plan = eqFinderBakePlan(base, axes, listOf(35, -20))
        assertEquals(EqSettings.BAND_COUNTS.toSet(), plan.keys)
        for (count in EqSettings.BAND_COUNTS) {
            val settings = plan[count]!!.settings
            assertEquals("bandCount が並びと食い違う", count, settings.bandCount)
            assertEquals(EqSolver.centerFrequencies(count), settings.bands.map { it.freqHz })
        }
    }

    /**
     * 適用 = 試聴 (main の要請の直接比較)。2 段で見る:
     * - 同じバンド数への焼き込みは、そのバンド中心で試聴した応答に厳密に合う (量子化幅の内)
     * - どのバンド数でも、全帯域 (120 点) の乖離は副題に出す忠実度そのもの以下
     */
    @Test
    fun theAppliedResponseStaysWithinTheAdvertisedErrorOfTheAudition() {
        val curve = doubleArrayOf(3.0, -2.0, 4.0, 0.0, -5.0, 2.0, 0.0, 1.0, -1.0, 2.0)
        val overlay = listOf(35, -20)
        for (enabled in listOf(true, false)) {
            val base = graphicBase(curve, enabled)
            val heard = heardDb(base, overlay)
            val plan = eqFinderBakePlan(base, axes, overlay)
            for (count in EqSettings.BAND_COUNTS) {
                val baked = plan[count]!!
                val got = eqFinderResponseDb(baked.settings.bands)
                val worst = heard.indices.maxOf { abs(heard[it] - got[it]) }
                assertTrue(
                    "enabled=$enabled count=$count: 乖離 $worst が副題の ${baked.maxErrorDb} を超えた",
                    worst <= baked.maxErrorDb + 1e-9,
                )
                // 数字が空回りしていないこと (0 に潰れた比較は何も見ていない)。
                assertTrue("enabled=$enabled count=$count: 忠実度が負", baked.maxErrorDb >= 0.0)
            }
            // 同じバンド数なら、バンド中心では試聴した応答に厳密に合う。
            val same = plan[base.bandCount]!!.settings
            for (hz in EqSolver.centerFrequencies(base.bandCount)) {
                val i = hz.toDouble()
                assertEquals(
                    "enabled=$enabled $hz Hz",
                    (if (base.enabled) EqSolver.combinedResponseDb(base.bands, i) else 0.0) +
                        EqSolver.combinedResponseDb(
                            EqFinderMaterialize.candidateBands(emptyList(), axes, overlay),
                            i,
                        ),
                    EqSolver.combinedResponseDb(same.bands, i),
                    0.2,
                )
            }
        }
    }

    /**
     * パラメトリックは 1 通りだけで、応答は試聴と**ビット単位で**一致する (ゲイン 0 の
     * バンドは応答式が厳密に 0.0 を返すため)。忠実度も厳密に 0。
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
            val heard = heardDb(base, overlay)
            val got = eqFinderResponseDb(baked.settings.bands)
            heard.indices.forEach { assertEquals(heard[it], got[it], 0.0) }
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
