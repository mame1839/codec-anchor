package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.ui.ModuleState
import io.github.mame1839.codecanchor.ui.needsAttention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状態タブに出す印の条件。
 *
 * **この印だけが「機器タブしか見ない人」への唯一の入口。**モジュールが無効だと適用が 1 件も
 * 起きないが、機器タブには**空の一覧が出るだけ**で理由がどこにも出ない。
 * 印を出す条件を緩めても厳しくしても、そこが壊れる:
 *
 * - 緩めて常時点くようにすると、点いていることに意味が無くなる
 * - 厳しくして無効のときに点かないと、**初めて入れた人が理由に辿り着けない**
 */
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
