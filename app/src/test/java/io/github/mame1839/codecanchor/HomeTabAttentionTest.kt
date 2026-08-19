package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.ui.ModuleState
import io.github.mame1839.codecanchor.ui.needsAttention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabAttentionTest {

    @Test
    fun anInactiveModuleAsksToBeLookedAt() {
        assertTrue(needsAttention(ModuleState.INACTIVE, configBroken = false))
    }

    @Test
    fun brokenSettingsAskToBeLookedAt() {
        assertTrue(needsAttention(ModuleState.ACTIVE, configBroken = true))
    }

    @Test
    fun aWorkingModuleDoesNotAsk() {
        assertFalse(needsAttention(ModuleState.ACTIVE, configBroken = false))
    }

    @Test
    fun checkingDoesNotAskYet() {
        assertFalse(needsAttention(ModuleState.CHECKING, configBroken = false))
    }
}
