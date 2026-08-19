package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.AutoEqParser
import io.github.mame1839.codecanchor.core.AutoEqResult
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * GraphicEQ 形式の取り込みが目標の曲線をどれだけ再現するかを、実データで固定する。
 *
 * データは AutoEQ (github.com/jaakkopasanen/AutoEq) の results にある
 * DUNU Titan S の GraphicEQ.txt (127 点、測定は Auriculares Argentina)。
 * eq-spec.md §1 の実現誤差の実測と同じ曲線・同じ評価 (20 Hz〜20 kHz を対数 2000 点)。
 *
 * 上限は実測 (llmdocs/tools/eq/18_import_fit.py。各バンド数の既定 Q での最大実現誤差) に
 * 余裕を載せて固定した — 概ね 1 割前後だが、15 バンド全域だけ薄い (7.12 に対し 7.5 で 5%)。
 * 中心サンプル + 中心厳密一致だった旧実装はどのバンド数でも ≤10 kHz の上限に落ちる
 * (実測 5: 9.62 / 10: 3.92 / 15: 1.03 / 31: 0.56 dB)。10 kHz で割るのは、AutoEQ 自身が
 * 10 kHz より上を最適化の目標にしていない (eq-spec.md §5) ため — 曲線の再現度を語れるのは
 * 主に 10 kHz 以下で、それより上はバンドが届かない裾 (5〜15 バンドは最終中心 16 kHz) も含む。
 * load-bearing なのは ≤10 kHz の側 — フィット範囲 (20 Hz〜最終バンド中心) を 20 kHz へ
 * 伸ばすと全域は 3 つとも良くなるが (15: 7.12→3.82 / 10: 3.95→3.33 / 5: 8.84→6.42)、
 * ≤10 kHz が壊れて落ちる (15 バンドで 0.72→2.92 dB。EqSolver.fitCurve の範囲の判断と同じ実測)。
 */
class AutoEqImportFitTest {

    private val text: String =
        checkNotNull(javaClass.getResourceAsStream("/autoeq/DUNU Titan S GraphicEQ.txt")) {
            "テストリソースが無い"
        }.bufferedReader().use { it.readText() }

    private val points: List<Pair<Double, Double>> =
        text.substringAfter(':').split(';').map { entry ->
            val (hz, db) = entry.trim().split(Regex("\\s+"))
            hz.toDouble() to db.toDouble()
        }.sortedBy { it.first }

    /** 20 Hz〜20 kHz を対数で 2000 点。eq-spec §1 の実測 (12_fit_redo.py:19) と同じ。 */
    private val evalHz = DoubleArray(2000) { i ->
        exp(ln(20.0) + (ln(20_000.0) - ln(20.0)) * i / 1999.0)
    }

    private fun realizedErrorDb(bandCount: Int): Pair<Double, Double> {
        val r = AutoEqParser.parse(text, bandCount) as AutoEqResult.Ok
        assertEquals(EqMode.GRAPHIC, r.settings.mode)
        assertEquals(bandCount, r.settings.bands.size)
        val preamp = r.settings.preampDb10.toDouble() / EqUnits.GAIN_SCALE
        var maxFull = 0.0
        var max10k = 0.0
        for (hz in evalHz) {
            val realized = EqSolver.combinedResponseDb(r.settings.bands, hz) + preamp
            val err = abs(realized - AutoEqParser.interpolate(points, hz))
            if (err > maxFull) maxFull = err
            if (hz <= 10_000.0 && err > max10k) max10k = err
        }
        return maxFull to max10k
    }

    @Test
    fun thirtyOneBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(31)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 1.8) // 実測 1.54 (旧 2.21)
        assertTrue("<=10 kHz: $low dB", low <= 0.5) // 実測 0.43 (旧 0.56)
    }

    @Test
    fun fifteenBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(15)
        // 全域の上限はバンドの届かない 16k-20k (曲線自身の起伏 ≈7 dB) を含む退行の網。
        assertTrue("20 Hz-20 kHz: $full dB", full <= 7.5) // 実測 7.12 (旧 6.83)
        assertTrue("<=10 kHz: $low dB", low <= 0.85) // 実測 0.72 (旧 1.03)
    }

    @Test
    fun tenBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(10)
        assertTrue("20 Hz-20 kHz: $full dB", full <= 4.3) // 実測 3.95 (旧 4.74)
        assertTrue("<=10 kHz: $low dB", low <= 3.1) // 実測 2.70 (旧 3.92)
    }

    @Test
    fun fiveBandsRealizeTheCurve() {
        val (full, low) = realizedErrorDb(5)
        // この 8.84 は「DUNU を 5 バンド (既定 Q=0.40) で取り込んだときの最大実現誤差」。
        // EqSolverTest の 8.85 (10 バンド全部 +6 dB・Q=1.41 の素朴カスケードのピーク) とは別の量。
        assertTrue("20 Hz-20 kHz: $full dB", full <= 9.4) // 実測 8.84 (旧 9.62)
        assertTrue("<=10 kHz: $low dB", low <= 5.6) // 実測 5.24 (旧 9.62)
    }

    // 実プリセットは既定 Q のままフィットできる。エスカレートすると既定 Q の意味が変わる
    // (eq-spec §7 の Q 選定の規則と同じ線)。
    @Test
    fun aRealPresetDoesNotEscalateTheQ() {
        val r = AutoEqParser.parse(text, 31) as AutoEqResult.Ok
        val defaultQ100 = (EqSolver.defaultQ(31) * EqUnits.Q_SCALE).toInt()
        assertTrue(r.settings.bands.all { it.q100 == defaultQ100 })
    }

    // GraphicEQ.txt はプリアンプが曲線に焼き込んである (ピークが -0.2 dB になるよう全体が
    // 下げてある)。取り込みが移してよいのは曲線の広帯域オフセットだけで、ヘッドルームを
    // 重ねると二重に掛かる。この曲線の平均は -6.9 dB なので、そこから大きく離れないこと。
    @Test
    fun preampCarriesTheCurveOffsetOnly() {
        val r = AutoEqParser.parse(text, 31) as AutoEqResult.Ok
        assertTrue("preampDb10=${r.settings.preampDb10}", r.settings.preampDb10 in -90..-50)
    }
}
