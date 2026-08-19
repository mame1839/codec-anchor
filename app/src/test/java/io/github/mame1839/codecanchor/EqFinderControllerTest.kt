package io.github.mame1839.codecanchor

import android.media.AudioManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.mame1839.codecanchor.bridge.EqFinderSaved
import io.github.mame1839.codecanchor.bridge.EqFinderStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
import io.github.mame1839.codecanchor.core.EqFinderAxes
import io.github.mame1839.codecanchor.core.EqFinderAxis
import io.github.mame1839.codecanchor.core.EqLoudness
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqFinderSession
import io.github.mame1839.codecanchor.ui.EqFinderController
import io.github.mame1839.codecanchor.ui.EqFinderEntryCard
import io.github.mame1839.codecanchor.ui.EqFinderScreen
import io.github.mame1839.codecanchor.ui.eqFinderRestore
import io.github.mame1839.codecanchor.ui.EqFinderResumeBlocked
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.axisKind
import io.github.mame1839.codecanchor.ui.EqFinderAxisKind
import io.github.mame1839.codecanchor.ui.eqFinderCandidateSettings
import io.github.mame1839.codecanchor.ui.pcmContentHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * セッション進行の接着のうち、音 (実機のデコーダ) に依らない部分。
 *
 * 音量等価の要 — **候補の設定は土台の聴感レベルに揃えたプリアンプで押す** — が黙って崩れると、
 * 探索がフラットへ潰れる (eq-finder-design.md §1)。ここはその見張り。
 */
@RunWith(RobolectricTestRunner::class)
class EqFinderControllerTest {

    // ------------------------------------------------------------------
    // 候補の押し込み
    // ------------------------------------------------------------------

