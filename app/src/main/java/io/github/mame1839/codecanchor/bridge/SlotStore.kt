package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqSlotBook

// フックはスロットを読まない (要るのは「いま鳴っている EQ」= profile.eq だけ) ので
// MODE_WORLD_READABLE は不要。PresetStore と同じ流儀。
class SlotStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): EqSlotBook = EqSlotBook.decode(prefs.getString(KEY, null))

    fun save(book: EqSlotBook) {
        prefs.edit { putString(KEY, book.encode()) }
    }

    private companion object {
        const val NAME = "eq_slots"
        const val KEY = "json"
    }
}
