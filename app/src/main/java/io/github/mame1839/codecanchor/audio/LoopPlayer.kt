package io.github.mame1839.codecanchor.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.mame1839.codecanchor.core.Spectrum
import kotlin.math.max
import kotlin.math.min

class LoopPlayer(private val audioManager: AudioManager?) {

    var onFocusLost: (() -> Unit)? = null

    private var loop = FloatArray(0)
    private var channels = 1
    private var sampleRate = 48_000
    private var track: AudioTrack? = null
    private var feeder: Thread? = null

    @Volatile
    private var feeding = false
    private var focusRequest: AudioFocusRequest? = null

    val isPlaying: Boolean get() = feeding

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    @Synchronized
    fun prepare(pcm: FloatArray, channels: Int, sampleRate: Int, fadeMs: Int = FADE_MS): Boolean {
        if (channels !in 1..2 || sampleRate !in 4_000..192_000 || pcm.size < channels) return false
        stopLocked()
        loop = Spectrum.crossfadeLoop(pcm, channels, fadeMs * sampleRate / 1_000)
        this.channels = channels
        this.sampleRate = sampleRate
        return loop.isNotEmpty()
    }

    @Synchronized
    fun play(): Boolean {
        if (feeding) return true
        if (loop.isEmpty()) return false
        if (!requestFocus()) return false
        val built = buildTrack()
        if (built == null || runCatching { built.play() }.isFailure) {
            runCatching { built?.release() }
            abandonFocus()
            return false
        }
        track = built
        feeding = true
        val data = loop
        val frameFloats = channels
        feeder = Thread { feed(built, data, frameFloats) }.also {
            it.name = "CodecAnchorLoop"
            it.isDaemon = true
            it.start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    @Synchronized
    fun release() {
        stopLocked()
        loop = FloatArray(0)
    }

    private fun stopLocked() {
        feeding = false
        val t = track
        track = null
        if (t != null) {
            runCatching { t.pause() }
            runCatching { t.flush() }
        }
        feeder?.let { runCatching { it.join(1_000) } }
        feeder = null
        if (t != null) {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        abandonFocus()
    }

    private fun feed(track: AudioTrack, data: FloatArray, channels: Int) {
        val chunk = FloatArray(CHUNK_FRAMES * channels)
        var pos = 0
        while (feeding) {
            var filled = 0
            while (filled < chunk.size) {
                val n = min(chunk.size - filled, data.size - pos)
                System.arraycopy(data, pos, chunk, filled, n)
                filled += n
                pos += n
                if (pos == data.size) pos = 0
            }
            var off = 0
            while (off < chunk.size) {
                if (!feeding) return
                val n = runCatching {
                    track.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(-1)
                if (n <= 0) {
                    if (n < 0) feeding = false
                    return
                }
                off += n
            }
        }
    }

    private fun buildTrack(): AudioTrack? = runCatching {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = max(minBytes * 2, sampleRate * channels * Float.SIZE_BYTES / 5)
        AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
            .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
    }.getOrNull()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop()
            onFocusLost?.invoke()
        }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    companion object {
        const val FADE_MS = 30

        private const val CHUNK_FRAMES = 4_096
    }
}
