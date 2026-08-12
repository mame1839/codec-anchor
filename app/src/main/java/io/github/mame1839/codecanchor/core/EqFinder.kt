package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 好みの EQ を見つける機能 (目隠し A/B) の探索エンジン (eq-finder-design.md §2)。
 *
 * 実装の骨格は「回答列だけが状態」のリプレイ方式。セッションの実体は
 * (axes, seed, startStepDb10, 回答列) の 4 つで、試行列・推定値・進捗は毎回それらから
 * 決定的に再生する。こうすると仕様の 2 つの要求が構造から出る:
 *
 * - 決定性: 同じ (axes, seed, startStepDb10, 回答列) は同じ試行列を生む
 * - 中断/再開: toJson は 4 つを書くだけで、fromJson 後の続きが完全に一致する
 *
 * 乱数 (A/B の割当・プローブの選択・摂動の符号) は SplitMix64 を自前で持つ。
 * kotlin.random は版間でアルゴリズムが保証されず、アプリ更新を跨いだ再開で
 * 試行列が変わってしまうため。
 */

/**
 * 探索軸 = オーバーレイの 1 本。ゲインは db10 整数。
 *
 * 既定の範囲 ±120 (±12 dB) は、シェルフを両方 +12 dB にしても聴感等価プリアンプが
 * [EqSettings.PREAMP_RANGE] に収まる余裕から (eq-finder-design.md §1)。
 */
data class EqFinderAxis(
    val freqHz: Int,
    val q100: Int,
    val type: Int,
    val minDb10: Int = -120,
    val maxDb10: Int = 120,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("f", freqHz)
        put("q", q100)
        put("t", type)
        put("lo", minDb10)
        put("hi", maxDb10)
    }

    companion object {
        /** 壊れていたら null。キーの欠けは org.json の getInt が投げるので runCatching で受ける。 */
        fun fromJson(o: JSONObject): EqFinderAxis? {
            val axis = runCatching {
                EqFinderAxis(o.getInt("f"), o.getInt("q"), o.getInt("t"), o.getInt("lo"), o.getInt("hi"))
            }.getOrNull() ?: return null
            val valid = axis.freqHz in EqBand.FREQ_RANGE &&
                axis.q100 in EqBand.Q_RANGE &&
                EqBandType.normalize(axis.type) == axis.type &&
                axis.minDb10 <= axis.maxDb10
            return if (valid) axis else null
        }
    }
}

object EqFinderAxes {
    /** 低域: LOW_SHELF 105 Hz / Q 0.71。Harman の低域シェルフ (仕様書 §2)。 */
    val BASS = EqFinderAxis(freqHz = 105, q100 = 71, type = EqBandType.LOW_SHELF)

    /** 高域: HIGH_SHELF 2.5 kHz / Q 0.71 (仕様書 §2)。 */
    val TREBLE = EqFinderAxis(freqHz = 2_500, q100 = 71, type = EqBandType.HIGH_SHELF)

    /**
     * 中域プレゼンス: PEAKING 3 kHz / Q 1.0 (仕様は 3 kHz ピーキングとだけ決めている。Q はここで決めた)。
     *
     * Q 1.0 は -3 dB 幅がちょうど 1 オクターブ強 (約 2.1〜4.3 kHz) で、耳が最も敏感な
     * プレゼンス帯を 1 本で覆う。0.71 まで広げると 2.5 kHz シェルフの遷移帯と大きく重なって
     * 軸同士の独立性が落ち、座標降下の収束が遅くなる。2.0 より狭くすると「共鳴探し」になり、
     * それは別の仕事 (帯域スイープ) の領分 (仕様書 §0)。
     */
    val MID = EqFinderAxis(freqHz = 3_000, q100 = 100, type = EqBandType.PEAKING)

    /** 既定の軸: 低域→高域 (→中域)。この順で探索する (個人差の支配項から先に)。 */
    fun default(includeMid: Boolean): List<EqFinderAxis> =
        if (includeMid) listOf(BASS, TREBLE, MID) else listOf(BASS, TREBLE)
}

