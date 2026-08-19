package io.github.mame1839.codecanchor.bridge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.mame1839.codecanchor.core.AppConfig
import io.github.mame1839.codecanchor.core.Bridge
import io.github.mame1839.codecanchor.xposed.XLog

class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    fun load(): AppConfig? = AppConfig.decode(prefs.getString(Bridge.PREFS_KEY, null))

    fun save(config: AppConfig) {
        prefs.edit().putString(Bridge.PREFS_KEY, config.encode()).apply()
    }

    fun settingsHooked(): Boolean = prefs.getLong(Bridge.PREFS_KEY_SETTINGS_HOOKED, 0L) > 0L

    fun markSettingsHooked() {
        prefs.edit { putLong(Bridge.PREFS_KEY_SETTINGS_HOOKED, System.currentTimeMillis()) }
    }

    fun freeOffloadSwitch(): Boolean =
        prefs.getBoolean(Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH, Bridge.FREE_OFFLOAD_SWITCH_DEFAULT)

    fun setFreeOffloadSwitch(value: Boolean) {
        prefs.edit(commit = true) { putBoolean(Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH, value) }
    }

    companion object {
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
        send(context) { Intent(Bridge.ACTION_PUSH_CONFIG).putExtra(Bridge.EXTRA_JSON, config.encode()) }
    }

    fun requestStatus(context: Context) {
        send(context) { Intent(Bridge.ACTION_REQUEST_STATUS) }
    }

    fun applyNow(context: Context, mac: String?) {
        send(context) { Intent(Bridge.ACTION_APPLY_NOW).apply { mac?.let { putExtra(Bridge.EXTRA_MAC, it) } } }
    }

    fun pushSettingsHook(context: Context, freeOffloadSwitch: Boolean) {
        runCatching {
            val intent = Intent(Bridge.ACTION_PUSH_SETTINGS_HOOK)
                .setPackage(Bridge.SETTINGS_PACKAGE)
                .putExtra(Bridge.EXTRA_FREE_OFFLOAD_SWITCH, freeOffloadSwitch)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        }
    }

    private fun send(context: Context, build: () -> Intent) {
        runCatching {
            val intent = build().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            Bridge.BLUETOOTH_PACKAGES.forEach { pkg ->
                context.sendBroadcast(Intent(intent).setPackage(pkg))
            }
        }
    }
}
