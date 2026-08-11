package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.QuietSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登録の適用のあいだ音を押さえる筋。
 *
 * ここが間違うと、症状は「静かな部屋で本体から音が出る」か「音楽が止まったまま戻らない」。
 * **どちらも実機でしか出ず、しかも 1 秒足らずの窓なので、後から原因を追うのが難しい。**
 */
class QuietSwitchTest {

    /**
     * 見に行った回数と眠った回数まで数える。
     *
     * **「待っているつもりで何も見ていない」を捕まえるにはこれが要る** — 出口の有無を
     * 返すだけの偽物では、待ちを丸ごと消しても全部のテストが通ってしまう。
     */
    private class Fake(
        /** `bluetoothOutputPresent()` が返す値を呼ばれた順に。尽きたら最後の値を返し続ける。 */
        private val presence: List<Boolean>,
        val focusGranted: Boolean = true,
    ) : QuietSwitch.Backend {
        var focusRequests = 0
        var abandons = 0
        var presenceReads = 0
        var sleeps = 0
        private var clock = 0L

        override fun requestFocus(): Boolean {
            focusRequests++
            return focusGranted
        }

        override fun abandonFocus() {
            abandons++
        }

        override fun bluetoothOutputPresent(): Boolean {
            val index = presenceReads.coerceAtMost(presence.lastIndex)
            presenceReads++
            return presence[index]
        }

        override fun nowMs(): Long = clock

        override fun sleep(ms: Long) {
            sleeps++
            clock += ms
        }
    }

    // ---- 押さえるかどうかの判断 -------------------------------------------

    @Test
    fun skipsEverythingWhenNoBluetoothOutput() {
        val fake = Fake(listOf(false))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Outcome.NO_BLUETOOTH_OUTPUT, quiet.outcome)
        // 落ちる先が無いなら再生を止めない。止めても得るものが無く、戻さないプレイヤーでは
        // 止めたままになる。
        assertEquals(0, fake.focusRequests)
        assertEquals(QuietSwitch.Restored.SKIPPED, quiet.awaitOutputRestored())
        quiet.close()
        assertEquals(0, fake.abandons)
    }

    @Test
    fun holdsWhenBluetoothOutputPresent() {
        val fake = Fake(listOf(true))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Outcome.HELD, quiet.outcome)
        assertEquals(1, fake.focusRequests)
    }

    @Test
    fun reportsWhenFocusIsRefused() {
        val fake = Fake(listOf(true), focusGranted = false)
        val quiet = QuietSwitch.open(fake)

        // フォーカスを尊重しないプレイヤーは止まらない。押さえられなかったことを隠さない。
        assertEquals(QuietSwitch.Outcome.NOT_HELD, quiet.outcome)
        quiet.close()
        // 取れていないものを返しに行かない。
        assertEquals(0, fake.abandons)
    }

    @Test
    fun stillWaitsWhenFocusWasRefused() {
        // 押さえられなくても待つ。**押し直し (pushEqParams) の前提は「機器が戻ったこと」**で、
        // フォーカスを取れたかどうかとは無関係。ここを早く返すと、枠が無いまま押すことになる。
        val fake = Fake(listOf(true, false, true), focusGranted = false)
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        assertTrue(quiet.sawOutputGone)
    }

    // ---- 待ち --------------------------------------------------------------

    @Test
    fun waitsUntilTheOutputComesBack() {
        // open で 1 回読む。その後 3 回消えていて、4 回目に戻る。
        val fake = Fake(listOf(true, false, false, false, true))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        // **待ちを丸ごと消すとここが 0 になる。**この 1 行が見張りの本体。
        assertEquals(3, fake.sleeps)
        assertTrue(quiet.sawOutputGone)
    }

    @Test
    fun returnsImmediatelyWhenTheOutputNeverWentAway() {
        val fake = Fake(listOf(true, true))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        assertEquals(0, fake.sleeps)
        // 消えるところを一度も見ていない = 「見る前に戻っていた」のか「そもそも
        // audioserver の生死を映していない」のかが付いていない。実機で確かめる手掛かり。
        assertFalse(quiet.sawOutputGone)
    }

    @Test
    fun givesUpAtTheDeadline() {
        val fake = Fake(listOf(true, false))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.TIMED_OUT, quiet.awaitOutputRestored())
        // 上限までで止まる。無限には待たない。
        val maxPolls = QuietSwitch.RESTORE_TIMEOUT_MS / QuietSwitch.POLL_MS
        assertTrue("眠りすぎ: ${fake.sleeps}", fake.sleeps <= maxPolls)
        assertTrue("待たなすぎ: ${fake.sleeps}", fake.sleeps >= maxPolls - 1)
    }

    @Test
    fun releasesTheHoldEvenWhenTheOutputNeverComesBack() {
        // 待ちきれなくても押さえは解く。登録そのものは成功しているので、
        // **待ちきれなかったことで音楽を止めたままにしない。**
        val fake = Fake(listOf(true, false))
        val quiet = QuietSwitch.open(fake)
        quiet.awaitOutputRestored()
        quiet.close()

        assertEquals(1, fake.abandons)
    }

    // ---- 解放 --------------------------------------------------------------

    @Test
    fun releasesOnce() {
        val fake = Fake(listOf(true))
        val quiet = QuietSwitch.open(fake)
        quiet.close()
        quiet.close()

        assertEquals(1, fake.abandons)
    }

    @Test
    fun releasesWithoutWaiting() {
        // 例外で待ちを飛ばして finally から close だけ来る経路。押さえたままにしない。
        val fake = Fake(listOf(true))
        QuietSwitch.open(fake).close()

        assertEquals(1, fake.abandons)
    }

    @Test
    fun timeoutIsShorterThanTheScriptTimeout() {
        // スクリプトが打ち切られた後もここで待ち続ける、を防ぐ。
        assertTrue(QuietSwitch.RESTORE_TIMEOUT_MS < EqDevices.TIMEOUT_MS)
    }
}