data class EqFinderResult(
    /** axes と同順のオーバーレイ (db10)。 */
    val overlayDb10: List<Int>,
    /** 一貫性プローブで矛盾が出た (結果画面で正直に出す。仕様書 §2)。 */
    val consistencyWarning: Boolean,
    /** 検証段まで完走した。上限打ち切りや途中確定では false のまま。 */
    val validated: Boolean,
    /** 検証段 (a) で結果が開始点に勝ったか。未実施 (未到達か、結果が開始点と同一) なら null。 */
    val startBeatenInValidation: Boolean?,
)

/**
 * 探索セッション。不変スナップショット — [answered] は新しいインスタンスを返す。
 *
 * ## 仕様が決めていない細部でここが決めた規則 (テストで固定)
 *
 * 1 軸の探索 (挑戦 = 現推定 ± step):
 * - 「同じ」は挑戦側の非勝利として扱う。同じ軸内で「同じ」が連続 2 回出たらステップ半減
 *   (最小ステップなら収束)。連続でなければ数えない (勝ち負けでリセット)
 * - 両側 (現方向とその反対) とも挑戦が勝てなかったらステップ半減。最小ステップなら収束 —
 *   推定が最小刻みで両隣に勝っている状態はこれ以上の情報を生まない
 *   (仕様の収束条件「反転 2 回 or 同じ 2 回」は勝ちが続いて往復する場合の規則)
 * - 反転 = 反対側の挑戦が勝って推定の移動方向が変わること。最小ステップでない反転は半減、
 *   最小ステップでの反転は 2 回で収束
 * - 範囲の端で挑戦値が現推定と同じになる側は、試行を消費せず非勝利として扱う
 * - 初回の方向は常に +。A/B の提示順は seed 乱数なので、方向の固定は回答バイアスにならない
 *
 * 相互作用の再確認 (全軸収束後):
 * - ステップ 10 (収束刻み 5 の一段上 — 相互作用によるずれは半減前の粗さで現れる)、1 軸 4 試行まで。
 *   + 側に動けるだけ動き、動かなければ − 側。動いた方向で終える (引き戻しはしない)
 * - 先頭軸は必ず再確認。動いたら残りの軸も順に。どこかが動いた周があればもう 1 周 (上限 2 周)
 *
 * 一貫性プローブ:
 * - 本数は軸数を 2..3 に丸めた数 (決着した過去の比較が足りなければその数)。seed 乱数で
 *   重複なしに選び、左右を入れ替えて再提示する
 * - 矛盾 = 逆の候補が勝つこと。「同じ」は矛盾に数えない
 * - 矛盾した軸はステップ 10 (一段戻し) で再探索し、consistencyWarning は完走しても true のまま
 *
 * 検証段:
 * - (a) 結果 vs 開始点 (全 0)。結果が勝てば 1 試行で確定、「同じ」なら 1 回だけ再提示し、
 *   再提示でも勝てなければ false。結果 = 開始点なら実施せず null。再検証の周では上書きする
 * - (b) 軸を順繰りに (軸数を 2..3 に丸めた本数)、±10 db10 の摂動 (符号は seed 乱数、
 *   端で反転、両端が塞がる軸は飛ばす)。摂動が勝ったらその軸の推定を摂動値に移して
 *   step 5 で再探索し、その周は打ち切って再検証する
 * - 再開 (摂動勝ちによる再探索) は全体で 2 回まで。使い切った後の摂動勝ちは無視して完走する。
 *   これが停止性の保証 — 検証の周は最大 3 回で必ず終わる
 *
 * 総試行の硬い上限は [MAX_TRIALS]。達したら [finished] (validated は実態どおり)。
 *
 * [progress] の見込み総数は、開始時の見積り (軸数 × 軸あたり見込み + 後段) に、追加の作業
 * (再確認の追加周・矛盾やその再探索・再検証) が確定した時点で上乗せし、常に消化済み試行数を
 * 下回らないよう底上げする。増える一方 (単調非減少) で、上限は [MAX_TRIALS]。
 * 早く終わったときは見込みが消化数より大きいまま残る — 完了の判定は [finished] で行うこと。
 */
