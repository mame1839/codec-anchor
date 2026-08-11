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

    // 実応答を評価するニュートン法の反復。10 バンド +12 dB で 8 回、31 バンドでも
    // 12 回あれば tolerance に落ちる (EqSolverTest が収束を見張っている)。
    private const val SOLVE_ITERATIONS = 16
    private const val SOLVE_TOLERANCE_DB = 1e-9

    // 量子化した後の整数の詰め。0.1 dB 刻みで丸めた時点で目標から最大 0.09 dB ずれるので、
    // 「+12.0 に合わせたのに +11.9 と出る」が起きる。
    private const val REFINE_SWEEPS = 8

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
     * 指定すると合成応答のピークが +10.86 dB になる (Q = defaultQ(10) = 1.0 での実測。
     * tools/eq/05_geq_gain_solve_10band.py の +8.85 は Q=1.41 で回した値)。
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
        val bands = freqs.mapIndexed { i, hz ->
            EqBand(
                freqHz = hz.coerceIn(EqBand.FREQ_RANGE),
                q100 = q100,
                gainDb10 = Math.round(solution.gainsDb[i] * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqBand.GAIN_RANGE),
            )
        }
        val targetDb10 = IntArray(targetDb.size) {
            Math.round(targetDb[it] * EqUnits.GAIN_SCALE).toInt()
        }
        return refineToTargets(bands, targetDb10, solution.q, fs)
    }

    /**
     * 量子化で出たずれを整数のまま詰める。
     *
     * **成り立つ理由: peaking は自分の中心では厳密に自分のゲインになる** (RBJ の定義。
     * 12 dB のバンドの中心での応答は 12.000000 dB)。つまりバンド i のゲインを 1 目盛り
     * 動かすと、バンド中心 i での合成応答もちょうど 1 目盛り動く。他のバンドへの漏れは
     * 裾の分だけなので、掃き出しを数回まわすと全部の中心が目標の 0.05 dB 以内に入る。
     *
     * これが無いと、解を 0.1 dB に丸めた時点で最大 0.09 dB ずれて
     * 「+12.0 に合わせたのに摘みが +11.9 と出る」が起きる。
     */
    private fun refineToTargets(
        bands: List<EqBand>,
        targetDb10: IntArray,
        q: Double,
        fs: Int,
    ): List<EqBand> {
        if (bands.isEmpty()) return bands
        val grid = CentreGrid(bands.map { it.freqHz }, q, fs)
        val gains = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        val db10 = IntArray(bands.size) { bands[it].gainDb10 }
        repeat(REFINE_SWEEPS) {
            var moved = false
            for (i in db10.indices) {
                val error = targetDb10[i] -
                    Math.round(grid.combinedAt(i, gains) * EqUnits.GAIN_SCALE).toInt()
                if (error == 0) continue
                val next = (db10[i] + error).coerceIn(EqBand.GAIN_RANGE)
                if (next == db10[i]) continue // 上限に当たっている。これ以上詰められない
                db10[i] = next
                gains[i] = next.toDouble() / EqUnits.GAIN_SCALE
                moved = true
            }
            if (!moved) return bands.mapIndexed { i, b -> b.copy(gainDb10 = db10[i]) }
        }
        return bands.mapIndexed { i, b -> b.copy(gainDb10 = db10[i]) }
    }

    /**
     * グラフィックの摘みが表す値 = そのバンド中心で**実際に鳴る**音量 (dB * 10)。
     *
     * バンドのゲインそのものではない。隣のバンドの裾が足し合わさるので、
     * 10 バンド全部を素で +12 dB にすると中心では +22.5 dB 鳴る。
     * 目標値を別に保存しないのは、行列 M が正則で**ここから完全に復元できる**ため
     * (往復誤差は倍精度で 3.6e-15、0.1 dB 量子化を挟んでも [refineToTargets] が 0.05 dB に収める)。
     */
    fun graphicTargetsDb10(bands: List<EqBand>, fs: Int = DEFAULT_FS): IntArray =
        IntArray(bands.size) { i ->
            Math.round(combinedResponseDb(bands, bands[i].freqHz.toDouble(), fs) * EqUnits.GAIN_SCALE)
                .toInt()
        }

    /**
     * 摘み [index] を [targetDb10] にしたバンドの並びを返す。他のバンドの目標は据え置く。
     *
     * **グラフィック専用。**パラメトリックは fc と Q をユーザが決めるので、
     * ゲインは触ったバンドにそのまま入れる (干渉補正を掛ける先が定義できない)。
     *
     * **他の摘みが動かないのは、この解き方から出る性質。**バンド中心での実現値が目標に
     * 厳密に一致するので、目標を 1 つだけ変えれば残りは定義から不変になる。
     * 実測でも移動量は 0.064 dB (表示の刻み 0.1 dB 未満)。
     * ⚠️ 求解が厳密でないとこれが崩れる — 線形モデルで解いていたときは 0.367 dB 動いていた。
     *
     * ### 中心と中心の**間**の起伏を減らす案 (Välimäki & Liski 2017 III-B) を採らない理由
     *
     * 論文は中心の幾何平均を設計点に足して 19x10 の擬似逆で解く。**こちらでは採れない** —
     * 相互作用行列は 10x10 で**階数 10、零空間の次元が 0** (cond 3.38)。
     * つまり**バンド中心での値を決めた時点で、中心間の形は完全に determined で、
     * 調整の余地が 1 つも残っていない。**19 点版が中心間を良くできるのは
     * **中心を犠牲にするから**で、実測すると中心の誤差が最大 1.24 dB (8 kHz) 出る。
     *
     * **摘みの値 = バンド中心で鳴る音量、という約束のほうが優先される** (それが
     * 「摘みが曲線に乗らない」の再発を防いでいる)。中心間に残る不足は
     * 中点で -0.7 dB 前後、**8k-16k の 1 区間だけ -2.98 dB** (11.3 kHz)。
     * これは 1 オクターブ間隔の最上バンドが fs=48k の Nyquist に寄って潰れる分で、
     * **最上バンドをシェルフにしても改善しない** (実測 -2.98 -> -3.67 dB で悪化し、
     * シェルフのゲインが 21 dB 要る)。減らしたければバンド数を 15 か 31 にする。
     */
    fun withGraphicTarget(
        bands: List<EqBand>,
        index: Int,
        targetDb10: Int,
        fs: Int = DEFAULT_FS,
    ): List<EqBand> {
        if (index !in bands.indices) return bands
        if (bands.size == 1) return listOf(bands[0].copy(gainDb10 = targetDb10.coerceIn(EqBand.GAIN_RANGE)))
        val freqs = bands.map { it.freqHz }
        val q = bands[index].q100.toDouble() / EqUnits.Q_SCALE
        // ドラッグ 1 コマごとに走るので、読み取りも速い経路で。値は graphicTargetsDb10 と一致する
        // (EqSolverTest の theFastCentreGridAgreesWithTheReferenceFormula が突き合わせている)。
        val grid = CentreGrid(freqs, q, fs)
        val gains = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        val targets = DoubleArray(bands.size) {
            Math.round(grid.combinedAt(it, gains) * EqUnits.GAIN_SCALE).toDouble() / EqUnits.GAIN_SCALE
        }
        targets[index] = targetDb10.toDouble() / EqUnits.GAIN_SCALE
        return solveBands(targets, freqs, q, fs)
    }

    /**
     * バンド中心どうしの応答を、ゲインだけ変えて何度も評価するための前計算。
     *
     * 求解はドラッグ 1 コマごとに走る (`EqCurve.withPreview`)。素直に
     * [peakingResponseDb] を n² 回ずつ呼ぶと 31 バンドで 1 コマ 1.9 ms 掛かり、
     * 144 Hz の 6.9 ms に対して重すぎる。
     *
     * RBJ peaking の分子と分母は、ゲインを含む項が `alpha*A` と `alpha/A` の
     * 1 つずつしかない形に整理できる:
     * ```
     *   numRe = P + (alpha*A)*U    denRe = P + (alpha/A)*U
     *   numIm = R + (alpha*A)*V    denIm = R + (alpha/A)*V
     *   P = 1 - 2cos(w0)cos(w) + cos(2w)      U = 1 - cos(2w)
     *   R = 2cos(w0)sin(w) - sin(2w)          V = sin(2w)
     * ```
     * P / U / R / V と alpha は周波数と Q と fs だけで決まるので、ここで持つ。
     * **[peakingResponseDb] と同じ値を返すことを EqSolverTest が突き合わせている。**
     * 式を触ったら必ずそのテストを見ること。
     */
    internal class CentreGrid(freqs: List<Int>, q: Double, val fs: Int) {
        val n = freqs.size
        private val p = DoubleArray(n * n)
        private val u = DoubleArray(n * n)
        private val r = DoubleArray(n * n)
        private val v = DoubleArray(n * n)
        private val alpha = DoubleArray(n)

        init {
            // 評価点 (i) 側の三角関数はバンド (j) に依存しない。j の内側で回すと n^2 回
            // 計算することになるので先に出しておく。
            val cosW = DoubleArray(n)
            val sinW = DoubleArray(n)
            val cos2W = DoubleArray(n)
            val sin2W = DoubleArray(n)
            for (i in 0 until n) {
                val w = 2.0 * Math.PI * freqs[i] / fs
                cosW[i] = cos(w)
                sinW[i] = sin(w)
                cos2W[i] = cos(2 * w)
                sin2W[i] = sin(2 * w)
            }
            for (j in 0 until n) {
                val w0 = 2.0 * Math.PI * freqs[j] / fs
                alpha[j] = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                for (i in 0 until n) {
                    val k = i * n + j
                    p[k] = 1 - 2 * cosW0 * cosW[i] + cos2W[i]
                    u[k] = 1 - cos2W[i]
                    r[k] = 2 * cosW0 * sinW[i] - sin2W[i]
                    v[k] = sin2W[i]
                }
            }
        }

        /** バンド [j] を [gainDb] にしたときの、バンド中心 [i] での応答 (dB)。 */
        fun responseDb(i: Int, j: Int, gainDb: Double): Double {
            if (gainDb == 0.0) return 0.0
            val a = 10.0.pow(gainDb / 40.0)
            val k = i * n + j
            val hi = alpha[j] * a
            val lo = alpha[j] / a
            val numRe = p[k] + hi * u[k]
            val numIm = r[k] + hi * v[k]
            val denRe = p[k] + lo * u[k]
            val denIm = r[k] + lo * v[k]
            val den = denRe * denRe + denIm * denIm
            if (den == 0.0) return 0.0
            return 10.0 * log10((numRe * numRe + numIm * numIm) / den)
        }

        /** バンド全部を重ねた、バンド中心 [i] での応答 (dB)。 */
        fun combinedAt(i: Int, gains: DoubleArray): Double {
            var sum = 0.0
            for (j in 0 until n) sum += responseDb(i, j, gains[j])
            return sum
        }
    }

    private fun solveOnce(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int): DoubleArray {
        val n = freqs.size
        val grid = CentreGrid(freqs, q, fs)
        // M[i][j] = バンド j に 1 dB 入れたときのバンド中心 i での応答 (dB)。
        // これはニュートン法のヤコビアンであって応答そのものではない。1 回だけ作る。
        val m = Array(n) { i -> DoubleArray(n) { j -> grid.responseDb(i, j, 1.0) } }
        val gains = targetDb.copyOf()
        repeat(SOLVE_ITERATIONS) {
            // ⚠️ 実現値は「M × gains」ではなく **本物の応答**を評価して作る。
            // RBJ peaking の裾はゲインに比例しない — 2 kHz のバンドの 1 kHz での応答は
            // 1 dB で 0.3048 dB だが 12 dB では 3.9307 dB で、比例なら 3.6580 dB。
            // M を応答として使うと、この差がそのまま解の誤差になる
            // (10 バンド全部 +12 dB の目標でバンド中心が 0.32 dB ずれる)。さらに
            // reband() が「真の応答を目標に読んで解き直す」写像なので、線形モデルで解くと
            // 往復のたびに曲線が育って発散する (+12 dB で 20 往復すると +27.9 dB)。
            // ヤコビアンとしてなら近似でよく、反復が誤差を消す。
            val residual = DoubleArray(n) { i -> targetDb[i] - grid.combinedAt(i, gains) }
            val delta = solveLinear(m, residual) ?: return gains
            var worst = 0.0
            for (i in 0 until n) {
                gains[i] += delta[i]
                if (abs(delta[i]) > worst) worst = abs(delta[i])
            }
            if (worst < SOLVE_TOLERANCE_DB) return gains
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

    /**
     * バンド 1 本の、指定周波数での応答 (dB)。
     *
     * 種別から式への振り分けはここ 1 箇所だけ。合成応答も画面の描画もこれを足して作るので、
     * 種別が増えても両方が同時に追従する。
     */
    fun bandResponseDb(band: EqBand, atHz: Double, fs: Int = DEFAULT_FS): Double {
        val gain = band.gainDb10.toDouble() / EqUnits.GAIN_SCALE
        val q = band.q100.toDouble() / EqUnits.Q_SCALE
        return when (band.type) {
            EqBandType.LOW_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, false)
            EqBandType.HIGH_SHELF -> shelfResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs, true)
            else -> peakingResponseDb(atHz, band.freqHz.toDouble(), q, gain, fs)
        }
    }

    /** バンド全部を重ねた応答の、指定周波数での値 (dB)。 */
    fun combinedResponseDb(bands: List<EqBand>, atHz: Double, fs: Int = DEFAULT_FS): Double =
        bands.sumOf { bandResponseDb(it, atHz, fs) }

    /**
     * 自動プリアンプ。合成応答のピークから必要なヘッドルームを求める。
     * 個々のゲインの合計ではない — 10 バンド全部 +6 dB のとき合計は 60 dB だが
     * 実際のピークは +10.86 dB (Q = defaultQ(10) = 1.0。
     * tools/eq/05_geq_gain_solve_10band.py の +8.85 は Q=1.41 で回した値)。
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
