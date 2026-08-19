package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqBand
import io.github.mame1839.codecanchor.core.EqSettings
import io.github.mame1839.codecanchor.ui.EqSessionPreview
import io.github.mame1839.codecanchor.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 探索セッションのプレビューが**押す値の決定にだけ**割り込むこと。
 *
 * 見張っている境界は 2 つ。**プレビューは対象の機器にしか勝たない** (押す直前に枠の持ち主が
 * 別のイヤホンへ替わっていたら、そちらには永続設定が行く) と、**プレビューは永続化しない**
 * (解除すれば保存済みの設定がそのまま戻る)。どちらも破れると「別の機器に試聴中の曲線が掛かる」
 * 「聴き比べの候補が本物の設定として残る」という、画面からは気づけない壊れ方をする。
 */
@RunWith(RobolectricTestRunner::class)
class EqPreviewTest {

    private val macA = "AA:BB:CC:DD:EE:01"
    private val macB = "AA:BB:CC:DD:EE:02"

    private val persisted = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 1_000, q100 = 141, gainDb10 = 30)),
    )
    private val candidate = EqSettings(
        enabled = true,
        bands = listOf(EqBand(freqHz = 105, q100 = 71, gainDb10 = 40)),
        preampDb10 = -12,
    )

    private fun vm(): MainViewModel = MainViewModel(RuntimeEnvironment.getApplication()).apply {
        ensureProfile(macA)
        updateEq(macA) { persisted }
    }

    @Test
    fun previewWinsOnlyForItsOwnDevice() {
        val vm = vm()
        vm.setEqPreview(EqSessionPreview(macA, candidate))
        assertEquals(candidate, vm.eqSettingsToPush(macA))
        // 持ち主が別の機器に替わっていたら、その機器には永続設定 (無ければ既定のオフ) を書く。
        assertEquals(EqSettings(), vm.eqSettingsToPush(macB))
    }

    @Test
    fun clearingThePreviewRestoresThePersistedSettings() {
        val vm = vm()
        vm.setEqPreview(EqSessionPreview(macA, candidate))
        vm.setEqPreview(null)
        assertEquals(persisted, vm.eqSettingsToPush(macA))
    }

    // プレビューを載せて外すだけでは何も保存されない。updateEq (確定) だけが設定を変える。
    @Test
    fun previewDoesNotTouchThePersistedConfig() {
        val vm = vm()
        vm.setEqPreview(EqSessionPreview(macA, candidate))
        assertEquals(persisted, vm.config.profileFor(macA)?.eq)
    }
}