class EqFinderSession private constructor(
    val axes: List<EqFinderAxis>,
    private val seed: Long,
    private val startStepDb10: Int,
    private val answers: List<Answer>,
) {
    enum class Answer { A, B, SAME }

    /** 今の試行。A/B への割当は seed 由来の乱数。 */
    data class Trial(val aOverlayDb10: List<Int>, val bOverlayDb10: List<Int>)

    companion object {
        /** 総試行の硬い上限 (仕様書 §2)。 */
        const val MAX_TRIALS = 60

        const val DEFAULT_START_STEP_DB10 = 40

        private const val VERSION = 1

        fun start(
            axes: List<EqFinderAxis>,
            seed: Long,
            startStepDb10: Int = DEFAULT_START_STEP_DB10,
        ): EqFinderSession {
            require(axes.isNotEmpty()) { "軸が 1 本も無い" }
            require(axes.all { it.minDb10 <= it.maxDb10 }) { "軸の範囲が逆 (minDb10 > maxDb10)" }
            return EqFinderSession(axes, seed, startStepDb10.coerceAtLeast(MIN_STEP_DB10), emptyList())
        }

        /** 壊れていたら null。 */
        fun fromJson(o: JSONObject): EqFinderSession? = runCatching { parse(o) }.getOrNull()

        private fun parse(o: JSONObject): EqFinderSession? {
            if (o.getInt("v") != VERSION) return null
            val seed = o.getLong("seed")
            val step = o.getInt("step")
            val axesArray = o.getJSONArray("axes")
            val axes = ArrayList<EqFinderAxis>(axesArray.length())
            for (i in 0 until axesArray.length()) {
                axes += EqFinderAxis.fromJson(axesArray.getJSONObject(i)) ?: return null
            }
            if (axes.isEmpty()) return null
            val text = o.getString("ans")
            if (text.length > MAX_TRIALS) return null
            val answers = ArrayList<Answer>(text.length)
            for (c in text) {
                answers += when (c) {
                    'A' -> Answer.A
                    'B' -> Answer.B
                    'S' -> Answer.SAME
                    else -> return null
                }
            }
            return EqFinderSession(axes, seed, step.coerceAtLeast(MIN_STEP_DB10), answers)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("seed", seed)
        put("step", startStepDb10)
        put("axes", JSONArray().also { a -> axes.forEach { a.put(it.toJson()) } })
        put(
            "ans",
            answers.joinToString("") {
                when (it) {
                    Answer.A -> "A"
                    Answer.B -> "B"
                    Answer.SAME -> "S"
                }
            },
        )
    }

    private val replayed: Replay by lazy { replay() }

    /** 今の試行。[finished] なら null。 */
    fun currentTrial(): Trial? = if (finished) null else replayed.pending

    /** 回答を 1 つ積んだ次のセッション。[finished] のときは何もしない (自分を返す)。 */
    fun answered(answer: Answer): EqFinderSession =
        if (finished) this else EqFinderSession(axes, seed, startStepDb10, answers + answer)

    val finished: Boolean
        get() = replayed.programDone || answers.size >= MAX_TRIALS

    /** (済んだ試行数, 見込み総数)。見込みは単調非減少 (クラス KDoc 参照)。 */
    fun progress(): Pair<Int, Int> = answers.size to replayed.expectedTotal

    /** いつでも呼べる (「ここで確定」)。 */
    fun result(): EqFinderResult = EqFinderResult(
        overlayDb10 = replayed.estimates.toList(),
        consistencyWarning = replayed.consistencyWarning,
        validated = replayed.validated,
        startBeatenInValidation = replayed.startBeaten,
    )

    private class Replay(
        val pending: Trial?,
        val programDone: Boolean,
        val estimates: IntArray,
        val consistencyWarning: Boolean,
        val validated: Boolean,
        val startBeaten: Boolean?,
        val expectedTotal: Int,
    )

    /**
     * 回答列を先頭から流し込んで探索プログラムを再生する。
     *
     * プログラムは試行を 1 つ yield するたびに止まり、こちらが [Feed] に回答を置いてから
     * 再開する。回答が尽きた時点の yield が「今の試行」。60 試行 (上限) を答え終わっても
     * プログラムが続きを求めることがあるが、その試行は表に出さない ([finished] が先に立つ)。
     */
    private fun replay(): Replay {
        val board = Board(axes)
        val feed = Feed()
        val iterator = explorationProgram(axes, seed, startStepDb10, feed, board).iterator()
        var pending: Spec? = null
        var programDone = false
        var i = 0
        while (true) {
            if (!iterator.hasNext()) {
                programDone = true
                break
            }
            val spec = iterator.next()
            if (i < answers.size) {
                feed.slot = answers[i]
                i++
            } else {
                pending = spec
                break
            }
        }
        return Replay(
            pending = pending?.let {
                Trial(
                    aOverlayDb10 = (if (it.secondIsA) it.second else it.first).toList(),
                    bOverlayDb10 = (if (it.secondIsA) it.first else it.second).toList(),
                )
            },
            programDone = programDone,
            estimates = board.est,
            consistencyWarning = board.consistencyWarning,
            validated = board.validated,
            startBeaten = board.startBeaten,
            expectedTotal = board.expected,
        )
    }
}

/** 探索の刻み: 40 → 20 → 10 → 5 (db10)。5 = 0.5 dB が最小 (仕様書 §2)。 */
private const val MIN_STEP_DB10 = 5

/** 相互作用の再確認と、矛盾時の「一段戻し」の刻み。 */
private const val RECHECK_STEP_DB10 = 10

/** 再確認 1 軸あたりの試行上限 (仕様書 §2 の「2〜4 試行」)。 */
private const val RECHECK_MAX_ASKS = 4

/** 検証段 (b) の摂動幅。収束刻み 5 の一段上 — 収束し損ねはこの粗さで現れる。 */
private const val PERTURB_DB10 = 10

/** 摂動勝ちによる再開の総数上限 (停止性の保証。仕様書 §2)。 */
private const val MAX_REOPENS = 2

/**
 * SplitMix64。kotlin.random は版間でシーケンスが保証されないので使わない —
 * ここの乱数列が変わると、保存済みセッションの再開で試行列がずれる。
 */
private class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextBoolean(): Boolean = nextLong() < 0

    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()
}

