package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqPresetBook

// 設定 (config) とは別のファイル。フックはプリセットを読まないので MODE_WORLD_READABLE は不要。
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): EqPresetBook = EqPresetBook.decode(prefs.getString(KEY, null))

    fun save(book: EqPresetBook) {
        prefs.edit { putString(KEY, book.encode()) }
    }

    /**
     * パラメトリックからグラフィックへ移るときの丸めの確認を、もう出さないか。
     *
     * 設定 (config) には載せない。フックが使わない値なので、載せると hash の契約が広がる。
     */
    fun roundingConfirmed(): Boolean = prefs.getBoolean(KEY_ROUNDING, false)

    fun confirmRounding() {
        prefs.edit { putBoolean(KEY_ROUNDING, true) }
    }

    private companion object {
        const val NAME = "presets"
        const val KEY = "json"
        const val KEY_ROUNDING = "rounding_confirmed"
    }
}
