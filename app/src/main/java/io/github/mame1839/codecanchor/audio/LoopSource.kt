package io.github.mame1839.codecanchor.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.github.mame1839.codecanchor.core.Spectrum
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class Loaded(
    val pcm: FloatArray,
    val channels: Int,
    val sampleRate: Int,
    val trackDurationMs: Long,
) {
    fun monoMix(): FloatArray = Spectrum.monoMix(pcm, channels)
}

object LoopSource {

    private const val TIMEOUT_US = 10_000L
    private const val STALL_LIMIT = 1_000

    const val MAX_CLIP_MS = 120_000L

    fun load(context: Context, uri: Uri, startMs: Long, lengthMs: Long): Result<Loaded> = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            decode(extractor, startMs.coerceAtLeast(0), lengthMs.coerceIn(1, MAX_CLIP_MS))
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, startMs: Long, lengthMs: Long): Loaded {
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                format = f
                break
            }
        }
        val trackFormat = format ?: error("音声トラックが無い")
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: error("MIME が読めない")
        val durationMs =
            if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION) / 1_000
            } else {
                0L
            }
        val startUs = startMs * 1_000
        val endUs = (startMs + lengthMs) * 1_000
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(trackFormat, null, null, 0)
            codec.start()
            return drain(extractor, codec, trackFormat, startUs, endUs, durationMs)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        startUs: Long,
        endUs: Long,
        durationMs: Long,
    ): Loaded {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val chunks = ArrayList<FloatArray>()
        var inputDone = false
        var pastEnd = false
        var outputDone = false
        var stalls = 0
        val info = MediaCodec.BufferInfo()
        while (!outputDone) {
            var progressed = false
            if (!inputDone) {
                val ix = codec.dequeueInputBuffer(TIMEOUT_US)
                if (ix >= 0) {
                    progressed = true
                    if (pastEnd) {
                        codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val buf = codec.getInputBuffer(ix) ?: error("入力バッファが取れない")
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(ix, 0, size, ptsUs, 0)
                            extractor.advance()
                            if (ptsUs >= endUs) pastEnd = true
                        }
                    }
                }
            }
            val ox = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    progressed = true
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }

                ox >= 0 -> {
                    progressed = true
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(ox) ?: error("出力バッファが取れない")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        appendClipped(
                            chunks, toFloats(buf, encoding), info.presentationTimeUs,
                            startUs, endUs, sampleRate, channels,
                        )
                    }
                    codec.releaseOutputBuffer(ox, false)
                }

                else -> {
                }
            }
            if (progressed) stalls = 0 else if (++stalls > STALL_LIMIT) error("デコードが進まない")
        }
        var total = 0
        for (c in chunks) total += c.size
        if (total == 0) error("指定範囲 (${startUs / 1_000}ms〜) に音声が無い")
        val pcm = FloatArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, pcm, off, c.size)
            off += c.size
        }
        return Loaded(pcm, channels, sampleRate, durationMs)
    }

    private fun appendClipped(
        into: MutableList<FloatArray>,
        floats: FloatArray,
        ptsUs: Long,
        startUs: Long,
        endUs: Long,
        sampleRate: Int,
        channels: Int,
    ) {
        if (sampleRate <= 0 || channels <= 0) return
        val frames = floats.size / channels
        if (frames == 0 || ptsUs >= endUs) return
        val skip =
            if (ptsUs >= startUs) 0
            else (((startUs - ptsUs) * sampleRate + 999_999) / 1_000_000).toInt()
        if (skip >= frames) return
        var take = frames - skip
        val bufEndUs = ptsUs + frames * 1_000_000L / sampleRate
        if (bufEndUs > endUs) {
            val wanted = ((endUs - ptsUs) * sampleRate / 1_000_000).toInt() - skip
            take = min(take, wanted)
        }
        if (take <= 0) return
        into.add(floats.copyOfRange(skip * channels, (skip + take) * channels))
    }

    private fun toFloats(buf: ByteBuffer, encoding: Int): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            FloatArray(shorts.remaining()) { shorts.get(it) / 32_768f }
        }

        AudioFormat.ENCODING_PCM_FLOAT -> {
            val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
            FloatArray(floats.remaining()).also { floats.get(it) }
        }

        else -> error("対応しない PCM 形式: $encoding")
    }
}
