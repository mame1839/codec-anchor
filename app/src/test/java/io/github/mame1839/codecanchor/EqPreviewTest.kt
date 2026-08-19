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
        assertEquals(EqSettings(), vm.eqSettingsToPush(macB))
    }

    @Test
    fun clearingThePreviewRestoresThePersistedSettings() {
        val vm = vm()
        vm.setEqPreview(EqSessionPreview(macA, candidate))
        vm.setEqPreview(null)
        assertEquals(persisted, vm.eqSettingsToPush(macA))
    }

    @Test
    fun previewDoesNotTouchThePersistedConfig() {
        val vm = vm()
        vm.setEqPreview(EqSessionPreview(macA, candidate))
        assertEquals(persisted, vm.config.profileFor(macA)?.eq)
    }
}
