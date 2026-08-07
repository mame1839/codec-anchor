package io.github.mame1839.codecanchor.bridge

import android.content.Context
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.EqDevices

/**
 * `audio_effects.xml` に登録済みのイヤホンの MAC。
 *
 * **`DeviceProfile` には入れない。**あちらはフックとの JSON の契約 (`hash()` が文字列の完全一致) で、
 * **フックはこの情報を要らない。**プリセットと同じく、アプリだけが持つ場所に置く。
 *
 * **バックアップにも載せない。**これはユーザの設定ではなく、この端末の XML に何が書かれているかの
 * 記録なので、別の端末へ持っていくと嘘になる。
 *
 * 記録を書くのは `EqDevices.apply()` が成功したときだけ。失敗しても書き換えないので、記録と XML が
 * ずれるのは「成功したのにアプリが落ちた」ときに限られ、次に登録を触った時点で**一覧を丸ごと
 * 送り直す**ので直る。
 */
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
