package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.ui.ModuleState
import io.github.mame1839.codecanchor.ui.needsAttention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状態タブに出す印の条件 (根拠は HomeScreen.kt の needsAttention)。 */
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

    /**
     * **確認中は印を出さない。**起動のたびに CHECKING を通るので、出すと毎回一瞬点く。
     * 「点いている = 見に行く必要がある」が崩れる。
     */
    @Test
    fun checkingDoesNotAskYet() {
        assertFalse(needsAttention(ModuleState.CHECKING, configBroken = false))
    }
}
