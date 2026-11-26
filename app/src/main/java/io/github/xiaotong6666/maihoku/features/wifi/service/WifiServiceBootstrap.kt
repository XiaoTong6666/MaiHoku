package io.github.xiaotong6666.maihoku.features.wifi.service

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.xiaotong6666.maihoku.SYSTEM_FRAMEWORK_PACKAGE
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Discovers the Wi-Fi APEX loader across early and late system_server callbacks. */
internal class WifiServiceBootstrap(
    private val module: XposedModule,
    private val initialize: (HookRuntime, BaseHook) -> Unit,
) {
    private companion object {
        const val TAG = "MaiHoku"
    }

    private var systemServerProcess = false
    private var systemServerRetryPosted = false

    fun onModuleLoaded(isSystemServer: Boolean) {
        systemServerProcess = isSystemServer
        if (systemServerProcess) {
            Log.i(TAG, "Module loaded in system_server; preparing Wi-Fi service hook")
            trySystemServerInit(Thread.currentThread().contextClassLoader)
            // Some frameworks dispatch the bootstrap callback before loading modern
            // modules, or inject after services are live. Retry on the main thread.
            if (!systemServerRetryPosted) {
                systemServerRetryPosted = true
                val handler = Looper.getMainLooper()?.let(::Handler)
                if (handler != null) {
                    var attempts = 0
                    val retry = object : Runnable {
                        override fun run() {
                            trySystemServerInit(Thread.currentThread().contextClassLoader)
                            if (!WifiServicePolicyHook.isInstalled() && ++attempts < 60) {
                                handler.postDelayed(this, 1000L)
                            }
                        }
                    }
                    handler.post(retry)
                }
            }
        }
    }

    // Wi-Fi service lives in system_server, not in an ordinary android package process.
    fun onSystemServerStarting(classLoader: ClassLoader) {
        Log.i(TAG, "System server starting: initializing Wi-Fi policy compatibility hook")
        systemServerProcess = true
        trySystemServerInit(classLoader)
    }

    fun onPackageReady(classLoader: ClassLoader) {
        if (systemServerProcess) trySystemServerInit(classLoader)
    }

    @Synchronized
    private fun trySystemServerInit(preferred: ClassLoader?) {
        if (!systemServerProcess || WifiServicePolicyHook.isInstalled()) return
        val loaders = listOfNotNull(
            preferred,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        val loader = loaders.firstOrNull { candidate ->
            runCatching { candidate.loadClass("com.android.server.SystemServiceManager") }.isSuccess
        } ?: return
        val runtime = HookRuntime(module, SYSTEM_FRAMEWORK_PACKAGE, loader, module.moduleApplicationInfo)
        initialize(runtime, WifiServicePolicyHook)
        WifiServicePolicyHook.tryRunningService(runtime)
    }
}
