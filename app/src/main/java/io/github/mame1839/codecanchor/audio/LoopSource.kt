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

/**
 * デコード済みの題材。[pcm] はチャネルインターリーブの float (フルスケール ±1.0)。
 *
 * data class にしない — 配列の equals/hashCode は参照比較で、lint (ArrayInDataClass) にも落ちる。
 */
class Loaded(
    val pcm: FloatArray,
    val channels: Int,
    val sampleRate: Int,
    val trackDurationMs: Long,
) {
    /** スペクトル計測用のモノラル。再生には使わない。 */
    fun monoMix(): FloatArray = Spectrum.monoMix(pcm, channels)
}

/**
 * SAF の URI から曲の一節をデコードする (MediaExtractor + MediaCodec、同期)。
 *
 * **同期で走るので、呼び出し側がワーカースレッドに置くこと。**数十秒の AAC で数百 ms 〜数秒
 * かかる。失敗は Result で返し、例外を漏らさない。
 */
object LoopSource {

    // dequeue 1 回の待ち。長くしてもデコードは速くならず、停止 (stall 判定) が鈍るだけ。
    private const val TIMEOUT_US = 10_000L

    // 進捗の無い dequeue の連続がこれを超えたら諦める (10 ms × 1000 = 約 10 秒)。
    // 壊れたファイルで無限に回らないための安全弁で、正常なファイルでは届かない。
    private const val STALL_LIMIT = 1_000

    // 題材はループする一節なので長くて数十秒。上限はメモリ保護
    // (120 s ステレオ 48 kHz float ≈ 46 MB。これ以上は端末によって OOM が現実になる)。
    const val MAX_CLIP_MS = 120_000L

    /**
     * [uri] の音声トラックから [startMs] 以降 [lengthMs] 分を float PCM へ。
     *
     * 切り出しはフレーム単位 (1/fs 秒)。デコーダが返す各バッファの presentationTime を
     * 信頼して端だけを刻む — 内側のバッファは丸めずに全量を継ぐ (µs への往復で毎バッファ
     * 1 フレーム落とすと、その分だけ周期的なクリックが入る)。
     */
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
        // 実際の PCM の形は INFO_OUTPUT_FORMAT_CHANGED が上書きする。ここは初期値
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
                            // 終端を過ぎた入力まで送ってから EOS。デコーダ内部の遅延分を
                            // 流しきらないと、最後のバッファが欠ける
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
                    // INFO_TRY_AGAIN_LATER。進んでいない
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

    /**
     * デコード済みバッファのうち [startUs, endUs) に掛かる部分だけを継ぐ。
     *
     * **内側のバッファは丸めに触らせず全量を取る** (先頭・終端に掛かるバッファだけ刻む)。
     * take を µs 経由で計算すると整数除算で毎バッファ 1 フレーム欠け、周期的なクリックになる。
     */
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
            else (((startUs - ptsUs) * sampleRate + 999_999) / 1_000_000).toInt() // ceil
        if (skip >= frames) return
        var take = frames - skip
        val bufEndUs = ptsUs + frames * 1_000_000L / sampleRate
        if (bufEndUs > endUs) {
            val wanted = ((endUs - ptsUs) * sampleRate / 1_000_000).toInt() - skip // floor
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
