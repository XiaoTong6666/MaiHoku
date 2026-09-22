package io.github.xiaotong6666.maihoku.hook.telegram

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.xiaotong6666.maihoku.hook.HookRuntime

internal class TelegramRuntime(
    val base: HookRuntime,
) {
    val module = base.module
    val classLoader = base.classLoader
    val hooks = base.hooks
    val dexKit = base.dexKit
    val strings = ModuleStringResources(module.moduleApplicationInfo)
    val config by lazy(LazyThreadSafetyMode.NONE) { TelegramConfig.load(this) }

    fun logUnsupported(featureId: String, error: Throwable) {
        Log.w(TAG, "$featureId UNSUPPORTED: ${error.message}", error)
    }

    companion object {
        const val TAG = "MaiHoku-Telegram"
    }
}

internal class ModuleStringResources(
    private val applicationInfo: ApplicationInfo,
) {
    fun get(context: Context, resId: Int, vararg formatArgs: Any): String {
        val resources = context.packageManager.getResourcesForApplication(applicationInfo)
        return if (formatArgs.isEmpty()) {
            resources.getString(resId)
        } else {
            resources.getString(resId, *formatArgs)
        }
    }
}
