package io.github.mame1839.codecanchor.bridge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.xposed.XLog

class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    fun load(): AppConfig = AppConfig.decode(prefs.getString(Bridge.PREFS_KEY, null))

    fun save(config: AppConfig) {
        prefs.edit().putString(Bridge.PREFS_KEY, config.encode()).apply()
    }

    companion object {
        // MODE_WORLD_READABLE は Xposed 環境でのみ通る。ブロードキャストを取り逃したときの保険。
        private fun openPrefs(context: Context): SharedPreferences =
            try {
                @Suppress("DEPRECATION")
                context.getSharedPreferences(Bridge.PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (t: Throwable) {
                XLog.i("設定ファイルを共有できないのでフックからは読めない: ${t.message}")
                context.getSharedPreferences(Bridge.PREFS_NAME, Context.MODE_PRIVATE)
            }
    }
}

object BridgeClient {

    fun pushConfig(context: Context, config: AppConfig) {
        send(context, Intent(Bridge.ACTION_PUSH_CONFIG).putExtra(Bridge.EXTRA_JSON, config.encode()))
    }

    fun requestStatus(context: Context) {
        send(context, Intent(Bridge.ACTION_REQUEST_STATUS))
    }

    fun applyNow(context: Context, mac: String?) {
        send(context, Intent(Bridge.ACTION_APPLY_NOW).apply { mac?.let { putExtra(Bridge.EXTRA_MAC, it) } })
    }

    // 受信側 (Bluetooth プロセス) は BRIDGE 権限の保持を送信元に要求する。ここで receiverPermission を
    // 付けると Bluetooth プロセスが権限を持たないため配送されない。
    private fun send(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        runCatching { context.sendBroadcast(intent) }
    }
}
