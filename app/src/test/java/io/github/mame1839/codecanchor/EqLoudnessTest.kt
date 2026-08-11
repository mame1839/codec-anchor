package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow

/**
 * 目隠し A/B の音量等価 (eq-finder-design.md §1) の固定。
 *
 * 数値の参照値は BS.1770-4 の係数から独立に (Python で) 計算した値で、実装をなぞっていない。
 */
class EqLoudnessTest {

    // セッションの 3 軸 (eq-finder-design.md §2): 低域シェルフ 105 Hz / 高域シェルフ 2.5 kHz
    // (どちらも Q 0.71) / 中域プレゼンス 3 kHz ピーキング (Q 1.0)。
    private fun bass(gainDb10: Int) =
        EqBand(freqHz = 105, q100 = 71, gainDb10 = gainDb10, type = EqBandType.LOW_SHELF)

    private fun treble(gainDb10: Int) =
        EqBand(freqHz = 2_500, q100 = 71, gainDb10 = gainDb10, type = EqBandType.HIGH_SHELF)

    private fun presence(gainDb10: Int) =
        EqBand(freqHz = 3_000, q100 = 100, gainDb10 = gainDb10)

    private fun flat() = listOf(bass(0), treble(0), presence(0))

    /** 候補が実際に提示される聴感レベル = 等価プリアンプ + 聴感ゲイン。 */
    private fun presentedLevelDb(bands: List<EqBand>, weights: DoubleArray, trimDb: Double): Double =
        EqLoudness.preampDb10(bands, weights, trimDb) / 10.0 +
            EqLoudness.perceivedGainDb(bands, weights)

    @Test
    fun gridIsLogSpacedFrom20HzTo20kHz() {
        val grid = EqLoudness.GRID_HZ
        assertEquals(401, grid.size)
        assertEquals(20.0, grid[0], 1e-9)
        assertEquals(20_000.0, grid[400], 1e-6)
        val ratio = grid[1] / grid[0]
        for (i in 1 until grid.size) {
            assertEquals("点 $i の間隔", ratio, grid[i] / grid[i - 1], 1e-9)
        }
    }

    /** 仕様のテスト 6: 1 kHz で 0、低域で明確に負、10 kHz 近辺はシェルフ +4 dB 側で正。 */
    @Test
    fun kWeightingFollowsTheBs1770Shape() {
        assertEquals(0.0, EqLoudness.kWeightingDb(1_000.0), 0.0) // 正規化点は厳密に 0
        assertEquals(-13.97, EqLoudness.kWeightingDb(20.0), 0.05)
        assertEquals(-3.60, EqLoudness.kWeightingDb(60.0), 0.02)
        assertEquals(-1.83, EqLoudness.kWeightingDb(100.0), 0.02)
        assertEquals(2.37, EqLoudness.kWeightingDb(2_000.0), 0.02)
        assertEquals(3.34, EqLoudness.kWeightingDb(10_000.0), 0.02)
        assertTrue("低域は明確に負", EqLoudness.kWeightingDb(60.0) < -2.0)
        assertTrue("10 kHz は正", EqLoudness.kWeightingDb(10_000.0) > 2.0)
    }

    /** 仕様のテスト 1: 全バンド 0 dB → 聴感ゲイン 0、プリアンプはトリム分のみ。 */
    @Test
    fun flatBandsHaveZeroPerceivedGainAndTrimOnlyPreamp() {
        val w = EqLoudness.defaultWeights()
        assertEquals(0.0, EqLoudness.perceivedGainDb(flat(), w), 0.0) // 厳密に 0
        assertEquals(0, EqLoudness.preampDb10(flat(), w, 0.0))
        assertEquals(-25, EqLoudness.preampDb10(flat(), w, 2.5))
    }