    private val base = EqSettings(
        enabled = false,
        mode = EqMode.PARAMETRIC,
        bandCount = 15,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 20)),
        preampDb10 = -30,
    )

    @Test
    fun candidateSettingsKeepTheShapeAndOverrideOnlyWhatTheSessionNeeds() {
        val bands = listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -20, type = EqBandType.HIGH_SHELF),
        )
        val weights = EqLoudness.defaultWeights()
        val settings = eqFinderCandidateSettings(base, bands, weights, baseLevelDb = -1.5)

        // mode / bandCount は元の値のまま (変えると hash の往復とグラフィックの解き直しに波及)。
        assertEquals(EqMode.PARAMETRIC, settings.mode)
        assertEquals(15, settings.bandCount)
        // 鳴らすために enabled は必ず true。プリアンプは自動 (ピーク基準) ではなく聴感等価。
        assertTrue(settings.enabled)
        assertEquals(bands, settings.bands)
        assertEquals(EqLoudness.preampDb10(bands, weights, -1.5), settings.preampDb10)
    }

    /** 聴感等価が輪切りで潰れていないこと: 低域ブーストの候補はピーク基準より静かにならない。 */
    @Test
    fun theSessionPreampIsLoudnessEqualNotPeakBased() {
        val boost = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 60, type = EqBandType.LOW_SHELF))
        val weights = EqLoudness.defaultWeights()
        val session = eqFinderCandidateSettings(base, boost, weights, baseLevelDb = 0.0).preampDb10
        // ピーク基準なら約 -60。聴感基準は音楽のエネルギー分布で決まり、それより浅い。
        assertTrue("聴感等価 ($session) がピーク基準相当まで沈んでいる", session > -60)
    }

    /**
     * **土台のプリアンプが候補に引き継がれる。**EQ を入れて −6.0 dB にしている人の探索では、
     * オーバーレイ全 0 の候補が −6.0 dB ちょうどで鳴る (= 普段そのままの音量)。
     * ここが 0 に戻ると、探索を始めた瞬間に 6 dB 大きくなる。
     */
    @Test
    fun theZeroOverlayCandidateSoundsAtTheBaseVolume() {
        val weights = EqLoudness.defaultWeights()
        val curve = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF))
        val on = EqSettings(enabled = true, mode = EqMode.PARAMETRIC, bands = curve, preampDb10 = -60)
        // 土台のバンドに、ゲイン 0 の軸を重ねただけの候補 = 「なし」
        val zeroOverlay = curve + listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 0, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = 0, type = EqBandType.HIGH_SHELF),
        )
        val level = EqLoudness.baseLevelDb(on, weights)
        assertEquals(-60, eqFinderCandidateSettings(on, zeroOverlay, weights, level).preampDb10)

        // 切ってある土台では P_eff = 0 (baseBands が空になるのと同じ規則)。
        val off = on.copy(enabled = false)
        assertEquals(0.0, EqLoudness.baseLevelDb(off, weights), 0.0)
        val bare = listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 0, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = 0, type = EqBandType.HIGH_SHELF),
        )
        assertEquals(
            0,
            eqFinderCandidateSettings(off, bare, weights, EqLoudness.baseLevelDb(off, weights)).preampDb10,
        )
    }

    @Test
    fun axisKindsFollowTheFilterType() {
        val axes = EqFinderAxes.default(includeMid = true)
        val kinds = axes.map { axisKind(it) }
        assertEquals(
            setOf(EqFinderAxisKind.BASS, EqFinderAxisKind.TREBLE, EqFinderAxisKind.MID),
            kinds.toSet(),
        )
        assertEquals(
            EqFinderAxisKind.MID,
            axisKind(EqFinderAxis(freqHz = 3_000, q100 = 100, type = EqBandType.PEAKING)),
        )
    }

    // ------------------------------------------------------------------
    // 一節の指紋
    // ------------------------------------------------------------------

    @Test
    fun thePcmFingerprintSeesContentChanges() {
        val pcm = FloatArray(4_096) { kotlin.math.sin(it * 0.01).toFloat() }
        val same = pcm.copyOf()
        val different = pcm.copyOf().also { it[2_048] += 1e-6f }

        assertEquals(pcmContentHash(pcm), pcmContentHash(same))
        assertNotEquals(pcmContentHash(pcm), pcmContentHash(different))
        // 空でも落ちない (デコードに失敗した断片を照合しない)。
        pcmContentHash(FloatArray(0))
    }

    // ------------------------------------------------------------------
    // 再開の遮断
    // ------------------------------------------------------------------

    private val mac = "AA:BB:CC:DD:EE:0F"

    private fun record(base: EqSettings) = EqFinderSaved(
        mac = mac,
        uri = "content://media/audio/1",
        startMs = 30_000,
        lengthMs = 20_000,
        includeMid = false,
        fineTune = false,
        done = 12,
        pcmHash = 42L,
        base = base,
        session = JSONObject("""{"v":1}"""),
        savedAt = 1L,
    )

    private fun controller(vm: MainViewModel) = EqFinderController(
        context = RuntimeEnvironment.getApplication(),
        vm = vm,
        mac = mac,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun resumeIsBlockedOnceTheSettingsDriftFromTheSavedBase() {
        val app = RuntimeEnvironment.getApplication()
        val persisted = base.copy(enabled = true)
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        vm.updateEq(mac) { persisted }
        EqFinderStore(app).save(record(persisted))

        // 保存時の base と一致しているうちは再開できる。
        assertNull(controller(vm).resumeBlocked())

        // 中断のあいだにどこか 1 箇所でも編集されたら、続きからは出さない。
        vm.updateEq(mac) { it.copy(preampDb10 = -50) }
        assertEquals(EqFinderResumeBlocked.SETTINGS_CHANGED, controller(vm).resumeBlocked())
    }

    @Test
    fun aRecordForAnotherDeviceDoesNotOfferResuming() {
        val app = RuntimeEnvironment.getApplication()
        EqFinderStore(app).save(record(base).copy(mac = "11:22:33:44:55:66"))
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        val c = controller(vm)
        assertNull("別の機器の記録が「続きから」に化けた", c.saved)
        assertNull(c.resumeBlocked())
    }

    /**
     * 再開の軸は**セッション JSON が真** — record.includeMid から既定の軸を引き直すと
     * 同じ情報の 2 箇所持ちで、軸の既定が変わった版では旧セッションのオーバーレイが
     * 別のバンドに実体化する。旗が食い違った記録 (このテストの形) では落ちもする。
     */
    @Test
    fun resumingTakesTheAxesFromTheSessionNotFromTheRecordFlag() {
        val threeAxes = EqFinderAxes.default(includeMid = true)
        val session = EqFinderSession.start(threeAxes, seed = 7L)
        val rec = record(base).copy(includeMid = false, session = session.toJson())

        val restored = eqFinderRestore(rec)
        requireNotNull(restored)
        assertEquals(threeAxes, restored.axes)
        // base が切られていれば土台は空 (素の音の上に重ねる)。
        assertTrue(restored.baseBands.isEmpty())
        assertEquals(
            base.bands,
            eqFinderRestore(rec.copy(base = base.copy(enabled = true)))?.baseBands,
        )
    }

    @Test
    fun anUnreadableSessionRefusesToRestore() {
        assertNull(eqFinderRestore(record(base).copy(session = JSONObject("""{"v":99}"""))))
    }

    // ------------------------------------------------------------------
    // 入口の門
    // ------------------------------------------------------------------

    /**
     * 使えない状態の入口は、理由を出して押せない。
     *
     * Robolectric ではエフェクトが登録されないので `eqAvailability` は必ず不可 —
     * つまりここで確かめられるのは閉じた側だけ。**開く側 (OK + 接続一致で押せる) は
     * JVM では作れない状態なので、実機で確かめる。**
     */
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEntryCardExplainsItselfInsteadOfOpening() {
        val vm = MainViewModel(RuntimeEnvironment.getApplication())
        vm.ensureProfile(mac)
        var opened = false
        compose.setContent {
            MaterialTheme {
                EqFinderEntryCard(vm, mac, onOpen = { opened = true })
            }
        }
        compose.onNodeWithText(
            RuntimeEnvironment.getApplication().getString(R.string.eq_finder_entry_unavailable),
        ).assertExists()
        // 行は disabled (OnClick の定義自体は disabled でも残るので、有効かどうかで見る)。
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        assertFalse(opened)
    }

    /**
     * 出口が消える予告 (ACTION_AUDIO_BECOMING_NOISY) の受け口が**画面の生存中だけ**あること。
     *
     * 受けっぱなし (解除漏れ) は常駐になり、登録し忘れは切断の瞬間にスピーカーへ漏れる窓が
     * 戻る。放送を受けたときの実際の停止・一時停止は実機でしか確かめられない
     * (Robolectric では音が出ないので、止まったことを観測できる状態が作れない)。
     */
    @Test
    fun theNoisyReceiverLivesOnlyWhileTheFinderIsOpen() {
        val app = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(app)
        vm.ensureProfile(mac)
        val before = noisyReceivers(app)

        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) {
                    EqFinderScreen(vm, mac, SnackbarHostState(), onBack = {})
                }
            }
        }
        assertEquals(before + 1, noisyReceivers(app))

        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        assertEquals("画面を閉じても受け口が残っている", before, noisyReceivers(app))
    }

    private fun noisyReceivers(app: android.app.Application): Int =
        Shadows.shadowOf(app).registeredReceivers.count { wrapper ->
            wrapper.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
}