/** リプレイ中の回答の受け渡し口。yield の直後に take される。 */
private class Feed {
    var slot: EqFinderSession.Answer? = null

    fun take(): EqFinderSession.Answer {
        val a = checkNotNull(slot) { "回答が積まれる前にプログラムが進んだ" }
        slot = null
        return a
    }
}

/** yield される 1 試行。first = 現職 (現推定 / 結果)、second = 挑戦側。 */
private class Spec(val first: IntArray, val second: IntArray, val secondIsA: Boolean)

/** 一貫性プローブの候補になる、決着済みの過去の比較。配列は当時のスナップショット。 */
private class PastDuel(
    val axis: Int,
    val first: IntArray,
    val second: IntArray,
    val secondWasA: Boolean,
    val secondWon: Boolean,
)

/** プログラムが書き、リプレイ後に読み取る板。 */
private class Board(axes: List<EqFinderAxis>) {
    val est = IntArray(axes.size) { 0.coerceIn(axes[it].minDb10, axes[it].maxDb10) }
    var asked = 0
    var expected = 0
    var consistencyWarning = false
    var validated = false
    var startBeaten: Boolean? = null
    val history = ArrayList<PastDuel>()

    /** 追加の作業が確定したときに見込みを上乗せする。単調非減少・上限 MAX_TRIALS。 */
    fun bump(extra: Int) {
        expected = (expected + extra).coerceAtMost(EqFinderSession.MAX_TRIALS)
    }

