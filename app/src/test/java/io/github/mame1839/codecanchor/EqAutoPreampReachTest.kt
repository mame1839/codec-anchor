package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqCurveGrid
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.core.EqUnits
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EqAutoPreampReachTest {

    private fun q100Of(bandCount: Int): Int =
        (EqSolver.defaultQ(bandCount) * EqUnits.Q_SCALE).toInt()

    private fun gridPeakDb(bands: List<EqBand>): Double {
        var peak = Double.NEGATIVE_INFINITY
        for (hz in EqCurveGrid.HZ) {
            val db = EqSolver.combinedResponseDb(bands, hz)
            if (db > peak) peak = db
        }
        return peak
    }

    private fun centreDb(bands: List<EqBand>): DoubleArray =
        DoubleArray(bands.size) { EqSolver.combinedResponseDb(bands, bands[it].freqHz.toDouble()) }

    private fun maxAbsGainDb(bands: List<EqBand>): Double =
        bands.maxOf { abs(it.gainDb10.toDouble() / EqUnits.GAIN_SCALE) }

    private fun solveKnobs(bandCount: Int, knobsDb10: IntArray): List<EqBand> {
        val freqs = EqSolver.centerFrequencies(bandCount)
        val target = DoubleArray(freqs.size) { knobsDb10[it].toDouble() / EqUnits.GAIN_SCALE }
        return EqSolver.solveBands(target, freqs, EqSolver.defaultQ(bandCount))
    }

    private fun report(label: String, bands: List<EqBand>): Int {
        val preamp = EqSolver.autoPreampDb10(bands)
        val peak = gridPeakDb(bands)
        println(
            "%-52s peak=%8.3f dB  autoPreamp=%6d  maxBandGain=%7.3f dB  clamped=%s"
                .format(label, peak, preamp, maxAbsGainDb(bands), preamp < EqSettings.PREAMP_RANGE.first),
        )
        return preamp
    }

    @Test
    fun theKdocFigureForTenBandsAtSixDb() {
        println("=== 1. KDoc: バンドのゲインを全部 +6 dB (摘みではない) ===")
        val values = EqSettings.BAND_COUNTS.associateWith { n ->
            val bands = EqSolver.centerFrequencies(n)
                .map { EqBand(freqHz = it, q100 = q100Of(n), gainDb10 = 60) }
            report("n=$n  raw band gain +6.0 dB  Q=${EqSolver.defaultQ(n)}", bands)
        }
        require(values.getValue(10) in -174..-172) { "KDoc の -173 から動いた: ${values.getValue(10)}" }

        println("=== 1b. 同じ +6 dB を『摘み (目標)』として解いた場合 ===")
        EqSettings.BAND_COUNTS.forEach { n ->
            val bands = solveKnobs(n, IntArray(n) { 60 })
            report("n=$n  knob +6.0 dB (solveBands)", bands)
        }
    }

    @Test
    fun graphicKnobsAtFullScale() {
        println("=== 2. グラフィック: 摘みを振り切った形 (摘みの可動域は ±12.0 dB) ===")
        var worst = 0
        var worstLabel = ""
        EqSettings.BAND_COUNTS.forEach { n ->
            val patterns = linkedMapOf(
                "all +12" to IntArray(n) { 120 },
                "all -12" to IntArray(n) { -120 },
                "alt +12/-12" to IntArray(n) { if (it % 2 == 0) 120 else -120 },
                "alt -12/+12" to IntArray(n) { if (it % 2 == 0) -120 else 120 },
                "+12 pair, rest -12" to IntArray(n) { if (it == n / 2 || it == n / 2 + 1) 120 else -120 },
                "+12 pair, rest 0" to IntArray(n) { if (it == n / 2 || it == n / 2 + 1) 120 else 0 },
                "-12 +12 +12 -12 (rest 0)" to IntArray(n) {
                    when (it) {
                        n / 2, n / 2 + 1 -> 120
                        n / 2 - 1, n / 2 + 2 -> -120
                        else -> 0
                    }
                },
                "single +12 at top" to IntArray(n) { if (it == n - 1) 120 else 0 },
                "single +12 at bottom" to IntArray(n) { if (it == 0) 120 else 0 },
            )
            patterns.forEach { (name, knobs) ->
                val bands = solveKnobs(n, knobs)
                val centres = centreDb(bands)
                val miss = knobs.indices.maxOf {
                    abs(centres[it] - knobs[it].toDouble() / EqUnits.GAIN_SCALE)
                }
                val preamp = report("n=%2d  %-26s (中心の誤差 %.3f dB)".format(n, name, miss), bands)
                if (preamp < worst) {
                    worst = preamp
                    worstLabel = "n=$n $name"
                }
            }
        }
        println("--- 型どおりの形での最悪: $worst  ($worstLabel) ---")
        require(worst in -150..-140) { "型どおりの形での最悪が -145 から動いた: $worst ($worstLabel)" }
    }

    @Test
    fun graphicKnobsRandomSearch() {
        println("=== 3. グラフィック: 摘みの空間の探索 (各バンド数とも同じ回数) ===")
        val draws = 1_500
        EqSettings.BAND_COUNTS.forEach { n ->
            val rng = Random(20260819L + n)
            var worst = 0
            var worstKnobs = IntArray(n)
            var worstIsFallback = false
            var fallbacks = 0
            repeat(draws) { k ->
                val knobs = if (k < draws / 2) {
                    IntArray(n) { intArrayOf(-120, 0, 120)[rng.nextInt(3)] }
                } else {
                    IntArray(n) { rng.nextInt(-120, 121) }
                }
                val bands = solveKnobs(n, knobs)
                val centres = centreDb(bands)
                val fell = knobs.indices.any {
                    abs(centres[it] - knobs[it].toDouble() / EqUnits.GAIN_SCALE) > 0.15
                }
                if (fell) fallbacks++
                val preamp = EqSolver.autoPreampDb10(bands)
                if (preamp < worst) {
                    worst = preamp
                    worstKnobs = knobs
                    worstIsFallback = fell
                }
            }
            println(
                "n=%2d  draws=%d  worst autoPreamp=%6d  素朴な値への転落=%d  worstIsFallback=%s"
                    .format(n, draws, worst, fallbacks, worstIsFallback),
            )
            println("      worst knobs = ${worstKnobs.joinToString(",")}")
            require(worst > -400) { "n=$n の無作為探索でクランプに届いた: $worst" }
        }

        var worst5 = 0
        var worst5Knobs = IntArray(5)
        val levels = intArrayOf(-120, 0, 120)
        for (a in levels) for (b in levels) for (c in levels) for (d in levels) for (e in levels) {
            val knobs = intArrayOf(a, b, c, d, e)
            val preamp = EqSolver.autoPreampDb10(solveKnobs(5, knobs))
            if (preamp < worst5) {
                worst5 = preamp
                worst5Knobs = knobs
            }
        }
        println("n= 5  3 値の総当たり 243 通り  worst autoPreamp=$worst5  knobs=${worst5Knobs.joinToString(",")}")
        require(worst5 > -400) { "5 バンドの総当たりでクランプに届いた: $worst5" }
    }

    @Test
    fun parametricStacking() {
        println("=== 4. パラメトリック: 同じ周波数へ重ねる ===")
        listOf(141 to "Q=1.41 (バンド追加時の既定)", 1_000 to "Q=10.00 (UI の上限)", 10 to "Q=0.10 (UI の下限)")
            .forEach { (q100, label) ->
                var firstClamped = -1
                for (k in 1..EqSettings.MAX_BANDS) {
                    val bands = List(k) { EqBand(freqHz = 1_000, q100 = q100, gainDb10 = 120) }
                    val preamp = EqSolver.autoPreampDb10(bands)
                    if (preamp < EqSettings.PREAMP_RANGE.first && firstClamped < 0) firstClamped = k
                    if (k <= 5 || k == EqSettings.MAX_BANDS) {
                        report("1 kHz %s  +12.0 dB x %2d".format(label, k), bands)
                    }
                }
                println("--- $label: クランプ (-400 未満) に入る最小の本数 = $firstClamped ---")
                require(firstClamped == 4) { "$label で 4 本から動いた: $firstClamped" }
            }

        println("=== 4b. JSON に書けば入る形 (EqBand.GAIN_RANGE = ±40.0 / Q_RANGE = 0.10〜40.00) ===")
        val jsonWorst = List(EqSettings.MAX_BANDS) { EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 400) }
        report("1 kHz Q=1.41  +40.0 dB x 31 (手書きプリセット)", jsonWorst)

        println("=== 4c. 現実的な形 (10 本を 1 kHz に重ねる / 3 本を別々の帯域へ) ===")
        report("1 kHz Q=1.41  +12.0 dB x 10", List(10) { EqBand(1_000, 141, 120) })
        report(
            "100/1k/10k Q=1.41  +12.0 dB x 3 (足した直後の並び)",
            listOf(EqBand(100, 141, 120), EqBand(1_000, 141, 120), EqBand(10_000, 141, 120)),
        )
    }

    @Test
    fun parametricSeparation() {
        println("=== 6. パラメトリック: +12 dB の 2 本を離していく (Q=1.41) ===")
        listOf(1_000, 1_100, 1_250, 1_400, 1_600, 2_000, 2_800, 4_000, 8_000).forEach { hz ->
            val bands = listOf(EqBand(1_000, 141, 120), EqBand(hz, 141, 120))
            report("1 kHz + %5d Hz  (%.2f oct 離れ)".format(hz, kotlin.math.ln(hz / 1000.0) / kotlin.math.ln(2.0)), bands)
        }
        println("=== 6b. 1/3 oct ごとに +12 dB を並べる (何本でクランプするか) ===")
        val thirds = listOf(1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000)
        for (k in 1..thirds.size) {
            val bands = thirds.take(k).map { EqBand(it, 141, 120) }
            report("1/3 oct 刻みに +12.0 dB x %d 本".format(k), bands)
        }
    }

    @Test
    fun shelvesAtFullScale() {
        println("=== 5. シェルフ ===")
        report(
            "LOW_SHELF 105 Hz Q=0.71 +12 / HIGH_SHELF 2.5k Q=0.71 +12",
            listOf(
                EqBand(105, 71, 120, EqBandType.LOW_SHELF),
                EqBand(2_500, 71, 120, EqBandType.HIGH_SHELF),
            ),
        )
        report(
            "同じ 2 本 + 3 kHz PEAKING Q=1.0 +12",
            listOf(
                EqBand(105, 71, 120, EqBandType.LOW_SHELF),
                EqBand(2_500, 71, 120, EqBandType.HIGH_SHELF),
                EqBand(3_000, 100, 120, EqBandType.PEAKING),
            ),
        )
    }
}
