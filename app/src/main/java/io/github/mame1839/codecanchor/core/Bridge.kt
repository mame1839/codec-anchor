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

    // 設定アプリのフック宛て。**AppConfig は流さない** — 宛先が com.android.settings になるので、
    // 全機器の MAC と名前が乗る JSON を渡すことになる。あちらが要る値だけを extra で持たせる。
    val ACTION_PUSH_SETTINGS_HOOK = "$PKG.action.PUSH_SETTINGS_HOOK"

    // 設定アプリのフックが起動時に投げる要求。**ACTION_REQUEST_CONFIG を使い回さない** —
    // あちらに答えると Bluetooth プロセスへも設定が飛び、接続中の全機器の取り直し
    // (LDAC の実効ビットレートは 1 台で数秒かかる) が開発者向けオプションを開くたびに走る。
    val ACTION_REQUEST_SETTINGS_HOOK = "$PKG.action.REQUEST_SETTINGS_HOOK"

    const val EXTRA_JSON = "json"
    const val EXTRA_MAC = "mac"
    const val EXTRA_FREE_OFFLOAD_SWITCH = "freeOffloadSwitch"

    // フックは XSharedPreferences で同じファイルを直接読む。片側だけ改名しないよう両側からここを参照する。
    const val PREFS_NAME = "config"
    const val PREFS_KEY = "json"

    // 設定アプリのフックが名乗ってきた時刻。アプリが動いていない間に届くので残しておく。
    const val PREFS_KEY_SETTINGS_HOOKED = "settingsHookedAt"

    /**
     * 設定アプリの開発者向けオプションのトグルを解放するか。既定は解放する。
     *
     * **`AppConfig` の JSON に入れていないのは意図的。**あちらは Bluetooth プロセスのフックが
     * decode → 再 encode して hashCode を返す値で、アプリはその一致を「設定が届いた」の判定に
     * 使っている。この値は Bluetooth 側が一切使わないのに、キーを足すと**それを知らない
     * 古いフックが再 encode で落として hash が永久に食い違い**、Bluetooth プロセスが
     * 再起動するまで「変更が届いていません」と嘘を出し続ける。
     * 独立した鍵にすれば、どちらの側も何も落とさない。
     */
    const val PREFS_KEY_FREE_OFFLOAD_SWITCH = "freeOffloadSwitch"

    /**
     * [PREFS_KEY_FREE_OFFLOAD_SWITCH] の既定。**保存にも、受信の取りこぼしにも、フック側の
     * 初期値にも同じものを使う** — 別々に書くと、経路のどれか 1 本だけ既定が違う状態になる。
     *
     * `const` なので参照側でリテラルに畳まれ、フックが読んでも [Bridge] のクラスロードは増えない。
     */
    const val FREE_OFFLOAD_SWITCH_DEFAULT = true

    const val HOOK_REQUEST_RECEIVER = "io.github.mame1839.codecanchor.bridge.HookRequestReceiver"

    val BLUETOOTH_PACKAGES = setOf(
        "com.android.bluetooth",
        "com.google.android.bluetooth",
    )

    // 開発者向けオプションのオフロードのトグルを解放するためだけに入る (SettingsHook)。
    const val SETTINGS_PACKAGE = "com.android.settings"
}
