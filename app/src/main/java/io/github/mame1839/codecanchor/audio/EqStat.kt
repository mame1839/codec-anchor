package io.github.mame1839.codecanchor.audio

import io.github.mame1839.codecanchor.core.EqParams
import java.util.concurrent.TimeUnit

/**
 * `caeqstat` の 1 枠分。値の意味は `app/src/main/cpp/caeqstat.cpp` の表と同じ。
 *
 * [inPeakDbfs] の null は "silent" — **無音が来ているだけで、経路に入っていないのとは別**
 * (経路に入っていなければ枠ごと出ないか age が凍る)。
 */
data class EqStatSlot(
    val slot: Int,
    val frames: Long,
    val age: String,
    val inPeakDbfs: Double?,
    val outPeakDbfs: Double?,
    val enabled: Boolean,
    val deviceSession: Boolean,
)

/**
 * `su -c '<nativeLibraryDir>/libcaeqstat.so'` の出力を読む薄いリーダ。
 * 「音がエフェクトを通っていない」検知用 (eq-finder-design.md §3 — 無音のまま答えさせない)。
 *
 * **表の形は `caeqstat.cpp` の printf と対。**列を動かすときは両方を揃える
 * (EqStatTest が session の値の一致を見張る)。人間向けの説明行はここでは読まない。
 */
object EqStat {

    /** APK に載る読み手。`EqParams.EXECUTABLE` と同じ理由で `lib*.so` を名乗る実行ファイル。 */
    const val EXECUTABLE = "libcaeqstat.so"

    /** `ca_eq_shm.h` の `CA_AUDIO_SESSION_DEVICE`。⚠️ 同じ値が 2 箇所 — EqStatTest が一致を見張る。 */
    internal const val DEVICE_SESSION = -2

    // 読むだけの数十 ms の仕事。長引くのは root マネージャに止められたときだけ
    private const val TIMEOUT_MS = 5_000L

    /** 実行して読む。**同期なので呼び出し側がワーカースレッドに置くこと。** */
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

    /**
     * 枠の行 (先頭トークンが枠番号) と、その続きの `session=` 行だけを拾う。
     * 読めない行は黙って飛ばす — 表示用の出力なので、知らない行で全体を失敗にしない。
     */
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
            // 枠の行だけが「整数 0x... 整数」で始まる。説明文の行はここを通らない
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
