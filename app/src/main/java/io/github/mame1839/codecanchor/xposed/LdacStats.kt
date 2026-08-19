package io.github.mame1839.codecanchor.xposed

import android.bluetooth.BluetoothAdapter
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object LdacStats {

    class Reading(val mode: String, val kbps: Int)

    private const val CACHE_MS = 2_000L

    private const val READ_TIMEOUT_MS = 3_000L

    private const val KEY_MODE = "LDAC quality mode"
    private const val KEY_BITRATE = "LDAC transmission bitrate"

    private val PRINT_ARGS = arrayOf("--print")
    private val NO_ARGS = emptyArray<String>()

    private val NO_NATIVE_DUMP = setOf(
        BluetoothAdapter.STATE_OFF,
        BluetoothAdapter.STATE_TURNING_OFF,
        14,
        16,
    )

    private class Entry(val reading: Reading, val readAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun cached(mac: String): Reading? = cache[mac]?.reading

    fun forget(mac: String) {
        cache.remove(mac)
    }

    fun clear() {
        cache.clear()
    }

    fun refresh(a2dpService: Any, mac: String): Reading? {
        val now = SystemClock.uptimeMillis()
        cache[mac]?.let { if (now - it.readAt < CACHE_MS) return it.reading }
        val reading = read(a2dpService)
        if (reading == null) cache.remove(mac) else cache[mac] = Entry(reading, now)
        return reading
    }

    private fun read(a2dpService: Any): Reading? {
        val adapter = runCatching { XposedHelpers.getObjectField(a2dpService, "mAdapterService") }
            .onFailure { XLog.d("AdapterService が取れない: ${it.message}") }
            .getOrNull() ?: return null
        val native = if (nativeDumpSafe(adapter)) {
            runCatching { XposedHelpers.getObjectField(adapter, "mNativeInterface") }.getOrNull()
        } else {
            null
        }
        var mode = ""
        var kbps = 0
        val complete = scan(adapter, native) { line ->
            when {
                line.contains(KEY_BITRATE) -> valueOf(line)?.toIntOrNull()?.let { kbps = it }
                line.contains(KEY_MODE) -> valueOf(line)?.let { mode = it }
            }
        }
        if (!complete) return null
        return if (kbps > 0 || mode.isNotEmpty()) Reading(mode, kbps) else null
    }

    private fun nativeDumpSafe(adapter: Any): Boolean {
        val state = runCatching { XposedHelpers.callMethod(adapter, "getState") as? Int }.getOrNull()
        return state != null && state !in NO_NATIVE_DUMP
    }

    private fun valueOf(line: String): String? =
        line.substringAfterLast(':', "").trim().takeIf { it.isNotEmpty() }

    private fun scan(adapter: Any, native: Any?, onLine: (String) -> Unit): Boolean {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }
            .onFailure { XLog.d("パイプを作れない: ${it.message}") }
            .getOrNull() ?: return false
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "CodecAnchorDump") {
            runCatching { write(adapter, native, writeEnd) }
                .onFailure { XLog.d("ダンプを生成できない: ${it.message}") }
            runCatching { writeEnd.close() }
        }
        thread(isDaemon = true, name = "CodecAnchorScan") {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().forEachLine(onLine)
            }
            done.countDown()
        }
        val complete = runCatching { done.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!complete) {
            XLog.d("ダンプを読み切れなかったので打ち切る")
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
        }
        return complete
    }

    private fun write(adapter: Any, native: Any?, writeEnd: ParcelFileDescriptor) {
        val fd = writeEnd.fileDescriptor
        if (native != null) {
            XposedHelpers.callMethod(native, "dump", fd, NO_ARGS)
            return
        }
        val writer = PrintWriter(FileOutputStream(fd))
        XposedHelpers.callMethod(adapter, "dump", fd, writer, PRINT_ARGS)
        writer.flush()
    }
}
