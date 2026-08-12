package io.github.mame1839.codecanchor

import android.os.Handler
import android.os.Looper
import io.github.mame1839.codecanchor.audio.MusicPlaybackMonitor
import io.github.mame1839.codecanchor.bridge.EqDeviceStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.EqFinderAnswer
import io.github.mame1839.codecanchor.ui.EqFinderCandidate
import io.github.mame1839.codecanchor.ui.EqFinderController
import io.github.mame1839.codecanchor.ui.EqFinderPhase
import io.github.mame1839.codecanchor.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers

/**
 * 結果画面のバンド数選択の配線 (コントローラ経由)。数字の正しさは EqFinderBakePlanTest が
 * 純関数で見るので、ここで見るのは**選択が保存物にだけ効く**こと — プレビューは押し直されず、
 * apply が選んだ数の実体を永続化する。セッションはライブ題材で回す (音のデコードが要らない)。
 */
@RunWith(RobolectricTestRunner::class)
class EqFinderBakeFlowTest {

    private val mac = "AA:BB:CC:DD:EE:0F"

    private fun app() = RuntimeEnvironment.getApplication()

    // EqFinderLiveTest と同じ組み立て: 登録は永続ストア、出口は ShadowAudioManager +
    // AudioDevicePort.mAddress の反射 (AudioDeviceInfoBuilder に setAddress が無い)。
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
            it.ensureProfile(mac)
            check(it.eqOwner == mac) { "テストの前提が崩れた: 出口の address が eqOwner に届いていない" }
        }
    }

    private fun sessionAtResult(vm: MainViewModel): EqFinderController {
        val m = MusicPlaybackMonitor(null, Handler(Looper.getMainLooper()))
        val c = EqFinderController(
            context = app(),
            vm = vm,
            mac = mac,
            scope = CoroutineScope(Dispatchers.Unconfined),
            music = m,
            compute = Dispatchers.Unconfined,
        )
        m.onRaw(true)
        c.chooseMaterial(true)
        c.begin()
        c.listen(EqFinderCandidate.B)
        c.answer(EqFinderAnswer.A)
        c.finishNow()
        check(c.phase == EqFinderPhase.RESULT) { "結果画面まで進めなかった" }
        return c
    }

    @Test
    fun choosingACountTouchesOnlyWhatApplySaves() {
        val vm = vm()
        val c = sessionAtResult(vm)
        val ui = c.result
        assertNotNull(ui)
        // グラフィックの結果には 4 択が付き、既定はいまの bandCount。
        assertEquals(EqSettings.BAND_COUNTS, ui!!.bandChoices?.map { it.count })
        assertEquals(10, c.bakeBandCount)

        // 数の選択はプレビューを押し直さない — バンド構成が変わる push はクリックレス切替の
        // 条件を外れ、耳で選んだ after と別物の聴感になる。結果の画面状態もそのまま。
        val pushed = vm.eqPreview
        assertNotNull("結果の音が押し込まれていない", pushed)
        c.chooseBakeBandCount(31)
        assertEquals(31, c.bakeBandCount)
        assertSame("プレビューが押し直された", pushed, vm.eqPreview)
        assertSame("結果の画面状態が組み直された", ui, c.result)

        c.apply()
        val eq = vm.config.profileFor(mac)?.eq
        assertNotNull(eq)
        assertEquals(31, eq!!.bandCount)
        assertEquals(EqSolver.centerFrequencies(31), eq.bands.map { it.freqHz })
        assertTrue(eq.enabled)
        assertTrue(eq.preampAuto)
        assertTrue("適用後はプレビューが解除される", vm.eqPreview == null)
    }

    @Test
    fun aParametricBaseGetsNoBandCountChoice() {
        val vm = vm()
        val before = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 20))
        vm.updateEq(mac) {
            EqSettings(enabled = true, mode = EqMode.PARAMETRIC, bands = before)
        }
        val c = sessionAtResult(vm)
        assertNull("パラメトリックに数の選択を出さない (fc/Q は手作業の成果物)", c.result!!.bandChoices)

        c.apply()
        val eq = vm.config.profileFor(mac)?.eq
        assertNotNull(eq)
        assertEquals(EqMode.PARAMETRIC, eq!!.mode)
        // 既存バンドは値ごと残る。オーバーレイの推定が 0 でない軸だけが増える。
        assertEquals(before, eq.bands.take(before.size))
        assertTrue(eq.bands.size >= before.size)
    }
}
