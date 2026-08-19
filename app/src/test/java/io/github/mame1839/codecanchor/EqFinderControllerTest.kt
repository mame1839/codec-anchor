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

@RunWith(RobolectricTestRunner::class)
class EqFinderControllerTest {

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

        assertEquals(EqMode.PARAMETRIC, settings.mode)
        assertEquals(15, settings.bandCount)
        assertTrue(settings.enabled)
        assertEquals(bands, settings.bands)
        assertEquals(EqLoudness.preampDb10(bands, weights, -1.5), settings.preampDb10)
    }

    @Test
    fun theSessionPreampIsLoudnessEqualNotPeakBased() {
        val boost = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 60, type = EqBandType.LOW_SHELF))
        val weights = EqLoudness.defaultWeights()
        val session = eqFinderCandidateSettings(base, boost, weights, baseLevelDb = 0.0).preampDb10
        assertTrue("聴感等価 ($session) がピーク基準相当まで沈んでいる", session > -60)
    }

    @Test
    fun theZeroOverlayCandidateSoundsAtTheBaseVolume() {
        val weights = EqLoudness.defaultWeights()
        val curve = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 40, type = EqBandType.LOW_SHELF))
        val on = EqSettings(enabled = true, mode = EqMode.PARAMETRIC, bands = curve, preampDb10 = -60)
        val zeroOverlay = curve + listOf(
            EqBand(freqHz = 105, q100 = 71, gainDb10 = 0, type = EqBandType.LOW_SHELF),
            EqBand(freqHz = 2_500, q100 = 71, gainDb10 = 0, type = EqBandType.HIGH_SHELF),
        )
        val level = EqLoudness.baseLevelDb(on, weights)
        assertEquals(-60, eqFinderCandidateSettings(on, zeroOverlay, weights, level).preampDb10)

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

    @Test
    fun thePcmFingerprintSeesContentChanges() {
        val pcm = FloatArray(4_096) { kotlin.math.sin(it * 0.01).toFloat() }
        val same = pcm.copyOf()
        val different = pcm.copyOf().also { it[2_048] += 1e-6f }

        assertEquals(pcmContentHash(pcm), pcmContentHash(same))
        assertNotEquals(pcmContentHash(pcm), pcmContentHash(different))
        pcmContentHash(FloatArray(0))
    }

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

        assertNull(controller(vm).resumeBlocked())

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

    @Test
    fun resumingTakesTheAxesFromTheSessionNotFromTheRecordFlag() {
        val threeAxes = EqFinderAxes.default(includeMid = true)
        val session = EqFinderSession.start(threeAxes, seed = 7L)
        val rec = record(base).copy(includeMid = false, session = session.toJson())

        val restored = eqFinderRestore(rec)
        requireNotNull(restored)
        assertEquals(threeAxes, restored.axes)
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
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        assertFalse(opened)
    }

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
