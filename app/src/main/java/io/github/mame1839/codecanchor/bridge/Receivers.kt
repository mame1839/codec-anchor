package io.github.mame1839.codecanchor.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mame1839.codecanchor.core.Bridge

class HookRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Bridge.ACTION_REQUEST_CONFIG) return
        BridgeClient.pushConfig(context, SettingsStore(context).load())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BridgeClient.pushConfig(context, SettingsStore(context).load())
    }
}