    /** 見込みが実績を下回らないように底上げする (見積りが外れた分の保険)。 */
    fun floorAtAsked() {
        expected = expected.coerceAtLeast(asked.coerceAtMost(EqFinderSession.MAX_TRIALS))
    }
}

private class Ctx(
    val axes: List<EqFinderAxis>,
    val rng: Rng,
    val feed: Feed,
    val board: Board,
)

/** 挑戦の結果。BLOCKED = 範囲の端で挑戦値が作れず、試行を消費していない。 */
private enum class Outcome { WON, LOST, SAME, BLOCKED }

/** 1 軸の探索見込み (試行数)。段ごとに 2 + 収束の確認 2。40 開始で 10 (仕様の 7〜10 と整合)。 */
private fun axisForecast(step: Int): Int {
    var s = step.coerceAtLeast(MIN_STEP_DB10)
    var levels = 1
    while (s > MIN_STEP_DB10) {
        s = (s / 2).coerceAtLeast(MIN_STEP_DB10)
        levels++
    }
    return 2 * levels + 2
}

private fun probeCount(axisCount: Int): Int = axisCount.coerceIn(2, 3)

/** 開始時の見込み総数: 軸 + 再確認 (先頭軸) + 一貫性 + 検証 (a)(b)。2 軸 40 開始で 27。 */
private fun initialForecast(axisCount: Int, startStep: Int): Int =
    (axisCount * axisForecast(startStep) + 2 + probeCount(axisCount) + 1 + probeCount(axisCount))
        .coerceAtMost(EqFinderSession.MAX_TRIALS)

/**
 * 探索プログラム本体。仕様書 §2 の手順を上から順に書いた形 —
 * 試行を出して回答を待つところはすべて [duel] (中で yield) に集約してある。
 */
private fun explorationProgram(
    axes: List<EqFinderAxis>,
    seed: Long,
    startStep: Int,
    feed: Feed,
    board: Board,
): Sequence<Spec> = sequence {
    val ctx = Ctx(axes, Rng(seed), feed, board)
    board.expected = initialForecast(axes.size, startStep)

    // 1. 座標降下: 渡された順に 1 軸ずつ (低域 → 高域 → [中域])
    for (j in axes.indices) exploreAxis(ctx, j, startStep)

    // 2. 相互作用の再確認: 先頭軸は必ず。動いたら残りも。動いた周があればもう 1 周 (上限 2 周)
    var movedAny = recheckAxis(ctx, 0)
    if (movedAny && axes.size > 1) {
        ctx.board.bump(2 * (axes.size - 1))
        for (j in 1 until axes.size) {
            if (recheckAxis(ctx, j)) movedAny = true
        }
    }
    if (movedAny) {
        ctx.board.bump(2 * axes.size)
        for (j in axes.indices) recheckAxis(ctx, j)
    }

    // 3. 一貫性プローブ
    runConsistencyProbes(ctx)

    // 4. 検証段。ここまで来たら (打ち切られなければ) validated
    runValidation(ctx)
    ctx.board.validated = true
}

