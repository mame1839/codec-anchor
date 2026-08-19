package io.github.mame1839.codecanchor.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object Spectrum {

    fun crossfadeLoop(pcm: FloatArray, channels: Int, fadeFrames: Int): FloatArray {
        require(channels >= 1) { "channels は 1 以上" }
        val frames = pcm.size / channels
        val fade = min(fadeFrames, frames / 2)
        if (fade < 2) return pcm.copyOf(frames * channels)
        val outFrames = frames - fade
        val out = FloatArray(outFrames * channels)
        for (i in 0 until outFrames) {
            if (i < fade) {
                val theta = (PI / 2) * i / (fade - 1)
                val head = sin(theta)
                val tail = cos(theta)
                for (c in 0 until channels) {
                    val a = pcm[i * channels + c] * head
                    val b = pcm[(outFrames + i) * channels + c] * tail
                    out[i * channels + c] = (a + b).toFloat()
                }
            } else {
                for (c in 0 until channels) out[i * channels + c] = pcm[i * channels + c]
            }
        }
        return out
    }

    fun pinkNoise(samples: Int, random: Random): FloatArray {
        val rows = 16
        val values = DoubleArray(rows) { random.nextDouble(-1.0, 1.0) }
        var sum = values.sum()
        val out = FloatArray(samples)
        for (n in 0 until samples) {
            if (n > 0) {
                val r = n.countTrailingZeroBits()
                if (r < rows) {
                    sum -= values[r]
                    values[r] = random.nextDouble(-1.0, 1.0)
                    sum += values[r]
                }
            }
            out[n] = ((sum + random.nextDouble(-1.0, 1.0)) / (rows + 1)).toFloat()
        }
        return out
    }
}
