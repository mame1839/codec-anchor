package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.Spectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class SpectrumTest {

    private val fs = 48_000

    private val probeHz = doubleArrayOf(125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0)

    private fun powerDbAt(pcm: FloatArray, freqHz: Double): Double {
        val n = 4_096
        val hann = DoubleArray(n) { 0.5 * (1.0 - cos(2.0 * PI * it / n)) }
        val omega = 2.0 * PI * freqHz / fs
        val kernelRe = DoubleArray(n) { cos(omega * it) * hann[it] }
        val kernelIm = DoubleArray(n) { -sin(omega * it) * hann[it] }
        var acc = 0.0
        var segments = 0
        var start = 0
        while (start + n <= pcm.size) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val v = pcm[start + i].toDouble()
                re += v * kernelRe[i]
                im += v * kernelIm[i]
            }
            acc += re * re + im * im
            segments++
            start += n / 2
        }
        return 10.0 * log10(acc / segments)
    }

    private fun slopeDbPerDecade(pcm: FloatArray): Double {
        val xs = DoubleArray(probeHz.size) { log10(probeHz[it]) }
        val ys = DoubleArray(probeHz.size) { powerDbAt(pcm, probeHz[it]) }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        return sxy / sxx
    }

    @Test
    fun theProbeReadsAFlatSlopeForWhiteNoise() {
        val r = Random(7)
        val white = FloatArray(fs * 10) { r.nextDouble(-1.0, 1.0).toFloat() }
        val slope = slopeDbPerDecade(white)
        assertTrue("白色雑音の傾き $slope dB/decade (期待 0 ± 0.5)", abs(slope) < 0.5)
    }

    @Test
    fun pinkNoiseSlopeIsMinusTenDbPerDecade() {
        val slope = slopeDbPerDecade(Spectrum.pinkNoise(fs * 10, Random(11)))
        assertTrue("傾き $slope dB/decade (期待 -10 ± 1.5)", slope in -11.5..-8.5)
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
    fun pinkNoiseStaysWithinFullScale() {
        val noise = Spectrum.pinkNoise(fs, Random(5))
        assertTrue(noise.all { abs(it) <= 1.0f })
    }
}