package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqSolverTest {

    // tools/eq/05_geq_gain_solve_10band.py の参照値。
    // 素朴なカスケードは 10 バンド全部 +6 dB でピーク +8.85 dB になる。
    //
    // ここの q100 = 141 は 05_*.py の条件をそのまま再現するための値で、
    // defaultQ(10) (= 0.5) とは別物。参照値と対で決まっているので defaultQ に置き換えない。
    // 製品の Q=0.5 での同じピークは +17.31 dB (EqCurveTest が固定している)。
    // 8.85 を製品の数字として読まないこと。
    @Test
    fun naiveCascadeOvershoots() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 60) }
        var peak = 0.0
        for (i in 0..400) {
            val hz = 20.0 * Math.pow(1000.0, i / 400.0)
            peak = maxOf(peak, EqSolver.combinedResponseDb(bands, hz))
        }
        assertTrue("期待は約 8.85 dB、実際は $peak", peak in 8.0..9.5)
    }

    // 解いたらバンド中心で目標に一致すること。
    //
    // 許容の 0.05 dB は保存の刻み (0.1 dB) の半分。solveBands が量子化の後に
    // 整数で詰めているので、ここまで詰まる。緩めると下の linearised... の見張りが効かなくなる。
    @Test
    fun solvedGainsHitTheTarget() {
        val freqs = EqSolver.centerFrequencies(10)
        val target = DoubleArray(freqs.size) { 6.0 }
        val q = EqSolver.defaultQ(10)
        val bands = EqSolver.solveBands(target, freqs, q)
        freqs.forEachIndexed { i, hz ->
            val realized = EqSolver.combinedResponseDb(bands, hz.toDouble())
            assertEquals("バンド $hz Hz", 6.0, realized, 0.05)
        }
    }

    /**
     * **求解は実応答を評価すること。相互作用行列を応答として使わないこと。**
     *
     * M[i][j] は「バンド j に **1 dB** 入れたときの中心 i での応答」で、
     * RBJ peaking の裾はゲインに比例しない。2 kHz のバンドの 1 kHz での応答は
     * 1 dB なら 0.3048 dB、12 dB なら 3.9307 dB (比例なら 3.6580 dB)。
     * M を応答として使うと 10 バンド +12 dB の目標で中心が **0.32 dB** ずれる。
     *
     * ゲインが大きいほど効くので、+6 dB では 0.04 dB しか出ず**気づけない**。
     * この試験が +12 dB を使っているのはそのため。下げないこと。
     */
    @Test
    fun solvedGainsHitTheTargetAtLargeGainsToo() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val target = DoubleArray(n) { 12.0 }
            val bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(n))
            freqs.forEachIndexed { i, hz ->
                val realized = EqSolver.combinedResponseDb(bands, hz.toDouble())
                assertEquals("n=$n バンド $hz Hz", 12.0, realized, 0.05)
            }
        }
    }

    /**
     * **ユーザの訴えそのもの: 全バンドを同じ値にしたら曲線が平らになること。**
     *
     * 2026-08-11 の報告「全部 12 dB にしたら波々になってる」。原因は干渉補正が
     * スライダー操作で走っていなかったこと。素のカスケードだと中央部 (125 Hz〜8 kHz) に
     * **4.9 dB** (Q=1.0) のうねりが出る。補正 + 平ら目標で選んだ Q なら 0.2 dB 以下。
     * バンド数ごとの上限は [flatTargetsStayFlatBetweenTheCentres] が締めている。
     *
     * 全域ではなく 125 Hz〜8 kHz を見るのは、両端のバンド (32 Hz / 16 kHz) の外側が
     * 必ず垂れるため。そこは補正の対象ではない (目標点が片側にしか無い)。
     */
    @Test
    fun allBandsAtTheSameTargetGiveAFlatCurve() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, freqs, EqSolver.defaultQ(10))
        val ripple = rippleDb(bands, 125.0, 8_000.0)
        assertTrue("補正後のうねりが $ripple dB (期待は 1.5 dB 以下)", ripple <= 1.5)

        // 素のカスケード (補正なし) だと同じ条件で 3 dB を超える。
        // この行が落ちたら「補正が要らなくなった」のではなく、比較の前提が崩れている。
        val naive = freqs.map { EqBand(freqHz = it, q100 = 100, gainDb10 = 120) }
        assertTrue("補正なしのうねりが ${rippleDb(naive, 125.0, 8_000.0)} dB しかない", rippleDb(naive, 125.0, 8_000.0) > 3.0)
    }

    /**
     * **全バンドを同じ値にしたら、中心と中心の間も同じ値に留まること。**バンド数ごとに固定する。
     *
     * 2026-08-12 の報告「32 から 16K までどの位置でとってもプラス 12 じゃないとおかしくない?」。
     * 摘み (バンド中心) は解けば必ず合うので、見張る対象は**中心間の谷**のほう。
     * 谷の深さは既定 Q でほぼ決まる — Q を上げるほど深くなる (V&R 2016 Fig.8)。
     * この上限は既定 Q の実測 + 余裕で、**Q の表を上げ直すとここが落ちる。**
     *
     * 31 バンドだけ上限が緩いのは、最上バンド (20 kHz) が fs=48k の Nyquist で潰れて
     * 16k–20k の 1 区間に谷が残るため (実測 2.9 dB)。**可聴域 (16 kHz まで) は別に締める。**
     */
    @Test
    fun flatTargetsStayFlatBetweenTheCentres() {
        // 実測 (solveBands 込み): 5 -> 0.94 / 10 -> 0.45 / 15 -> 0.36 / 31 -> 2.88。
        // 10/15 の最悪点は谷ではなく低域端の山 (41 Hz / 30 Hz の +0.4 dB)。
        val limits = mapOf(5 to 1.2, 10 to 0.6, 15 to 0.6, 31 to 3.3)
        for ((n, limit) in limits) {
            val freqs = EqSolver.centerFrequencies(n)
            val bands = EqSolver.solveBands(DoubleArray(n) { 12.0 }, freqs, EqSolver.defaultQ(n))
            val whole = maxDeviationDb(bands, freqs.first().toDouble(), freqs.last().toDouble(), 12.0)
            assertTrue("n=$n の中心間のずれが $whole dB (上限 $limit)", whole <= limit)
        }
        // 31 バンドの可聴域。ユーザの訴えの範囲 (〜16 kHz) では 1 dB 未満であること。
        val freqs = EqSolver.centerFrequencies(31)
        val bands = EqSolver.solveBands(DoubleArray(31) { 12.0 }, freqs, EqSolver.defaultQ(31))
        val audible = maxDeviationDb(bands, 20.0, 16_000.0, 12.0)
        assertTrue("31 バンドの 16 kHz までのずれが $audible dB (上限 1.0)", audible <= 1.0)
    }

    private fun maxDeviationDb(bands: List<EqBand>, loHz: Double, hiHz: Double, targetDb: Double): Double {
        var worst = 0.0
        for (i in 0..2000) {
            val hz = loHz * Math.pow(hiHz / loHz, i / 2000.0)
            val d = abs(EqSolver.combinedResponseDb(bands, hz) - targetDb)
            if (d > worst) worst = d
        }
        return worst
    }

    /**
     * **エスカレートした Q は、目標が穏やかに戻ったら既定へ戻ること。**
     *
     * [EqSolver.withGraphicTarget] が解く種を保存済みの q100 から取ると、スパイクで一度
     * 上がった Q が**戻す操作をしても残り続ける** (全 +12 が細い Q の櫛で鳴る —
     * 2026-08-12 の実機のスクリーンショットの再現条件)。種は毎回 [EqSolver.defaultQ] から
     * 取り直し、エスカレーションは solve() がその目標のためだけに毎回やり直す。
     */
    @Test
    fun escalatedQAnnealsBackWhenTheTargetCalmsDown() {
        val n = 31
        val defaultQ100 = (EqSolver.defaultQ(n) * 100).toInt()
        var bands = EqSolver.solveBands(DoubleArray(n) { 0.0 }, EqSolver.centerFrequencies(n), EqSolver.defaultQ(n))
        bands = EqSolver.withGraphicTarget(bands, 15, 120)
        // 前提: スパイク 1 本は既定 Q では 20 dB に収まらず、エスカレーションが要る。
        assertTrue("前提が崩れた: スパイクで Q が上がっていない", bands.first().q100 > defaultQ100)
        assertEquals(120, EqSolver.graphicTargetsDb10(bands)[15])

        bands = EqSolver.withGraphicTarget(bands, 15, 0)
        assertEquals("平らに戻したのに Q が残っている", defaultQ100, bands.first().q100)
        assertTrue(EqSolver.graphicTargetsDb10(bands).all { it == 0 })
    }

    private fun rippleDb(bands: List<EqBand>, loHz: Double, hiHz: Double): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0..800) {
            val hz = loHz * Math.pow(hiHz / loHz, i / 800.0)
            val db = EqSolver.combinedResponseDb(bands, hz)
            lo = minOf(lo, db)
            hi = maxOf(hi, db)
        }
        return hi - lo
    }

    /**
     * **摘みの値は保存していない。バンドのゲインから復元する。**
     * 設定したとおりの値が読み戻せること — 読み戻せないと「+12.0 にしたのに +11.9 と出る」。
     */
    @Test
    fun graphicTargetsRoundTripThroughTheStoredGains() {
        for (n in listOf(5, 10, 15, 31)) {
            var bands = EqSolver.solveBands(DoubleArray(n) { 0.0 }, EqSolver.centerFrequencies(n), EqSolver.defaultQ(n))
            for (value in listOf(120, -120, 55, -35, 0)) {
                for (index in bands.indices) {
                    bands = EqSolver.withGraphicTarget(bands, index, value)
                    val read = EqSolver.graphicTargetsDb10(bands)[index]
                    assertEquals("n=$n band=$index に $value を入れた", value, read)
                }
            }
        }
    }

    /**
     * **ドラッグ 1 コマ分の予算。**
     *
     * 摘みを動かしている間、絵は毎コマ [EqSolver.withGraphicTarget] を通る
     * (`EqCurve.withPreview`)。画面は 144 Hz = 1 コマ 6.9 ms で、曲線の描画と
     * 文字の測定が既にその大半を使っている (PLAN.md 2026-08-07)。
     * 31 バンドが最悪ケース。
     */
    @Test
    fun solvingIsFastEnoughForOneDragFrame() {
        for (n in listOf(10, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val bands = EqSolver.solveBands(DoubleArray(n) { 4.0 }, freqs, EqSolver.defaultQ(n))
            repeat(20) { EqSolver.withGraphicTarget(bands, n / 2, 60) } // JIT を温める
            val started = System.nanoTime()
            val rounds = 50
            repeat(rounds) { EqSolver.withGraphicTarget(bands, n / 2, 60) }
            val perCallMs = (System.nanoTime() - started) / 1e6 / rounds
            println("withGraphicTarget n=$n: %.3f ms/回".format(perCallMs))
            assertTrue("n=$n で 1 回 $perCallMs ms かかっている", perCallMs < 2.0)
        }
    }

    /** 1 本だけ動かしたとき、他のバンドの表示値が動かないこと。動くと勝手に音が変わる。 */
    @Test
    fun movingOneBandLeavesTheOthersWhereTheyWere() {
        val freqs = EqSolver.centerFrequencies(10)
        var bands = EqSolver.solveBands(doubleArrayOf(3.0, -2.0, 0.0, 5.0, -6.0, 1.0, 4.0, -1.0, 2.0, 0.0), freqs, EqSolver.defaultQ(10))
        val before = EqSolver.graphicTargetsDb10(bands)
        bands = EqSolver.withGraphicTarget(bands, 4, 120)
        val after = EqSolver.graphicTargetsDb10(bands)
        assertEquals("触ったバンド", 120, after[4])
        after.indices.filter { it != 4 }.forEach {
            assertEquals("バンド ${freqs[it]} Hz が動いた", before[it], after[it])
        }
    }

    /**
     * 操作を重ねても値が溜まらないこと。
     *
     * 目標値を保存せずバンドのゲインから復元する設計なので、往復のたびに
     * 0.1 dB の量子化を通る。ここが溜まると、触っていないバンドが少しずつずれていく。
     */
    @Test
    fun repeatedEditsDoNotDrift() {
        val freqs = EqSolver.centerFrequencies(10)
        var bands = EqSolver.solveBands(DoubleArray(10) { 0.0 }, freqs, EqSolver.defaultQ(10))
        val rng = java.util.Random(7)
        repeat(400) {
            val index = rng.nextInt(10)
            val value = (rng.nextInt(49) - 24) * 5
            bands = EqSolver.withGraphicTarget(bands, index, value)
            assertEquals("直後に読み戻せない", value, EqSolver.graphicTargetsDb10(bands)[index])
        }
        // 同じ操作を繰り返しても状態が動かないこと (冪等)。
        val settled = EqSolver.withGraphicTarget(bands, 3, 60)
        assertEquals(settled, EqSolver.withGraphicTarget(settled, 3, 60))
    }

    /**
     * **同じ応答の式が 2 箇所にある。**求解の内側は前計算した係数から組む速い経路を使い、
     * 画面と摘みの表示は [EqSolver.peakingResponseDb] を使う。**両者がずれると、
     * 摘みの値と曲線が食い違う** (このプロジェクトで 2 回出ている「点が曲線に乗らない」)。
     *
     * git は片方だけの変更を衝突と報告しないので、ここで一致を見張る。
     */
    @Test
    fun theFastCentreGridAgreesWithTheReferenceFormula() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            for (q in listOf(0.4, 0.5, 0.7, 1.0, 1.41, 2.0, 3.0)) {
                val grid = EqSolver.CentreGrid(freqs, q, EqSolver.DEFAULT_FS)
                for (i in freqs.indices) {
                    for (j in freqs.indices) {
                        for (gain in listOf(0.0, 1.0, -1.0, 6.0, -6.0, 12.0, -12.0, 24.0, -24.0)) {
                            val fast = grid.responseDb(i, j, gain)
                            val slow = EqSolver.peakingResponseDb(
                                freqs[i].toDouble(), freqs[j].toDouble(), q, gain, EqSolver.DEFAULT_FS,
                            )
                            assertEquals("n=$n q=$q i=$i j=$j gain=$gain", slow, fast, 1e-9)
                        }
                    }
                }
            }
        }
    }

    /**
     * **グラフィックとパラメトリックを往復しても曲線が育たないこと。**
     *
     * `reband()` は「いまの合成応答をバンド中心で読む → それを目標に解き直す」写像で、
     * `toGraphic()` が同じバンド数で `reband` を呼ぶので、往復 1 回がこの写像 1 回になる。
     * 求解が実応答ではなく線形モデルを解いていたときは目標より上に外し続けるので、
     * **往復のたびに曲線が育った** (+12 dB を 20 往復で +27.9 dB、40 往復で上限に張り付き)。
     * 実応答で解けば固定点になる。
     */
    @Test
    fun rebandingRepeatedlyDoesNotGrowTheCurve() {
        val freqs = EqSolver.centerFrequencies(10)
        val q = EqSolver.defaultQ(10)
        var bands = EqSolver.solveBands(DoubleArray(10) { 12.0 }, freqs, q)
        val first = EqSolver.graphicTargetsDb10(bands)
        repeat(40) {
            val target = DoubleArray(10) { i ->
                EqSolver.combinedResponseDb(bands, freqs[i].toDouble())
            }
            bands = EqSolver.solveBands(target, freqs, q)
        }
        val after = EqSolver.graphicTargetsDb10(bands)
        first.indices.forEach { i ->
            assertEquals("バンド ${freqs[i]} Hz が 40 往復で動いた", first[i], after[i])
        }
    }

    /** パラメトリックの 1 本きりの並びでは、目標がそのままゲインになる (干渉する相手が無い)。 */
    @Test
    fun aSingleBandTakesTheTargetDirectly() {
        val one = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 0))
        assertEquals(75, EqSolver.withGraphicTarget(one, 0, 75).first().gainDb10)
        assertEquals(one, EqSolver.withGraphicTarget(one, 5, 75))
    }

    // 病的な目標 (交互 ±6 dB) で解が暴れたら、Q を上げるか素朴な値に戻すこと。
    // tools/eq/06_geq_gain_solve_31band.py では Q=1.9 で 19.73 dB まで暴れた。
    @Test
    fun wildSolutionsAreClamped() {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 6.0 else -6.0 }
        val solved = EqSolver.solve(target, freqs, EqSolver.defaultQ(31)).gainsDb
        assertTrue("解が暴れている: ${solved.maxOf { abs(it) }}", solved.all { abs(it) <= 20.0 })
    }

    // Q を上げて解き直したときは、組んだバンドの Q も上がった側でなければならない。
    // 元の Q でバンドを作ると、解いた応答と実際に鳴る応答が食い違う。
    @Test
    fun solvedBandsCarryTheQThatWasActuallyUsed() {
        val freqs = EqSolver.centerFrequencies(31)
        val target = DoubleArray(freqs.size) { if (it % 2 == 0) 6.0 else -6.0 }
        val baseQ = EqSolver.defaultQ(31)
        val solution = EqSolver.solve(target, freqs, baseQ)
        assertTrue("この目標は Q を上げないと収まらないはず", solution.q > baseQ)

        val bands = EqSolver.solveBands(target, freqs, baseQ)
        assertEquals((solution.q * 100).toInt(), bands.first().q100)
    }

    // プリアンプは合成応答のピークから。個々のゲインの合計ではない。
    // q100 = 141 は上と同じく 05_*.py の条件の再現 (製品の Q=0.5 ならピークは +17.31 dB)。
    @Test
    fun autoPreampUsesCombinedPeakNotSum() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 60) }
        val preamp = EqSolver.autoPreampDb10(bands)
        // 合計なら -600 (= -60.0 dB)。ピークなら -88 前後 (= -8.8 dB)。
        assertTrue("期待は -80 〜 -95、実際は $preamp", preamp in -95..-80)
    }

    @Test
    fun flatEqNeedsNoHeadroom() {
        val freqs = EqSolver.centerFrequencies(10)
        val bands = freqs.map { EqBand(freqHz = it, q100 = 141, gainDb10 = 0) }
        assertEquals(0, EqSolver.autoPreampDb10(bands))
    }

    // 本数・昇順・重複なしを見る。重複があるとバンドが重なって相互作用行列が特異になり、
    // solveLinear が null を返して求解が黙って打ち切られる。
    @Test
    fun bandCountsProduceDistinctAscendingFrequencies() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            assertEquals(n, freqs.size)
            assertEquals(freqs.sorted(), freqs)
            assertEquals(n, freqs.distinct().size)
            assertTrue(freqs.first() >= 20 && freqs.last() <= 20_000)
        }
    }

    // 4 つとも実測済み (tools/eq/15_*.py)。どのバンド数でも解が上限内に収まること。
    @Test
    fun allBandCountsSolveWithinLimits() {
        for (n in listOf(5, 10, 15, 31)) {
            val freqs = EqSolver.centerFrequencies(n)
            val target = DoubleArray(n) { 4.0 }
            val solved = EqSolver.solve(target, freqs, EqSolver.defaultQ(n)).gainsDb
            assertTrue(solved.all { it.isFinite() && abs(it) <= 20.0 })
        }
    }

    // シェルフは Q をスロープとして使うので、Q > 1 とゲインの組み合わせで
    // 根号の中が負になる。NaN を返すと合成応答とプリアンプが丸ごと壊れる。
    @Test
    fun shelfStaysFiniteOutsideItsDomain() {
        val db = EqSolver.shelfResponseDb(1_000.0, 100.0, 4.0, 40.0, EqSolver.DEFAULT_FS, false)
        assertTrue("シェルフが NaN を返した", db.isFinite())
        val bands = listOf(EqBand(freqHz = 100, q100 = 400, gainDb10 = 400, type = 1))
        assertTrue(EqSolver.autoPreampDb10(bands) <= 0)
    }
}
