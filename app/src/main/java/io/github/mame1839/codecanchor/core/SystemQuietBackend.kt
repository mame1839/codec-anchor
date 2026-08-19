package io.github.mame1839.codecanchor.core

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class SystemQuietBackend(private val audio: AudioManager?) : QuietSwitch.Backend {

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
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
