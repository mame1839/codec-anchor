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

/**
 * [Spectrum.averageSpectrumDb] が返す値の単位の判定と、
 * [EqLoudness.weightsFromSpectrumDb] の帯域幅補正 (× GRID_HZ) がそれと噛み合うかの測定。
 *
 * 判定したい対立仮説は 2 つ:
 * - **per-Hz PSD の dB** (契約に書いてあるほう) — 値は窓長に依らず、fs に反比例する
 * - **per-bin パワーの dB** — 値は 2σ²/N で、窓長に依存し fs に依存しない
 *
 * どちらであっても一様グリッド上では定数倍しか違わないので、max 正規化を挟む
 * [EqLoudness.weightsFromSpectrumDb] の結果は変わらない (それも下で撃って確かめる)。
 * × GRID_HZ が壊れるのは値が**対数ビンに合算済み**のときだけで、それは
 * 実配線 (`ui/EqFinderController.kt` の averageSpectrumDb → resampleDb →
 * weightsFromSpectrumDb) に合算が無いこと + ピンクノイズの端から端までの検査で見る。
 */
class EqSpectrumPsdContractTest {

    private val fs = 48_000

    /** dB 列を線形パワーで平均して dB に戻す。dB のまま平均すると 1 区間のとき −2.5 dB の偏りが出る。 */
    private fun meanPowerDb(db: DoubleArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (k in from until until) sum += 10.0.pow(db[k] / 10.0)
        return 10.0 * log10(sum / (until - from))
    }

    // ── 判定 1: 絶対値。一様乱数 ±1 (σ² = 1/3) の片側 PSD は 2σ²/fs ────────────────
    // per-Hz なら 10·log10(2/3/48000) = −48.57 dB。
    // per-bin (窓 4096) なら 10·log10(2/3/4096) = −37.88 dB。10.7 dB 離れているので誤認しない。
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

    // ── 判定 2: 窓長不変性。密度は窓が縮んでも動かない。per-bin なら 2048 で +3.01 dB ──
    @Test
    fun densityLevelSurvivesWindowShrink() {
        val rLong = Random(21)
        val longNoise = FloatArray(fs * 10) { (rLong.nextDouble(-1.0, 1.0)).toFloat() }
        val rShort = Random(22)
        val shortNoise = FloatArray(3_000) { (rShort.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, dbLong) = Spectrum.averageSpectrumDb(longNoise, fs) // 窓 4096
        val (hzShort, dbShort) = Spectrum.averageSpectrumDb(shortNoise, fs) // 窓 2048 に縮む
        assertEquals(1_025, dbShort.size) // 縮んだことの確認 (SpectrumTest と同じ判定)
        assertEquals(fs / 2048.0, hzShort[1], 1e-9)
        val meanLong = meanPowerDb(dbLong, 3, dbLong.size - 1)
        val meanShort = meanPowerDb(dbShort, 3, dbShort.size - 1)
        println("窓 4096: %.3f dB / 窓 2048: %.3f dB / 差 %.3f (per-bin なら 3.01)".format(meanLong, meanShort, meanShort - meanLong))
        assertEquals("窓長を変えても密度は同じ", meanLong, meanShort, 0.8)
    }

    // ── 判定 3: fs 依存性。同じ PCM を fs 違いで読むと、密度は厳密に 10·log10(fs比) 動く ──
    // 正規化に fs が入っていなければ (per-bin なら) 差は 0。決定的なので許容 1e-6。
    @Test
    fun theFsArgumentScalesTheDensityExactly() {
        val r = Random(23)
        val noise = FloatArray(fs * 2) { (r.nextDouble(-1.0, 1.0)).toFloat() }
        val (_, db48) = Spectrum.averageSpectrumDb(noise, 48_000)
        val (_, db96) = Spectrum.averageSpectrumDb(noise, 96_000)
        val expected = 10.0 * log10(48_000.0 / 96_000.0) // −3.0103
        for (k in db48.indices) {
            assertEquals("bin $k", expected, db96[k] - db48[k], 1e-6)
        }
        println("fs 48k→96k で全 bin が %.4f dB 動いた (密度の定義どおり)".format(expected))
    }

    // ── 判定 4: 実配線の端から端。ピンクノイズ → 重みが「K 特性 × 定数」になること ──
    // ピンクの PSD ∝ 1/f。対数グリッドの帯域幅補正 ×f と打ち消し合うので、重みは
    // 10^(K/10) × 定数になるはず (EqLoudness.defaultWeights の KDoc と同じ主張)。
    // ×GRID_HZ を消すと −10 dB/decade、二重に掛けると +10 dB/decade の傾きが残るので、
    // ここの平坦性がその両方を殺す。経路は ui/EqFinderController.kt:440-442 と同一。
    @Test
    fun measuredPinkNoiseYieldsEqualOctaveWeights() {
        val pink = Spectrum.pinkNoise(fs * 10, Random(11))
        val (hz, db) = Spectrum.averageSpectrumDb(pink, fs)
        val w = EqLoudness.weightsFromSpectrumDb(Spectrum.resampleDb(hz, db, EqLoudness.GRID_HZ))
        // K を割り戻した残り。ピンクが理想 1/f なら定数。
        val xs = ArrayList<Double>()
        val flat = ArrayList<Double>()
        for (i in EqLoudness.GRID_HZ.indices) {
            val f = EqLoudness.GRID_HZ[i]
            if (f in 100.0..8_000.0) { // SpectrumTest が傾きを信用している帯域に合わせる
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

    // ── 判定 5: 定数オフセットは重みに影響しない (max 正規化で消える) ───────────────
    // per-Hz と per-bin は一様グリッド上では定数 dB 差でしかないので、この性質により
    // 「どちらであっても重みは同じ」— 単位の取り違えが起きても壊れるのは表示だけで、
    // 壊れうるのは対数ビン合算 (判定 4 が見る) だけ、という根拠。
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
