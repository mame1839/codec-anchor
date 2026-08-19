package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqSettings
import org.json.JSONObject

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
