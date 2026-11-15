package io.github.xiaotong6666.maihoku

import io.github.kyuubiran.ezxhelper.core.EzXReflection
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.DoubleClickDrawerHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.hook.ScreenRecorderHighFpsHook
import io.github.xiaotong6666.maihoku.hook.telegram.TelegramHook
import io.github.xiaotong6666.maihoku.mimessage.host.aosp.AospMessagingHook
import io.github.xiaotong6666.maihoku.mimessage.host.google.GoogleMessagesHook
import io.github.xiaotong6666.maihoku.mimessage.telephony.PrivilegedSimIdentityHook
import io.github.xiaotong6666.maihoku.mimessage.ui.MessagingResources
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

const val SCREEN_RECORDER_PACKAGE = "com.miui.screenrecorder"
const val TERMUX_PACKAGE = "com.termux"
const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
const val AOSP_MESSAGING_PACKAGE = "com.android.messaging"
const val MIUI_MESSAGES_PACKAGE = "com.android.mms"
const val AOSP_PHONE_PACKAGE = "com.android.phone"
private val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
)

private val targetPackages = setOf(
    SCREEN_RECORDER_PACKAGE,
    TERMUX_PACKAGE,
    GOOGLE_MESSAGES_PACKAGE,
    AOSP_MESSAGING_PACKAGE,
    MIUI_MESSAGES_PACKAGE,
    AOSP_PHONE_PACKAGE,
) + TELEGRAM_PACKAGES

class MainHook : XposedModule() {
    private val initializedHooks = mutableSetOf<String>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Loaded by $frameworkName $frameworkVersion, API $apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in targetPackages) return
        EzXReflection.init(param.classLoader)
        MessagingResources.initialize(moduleApplicationInfo)

        val runtime = HookRuntime(
            module = this,
            packageName = param.packageName,
            classLoader = param.classLoader,
            applicationInfo = param.applicationInfo,
        )

        when (param.packageName) {
            SCREEN_RECORDER_PACKAGE -> initHooks(runtime, ScreenRecorderHighFpsHook)
            TERMUX_PACKAGE -> initHooks(runtime, DoubleClickDrawerHook)
            GOOGLE_MESSAGES_PACKAGE -> initHooks(runtime, GoogleMessagesHook)
            AOSP_MESSAGING_PACKAGE -> initHooks(runtime, AospMessagingHook)
            MIUI_MESSAGES_PACKAGE, AOSP_PHONE_PACKAGE -> initHooks(runtime, PrivilegedSimIdentityHook)
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
