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
import androidx.compose.ui.test.performTextInput
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
import io.github.mame1839.codecanchor.ui.EqFinderCandidate
import io.github.mame1839.codecanchor.ui.EqFinderIntroUi
import io.github.mame1839.codecanchor.ui.EqFinderIntroContent
import io.github.mame1839.codecanchor.ui.EqFinderPause
import io.github.mame1839.codecanchor.ui.EqFinderResultContent
import io.github.mame1839.codecanchor.ui.EqFinderResultUi
import io.github.mame1839.codecanchor.ui.EqFinderResumeUi
import io.github.mame1839.codecanchor.ui.EqFinderTrialContent
import io.github.mame1839.codecanchor.ui.EqFinderTrialUi
import io.github.mame1839.codecanchor.ui.drawEqFinderCurves
import io.github.mame1839.codecanchor.ui.eqFinderPlotRange
import io.github.mame1839.codecanchor.ui.eqFinderResponseDb
import io.github.mame1839.codecanchor.ui.eqFinderTimeText
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

/**
 * 探索画面の見張り。
 *
 * 中心は **「両方を聴くまで答えられない」と「破棄・やり直しは確認を挟む」** — どちらも
 * 目隠し比較の信頼性とデータ保護の要で、壊れても画面はもっともらしく動き続ける。
 *
 * 候補のゲイン値そのものは `EqFinderTrialUi` が**持っていない** (型で守る)。
 * 「画面が値を出さないこと」のテストはここには無い — 持っていない値は出しようがなく、
 * 出せないものを見張るテストは何も検査しない。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqFinderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    // ------------------------------------------------------------------
    // 試行
    // ------------------------------------------------------------------

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

        // 片方しか聴いていないうちは 3 つとも押せず、理由が出ている。
        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_same).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_b).assertIsNotEnabled()
        onText(R.string.eq_finder_answer_locked).assertExists()

        // 両方聴いた瞬間に、同じ composition のまま解錠される。
        compose.runOnIdle { ui.value = trial.copy(bothHeard = true) }
        onText(R.string.eq_finder_answer_b).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(EqFinderAnswer.B, answered)
    }

    @Test
    fun listeningTapsSwitchTheCandidate() {
        var listened: EqFinderCandidate? = null
        showTrial(trial, onListen = { listened = it })
        // カードの文字は "B" だけの独立したノード ("B is better" とは一致しない)。
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
        // 両方聴いた後でも、一時停止中は答えられない (鳴っていない音への回答になる)。
        onText(R.string.eq_finder_answer_same).assertIsNotEnabled()
        onText(R.string.eq_finder_paused_focus).assertExists()
        onText(R.string.eq_finder_resume_playback).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(resumed)
    }

    @Test
    fun disconnectionShowsItsOwnNoticeWithoutAResumeButton() {
        showTrial(trial.copy(bothHeard = true, pause = EqFinderPause.DISCONNECTED))
        onText(R.string.eq_finder_paused_disconnected).assertExists()
        // 再開の押しどころは無い — 接続が戻れば勝手に続く。
        compose.onNodeWithText(string(R.string.eq_finder_resume_playback)).assertDoesNotExist()
        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
    }

    // ------------------------------------------------------------------
    // 導入
    // ------------------------------------------------------------------

    private fun showIntro(
        ui: EqFinderIntroUi,
        onBegin: () -> Unit = {},
        onResume: () -> Unit = {},
        onStartOver: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderIntroContent(
                        ui = ui,
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
        // 新規開始の入力 (曲選び) は出ない。出すと「続き」と「新規」が同じ画面で混ざる。
        compose.onNodeWithText(string(R.string.eq_finder_song)).assertDoesNotExist()

        onText(R.string.action_continue).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(resumed)

        onText(R.string.eq_finder_start_over).performSemanticsAction(SemanticsActions.OnClick)
        onText(R.string.eq_finder_start_over_title).assertExists()
        // 17 回の回答はミスタップで消してよいものではない。確認してからしか消えない。
        assertTrue(!startedOver)
        compose.onNode(
            hasText(string(R.string.action_delete)) and hasAnyAncestor(isDialog()),
        ).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(startedOver)
    }

    @Test
    fun aMissingSongBlocksContinuingUntilItIsPickedAgain() {
        showIntro(
            EqFinderIntroUi(resume = EqFinderResumeUi(done = 5, songMissing = true)),
        )
        onText(R.string.eq_finder_resume_missing).assertExists()
        onText(R.string.action_continue).assertIsNotEnabled()
        // 選び直しの行はこのときだけ出る。
        onText(R.string.eq_finder_song).assertExists()
    }

    // ------------------------------------------------------------------
    // 結果
    // ------------------------------------------------------------------

    private fun result(
        bakeFailed: Boolean = false,
        presetOffer: Boolean = false,
    ) = EqFinderResultUi(
        axes = listOf(
            EqFinderAxisDelta(EqFinderAxisKind.BASS, 25),
            EqFinderAxisDelta(EqFinderAxisKind.TREBLE, -10),
        ),
        beforeDb = DoubleArray(8),
        afterDb = DoubleArray(8) { 3.0 },
        consistencyWarning = true,
        startBeaten = true,
        bakeFailed = bakeFailed,
        presetOffer = presetOffer,
    )

    private fun showResult(
        ui: EqFinderResultUi,
        onApply: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onSavePreset: (String) -> Unit = {},
        onDismissPresetOffer: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    EqFinderResultContent(
                        ui = ui,
                        onApply = onApply,
                        onDiscard = onDiscard,
                        onSavePreset = onSavePreset,
                        onDismissPresetOffer = onDismissPresetOffer,
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
    fun thePresetOfferSavesTheTypedName() {
        var saved: String? = null
        showResult(result(presetOffer = true), onSavePreset = { saved = it })
        // 空のうちは押せない (無名のプリセットは一覧で選べない)。
        onText(R.string.action_save).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.eq_preset_name)).performTextInput("夜用")
        onText(R.string.action_save).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("夜用", saved)
    }

    @Test
    fun theHonestLinesAreShown() {
        showResult(result(bakeFailed = true))
        onText(R.string.eq_finder_validated_win).assertExists()
        onText(R.string.eq_finder_consistency).assertExists()
        onText(R.string.eq_finder_bake_failed).assertExists()
    }

    private fun onText(id: Int) = compose.onNodeWithText(string(id))

    // ------------------------------------------------------------------
    // 曲線 (計算)
    // ------------------------------------------------------------------

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
        // FSI/PDI で囲まれていること自体が仕様 (RTL で数字と区切りが入れ替わらないための isolate)。
        // 生の制御文字をソースに置くと lint (BidiSpoofing) に落ちるのでエスケープで書く。
        assertEquals("\u20680:00\u2069", eqFinderTimeText(0))
        assertEquals("\u20680:20\u2069", eqFinderTimeText(20_000))
        assertEquals("\u20681:01\u2069", eqFinderTimeText(61_000))
        assertEquals("\u206812:05\u2069", eqFinderTimeText(725_400))
    }

    // ------------------------------------------------------------------
    // 曲線 (絵)。EqCurveTest と同じく、アプリの Canvas が呼ぶ描画関数を
    // ImageBitmap へ流して画素で見る。色は役割ごとに分ける。
    // ------------------------------------------------------------------

    private val density = Density(3f)
    private val measurer by lazy {
        TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)
    }

    /** 適用後の曲線。 */
    private val accent = Color.Red

    /** 適用前の曲線と目盛り線 — 区別は不透明度 (曲線 1.0 / 目盛り 0.28 以下)。 */
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
        // +6 dB の「後」は 0 dB の「前」より必ず上 (y が小さい)。逆なら軸の向きが壊れている。
        assertTrue(
            "持ち上げた後の曲線が前より下に描かれている",
            accentYSum / accentCount < beforeYSum / beforeCount,
        )
    }
}
