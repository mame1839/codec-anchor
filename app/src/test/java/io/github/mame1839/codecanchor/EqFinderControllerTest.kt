package io.github.mame1839.codecanchor

import androidx.compose.material3.MaterialTheme
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
import io.github.mame1839.codecanchor.ui.EqFinderController
import io.github.mame1839.codecanchor.ui.EqFinderEntryCard
import io.github.mame1839.codecanchor.ui.EqFinderResumeBlocked
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.axisKind
import io.github.mame1839.codecanchor.ui.EqFinderAxisKind
import io.github.mame1839.codecanchor.ui.eqFinderCandidateSettings
import io.github.mame1839.codecanchor.ui.eqFinderCornerOverlays
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

/**
 * セッション進行の接着のうち、音 (実機のデコーダ) に依らない部分。
 *
 * 音量等価の要 — **候補の設定は聴感等価プリアンプで押し、共通トリムの端は全組合せで洗う** —
 * が黙って崩れると、探索がフラットへ潰れる (eq-finder-design.md §1)。ここはその見張り。
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
        preampAuto = true,
        preampDb10 = -30,
    )

    @Test
    fun candidateSettingsKeepTheShapeAndOverrideOnlyWhatTheSessionNeeds() {
        val bands = listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = -20, type = EqBandType.HIGH_SHELF),
        )
        val weights = EqLoudness.defaultWeights()
        val settings = eqFinderCandidateSettings(base, bands, weights, trimDb = 1.5)

        // mode / bandCount は元の値のまま (変えると hash の往復とグラフィックの解き直しに波及)。
        assertEquals(EqMode.PARAMETRIC, settings.mode)
        assertEquals(15, settings.bandCount)
        // 鳴らすために enabled は必ず true。プリアンプは自動 (ピーク基準) ではなく聴感等価。
        assertTrue(settings.enabled)
        assertFalse(settings.preampAuto)
        assertEquals(bands, settings.bands)
        assertEquals(EqLoudness.preampDb10(bands, weights, 1.5), settings.preampDb10)
    }

    /** 聴感等価が輪切りで潰れていないこと: 低域ブーストの候補はピーク基準より静かにならない。 */
    @Test
    fun theSessionPreampIsLoudnessEqualNotPeakBased() {
        val boost = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 60, type = EqBandType.LOW_SHELF))
        val weights = EqLoudness.defaultWeights()
        val session = eqFinderCandidateSettings(base, boost, weights, trimDb = 0.0).preampDb10
        // ピーク基準なら約 -60。聴感基準は音楽のエネルギー分布で決まり、それより浅い。
        assertTrue("聴感等価 ($session) がピーク基準相当まで沈んでいる", session > -60)
    }

    // ------------------------------------------------------------------
    // 共通トリムの端
    // ------------------------------------------------------------------

    @Test
    fun cornerOverlaysCoverEveryExtremeAndTheFlatCentre() {
        val axes = EqFinderAxes.default(includeMid = true)
        val corners = eqFinderCornerOverlays(axes)

        assertEquals("2^3 + 全 0", 9, corners.size)
        assertTrue(corners.all { it.size == axes.size })
        assertTrue("全 0 が入っていない", corners.any { overlay -> overlay.all { it == 0 } })
        // 全 min と全 max の角が必ずある。最悪候補は端に出る。
        assertTrue(corners.any { o -> o.withIndex().all { (i, v) -> v == axes[i].minDb10 } })
        assertTrue(corners.any { o -> o.withIndex().all { (i, v) -> v == axes[i].maxDb10 } })
        // 重複が無い (同じ候補を 2 回測っても害は無いが、数が合っていることの裏取り)。
        assertEquals(corners.size, corners.toSet().size)

        assertEquals(5, eqFinderCornerOverlays(EqFinderAxes.default(includeMid = false)).size)
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
}
