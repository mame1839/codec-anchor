package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqDevices

class EqDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): List<String> =
        EqDevices.normalizeAll(prefs.getString(KEY, null).orEmpty().split('\n'))

    fun save(macs: List<String>) {
        prefs.edit { putString(KEY, macs.joinToString("\n")) }
    }

    private companion object {
        const val NAME = "eq_devices"
        const val KEY = "macs"
    }
}
