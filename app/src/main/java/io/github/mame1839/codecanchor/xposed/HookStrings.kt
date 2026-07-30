package io.github.mame1839.codecanchor.xposed

import android.content.Context
import android.content.res.Resources
import io.github.mame1839.codecanchor.core.Bridge

// フックは Bluetooth プロセスで動くので、自分の APK のリソースを名前で引いて訳文を得る。
// 引けなかったときは英語のフォールバックを使う。
internal object HookStrings {

    @Volatile
    private var cached: Resources? = null

    fun format(context: Context, name: String, fallback: String, vararg args: Any): String {
        resources(context)?.let { res ->
            val id = runCatching { res.getIdentifier(name, "string", Bridge.PKG) }.getOrDefault(0)
            if (id != 0) {
                runCatching { return res.getString(id, *args) }
                    .onFailure { XLog.d("$name の書式化に失敗: ${it.message}") }
            }
        }
        return runCatching { String.format(fallback, *args) }.getOrDefault(fallback)
    }

    private fun resources(context: Context): Resources? {
        cached?.let { return it }
        return runCatching { context.packageManager.getResourcesForApplication(Bridge.PKG) }
            .onFailure { XLog.d("アプリのリソースを読めない: ${it.message}") }
            .getOrNull()
            ?.also { cached = it }
    }
}
