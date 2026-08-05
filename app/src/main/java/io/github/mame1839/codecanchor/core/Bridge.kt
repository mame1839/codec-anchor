package io.github.mame1839.codecanchor.core

import io.github.mame1839.codecanchor.BuildConfig

object Bridge {
    val PKG: String = BuildConfig.APPLICATION_ID
    val PERMISSION = "$PKG.permission.BRIDGE"

    val ACTION_PUSH_CONFIG = "$PKG.action.PUSH_CONFIG"
    val ACTION_REQUEST_STATUS = "$PKG.action.REQUEST_STATUS"
    val ACTION_APPLY_NOW = "$PKG.action.APPLY_NOW"
    val ACTION_REQUEST_CONFIG = "$PKG.action.REQUEST_CONFIG"
    val ACTION_REPORT = "$PKG.action.REPORT"

    // 設定アプリのプロセスに乗ったフックが、実際に判定を差し替えた時点で名乗ってくる。
    val ACTION_SETTINGS_HOOKED = "$PKG.action.SETTINGS_HOOKED"

    const val EXTRA_JSON = "json"
    const val EXTRA_MAC = "mac"

    // フックは XSharedPreferences で同じファイルを直接読む。片側だけ改名しないよう両側からここを参照する。
    const val PREFS_NAME = "config"
    const val PREFS_KEY = "json"

    // 設定アプリのフックが名乗ってきた時刻。アプリが動いていない間に届くので残しておく。
    const val PREFS_KEY_SETTINGS_HOOKED = "settingsHookedAt"

    const val HOOK_REQUEST_RECEIVER = "io.github.mame1839.codecanchor.bridge.HookRequestReceiver"

    val BLUETOOTH_PACKAGES = setOf(
        "com.android.bluetooth",
        "com.google.android.bluetooth",
    )

    // 開発者向けオプションのオフロードのトグルを解放するためだけに入る (SettingsHook)。
    const val SETTINGS_PACKAGE = "com.android.settings"
}
