package io.github.mame1839.codecanchor.audio

import io.github.mame1839.codecanchor.core.EqParams
import java.util.concurrent.TimeUnit

data class EqStatSlot(
    val slot: Int,
    val frames: Long,
    val age: String,
    val inPeakDbfs: Double?,
    val outPeakDbfs: Double?,
    val enabled: Boolean,
    val deviceSession: Boolean,
)

object EqStat {

    const val EXECUTABLE = "libcaeqstat.so"

    internal const val DEVICE_SESSION = -2

    private const val TIMEOUT_MS = 5_000L

    fun read(nativeLibraryDir: String): Result<List<EqStatSlot>> = runCatching {
        require(EqParams.isSafeDirectory(nativeLibraryDir)) { "nativeLibraryDir が受け付けられない形" }
        val process = ProcessBuilder("su", "-c", "'$nativeLibraryDir/$EXECUTABLE'")
            .redirectErrorStream(true)
            .start()
        val out = StringBuilder()
        val drain = Thread {
            runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
        }.also {
            it.isDaemon = true
            it.start()
        }
        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            drain.join(1_000)
            error("caeqstat がタイムアウト")
        }
        drain.join(1_000)
        parse(out.toString())
    }

    fun parse(text: String): List<EqStatSlot> {
        val slots = mutableListOf<EqStatSlot>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("session=") && slots.isNotEmpty()) {
                val id = line.removePrefix("session=").substringBefore(' ').toIntOrNull()
                if (id == DEVICE_SESSION) slots[slots.size - 1] = slots.last().copy(deviceSession = true)
                continue
            }
            val tokens = line.split(WHITESPACE)
            if (tokens.size < 12) continue
            val slot = tokens[0].toIntOrNull() ?: continue
            if (!tokens[1].startsWith("0x")) continue
            slots += EqStatSlot(
                slot = slot,
                frames = tokens[3].toLongOrNull() ?: 0L,
                age = tokens[7],
                inPeakDbfs = tokens[10].toDoubleOrNull(),
                outPeakDbfs = tokens[11].toDoubleOrNull(),
                enabled = tokens.drop(12).contains("enabled"),
                deviceSession = false,
            )
        }
        return slots
    }

    private val WHITESPACE = Regex("""\s+""")
}
