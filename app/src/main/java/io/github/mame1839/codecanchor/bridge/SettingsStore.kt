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

    // 保存された JSON が壊れているときは null。既定値で代用すると、次の保存で設定を消してしまう。
    fun load(): AppConfig? = AppConfig.decode(prefs.getString(Bridge.PREFS_KEY, null))

    fun save(config: AppConfig) {
        prefs.edit().putString(Bridge.PREFS_KEY, config.encode()).apply()
    }

    // 設定アプリのフックが一度でも判定を差し替えたか。差し替えられていれば、開発者向けオプションの
    // トグルは解放されている。
    fun settingsHooked(): Boolean = prefs.getLong(Bridge.PREFS_KEY_SETTINGS_HOOKED, 0L) > 0L

    fun markSettingsHooked() {
        prefs.edit { putLong(Bridge.PREFS_KEY_SETTINGS_HOOKED, System.currentTimeMillis()) }
    }

    // 設定アプリのフックが XSharedPreferences で同じ鍵を読む (ブロードキャストを取り逃したとき、
    // および設定アプリのプロセスが立った直後の 1 回目の描画のため)。
    fun freeOffloadSwitch(): Boolean =
        prefs.getBoolean(Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH, Bridge.FREE_OFFLOAD_SWITCH_DEFAULT)

    // commit で書く (apply は write-behind)。読むのが別プロセスの XSharedPreferences なので、
    // 切った直後に開発者向けオプションを開くと、ファイルが書かれる前に読まれうる。
    fun setFreeOffloadSwitch(value: Boolean) {
        prefs.edit(commit = true) { putBoolean(Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH, value) }
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
        send(context) { Intent(Bridge.ACTION_PUSH_CONFIG).putExtra(Bridge.EXTRA_JSON, config.encode()) }
    }

    fun requestStatus(context: Context) {
        send(context) { Intent(Bridge.ACTION_REQUEST_STATUS) }
    }

    fun applyNow(context: Context, mac: String?) {
        send(context) { Intent(Bridge.ACTION_APPLY_NOW).apply { mac?.let { putExtra(Bridge.EXTRA_MAC, it) } } }
    }

    // 設定アプリのフックだけが読む値。宛先も中身も上の 3 つとは別なので経路を分ける —
    // AppConfig を com.android.settings へ流すと全機器の MAC と名前が渡ることになる。
    fun pushSettingsHook(context: Context, freeOffloadSwitch: Boolean) {
        runCatching {
            val intent = Intent(Bridge.ACTION_PUSH_SETTINGS_HOOK)
                .setPackage(Bridge.SETTINGS_PACKAGE)
                .putExtra(Bridge.EXTRA_FREE_OFFLOAD_SWITCH, freeOffloadSwitch)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        }
    }

    // 受信側 (Bluetooth プロセス) は BRIDGE 権限の保持を送信元に要求する。ここで receiverPermission を
    // 付けると Bluetooth プロセスが権限を持たないため配送されない。宛先の限定は setPackage で行う
    // (実行時登録のレシーバにも効く) — 設定には全機器の MAC と名前が載るので、宛先無制限で流さない。
    private fun send(context: Context, build: () -> Intent) {
        runCatching {
            val intent = build().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            Bridge.BLUETOOTH_PACKAGES.forEach { pkg ->
                context.sendBroadcast(Intent(intent).setPackage(pkg))
            }
        }
    }
}
