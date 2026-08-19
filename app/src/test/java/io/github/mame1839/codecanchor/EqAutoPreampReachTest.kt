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

/**
 * 自動プリアンプ ([EqSolver.autoPreampDb10]) が取りうる値の測定。
 *
 * [EqSolver.autoPreampDb10] が今も残っているのは**旧版からの移行のため**だけ
 * (`EqSettings.preampFrom`。`pa` が真だった保存値の「実際に鳴っていたプリアンプ」を
 * 同じ関数で再現する)。移行の値は [EqSettings.PREAMP_RANGE] にクランプされるので、
 * **この関数が -400 を下回る構成では、移行の前後で音が変わる。**
 * ここはその「クランプが働く面」がどこから始まるかの測定。
 *
 * ここは**見張りではなく測定の記録**。数値は測った当日の実測で、
 * 動いたら「変わった」と分かるように literal で釘を打ってある。
 * 落ちたときに直す先はこのファイルではなく、変えた側の妥当性の確認。
 */
class EqAutoPreampReachTest {

    private fun q100Of(bandCount: Int): Int =
        (EqSolver.defaultQ(bandCount) * EqUnits.Q_SCALE).toInt()

    /** 401 点の格子 ([EqCurveGrid.HZ] = autoPreampDb10 が見る点) 上のピーク (dB)。 */
    private fun gridPeakDb(bands: List<EqBand>): Double {
        var peak = Double.NEGATIVE_INFINITY
        for (hz in EqCurveGrid.HZ) {
            val db = EqSolver.combinedResponseDb(bands, hz)
            if (db > peak) peak = db
        }
        return peak
    }

    /** バンド中心での実現応答 (= グラフィックの摘みが表す値)。単位は dB。 */
    private fun centreDb(bands: List<EqBand>): DoubleArray =
        DoubleArray(bands.size) { EqSolver.combinedResponseDb(bands, bands[it].freqHz.toDouble()) }

    private fun maxAbsGainDb(bands: List<EqBand>): Double =
        bands.maxOf { abs(it.gainDb10.toDouble() / EqUnits.GAIN_SCALE) }

    /**
     * 目標 (摘み) を解いてバンドにする。UI の経路 (`EqSection.reband` /
     * [EqSolver.withGraphicTarget]) はどちらも最後にこの呼び出しへ落ちる。
     */
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

    // ── 1. KDoc の数字 ────────────────────────────────────────────────
    // EqSolver.autoPreampDb10 の KDoc は「10 バンド全部 +6 dB のとき実際のピークは +17.31 dB
    // (Q = defaultQ(10) = 0.5)」と書いている。ここで言う「+6 dB」が
    // **バンドのゲインそのもの** (摘み = 目標ではない) であることも一緒に確かめる。
    @Test
    fun theKdocFigureForTenBandsAtSixDb() {
        println("=== 1. KDoc: バンドのゲインを全部 +6 dB (摘みではない) ===")
        val values = EqSettings.BAND_COUNTS.associateWith { n ->
            val bands = EqSolver.centerFrequencies(n)
                .map { EqBand(freqHz = it, q100 = q100Of(n), gainDb10 = 60) }
            report("n=$n  raw band gain +6.0 dB  Q=${EqSolver.defaultQ(n)}", bands)
        }
        // 10 バンドの実測。KDoc の +17.31 dB がいまも正しいかの答え。
        require(values.getValue(10) in -174..-172) { "KDoc の -173 から動いた: ${values.getValue(10)}" }

        println("=== 1b. 同じ +6 dB を『摘み (目標)』として解いた場合 ===")
        EqSettings.BAND_COUNTS.forEach { n ->
            val bands = solveKnobs(n, IntArray(n) { 60 })
            report("n=$n  knob +6.0 dB (solveBands)", bands)
        }
    }