    /**
     * 仕様のテスト 2 (eq-finder-design.md §1 の例): 低域シェルフ +6 dB (105 Hz, Q 0.71) の
     * 聴感ゲインは、音楽的な重み (defaultWeights) では +0.67 dB しかない。
     *
     * ピーク基準の autoPreampDb10 はこの候補に −5.8 dB を掛けるので、聴感では 5.1 dB
     * 静かになる (仕様の「体感 5 dB 静かになり」)。ピーク基準 (−58) と聴感等価 (−7) の
     * 51 (db10) の差が、この機能が autoPreampDb10 を使えない理由。
     *
     * 帯域幅補正 (weightsFromSpectrumDb の ×GRID_HZ) を消すと聴感ゲインが +3.54 dB に
     * 化けるので、0.67 の固定がその退行の見張りを兼ねる。
     */
    @Test
    fun bassShelfLoudnessIsFarBelowItsPeak() {
        val bands = listOf(bass(60))
        val w = EqLoudness.defaultWeights()
        val perceived = EqLoudness.perceivedGainDb(bands, w)
        assertEquals(0.670, perceived, 0.02)
        assertTrue("+6 dB シェルフの聴感は +6 よりはるかに小さい", perceived < 1.5)

        assertEquals(5.899, EqLoudness.peakGainDb(bands), 0.02)
        assertEquals(-58, EqSolver.autoPreampDb10(bands))
        assertEquals(-7, EqLoudness.preampDb10(bands, w, 0.0))

        // ピーク基準で揃えたときの聴感レベル。フラット (0 dB) と 5 dB 以上開く
        val peakBasedLevel = EqSolver.autoPreampDb10(bands) / 10.0 + perceived
        assertEquals(-5.13, peakBasedLevel, 0.05)
    }

    /**
     * 仕様のテスト 3: 任意の 2 候補で (preamp/10 + L) の差が量子化誤差 (±0.1 dB) 以内。
     *
     * round half up の誤差は片側 0.05 dB なので、各候補が −trim から 0.05 以内という
     * さらに強い形でも見る (緩めると丸め方式の退行に気づけない)。
     */
    @Test
    fun anyTwoCandidatesPresentAtTheSameLevel() {
        val candidates = listOf(
            flat(),
            listOf(bass(40)),
            listOf(bass(-40)),
            listOf(treble(40)),
            listOf(treble(-40)),
            listOf(bass(20), treble(-40)),
            listOf(bass(40), treble(40), presence(20)),
            listOf(presence(-60)),
            listOf(bass(-120), treble(-120)),
            listOf(bass(120), treble(-120)),
        )
        val w = EqLoudness.defaultWeights()
        val trim = EqLoudness.sessionTrimDb(candidates, w)
        val levels = candidates.map { presentedLevelDb(it, w, trim) }
        levels.forEachIndexed { i, level ->
            assertEquals("候補 $i の提示レベル", -trim, level, 0.05 + 1e-9)
        }
        val spread = levels.maxOrNull()!! - levels.minOrNull()!!
        assertTrue("候補間の開き $spread", spread <= 0.1 + 1e-9)
    }

    /**
     * 仕様のテスト 5: 「なし」= 全バンド 0 + 等価プリアンプが、他候補と同じ聴感レベルになる。
     * --off で表すとプリアンプごと消えて音量が揃わない (仕様 §1) ので、この形が正。
     */
    @Test
    fun noneIsFlatBandsPlusEquivalentPreamp() {
        val none = flat()
        val candidates = listOf(none, listOf(bass(40)), listOf(treble(-40)))
        val w = EqLoudness.defaultWeights()
        val trim = EqLoudness.sessionTrimDb(candidates, w)
        // L = 0 なので「なし」のプリアンプはトリム分そのもの
        assertEquals(Math.round(-trim * 10).toInt(), EqLoudness.preampDb10(none, w, trim))
        val noneLevel = presentedLevelDb(none, w, trim)
        candidates.forEachIndexed { i, c ->
            assertEquals("なしと候補 $i のレベル差", noneLevel, presentedLevelDb(c, w, trim), 0.1 + 1e-9)
        }
    }

    /**
     * 仕様のテスト 4: セッションの端の候補群 (3 軸の全組合せ 27 通り) で、トリムを通した
     * preamp + peak が全候補で 0 以下 (クリップしない)。
     *
     * トリムの参照値 12.026 は独立計算した max(peak − L) = 11.976 (低域 +12 / 高域 −12 /
     * 中域 −6 の組) + 丸めガード 0.05。**ガードを消すと 11.976 になりこの固定が落ちる。**
     * ガード無しでも下の ≤ 0 自体は丸めの向き次第で通ってしまう (このセットでは境界候補の
     * 丸めがたまたま下向き) ので、ガードの見張りはトリム値の固定のほう。
     */
    @Test
    fun sessionTrimKeepsEveryCornerCandidateClipFree() {
        val shelfGains = listOf(-120, 0, 120)
        val presenceGains = listOf(-60, 0, 60)
        val candidates = shelfGains.flatMap { b ->
            shelfGains.flatMap { t ->
                presenceGains.map { p -> listOf(bass(b), treble(t), presence(p)) }
            }
        }
        assertEquals(27, candidates.size)
        val w = EqLoudness.defaultWeights()
        assertEquals(0.0, EqLoudness.sessionTrimDb(emptyList(), w), 0.0)
        val trim = EqLoudness.sessionTrimDb(candidates, w)
        assertEquals(12.026, trim, 0.02)
        candidates.forEachIndexed { i, c ->
            val preamp = EqLoudness.preampDb10(c, w, trim)
            val total = preamp / 10.0 + EqLoudness.peakGainDb(c)
            assertTrue("候補 $i: preamp + peak = $total", total <= 1e-9)
            // クランプが働くと等価が黙って崩れる。端の組でも働かないことを併せて見る
            assertTrue(
                "候補 $i: クランプ非作動 (preamp = $preamp)",
                preamp > EqSettings.PREAMP_RANGE.first && preamp < EqSettings.PREAMP_RANGE.last,
            )
        }
    }

