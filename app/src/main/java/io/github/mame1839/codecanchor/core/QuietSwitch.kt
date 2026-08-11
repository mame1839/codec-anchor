package io.github.mame1839.codecanchor.core

/**
 * 登録の適用 (`audio_effects.xml` の書き換えと audioserver の作り直し) のあいだ、再生中の音が
 * **端末のスピーカーへ落ちないように押さえておく。**
 *
 * ## 直している症状
 *
 * 静かな部屋でイヤホンを着けて音楽を聴いているときに音響処理をオンにすると、**本体から数百 ms
 * だけ音が出る。**
 *
 * ## ⚠️ 漏れる窓は「audioserver が死んでいるあいだ」ではない
 *
 * 実機で測った (2026-08-11、Xiaomi 2407FPN8ER / Android 16)。再生中のトラックがどの出力ポートへ
 * 流れているかを `dumpsys audio` の `deviceIds` で追ったもの。**ポート 3 = 本体スピーカー /
 * ポート 35 = BT A2DP Out** (`dumpsys media.audio_policy` で確認):
 *
 * ```
 * +0.000  audioserver を kill
 * +0.713  dev=[35]   イヤホン
 * +0.761  dev=[3]    ← 本体スピーカー。ここから漏れる
 * +1.007  dev=[3]
 * +1.061  dev=[35]   イヤホンへ復帰
 * ```
 *
 * **再生中の音が本体スピーカーへ約 300 ms 流れた。**これがユーザの報告そのもの。
 *
 * 別に測った機器の側:
 *
 * ```
 * +0.000  kill
 * +0.070  新しい audioserver の pid が既に存在する
 * +1.000  A2DP が AudioPolicyManager の「使える出力機器」に戻る
 * ```
 *
 * **audioserver は 70 ms で戻るが、イヤホンが戻るのは 1 秒後。**死んでいるあいだは誰も音を
 * 出せないので無害で、**危ないのは戻った直後の、この 1 秒の隙間。**
 *
 * **つまり「audioserver が戻った」で音を戻すと、漏れる窓が始まる直前に手を離すことになる。**
 * [awaitOutputRestored] が待つのは audioserver ではなく **Bluetooth の出口が戻ったこと。**
 * 上の測定は、その出口が**実際に消えて戻る**ことの裏付けでもある (消えないなら待ちは無意味)。
 *
 * ## 待つ相手は「経路に載ったか」ではなく「機器が使えるか」
 *
 * **押さえるのが効くと、待っている経路のほうが消える。**上のログで SPEAKER の patch ができたのは
 * 再生が続いていたからで、音を止めれば audioserver が戻っても patch は 1 つも作られない。
 * **経路 (audio patch / エフェクトの sub effect) を待つと、押さえが効いたときに限って必ず
 * 空振りし、押さえが効かなかったときだけ通る** — 最悪の裏返り方をする。
 *
 * 機器が使えるかどうかは再生の有無と無関係なので、こちらを見る。
 *
 * ## Bluetooth の出口が無いときは何もしない
 *
 * 既に本体から鳴っているなら、落ちる先が無いので**驚きが無い。**そこで再生を止めても得るものが
 * 無いうえ、[AUDIOFOCUS_GAIN][android.media.AudioManager.AUDIOFOCUS_GAIN] で自動的に再生を
 * 戻さないプレイヤーでは**止めたままになる**ので、介入は必要なときだけに限る。
 *
 * ## 消音ではなく音声フォーカスを使う理由 — 消音は危ないうえに効かない
 *
 * `STREAM_MUSIC` の消音は 2 つの理由で不採用。**どちらも AOSP (Android 16) で確認したもの。**
 *
 * 1. **詰まると消音のまま残る。**プロセスの死亡で自動解除されるのは **API 22 より前だけ**で、
 *    現在の `VolumeStreamState.mIsMuted` は素の boolean。**紐づく DeathRecipient が無い。**
 *    アプリが途中で死ねば、誰かが解除するまで端末の音楽が消音のまま残る
 * 2. **そもそもこの窓を塞げない。**消音は system_server 側にあり、作り直した audioserver へ
 *    再適用されるのは `onAudioServerDied()` → `onReinitVolumes()` を抜けた後。
 *    **起動したての audioserver は既定の音量で動いていて、消音を知らない。**
 *    そして下記のとおり、トラックの作り直しはそれより先に着きうる
 *
 * フォーカスはプロセスが死ねばシステムが解放するので、**危険な状態が残らない。**
 * 最悪の残り方が「音楽が止まったまま」で、ユーザが再生を押せば直る。
 *
 * ## ⚠️ フォーカスは協調であって強制ではない
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` でも、相手に届くのは `AUDIOFOCUS_LOSS_TRANSIENT`。
 * **EXCLUSIVE の意味は「音量を下げて鳴り続けるな」だけで、止めるのは相手の仕事。**
 * **フォーカスを無視するプレイヤーは鳴り続け、この直しは効かない。**
 *
 * しかも [Outcome.NOT_HELD] で分かるのは**フォーカスを取れなかったこと**だけで、
 * **取れたうえで相手が無視した場合は区別が付かない。**要求はほぼ常に通るので、
 * 実際に効いたかどうかはここからは分からない。
 *
 * ## なぜ音が漏れるのか (AOSP を読んだ範囲)
 *
 * audioserver が戻ると `AudioTrack::restoreTrack_l` がトラックを作り直す。**このとき
 * A2DP がまだ APM に戻っていなければスピーカーが返り、生成は「成功」する。**
 * リトライは生成が**失敗**したときだけなので、**スピーカーで成功したらそのまま鳴る。**
 *
 * 一方 A2DP を APM へ戻すのは system_server の `onRestoreDevices()` で、そこへ着くまでに
 * **500 ms 粒度のポーリング**とハンドラのホップが挟まる。**この競争にアプリが勝つと音が漏れる。**
 * 再生していなければ作り直すトラックが無いので、競争そのものが起きない。
 *
 * ## 使い方
 *
 * ```kotlin
 * val quiet = QuietSwitch.open(QuietSwitch.system(context))
 * try {
 *     val result = EqDevices.apply(macs)   // XML → audioserver の作り直し
 *     quiet.awaitOutputRestored()          // イヤホンが戻るまで押さえたまま
 *     pushEqParams()                       // ⚠️ 音を戻す前に押し直す (下記)
 * } finally {
 *     quiet.close()
 * }
 * ```
 *
 * **`pushEqParams()` を [close] より前に置くこと。**登録の適用は audioserver を作り直して
 * **枠の添字を変える**が `params[]` はインスタンスの死を越えて残るので、押し直さないと
 * **B の登録を外した後に A が B の添字へ載って、A に B の曲線が掛かったまま鳴る。**
 * 音を戻してから押すと、その隙間が**そのまま聞こえる。**
 */