/**
 * 1 試行 = 目隠し A/B を 1 回。戻り値は挑戦側 (second) が勝ったか。null = 「同じ」。
 *
 * A/B への割当は seed 由来の乱数。一貫性プローブだけは「元の提示の左右入れ替え」を
 * [forcedSecondIsA] で指定する (乱数は消費しない)。
 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.duel(
    ctx: Ctx,
    first: IntArray,
    second: IntArray,
    recordAxis: Int? = null,
    forcedSecondIsA: Boolean? = null,
): Boolean? {
    val secondIsA = forcedSecondIsA ?: ctx.rng.nextBoolean()
    ctx.board.asked++
    ctx.board.floorAtAsked()
    yield(Spec(first, second, secondIsA))
    val won = when (ctx.feed.take()) {
        EqFinderSession.Answer.SAME -> null
        EqFinderSession.Answer.A -> secondIsA
        EqFinderSession.Answer.B -> !secondIsA
    }
    if (recordAxis != null && won != null) {
        ctx.board.history += PastDuel(recordAxis, first, second, secondIsA, won)
    }
    return won
}

/**
 * 軸 [j] の推定を [deltaDb10] だけ動かした挑戦を出す。勝ったら推定を移す。
 * 範囲の端で挑戦値が現推定と同じになるなら、試行を消費せず BLOCKED。
 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.challenge(
    ctx: Ctx,
    j: Int,
    deltaDb10: Int,
): Outcome {
    val axis = ctx.axes[j]
    val target = (ctx.board.est[j] + deltaDb10).coerceIn(axis.minDb10, axis.maxDb10)
    if (target == ctx.board.est[j]) return Outcome.BLOCKED
    val incumbent = ctx.board.est.copyOf()
    val challenger = incumbent.copyOf().also { it[j] = target }
    return when (duel(ctx, incumbent, challenger, recordAxis = j)) {
        true -> {
            ctx.board.est[j] = target
            Outcome.WON
        }
        false -> Outcome.LOST
        null -> Outcome.SAME
    }
}

/**
 * 1 軸の縮小ステップ探索。規則は [EqFinderSession] の KDoc にまとめてある。
 * 他の軸は現推定に固定される (座標降下)。
 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.exploreAxis(
    ctx: Ctx,
    j: Int,
    startStep: Int,
) {
    var step = startStep.coerceAtLeast(MIN_STEP_DB10)
    var dir = 1
    var sameStreak = 0
    var reversalsAtMin = 0

    // ステップを半減する。既に最小なら false (= 収束)。
    fun shrink(): Boolean {
        if (step == MIN_STEP_DB10) return false
        step = (step / 2).coerceAtLeast(MIN_STEP_DB10)
        return true
    }

    while (true) {
        // 現方向の挑戦
        when (challenge(ctx, j, dir * step)) {
            Outcome.WON -> {
                sameStreak = 0
                continue
            }
            Outcome.SAME -> {
                sameStreak++
                if (sameStreak >= 2) {
                    sameStreak = 0
                    if (!shrink()) return
                    continue
                }
            }
            Outcome.LOST -> sameStreak = 0
            Outcome.BLOCKED -> {}
        }
        // 反対側のプローブ
        when (challenge(ctx, j, -dir * step)) {
            Outcome.WON -> {
                sameStreak = 0
                dir = -dir
                if (step == MIN_STEP_DB10) {
                    if (++reversalsAtMin >= 2) return
                } else {
                    shrink()
                }
                continue
            }
            Outcome.SAME -> {
                sameStreak++
                if (sameStreak >= 2) {
                    sameStreak = 0
                    if (!shrink()) return
                    continue
                }
            }
            Outcome.LOST -> sameStreak = 0
            Outcome.BLOCKED -> {}
        }
        // 両側とも勝てなかった
        if (!shrink()) return
    }
}

/**
 * 相互作用の再確認。ステップ [RECHECK_STEP_DB10] 固定・[RECHECK_MAX_ASKS] 試行まで。
 * + 側に勝てるだけ動き、1 度も動かなければ − 側も突く。動いたかを返す。
 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.recheckAxis(ctx: Ctx, j: Int): Boolean {
    var asksLeft = RECHECK_MAX_ASKS
    var moved = false
    for (dir in intArrayOf(1, -1)) {
        if (dir == -1 && moved) break
        while (asksLeft > 0) {
            val outcome = challenge(ctx, j, dir * RECHECK_STEP_DB10)
            if (outcome == Outcome.BLOCKED) break
            asksLeft--
            if (outcome == Outcome.WON) moved = true else break
        }
    }
    return moved
}

/**
 * 一貫性プローブ: 決着済みの比較から seed 乱数で選び、左右を入れ替えて再提示。
 * 逆の候補が勝ったら矛盾 — その軸を一段戻し (ステップ 10) で再探索し、警告を立てる。
 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.runConsistencyProbes(ctx: Ctx) {
    val pool = ctx.board.history.toMutableList()
    val want = probeCount(ctx.axes.size).coerceAtMost(pool.size)
    if (want == 0) return
    val picked = ArrayList<PastDuel>(want)
    repeat(want) { picked += pool.removeAt(ctx.rng.nextInt(pool.size)) }
    val contradicted = sortedSetOf<Int>()
    for (p in picked) {
        val won = duel(ctx, p.first, p.second, forcedSecondIsA = !p.secondWasA)
        if (won != null && won != p.secondWon) contradicted += p.axis
    }
    if (contradicted.isEmpty()) return
    ctx.board.consistencyWarning = true
    for (j in contradicted) {
        ctx.board.bump(axisForecast(RECHECK_STEP_DB10))
        exploreAxis(ctx, j, RECHECK_STEP_DB10)
    }
}

/** 検証段 (a): 結果 vs 開始点 (全 0)。規則はクラス KDoc。 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.validateAgainstStart(ctx: Ctx) {
    if (ctx.board.est.all { it == 0 }) return
    val first = duel(ctx, ctx.board.est.copyOf(), IntArray(ctx.axes.size))
    ctx.board.startBeaten = when (first) {
        false -> true // 挑戦側 = 開始点が負けた = 結果の勝ち
        true -> false
        null -> duel(ctx, ctx.board.est.copyOf(), IntArray(ctx.axes.size)) == false
    }
}

/** 検証段 (a)+(b)。摂動勝ちで再開・再検証。再開は [MAX_REOPENS] 回まで (停止性)。 */
private suspend fun kotlin.sequences.SequenceScope<Spec>.runValidation(ctx: Ctx) {
    var reopensLeft = MAX_REOPENS
    while (true) {
        validateAgainstStart(ctx)
        var reopened = false
        val probes = probeCount(ctx.axes.size)
        for (k in 0 until probes) {
            val j = k % ctx.axes.size
            val axis = ctx.axes[j]
            val sign = if (ctx.rng.nextBoolean()) 1 else -1
            var target = (ctx.board.est[j] + sign * PERTURB_DB10).coerceIn(axis.minDb10, axis.maxDb10)
            if (target == ctx.board.est[j]) {
                target = (ctx.board.est[j] - sign * PERTURB_DB10).coerceIn(axis.minDb10, axis.maxDb10)
            }
            if (target == ctx.board.est[j]) continue // 両端が塞がった軸 (範囲が退化) は測れない
            val result = ctx.board.est.copyOf()
            val perturbed = result.copyOf().also { it[j] = target }
            val won = duel(ctx, result, perturbed)
            if (won == true && reopensLeft > 0) {
                reopensLeft--
                ctx.board.est[j] = target
                ctx.board.bump(axisForecast(MIN_STEP_DB10) + 1 + probes)
                exploreAxis(ctx, j, MIN_STEP_DB10)
                reopened = true
                break // この周は打ち切って再検証へ
            }
        }
        if (!reopened) return
    }
}

