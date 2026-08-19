package io.github.mame1839.codecanchor.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mame1839.codecanchor.core.Bridge

// フックからアプリへの入り口。どちらの合図もアプリが動いていない間に届くので、実行時登録では
// 受け取れない。マニフェストのレシーバはここ 1 つにまとめて、外に開く口を増やさない。
class HookRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Bridge.ACTION_REQUEST_CONFIG -> pushSaved(context)
            // 設定アプリのフックはこちら。Bluetooth 側には何も投げない。
            Bridge.ACTION_REQUEST_SETTINGS_HOOK -> pushSettingsHook(context)
            // 設定アプリのフックが判定を差し替えた合図。開発者向けオプションのトグルが
            // 解放されていることの裏付けになるので、案内の出し分けのために残す。
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

// exported なレシーバには明示 Intent で任意の action が届くので、受け取る action を絞る。
private val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

// onReceive で投げた例外はアプリのプロセスを落とす。
//
// 起動と更新のときは両方の行き先へ配る。設定アプリ側の値は AppConfig とは別の鍵なので、
// JSON が壊れていても送れる (送らないと、壊れた設定を直すまで介入のオンオフだけが届かない)。
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
