package io.github.mame1839.codecanchor.core

import org.json.JSONArray
import org.json.JSONObject

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
    val BASS = EqFinderAxis(freqHz = 105, q100 = 71, type = EqBandType.LOW_SHELF)

    val TREBLE = EqFinderAxis(freqHz = 2_500, q100 = 71, type = EqBandType.HIGH_SHELF)

    val MID = EqFinderAxis(freqHz = 3_000, q100 = 100, type = EqBandType.PEAKING)

    fun default(includeMid: Boolean): List<EqFinderAxis> =
        if (includeMid) listOf(BASS, TREBLE, MID) else listOf(BASS, TREBLE)
}

data class EqFinderResult(
    val overlayDb10: List<Int>,
    val consistencyWarning: Boolean,
    val validated: Boolean,
    val startBeatenInValidation: Boolean?,
)

class EqFinderSession private constructor(
    val axes: List<EqFinderAxis>,
    private val seed: Long,
    private val startStepDb10: Int,
    private val answers: List<Answer>,
) {
    enum class Answer { A, B, SAME }

    data class Trial(val aOverlayDb10: List<Int>, val bOverlayDb10: List<Int>)

    companion object {
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

    fun currentTrial(): Trial? = if (finished) null else replayed.pending

    fun answered(answer: Answer): EqFinderSession =
        if (finished) this else EqFinderSession(axes, seed, startStepDb10, answers + answer)

    val finished: Boolean
        get() = replayed.programDone || answers.size >= MAX_TRIALS

    fun progress(): Pair<Int, Int> = answers.size to replayed.expectedTotal

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

private const val MIN_STEP_DB10 = 5

private const val RECHECK_STEP_DB10 = 10

private const val RECHECK_MAX_ASKS = 4

private const val PERTURB_DB10 = 10

private const val MAX_REOPENS = 2

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

private class Feed {
    var slot: EqFinderSession.Answer? = null

    fun take(): EqFinderSession.Answer {
        val a = checkNotNull(slot) { "回答が積まれる前にプログラムが進んだ" }
        slot = null
        return a
    }
}

private class Spec(val first: IntArray, val second: IntArray, val secondIsA: Boolean)

private class PastDuel(
    val axis: Int,
    val first: IntArray,
    val second: IntArray,
    val secondWasA: Boolean,
    val secondWon: Boolean,
)

private class Board(axes: List<EqFinderAxis>) {
    val est = IntArray(axes.size) { 0.coerceIn(axes[it].minDb10, axes[it].maxDb10) }
    var asked = 0
    var expected = 0
    var consistencyWarning = false
    var validated = false
    var startBeaten: Boolean? = null
    val history = ArrayList<PastDuel>()

    fun bump(extra: Int) {
        expected = (expected + extra).coerceAtMost(EqFinderSession.MAX_TRIALS)
    }

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

private enum class Outcome { WON, LOST, SAME, BLOCKED }

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

private fun initialForecast(axisCount: Int, startStep: Int): Int =
    (axisCount * axisForecast(startStep) + 2 + probeCount(axisCount) + 1 + probeCount(axisCount))
        .coerceAtMost(EqFinderSession.MAX_TRIALS)

private fun explorationProgram(
    axes: List<EqFinderAxis>,
    seed: Long,
    startStep: Int,
    feed: Feed,
    board: Board,
): Sequence<Spec> = sequence {
    val ctx = Ctx(axes, Rng(seed), feed, board)
    board.expected = initialForecast(axes.size, startStep)

    for (j in axes.indices) exploreAxis(ctx, j, startStep)

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

    runConsistencyProbes(ctx)

    runValidation(ctx)
    ctx.board.validated = true
}

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

private suspend fun kotlin.sequences.SequenceScope<Spec>.exploreAxis(
    ctx: Ctx,
    j: Int,
    startStep: Int,
) {
    var step = startStep.coerceAtLeast(MIN_STEP_DB10)
    var dir = 1
    var sameStreak = 0
    var reversalsAtMin = 0

    fun shrink(): Boolean {
        if (step == MIN_STEP_DB10) return false
        step = (step / 2).coerceAtLeast(MIN_STEP_DB10)
        return true
    }

    while (true) {
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
        if (!shrink()) return
    }
}

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

private suspend fun kotlin.sequences.SequenceScope<Spec>.validateAgainstStart(ctx: Ctx) {
    if (ctx.board.est.all { it == 0 }) return
    val first = duel(ctx, ctx.board.est.copyOf(), IntArray(ctx.axes.size))
    ctx.board.startBeaten = when (first) {
        false -> true
        true -> false
        null -> duel(ctx, ctx.board.est.copyOf(), IntArray(ctx.axes.size)) == false
    }
}

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
            if (target == ctx.board.est[j]) continue
            val result = ctx.board.est.copyOf()
            val perturbed = result.copyOf().also { it[j] = target }
            val won = duel(ctx, result, perturbed)
            if (won == true && reopensLeft > 0) {
                reopensLeft--
                ctx.board.est[j] = target
                ctx.board.bump(axisForecast(MIN_STEP_DB10) + 1 + probes)
                exploreAxis(ctx, j, MIN_STEP_DB10)
                reopened = true
                break
            }
        }
        if (!reopened) return
    }
}

object EqFinderMaterialize {

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

    fun candidateBands(
        baseBands: List<EqBand>,
        axes: List<EqFinderAxis>,
        overlayDb10: List<Int>,
    ): List<EqBand> = baseBands + axisBands(axes, overlayDb10)

    fun bake(
        settings: EqSettings,
        axes: List<EqFinderAxis>,
        overlayDb10: List<Int>,
        preampDb10: Int,
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
                    settings.copy(bands = bands, enabled = true, preampDb10 = preampDb10)
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
                    preampDb10 = preampDb10,
                )
            }
        }
    }
}
