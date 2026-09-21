package io.github.xiaotong6666.maihoku

import android.util.Log
import io.github.kyuubiran.ezxhelper.core.EzXReflection
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.DoubleClickDrawerHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.hook.ScreenRecorder120FpsHook
import io.github.xiaotong6666.maihoku.hook.telegram.TelegramHook

const val SCREEN_RECORDER_PACKAGE = "com.miui.screenrecorder"
const val TERMUX_PACKAGE = "com.termux"
private val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
)

private val targetPackages = setOf(SCREEN_RECORDER_PACKAGE, TERMUX_PACKAGE) + TELEGRAM_PACKAGES

class MainHook : XposedModule() {
    private val initializedHooks = mutableSetOf<String>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Loaded by $frameworkName $frameworkVersion, API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in targetPackages) return
        EzXReflection.init(param.classLoader)

        val runtime = HookRuntime(
            module = this,
            packageName = param.packageName,
            classLoader = param.classLoader,
            applicationInfo = param.applicationInfo,
        )

        when (param.packageName) {
            SCREEN_RECORDER_PACKAGE -> initHooks(runtime, ScreenRecorder120FpsHook)
            TERMUX_PACKAGE -> initHooks(runtime, DoubleClickDrawerHook)
            in TELEGRAM_PACKAGES -> initHooks(runtime, TelegramHook)
        }
    }

    private fun initHooks(runtime: HookRuntime, vararg hooks: BaseHook) {
        for (hook in hooks) {
            try {
                val key = "${runtime.packageName}:${hook.name}"
                if (!initializedHooks.add(key)) continue
                hook.init(runtime)
            } catch (t: Throwable) {
                initializedHooks.remove("${runtime.packageName}:${hook.name}")
                Log.e(TAG, "Failed to initialize hook: ${hook.name}", t)
            }
        }
    }

    private companion object {
        const val TAG = "MaiHoku"
    }
}
