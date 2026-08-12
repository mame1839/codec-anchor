package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 参照 ([EqReferenceResponse]) 自体の検算。**製品のコードとは突き合わせない** —
 * 突き合わせたら独立性が消えて、参照を使う意味が無くなる。
 *
 * 代わりに RBJ の定義から**解析的に分かっている値**で確かめる。ここが緑なら、参照は
 * 「フィルタの応答」として正しく、乖離の上限テストの物差しとして使える。
 */
class EqReferenceResponseTest {

    private val fs = 48_000

    /** peaking は自分の中心で厳密にゲインそのもの (RBJ の定義。EqSolver の詰めもこれに依存)。 */
    @Test
    fun peakingIsExactlyItsGainAtTheCentre() {
        for (gainDb10 in listOf(-120, -35, 60, 120)) {
            for (q100 in listOf(50, 141, 400)) {
                val band = EqBand(freqHz = 1_000, q100 = q100, gainDb10 = gainDb10)
                assertEquals(
                    "gain=$gainDb10 q=$q100",
                    gainDb10 / 10.0,
                    EqReferenceResponse.bandDb(band, 1_000.0, fs),
                    1e-9,
                )
            }
        }
    }

    /** peaking は DC と Nyquist で必ず 0 dB (だから広帯域のオフセットはバンドでは作れない)。 */
    @Test
    fun peakingVanishesAtDcAndNyquist() {
        val band = EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 120)
        assertEquals(0.0, EqReferenceResponse.bandDb(band, 0.0, fs), 1e-9)
        assertEquals(0.0, EqReferenceResponse.bandDb(band, fs / 2.0, fs), 1e-9)
    }

    /** シェルフは通過域の端 (低域なら DC、高域なら Nyquist) でゲインそのもの。 */
    @Test
    fun shelvesReachTheirFullGainInTheirPassband() {
        val low = EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF)
        assertEquals(4.0, EqReferenceResponse.bandDb(low, 0.0, fs), 1e-9)
        // 反対側は素通し。
        assertEquals(0.0, EqReferenceResponse.bandDb(low, fs / 2.0, fs), 1e-9)

        val high = EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -20, type = EqBandType.HIGH_SHELF)
        assertEquals(-2.0, EqReferenceResponse.bandDb(high, fs / 2.0, fs), 1e-9)
        assertEquals(0.0, EqReferenceResponse.bandDb(high, 0.0, fs), 1e-9)
    }

    /** ゲイン 0 のバンドは音を変えない (焼き込みで fc/Q だけ残す形が成り立つ根拠)。 */
    @Test
    fun zeroGainBandsAreTransparent() {
        val bands = listOf(
            EqBand(freqHz = 200, q100 = 141, gainDb10 = 0),
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 0, type = EqBandType.LOW_SHELF),
        )
        EqReferenceResponse.GRID_HZ.forEach {
            assertEquals(0.0, EqReferenceResponse.combinedDb(bands, it, fs), 0.0)
        }
    }

    /** 合成は各バンドの dB の和 (カスケードなので定義から)。 */
    @Test
    fun combinedIsTheSumOfTheBands() {
        val a = EqBand(freqHz = 250, q100 = 100, gainDb10 = 45)
        val b = EqBand(freqHz = 4_000, q100 = 200, gainDb10 = -30)
        val at = 1_000.0
        assertEquals(
            EqReferenceResponse.bandDb(a, at, fs) + EqReferenceResponse.bandDb(b, at, fs),
            EqReferenceResponse.combinedDb(listOf(a, b), at, fs),
            1e-12,
        )
    }

    /** 対数補間: 端の外は端の値、格子点では点の値、幾何平均でちょうど中点。 */
    @Test
    fun theLogInterpolationHoldsItsAnchors() {
        val points = listOf(100.0 to 2.0, 1_000.0 to 6.0)
        assertEquals(2.0, EqReferenceResponse.interpolateLog(points, 20.0), 0.0)
        assertEquals(6.0, EqReferenceResponse.interpolateLog(points, 20_000.0), 0.0)
        assertEquals(2.0, EqReferenceResponse.interpolateLog(points, 100.0), 0.0)
        // √(100·1000) = 316.2 Hz が対数の中点。
        assertEquals(4.0, EqReferenceResponse.interpolateLog(points, 316.227766), 1e-6)
    }
}
