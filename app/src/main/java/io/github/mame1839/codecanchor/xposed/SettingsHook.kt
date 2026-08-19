package io.github.mame1839.codecanchor.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.core.Bridge
import java.lang.reflect.Method

internal object SettingsHook {
    private const val CLASS_A2DP_CONTROLLER =
        "com.android.settings.development.BluetoothA2dpHwOffloadPreferenceController"
    private const val METHOD_A2DP_GATE = "isA2dpOffloadPreferenceEnable"

    private const val ANNOUNCE_INTERVAL_MS = 30_000L

    @Volatile
    private var announcedAt = 0L

    @Volatile
    private var freeOffloadSwitch = Bridge.FREE_OFFLOAD_SWITCH_DEFAULT

    @Volatile
    private var bridgeOpen = false

    fun install(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_A2DP_CONTROLLER, classLoader)
        if (clazz == null) {
            XLog.i("設定アプリに $CLASS_A2DP_CONTROLLER が無いので何もしない")
            return
        }
        loadFromPrefs()
        var hooked = 0
        booleanMethods(clazz, METHOD_A2DP_GATE).forEach { method ->
            runCatching { XposedBridge.hookMethod(method, offloadGate) }
                .onSuccess { hooked++ }
                .onFailure { XLog.e("$METHOD_A2DP_GATE を差し替えられない", it) }
        }
        if (hooked == 0) {
            XLog.i("$METHOD_A2DP_GATE が無いので何もしない (この ROM は改造されていない)")
            return
        }
        XLog.i("オフロードのトグルの解放を設置した (module=${BuildConfig.VERSION_NAME})")
    }

    private val offloadGate = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val ctx = param.args.getOrNull(0) as? Context
            runCatching { openBridge(ctx) }
            if (!freeOffloadSwitch) return
            param.result = true
            runCatching { announce(ctx) }
        }
    }

    private fun openBridge(context: Context?) {
        if (bridgeOpen) return
        loadFromPrefs()
        val ctx = context?.applicationContext ?: return
        val filter = IntentFilter(Bridge.ACTION_PUSH_SETTINGS_HOOK)
        val opened = runCatching {
            registerExported(ctx, receiver, filter, Bridge.PERMISSION, null)
        }.onFailure { XLog.e("設定アプリ側の受け口を作れない", it) }.isSuccess
        if (!opened) return
        bridgeOpen = true
        requestSettingsHook(ctx)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            runCatching {
                if (intent?.action != Bridge.ACTION_PUSH_SETTINGS_HOOK) return
                val value = intent.getBooleanExtra(
                    Bridge.EXTRA_FREE_OFFLOAD_SWITCH,
                    Bridge.FREE_OFFLOAD_SWITCH_DEFAULT,
                )
                if (value == freeOffloadSwitch) return
                freeOffloadSwitch = value
                XLog.i("オフロードのトグルの解放: $value")
            }
        }
    }

    private fun requestSettingsHook(ctx: Context) {
        val intent = Intent(Bridge.ACTION_REQUEST_SETTINGS_HOOK).apply {
            setClassName(Bridge.PKG, Bridge.HOOK_REQUEST_RECEIVER)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("設定を要求できない: ${it.message}") }
    }

    private fun loadFromPrefs() {
        runCatching {
            val prefs = XSharedPreferences(Bridge.PKG, Bridge.PREFS_NAME)
            if (!prefs.file.canRead()) {
                XLog.d("設定ファイルを読めない: ${prefs.file.path}")
                return
            }
            freeOffloadSwitch = prefs.getBoolean(
                Bridge.PREFS_KEY_FREE_OFFLOAD_SWITCH,
                Bridge.FREE_OFFLOAD_SWITCH_DEFAULT,
            )
        }.onFailure { XLog.d("設定ファイルを読めない: ${it.message}") }
    }

    private fun announce(context: Context?) {
        val ctx = context ?: return
        val now = SystemClock.uptimeMillis()
        if (announcedAt != 0L && now - announcedAt < ANNOUNCE_INTERVAL_MS) return
        announcedAt = now
        val intent = Intent(Bridge.ACTION_SETTINGS_HOOKED).apply {
            setClassName(Bridge.PKG, Bridge.HOOK_REQUEST_RECEIVER)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        runCatching { ctx.sendBroadcast(intent) }.onFailure { XLog.d("合図を送れない: ${it.message}") }
    }

    private fun booleanMethods(clazz: Class<*>, name: String): List<Method> = runCatching {
        clazz.declaredMethods.filter {
            it.name == name && it.returnType == Boolean::class.javaPrimitiveType
        }
    }.getOrDefault(emptyList())
}
