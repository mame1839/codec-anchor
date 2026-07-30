package io.github.mame1839.codecanchor.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in io.github.mame1839.codecanchor.core.Bridge.BLUETOOTH_PACKAGES) return
        runCatching { A2dpHook.install(lpparam.classLoader, lpparam.packageName) }
            .onFailure { XLog.e("フックの設置に失敗した", it) }
    }
}
