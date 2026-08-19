package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqFinderAxis
import io.github.mame1839.codecanchor.core.EqFinderMaterialize
import io.github.mame1839.codecanchor.core.EqFinderSession
import io.github.mame1839.codecanchor.core.EqFinderSession.Answer
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class EqFinderTest {

    private fun l1(v: List<Int>, pref: IntArray): Int =
        v.indices.sumOf { abs(v[it] - pref[it]) }

    private fun l1Answer(t: EqFinderSession.Trial, pref: IntArray, thresholdDb10: Int): Answer {
        val da = l1(t.aOverlayDb10, pref)
        val db = l1(t.bOverlayDb10, pref)
        return when {
            abs(da - db) < thresholdDb10 -> Answer.SAME
            da < db -> Answer.A
            else -> Answer.B
        }
    }

    private fun runToEnd(
        start: EqFinderSession,
        guard: Int = 200,
        policy: (EqFinderSession.Trial, EqFinderSession) -> Answer,
    ): Pair<EqFinderSession, List<EqFinderSession.Trial>> {
        var s = start
        val trials = ArrayList<EqFinderSession.Trial>()
        var i = 0
        while (!s.finished) {
            val t = checkNotNull(s.currentTrial()) { "finished でないのに試行が無い" }
            trials += t
            s = s.answered(policy(t, s))
            check(++i <= guard) { "ガード ($guard) を超えた" }
        }
        return s to trials
    }

    @Test
    fun simulatedUserConvergesWithinBudget() {
        for ((pref, seed) in listOf(intArrayOf(60, -40) to 3L, intArrayOf(47, -12) to 11L)) {
            val (s, _) = runToEnd(
                EqFinderSession.start(EqFinderAxes.default(false), seed),
            ) { t, _ -> l1Answer(t, pref, 5) }
            val r = s.result()
            assertTrue("好み ${pref.toList()}: ${s.progress().first} 試行", s.progress().first <= 40)
            assertTrue(s.finished)
            assertTrue(r.validated)
            assertFalse(r.consistencyWarning)
            assertEquals(true, r.startBeatenInValidation)
            r.overlayDb10.forEachIndexed { i, est ->
                assertTrue(
                    "軸 $i: 推定 $est 好み ${pref[i]}",
                    abs(est - pref[i]) <= 10,
                )
            }
        }
    }

    @Test
    fun threeAxesFinishWellBelowTheHardCap() {
        val pref = intArrayOf(40, -30, 20)
        val (s, _) = runToEnd(
            EqFinderSession.start(EqFinderAxes.default(true), seed = 7),
        ) { t, _ -> l1Answer(t, pref, 5) }
        assertTrue("実際: ${s.progress().first}", s.progress().first <= 45)
        assertTrue(s.result().validated)
    }

    @Test
    fun sessionIsDeterministicAndSurvivesJsonRoundTrip() {
        val pref = intArrayOf(47, -12)
        val axes = EqFinderAxes.default(false)
        var straight = EqFinderSession.start(axes, seed = 9)
        var roundTripped = EqFinderSession.fromJson(straight.toJson())!!
        val answers = ArrayList<Answer>()
        val trials = ArrayList<EqFinderSession.Trial>()
        var guard = 0
        while (!straight.finished) {
            assertEquals(straight.currentTrial(), roundTripped.currentTrial())
            assertEquals(straight.progress(), roundTripped.progress())
            val t = straight.currentTrial()!!
            trials += t
            val a = l1Answer(t, pref, 5)
            answers += a
            straight = straight.answered(a)
            roundTripped = EqFinderSession.fromJson(roundTripped.toJson())!!.answered(a)
            check(++guard <= 200)
        }
        assertTrue(roundTripped.finished)
        assertEquals(straight.result(), roundTripped.result())
        assertEquals(straight.toJson().toString(), roundTripped.toJson().toString())

        var replayed = EqFinderSession.start(axes, seed = 9)
        trials.forEachIndexed { i, expected ->
            assertEquals("試行 $i", expected, replayed.currentTrial())
            replayed = replayed.answered(answers[i])
        }
        assertEquals(straight.result(), replayed.result())
    }

    @Test
    fun abAssignmentDependsOnSeedAndIsReproducible() {
        val axes = EqFinderAxes.default(false)
        val challengerSides = (0L until 20L).map { seed ->
            val t = EqFinderSession.start(axes, seed).currentTrial()!!
            assertEquals(
                setOf(listOf(0, 0), listOf(40, 0)),
                setOf(t.aOverlayDb10, t.bOverlayDb10),
            )
            t.aOverlayDb10 == listOf(40, 0)
        }
        assertTrue("挑戦側が A になる seed が無い", challengerSides.any { it })
        assertTrue("挑戦側が B になる seed が無い", challengerSides.any { !it })

        val first = EqFinderSession.start(axes, seed = 5).answered(Answer.SAME).currentTrial()
        val second = EqFinderSession.start(axes, seed = 5).answered(Answer.SAME).currentTrial()
        assertEquals(first, second)
    }

    @Test
    fun sameAnswersHalveTheStepAndLossesResetTheStreak() {
        fun pairOf(t: EqFinderSession.Trial) = setOf(t.aOverlayDb10, t.bOverlayDb10)
        var s = EqFinderSession.start(listOf(EqFinderAxes.BASS), seed = 1)

        assertEquals(setOf(listOf(0), listOf(40)), pairOf(s.currentTrial()!!))
        s = s.answered(Answer.SAME)
        assertEquals(setOf(listOf(0), listOf(-40)), pairOf(s.currentTrial()!!))
        s = s.answered(Answer.SAME)
        assertEquals(setOf(listOf(0), listOf(20)), pairOf(s.currentTrial()!!))

        val incumbent = if (s.currentTrial()!!.aOverlayDb10 == listOf(0)) Answer.A else Answer.B
        s = s.answered(incumbent)
        assertEquals(setOf(listOf(0), listOf(-20)), pairOf(s.currentTrial()!!))
        s = s.answered(Answer.SAME)
        assertEquals(setOf(listOf(0), listOf(10)), pairOf(s.currentTrial()!!))
    }

    @Test
    fun adversarialUserStopsAtTheHardCap() {
        var s = EqFinderSession.start(EqFinderAxes.default(false), seed = 13)
        var lastExpected = 0
        var guard = 0
        while (!s.finished) {
            val (done, expected) = s.progress()
            assertTrue("見込みが縮んだ: $lastExpected -> $expected", expected >= lastExpected)
            assertTrue("見込みが上限超え: $expected", expected <= EqFinderSession.MAX_TRIALS)
            assertTrue(expected >= done)
            lastExpected = expected
            val t = s.currentTrial()!!
            val current = s.result().overlayDb10
            s = s.answered(if (t.aOverlayDb10 == current) Answer.B else Answer.A)
            check(++guard <= 200)
        }
        assertEquals(EqFinderSession.MAX_TRIALS, s.progress().first)
        assertTrue(s.finished)
        assertNull(s.currentTrial())
        assertFalse("上限打ち切りは validated にならない", s.result().validated)
        assertSame("finished 後の answered は何もしない", s, s.answered(Answer.A))
    }

    @Test
    fun perturbationWinsAreBoundedByTheReopenBudget() {
        val pref = intArrayOf(15, 15)
        var perturbationWins = 0
        val (s, _) = runToEnd(
            EqFinderSession.start(EqFinderAxes.default(false), seed = 21),
        ) { t, session ->
            val r = session.result()
            val diffAxes = t.aOverlayDb10.indices.count { t.aOverlayDb10[it] != t.bOverlayDb10[it] }
            val diffSum = t.aOverlayDb10.indices.sumOf { abs(t.aOverlayDb10[it] - t.bOverlayDb10[it]) }
            val perturbation = r.startBeatenInValidation != null && diffAxes == 1 && diffSum == 10
            if (perturbation) {
                perturbationWins++
                if (t.aOverlayDb10 == r.overlayDb10) Answer.B else Answer.A
            } else {
                l1Answer(t, pref, 5)
            }
        }
        assertTrue("摂動が 1 度も勝っていない", perturbationWins >= 2)
        assertTrue(s.finished)
        assertTrue(s.progress().first <= EqFinderSession.MAX_TRIALS)
        val r = s.result()
        assertTrue("再開込みでも検証段を完走する", r.validated)
        assertFalse(r.consistencyWarning)
        assertEquals(listOf(15, 15), r.overlayDb10)
    }

    @Test
    fun degenerateAxesFinishWithoutAnyTrial() {
        val axes = listOf(
            EqFinderAxes.BASS.copy(minDb10 = 0, maxDb10 = 0),
            EqFinderAxes.TREBLE.copy(minDb10 = 0, maxDb10 = 0),
        )
        val s = EqFinderSession.start(axes, seed = 1)
        assertTrue(s.finished)
        assertNull(s.currentTrial())
        assertEquals(0, s.progress().first)
        val r = s.result()
        assertEquals(listOf(0, 0), r.overlayDb10)
        assertTrue(r.validated)
        assertNull(r.startBeatenInValidation)
        assertSame(s, s.answered(Answer.A))
    }

    @Test
    fun contradictionsRaiseTheConsistencyWarning() {
        val pref = intArrayOf(37, -43)
        val seen = HashSet<Set<List<Int>>>()
        val (s, _) = runToEnd(
            EqFinderSession.start(EqFinderAxes.default(false), seed = 17),
        ) { t, _ ->
            val key = setOf(t.aOverlayDb10, t.bOverlayDb10)
            val isDuplicate = key in seen
            if (!isDuplicate) seen += key
            val normal = l1Answer(t, pref, 0)
            if (!isDuplicate) {
                normal
            } else {
                when (normal) {
                    Answer.A -> Answer.B
                    Answer.B -> Answer.A
                    Answer.SAME -> Answer.SAME
                }
            }
        }
        assertTrue(s.finished)
        assertTrue(s.progress().first <= EqFinderSession.MAX_TRIALS)
        assertTrue("矛盾が警告になっていない", s.result().consistencyWarning)
    }

    @Test
    fun estimatesAndCandidatesStayInsideTheAxisRange() {
        val axes = listOf(
            EqFinderAxes.BASS.copy(minDb10 = -60, maxDb10 = 60),
            EqFinderAxes.TREBLE.copy(minDb10 = -60, maxDb10 = 60),
        )
        val (s, trials) = runToEnd(EqFinderSession.start(axes, seed = 2)) { t, _ ->
            val sa = t.aOverlayDb10.sum()
            val sb = t.bOverlayDb10.sum()
            if (sa >= sb) Answer.A else Answer.B
        }
        for (t in trials) {
            (t.aOverlayDb10 + t.bOverlayDb10).forEach {
                assertTrue("範囲外の候補 $it", it in -60..60)
            }
        }
        assertEquals(listOf(60, 60), s.result().overlayDb10)
        assertTrue(s.result().validated)
    }

    @Test
    fun resultIsAvailableMidSession() {
        val s0 = EqFinderSession.start(EqFinderAxes.default(false), seed = 5)
        val r0 = s0.result()
        assertEquals(listOf(0, 0), r0.overlayDb10)
        assertFalse(r0.validated)
        assertFalse(r0.consistencyWarning)
        assertNull(r0.startBeatenInValidation)
        assertEquals(0, s0.progress().first)
        assertTrue(s0.progress().second > 0)

        val t = s0.currentTrial()!!
        val s1 = s0.answered(if (t.aOverlayDb10 == listOf(40, 0)) Answer.A else Answer.B)
        assertEquals(listOf(40, 0), s1.result().overlayDb10)
    }

    @Test
    fun fromJsonRejectsBrokenInput() {
        val base = EqFinderSession.start(EqFinderAxes.default(false), seed = 42)
            .answered(Answer.A).answered(Answer.SAME)
        val text = base.toJson().toString()

        fun mutate(block: (JSONObject) -> Unit): EqFinderSession? {
            val o = JSONObject(text)
            block(o)
            return EqFinderSession.fromJson(o)
        }

        assertNotNull(EqFinderSession.fromJson(JSONObject(text)))
        assertEquals(base.currentTrial(), EqFinderSession.fromJson(JSONObject(text))!!.currentTrial())

        assertNull("seed 欠落", mutate { it.remove("seed") })
        assertNull("ans 欠落", mutate { it.remove("ans") })
        assertNull("不明な回答文字", mutate { it.put("ans", "AXB") })
        assertNull("回答が上限超え", mutate { it.put("ans", "A".repeat(EqFinderSession.MAX_TRIALS + 1)) })
        assertNull("軸が空", mutate { it.put("axes", JSONArray()) })
        assertNull("未知の版", mutate { it.put("v", 2) })
        assertNull(
            "範囲が逆の軸",
            mutate { it.getJSONArray("axes").getJSONObject(0).put("lo", 50).put("hi", -50) },
        )
    }

    @Test
    fun candidateBandsKeepTheStructureAcrossCandidates() {
        val baseBands = listOf(
            EqBand(freqHz = 200, q100 = 141, gainDb10 = 25),
            EqBand(freqHz = 4_000, q100 = 200, gainDb10 = -30),
        )
        val axes = EqFinderAxes.default(true)
        val a = EqFinderMaterialize.candidateBands(baseBands, axes, listOf(0, 0, 0))
        val b = EqFinderMaterialize.candidateBands(baseBands, axes, listOf(40, -20, 10))
        assertEquals(baseBands.size + axes.size, a.size)
        assertEquals(a.size, b.size)
        a.zip(b).forEachIndexed { i, (x, y) ->
            assertEquals("freq $i", x.freqHz, y.freqHz)
            assertEquals("q $i", x.q100, y.q100)
            assertEquals("type $i", x.type, y.type)
        }
        assertEquals(baseBands, a.take(baseBands.size))
        assertEquals(baseBands, b.take(baseBands.size))
        assertEquals(listOf(0, 0, 0), a.drop(baseBands.size).map { it.gainDb10 })
        assertEquals(listOf(40, -20, 10), b.drop(baseBands.size).map { it.gainDb10 })
        assertEquals(
            listOf(EqBandType.LOW_SHELF, EqBandType.HIGH_SHELF, EqBandType.PEAKING),
            a.drop(baseBands.size).map { it.type },
        )
    }

    private val carriedPreampDb10 = -77

    @Test
    fun bakedGraphicMatchesTheBaseResponsePlusTheOverlay() {
        val freqs = EqSolver.centerFrequencies(10)
        val target = doubleArrayOf(3.0, -2.0, 4.0, 0.0, -5.0, 2.0, 0.0, 1.0, -1.0, 2.0)
        val base = EqSettings(
            enabled = true,
            mode = EqMode.GRAPHIC,
            bandCount = 10,
            bands = EqSolver.solveBands(target, freqs, EqSolver.defaultQ(10)),
        )
        val axes = EqFinderAxes.default(false)
        val overlay = listOf(60, -40)
        val overlayBands = EqFinderMaterialize.candidateBands(emptyList(), axes, overlay)

        val baked = EqFinderMaterialize.bake(base, axes, overlay, carriedPreampDb10)
        assertNotNull(baked)
        assertTrue(baked!!.enabled)
        assertEquals("渡したプリアンプが落ちた", carriedPreampDb10, baked.preampDb10)
        assertEquals(EqMode.GRAPHIC, baked.mode)
        assertEquals(10, baked.bands.size)
        for (hz in freqs) {
            val expect = EqSolver.combinedResponseDb(base.bands, hz.toDouble()) +
                EqSolver.combinedResponseDb(overlayBands, hz.toDouble())
            val got = EqSolver.combinedResponseDb(baked.bands, hz.toDouble())
            assertEquals("バンド $hz Hz", expect, got, 0.2)
        }
    }

    @Test
    fun bakedGraphicIgnoresDisabledBandsLikeTheAuditionDid() {
        val freqs = EqSolver.centerFrequencies(10)
        val old = doubleArrayOf(8.0, 6.0, 4.0, 0.0, -5.0, 2.0, 0.0, 1.0, -1.0, 2.0)
        val base = EqSettings(
            enabled = false,
            mode = EqMode.GRAPHIC,
            bandCount = 10,
            bands = EqSolver.solveBands(old, freqs, EqSolver.defaultQ(10)),
        )
        val axes = EqFinderAxes.default(false)
        val overlay = listOf(60, -40)
        val overlayBands = EqFinderMaterialize.candidateBands(emptyList(), axes, overlay)

        val baked = EqFinderMaterialize.bake(base, axes, overlay, carriedPreampDb10)
        assertNotNull(baked)
        for (hz in freqs) {
            val heard = EqSolver.combinedResponseDb(overlayBands, hz.toDouble())
            val got = EqSolver.combinedResponseDb(baked!!.bands, hz.toDouble())
            assertEquals("バンド $hz Hz (試聴した音と違う音が保存される)", heard, got, 0.2)
        }
        val atLow = EqSolver.combinedResponseDb(base.bands, 63.0) +
            EqSolver.combinedResponseDb(overlayBands, 63.0)
        assertTrue(
            "土台のカーブが平ら過ぎて新旧の挙動が区別できていない",
            kotlin.math.abs(atLow - EqSolver.combinedResponseDb(baked!!.bands, 63.0)) > 2.0,
        )
    }

    @Test
    fun bakedGraphicFromEmptyBandsRealizesTheOverlay() {
        val axes = EqFinderAxes.default(false)
        val overlay = listOf(60, -40)
        val overlayBands = EqFinderMaterialize.candidateBands(emptyList(), axes, overlay)
        assertTrue(EqSolver.combinedResponseDb(overlayBands, 50.0) > 4.0)
        assertTrue(EqSolver.combinedResponseDb(overlayBands, 10_000.0) < -3.0)
        val baked = EqFinderMaterialize.bake(
            EqSettings(mode = EqMode.GRAPHIC, bandCount = 10),
            axes,
            overlay,
            carriedPreampDb10,
        )
        assertNotNull(baked)
        for (hz in EqSolver.centerFrequencies(10)) {
            val expect = EqSolver.combinedResponseDb(overlayBands, hz.toDouble())
            val got = EqSolver.combinedResponseDb(baked!!.bands, hz.toDouble())
            assertEquals("バンド $hz Hz", expect, got, 0.2)
        }
    }

    @Test
    fun bakeRefusesWhenTheParametricBandsAreFull() {
        fun parametric(count: Int) = EqSettings(
            enabled = false,
            mode = EqMode.PARAMETRIC,
            bands = List(count) { EqBand(freqHz = 50 + it * 100, q100 = 141, gainDb10 = 10) },
        )
        val axes = EqFinderAxes.default(false)

        assertNull(EqFinderMaterialize.bake(parametric(31), axes, listOf(40, 0), carriedPreampDb10))

        val fit = EqFinderMaterialize.bake(parametric(30), axes, listOf(40, 0), carriedPreampDb10)
        assertNotNull(fit)
        assertEquals(31, fit!!.bands.size)
        assertTrue(fit.enabled)
        assertEquals("渡したプリアンプが落ちた", carriedPreampDb10, fit.preampDb10)
        val added = fit.bands.last()
        assertEquals(EqFinderAxes.BASS.freqHz, added.freqHz)
        assertEquals(EqFinderAxes.BASS.q100, added.q100)
        assertEquals(EqBandType.LOW_SHELF, added.type)
        assertEquals(40, added.gainDb10)
        fit.bands.dropLast(1).forEachIndexed { i, band ->
            assertEquals("fc $i が変わった", 50 + i * 100, band.freqHz)
            assertEquals("q $i が変わった", 141, band.q100)
            assertEquals("切ってあった旧ゲインが復活した", 0, band.gainDb10)
        }

        val unchanged = EqFinderMaterialize.bake(parametric(31), axes, listOf(0, 0), carriedPreampDb10)
        assertNotNull(unchanged)
        assertEquals(31, unchanged!!.bands.size)
        assertTrue(unchanged.enabled)
        assertTrue(unchanged.bands.all { it.gainDb10 == 0 })
    }

    @Test
    fun bakedParametricKeepsEnabledBandsIntact() {
        val bands = listOf(
            EqBand(freqHz = 200, q100 = 141, gainDb10 = 25),
            EqBand(freqHz = 4_000, q100 = 200, gainDb10 = -30),
        )
        val base = EqSettings(enabled = true, mode = EqMode.PARAMETRIC, bands = bands)
        val baked = EqFinderMaterialize.bake(base, EqFinderAxes.default(false), listOf(40, -20), carriedPreampDb10)
        assertNotNull(baked)
        assertEquals(bands, baked!!.bands.take(2))
        assertEquals(listOf(40, -20), baked.bands.drop(2).map { it.gainDb10 })
    }
}
