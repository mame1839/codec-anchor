package io.github.mame1839.codecanchor

import io.github.mame1839.codecanchor.core.EqDevices
import io.github.mame1839.codecanchor.core.QuietSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietSwitchTest {

    private class Fake(
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

    @Test
    fun skipsEverythingWhenNoBluetoothOutput() {
        val fake = Fake(listOf(false))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Outcome.NO_BLUETOOTH_OUTPUT, quiet.outcome)
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

        assertEquals(QuietSwitch.Outcome.NOT_HELD, quiet.outcome)
        quiet.close()
        assertEquals(0, fake.abandons)
    }

    @Test
    fun stillWaitsWhenFocusWasRefused() {
        val fake = Fake(listOf(true, false, true), focusGranted = false)
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        assertTrue(quiet.sawOutputGone)
    }

    @Test
    fun waitsUntilTheOutputComesBack() {
        val fake = Fake(listOf(true, false, false, false, true))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        assertEquals(3, fake.sleeps)
        assertTrue(quiet.sawOutputGone)
    }

    @Test
    fun returnsImmediatelyWhenTheOutputNeverWentAway() {
        val fake = Fake(listOf(true, true))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.RESTORED, quiet.awaitOutputRestored())
        assertEquals(0, fake.sleeps)
        assertFalse(quiet.sawOutputGone)
    }

    @Test
    fun givesUpAtTheDeadline() {
        val fake = Fake(listOf(true, false))
        val quiet = QuietSwitch.open(fake)

        assertEquals(QuietSwitch.Restored.TIMED_OUT, quiet.awaitOutputRestored())
        val maxPolls = QuietSwitch.RESTORE_TIMEOUT_MS / QuietSwitch.POLL_MS
        assertTrue("眠りすぎ: ${fake.sleeps}", fake.sleeps <= maxPolls)
        assertTrue("待たなすぎ: ${fake.sleeps}", fake.sleeps >= maxPolls - 1)
    }

    @Test
    fun releasesTheHoldEvenWhenTheOutputNeverComesBack() {
        val fake = Fake(listOf(true, false))
        val quiet = QuietSwitch.open(fake)
        quiet.awaitOutputRestored()
        quiet.close()

        assertEquals(1, fake.abandons)
    }

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
        val fake = Fake(listOf(true))
        QuietSwitch.open(fake).close()

        assertEquals(1, fake.abandons)
    }

    @Test
    fun timeoutIsShorterThanTheScriptTimeout() {
        assertTrue(QuietSwitch.RESTORE_TIMEOUT_MS < EqDevices.TIMEOUT_MS)
    }
}
