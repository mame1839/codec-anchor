package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

class EqSpectrumPsdContractTest {

    private val fs = 48_000

    private fun meanPowerDb(db: DoubleArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (k in from until until) sum += 10.0.pow(db[k] / 10.0)
        return 10.0 * log10(sum / (until - from))
    }

    @Test
    fun whiteNoiseLevelMatchesPerHzDensityNotPerBinPower() {
        val r = Random(7)
        val noise = FloatArray(fs * 10) { (r.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, db) = Spectrum.averageSpectrumDb(noise, fs)
        val mean = meanPowerDb(db, 3, db.size - 1)
        val perHz = 10.0 * log10(2.0 / 3.0 / fs)
        val perBin = 10.0 * log10(2.0 / 3.0 / 4096)
        println("白色雑音: 実測 %.3f dB / per-Hz 予測 %.3f / per-bin 予測 %.3f".format(mean, perHz, perBin))
        assertEquals("per-Hz PSD の絶対値", perHz, mean, 0.3)
        assertTrue("per-bin 仮説 ($perBin) から 5 dB 以上離れていること (実測 $mean)", abs(mean - perBin) > 5.0)
    }

    @Test
    fun densityLevelSurvivesWindowShrink() {
        val rLong = Random(21)
        val longNoise = FloatArray(fs * 10) { (rLong.nextDouble(-1.0, 1.0)).toFloat() }
        val rShort = Random(22)
        val shortNoise = FloatArray(3_000) { (rShort.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, dbLong) = Spectrum.averageSpectrumDb(longNoise, fs)
        val (hzShort, dbShort) = Spectrum.averageSpectrumDb(shortNoise, fs)
        assertEquals(1_025, dbShort.size)
        assertEquals(fs / 2048.0, hzShort[1], 1e-9)
        val meanLong = meanPowerDb(dbLong, 3, dbLong.size - 1)
        val meanShort = meanPowerDb(dbShort, 3, dbShort.size - 1)
        println("窓 4096: %.3f dB / 窓 2048: %.3f dB / 差 %.3f (per-bin なら 3.01)".format(meanLong, meanShort, meanShort - meanLong))
        assertEquals("窓長を変えても密度は同じ", meanLong, meanShort, 0.8)
    }

    @Test
    fun theFsArgumentScalesTheDensityExactly() {
        val r = Random(23)
        val noise = FloatArray(fs * 2) { (r.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, db48) = Spectrum.averageSpectrumDb(noise, 48_000)
        val (_, db96) = Spectrum.averageSpectrumDb(noise, 96_000)
        val expected = 10.0 * log10(48_000.0 / 96_000.0)
        for (k in db48.indices) {
            assertEquals("bin $k", expected, db96[k] - db48[k], 1e-6)
        }
        println("fs 48k→96k で全 bin が %.4f dB 動いた (密度の定義どおり)".format(expected))
    }

    @Test
    fun measuredPinkNoiseYieldsEqualOctaveWeights() {
        val pink = Spectrum.pinkNoise(fs * 10, Random(11))
        val (hz, db) = Spectrum.averageSpectrumDb(pink, fs)
        val w = EqLoudness.weightsFromSpectrumDb(Spectrum.resampleDb(hz, db, EqLoudness.GRID_HZ))
        val xs = ArrayList<Double>()
        val flat = ArrayList<Double>()
        for (i in EqLoudness.GRID_HZ.indices) {
            val f = EqLoudness.GRID_HZ[i]
            if (f in 100.0..8_000.0) {
                xs += log10(f)
                flat += 10.0 * log10(w[i]) - EqLoudness.kWeightingDb(f)
            }
        }
        val mean = flat.average()
        val spread = flat.maxOf { abs(it - mean) }
        val mx = xs.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (flat[i] - mean)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = sxy / sxx
        println("ピンク→重み÷K: 平均からの最大ずれ %.3f dB / 傾き %.3f dB/decade".format(spread, slope))
        assertTrue("平坦性 (実測ずれ $spread dB, 許容 2.5)", spread < 2.5)
        assertTrue("傾き $slope dB/decade (×f を消すと −10、二重なら +10)", abs(slope) < 3.0)
    }

    @Test
    fun aConstantOffsetDoesNotChangeTheWeights() {
        val r = Random(31)
        val spectrum = DoubleArray(EqLoudness.GRID_HZ.size) { -60.0 + r.nextDouble(-20.0, 20.0) }
        val shifted = DoubleArray(spectrum.size) { spectrum[it] + 7.0 }
        val w0 = EqLoudness.weightsFromSpectrumDb(spectrum)
        val w7 = EqLoudness.weightsFromSpectrumDb(shifted)
        for (i in w0.indices) {
            assertEquals("i=$i", w0[i], w7[i], w0[i] * 1e-12)
        }
    }
}
