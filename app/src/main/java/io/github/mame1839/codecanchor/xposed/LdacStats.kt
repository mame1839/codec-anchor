package io.github.mame1839.codecanchor.xposed

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import java.io.FileOutputStream
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// LDAC の実効ビットレートはネイティブスタックが dumpsys に書く 2 行しか出どころが無い。パイプへ向けて
// ダンプを生成させ、その 2 行だけ取って残りは読み捨てる。
// ダンプ本文には他のペアリング機器のアドレスも載るので、値以外は保持もログ出力もしない。
internal object LdacStats {

    class Reading(val mode: String, val kbps: Int)

    private const val CACHE_MS = 2_000L

    // 読み取りは状態要求のブロードキャストの中で行うので、配送の制限時間より十分手前で諦める。
    private const val READ_TIMEOUT_MS = 3_000L

    private const val KEY_MODE = "LDAC quality mode"
    private const val KEY_BITRATE = "LDAC transmission bitrate"

    // AdapterService は引数が空だと本体を書かずに戻る。ネイティブ層は引数を見ない。
    private val PRINT_ARGS = arrayOf("--print")
    private val NO_ARGS = emptyArray<String>()

    private class Entry(val reading: Reading, val readAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun cached(mac: String): Reading? = cache[mac]?.reading

    fun forget(mac: String) {
        cache.remove(mac)
    }

    fun clear() {
        cache.clear()
    }

    // ダンプ全体の生成は重いので、直近に読んだ値はそのまま使う。
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
        // 欲しい 2 行はネイティブ層しか書かないので、そこだけ吐かせる経路を優先する。出力が短く、
        // Java 側のダンプに付くメーカー独自の副作用 (スヌープログのコピー等) も通らない。
        val native = runCatching { XposedHelpers.getObjectField(adapter, "mNativeInterface") }.getOrNull()
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

    private fun valueOf(line: String): String? =
        line.substringAfterLast(':', "").trim().takeIf { it.isNotEmpty() }

    // 書き手と読み手を分けないとパイプのバッファ (ダンプより小さい) で詰まる。読み手は途中で
    // やめずに EOF まで読み切り、待ちが返らないときだけ両端を閉じて打ち切る。
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
        // ネイティブ層は fd へ直接書くので、Java 側の書き込みを流し切ってからでないと順序が崩れる。
        val writer = PrintWriter(FileOutputStream(fd))
        XposedHelpers.callMethod(adapter, "dump", fd, writer, PRINT_ARGS)
        writer.flush()
    }
}