class QuietSwitch private constructor(private val backend: Backend) : AutoCloseable {

    /**
     * 触る相手。**テストのために切り出してある** — 本物の [android.media.AudioManager] は
     * ホストの JVM で動かないので、判断の筋だけを持つ [QuietSwitch] とは分けておく。
     */
    interface Backend {
        /** 音声フォーカスを取る。取れたら true。**取れなくても操作は続ける** (押さえられないだけ)。 */
        fun requestFocus(): Boolean

        /** 取ったフォーカスを返す。取れていないときは呼ばれない。 */
        fun abandonFocus()

        /**
         * いま出力に使える Bluetooth の音声出口があるか。
         *
         * **audioserver の生死で変わる値を見ること。**system_server 側の台帳 (再起動しても
         * 消えない) を見ると、**待ちが常に即座に成立して静かに外れる。**
         */
        fun bluetoothOutputPresent(): Boolean

        /** 単調増加のミリ秒。実時間の壁時計を使わないこと (時刻の巻き戻しで上限が壊れる)。 */
        fun nowMs(): Long

        fun sleep(ms: Long)
    }

    /** 押さえられたかどうか。**呼び出し側が診断に出せるように返す。** */
    enum class Outcome {
        /** Bluetooth の出口が無いので何もしなかった。落ちる先が無いので、これで正しい。 */
        NO_BLUETOOTH_OUTPUT,

        /** フォーカスを取れなかった。押さえずに進んだ。 */
        NOT_HELD,

        /** 押さえた。 */
        HELD,
    }

    /** [awaitOutputRestored] の結果。 */
    enum class Restored {
        /** 押さえていないので待っていない。 */
        SKIPPED,

        /** Bluetooth の出口が戻った。 */
        RESTORED,

        /** 上限まで待っても戻らなかった。**押さえは解く** — これ以上待っても音が戻らないだけ。 */
        TIMED_OUT,
    }

    var outcome: Outcome = Outcome.NO_BLUETOOTH_OUTPUT
        private set

    var restored: Restored = Restored.SKIPPED
        private set

    /**
     * **待っているあいだに出口が消えるのを一度でも見たか。**
     *
     * 見えないまま [Restored.RESTORED] になったときは、2 つの区別が付いていない —
     * 「見に行く前に戻っていた」のか、**[Backend.bluetoothOutputPresent] が audioserver の
     * 生死を映していない**のか。後者なら待ちは何も見ずに通っているだけで、**押さえは漏れる窓の
     * 手前で解ける。**実機で確かめるときの手掛かりとして残す。
     */
    var sawOutputGone: Boolean = false
        private set

    private var closed = false

    /**
     * Bluetooth の出口が戻るまで待つ。**押さえたまま呼ぶこと。**
     *
     * 上限を超えても [close] は行う。登録そのものは成功しているので、**待ちきれなかったことを
     * 失敗に化けさせない。**
     */
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

    /** 押さえを解く。**2 回呼んでも 1 回しか解かない。** */
    override fun close() {
        if (closed) return
        closed = true
        if (outcome == Outcome.HELD) backend.abandonFocus()
    }

    companion object {
        /**
         * 押さえを取る。**Bluetooth の出口があるときだけフォーカスを取る** (無ければ落ちる先が
         * 無いので、再生を止める意味が無い)。
         */
        fun open(backend: Backend): QuietSwitch {
            val quiet = QuietSwitch(backend)
            if (!backend.bluetoothOutputPresent()) {
                quiet.outcome = Outcome.NO_BLUETOOTH_OUTPUT
                return quiet
            }
            quiet.outcome = if (backend.requestFocus()) Outcome.HELD else Outcome.NOT_HELD
            return quiet
        }

        /**
         * 上限。**時間で進む実装ではなく、上限としてだけ使う** — 戻ったかどうかは
         * [Backend.bluetoothOutputPresent] で決める。実機で見た戻りは約 1.5 秒なので、
         * 遅い端末を見込んでも十分に余裕がある。
         *
         * `EqDevices.TIMEOUT_MS` (30 秒) より短くしてあること。**逆転すると、スクリプトが
         * 打ち切られた後もここで待ち続ける。**
         */
        const val RESTORE_TIMEOUT_MS = 8_000L

        /** 見に行く間隔。**これがそのまま「音を戻すのが遅れる時間」の上限になる。** */
        const val POLL_MS = 50L
    }
}