    // ── 2. グラフィックの摘みを振り切った形 ──────────────────────────
    // 摘みの可動域は ui/EqCurve.kt の EQ_GAIN_RANGE = -120..120 (= ±12.0 dB)。
    // ui/EqSection.kt の GAIN_SCALE がそれをそのまま使い、グラフィックの摘みも
    // パラメトリックのゲインも同じスケールを引く。
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
        // 実測 2026-08-19。最悪は n=5 の「+12 の 2 本を -12 で挟む」で -145 (14.53 dB)。
        // PREAMP_RANGE の下端 -400 まで 25 dB 以上の余裕がある。
        require(worst in -150..-140) { "型どおりの形での最悪が -145 から動いた: $worst ($worstLabel)" }
    }

    // ── 3. 摘みの空間をしらみつぶし / 無作為に撃つ ────────────────────
    // 型どおりの形しか撃たないと「撃っていない形は届かない」を言えないので、
    // バンド数ごとに**同じ本数**の無作為な摘みベクトルを撃つ。
    // 5 バンドだけは 3 値 (-12/0/+12) の全 243 通りを追加で総当たりする。
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
                // 前半は 3 値 (端と 0)、後半は 0.1 dB 刻みの一様。
                val knobs = if (k < draws / 2) {
                    IntArray(n) { intArrayOf(-120, 0, 120)[rng.nextInt(3)] }
                } else {
                    IntArray(n) { rng.nextInt(-120, 121) }
                }
                val bands = solveKnobs(n, knobs)
                val centres = centreDb(bands)
                // solve() が Q を上げても収まらないと素朴な値へ落ちる。そのとき中心が目標に
                // 合わなくなるので、ここで検出する (落ちた形はピークが跳ねうる)。
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

        // 5 バンドだけ 3 値の総当たり。3^5 = 243 通り。
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

    // ── 4. パラメトリック ────────────────────────────────────────────
    // UI で作れる形: バンドは最大 31 本 (EqSettings.MAX_BANDS)、ゲインはグラフィックと
    // 同じ ±12.0 dB (ui/EqSection.kt の GAIN_SCALE)、Q は 0.10〜10.00
    // (ui/EqSection.kt の Q_SCALE = Log(10..1_000))、周波数は 20〜20 kHz。
    // バンドを足したときの初期値は 1 kHz / Q 1.41 / 0 dB なので、
    // **足しただけのバンドは全部 1 kHz に重なる。**
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
                // 実測 2026-08-19。Q を UI の端から端まで振っても 4 本。
                // バンドを足したときの既定が 1 kHz なので、**足して +12 にするだけで重なる。**
                require(firstClamped == 4) { "$label で 4 本から動いた: $firstClamped" }
            }

        println("=== 4b. JSON に書けば入る形 (EqBand.GAIN_RANGE = ±40.0 / Q_RANGE = 0.10〜40.00) ===")
        // 旧版のプリセット JSON が "pa": true を持っていれば、読み込みで preampFrom が
        // この値を通す (applyPreset は settings をそのまま着地させる)。
        val jsonWorst = List(EqSettings.MAX_BANDS) { EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 400) }
        report("1 kHz Q=1.41  +40.0 dB x 31 (手書きプリセット)", jsonWorst)

        println("=== 4c. 現実的な形 (10 本を 1 kHz に重ねる / 3 本を別々の帯域へ) ===")
        report("1 kHz Q=1.41  +12.0 dB x 10", List(10) { EqBand(1_000, 141, 120) })
        report(
            "100/1k/10k Q=1.41  +12.0 dB x 3 (足した直後の並び)",
            listOf(EqBand(100, 141, 120), EqBand(1_000, 141, 120), EqBand(10_000, 141, 120)),
        )
    }

    // ── 5. 好みの EQ の焼き込み ──────────────────────────────────────
    // グラフィックの側は「土台の応答 + オーバーレイの応答」を目標に解き直すので、
    // **目標が摘みの可動域 ±12 dB を越えうる。**バンドがどこまで育つかは
    // プリアンプの決め方と独立なので、この測定は移行の後もそのまま有効。
    @Test
    fun finderBakeReach() {
        println("=== 5. 好みの EQ の焼き込み ===")
        val axes = listOf(
            io.github.mame1839.codecanchor.core.EqFinderAxes.BASS,
            io.github.mame1839.codecanchor.core.EqFinderAxes.TREBLE,
            io.github.mame1839.codecanchor.core.EqFinderAxes.MID,
        )
        val overlay = listOf(120, 120, 120) // 3 軸とも上端

        EqSettings.BAND_COUNTS.forEach { n ->
            val base = EqSettings(
                enabled = true,
                bandCount = n,
                bands = solveKnobs(n, IntArray(n) { 120 }),
            )
            val baked = io.github.mame1839.codecanchor.core.EqFinderMaterialize
                .bake(base, axes, overlay, 0)
            requireNotNull(baked)
            val knobs = EqSolver.graphicTargetsDb10(baked.bands)
            report("graphic n=%2d  土台 摘み全 +12 + 3 軸 +12".format(n), baked.bands)
            println("      焼き込み後の摘み (dB*10) = ${knobs.joinToString(",")}")
        }

        val paramBase = EqSettings(
            enabled = true,
            mode = io.github.mame1839.codecanchor.core.EqMode.PARAMETRIC,
            bands = List(10) { EqBand(1_000, 141, 120) },
        )
        val paramBaked = io.github.mame1839.codecanchor.core.EqFinderMaterialize
            .bake(paramBase, axes, overlay, 0)
        requireNotNull(paramBaked)
        report("parametric  土台 1 kHz +12 x10 + 3 軸 +12", paramBaked.bands)

        // 焼き込みは何度でも重ねられる。グラフィックは応答を目標に読み直すので、
        // 摘みが可動域 ±12 を越えて育つ。
        println("--- 5b. 焼き込みを繰り返す (3 軸とも上端 +12。素の音から開始) ---")
        listOf(5, 10, 15, 31).forEach { n ->
            var s = EqSettings(
                enabled = true,
                bandCount = n,
                bands = solveKnobs(n, IntArray(n) { 0 }),
            )
            for (round in 1..6) {
                s = io.github.mame1839.codecanchor.core.EqFinderMaterialize
                    .bake(s, axes, overlay, 0) ?: break
                val knobs = EqSolver.graphicTargetsDb10(s.bands)
                val preamp = report(
                    "graphic n=%2d  焼き込み %d 回目 (摘みの最大 %.1f dB)"
                        .format(n, round, knobs.max() / 10.0),
                    s.bands,
                )
                if (preamp < EqSettings.PREAMP_RANGE.first) {
                    println("      ↑ n=$n はここでクランプに入った (焼き込み $round 回目)")
                    break
                }
            }
        }

        // 上端に張り付いた結果ばかり撃つと「実際に出る結果」から離れるので、
        // 控えめなオーバーレイでも同じ測り方をする。
        println("--- 5b'. 控えめなオーバーレイで繰り返す (グラフィック 10 バンド) ---")
        listOf(
            listOf(60, 60, 60) to "3 軸とも +6",
            listOf(120, 0, 0) to "低域だけ +12",
            listOf(60, 0, 0) to "低域だけ +6",
        ).forEach { (ov, label) ->
            var s = EqSettings(
                enabled = true,
                bandCount = 10,
                bands = solveKnobs(10, IntArray(10) { 0 }),
            )
            var clampedAt = -1
            for (round in 1..12) {
                s = io.github.mame1839.codecanchor.core.EqFinderMaterialize.bake(s, axes, ov, 0) ?: break
                val preamp = EqSolver.autoPreampDb10(s.bands)
                if (preamp < EqSettings.PREAMP_RANGE.first) {
                    clampedAt = round
                    break
                }
            }
            println("      $label: クランプに入る焼き込みの回数 = ${if (clampedAt < 0) "12 回でも入らない" else "$clampedAt 回目"}")
        }

        println("--- 5c. パラメトリックで焼き込みを繰り返す (3 本ずつ増える。上限 31 本) ---")
        var p = EqSettings(
            enabled = true,
            mode = io.github.mame1839.codecanchor.core.EqMode.PARAMETRIC,
            bands = listOf(EqBand(100, 141, 0), EqBand(1_000, 141, 0), EqBand(10_000, 141, 0)),
        )
        for (round in 1..9) {
            p = io.github.mame1839.codecanchor.core.EqFinderMaterialize
                .bake(p, axes, overlay, 0) ?: break
            val preamp = report("parametric 焼き込み %d 回目 (%2d 本)".format(round, p.bands.size), p.bands)
            if (preamp < EqSettings.PREAMP_RANGE.first) {
                println("      ↑ ここでクランプに入った (焼き込み $round 回目)")
                break
            }
        }
    }

    // ── 7. パラメトリックのバンドをどれだけ離せば足し合わないか ────────
    // 「重ねると 4 本でクランプ」がどれくらいの近さで起きるかの目安。
    @Test
    fun parametricSeparation() {
        println("=== 7. パラメトリック: +12 dB の 2 本を離していく (Q=1.41) ===")
        listOf(1_000, 1_100, 1_250, 1_400, 1_600, 2_000, 2_800, 4_000, 8_000).forEach { hz ->
            val bands = listOf(EqBand(1_000, 141, 120), EqBand(hz, 141, 120))
            report("1 kHz + %5d Hz  (%.2f oct 離れ)".format(hz, kotlin.math.ln(hz / 1000.0) / kotlin.math.ln(2.0)), bands)
        }
        println("=== 7b. 1/3 oct ごとに +12 dB を並べる (何本でクランプするか) ===")
        val thirds = listOf(1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000, 5_000)
        for (k in 1..thirds.size) {
            val bands = thirds.take(k).map { EqBand(it, 141, 120) }
            report("1/3 oct 刻みに +12.0 dB x %d 本".format(k), bands)
        }
    }

    // ── 6. シェルフ (手書きプリセットとファインダの軸だけが使う) ────────
    @Test
    fun shelvesAtFullScale() {
        println("=== 6. シェルフ ===")
        report(
            "LOW_SHELF 105 Hz Q=0.71 +12 / HIGH_SHELF 2.5k Q=0.71 +12",
            listOf(
                EqBand(105, 71, 120, EqBandType.LOW_SHELF),
                EqBand(2_500, 71, 120, EqBandType.HIGH_SHELF),
            ),
        )
        report(
            "同じ 2 本 + 3 kHz PEAKING Q=1.0 +12 (ファインダの 3 軸を上端で)",
            listOf(
                EqBand(105, 71, 120, EqBandType.LOW_SHELF),
                EqBand(2_500, 71, 120, EqBandType.HIGH_SHELF),
                EqBand(3_000, 100, 120, EqBandType.PEAKING),
            ),
        )
    }
}
