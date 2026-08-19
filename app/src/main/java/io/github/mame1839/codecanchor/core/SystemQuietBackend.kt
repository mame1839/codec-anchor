package io.github.mame1839.codecanchor.core

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// QuietSwitch が触る本物の AudioManager。判断は持たない (筋は QuietSwitch 側)。
class SystemQuietBackend(private val audio: AudioManager?) : QuietSwitch.Backend {

    // ⚠️ 同じ要求オブジェクトで取って返す (別に作り直すと解放されない)。GAIN_TRANSIENT_EXCLUSIVE は
    // 「音量を下げて鳴り続けるのではなく止まってほしい」の意味 — ducking だと症状がそのまま残る。
    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // Looper の無いスレッドから組んでも落ちないよう明示的にメインの Handler を渡す。
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()

    override fun requestFocus(): Boolean =
        runCatching { audio?.requestAudioFocus(request) }.getOrNull() ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandonFocus() {
        runCatching { audio?.abandonAudioFocusRequest(request) }
    }

    override fun bluetoothOutputPresent(): Boolean = AudioOutputs.anyBluetooth(audio)

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
