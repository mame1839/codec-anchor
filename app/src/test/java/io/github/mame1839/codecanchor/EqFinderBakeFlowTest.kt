package io.github.mame1839.codecanchor

import android.os.Handler
import android.os.Looper
import io.github.mame1839.codecanchor.audio.MusicPlaybackMonitor
import io.github.mame1839.codecanchor.bridge.EqDeviceStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqBandType
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
import org.junit.Assert.assertNotEquals
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

    /** ライブ題材で始めた試行中のセッション (音のデコードが要らない)。 */
    private fun session(vm: MainViewModel): EqFinderController {
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
        return c
    }

    private fun sessionAtResult(vm: MainViewModel): EqFinderController {
        val c = session(vm)
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

        assertTrue(c.apply())
        val eq = vm.config.profileFor(mac)?.eq
        assertNotNull(eq)
        assertEquals(31, eq!!.bandCount)
        assertEquals(EqSolver.centerFrequencies(31), eq.bands.map { it.freqHz })
        assertTrue(eq.enabled)
        assertTrue(eq.preampAuto)
        assertTrue("適用後はプレビューが解除される", vm.eqPreview == null)

        // 結果の中身 (選択肢・曲線) は適用で組み直されない。旗を結果に混ぜて写していた頃は、
        // 写し忘れがここを静かに壊した。
        assertSame("適用で結果が組み直された", ui, c.result)
        assertEquals(EqSettings.BAND_COUNTS, c.result!!.bandChoices?.map { it.count })
    }

    /**
     * **探索の結果は新しいスロットに着地する** (`llmdocs/eq-slot-design.md` §1
     * 「外から来る曲線は必ず新しいスロットに着地する」)。開始点のスロットが残ることが要点 —
     * 15 分かけて探した結果と、それまで使っていた曲線を 1 タップで聴き比べられる。
     *
     * `vm.updateEq` を直に呼ぶ実装に戻すと、着地先が選択中スロットになって開始点が消える。
     */
    @Test
    fun applyingLandsInANewSlotAndKeepsTheOneYouStartedFrom() {
        val vm = vm()
        val start = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
        )
        vm.updateEq(mac) { start }
        val from = vm.slotsOf(mac).active

        val c = sessionAtResult(vm)
        assertTrue(c.apply())

        val landed = vm.slotsOf(mac).active
        assertNotEquals("選択中スロットを上書きしている", from, landed)
        assertEquals(vm.config.profileFor(mac)?.eq, vm.slotsOf(mac).slot(landed)?.eq)
        assertEquals("開始点の曲線が消えた", start, vm.slotsOf(mac).slot(from)?.eq)
        // 既定名は表示層の文言。訳文をデータに焼くと端末の言語を替えたときに嘘になる。
        assertEquals("", vm.slotsOf(mac).slot(landed)?.name)
    }

    /** 焼けなかったときは何も保存しない (スロットも増えない)。画面はこの false で開いたまま留まる。 */
    @Test
    fun aFailedBakeLandsNothing() {
        val vm = vm()
        // パラメトリックが満杯なら、ゲインの付いた軸のバンドを足す余地が無く bake は null。
        val full = List(EqSettings.MAX_BANDS) { EqBand(freqHz = 100 + it, q100 = 141, gainDb10 = 10) }
        vm.updateEq(mac) { EqSettings(enabled = true, mode = EqMode.PARAMETRIC, bands = full) }
        val before = vm.slotsOf(mac)

        val c = session(vm)
        repeat(4) { answerPreferringMoreBass(c, vm) }
        c.finishNow()
        check(c.phase == EqFinderPhase.RESULT) { "結果画面まで進めなかった" }
        check(c.result!!.axes.any { it.deltaDb10 != 0 }) { "推定が動いていない (bake が通ってしまう)" }

        assertTrue("焼けない前提が崩れた", !c.apply())
        assertTrue(c.bakeFailed)
        assertEquals(before, vm.slotsOf(mac))
        assertEquals("焼けなかったのに永続化が走った", full, vm.config.profileFor(mac)?.eq?.bands)
        // 画面は開いたまま留まるので、結果の音も鳴らしたまま (プレビューは解除しない)。
        assertNotNull(vm.eqPreview)
    }

    /**
     * 「低音は多いほうが良い」と一貫して答える聴き手。**固定で A と答えてはいけない** —
     * A/B の割当は試行ごとにランダムなので、推定が動くかどうかがシードに依存する
     * (動かなければオーバーレイが 0 のままで、満杯のパラメトリックでも bake が通ってしまう)。
     *
     * 候補の中身は押し込まれたプレビューから読む。土台は PEAKING だけなので、
     * LOW_SHELF は軸が足したバンドしかない。
     */
    private fun answerPreferringMoreBass(c: EqFinderController, vm: MainViewModel) {
        c.listen(EqFinderCandidate.A)
        val a = bassDb10(vm)
        c.listen(EqFinderCandidate.B)
        val b = bassDb10(vm)
        c.answer(
            when {
                a > b -> EqFinderAnswer.A
                b > a -> EqFinderAnswer.B
                else -> EqFinderAnswer.SAME
            },
        )
    }

    private fun bassDb10(vm: MainViewModel): Int =
        vm.eqPreview!!.settings.bands.first { it.type == EqBandType.LOW_SHELF }.gainDb10

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
