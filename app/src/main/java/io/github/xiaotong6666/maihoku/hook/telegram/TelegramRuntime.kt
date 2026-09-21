package io.github.xiaotong6666.maihoku.hook.telegram

import android.util.Log
import io.github.xiaotong6666.maihoku.hook.HookRuntime

internal class TelegramRuntime(
    val base: HookRuntime,
) {
    val module = base.module
    val classLoader = base.classLoader
    val hooks = base.hooks
    val dexKit = base.dexKit
    val config by lazy(LazyThreadSafetyMode.NONE) { TelegramConfig.load(this) }

    fun logUnsupported(featureId: String, error: Throwable) {
        Log.w(TAG, "$featureId UNSUPPORTED: ${error.message}", error)
    }

    companion object {
        const val TAG = "MaiHoku-Telegram"
    }
}
