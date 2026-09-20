package io.github.xiaotong6666.maihoku

import android.util.Log
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.DoubleClickDrawerHook
import io.github.xiaotong6666.maihoku.hook.ScreenRecorder120FpsHook

const val SCREEN_RECORDER_PACKAGE = "com.miui.screenrecorder"
const val TERMUX_PACKAGE = "com.termux"

private val targetPackages = setOf(SCREEN_RECORDER_PACKAGE, TERMUX_PACKAGE)

class MainHook : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in targetPackages) return
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in targetPackages) return
        EzXposed.initOnPackageReady(param)

        when (param.packageName) {
            SCREEN_RECORDER_PACKAGE -> initHooks(ScreenRecorder120FpsHook)
            TERMUX_PACKAGE -> initHooks(DoubleClickDrawerHook)
        }
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            try {
                if (hook.isInit) continue
                hook.init()
                hook.isInit = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize hook: ${hook.name}", t)
            }
        }
    }

    private companion object {
        const val TAG = "MaiHoku"
    }
}
