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

/**
 * ライブ題材 (いま流れている音楽) の全遷移。
 *
 * 検知は [MusicPlaybackMonitor] を AudioManager 無しで注入し、raw の遷移 (onRaw) を直接振る。
 * デバウンスは main looper の postDelayed なので、Robolectric の時計送りで実時間なしに踏める。
 * 音そのもの (実機のデコーダ・他アプリの再生) はここでは扱わない — 実機の
 * AudioPlaybackCallback が本当に届くかは統合後に実機で見る。
 */
@RunWith(RobolectricTestRunner::class)
class EqFinderLiveTest {

    private val mac = "AA:BB:CC:DD:EE:0F"

    private fun app() = RuntimeEnvironment.getApplication()

    /**
     * 枠の持ち主がこの機器になった状態 (begin/resume のガード eqOwner を通すため)。
     * どちらも本物の経路で作る — 登録済みは永続ストア、出口は ShadowAudioManager の
     * デバイス一覧。vm の状態を直接書く裏口を作らない。
     *
     * AudioDeviceInfoBuilder (Robolectric 4.16) に setAddress が無いので、address だけ
     * AudioDevicePort の mAddress へ反射で入れる。android-all の内部名が変わったら
     * 最後の check が理由ごと落とす。
     */
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

    // ------------------------------------------------------------------
    // 検知のデバウンス
    // ------------------------------------------------------------------

    @Test
    fun theQuietDirectionIsDebouncedAndPlayIsImmediate() {
        val m = monitor(quietDelayMs = 1_500)
        m.start()
        val changes = mutableListOf<Boolean>()
        m.onChange = { changes += it }

        // 現れる方向は即時。
        m.onRaw(true)
        assertTrue(m.active)
        assertEquals(listOf(true), changes)

        // 消える方向は遅れて確定する。生値 (rawActive) だけが即時に落ちる。
        m.onRaw(false)
        assertFalse(m.rawActive)
        assertTrue("デバウンス前に落ちた", m.active)
        idle(1_400)
        assertTrue("デバウンスが短すぎる", m.active)
        idle(200)
        assertFalse(m.active)
        assertEquals(listOf(true, false), changes)
    }

    /** 曲間の一瞬の無音では警告が明滅しない — 窓の中で戻れば何も起きない。 */
    @Test
    fun aGapBetweenSongsDoesNotFlicker() {
        val m = monitor(quietDelayMs = 1_500)
        m.start()
        val changes = mutableListOf<Boolean>()
        m.onChange = { changes += it }
        m.onRaw(true)
        m.onRaw(false)
        idle(800)
        m.onRaw(true) // 次の曲が始まった
        idle(3_000)
        assertTrue(m.active)
        assertEquals("明滅した", listOf(true), changes)
    }

    // ------------------------------------------------------------------
    // 開始と保存
    // ------------------------------------------------------------------

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
        c.begin() // 音楽が流れていない
        assertEquals(EqFinderPhase.INTRO, c.phase)
        assertNull(EqFinderStore(app()).load())
    }

    // ------------------------------------------------------------------
    // 試行中の一時停止と heard の正直さ
    // ------------------------------------------------------------------

    @Test
    fun silenceRaisesTheNoMusicPauseAndHonestHeardMarks() {
        val vm = vm()
        val m = monitor(quietDelayMs = 1_500)
        val c = controller(vm, m)
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()
        assertEquals(EqFinderPhase.TRIAL, c.phase)

        // 音楽が止まった直後 (デバウンスの窓の中): 一時停止はまだ立たないが、
        // この間に押した候補を「聴いた」ことにはしない — 実際には何も鳴っていない。
        m.onRaw(false)
        assertEquals(EqFinderPause.NONE, c.pause)
        c.listen(EqFinderCandidate.B)
        assertEquals(EqFinderCandidate.B, c.selected)
        assertFalse("無音のまま聴いたことになった", c.bHeard)

        // 窓が閉じると自動で一時停止。回答も試聴も止まる。
        idle(1_600)
        assertEquals(EqFinderPause.NO_MUSIC, c.pause)
        val doneBefore = c.done
        c.answer(EqFinderAnswer.B)
        assertEquals("一時停止中に回答が通った", doneBefore, c.done)

        // 音楽が戻れば自動で下りて、鳴り始めた選択中の候補が「聴いた」になる。
        m.onRaw(true)
        assertEquals(EqFinderPause.NONE, c.pause)
        assertTrue(c.bHeard)
    }

    /**
     * 切断からの復帰。ループはプレイヤーの play() の成否で戻るが、ライブにプレイヤーは無く
     * play() は常に false — その経路に乗せると永久に DISCONNECTED のまま残る。
     * 音楽の有無を直接見て戻ることの見張り。
     */
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

        // 切断中に音楽も止まっていたら、復帰先は NO_MUSIC (鳴っていないのに再開しない)。
        c.onConnectionChanged(false)
        m.onRaw(false)
        idle(1_600)
        assertEquals(EqFinderPause.DISCONNECTED, c.pause)
        c.onConnectionChanged(true)
        assertEquals(EqFinderPause.NO_MUSIC, c.pause)
    }

    // ------------------------------------------------------------------
    // 中断と再開
    // ------------------------------------------------------------------

    @Test
    fun aLiveSessionResumesWithoutTouchingAnySong() {
        val vm = vm()
        // onRaw はコントローラを作ってから振る — start() の初期読み (AudioManager 無し = 無音)
        // が後から上書きするため。
        val m1 = monitor()
        val c1 = controller(vm, m1)
        m1.onRaw(true)
        c1.chooseMaterial(true)
        c1.begin()
        assertEquals(EqFinderPhase.TRIAL, c1.phase)
        c1.dispose()

        // プロセス死からの開き直し。記録から題材が立ち、曲の読み直しも照合も無しで続きへ。
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

    /** 設定ずれの遮断はライブでも同じに効く (SONG_CHANGED はライブでは出ない)。 */
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

        vm.updateEq(mac) { it.copy(preampDb10 = -50, preampAuto = false) }
        val m2 = monitor()
        val c2 = controller(vm, m2)
        m2.onRaw(true)
        assertEquals(EqFinderResumeBlocked.SETTINGS_CHANGED, c2.resumeBlocked())
        c2.resume()
        assertEquals(EqFinderPhase.INTRO, c2.phase)
    }

    /** やり直しは題材も既定 (ループ) へ戻す。 */
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
