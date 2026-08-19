package io.github.mame1839.codecanchor.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object EqParamsExit {
    const val OK = 0
    const val BAD_INPUT = 10
    const val NO_SHM = 11
    const val SHM_MISMATCH = 12
    const val NO_LIVE_SLOT = 13
    const val AMBIGUOUS_SLOT = 14
    const val REJECTED = 15
    const val SLOTS_FULL = 16
}

enum class EqParamsOutcome {
    APPLIED,

    NO_LIVE_SLOT,

    AMBIGUOUS_SLOT,

    SLOTS_FULL,

    REJECTED,

    NO_SHM,

    VERSION_MISMATCH,

    BAD_INPUT,

    ROOT_DENIED,

    NO_SU,

    TIMEOUT,

    UNKNOWN,
}

data class EqParamsResult(
    val outcome: EqParamsOutcome,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun diagnostics(limit: Int = 3): String =
        (stderr.lineSequence() + stdout.lineSequence())
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != EqParams.BEGIN_MARKER }
            .take(limit)
            .joinToString("\n")
}

object EqParams {

    const val EXECUTABLE = "libcaeqset.so"

    const val BEGIN_MARKER = "CA_EQ_SET_BEGIN"

    const val NO_EXIT_CODE = -1

    private const val TIMEOUT_MS = 5_000L

    private const val DRAIN_JOIN_MS = 1_000L

    private val SAFE_DIR = Regex("""^/[A-Za-z0-9._~=+/-]+$""")

    internal val SAFE_ARGUMENT = Regex("""^[A-Za-z0-9.:_+-]+$""")

    fun isSafeDirectory(dir: String): Boolean = SAFE_DIR.matches(dir) && !dir.contains("..")

    fun isSafePath(path: String): Boolean = isSafeDirectory(path)

    fun arguments(eq: EqSettings): List<String> {
        if (!eq.enabled) return listOf("--auto-slot", "--off")
        val args =
            mutableListOf("--auto-slot", "--on", "--preamp", decimal(eq.preampDb10, EqUnits.GAIN_SCALE))
        args += if (eq.firRequested) "--hp" else "--std"
        eq.bands.take(EqSettings.MAX_BANDS).forEach { band ->
            args += "--band"
            args += listOf(
                band.freqHz.toString(),
                decimal(band.q100, EqUnits.Q_SCALE),
                decimal(band.gainDb10, EqUnits.GAIN_SCALE),
                typeToken(band.type),
            ).joinToString(":")
        }
        return args
    }

    fun command(nativeLibraryDir: String, eq: EqSettings, curvePath: String? = null): String {
        val head = listOf("'$nativeLibraryDir/$EXECUTABLE'")
        val curve =
            if (curvePath != null && eq.firRequested) listOf("--curve", "'$curvePath'") else emptyList()
        return (head + arguments(eq) + curve).joinToString(" ")
    }

    fun apply(
        nativeLibraryDir: String,
        eq: EqSettings,
        curveFile: File? = null,
    ): EqParamsResult {
        if (!isSafeDirectory(nativeLibraryDir)) {
            return EqParamsResult(
                outcome = EqParamsOutcome.BAD_INPUT,
                exitCode = NO_EXIT_CODE,
                stdout = "",
                stderr = "nativeLibraryDir が受け付けられない形: $nativeLibraryDir",
            )
        }
        val curvePath = curveFile?.absolutePath?.takeIf { isSafePath(it) }
        return execute(command(nativeLibraryDir, eq, curvePath))
    }

    fun outcomeOf(exitCode: Int): EqParamsOutcome = when (exitCode) {
        EqParamsExit.OK -> EqParamsOutcome.APPLIED
        EqParamsExit.BAD_INPUT -> EqParamsOutcome.BAD_INPUT
        EqParamsExit.NO_SHM -> EqParamsOutcome.NO_SHM
        EqParamsExit.SHM_MISMATCH -> EqParamsOutcome.VERSION_MISMATCH
        EqParamsExit.NO_LIVE_SLOT -> EqParamsOutcome.NO_LIVE_SLOT
        EqParamsExit.AMBIGUOUS_SLOT -> EqParamsOutcome.AMBIGUOUS_SLOT
        EqParamsExit.SLOTS_FULL -> EqParamsOutcome.SLOTS_FULL
        EqParamsExit.REJECTED -> EqParamsOutcome.REJECTED
        else -> EqParamsOutcome.UNKNOWN
    }

    internal fun decimal(value: Int, scale: Int): String {
        val digits = scale.toString().length - 1
        val magnitude = abs(value.toLong())
        val sign = if (value < 0) "-" else ""
        return "$sign${magnitude / scale}.${(magnitude % scale).toString().padStart(digits, '0')}"
    }

    internal fun typeToken(type: Int): String = when (type) {
        EqBandType.LOW_SHELF -> "ls"
        EqBandType.HIGH_SHELF -> "hs"
        else -> "pk"
    }

    private fun execute(command: String): EqParamsResult {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return EqParamsResult(
                outcome = EqParamsOutcome.NO_SU,
                exitCode = NO_EXIT_CODE,
                stdout = "",
                stderr = e.message.orEmpty(),
            )
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val outDrain = drain(process.inputStream, out)
        val errDrain = drain(process.errorStream, err)
        val finished = try {
            process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroy()
            outDrain.join(DRAIN_JOIN_MS)
            errDrain.join(DRAIN_JOIN_MS)
            return EqParamsResult(
                outcome = EqParamsOutcome.TIMEOUT,
                exitCode = NO_EXIT_CODE,
                stdout = out.toString(),
                stderr = err.toString(),
            )
        }
        outDrain.join(DRAIN_JOIN_MS)
        errDrain.join(DRAIN_JOIN_MS)
        val code = process.exitValue()
        val stdout = out.toString()
        val outcome = if (stdout.contains(BEGIN_MARKER)) outcomeOf(code) else EqParamsOutcome.ROOT_DENIED
        return EqParamsResult(outcome, code, stdout, err.toString())
    }

    private fun drain(stream: InputStream, into: StringBuilder): Thread =
        Thread {
            runCatching { stream.bufferedReader().use { into.append(it.readText()) } }
        }.also { it.isDaemon = true; it.start() }
}
