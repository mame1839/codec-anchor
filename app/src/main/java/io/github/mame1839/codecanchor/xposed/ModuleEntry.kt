package io.github.mame1839.codecanchor.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mame1839.codecanchor.core.Bridge

class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            in Bridge.BLUETOOTH_PACKAGES ->
                runCatching { A2dpHook.install(lpparam.classLoader, lpparam.packageName) }
                    .onFailure { XLog.e("フックの設置に失敗した", it) }

            Bridge.SETTINGS_PACKAGE ->
                runCatching { SettingsHook.install(lpparam.classLoader) }
                    .onFailure { XLog.e("設定アプリのフックの設置に失敗した", it) }
        }
    }
}
