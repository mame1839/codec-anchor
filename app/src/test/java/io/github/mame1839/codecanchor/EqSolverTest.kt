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
    // defaultQ(10) (= 1.0) とは別物。参照値と対で決まっているので defaultQ に置き換えない。
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
    @Test
    fun solvedGainsHitTheTarget() {
        val freqs = EqSolver.centerFrequencies(10)
        val target = DoubleArray(freqs.size) { 6.0 }
        val q = EqSolver.defaultQ(10)
        val bands = EqSolver.solveBands(target, freqs, q)
        freqs.forEachIndexed { i, hz ->
            val realized = EqSolver.combinedResponseDb(bands, hz.toDouble())
            assertEquals("バンド $hz Hz", 6.0, realized, 0.2)
        }
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
