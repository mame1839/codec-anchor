package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqDevices

// ⚠️ DeviceProfile には入れない (フックとの JSON 契約 hash() の対象を広げない。フックはこの
// 情報を要らない)。バックアップにも載せない (この端末の XML の記録であり、他端末へ持ち出すと嘘になる)。
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
