package io.github.mame1839.codecanchor.xposed

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
import io.github.mame1839.codecanchor.core.Bridge
import java.lang.reflect.Method

// 設定アプリの開発者向けオプションにある「Bluetooth A2DP ハードウェア オフロードの無効化」は、
// Xiaomi の改造で常に操作できなくなっている。判定を担う isA2dpOffloadPreferenceEnable は
// persist.bluetooth.a2dp_offload.disabled を "false" へ書き戻してから「操作不可」を返し、
// これが画面を開くたび (DashboardFragment の updatePreferenceStates) に走る。
// 常に true を返すよう差し替えると、グレーアウトが外れ、メソッドごと置き換わるので書き戻しも起きない。
// この改造の無い ROM ではクラスもメソッドも見つからないので、何もせずに通る。
internal object SettingsHook {
    private const val CLASS_A2DP_CONTROLLER =
        "com.android.settings.development.BluetoothA2dpHwOffloadPreferenceController"
    private const val METHOD_A2DP_GATE = "isA2dpOffloadPreferenceEnable"

    // 同じ画面の再表示ごとに走るので、アプリのプロセスを起こす合図は間引く。
    private const val ANNOUNCE_INTERVAL_MS = 30_000L

    @Volatile
    private var announcedAt = 0L

    fun install(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_A2DP_CONTROLLER, classLoader)
        if (clazz == null) {
            XLog.i("設定アプリに $CLASS_A2DP_CONTROLLER が無いので何もしない")
            return
        }
        var hooked = 0
        booleanMethods(clazz, METHOD_A2DP_GATE).forEach { method ->
            runCatching { XposedBridge.hookMethod(method, alwaysOperable) }
                .onSuccess { hooked++ }
                .onFailure { XLog.e("$METHOD_A2DP_GATE を差し替えられない", it) }
        }
        if (hooked == 0) {
            XLog.i("$METHOD_A2DP_GATE が無いので何もしない (この ROM は改造されていない)")
            return
        }
        XLog.i("オフロードのトグルの解放を設置した (module=${BuildConfig.VERSION_NAME})")
    }

    // 差し替えが実際に走った時点で名乗る。「フックを設置した」ではなく「開発者向けオプションが
    // 解放された状態で描かれた」を伝えるので、アプリ側はこれをそのまま案内の出し分けに使える。
    private val alwaysOperable = object : XC_MethodReplacement() {
        override fun replaceHookedMethod(param: MethodHookParam): Any {
            runCatching { announce(param.args.getOrNull(0) as? Context) }
            return true
        }
    }

    // 引数の Context は改造されたメソッドが受け取っているもの (Xiaomi 側は使っていない)。
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

    // 戻り値が boolean のものだけ差し替える。同名で別の形のメソッドを持つ ROM に定数を返させない。
    private fun booleanMethods(clazz: Class<*>, name: String): List<Method> = runCatching {
        clazz.declaredMethods.filter {
            it.name == name && it.returnType == Boolean::class.javaPrimitiveType
        }
    }.getOrDefault(emptyList())
}
