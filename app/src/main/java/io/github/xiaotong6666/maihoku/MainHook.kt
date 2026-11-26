package io.github.xiaotong6666.maihoku

import io.github.kyuubiran.ezxhelper.core.EzXReflection
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.xiaotong6666.maihoku.features.googleone.GoogleOneHook
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingHook
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesHook
import io.github.xiaotong6666.maihoku.features.mimessage.telephony.PrivilegedSimIdentityHook
import io.github.xiaotong6666.maihoku.features.screenrecorder.ScreenRecorderHighFpsHook
import io.github.xiaotong6666.maihoku.features.telegram.TelegramHook
import io.github.xiaotong6666.maihoku.features.termux.DoubleClickDrawerHook
import io.github.xiaotong6666.maihoku.features.wifi.host.common.WifiSettingsHook
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiPolicyCommitHook
import io.github.xiaotong6666.maihoku.features.wifi.service.WifiServiceBootstrap
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

const val SCREEN_RECORDER_PACKAGE = "com.miui.screenrecorder"
const val TERMUX_PACKAGE = "com.termux"
const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
const val AOSP_MESSAGING_PACKAGE = "com.android.messaging"
const val MIUI_MESSAGES_PACKAGE = "com.android.mms"
const val AOSP_PHONE_PACKAGE = "com.android.phone"
const val GOOGLE_ONE_PACKAGE = "com.google.android.apps.subscriptions.red"
const val SETTINGS_PACKAGE = "com.android.settings"
const val SYSTEM_FRAMEWORK_PACKAGE = "android"
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
    GOOGLE_ONE_PACKAGE,
    SETTINGS_PACKAGE,

) + TELEGRAM_PACKAGES

class MainHook : XposedModule() {
    private val initializedHooks = mutableSetOf<String>()
    private val wifiServiceBootstrap = WifiServiceBootstrap(this) { runtime, hook ->
        initHooks(runtime, hook)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Loaded by $frameworkName $frameworkVersion, API $apiVersion")
        wifiServiceBootstrap.onModuleLoaded(param.isSystemServer)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        wifiServiceBootstrap.onSystemServerStarting(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        wifiServiceBootstrap.onPackageReady(param.classLoader)
        if (param.packageName !in targetPackages) return
        EzXReflection.init(param.classLoader)
        ModuleResources.initialize(moduleApplicationInfo)

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
            GOOGLE_ONE_PACKAGE -> initHooks(runtime, GoogleOneHook)
            SETTINGS_PACKAGE -> initHooks(runtime, WifiSettingsHook, WifiPolicyCommitHook)
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
