package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class SpectrumTest {

    private val fs = 48_000

    private fun sine(freqHz: Double, samples: Int, amplitude: Double = 1.0): FloatArray =
        FloatArray(samples) { (amplitude * sin(2.0 * PI * freqHz * it / fs)).toFloat() }

    private fun argMax(values: DoubleArray, from: Int = 0): Int {
        var best = from
        for (i in from until values.size) if (values[i] > values[best]) best = i
        return best
    }

    @Test
    fun exactBinSineReadsItsAnalyticLevel() {
        val k0 = 96
        val f = k0.toDouble() * fs / 4096
        val (binHz, db) = Spectrum.averageSpectrumDb(sine(f, fs * 2), fs)
        assertEquals(2049, db.size)
        assertEquals(f, binHz[k0], 1e-9)
        assertEquals(k0, argMax(db))
        val peak = 10.0 * log10(0.5) - 10.0 * log10(1.5 * fs / 4096.0)
        assertEquals("ピーク bin", peak, db[k0], 0.05)
        assertEquals("隣接 bin (窓の裾)", peak - 6.0206, db[k0 - 1], 0.05)
        assertEquals("隣接 bin (窓の裾)", peak - 6.0206, db[k0 + 1], 0.05)
    }

    @Test
    fun offBinSinePeaksAtNearestBin() {
        val (_, db) = Spectrum.averageSpectrumDb(sine(1_000.0, fs * 2), fs)
        assertEquals(85, argMax(db))
    }

    @Test
    fun whiteNoiseReadsItsPsdFlat() {
        val r = Random(7)
        val noise = FloatArray(fs * 10) { (r.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, db) = Spectrum.averageSpectrumDb(noise, fs)
        val body = db.copyOfRange(3, db.size - 1)
        val mean = body.average()
        assertEquals("PSD の絶対値 (10·log10(2σ²/fs))", 10.0 * log10(2.0 / 3.0 / fs), mean, 0.2)
        val worst = body.maxOf { abs(it - mean) }
        assertTrue("平均からの最大ずれ $worst dB (許容 1.5 dB)", worst < 1.5)
    }

    @Test
    fun pinkNoiseSlopeIsMinusTenDbPerDecade() {
        val noise = Spectrum.pinkNoise(fs * 10, Random(11))
        val (binHz, db) = Spectrum.averageSpectrumDb(noise, fs)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (k in binHz.indices) {
            if (binHz[k] in 100.0..8_000.0) {
                xs += log10(binHz[k])
                ys += db[k]
            }
        }
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = sxy / sxx
        assertTrue("傾き $slope dB/decade (期待 -10 ± 1.5)", slope in -11.5..-8.5)
    }

    @Test
    fun tooShortInputReturnsEmpty() {
        val (hz, db) = Spectrum.averageSpectrumDb(FloatArray(100), fs)
        assertEquals(0, hz.size)
        assertEquals(0, db.size)
    }

    @Test
    fun windowShrinksToPowerOfTwoForShortInput() {
        val (hz, db) = Spectrum.averageSpectrumDb(sine(1_000.0, 3_000), fs)
        assertEquals(1_025, db.size)
        assertEquals(fs / 2048.0, hz[1], 1e-9)
    }

    @Test
    fun resampleInterpolatesInLogFrequency() {
        val srcHz = doubleArrayOf(100.0, 1_000.0, 10_000.0)
        val srcDb = doubleArrayOf(0.0, 10.0, 30.0)
        val out = Spectrum.resampleDb(srcHz, srcDb, doubleArrayOf(316.22776601683796, 3_162.2776601683795))
        assertEquals(5.0, out[0], 1e-9)
        assertEquals(20.0, out[1], 1e-9)
    }

    @Test
    fun resampleClampsOutsideTheSourceRange() {
        val srcHz = doubleArrayOf(0.0, 100.0, 10_000.0)
        val srcDb = doubleArrayOf(99.0, -3.0, 12.0)
        val out = Spectrum.resampleDb(srcHz, srcDb, doubleArrayOf(1.0, 100.0, 10_000.0, 40_000.0))
        assertEquals(-3.0, out[0], 1e-9)
        assertEquals(-3.0, out[1], 1e-9)
        assertEquals(12.0, out[2], 1e-9)
        assertEquals(12.0, out[3], 1e-9)
    }

    @Test
    fun resamplePreservesMonotonicity() {
        val r = Random(3)
        val srcHz = DoubleArray(64) { 20.0 * Math.pow(1_000.0, it / 63.0) }
        val srcDb = DoubleArray(64)
        for (i in 1 until 64) srcDb[i] = srcDb[i - 1] + r.nextDouble(0.0, 2.0)
        val dstHz = DoubleArray(401) { 10.0 * Math.pow(4_000.0, it / 400.0) }
        val out = Spectrum.resampleDb(srcHz, srcDb, dstHz)
        for (i in 1 until out.size) {
            assertTrue("i=$i で単調性が崩れた (${out[i - 1]} → ${out[i]})", out[i] >= out[i - 1] - 1e-12)
        }
    }

    @Test
    fun crossfadeLoopIsSeamlessAndEqualPower() {
        val frames = 1_000
        val fade = 100
        val outFrames = frames - fade
        val clip = FloatArray(frames * 2)
        for (i in 0 until frames) {
            clip[i * 2] = i.toFloat()
            clip[i * 2 + 1] = (10_000 + i).toFloat()
        }
        val out = Spectrum.crossfadeLoop(clip, 2, fade)
        assertEquals(outFrames * 2, out.size)
        assertEquals(outFrames.toFloat(), out[0], 1e-3f)
        assertEquals((10_000 + outFrames).toFloat(), out[1], 1e-3f)
        assertEquals((fade - 1).toFloat(), out[(fade - 1) * 2], 1e-3f)
        for (i in fade until outFrames) {
            assertEquals(clip[i * 2], out[i * 2], 0f)
            assertEquals(clip[i * 2 + 1], out[i * 2 + 1], 0f)
        }
        var previousHead = -1.0
        for (i in 0 until fade) {
            val headL = i.toDouble()
            val tailL = (outFrames + i).toDouble()
            val headR = (10_000 + i).toDouble()
            val tailR = (10_000 + outFrames + i).toDouble()
            val det = headL * tailR - tailL * headR
            val h = (out[i * 2] * tailR - tailL * out[i * 2 + 1]) / det
            val t = (headL * out[i * 2 + 1] - out[i * 2] * headR) / det
            assertEquals("i=$i の等パワー", 1.0, h * h + t * t, 1e-3)
            assertTrue("i=$i で head が単調でない", h > previousHead)
            assertTrue("i=$i の係数が範囲外 (h=$h t=$t)", h in -1e-3..1.0 + 1e-3 && t in -1e-3..1.0 + 1e-3)
            previousHead = h
        }
    }

    @Test
    fun crossfadeLoopClampsDegenerateFades() {
        val clip = FloatArray(10) { it.toFloat() }
        assertEquals(10, Spectrum.crossfadeLoop(clip, 1, 0).size)
        assertEquals(10, Spectrum.crossfadeLoop(clip, 1, 1).size)
        assertEquals(5, Spectrum.crossfadeLoop(clip, 1, 100).size)
    }

    @Test
    fun monoMixAveragesChannels() {
        val stereo = floatArrayOf(1f, 3f, -2f, 2f, 0.5f, 0.5f)
        val mono = Spectrum.monoMix(stereo, 2)
        assertEquals(3, mono.size)
        assertEquals(2f, mono[0], 0f)
        assertEquals(0f, mono[1], 0f)
        assertEquals(0.5f, mono[2], 0f)
    }

    @Test
    fun pinkNoiseStaysWithinFullScale() {
        val noise = Spectrum.pinkNoise(fs, Random(5))
        assertTrue(noise.all { abs(it) <= 1.0f })
    }
}
