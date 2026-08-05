package io.github.mame1839.codecanchor.xposed

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.mame1839.codecanchor.BuildConfig
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

    fun install(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(CLASS_A2DP_CONTROLLER, classLoader)
        if (clazz == null) {
            XLog.i("設定アプリに $CLASS_A2DP_CONTROLLER が無いので何もしない")
            return
        }
        var hooked = 0
        booleanMethods(clazz, METHOD_A2DP_GATE).forEach { method ->
            runCatching { XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true)) }
                .onSuccess { hooked++ }
                .onFailure { XLog.e("$METHOD_A2DP_GATE を差し替えられない", it) }
        }
        if (hooked == 0) {
            XLog.i("$METHOD_A2DP_GATE が無いので何もしない (この ROM は改造されていない)")
            return
        }
        XLog.i("オフロードのトグルの解放を設置した (module=${BuildConfig.VERSION_NAME})")
    }

    // 戻り値が boolean のものだけ差し替える。同名で別の形のメソッドを持つ ROM に定数を返させない。
    private fun booleanMethods(clazz: Class<*>, name: String): List<Method> = runCatching {
        clazz.declaredMethods.filter {
            it.name == name && it.returnType == Boolean::class.javaPrimitiveType
        }
    }.getOrDefault(emptyList())
}
