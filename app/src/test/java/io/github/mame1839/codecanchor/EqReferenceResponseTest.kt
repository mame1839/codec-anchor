package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import org.junit.Assert.assertEquals
import org.junit.Test

class EqReferenceResponseTest {

    private val fs = 48_000

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

    @Test
    fun peakingVanishesAtDcAndNyquist() {
        val band = EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 120)
        assertEquals(0.0, EqReferenceResponse.bandDb(band, 0.0, fs), 1e-9)
        assertEquals(0.0, EqReferenceResponse.bandDb(band, fs / 2.0, fs), 1e-9)
    }

    @Test
    fun shelvesReachTheirFullGainInTheirPassband() {
        val low = EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF)
        assertEquals(4.0, EqReferenceResponse.bandDb(low, 0.0, fs), 1e-9)
        assertEquals(0.0, EqReferenceResponse.bandDb(low, fs / 2.0, fs), 1e-9)

        val high = EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -20, type = EqBandType.HIGH_SHELF)
        assertEquals(-2.0, EqReferenceResponse.bandDb(high, fs / 2.0, fs), 1e-9)
        assertEquals(0.0, EqReferenceResponse.bandDb(high, 0.0, fs), 1e-9)
    }

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

    @Test
    fun theLogInterpolationHoldsItsAnchors() {
        val points = listOf(100.0 to 2.0, 1_000.0 to 6.0)
        assertEquals(2.0, EqReferenceResponse.interpolateLog(points, 20.0), 0.0)
        assertEquals(6.0, EqReferenceResponse.interpolateLog(points, 20_000.0), 0.0)
        assertEquals(2.0, EqReferenceResponse.interpolateLog(points, 100.0), 0.0)
        assertEquals(4.0, EqReferenceResponse.interpolateLog(points, 316.227766), 1e-6)
    }
}
