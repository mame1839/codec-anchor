package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqSettings
import org.json.JSONObject

// フックは読まないので MODE_WORLD_READABLE は不要 (PresetStore と同じ)。持つのは常に 1 件だけ
// (書ける機器が 1 台・画面も 1 枚なので、別の機器で新しく始めると上書きされる)。
class EqFinderStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): EqFinderSaved? = EqFinderSaved.decode(prefs.getString(KEY, null))

    fun save(record: EqFinderSaved) {
        prefs.edit { putString(KEY, record.encode()) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    private companion object {
        const val NAME = "eq_finder"
        const val KEY = "session"
    }
}

// 中断したセッションを次回そのまま続けるのに要るものすべて。聴感の重みとトリムは保存しない —
// 同じ一節 ([uri] + [startMs]) から決定的に計算し直せる。[pcmHash] でファイル差し替えを検知する。
//
// ⚠️ 題材の区別は optional なキーで持ち、版は上げない。[live] の記録は uri なしで書くので、
// キーの無い v1 の記録はループとして読める (後方互換)。ライブの記録をこのキーを知らない旧ビルドが
// 読むと uri 欠けで「保存なし」に倒れる (壊れた再開より安全側)。
data class EqFinderSaved(
    val mac: String,
    val live: Boolean = false,
    val uri: String?,
    val startMs: Int,
    val lengthMs: Int,
    val includeMid: Boolean,
    val fineTune: Boolean,
    val done: Int,
    val pcmHash: Long,
    val base: EqSettings,
    val session: JSONObject,
    val savedAt: Long,
) {
    fun encode(): String = JSONObject().apply {
        put("v", VERSION)
        put("mac", mac)
        if (live) put("live", true)
        uri?.let { put("uri", it) }
        if (!live) {
            put("start", startMs)
            put("len", lengthMs)
        }
        put("mid", includeMid)
        put("fine", fineTune)
        put("done", done)
        put("pcm", pcmHash)
        put("base", base.toJson())
        put("session", session)
        put("at", savedAt)
    }.toString()

    companion object {
        private const val VERSION = 1

        fun decode(text: String?): EqFinderSaved? = runCatching {
            val o = JSONObject(text ?: return null)
            if (o.optInt("v", 0) != VERSION) return null
            val live = o.optBoolean("live", false)
            EqFinderSaved(
                mac = o.optString("mac").takeIf { it.isNotEmpty() } ?: return null,
                live = live,
                uri = if (live) null else (o.optString("uri").takeIf { it.isNotEmpty() } ?: return null),
                startMs = if (live) 0 else o.optInt("start", 0).coerceAtLeast(0),
                lengthMs = if (live) 0 else (o.optInt("len", 0).takeIf { it > 0 } ?: return null),
                includeMid = o.optBoolean("mid", false),
                fineTune = o.optBoolean("fine", false),
                done = o.optInt("done", 0).coerceAtLeast(0),
                pcmHash = o.optLong("pcm", 0L),
                base = EqSettings.fromJson(o.optJSONObject("base") ?: return null),
                session = o.optJSONObject("session") ?: return null,
                savedAt = o.optLong("at", 0L),
            )
        }.getOrNull()
    }
}
