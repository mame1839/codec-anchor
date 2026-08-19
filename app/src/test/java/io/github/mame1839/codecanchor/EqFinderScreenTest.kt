package io.github.mame1839.codecanchor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.EqFinderAnswer
import io.github.mame1839.codecanchor.ui.EqFinderAxisDelta
import io.github.mame1839.codecanchor.ui.EqFinderAxisKind
import io.github.mame1839.codecanchor.ui.EqFinderBandChoice
import io.github.mame1839.codecanchor.ui.EqFinderCandidate
import io.github.mame1839.codecanchor.ui.EqFinderIntroUi
import io.github.mame1839.codecanchor.ui.EqFinderIntroContent
import io.github.mame1839.codecanchor.ui.EqFinderPause
import io.github.mame1839.codecanchor.ui.EqFinderResultContent
import io.github.mame1839.codecanchor.ui.EqFinderResultUi
import io.github.mame1839.codecanchor.ui.EqFinderResumeBlocked
import io.github.mame1839.codecanchor.ui.EqFinderResumeUi
import io.github.mame1839.codecanchor.ui.EqFinderTrialContent
import io.github.mame1839.codecanchor.ui.EqFinderTrialUi
import io.github.mame1839.codecanchor.ui.drawEqFinderCurves
import io.github.mame1839.codecanchor.ui.eqCountText
import io.github.mame1839.codecanchor.ui.eqFinderPlotRange
import io.github.mame1839.codecanchor.ui.eqFinderResponseDb
import io.github.mame1839.codecanchor.ui.eqFinderTimeText
import io.github.mame1839.codecanchor.ui.eqGainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.math.exp
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqFinderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private fun showTrial(
        ui: EqFinderTrialUi,
        onListen: (EqFinderCandidate) -> Unit = {},
        onAnswer: (EqFinderAnswer) -> Unit = {},
        onResumePlayback: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderTrialContent(
                        ui = ui,
                        onListen = onListen,
                        onAnswer = onAnswer,
                        onFinishNow = {},
                        onSuspend = {},
                        onResumePlayback = onResumePlayback,
                    )
                }
            }
        }
    }

    private val trial = EqFinderTrialUi(
        done = 3,
        total = 30,
        selected = EqFinderCandidate.A,
        bothHeard = false,
    )

    @Test
    fun answersUnlockOnlyAfterBothCandidatesWereHeard() {
        var answered: EqFinderAnswer? = null
        val ui = mutableStateOf(trial)
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderTrialContent(
                        ui = ui.value,
                        onListen = {},
                        onAnswer = { answered = it },
                        onFinishNow = {},
                        onSuspend = {},
                        onResumePlayback = {},
                    )
                }
            }
        }

        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_same).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_b).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_locked).assertExists()

        compose.runOnIdle { ui.value = trial.copy(bothHeard = true) }
        onText(R.string.eq_finder_answer_b).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(EqFinderAnswer.B, answered)
    }

    @Test
    fun listeningTapsSwitchTheCandidate() {
        var listened: EqFinderCandidate? = null
        showTrial(trial, onListen = { listened = it })
        compose.onNodeWithText(string(R.string.eq_finder_b)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(EqFinderCandidate.B, listened)
    }

    @Test
    fun pausesBlockAnsweringAndOfferResumeOnlyForFocusLoss() {
        var resumed = false
        showTrial(
            trial.copy(bothHeard = true, pause = EqFinderPause.FOCUS_LOST),
            onResumePlayback = { resumed = true },
        )
        onText(R.string.eq_finder_answer_same).assertIsNotEnabled()
        onText(R.string.eq_finder_paused_focus).assertExists()
        onText(R.string.eq_finder_resume_playback).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(resumed)
    }

    @Test
    fun disconnectionShowsItsOwnNoticeWithoutAResumeButton() {
        showTrial(trial.copy(bothHeard = true, pause = EqFinderPause.DISCONNECTED))
        onText(R.string.eq_finder_paused_disconnected).assertExists()
        compose.onNodeWithText(string(R.string.eq_finder_resume_playback)).assertDoesNotExist()
        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
    }

    @Test
    fun theNoMusicPauseHasNoResumeButton() {
        showTrial(trial.copy(bothHeard = true, pause = EqFinderPause.NO_MUSIC))
        onText(R.string.eq_finder_paused_no_music).assertExists()
        compose.onNodeWithText(string(R.string.eq_finder_resume_playback)).assertDoesNotExist()
        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
    }

    private fun showIntro(
        ui: EqFinderIntroUi,
        onMaterial: (Boolean) -> Unit = {},
        onBegin: () -> Unit = {},
        onResume: () -> Unit = {},
        onStartOver: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderIntroContent(
                        ui = ui,
                        onMaterial = onMaterial,
                        onPickSong = {},
                        onStartMs = {},
                        onStartMsChosen = {},
                        onPreviewToggle = {},
                        onIncludeMid = {},
                        onFineTune = {},
                        onBegin = onBegin,
                        onResume = onResume,
                        onStartOver = onStartOver,
                    )
                }
            }
        }
    }

    @Test
    fun startIsGatedOnTheSongAndTheConnection() {
        var begun = false
        showIntro(
            EqFinderIntroUi(
                songName = "track.flac",
                loaded = true,
                trackDurationMs = 180_000,
                startBlockedReason = "connect first",
            ),
            onBegin = { begun = true },
        )
        onText(R.string.eq_finder_begin).assertIsNotEnabled()
        compose.onNodeWithText("connect first").assertExists()
        assertTrue(!begun)
    }

    @Test
    fun startBecomesPressableOnceASongIsLoaded() {
        var begun = false
        showIntro(
            EqFinderIntroUi(songName = "track.flac", loaded = true, trackDurationMs = 180_000),
            onBegin = { begun = true },
        )
        onText(R.string.eq_finder_begin).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(begun)
    }

    @Test
    fun aSongIsRequiredBeforeStarting() {
        showIntro(EqFinderIntroUi())
        onText(R.string.eq_finder_begin).assertIsNotEnabled()
    }

    @Test
    fun theLiveMaterialNeedsNoSong() {
        var begun = false
        showIntro(EqFinderIntroUi(materialLive = true), onBegin = { begun = true })
        compose.onNodeWithText(string(R.string.eq_finder_song)).assertDoesNotExist()
        onText(R.string.eq_finder_material_live_desc).assertExists()
        onText(R.string.eq_finder_begin).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(begun)
    }

    @Test
    fun theLiveMaterialIsGatedOnPlayingMusic() {
        showIntro(
            EqFinderIntroUi(
                materialLive = true,
                startBlockedReason = "play music first",
            ),
        )
        onText(R.string.eq_finder_begin).assertIsNotEnabled()
        compose.onNodeWithText("play music first").assertExists()
    }

    @Test
    fun choosingTheLiveMaterialShowsItsPrecisionNote() {
        var chosen: Boolean? = null
        showIntro(EqFinderIntroUi(), onMaterial = { chosen = it })
        onText(R.string.eq_finder_material).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(
            hasText(string(R.string.eq_finder_material_live_desc)) and hasAnyAncestor(isDialog()),
        ).assertExists()
        compose.onNode(
            hasText(string(R.string.eq_finder_material_live)) and hasAnyAncestor(isDialog()),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, chosen)
    }

    @Test
    fun fineTuneIsOfferedOnlyWhenThereIsSomethingToRefine() {
        showIntro(EqFinderIntroUi(fineTuneVisible = false))
        compose.onNodeWithText(string(R.string.eq_finder_fine_tune)).assertDoesNotExist()
    }

    @Test
    fun resumeOffersContinueAndConfirmsStartingOver() {
        var resumed = false
        var startedOver = false
        showIntro(
            EqFinderIntroUi(resume = EqFinderResumeUi(done = 17)),
            onResume = { resumed = true },
            onStartOver = { startedOver = true },
        )
        compose.onNodeWithText(string(R.string.eq_finder_song)).assertDoesNotExist()

        onText(R.string.action_continue).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(resumed)

        onText(R.string.eq_finder_start_over).performSemanticsAction(SemanticsActions.OnClick)
        onText(R.string.eq_finder_start_over_title).assertExists()
        assertTrue(!startedOver)
        compose.onNode(
            hasText(string(R.string.action_delete)) and hasAnyAncestor(isDialog()),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(startedOver)
    }

    @Test
    fun aResumeBlockedByChangedSettingsOnlyOffersStartingOver() {
        showIntro(
            EqFinderIntroUi(
                resume = EqFinderResumeUi(done = 5, blocked = EqFinderResumeBlocked.SETTINGS_CHANGED),
            ),
        )
        onText(R.string.eq_finder_resume_blocked_settings).assertExists()
        compose.onNodeWithText(string(R.string.action_continue)).assertDoesNotExist()
        onText(R.string.eq_finder_start_over).assertExists()
    }

    @Test
    fun aResumeBlockedByAChangedSongOnlyOffersStartingOver() {
        showIntro(
            EqFinderIntroUi(
                resume = EqFinderResumeUi(done = 5, blocked = EqFinderResumeBlocked.SONG_CHANGED),
            ),
        )
        onText(R.string.eq_finder_resume_blocked_song).assertExists()
        compose.onNodeWithText(string(R.string.action_continue)).assertDoesNotExist()
    }

    private fun result(bandChoices: List<EqFinderBandChoice>? = null) = EqFinderResultUi(
        axes = listOf(
            EqFinderAxisDelta(EqFinderAxisKind.BASS, 25),
            EqFinderAxisDelta(EqFinderAxisKind.TREBLE, -10),
        ),
        beforeDb = DoubleArray(8),
        afterDb = DoubleArray(8) { 3.0 },
        consistencyWarning = true,
        startBeaten = true,
        bandChoices = bandChoices,
    )

    private fun showResult(
        ui: EqFinderResultUi,
        selectedBandCount: Int = 10,
        onBandCount: (Int) -> Unit = {},
        bakeFailed: Boolean = false,
        onApply: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderResultContent(
                        ui = ui,
                        selectedBandCount = selectedBandCount,
                        onBandCount = onBandCount,
                        bakeFailed = bakeFailed,
                        onApply = onApply,
                        onDiscard = onDiscard,
                    )
                }
            }
        }
    }

    @Test
    fun discardingNeedsConfirmation() {
        var discarded = false
        showResult(result(), onDiscard = { discarded = true })
        onText(R.string.eq_finder_discard).performSemanticsAction(SemanticsActions.OnClick)
        onText(R.string.eq_finder_discard_title).assertExists()
        assertTrue("確認の前に破棄が走った", !discarded)
        compose.onNode(
            hasText(string(R.string.eq_finder_discard)) and hasAnyAncestor(isDialog()),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(discarded)
    }

    @Test
    fun applyingAsksNothingFurther() {
        var applied = false
        showResult(result(), onApply = { applied = true })
        onText(R.string.eq_finder_apply).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(applied)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun theHonestLinesAreShown() {
        showResult(result(), bakeFailed = true)
        onText(R.string.eq_finder_validated_win).assertExists()
        onText(R.string.eq_finder_consistency).assertExists()
        onText(R.string.eq_finder_bake_failed).assertExists()
    }

    @Test
    fun theBandCountRowShowsFidelityPerChoice() {
        var chosen: Int? = null
        showResult(
            result(
                bandChoices = listOf(
                    EqFinderBandChoice(5, 21),
                    EqFinderBandChoice(10, 6),
                    EqFinderBandChoice(15, 3),
                    EqFinderBandChoice(31, 4),
                ),
            ),
            onBandCount = { chosen = it },
        )
        onText(R.string.eq_band_count).performSemanticsAction(SemanticsActions.OnClick)
        val unit = RuntimeEnvironment.getApplication().getString(R.string.eq_unit_db)
        compose.onNode(
            hasText(string(R.string.eq_finder_band_error, eqGainText(21, unit, signed = false))) and
                hasAnyAncestor(isDialog()),
        ).assertExists()
        compose.onNode(
            hasText(eqCountText(31)) and hasAnyAncestor(isDialog()),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(31, chosen)
    }

    @Test
    fun parametricResultsHaveNoBandCountRow() {
        showResult(result())
        compose.onNodeWithText(string(R.string.eq_band_count)).assertDoesNotExist()
    }

    private fun onText(id: Int) = compose.onNodeWithText(string(id))

    @Test
    fun responseSamplingMatchesTheSolver() {
        val bands = listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -25, type = EqBandType.HIGH_SHELF),
        )
        val samples = eqFinderResponseDb(bands, 16)
        samples.indices.forEach { i ->
            val hz = exp(ln(20.0) + ln(20_000.0 / 20.0) * i / 15)
            assertEquals(EqSolver.combinedResponseDb(bands, hz), samples[i], 1e-9)
        }
        assertTrue("バンド無しは 0 dB の平ら", eqFinderResponseDb(emptyList(), 8).all { it == 0.0 })
    }

    @Test
    fun theCurveRangeGrowsInStepsAndHoldsBothCurves() {
        assertEquals(12.0, eqFinderPlotRange(DoubleArray(4), DoubleArray(4) { 3.0 }), 0.0)
        assertEquals(18.0, eqFinderPlotRange(DoubleArray(4) { -15.0 }, DoubleArray(4)), 0.0)
        assertEquals(40.0, eqFinderPlotRange(DoubleArray(4), DoubleArray(4) { 99.0 }), 0.0)
    }

    @Test
    fun timeTextUsesMinutesAndTwoDigitSeconds() {
        assertEquals("\u20680:00\u2069", eqFinderTimeText(0))
        assertEquals("\u20680:20\u2069", eqFinderTimeText(20_000))
        assertEquals("\u20681:01\u2069", eqFinderTimeText(61_000))
        assertEquals("\u206812:05\u2069", eqFinderTimeText(725_400))
    }

    private val density = Density(3f)
    private val measurer by lazy {
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)
    }

    private val accent = Color.Red

    private val muted = Color.Blue

    private val labelStyle = TextStyle(fontSize = 10.sp, color = Color.Green)

    @Test
    fun bothCurvesAndTheGridArePainted() {
        val before = DoubleArray(32)
        val after = DoubleArray(32) { 6.0 }
        val range = eqFinderPlotRange(before, after)
        val yTicks = listOf(range, 0.0, -range).map { it to measurer.measure("+12", labelStyle) }
        val xTicks = listOf(0.2f to measurer.measure("1k", labelStyle))

        val width = 600
        val height = 480
        val pixels = ImageBitmap(width, height).also { bitmap ->
            CanvasDrawScope().draw(
                density,
                LayoutDirection.Ltr,
                Canvas(bitmap),
                Size(width.toFloat(), height.toFloat()),
            ) {
                drawEqFinderCurves(before, after, range, yTicks, xTicks, 30f, accent, muted)
            }
        }.toPixelMap()

        var accentYSum = 0L
        var accentCount = 0
        var beforeYSum = 0L
        var beforeCount = 0
        var gridRows = 0
        for (y in 0 until height) {
            var gridHit = false
            for (x in 0 until width) {
                val c = pixels[x, y]
                if (c.red > 0.5f && c.green < 0.3f && c.blue < 0.3f) {
                    accentYSum += y
                    accentCount++
                }
                if (c.blue > 0.5f && c.red < 0.3f) {
                    if (c.alpha > 0.9f) {
                        beforeYSum += y
                        beforeCount++
                    } else if (c.alpha > 0.05f) {
                        gridHit = true
                    }
                }
            }
            if (gridHit) gridRows++
        }

        assertTrue("適用後の曲線が描かれていない", accentCount > 0)
        assertTrue("適用前の曲線が描かれていない", beforeCount > 0)
        assertTrue("目盛り線が 1 本も無い", gridRows >= 3)
        assertTrue(
            "持ち上げた後の曲線が前より下に描かれている",
            accentYSum / accentCount < beforeYSum / beforeCount,
        )
    }
}
