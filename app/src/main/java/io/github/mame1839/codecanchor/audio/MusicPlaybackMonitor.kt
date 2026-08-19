package io.github.mame1839.codecanchor.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

class MusicPlaybackMonitor(
    private val audioManager: AudioManager?,
    private val handler: Handler,
    private val quietDelayMs: Long = QUIET_DELAY_MS,
) {
    var active: Boolean = false
        private set

    var rawActive: Boolean = false
        private set

    var onChange: ((Boolean) -> Unit)? = null

    private var started = false
    private var pendingQuiet: Runnable? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onRaw(configs.orEmpty().any(::isMedia))
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { audioManager?.registerAudioPlaybackCallback(callback, handler) }
        val now = runCatching { audioManager?.activePlaybackConfigurations }
            .getOrNull().orEmpty().any(::isMedia)
        rawActive = now
        active = now
    }

    fun stop() {
        if (!started) return
        started = false
        pendingQuiet?.let { handler.removeCallbacks(it) }
        pendingQuiet = null
        runCatching { audioManager?.unregisterAudioPlaybackCallback(callback) }
    }

    internal fun onRaw(nowActive: Boolean) {
        rawActive = nowActive
        pendingQuiet?.let { handler.removeCallbacks(it) }
        pendingQuiet = null
        if (nowActive) {
            if (!active) {
                active = true
                onChange?.invoke(true)
            }
        } else if (active) {
            val settle = Runnable {
                pendingQuiet = null
                active = false
                onChange?.invoke(false)
            }
            pendingQuiet = settle
            handler.postDelayed(settle, quietDelayMs)
        }
    }

    private fun isMedia(config: AudioPlaybackConfiguration): Boolean =
        runCatching { config.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
            .getOrDefault(false)

    companion object {
        const val QUIET_DELAY_MS = 1_500L
    }
}
