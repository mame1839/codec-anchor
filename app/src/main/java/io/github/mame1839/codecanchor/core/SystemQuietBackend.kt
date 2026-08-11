package io.github.mame1839.codecanchor.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * [QuietSwitch] が触る本物の [AudioManager]。**判断は持たない** — 筋は [QuietSwitch] にある。
 */
class SystemQuietBackend(context: Context) : QuietSwitch.Backend {

    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * **同じ要求オブジェクトで取って返す。**別に作り直したものを渡すと解放されない。
     *
     * `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` は「短いあいだ独占するので、音量を下げて鳴り続ける
     * (ducking) のではなく止まってほしい」の意味。音量を下げるだけだと**スピーカーから小さく
     * 鳴る**ことになり、直したい症状がそのまま残る。
     */
    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // 自分は音を出さないので通知は捨てるが、Looper の無いスレッドから組んでも
            // 落ちないよう明示的にメインの Handler を渡す。
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()

    override fun requestFocus(): Boolean =
        runCatching { audio?.requestAudioFocus(request) }.getOrNull() ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandonFocus() {
        runCatching { audio?.abandonAudioFocusRequest(request) }
    }

    /**
     * `getDevices` は audioserver (AudioPolicyManager) が持つ「いま使える機器」を引くので、
     * **audioserver の作り直しで一度消えて戻る。**[QuietSwitch] はこの消えて戻るのを待つ。
     *
     * **読めなかったときは「無い」に倒す。**作り直しの直後は audioserver がまだ binder に
     * 出ていないので、ここが失敗するのは**まさに「戻っていない」瞬間**。true に倒すと
     * その 1 回で待ちが抜け、**漏れる窓の手前で音を戻してしまう** — 直したい症状が
     * そのまま残り、しかも静かに残る。
     *
     * 代償は、恒久的に読めない端末で [QuietSwitch.RESTORE_TIMEOUT_MS] だけ音楽が止まること。
     * **止まったことは聞けば分かるが、漏れが直っていないことは分からない。**
     */
    override fun bluetoothOutputPresent(): Boolean = runCatching {
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .any { it.type in BLUETOOTH_OUTPUT_TYPES }
    }.getOrDefault(false)

    /** 壁時計を使わない。時刻が巻き戻ると上限が効かなくなる。 */
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private companion object {
        /**
         * 音楽が流れうる Bluetooth の出口。**A2DP だけに絞らない** —
         * LE Audio のイヤホンや補聴器でも同じ経路で同じ症状が出る。
         *
         * 通話用の SCO と `TYPE_BLE_BROADCAST` は入れない。イヤホンで音楽を聴いている状態を
         * 表さないので、入れると「押さえる必要がある」の判定が緩くなる。
         */
        val BLUETOOTH_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
