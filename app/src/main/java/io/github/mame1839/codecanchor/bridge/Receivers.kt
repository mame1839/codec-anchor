package io.github.mame1839.codecanchor.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mame1839.codecanchor.core.Bridge

class HookRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Bridge.ACTION_REQUEST_CONFIG) return
        pushSaved(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        pushSaved(context)
    }
}

// exported なレシーバには明示 Intent で任意の action が届くので、受け取る action を絞る。
private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

// onReceive で投げた例外はアプリのプロセスを落とす。
private fun pushSaved(context: Context) {
    runCatching {
        SettingsStore(context).load()?.let { BridgeClient.pushConfig(context, it) }
    }
}
