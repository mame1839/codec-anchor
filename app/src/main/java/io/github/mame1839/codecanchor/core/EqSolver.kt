package io.github.mame1839.codecanchor.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// RBJ Audio EQ Cookbook。AutoEQ の実装が同じ式の逐語訳なので、これに合わせると
// ParametricEQ.txt をフィットなしでそのまま鳴らせる (eq-spec.md §5)。
// cramping 補正 (Orfanidis 等) を入れないのは、AutoEQ が cramping を織り込んだ状態で
// fc / Q / gain を最適化しているため。「直す」と意図した曲線から外れる。
object EqSolver {

    const val DEFAULT_FS = 48_000

    // 解の絶対値がこれを超えたら Q を上げて解き直す。超えたまま渡すと、
    // .so 側の検査で設定が丸ごと捨てられるか、通ってしまえば爆音になる。
    private const val SOLVED_GAIN_LIMIT_DB = 20.0
    private const val Q_ESCALATION = 1.5
    private const val Q_ESCALATION_TRIES = 4

    /**
     * 求解の結果。
     *
     * ゲインだけでなく Q も返すのは、solve() が上限に収まらないときに **Q を上げて解き直す**から。
     * 返ったゲインはそのとき使った Q に対する解なので、呼び出し側が元の Q でバンドを組むと
     * 解いた応答と実際の応答が食い違う。バンドを作るなら solveBands() を使う。
     */
    class Solution(val gainsDb: DoubleArray, val q: Double)

    /**
     * バンド間隔に見合う Q。ユーザには出さない (eq-spec.md §7)。
     * 4 つとも実測 (tools/eq/15_q_per_bandcount.py)。
     *
     * 選定の規則は「誤差が最小の Q」ではなく
     * 「飽和せず、条件数が二桁に収まる範囲で誤差が最小の Q」。
     *   - max|band gain| が上限 (15 dB) に張り付いた組み合わせは採らない。飽和しているので
     *     その誤差はクランプ後の結果であって最適ではない (10 バンドの Q=0.7、15 バンドの Q=1.0)
     *   - 31 バンドは Q=1.0 のほうが誤差が小さい (0.47 対 0.73 dB) が、条件数が 327 対 17.4 で
     *     19 倍悪い。手で描いた病的な目標で解が暴れるので採らない
     * この規則を消すと、次に測り直した人が Q=1.0 に変えてしまう。
     */
    fun defaultQ(bandCount: Int): Double = when (bandCount) {
        31 -> 2.0 // 1/3 oct。誤差 0.73 dB / 条件数 17.4
        15 -> 1.41 // 2/3 oct。誤差 2.57 dB / 条件数 6.1
        10 -> 1.0 // 1 oct。  誤差 2.09 dB / 条件数 4.7
        else -> 0.7 // 2 oct。  誤差 3.76 dB / 条件数 2.0
    }