object EqFinderMaterialize {

    /**
     * 軸 → バンドの写像はここ 1 本だけ。試聴候補も焼き込みのオーバーレイ応答も
     * これを通す — 写像が 2 箇所にあると片方だけ直して音が食い違う。
     */
    private fun axisBands(axes: List<EqFinderAxis>, overlayDb10: List<Int>): List<EqBand> {
        require(overlayDb10.size == axes.size) {
            "オーバーレイは軸と同数であること (軸 ${axes.size}, 実際 ${overlayDb10.size})"
        }
        return axes.mapIndexed { i, a ->
            EqBand(
                freqHz = a.freqHz.coerceIn(EqBand.FREQ_RANGE),
                q100 = a.q100.coerceIn(EqBand.Q_RANGE),
                gainDb10 = overlayDb10[i].coerceIn(EqBand.GAIN_RANGE),
                type = EqBandType.normalize(a.type),
            )
        }
    }

    /**
     * 試聴候補: base にオーバーレイのバンドを追記する。**ゲイン 0 の軸も含める** —
     * 全候補でバンド構成 (freq/q/type の並び) を固定してゲイン差だけにするのが
     * クリックレス切替 (10 ms 係数ランプ) の成立条件 (仕様書 §3)。
     */
    fun candidateBands(
        baseBands: List<EqBand>,
        axes: List<EqFinderAxis>,
        overlayDb10: List<Int>,
    ): List<EqBand> = baseBands + axisBands(axes, overlayDb10)