    /** 床 (最大値 −100 dB) と非有限の扱い。壊れた実測でもゼロ和・NaN を作らない。 */
    @Test
    fun weightsSurviveBrokenSpectra() {
        // 全点非有限 → 題材なしと同じ (defaultWeights)
        val allNan = DoubleArray(EqLoudness.GRID_HZ.size) { Double.NaN }
        assertArrayEquals(EqLoudness.defaultWeights(), EqLoudness.weightsFromSpectrumDb(allNan), 1e-12)

        val spectrum = DoubleArray(EqLoudness.GRID_HZ.size) // 全点 0 dB
        spectrum[100] = Double.NaN
        spectrum[200] = -500.0
        spectrum[300] = Double.NEGATIVE_INFINITY
        val w = EqLoudness.weightsFromSpectrumDb(spectrum)
        assertTrue("全重みが正の有限値", w.all { it.isFinite() && it > 0.0 })
        for (i in intArrayOf(100, 200, 300)) {
            val floored = 10.0.pow((-100.0 + EqLoudness.kWeightingDb(EqLoudness.GRID_HZ[i])) / 10.0) *
                EqLoudness.GRID_HZ[i]
            assertEquals("点 $i は床に落ちる", floored, w[i], floored * 1e-9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EqLoudness.weightsFromSpectrumDb(DoubleArray(400))
        }
    }

    /**
     * スペクトルの規約の固定: spectrumDb は PSD (per Hz) の dB で、基準の定数は結果に
     * 効かない。ピンクノイズの PSD を通すと defaultWeights と一致し、その形は
     * 10^(K/10) × 定数 (帯域幅補正 ×f と密度 1/f の相殺)。題材を実測する側 (Spectrum.kt)
     * はこの規約 (PSD の dB) に合わせること。×f を消すと 20 Hz と 20 kHz で重みが
     * 1000 倍ずれてここで落ちる。
     */
    @Test
    fun pinkPsdReproducesTheDefaultWeights() {
        val grid = EqLoudness.GRID_HZ
        val pinkShifted = DoubleArray(grid.size) { -10.0 * log10(grid[it]) + 37.0 }
        val w = EqLoudness.weightsFromSpectrumDb(pinkShifted)
        val d = EqLoudness.defaultWeights()
        for (i in grid.indices) {
            assertEquals("点 $i", d[i], w[i], d[i] * 1e-9)
        }
        val scale = d[0] / 10.0.pow(EqLoudness.kWeightingDb(grid[0]) / 10.0)
        for (i in grid.indices) {
            val k = 10.0.pow(EqLoudness.kWeightingDb(grid[i]) / 10.0)
            assertEquals("点 $i の形", scale, d[i] / k, scale * 1e-9)
        }
    }

    /** peakGainDb はクランプしない生値。全カットの候補では負になる (0 に頭打ちしない)。 */
    @Test
    fun peakGainDbIsRawAndCanBeNegative() {
        val allCut = listOf(bass(-120), treble(-120))
        val peak = EqLoudness.peakGainDb(allCut)
        assertEquals(-0.575, peak, 0.02)
        assertTrue("全カットのピークは負", peak < 0.0)
        assertEquals(0.0, EqLoudness.peakGainDb(emptyList()), 0.0)
    }

    /** 等価プリアンプは PREAMP_RANGE (−40.0〜+12.0 dB) にクランプされる。 */
    @Test
    fun preampClampsToThePreampRange() {
        val w = EqLoudness.defaultWeights()
        assertEquals(EqSettings.PREAMP_RANGE.first, EqLoudness.preampDb10(flat(), w, 100.0))
        // 全部 −40 dB の候補は L ≈ −21.9 dB → 生の値 +219 → 上限 +120 で止まる
        val deepCut = listOf(bass(-400), treble(-400), presence(-400))
        assertEquals(EqSettings.PREAMP_RANGE.last, EqLoudness.preampDb10(deepCut, w, 0.0))
    }
}
