package io.github.mame1839.codecanchor

import android.os.Handler
import android.os.Looper
import io.github.mame1839.codecanchor.audio.MusicPlaybackMonitor
import io.github.mame1839.codecanchor.bridge.EqDeviceStore
import io.github.mame1839.codecanchor.bridge.EqFinderStore
import io.github.mame1839.codecanchor.ui.EqFinderAnswer
import io.github.mame1839.codecanchor.ui.EqFinderCandidate
import io.github.mame1839.codecanchor.ui.EqFinderController
import io.github.mame1839.codecanchor.ui.EqFinderPause
import io.github.mame1839.codecanchor.ui.EqFinderPhase
import io.github.mame1839.codecanchor.ui.EqFinderResumeBlocked
import io.github.mame1839.codecanchor.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class EqFinderLiveTest {

    private val mac = "AA:BB:CC:DD:EE:0F"

    private fun app() = RuntimeEnvironment.getApplication()

    private fun vm(): MainViewModel {
        EqDeviceStore(app()).save(listOf(mac))
        val audioManager = app().getSystemService(android.media.AudioManager::class.java)
        val device = org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
            .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            .build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(port, "mAddress", mac)
        Shadows.shadowOf(audioManager).setOutputDevices(listOf(device))
        return MainViewModel(app()).also {
            check(it.eqOwner == mac) { "テストの前提が崩れた: 出口の address が eqOwner に届いていない" }
        }
    }

    private fun monitor(quietDelayMs: Long = MusicPlaybackMonitor.QUIET_DELAY_MS) =
        MusicPlaybackMonitor(null, Handler(Looper.getMainLooper()), quietDelayMs)

    private fun controller(vm: MainViewModel, music: MusicPlaybackMonitor) = EqFinderController(
        context = app(),
        vm = vm,
        mac = mac,
        scope = CoroutineScope(Dispatchers.Unconfined),
        music = music,
        compute = Dispatchers.Unconfined,
    )

    private fun idle(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun theQuietDirectionIsDebouncedAndPlayIsImmediate() {
        val m = monitor(quietDelayMs = 1_500)
        m.start()
        val changes = mutableListOf<Boolean>()
        m.onChange = { changes += it }

        m.onRaw(true)
        assertTrue(m.active)
        assertEquals(listOf(true), changes)

        m.onRaw(false)
        assertFalse(m.rawActive)
        assertTrue("デバウンス前に落ちた", m.active)
        idle(1_400)
        assertTrue("デバウンスが短すぎる", m.active)
        idle(200)
        assertFalse(m.active)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun aGapBetweenSongsDoesNotFlicker() {
        val m = monitor(quietDelayMs = 1_500)
        m.start()
        val changes = mutableListOf<Boolean>()
        m.onChange = { changes += it }
        m.onRaw(true)
        m.onRaw(false)
        idle(800)
        m.onRaw(true)
        idle(3_000)
        assertTrue(m.active)
        assertEquals("明滅した", listOf(true), changes)
    }

    @Test
    fun liveBeginNeedsNoSongAndSavesALiveRecord() {
        val vm = vm()
        val m = monitor()
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)

        c.begin()

        assertEquals(EqFinderPhase.TRIAL, c.phase)
        assertEquals(EqFinderCandidate.A, c.selected)
        assertTrue("自動再生された A が聴けている", c.aHeard)
        assertFalse(c.bHeard)
        val record = EqFinderStore(app()).load()
        assertNotNull(record)
        assertTrue(record!!.live)
        assertNull(record.uri)
        assertEquals("ライブは照合しない (既存の 0 の意味)", 0L, record.pcmHash)
    }

    @Test
    fun liveBeginIsGatedOnPlayingMusic() {
        val c = controller(vm(), monitor())
        c.chooseMaterial(true)
        c.begin()
        assertEquals(EqFinderPhase.INTRO, c.phase)
        assertNull(EqFinderStore(app()).load())
    }

    @Test
    fun silenceRaisesTheNoMusicPauseAndHonestHeardMarks() {
        val vm = vm()
        val m = monitor(quietDelayMs = 1_500)
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()
        assertEquals(EqFinderPhase.TRIAL, c.phase)

        m.onRaw(false)
        assertEquals(EqFinderPause.NONE, c.pause)
        c.listen(EqFinderCandidate.B)
        assertEquals(EqFinderCandidate.B, c.selected)
        assertFalse("無音のまま聴いたことになった", c.bHeard)

        idle(1_600)
        assertEquals(EqFinderPause.NO_MUSIC, c.pause)
        val doneBefore = c.done
        c.answer(EqFinderAnswer.B)
        assertEquals("一時停止中に回答が通った", doneBefore, c.done)

        m.onRaw(true)
        assertEquals(EqFinderPause.NONE, c.pause)
        assertTrue(c.bHeard)
    }

    @Test
    fun reconnectingInLiveReturnsWithoutThePlayer() {
        val vm = vm()
        val m = monitor()
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()

        c.onConnectionChanged(false)
        assertEquals(EqFinderPause.DISCONNECTED, c.pause)
        c.onConnectionChanged(true)
        assertEquals("play() の成否に頼って戻れなくなっている", EqFinderPause.NONE, c.pause)

        c.onConnectionChanged(false)
        m.onRaw(false)
        idle(1_600)
        assertEquals(EqFinderPause.DISCONNECTED, c.pause)
        c.onConnectionChanged(true)
        assertEquals(EqFinderPause.NO_MUSIC, c.pause)
    }

    @Test
    fun reconnectingInsideTheDebounceWindowDoesNotUnlockASilentCandidate() {
        val vm = vm()
        val m = monitor(quietDelayMs = 1_500)
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()

        m.onRaw(false)
        c.listen(EqFinderCandidate.B)
        assertEquals(EqFinderCandidate.B, c.selected)
        assertFalse(c.bHeard)

        c.onConnectionChanged(false)
        assertEquals(EqFinderPause.DISCONNECTED, c.pause)
        c.onConnectionChanged(true)
        assertEquals(EqFinderPause.NONE, c.pause)
        assertFalse("無音のまま再接続で解錠された", c.bHeard)

        m.onRaw(true)
        c.listen(EqFinderCandidate.B)
        assertTrue(c.bHeard)
    }

    @Test
    fun aLiveSessionResumesWithoutTouchingAnySong() {
        val vm = vm()
        val m1 = monitor()
        val c1 = controller(vm, m1)
        m1.onRaw(true)
        c1.chooseMaterial(true)
        c1.begin()
        assertEquals(EqFinderPhase.TRIAL, c1.phase)
        c1.dispose()

        val m2 = monitor()
        val c2 = controller(vm, m2)
        m2.onRaw(true)
        assertNotNull(c2.saved)
        assertTrue(c2.materialLive)
        assertNull(c2.resumeBlocked())
        c2.resume()
        assertEquals(EqFinderPhase.TRIAL, c2.phase)
        assertTrue(c2.aHeard)
    }

    @Test
    fun settingsDriftStillBlocksALiveResume() {
        val vm = vm()
        vm.ensureProfile(mac)
        val m1 = monitor()
        val c1 = controller(vm, m1)
        m1.onRaw(true)
        c1.chooseMaterial(true)
        c1.begin()
        c1.dispose()

        vm.updateEq(mac) { it.copy(preampDb10 = -50) }
        val m2 = monitor()
        val c2 = controller(vm, m2)
        m2.onRaw(true)
        assertEquals(EqFinderResumeBlocked.SETTINGS_CHANGED, c2.resumeBlocked())
        c2.resume()
        assertEquals(EqFinderPhase.INTRO, c2.phase)
    }

    @Test
    fun startingOverResetsTheMaterial() {
        val vm = vm()
        val m = monitor()
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()
        c.dispose()

        val c2 = controller(vm, monitor())
        assertTrue(c2.materialLive)
        c2.startOver()
        assertFalse(c2.materialLive)
        assertNull(c2.saved)
    }
}
