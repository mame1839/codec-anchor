package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqSettings
import org.json.JSONObject

/**
 * 「好みの EQ を探す」の中断セッションの置き場。設定 (config) ともプリセットとも別のファイルで、
 * フックは読まないので MODE_WORLD_READABLE は不要 (PresetStore と同じ)。
 *
 * 持つのは常に 1 件だけ。同時に走るセッションは無い (書ける機器が 1 台で、画面も 1 枚) ので、
 * 別の機器で新しく始めるときは上書きされる。
 */
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

/**
 * 中断したセッションを次回そのまま続けるのに要るものすべて。
 *
 * [session] はセッションエンジンの `toJson()` をそのまま持つ (中身はここでは解釈しない)。
 * 聴感の重みと土台の聴感レベルは保存しない — 重みは同じ一節 (同じ [uri] + [startMs]) から、
 * レベルはその重みと [base] から、どちらも決定的に計算し直せる。
 * 一節が同じかどうかは [pcmHash] で確かめる (ファイルが差し替わっていたら、それまでの回答は
 * 別の音についてのものなので、黙って続けない)。
 *
 * [base] はセッション開始時点の EQ 設定の写し。候補はこの上に重ねるので、中断中に本体の設定が
 * 変わっても、再開後の候補と確定結果は回答時に鳴っていた音と同じものを指し続ける。
 *
 * ### 題材の区別は optional なキーで持つ (版は上げない)
 *
 * [live] = true (題材が「いま流れている音楽」) の記録は一節を持たないので、
 * [uri] は null・[startMs]/[lengthMs] は 0・[pcmHash] は 0 (= 照合しない、既存の意味)。
 * encode はキー自体を書かない。**キーの無い v1 の記録はループとして読める** (後方互換)、
 * そして**ライブの記録をこのキーを知らない旧ビルドが読むと uri 欠けで「保存なし」に劣化する**
 * (壊れた再開を出すのではなく、安全側に倒れる)。
 */
data class EqFinderSaved(
    val mac: String,
    /** 題材: false = 曲の一節のループ再生、true = いま流れている音楽。 */
    val live: Boolean = false,
    /** 一節の URI。ループでは必須、ライブでは null。 */
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
        // ループの記録は v1 と同じキー並びのまま (旧ビルドでもそのまま読める)。
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
        // 読めない版・欠けた記録は「保存なし」に倒す。中断データは作り直せる (もう一度探せばよい)
        // ので、壊れたセッションを無理に再開するより安全側。
        private const val VERSION = 1

        fun decode(text: String?): EqFinderSaved? = runCatching {
            val o = JSONObject(text ?: return null)
            if (o.optInt("v", 0) != VERSION) return null
            val live = o.optBoolean("live", false)
            EqFinderSaved(
                mac = o.optString("mac").takeIf { it.isNotEmpty() } ?: return null,
                live = live,
                // ライブに一節は無い。紛れ込んだ uri は捨てて、動きを材料キーだけで決める。
                uri = if (live) null else (o.optString("uri").takeIf { it.isNotEmpty() } ?: return null),
                startMs = if (live) 0 else o.optInt("start", 0).coerceAtLeast(0),
                lengthMs = if (live) 0 else (o.optInt("len", 0).takeIf { it > 0 } ?: return null),
                includeMid = o.optBoolean("mid", false),
                fineTune = o.optBoolean("fine", false),
                done = o.optInt("done", 0).coerceAtLeast(0),
                pcmHash = o.optLong("pcm", 0L),
                // fromJson(null) は既定値を返すので、欠けをここで弾かないと base が
                // 空の EqSettings() に化ける — 「設定ずれ」の照合が偶然すり抜けて、
                // 空の土台の上で再開してしまう。
                base = EqSettings.fromJson(o.optJSONObject("base") ?: return null),
                session = o.optJSONObject("session") ?: return null,
                savedAt = o.optLong("at", 0L),
            )
        }.getOrNull()
    }
}
