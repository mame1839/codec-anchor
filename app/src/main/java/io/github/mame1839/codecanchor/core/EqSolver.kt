package io.github.mame1839.codecanchor.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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

    // 実応答を評価するニュートン法の反復の上限。ヤコビアンを毎回作り直すので収束は二次で、
    // 通常は数回で tolerance に落ちる (EqSolverTest が収束を見張っている)。落ちないまま
    // 使い切る目標は解が大きく暴れているので、solve() の上限検査がエスカレーションで受け止める。
    private const val SOLVE_ITERATIONS = 16
    private const val SOLVE_TOLERANCE_DB = 1e-9

    // 量子化した後の整数の詰め。0.1 dB 刻みで丸めた時点で目標から最大 0.09 dB ずれるので、
    // 「+12.0 に合わせたのに +11.9 と出る」が起きる。
    private const val REFINE_SWEEPS = 8

    // 取り込みのフィット (fitCurve) の評価グリッド。20 Hz〜最終バンド中心を対数で刻む。
    // 2000 は eq-spec §1 の実測 (tools/eq/12_fit_redo.py) と同じ点数で、127 点の
    // AutoEQ 曲線には十分に密。
    private const val FIT_GRID_POINTS = 2000

    // 固定基底 (各バンド +6 dB の実応答をそのゲインで割った列) での減衰付き反復。
    // 12_fit_redo.py と同じ方式。solveOnce と違ってヤコビアンを動作点で作り直さないのは、
    // 摘みの契約 (中心厳密一致) と違ってフィットは妥協解でよく、毎回ヤコビアンを作る
    // ガウス・ニュートンと比べても DUNU の実測で差が 0.03 dB 以下だったため。
    // ここを solveOnce に「揃える」修正も、solveOnce をこちらに「揃える」修正も誤り。
    private const val FIT_ITERATIONS = 12
    private const val FIT_DAMPING = 0.6
    private const val FIT_REFERENCE_GAIN_DB = 6.0

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
     * 4 つとも実測 (tools/eq/17_q_joint_rederive.py。2026-08-12 に引き直した)。
     *
     * 選定の規則は「誤差が最小の Q」ではなく
     * 「既定のまま実プリセットがエスカレートせず、条件数が二桁に収まる範囲で、
     *   **平ら目標の中心間の谷**と AutoEQ の実現誤差の両方が最小の Q」。
     *   - **評価は必ずこの製品の経路 (中心厳密一致 + 20 dB でエスカレーション) で行う。**
     *     旧表 (0.7 / 1.0 / 1.41 / 2.0) は別経路 (lstsq + 15 dB クランプ。15_q_per_bandcount.py)
     *     の評価で、飽和の歪みが低い Q を不当に落としていた。その表は全バンド +12 で
     *     谷 4.8 dB (5 バンド) の波を作った — 摘みは中心で必ず合うので、**表の良し悪しは
     *     中心の誤差ではなく中心間に出る**
     *   - Q を下げる側の壁は 2 つ: 条件数が三桁に入る (31 バンドの Q=1.2 で 125、
     *     10 バンドの Q=0.4 で 126)、解の最大値が上限 20 dB に寄ってプリセット次第で
     *     エスカレートする (15 バンドの Q=0.65 で DUNU の max|g| 18.7 = 余裕 6%)
     *   - √2 の階段 (旧表) には戻さない。あの並びは飽和した評価の産物で、
     *     密なバンドほど隣との重なりを増やさないと Nyquist 側の潰れが谷になる
     * この規則を消すと、次に測り直した人が旧表に戻してしまう。
     * 谷の上限は EqSolverTest.flatTargetsStayFlatBetweenTheCentres が固定している。
     *
     * 各行の DUNU の値は選定時の実測で、当時の取り込み (中心サンプル) の実現誤差。
     * 取り込みは今は曲線全体フィット ([fitCurve]) なので現行の実現誤差はより小さい
     * (AutoEqImportFitTest が上限を固定)。Q の選定根拠としてはこの表のまま。
     */
    fun defaultQ(bandCount: Int): Double = when (bandCount) {
        31 -> 1.41 // 1/3 oct。平ら+12 のずれ 2.9 dB (16k 超のみ。16k までは 0.6) / DUNU 2.2 dB / 条件数 60
        15 -> 0.7 // 2/3 oct。ずれ 0.36 dB / DUNU 6.8 dB / 条件数 52
        10 -> 0.5 // 1 oct。  ずれ 0.45 dB / DUNU 4.7 dB / 条件数 34
        else -> 0.4 // 2 oct。  ずれ 0.94 dB / DUNU 9.7 dB / 条件数 3.8
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
     * 指定すると合成応答のピークが +17.31 dB になる (Q = defaultQ(10) = 0.5 での実測。
     * tools/eq/05_geq_gain_solve_10band.py の +8.85 は Q=1.41 で回した値。Q が低いほど
     * 裾が重なって高く出る)。
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
        // 詰めは solution.q ではなく、保存される量子化後の Q で回す。エスカレートした Q が
        // 0.01 の刻みに乗らないとき (0.5 * 1.5^2 = 1.125 -> q100 112)、solution.q で詰めると
        // 読み戻し (q100 の応答) が 1 目盛りずれて「-12.0 にしたのに -11.9」が再発する。
        return refineToTargets(bands, targetDb10, q100.toDouble() / EqUnits.Q_SCALE, fs)
    }

    /**
     * [fitCurve] の結果。[offsetDb] は曲線から分離した広帯域オフセット。
     * peaking は DC と Nyquist で必ず 0 dB になるので、広帯域のオフセットはバンドでは
     * 作れない — プリアンプが唯一の置き場 (呼び出し側が preampDb10 に移す)。
     */
    class CurveFit(val bands: List<EqBand>, val offsetDb: Double)

    /**
     * 目標の曲線 (dB) に合成応答が最も近づくバンドを最小二乗で決める。**取り込み専用。**
     *
     * 摘みの操作 ([withGraphicTarget]) の「バンド中心で厳密一致」とは目的が違う —
     * 取り込みの目的は曲線全体の再現で、中心の値だけを合わせると中心の間で曲線から離れる
     * (DUNU Titan S の 127 点で実測 2.2 dB。フィットなら 20 Hz〜20 kHz で 1.5 dB /
     * 10 kHz 以下 0.4 dB。上限は AutoEqImportFitTest が固定)。取り込んだ後の摘みは
     * 「バンド中心での実現値」として実応答から読み直されるので、摘みが曲線に乗る
     * 不変条件はそのまま。
     *
     * - **フィットの範囲は 20 Hz〜最終バンド中心。**バンドの届かない裾 (5〜15 バンドの
     *   16 kHz より上) を目標に入れると、届かない裾を追って届く範囲が歪む
     *   (15 バンドで 10 kHz 以下の誤差が 0.69 → 2.92 dB に悪化する実測)
     * - **オフセット (曲線の平均) を先に分離して、形だけを解く。**オフセットを未知数として
     *   一緒に解くと「全バンド同値」の応答がほぼ平らであること (§7) と共線になり、解が
     *   バンド全部 +9 dB / オフセット -16 dB のような分解へ流れる — 鳴る音は同じでも、
     *   摘みとプリアンプが UI の可動域 (±12 / -30〜0) から出る
     * - 解の上限は solve() と同じ [SOLVED_GAIN_LIMIT_DB]。収まらなければ Q を
     *   [Q_ESCALATION] 倍して解き直し、それでも収まらなければ素朴な値 (中心サンプル) に戻す
     * - 最後に、量子化後の実応答の誤差が上下対称になる位置へオフセットだけを寄せる
     *   (バンドは動かさないので摘みの値には影響しない)
     */
    fun fitCurve(
        curveDb: (Double) -> Double,
        freqs: List<Int>,
        q: Double,
        fs: Int = DEFAULT_FS,
    ): CurveFit {
        if (freqs.isEmpty()) return CurveFit(emptyList(), 0.0)
        val hiHz = minOf(20_000.0, freqs.last().toDouble())
        val atHz = DoubleArray(FIT_GRID_POINTS) { i ->
            exp(ln(20.0) + (ln(hiHz) - ln(20.0)) * i / (FIT_GRID_POINTS - 1.0))
        }
        val target = DoubleArray(atHz.size) { curveDb(atHz[it]) }
        val offset = target.average()
        val shape = DoubleArray(atHz.size) { target[it] - offset }

        var currentQ = q
        repeat(Q_ESCALATION_TRIES) {
            // 保存される量子化後の Q でフィットする (solveBands の詰めと同じ理由 —
            // 解いた Q と保存した Q がずれると、読み戻した応答が 1 目盛りずれる)。
            val q100 = (currentQ * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
            val gains = fitShape(shape, atHz, freqs, q100.toDouble() / EqUnits.Q_SCALE, fs)
            if (gains != null) return quantizedFit(gains, q100, shape, atHz, freqs, offset, fs)
            currentQ *= Q_ESCALATION
        }
        // Q を上げても収まらない目標。solve() と同じく素朴な値 (中心サンプル) に戻す。
        // 暴れた解を渡すより、効きが目標より強いほうがまだ説明できる。
        val q100 = (q * EqUnits.Q_SCALE).toInt().coerceIn(EqBand.Q_RANGE)
        val naive = DoubleArray(freqs.size) { curveDb(freqs[it].toDouble()) - offset }
        return quantizedFit(naive, q100, shape, atHz, freqs, offset, fs)
    }

    /**
     * 形 (オフセット除去済み) への最小二乗フィット。基底は固定のまま、残差は本物の応答で
     * 測り直す (裾がゲインに比例しないのは [solveOnce] と同じ事情)。正規方程式の左辺は
     * 反復を通して不変なので 1 回だけ作る。
     *
     * 解が [SOLVED_GAIN_LIMIT_DB] のクランプに張り付いたままなら null
     * (呼び出し側が Q を上げて解き直す)。
     */
    private fun fitShape(
        shape: DoubleArray,
        atHz: DoubleArray,
        freqs: List<Int>,
        q: Double,
        fs: Int,
    ): DoubleArray? {
        val n = freqs.size
        val rows = atHz.size
        val grid = CentreGrid(atHz, freqs, q, fs)
        val basis = Array(rows) { i ->
            DoubleArray(n) { j -> grid.responseDb(i, j, FIT_REFERENCE_GAIN_DB) / FIT_REFERENCE_GAIN_DB }
        }
        val normal = Array(n) { DoubleArray(n) }
        for (r in 0 until rows) {
            val row = basis[r]
            for (i in 0 until n) {
                val ri = row[i]
                if (ri == 0.0) continue
                val ni = normal[i]
                for (j in i until n) ni[j] += ri * row[j]
            }
        }
        for (i in 0 until n) for (j in 0 until i) normal[i][j] = normal[j][i]

        fun lstsq(residual: DoubleArray): DoubleArray? {
            val rhs = DoubleArray(n)
            for (r in 0 until rows) {
                val w = residual[r]
                if (w == 0.0) continue
                val row = basis[r]
                for (j in 0 until n) rhs[j] += row[j] * w
            }
            return solveLinear(normal, rhs)
        }

        val gains = lstsq(shape) ?: return null
        for (j in 0 until n) {
            gains[j] = gains[j].coerceIn(-SOLVED_GAIN_LIMIT_DB, SOLVED_GAIN_LIMIT_DB)
        }
        for (iteration in 0 until FIT_ITERATIONS) {
            val residual = DoubleArray(rows) { i -> shape[i] - grid.combinedAt(i, gains) }
            val delta = lstsq(residual) ?: break
            for (j in 0 until n) {
                gains[j] = (gains[j] + FIT_DAMPING * delta[j])
                    .coerceIn(-SOLVED_GAIN_LIMIT_DB, SOLVED_GAIN_LIMIT_DB)
            }
        }
        // 張り付いた解は上限を超えたがっている。ちょうど上限ぴったりの解も巻き添えで
        // エスカレートするが、実プリセットの解は一桁 dB (DUNU で 10.4) なので幅に実害は無い。
        return if (gains.all { abs(it) < SOLVED_GAIN_LIMIT_DB }) gains else null
    }

    /**
     * フィット解を 0.1 dB に量子化してバンドに組み、量子化後の実応答の誤差が上下対称に
     * なる位置へオフセットを寄せる。詰め ([refineToTargets]) はしない — フィットには
     * 「この点に厳密に合わせる」目標が無く、摘みは実応答から読み直されるため。
     */
    private fun quantizedFit(
        gainsDb: DoubleArray,
        q100: Int,
        shape: DoubleArray,
        atHz: DoubleArray,
        freqs: List<Int>,
        offsetDb: Double,
        fs: Int,
    ): CurveFit {
        val bands = freqs.mapIndexed { i, hz ->
            EqBand(
                freqHz = hz.coerceIn(EqBand.FREQ_RANGE),
                q100 = q100,
                gainDb10 = Math.round(gainsDb[i] * EqUnits.GAIN_SCALE).toInt()
                    .coerceIn(EqBand.GAIN_RANGE),
            )
        }
        val grid = CentreGrid(atHz, freqs, q100.toDouble() / EqUnits.Q_SCALE, fs)
        val quantized = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        var maxErr = Double.NEGATIVE_INFINITY
        var minErr = Double.POSITIVE_INFINITY
        for (i in atHz.indices) {
            val err = grid.combinedAt(i, quantized) - shape[i]
            if (err > maxErr) maxErr = err
            if (err < minErr) minErr = err
        }
        return CurveFit(bands, offsetDb - (maxErr + minErr) / 2.0)
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
     * 10 バンド全部を素で +12 dB にすると中心では +35.2 dB 鳴る (Q=0.5)。
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
     * ⚠️ 求解が厳密でないとこれが崩れる — 線形モデルで解いていたときは 0.367 dB 動いていた。
     *
     * ### 中心と中心の**間**の起伏を減らす案 (Välimäki & Liski 2017 III-B) を採らない理由
     *
     * 論文は中心の幾何平均を設計点に足して 19x10 の擬似逆で解く。**こちらでは採れない** —
     * 相互作用行列は 10x10 で**階数 10、零空間の次元が 0**。
     * つまり**バンド中心での値を決めた時点で、中心間の形は完全に determined で、
     * 調整の余地が 1 つも残っていない。**19 点版が中心間を良くできるのは
     * **中心を犠牲にするから**で、実測すると中心の誤差が最大 1.24 dB (8 kHz) 出る。
     *
     * **摘みの値 = バンド中心で鳴る音量、という約束のほうが優先される** (それが
     * 「摘みが曲線に乗らない」の再発を防いでいる)。中心間の起伏そのものは設計点を足すのでは
     * なく **[defaultQ] を平ら目標で選ぶことで抑える** (全 +12 の谷が 10 バンドで 0.13 dB。
     * 上限は EqSolverTest.flatTargetsStayFlatBetweenTheCentres)。例外は 31 バンドの 16k-20k の
     * 1 区間だけで、最上バンドが fs=48k の Nyquist で潰れる分 (2.9 dB)。これは Q では消えず、
     * シェルフにしても改善しない (10 バンド Q=1.0 時代の実測で -2.98 -> -3.67 dB と悪化)。
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
        // 読み取りは保存されている Q で。いま実際に鳴っている応答は保存中のバンドが決める。
        val storedQ = bands[index].q100.toDouble() / EqUnits.Q_SCALE
        // ドラッグ 1 コマごとに走るので、読み取りも速い経路で。値は graphicTargetsDb10 と一致する
        // (EqSolverTest の theFastCentreGridAgreesWithTheReferenceFormula が突き合わせている)。
        val grid = CentreGrid(freqs, storedQ, fs)
        val gains = DoubleArray(bands.size) { bands[it].gainDb10.toDouble() / EqUnits.GAIN_SCALE }
        val targets = DoubleArray(bands.size) {
            Math.round(grid.combinedAt(it, gains) * EqUnits.GAIN_SCALE).toDouble() / EqUnits.GAIN_SCALE
        }
        targets[index] = targetDb10.toDouble() / EqUnits.GAIN_SCALE
        // 解く種は保存されている Q ではなく、毎回既定 Q から取り直す。保存値を種にすると、
        // スパイク状の目標で一度エスカレートした Q が平らに戻しても残り続け (下げる経路が無い)、
        // その後の全バンド +12 が Q 4.5 の櫛で鳴る (2026-08-12 の実機で発生)。
        // エスカレーションは solve() がその目標のためだけに毎回やり直す。バンド数を変えたときに
        // reband() が既定 Q へ戻るのと同じ規則 (EqSolverTest.escalatedQAnnealsBack... が見張る)。
        return solveBands(targets, freqs, defaultQ(bands.size), fs)
    }

    /**
     * 評価点 × バンドの応答を、ゲインだけ変えて何度も評価するための前計算。
     * 求解 (solveOnce) は評価点 = バンド中心の正方で、取り込みのフィット (fitShape) は
     * 評価点 = 密な対数グリッドの長方形で使う。
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
    internal class CentreGrid(atHz: DoubleArray, freqs: List<Int>, q: Double, val fs: Int) {
        constructor(freqs: List<Int>, q: Double, fs: Int) :
            this(DoubleArray(freqs.size) { freqs[it].toDouble() }, freqs, q, fs)

        val n = freqs.size
        private val rows = atHz.size
        private val p = DoubleArray(rows * n)
        private val u = DoubleArray(rows * n)
        private val r = DoubleArray(rows * n)
        private val v = DoubleArray(rows * n)
        private val alpha = DoubleArray(n)

        init {
            // 評価点 (i) 側の三角関数はバンド (j) に依存しない。j の内側で回すと rows×n 回
            // 計算することになるので先に出しておく。
            val cosW = DoubleArray(rows)
            val sinW = DoubleArray(rows)
            val cos2W = DoubleArray(rows)
            val sin2W = DoubleArray(rows)
            for (i in 0 until rows) {
                val w = 2.0 * Math.PI * atHz[i] / fs
                cosW[i] = cos(w)
                sinW[i] = sin(w)
                cos2W[i] = cos(2 * w)
                sin2W[i] = sin(2 * w)
            }
            for (j in 0 until n) {
                val w0 = 2.0 * Math.PI * freqs[j] / fs
                alpha[j] = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                for (i in 0 until rows) {
                    val k = i * n + j
                    p[k] = 1 - 2 * cosW0 * cosW[i] + cos2W[i]
                    u[k] = 1 - cos2W[i]
                    r[k] = 2 * cosW0 * sinW[i] - sin2W[i]
                    v[k] = sin2W[i]
                }
            }
        }

        /** バンド [j] を [gainDb] にしたときの、評価点 [i] での応答 (dB)。 */
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

        /** バンド全部を重ねた、評価点 [i] での応答 (dB)。 */
        fun combinedAt(i: Int, gains: DoubleArray): Double {
            var sum = 0.0
            for (j in 0 until n) sum += responseDb(i, j, gains[j])
            return sum
        }
    }

    private fun solveOnce(targetDb: DoubleArray, freqs: List<Int>, q: Double, fs: Int): DoubleArray {
        val n = freqs.size
        val grid = CentreGrid(freqs, q, fs)
        val gains = targetDb.copyOf()
        repeat(SOLVE_ITERATIONS) {
            // ⚠️ 実現値は「M × gains」ではなく **本物の応答**を評価して作る。
            // RBJ peaking の裾はゲインに比例しない — 2 kHz のバンドの 1 kHz での応答は
            // 1 dB で 0.3048 dB だが 12 dB では 3.9307 dB で、比例なら 3.6580 dB。
            // M を応答として使うと、この差がそのまま解の誤差になる
            // (10 バンド全部 +12 dB の目標でバンド中心が 0.32 dB ずれる)。さらに
            // reband() が「真の応答を目標に読んで解き直す」写像なので、線形モデルで解くと
            // 往復のたびに曲線が育って発散する (+12 dB で 20 往復すると +27.9 dB)。
            val residual = DoubleArray(n) { i -> targetDb[i] - grid.combinedAt(i, gains) }
            if (residual.maxOf { abs(it) } < SOLVE_TOLERANCE_DB) return gains
            // ⚠️ ヤコビアンは**毎回、現在の動作点の中心差分**で作り直す。「1 dB の割線を
            // 初回に 1 回だけ」に戻してはいけない — Q が低い (裾の重なりが強い) ときに
            // 正負の混じった目標で反復の縮小率が 0.995 まで落ち、16 回では残差 1.5 dB の
            // まま返る (10 バンド Q=0.5、[-3.5,-3.5,-3.5,5.5...] で実測)。その誤差は量子化後の
            // 詰め (refineToTargets) では回収できず「-3.5 にしたのに -3.4」になる。
            // 対角は peaking の定義から厳密に 1 のまま (中心での応答 = 自分のゲイン)。
            val m = Array(n) { i ->
                DoubleArray(n) { j ->
                    grid.responseDb(i, j, gains[j] + 0.5) - grid.responseDb(i, j, gains[j] - 0.5)
                }
            }
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
     * 旧版の自動プリアンプ = −(合成応答のピーク)。**呼び出してよいのは
     * [EqSettings.fromJson] の移行だけ** — 「自動」を廃してプリアンプはユーザが決めた 1 つの値に
     * なったので、常用経路にこの式は無い。ここに残っているのは、`pa` を持っていた版が
     * 焼いていた値を移行で再現するため。
     *
     * 個々のゲインの合計ではない — 10 バンド全部 +6 dB のとき合計は 60 dB だが
     * 実際のピークは +17.31 dB (Q = defaultQ(10) = 0.5。
     * tools/eq/05_geq_gain_solve_10band.py の +8.85 は Q=1.41 で回した値)。
     * AutoEQ も同じ考え方 (PEQ.max_gain)。
     *
     * **⚠️ これはクリップの保証ではない。**周波数応答の最大 max|H| は出力ピークの上界ではなく、
     * 真の最悪はインパルス応答の L1 ノルム Σ|h[n]|。カットだけの曲線 (−6 dB Q=2 単発) でも
     * 0 dBFS の 200 Hz 矩形波で出力 +1.12 dBFS (余地 +3.67 dB) が実測されている
     * (合成信号での測定。実際の音楽は通していない)。「ピークが 0 以下なら 0 を返す」のも
     * 安全だからではなく、旧版がそう書いていたから。
     */
    fun autoPreampDb10(bands: List<EqBand>, fs: Int = DEFAULT_FS): Int {
        if (bands.isEmpty()) return 0
        var peak = 0.0
        // 評価点は [EqCurveGrid]。**格子をここで作り直さない** — 同じ 20 Hz〜20 kHz の
        // 対数等間隔 401 点を、聴感重みと「高精度」へ送る曲線も引いている。
        for (hz in EqCurveGrid.HZ) {
            val db = combinedResponseDb(bands, hz, fs)
            if (db > peak) peak = db
        }
        if (peak <= 0.0) return 0
        return -(peak * EqUnits.GAIN_SCALE).toInt()
    }
}
