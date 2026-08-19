package io.github.mame1839.codecanchor.core

import java.util.concurrent.TimeUnit

object EqDevices {

    const val PROPERTY_DIR = "ro.codecanchor.module_dir"
    const val SCRIPT_NAME = "eq_devices.sh"
    const val ARG_APPLY = "apply"
    const val SU_MARKER = "CA_SU_OK"
    const val BEGIN_MARKER = "CA_EQ_DEVICES_BEGIN"

    const val EXIT_OK = 0
    const val EXIT_BAD_INPUT = 10
    const val EXIT_NO_STATE = 11
    const val EXIT_XML_FAILED = 12
    const val EXIT_APPLY_FAILED = 13
    const val EXIT_AUDIOSERVER_TIMEOUT = 14

    const val TIMEOUT_MS = 30_000L

    private const val SU = "su"
    private const val READER_JOIN_MS = 1_000L
    private const val OUTPUT_LIMIT = 8_192

    private val MAC_PATTERN = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val DIR_PATTERN = Regex("^/[0-9A-Za-z._/-]+$")

    fun normalizeMac(raw: String): String? {
        val text = raw.trim().uppercase()
        return if (MAC_PATTERN.matches(text)) text else null
    }

    fun normalizeAll(macs: Collection<String>): List<String> =
        macs.mapNotNull(::normalizeMac).distinct().sorted()

    fun withDevice(current: Collection<String>, mac: String, registered: Boolean): List<String> {
        val key = normalizeMac(mac) ?: return normalizeAll(current)
        return normalizeAll(if (registered) current + key else current - key)
    }

    fun recordAfter(
        current: List<String>,
        sent: List<String>,
        outcome: EqDevicesOutcome,
    ): List<String> = if (outcome == EqDevicesOutcome.OK) sent else current

    fun ranSu(output: String): Boolean = hasMarker(output, SU_MARKER)

    fun ranScript(output: String): Boolean = hasMarker(output, BEGIN_MARKER)

    private fun hasMarker(output: String, marker: String): Boolean =
        output.lineSequence().any { it.trim() == marker }

    fun outcomeOf(output: String, exitCode: Int): EqDevicesOutcome = when {
        !ranSu(output) -> EqDevicesOutcome.ROOT_DENIED
        !ranScript(output) -> EqDevicesOutcome.SCRIPT_MISSING
        exitCode == EXIT_OK -> EqDevicesOutcome.OK
        else -> EqDevicesOutcome.SCRIPT_FAILED
    }

    fun apply(macs: List<String>): EqDevicesResult {
        val dir = ModuleVersion.read(PROPERTY_DIR).trim()
        if (!DIR_PATTERN.matches(dir)) return EqDevicesResult(EqDevicesOutcome.NO_MODULE)
        return runCatching { run(dir, macs) }
            .getOrElse { EqDevicesResult(EqDevicesOutcome.ROOT_DENIED, output = it.message.orEmpty()) }
    }

    private fun run(dir: String, macs: List<String>): EqDevicesResult {
        val process = ProcessBuilder(SU, "-c", "echo $SU_MARKER; sh '$dir/$SCRIPT_NAME' $ARG_APPLY")
            .redirectErrorStream(true)
            .start()
        return try {
            drive(process, macs)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun drive(process: Process, macs: List<String>): EqDevicesResult {
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        if (output.length < OUTPUT_LIMIT) output.append(line).append('\n')
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        runCatching {
            process.outputStream.bufferedWriter().use { writer ->
                macs.forEach { writer.append(it).append('\n') }
            }
        }

        val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val text = synchronized(output) { output.toString() }

        if (!finished) return EqDevicesResult(EqDevicesOutcome.TIMEOUT, output = text)

        val code = process.exitValue()
        return EqDevicesResult(outcomeOf(text, code), exitCode = code, output = text)
    }
}

enum class EqDevicesOutcome {
    OK,
    NO_MODULE,
    ROOT_DENIED,
    SCRIPT_MISSING,
    TIMEOUT,
    SCRIPT_FAILED,
}

data class EqDevicesResult(
    val outcome: EqDevicesOutcome,
    val exitCode: Int = -1,
    val output: String = "",
) {
    fun diagnostics(limit: Int = 3): String =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != EqDevices.BEGIN_MARKER && it != EqDevices.SU_MARKER }
            .toList()
            .takeLast(limit)
            .joinToString("\n")
}
