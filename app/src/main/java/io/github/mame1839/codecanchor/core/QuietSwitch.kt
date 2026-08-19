package io.github.mame1839.codecanchor.core

// 登録の適用 (audio_effects.xml の書き換えと audioserver の作り直し) のあいだ、再生中の音が
// 端末のスピーカーへ落ちないように押さえておく。実測・機序・設計判断は audio-effects.md
// 「登録の適用中に音が本体スピーカーへ漏れる」。
//
// ⚠️ 待つ相手は「audioserver が戻ったか」ではなく「Bluetooth の出口が使えるか」(awaitOutputRestored)。
// ⚠️ pushEqParams() は close() より前に呼ぶこと — 枠の添字が変わるので、押し直さないと
// 別の機器の曲線が掛かったまま鳴り、音を戻してから押すとその隙間が聞こえる。
//
// ```kotlin
// val quiet = QuietSwitch.open(QuietSwitch.system(context))
// try {
//     val result = EqDevices.apply(macs)
//     quiet.awaitOutputRestored()
//     pushEqParams()
// } finally {
//     quiet.close()
// }
// ```
class QuietSwitch private constructor(private val backend: Backend) : AutoCloseable {

    // テストのために切り出してある (本物の AudioManager はホストの JVM で動かない)。
    interface Backend {
        fun requestFocus(): Boolean

        fun abandonFocus()

        // ⚠️ audioserver の生死で変わる値を見ること。system_server 側の台帳 (再起動しても
        // 消えない) を見ると、待ちが常に即座に成立して静かに外れる。
        fun bluetoothOutputPresent(): Boolean

        // 単調増加のミリ秒。壁時計を使わないこと (時刻の巻き戻しで上限が壊れる)。
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

    // 待っているあいだに出口が消えるのを一度でも見たか。見えないまま RESTORED になったときは
    // 「見に行く前に戻っていた」のか「bluetoothOutputPresent が生死を映していない」のか
    // 区別が付かない (実機で確かめるときの手掛かり)。
    var sawOutputGone: Boolean = false
        private set

    private var closed = false

    // ⚠️ 押さえたまま呼ぶこと。上限を超えても close は行う (待ちきれなかったことを失敗に化けさせない)。
    fun awaitOutputRestored(): Restored {
        if (outcome == Outcome.NO_BLUETOOTH_OUTPUT) return restored
        val deadline = backend.nowMs() + RESTORE_TIMEOUT_MS
        while (true) {
            if (backend.bluetoothOutputPresent()) {
                restored = Restored.RESTORED
                return restored
            }
            // ここを通った = 出口が消えている瞬間を見た。待ちが本物であることの証拠。
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

        // ⚠️ EqDevices.TIMEOUT_MS (30 秒) より短くしてあること (逆転するとスクリプトが
        // 打ち切られた後もここで待ち続ける)。
        const val RESTORE_TIMEOUT_MS = 8_000L

        const val POLL_MS = 50L
    }
}
