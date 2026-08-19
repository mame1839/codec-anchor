package io.github.mame1839.codecanchor

import android.app.Application
import io.github.mame1839.codecanchor.bridge.SlotStore
import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqMode
import io.github.mame1839.codecanchor.core.EqPreset
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.core.EqSlotBook
import io.github.mame1839.codecanchor.core.EqSolver
import io.github.mame1839.codecanchor.ui.MainViewModel
import io.github.mame1839.codecanchor.ui.flatEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * スロットの切り替えと編集 (`llmdocs/eq-slot-design.md` §5 段 2) の配線。
 *
 * **一番の見張りは「選択を動かしてから updateEq」の順番**
 * ([EqSlotBook.reconciledWith] の KDoc)。逆順だと write-through が新しい曲線を
 * **まだ選択中の古いスロット**へ写して上書きするので、切り替えて戻ってきたときに
 * 前の曲線が消えている。値としては何も壊れて見えないので、この順番でしか捕まらない。
 */
@RunWith(RobolectricTestRunner::class)
class EqSlotSelectionTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    private val curveA = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val curveB = EqSettings(
        enabled = true,
        mode = EqMode.PARAMETRIC,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = -25)),
        preampDb10 = -60,
    )

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun viewModel(): MainViewModel = MainViewModel(app).also { it.ensureProfile(mac) }

    private fun MainViewModel.eqOf(): EqSettings? = config.profileFor(mac)?.eq

    /**
     * **フラットの中身は必ず [EqSlotBook.isNeutral] を満たすこと。**満たさないと、フラットの
     * チップを押すたびに和解が「中立でない曲線」と読んで新しいスロットを作り、**押すたびに
     * スロットが 1 つ増える。**画面側 ([flatEq]) と保存側 (isNeutral) が別のファイルにある
     * 一致なので、ここで見張る。
     */
    @Test
    fun theFlatCurveIsWhatTheLedgerCallsNeutral() {
        val bases = listOf(
            EqSettings(enabled = true),
            curveA,
            curveB,
            EqSettings(enabled = true, bandCount = 31, preampDb10 = -120),
        )
        for (base in bases) {
            val flat = flatEq(base)
            assertTrue("$base -> $flat が中立でない", EqSlotBook.isNeutral(flat))
            // 平ら = どのバンドも 0 dB。中立の判定 (bands の gainDb10) と、耳に届く形
            // (合成応答) の両方を見る。
            assertTrue(EqSolver.graphicTargetsDb10(flat.bands).all { it == 0 })
            assertEquals(base.enabled, flat.enabled)
        }
    }

    /**
     * **切り替えて戻ってきたら、置いてきた曲線がそのまま在ること。**
     * 「選択を動かしてから updateEq」を逆順にすると、B を選んだ瞬間に A の中身が
     * B の曲線で上書きされてこのテストが落ちる。
     */
    @Test
    fun switchingSlotsLeavesTheCurveYouCameFromAlone() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active

        vm.addSlot(mac)
        val second = vm.slotsOf(mac).active
        assertNotEquals(first, second)
        vm.updateEq(mac) { curveB }

        vm.selectSlot(mac, first)
        assertEquals(curveA, vm.eqOf())
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals(curveB, vm.slotsOf(mac).slot(second)?.eq)

        vm.selectSlot(mac, second)
        assertEquals(curveB, vm.eqOf())
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals(2, vm.slotsOf(mac).slots.size)
    }

    /** フラットは実体を保存しない。選んでもスロットは増えず、置いてきた曲線も残る。 */
    @Test
    fun selectingFlatSilencesTheSoundWithoutAddingASlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val custom = vm.slotsOf(mac).active

        vm.selectSlot(mac, EqSlotBook.FLAT_ID)
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)
        assertEquals(1, vm.slotsOf(mac).slots.size)
        assertEquals(curveA, vm.slotsOf(mac).slot(custom)?.eq)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        // 主電源は別の層。フラットは「オンの中の中立な曲線」であってオフではない。
        assertEquals(true, vm.eqOf()?.enabled)

        vm.selectSlot(mac, custom)
        assertEquals(curveA, vm.eqOf())
    }

    /** 「+」はフラットを種にした新しいスロット。押した時点で選択も移る。 */
    @Test
    fun addSlotStartsFlatAndSelectsItself() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active

        vm.addSlot(mac)
        val added = vm.slotsOf(mac).active
        assertNotEquals(first, added)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        assertEquals(vm.eqOf(), vm.slotsOf(mac).slot(added)?.eq)
        assertEquals(curveA, vm.slotsOf(mac).slot(first)?.eq)
        assertEquals("", vm.slotsOf(mac).slot(added)?.name)
    }

    /**
     * **プリセットの適用は既存スロットを上書きしない** (`eq-slot-design.md` §1
     * 「外から来る曲線は必ず新しいスロットに着地する」)。名前はプリセットのものを引き継ぐ。
     */
    @Test
    fun applyingAPresetLandsInANewSlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val working = vm.slotsOf(mac).active
        vm.savePreset("夜用", curveB)

        vm.applyPreset(mac, "夜用")
        val landed = vm.slotsOf(mac).active
        assertNotEquals(working, landed)
        assertEquals(curveB, vm.eqOf())
        assertEquals("夜用", vm.slotsOf(mac).slot(landed)?.name)
        // 作りかけは残っていて、チップ 1 タップで戻れる
        assertEquals(curveA, vm.slotsOf(mac).slot(working)?.eq)
    }

    /**
     * 主電源とスロットは別の層なので、**スロットを選んだだけでイコライザーが切れてはいけない。**
     * `enabled=false` で書き出されたプリセットを読み込んだときにだけ起きる。
     */
    @Test
    fun landingAnExternalCurveNeverTurnsTheEqualiserOff() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        vm.savePreset("切れたまま", curveB.copy(enabled = false))

        vm.applyPreset(mac, "切れたまま")
        assertEquals(true, vm.eqOf()?.enabled)
        assertEquals(true, vm.slotsOf(mac).activeSlot()?.eq?.enabled)
    }

    @Test
    fun duplicatingCopiesTheCurveIntoANewUnnamedSlot() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val source = vm.slotsOf(mac).active
        vm.renameSlot(mac, source, "昼用")

        vm.duplicateSlot(mac, source)
        val copy = vm.slotsOf(mac).active
        assertNotEquals(source, copy)
        assertEquals(curveA, vm.slotsOf(mac).slot(copy)?.eq)
        // 「〜のコピー」は作らない。訳文をデータに焼くと端末の言語を替えたときに嘘になる。
        assertEquals("", vm.slotsOf(mac).slot(copy)?.name)
        assertEquals("昼用", vm.slotsOf(mac).slot(source)?.name)
    }

    /** 名前は音に関わらない。改名で曲線が動かないこと。 */
    @Test
    fun renamingDoesNotTouchTheSound() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val id = vm.slotsOf(mac).active

        vm.renameSlot(mac, id, "  夜用  ")
        assertEquals("夜用", vm.slotsOf(mac).slot(id)?.name)
        assertEquals(curveA, vm.eqOf())

        // 空にすると未命名へ戻る (表示は既定名)。
        vm.renameSlot(mac, id, "   ")
        assertEquals("", vm.slotsOf(mac).slot(id)?.name)
        assertEquals(curveA, vm.eqOf())
    }

    @Test
    fun deletingTheSlotYouAreOnFallsBackToFlat() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val id = vm.slotsOf(mac).active

        vm.deleteSlot(mac, id)
        assertNull(vm.slotsOf(mac).slot(id))
        assertEquals(EqSlotBook.FLAT_ID, vm.slotsOf(mac).active)
        assertTrue(EqSlotBook.isNeutral(vm.eqOf()!!))
        assertEquals(true, vm.eqOf()?.enabled)
    }

    @Test
    fun deletingAnotherSlotDoesNotChangeWhatIsPlaying() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val other = vm.slotsOf(mac).active
        vm.addSlot(mac)
        val current = vm.slotsOf(mac).active
        vm.updateEq(mac) { curveB }

        vm.deleteSlot(mac, other)
        assertEquals(current, vm.slotsOf(mac).active)
        assertEquals(curveB, vm.eqOf())
        assertEquals(1, vm.slotsOf(mac).slots.size)
    }

    /** id は消しても振り直さない — 既定の表示名がこの番号なので、振り直すと別の名前が変わる。 */
    @Test
    fun slotIdsAreNotReusedAfterADelete() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        val first = vm.slotsOf(mac).active
        vm.addSlot(mac)
        val second = vm.slotsOf(mac).active
        assertEquals(listOf("1", "2"), listOf(first, second))

        vm.deleteSlot(mac, first)
        assertEquals(listOf("2"), vm.slotsOf(mac).slots.map { it.id })
        vm.addSlot(mac)
        assertEquals(listOf("2", "3"), vm.slotsOf(mac).slots.map { it.id })
    }

    /** 選択も中身も次の起動に残ること (台帳は SlotStore に書かれている)。 */
    @Test
    fun theSelectionSurvivesARestart() {
        val vm = viewModel()
        vm.updateEq(mac) { curveA }
        vm.addSlot(mac)
        vm.updateEq(mac) { curveB }
        val active = vm.slotsOf(mac).active
        assertEquals(vm.slots, SlotStore(app).load())

        val restarted = MainViewModel(app)
        assertEquals(active, restarted.slotsOf(mac).active)
        assertEquals(curveA, restarted.slotsOf(mac).slot("1")?.eq)
        assertEquals(curveB, restarted.eqOf())
    }

    // 保存し直しても中身が化けないこと (EqPreset は名前を必須にしている)。
    @Test
    fun savingASlotAsAPresetKeepsTheCurve() {
        val vm = viewModel()
        vm.updateEq(mac) { curveB }
        vm.savePreset("寝る前", vm.slotsOf(mac).activeSlot()!!.eq)
        assertEquals(EqPreset("寝る前", curveB), vm.presets.presets.single())
    }
}
