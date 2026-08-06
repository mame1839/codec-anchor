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

    private companion object {
        const val NAME = "presets"
        const val KEY = "json"
    }
}
