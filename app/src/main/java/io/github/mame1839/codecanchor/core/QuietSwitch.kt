package io.github.mame1839.codecanchor.core

class QuietSwitch private constructor(private val backend: Backend) : AutoCloseable {

    interface Backend {
        fun requestFocus(): Boolean

        fun abandonFocus()

        fun bluetoothOutputPresent(): Boolean

        fun nowMs(): Long

        fun sleep(ms: Long)
    }

    enum class Outcome {
        NO_BLUETOOTH_OUTPUT,
        NOT_HELD,
        HELD,
    }

    enum class Restored {
        SKIPPED,
        RESTORED,
        TIMED_OUT,
    }

    var outcome: Outcome = Outcome.NO_BLUETOOTH_OUTPUT
        private set

    var restored: Restored = Restored.SKIPPED
        private set

    var sawOutputGone: Boolean = false
        private set

    private var closed = false

    fun awaitOutputRestored(): Restored {
        if (outcome == Outcome.NO_BLUETOOTH_OUTPUT) return restored
        val deadline = backend.nowMs() + RESTORE_TIMEOUT_MS
        while (true) {
            if (backend.bluetoothOutputPresent()) {
                restored = Restored.RESTORED
                return restored
            }
            sawOutputGone = true
            if (backend.nowMs() >= deadline) {
                restored = Restored.TIMED_OUT
                return restored
            }
            backend.sleep(POLL_MS)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (outcome == Outcome.HELD) backend.abandonFocus()
    }

    companion object {
        fun open(backend: Backend): QuietSwitch {
            val quiet = QuietSwitch(backend)
            if (!backend.bluetoothOutputPresent()) {
                quiet.outcome = Outcome.NO_BLUETOOTH_OUTPUT
                return quiet
            }
            quiet.outcome = if (backend.requestFocus()) Outcome.HELD else Outcome.NOT_HELD
            return quiet
        }

        const val RESTORE_TIMEOUT_MS = 8_000L

        const val POLL_MS = 50L
    }
}
