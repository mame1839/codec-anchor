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

    /**
     * ライブ題材の無音は自動で下りる一時停止で、**再開ボタンを出さない** — 他人のアプリの
     * 再生はこちらから再開できない (「再生を再開」はフォーカス喪失専用で、ライブでは
     * フォーカスを取らないので到達もしない)。
     */
    @Test
    fun theNoMusicPauseHasNoResumeButton() {
        showTrial(trial.copy(bothHeard = true, pause = EqFinderPause.NO_MUSIC))
        onText(R.string.eq_finder_paused_no_music).assertExists()
        compose.onNodeWithText(string(R.string.eq_finder_resume_playback)).assertDoesNotExist()
        onText(R.string.eq_finder_answer_a).assertIsNotEnabled()
    }

    // ------------------------------------------------------------------
    // 導入
    // ------------------------------------------------------------------

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

    /** ライブ題材に曲は要らない。曲の行ごと消え、音楽さえ流れていれば始められる。 */
    @Test
    fun theLiveMaterialNeedsNoSong() {
        var begun = false
        showIntro(EqFinderIntroUi(materialLive = true), onBegin = { begun = true })
        compose.onNodeWithText(string(R.string.eq_finder_song)).assertDoesNotExist()
        // 精度が下がることの 1 行は「始める前に」のノートに出続ける。
        onText(R.string.eq_finder_material_live_desc).assertExists()
        onText(R.string.eq_finder_begin).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(begun)
    }

    /** 音楽が流れていないライブは、既存の門 (startBlockedReason) の形で塞がる。 */
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

    /** 題材の選択肢に、精度が下がることの説明が付いている (選ぶ瞬間に読める)。 */
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

    // 遮断された再開に「続ける」を出すと、黙って壊れた前提の上で回答が続く
    // (設定ずれ = 中間の編集が確定で消える / 一節ずれ = 回答が別の音のもの)。
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

    // ------------------------------------------------------------------
    // 結果
    // ------------------------------------------------------------------

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

    /**
     * 適用したら**何も聞かずに終わる。**結果は自分のスロットへ残り、名前付けも書き出しも
     * スロットのメニューに常設されている — ここで重ねて聞くと同じことを 2 通りで聞く。
     */
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

    /** バンド数の行はグラフィックのときだけ。選択肢は忠実度の 1 行を連れて出る。 */
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
        // 副題 = 「聴いた曲線との差 最大 X.X dB」。数字は 0.1 dB 丸めの db10 から組む。
        // eq_unit_db は書式そのもの (%1$s を含む) なので、引数付きの getString には通さない。
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

    /** パラメトリックの結果 (bandChoices = null) にバンド数の行は出ない。 */
    @Test
    fun parametricResultsHaveNoBandCountRow() {
        showResult(result())
        compose.onNodeWithText(string(R.string.eq_band_count)).assertDoesNotExist()
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
