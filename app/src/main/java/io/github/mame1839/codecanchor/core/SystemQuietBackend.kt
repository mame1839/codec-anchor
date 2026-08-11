package io.github.mame1839.codecanchor.core

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * [QuietSwitch] が触る本物の [AudioManager]。**判断は持たない** — 筋は [QuietSwitch] にある。
 */
class SystemQuietBackend(private val audio: AudioManager?) : QuietSwitch.Backend {

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
     * 読めなかったときに「無い」へ倒す判断は [AudioOutputs.anyBluetooth] 側にある。
     */
    override fun bluetoothOutputPresent(): Boolean = AudioOutputs.anyBluetooth(audio)

    /** 壁時計を使わない。時刻が巻き戻ると上限が効かなくなる。 */
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
