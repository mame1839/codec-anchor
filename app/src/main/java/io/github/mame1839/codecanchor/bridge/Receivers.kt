package io.github.mame1839.codecanchor.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mame1839.codecanchor.core.Bridge

class HookRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Bridge.ACTION_REQUEST_CONFIG -> pushSaved(context)
            Bridge.ACTION_REQUEST_SETTINGS_HOOK -> pushSettingsHook(context)
            Bridge.ACTION_SETTINGS_HOOKED -> runCatching { SettingsStore(context).markSettingsHooked() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        pushSaved(context)
    }
}

private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

private fun pushSaved(context: Context) {
    runCatching {
        val store = SettingsStore(context)
        BridgeClient.pushSettingsHook(context, store.freeOffloadSwitch())
        store.load()?.let { BridgeClient.pushConfig(context, it) }
    }
}

private fun pushSettingsHook(context: Context) {
    runCatching {
        BridgeClient.pushSettingsHook(context, SettingsStore(context).freeOffloadSwitch())
    }
}
