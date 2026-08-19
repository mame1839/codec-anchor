package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqPresetBook

class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): EqPresetBook = EqPresetBook.decode(prefs.getString(KEY, null))

    fun save(book: EqPresetBook) {
        prefs.edit { putString(KEY, book.encode()) }
    }

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