    /**
     * 確定結果の焼き込み。入らないときは null (呼び出し側がエラー表示)。
     *
     * **不変条件: 適用した設定は試聴した音と同じ応答を持たなければならない。**
     * 試聴の土台は「enabled なら bands、切ってあれば素の音」(セッション開始時の
     * baseBands の規則) なので、焼き込みも同じ土台から作る。enabled=false の bands を
     * 応答に含めると、切ってあった旧カーブが適用の瞬間に復活し、耳で選んだ after とは
     * 別の音が保存される。
     *
     * - パラメトリック: ゲイン 0 でない軸のバンドを追記。base が切ってあった場合は、
     *   既存バンドの fc/Q (手作業の成果物) を保ったままゲインを 0 にして残す — 応答は
     *   厳密に試聴と同じ (ゲイン 0 のバンドは音を変えない)。
     *   合計が [EqSettings.MAX_BANDS] を超えるなら null
     * - グラフィック: バンド中心での土台の応答にオーバーレイの応答を足した値を目標にして
     *   [EqSolver.solveBands] で解き直す (仕様書 §4)。バンドが並びどおりなら土台の応答は
     *   [EqSolver.graphicTargetsDb10] と同じ値で、土台が素の音なら目標はオーバーレイの
     *   応答そのもの。Q は保存値ではなく [EqSolver.defaultQ] から取り直す —
     *   エスカレートした Q を種にしない規則 ([EqSolver.withGraphicTarget] と同じ)
     *
     * base が切ってあった場合、鳴っていなかった旧カーブのデータはこの**戻り値からは**消える
     * (グラフィックは摘みごと解き直し、パラメトリックはゲインが 0 になる)。データ自体は残る —
     * 適用は開始点のスロットを書き換えず新しいスロットへ着地するので、旧カーブは
     * 開始点のスロットにそのまま在る (`llmdocs/eq-slot-design.md` §1)。
     *
     * 焼き込み後は enabled=true、そして preampAuto=true に戻す。セッション中のプリアンプは
     * 聴感等価 ([EqLoudness]) の一時値で、恒久設定に残すと「なし」側と揃えるための
     * 下駄が鳴りっぱなしになる。自動に戻せば適用側 ([EqParams]) がクリップ防止として解き直す。
     */
    fun bake(
        settings: EqSettings,
        axes: List<EqFinderAxis>,
        overlayDb10: List<Int>,
    ): EqSettings? {
        val overlay = axisBands(axes, overlayDb10)
        return when (settings.mode) {
            EqMode.PARAMETRIC -> {
                val added = overlay.filter { it.gainDb10 != 0 }
                val kept = if (settings.enabled) {
                    settings.bands
                } else {
                    settings.bands.map { it.copy(gainDb10 = 0) }
                }
                val bands = kept + added
                if (bands.size > EqSettings.MAX_BANDS) {
                    null
                } else {
                    settings.copy(bands = bands, enabled = true, preampAuto = true)
                }
            }
            else -> {
                val baseBands = if (settings.enabled) settings.bands else emptyList()
                val freqs = EqSolver.centerFrequencies(settings.bandCount)
                val target = DoubleArray(freqs.size) { i ->
                    val hz = freqs[i].toDouble()
                    EqSolver.combinedResponseDb(baseBands, hz) +
                        EqSolver.combinedResponseDb(overlay, hz)
                }
                settings.copy(
                    bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(freqs.size)),
                    enabled = true,
                    preampAuto = true,
                )
            }
        }
    }
}
