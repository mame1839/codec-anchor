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

    val ACTION_SETTINGS_HOOKED = "$PKG.action.SETTINGS_HOOKED"

    val ACTION_PUSH_SETTINGS_HOOK = "$PKG.action.PUSH_SETTINGS_HOOK"

    val ACTION_REQUEST_SETTINGS_HOOK = "$PKG.action.REQUEST_SETTINGS_HOOK"

    const val EXTRA_JSON = "json"
    const val EXTRA_MAC = "mac"
    const val EXTRA_FREE_OFFLOAD_SWITCH = "freeOffloadSwitch"

    const val PREFS_NAME = "config"
    const val PREFS_KEY = "json"

    const val PREFS_KEY_SETTINGS_HOOKED = "settingsHookedAt"

    const val PREFS_KEY_FREE_OFFLOAD_SWITCH = "freeOffloadSwitch"

    const val FREE_OFFLOAD_SWITCH_DEFAULT = true

    const val HOOK_REQUEST_RECEIVER = "io.github.mame1839.codecanchor.bridge.HookRequestReceiver"

    val BLUETOOTH_PACKAGES = setOf(
        "com.android.bluetooth",
        "com.google.android.bluetooth",
    )

    const val SETTINGS_PACKAGE = "com.android.settings"
}