    /**
     * ISO 266 の推奨中心周波数。式で作らずに表で持つ。
     *
     * 式 + 丸めにすると低域で隣り合う値が同じ数に落ちることがあり、
     * バンドが重なって相互作用行列が特異になる。表なら起きない。
     * Q の実測もこの周波数に対して取ってあるので、ここを動かすと Q の根拠が外れる。
     */
    fun centerFrequencies(bandCount: Int): List<Int> = when (bandCount) {
        // 1/3 oct、20 Hz 〜 20 kHz
        31 -> listOf(
            20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000, 6_300,
            8_000, 10_000, 12_500, 16_000, 20_000,
        )
        // 2/3 oct
        15 -> listOf(
            25, 40, 63, 100, 160, 250, 400, 630, 1_000, 1_600, 2_500, 4_000, 6_300, 10_000, 16_000,
        )
        // 1 oct
        10 -> listOf(32, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        // 2 oct
        else -> listOf(63, 250, 1_000, 4_000, 16_000)
    }

    /**
     * 目標のゲイン (dB) をバンド中心で与えると、実際にその応答になるバンドゲインを返す。
     *
     * 素朴なカスケードは隣のバンドのスカートが足し合わさるので、10 バンド全部 +6 dB を
     * 指定すると合成応答のピークが +8.85 dB になる (tools/eq/05_geq_gain_solve_10band.py)。
     * 相互作用行列 M を作って g <- g + M^-1 (target - realized(g)) を数回回すと 0.000 dB。
     */
    fun solve(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int = DEFAULT_FS): Solution {
        var currentQ = q
        repeat(Q_ESCALATION_TRIES) {
            val solved = solveOnce(targetDb, freqs, currentQ, fs)
            if (solved.all { abs(it) <= SOLVED_GAIN_LIMIT_DB }) return Solution(solved, currentQ)
            currentQ *= Q_ESCALATION
        }
        // Q を上げても収まらない目標。補正なしの素朴な値に戻す。
        // 暴れた解を渡すより、効きが目標より強いほうがまだ説明できる。
        return Solution(targetDb.copyOf(), q)
    }

    /**
     * 解いた結果をそのままバンドにする。**Q は solve() が実際に使った値**を入れるので、
     * 求解の途中で Q が上がっても組んだバンドの応答が解と一致する。
     */
    fun solveBands(
        targetDb: DoubleArray,
        freqs: List<Int>,
        q: Double,
        fs: Int = DEFAULT_FS,
    ): List<EqBand> {
        val solution = solve(targetDb, freqs, q, fs)
        val q100 = (solution.q * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
        return freqs.mapIndexed { i, hz ->
            EqBand(
                freqHz = hz.coerceIn(EqBand.FREQ_RANGE),
                q100 = q100,
                gainDb10 = (solution.gainsDb[i] * EqUnits.GAIN_SCALE).toInt().coerceIn(EqBand.GAIN_RANGE),
            )
        }
    }

    private fun solveOnce(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int): DoubleArray {
        val n = freqs.size
        // M[i][j] = バンド j に 1 dB 入れたときのバンド中心 i での応答 (dB)。線形なので 1 回だけ作る。
        val m = Array(n) { i ->
            DoubleArray(n) { j ->
                peakingResponseDb(freqs[i].toDouble(), freqs[j].toDouble(), q, 1.0, fs)
            }
        }
        val gains = targetDb.copyOf()
        val iterations = if (n >= 31) 8 else 6
        repeat(iterations) {
            val realized = DoubleArray(n) { i ->
                (0 until n).sumOf { j -> m[i][j] * gains[j] }
            }
            val residual = DoubleArray(n) { i -> targetDb[i] - realized[i] }
            val delta = solveLinear(m, residual) ?: return gains
            for (i in 0 until n) gains[i] += delta[i]
        }
        return gains
    }

    /** ガウスの消去法。特異なら null (呼び出し側はその回で打ち切る)。 */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (k in col..n) m[row][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** RBJ の peaking 1 段の、指定周波数での応答 (dB)。 */
    fun peakingResponseDb(atHz: Double, fcHz: Double, q: Double, gainDb: Double, fs: Int): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fcHz / fs
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1 + alpha * a
        val b1 = -2 * cos(w0)
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cos(w0)
        val a2 = 1 - alpha / a
        return biquadMagnitudeDb(b0, b1, b2, a0, a1, a2, atHz, fs)
    }

    fun shelfResponseDb(
        atHz: Double,
        fcHz: Double,
        q: Double,
        gainDb: Double,
        fs: Int,
        high: Boolean,
    ): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fcHz / fs
        // RBJ のシェルフは Q をスロープとして使うので、Q > 1 かつゲインが大きいと
        // 根号の中が負になる (式の定義域の外)。負のまま sqrt に渡すと NaN が
        // 合成応答とプリアンプの計算に伝播して全体が壊れるので、0 で止める。
        // 0 のときは共振項が消えた素直なシェルフになる。
        val radicand = ((a + 1 / a) * (1 / q - 1) + 2).coerceAtLeast(0.0)
        val alpha = sin(w0) / 2.0 * sqrt(radicand)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val cosW0 = cos(w0)
        return if (high) {
            biquadMagnitudeDb(
                a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha),
                -2 * a * ((a - 1) + (a + 1) * cosW0),
                a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha),
                (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha,
                2 * ((a - 1) - (a + 1) * cosW0),
                (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha,
                atHz, fs,
            )
        } else {
            biquadMagnitudeDb(
                a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha),
                2 * a * ((a - 1) - (a + 1) * cosW0),
                a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha),
                (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha,
                -2 * ((a - 1) + (a + 1) * cosW0),
                (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha,
                atHz, fs,
            )
        }
    }

    private fun biquadMagnitudeDb(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
        atHz: Double,
        fs: Int,
    ): Double {
        val w = 2.0 * Math.PI * atHz / fs
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = a0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        if (den == 0.0) return 0.0
        return 20.0 * log10(num / den)
    }

    /** バンド全部を重ねた応答の、指定周波数での値 (dB)。 */
    fun combinedResponseDb(bands: List<EqBand>, atHz: Double, fs: Int = DEFAULT_FS): Double =
        bands.sumOf { band ->
            val gain = band.gainDb10.toDouble() / EqUnits.GAIN_SCALE
            val q = band.q100.toDouble() / EqUnits.Q_SCALE
            when (band.type) {
                EqBandType.LOW_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, false)
                EqBandType.HIGH_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, true)
                else -> peakingResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs)
            }
        }

    /**
     * 自動プリアンプ。合成応答のピークから必要なヘッドルームを求める。
     * 個々のゲインの合計ではない — 10 バンド全部 +6 dB のとき合計は 60 dB だが
     * 実際のピークは 8.85 dB (tools/eq/05_geq_gain_solve_10band.py)。
     * AutoEQ も同じ考え方 (PEQ.max_gain)。
     */
    fun autoPreampDb10(bands: List<EqBand>, fs: Int = DEFAULT_FS): Int {
        if (bands.isEmpty()) return 0
        var peak = 0.0
        // 20 Hz 〜 20 kHz を対数で 400 点。ピークを取り逃さない粒度。
        val steps = 400
        val lo = ln(20.0)
        val hi = ln(20_000.0)
        for (i in 0..steps) {
            val hz = Math.E.pow(lo + (hi - lo) * i / steps)
            val db = combinedResponseDb(bands, hz, fs)
            if (db > peak) peak = db
        }
        if (peak <= 0.0) return 0
        return -(peak * EqUnits.GAIN_SCALE).toInt()
    }
}
