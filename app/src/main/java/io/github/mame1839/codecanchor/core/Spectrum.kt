package io.github.mame1839.codecanchor.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 題材 (曲の一節) の PCM を扱う純関数。平均スペクトル・ループの継ぎ目・試験信号。
 *
 * 「好みの EQ を見つける機能」の音量等価 (eq-finder-design.md §1) は、ループ再生する一節の
 * 実測パワースペクトルを聴感重みに使う。ここはその実測部分で、Android に依存しない —
 * 正しさはホストの単体テスト (SpectrumTest) で固定し、実機では測り直さない。
 *
 * **dB の絶対値は較正しない。**重みは L_c の分子と分母の両方に掛かるので、
 * 一定のオフセットは結果から消える。テストが当てにするのは下の正規化の定義だけ。
 */
object Spectrum {

    /** Welch 平均の窓長。4096 @ 48 kHz で分解能 11.7 Hz、EQ の Q に対して十分細かい。 */
    private const val WINDOW = 4096

    /** 窓 1 枚も置けない入力は題材ではないので、スペクトルを出さずに空を返す。 */
    private const val MIN_WINDOW = 256

    /**
     * Welch 法の平均パワースペクトル。周期 Hann 窓・50% ホップ。
     *
     * 返り値は (binHz, powerDb)。bin は 0 (DC) 〜 fs/2 (Nyquist) の n/2+1 点。
     * 入力が窓長 4096 より短ければ窓を 2 の冪で縮め、[MIN_WINDOW] を切ったら空の組を返す。
     *
     * **契約: 値は PSD (per Hz) の dB。基準 (0 dB の位置) は任意。**
     * `P[k] = 片側化 × mean|X[k]|² / (fs × Σw²)` — 分母の Σw² が窓の等価雑音帯域幅の補正で、
     * これを欠くと窓長が縮んだ呼び出しと絶対値が食い違う。白色雑音 (分散 σ²) は窓長に依らず
     * 10·log10(2σ²/fs) を読む — この既知値がテストの足場。
     *
     * **対数ビンへの合算はしない。**EqLoudness 側が対数グリッドの帯域幅補正 (×f) を掛けるので、
     * ここで束ねると二重補正になる。載せ替えは補間だけ ([resampleDb])。
     */
    fun averageSpectrumDb(pcm: FloatArray, fs: Int): Pair<DoubleArray, DoubleArray> {
        var n = WINDOW
        while (n > 0 && n > pcm.size) n = n shr 1
        if (n < MIN_WINDOW) return Pair(DoubleArray(0), DoubleArray(0))
        val hop = n / 2
        val window = DoubleArray(n) { 0.5 * (1.0 - cos(2.0 * PI * it / n)) }
        val windowSquaredSum = window.sumOf { it * it }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val acc = DoubleArray(n / 2 + 1)
        var segments = 0
        var start = 0
        while (start + n <= pcm.size) {
            for (i in 0 until n) {
                re[i] = pcm[start + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in 0..n / 2) acc[k] += re[k] * re[k] + im[k] * im[k]
            segments++
            start += hop
        }
        val binHz = DoubleArray(n / 2 + 1) { it.toDouble() * fs / n }
        val norm = 1.0 / (segments.toDouble() * fs * windowSquaredSum)
        val powerDb = DoubleArray(n / 2 + 1) { k ->
            val sided = if (k == 0 || k == n / 2) 1.0 else 2.0
            // 1e-20 は完全な無音 (log10(0)) の下駄。-200 dB として読める
            10.0 * log10(acc[k] * sided * norm + 1e-20)
        }
        return Pair(binHz, powerDb)
    }

    /**
     * dB 列を別の周波数グリッドへ載せ替える (対数周波数で線形補間)。
     *
     * PSD の dB をそのまま補間する — **束ねない・積分しない** (帯域幅の扱いは EqLoudness 側)。
     *
     * 呼び出し側が EqLoudness の対数グリッドを渡す。**ここからそのグリッドを import しない** —
     * 依存の向きを固定するための取り決めで、シグネチャに現れない分ここに書いておく。
     *
     * [srcHz] は昇順であること ([averageSpectrumDb] の返りはそう)。DC (0 Hz) は対数軸に
     * 置けないので読み飛ばす。グリッドの範囲外は端の値で固定 — 外挿すると、題材に無い
     * 超低域・超高域の重みが傾きの延長で暴れる。
     */
    fun resampleDb(srcHz: DoubleArray, srcDb: DoubleArray, dstHz: DoubleArray): DoubleArray {
        require(srcHz.size == srcDb.size) { "srcHz と srcDb の長さが違う" }
        val first = srcHz.indexOfFirst { it > 0.0 }
        if (first < 0) return DoubleArray(dstHz.size)
        if (srcHz.size - first == 1) return DoubleArray(dstHz.size) { srcDb[first] }
        return DoubleArray(dstHz.size) { d ->
            val f = dstHz[d]
            when {
                f <= srcHz[first] -> srcDb[first]
                f >= srcHz[srcHz.size - 1] -> srcDb[srcDb.size - 1]
                else -> {
                    var lo = first
                    var hi = srcHz.size - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) ushr 1
                        if (srcHz[mid] <= f) lo = mid else hi = mid
                    }
                    val t = (ln(f) - ln(srcHz[lo])) / (ln(srcHz[hi]) - ln(srcHz[lo]))
                    srcDb[lo] + (srcDb[hi] - srcDb[lo]) * t
                }
            }
        }
    }

    /**
     * ギャップレスループ用に継ぎ目を仕込む。末尾 [fadeFrames] を切り落とし、切り落とした分を
     * 先頭に等パワーで重ねる。返りは (frames - fadeFrames) フレーム。
     *
     * こうすると折り返しの瞬間 (最終フレーム → 先頭) が「切り落とす前の連続した 2 サンプル」に
     * なるので、境界そのものにクリックが出ない。音色の混ざりは先頭の fade 区間に移る。
     *
     * **無音への fade in/out で凹ませる形にしないこと** (仕様の禁止事項)。等パワー
     * (sin/cos) なら無相関な素材で実効レベルが保たれる。
     */
    fun crossfadeLoop(pcm: FloatArray, channels: Int, fadeFrames: Int): FloatArray {
        require(channels >= 1) { "channels は 1 以上" }
        val frames = pcm.size / channels
        val fade = min(fadeFrames, frames / 2)
        if (fade < 2) return pcm.copyOf(frames * channels)
        val outFrames = frames - fade
        val out = FloatArray(outFrames * channels)
        for (i in 0 until outFrames) {
            if (i < fade) {
                // i=0 で head=0 / tail=1 (先頭 = 末尾の続き)、i=fade-1 で head=1 / tail=0
                val theta = (PI / 2) * i / (fade - 1)
                val head = sin(theta)
                val tail = cos(theta)
                for (c in 0 until channels) {
                    val a = pcm[i * channels + c] * head
                    val b = pcm[(outFrames + i) * channels + c] * tail
                    out[i * channels + c] = (a + b).toFloat()
                }
            } else {
                for (c in 0 until channels) out[i * channels + c] = pcm[i * channels + c]
            }
        }
        return out
    }

    /** インターリーブ PCM をモノラルへ (各チャネルの平均)。スペクトル計測用で、再生には使わない。 */
    fun monoMix(pcm: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return pcm.copyOf()
        val frames = pcm.size / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += pcm[i * channels + c]
            out[i] = sum / channels
        }
        return out
    }

    /**
     * ピンクノイズ (Voss-McCartney、16 行 + 白色 1 行)。デバッグトーンと、
     * スペクトル計測の傾き検査 (-10 dB/decade) の試験信号。
     *
     * 正規化は (行数+1) での割り算 — 各行が ±1 なので**ピークが 1.0 を超えない**ことが
     * 構造で保証される (実効値は約 -17 dBFS)。ゲインを掛けて稼がないこと。クリップは
     * デバッグトーンに高調波を混ぜ、device effect の検証で見る対象を汚す。
     */
    fun pinkNoise(samples: Int, random: Random): FloatArray {
        val rows = 16
        val values = DoubleArray(rows) { random.nextDouble(-1.0, 1.0) }
        var sum = values.sum()
        val out = FloatArray(samples)
        for (n in 0 until samples) {
            if (n > 0) {
                val r = n.countTrailingZeroBits()
                if (r < rows) {
                    sum -= values[r]
                    values[r] = random.nextDouble(-1.0, 1.0)
                    sum += values[r]
                }
            }
            out[n] = ((sum + random.nextDouble(-1.0, 1.0)) / (rows + 1)).toFloat()
        }
        return out
    }

    /**
     * 基数 2 の反復 Cooley-Tukey (in-place)。長さは 2 の冪であること — 呼ぶのは
     * [averageSpectrumDb] だけで、そこが窓長を 2 の冪に保っている。
     *
     * 回転因子は漸化式で回す。誤差は窓長 4096 で 1e-12 のオーダーに収まり、
     * テストの許容 (0.05 dB) より桁違いに小さい。
     */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val stepRe = cos(ang)
            val stepIm = sin(ang)
            var base = 0
            while (base < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val aRe = re[base + k]
                    val aIm = im[base + k]
                    val bRe = re[base + k + half] * curRe - im[base + k + half] * curIm
                    val bIm = re[base + k + half] * curIm + im[base + k + half] * curRe
                    re[base + k] = aRe + bRe
                    im[base + k] = aIm + bIm
                    re[base + k + half] = aRe - bRe
                    im[base + k + half] = aIm - bIm
                    val nextRe = curRe * stepRe - curIm * stepIm
                    curIm = curRe * stepIm + curIm * stepRe
                    curRe = nextRe
                }
                base += len
            }
            len = len shl 1
        }
    }
}
