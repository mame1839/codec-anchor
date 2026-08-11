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

    /**
     * bin ちょうどの正弦は PSD 正規化で解析的に読みが決まる:
     * 線パワー A²/2 が周期 Hann の等価雑音帯域幅 (1.5 bin = 1.5·fs/N Hz) に広がるので、
     * ピーク bin = 10·log10(A²/2) − 10·log10(1.5·fs/N)。隣接 bin はその −6.02 dB
     * (周期 Hann の W(±1) = −N/4)。
     *
     * **隣接 bin は「窓が掛かっている」ことの見張り。**矩形窓 (窓を掛け忘れた形) だと
     * bin ちょうどの正弦は隣に一切漏れず -200 dB になるので、ここで確実に落ちる。
     */
    @Test
    fun exactBinSineReadsItsAnalyticLevel() {
        val k0 = 96
        val f = k0.toDouble() * fs / 4096 // 1125 Hz
        val (binHz, db) = Spectrum.averageSpectrumDb(sine(f, fs * 2), fs)
        assertEquals(2049, db.size)
        assertEquals(f, binHz[k0], 1e-9)
        assertEquals(k0, argMax(db))
        val peak = 10.0 * log10(0.5) - 10.0 * log10(1.5 * fs / 4096.0)
        assertEquals("ピーク bin", peak, db[k0], 0.05)
        assertEquals("隣接 bin (窓の裾)", peak - 6.0206, db[k0 - 1], 0.05)
        assertEquals("隣接 bin (窓の裾)", peak - 6.0206, db[k0 + 1], 0.05)
    }

    // 1 kHz は bin 境界に乗らない (1000·4096/48000 = 85.33)。それでも最寄りの bin が最大になる。
    @Test
    fun offBinSinePeaksAtNearestBin() {
        val (_, db) = Spectrum.averageSpectrumDb(sine(1_000.0, fs * 2), fs)
        assertEquals(85, argMax(db))
    }

    /**
     * 白色雑音は平坦に、しかも PSD の絶対値どおりに読めること。
     *
     * - 平坦性: Welch 平均が壊れている (1 区間しか見ていない等) と、単区間のペリオドグラムは
     *   bin ごとに ±数 dB 暴れるのでこの許容には入らない
     * - 絶対値: 一様乱数 ±1 の分散は 1/3、片側 PSD は 2σ²/fs。**PSD 正規化 (fs と Σw² の除算)
     *   を欠くと数十 dB 単位でずれる** — EqLoudness との契約 (per Hz) の見張り
     */
    @Test
    fun whiteNoiseReadsItsPsdFlat() {
        val r = Random(7)
        val noise = FloatArray(fs * 10) { (r.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, db) = Spectrum.averageSpectrumDb(noise, fs)
        // DC 近傍は標本平均、Nyquist は片側化の例外なので外す
        val body = db.copyOfRange(3, db.size - 1)
        val mean = body.average()
        assertEquals("PSD の絶対値 (10·log10(2σ²/fs))", 10.0 * log10(2.0 / 3.0 / fs), mean, 0.2)
        val worst = body.maxOf { abs(it - mean) }
        assertTrue("平均からの最大ずれ $worst dB (許容 1.5 dB)", worst < 1.5)
    }

    // ピンクノイズは -10 dB/decade。生成 (Voss-McCartney) と計測 (Welch) は独立の実装なので、
    // 両方をまたいだ検査になる。
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

    // 窓 1 枚に足りない入力は空を返す (窓は 2 の冪で縮む。その下限を割ったとき)
    @Test
    fun tooShortInputReturnsEmpty() {
        val (hz, db) = Spectrum.averageSpectrumDb(FloatArray(100), fs)
        assertEquals(0, hz.size)
        assertEquals(0, db.size)
    }

    // 窓長より短い入力では窓が 2 の冪で縮む (3000 サンプル → 窓 2048 → 1025 bin)
    @Test
    fun windowShrinksToPowerOfTwoForShortInput() {
        val (hz, db) = Spectrum.averageSpectrumDb(sine(1_000.0, 3_000), fs)
        assertEquals(1_025, db.size)
        assertEquals(fs / 2048.0, hz[1], 1e-9)
    }

    // 対数周波数の線形補間: 100→1000 Hz の幾何平均 (316.23 Hz) は dB でも中点になる
    @Test
    fun resampleInterpolatesInLogFrequency() {
        val srcHz = doubleArrayOf(100.0, 1_000.0, 10_000.0)
        val srcDb = doubleArrayOf(0.0, 10.0, 30.0)
        val out = Spectrum.resampleDb(srcHz, srcDb, doubleArrayOf(316.22776601683796, 3_162.2776601683795))
        assertEquals(5.0, out[0], 1e-9)
        assertEquals(20.0, out[1], 1e-9)
    }

    // 範囲外は端の値で固定。外挿すると題材に無い帯域の重みが傾きの延長で暴れる
    @Test
    fun resampleClampsOutsideTheSourceRange() {
        val srcHz = doubleArrayOf(0.0, 100.0, 10_000.0) // 先頭の DC は読み飛ばされる
        val srcDb = doubleArrayOf(99.0, -3.0, 12.0)
        val out = Spectrum.resampleDb(srcHz, srcDb, doubleArrayOf(1.0, 100.0, 10_000.0, 40_000.0))
        assertEquals(-3.0, out[0], 1e-9) // DC の 99.0 ではなく最初の正の周波数の値
        assertEquals(-3.0, out[1], 1e-9)
        assertEquals(12.0, out[2], 1e-9)
        assertEquals(12.0, out[3], 1e-9)
    }

    // 単調な源は載せ替えても単調のまま (線形補間 + 端の固定なら崩れない)
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

    /**
     * ループの継ぎ目の構造:
     * - 折り返し (最終フレーム → 先頭) が「切り落とす前の連続 2 サンプル」になる
     * - fade 区間の外は素通し
     * - 混合は等パワー (h² + t² = 1)。**線形や無音経由の fade だとここで落ちる**
     *   (線形は中点で h²+t² = 0.5)
     */
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
        // 折り返し: out の先頭 = 切り落とした領域の先頭 (= 最終フレームの続き)
        assertEquals(outFrames.toFloat(), out[0], 1e-3f)
        assertEquals((10_000 + outFrames).toFloat(), out[1], 1e-3f)
        // fade の終端では head 側が素通しに戻る
        assertEquals((fade - 1).toFloat(), out[(fade - 1) * 2], 1e-3f)
        // fade 区間の外は素通し
        for (i in fade until outFrames) {
            assertEquals(clip[i * 2], out[i * 2], 0f)
            assertEquals(clip[i * 2 + 1], out[i * 2 + 1], 0f)
        }
        // 2 チャネルの連立から混合係数 (h, t) を復元して等パワーを確かめる
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

    // fade がループ長に対して大きすぎるときは半分まで詰める。2 フレーム未満なら素通し
    @Test
    fun crossfadeLoopClampsDegenerateFades() {
        val clip = FloatArray(10) { it.toFloat() }
        assertEquals(10, Spectrum.crossfadeLoop(clip, 1, 0).size)
        assertEquals(10, Spectrum.crossfadeLoop(clip, 1, 1).size)
        // fade 100 > frames/2 = 5 → 5 に詰まって 5 フレーム残る
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

    // ピンクノイズの正規化はピークが構造的に 1.0 を超えない (±1 の 17 項 / 17)
    @Test
    fun pinkNoiseStaysWithinFullScale() {
        val noise = Spectrum.pinkNoise(fs, Random(5))
        assertTrue(noise.all { abs(it) <= 1.0f })
    }
}
