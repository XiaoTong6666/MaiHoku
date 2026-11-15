package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.Process
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingReceiveActionAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingSendAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesSendAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesSmsDeliverAdapter
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Installs the host-UID inbox receiver from the target app's actual Application lifecycle. */
internal object MessagingHostApplicationHook {
    private const val TAG = "MessagingHost"

    fun install(
        runtime: HookRuntime,
        hookPrefix: String,
        modulePackageName: String,
        aospReceiveAdapter: AospMessagingReceiveActionAdapter? = null,
        googleMessagesSmsDeliverAdapter: GoogleMessagesSmsDeliverAdapter? = null,
        aospSendAdapter: AospMessagingSendAdapter? = null,
        googleMessagesSendAdapter: GoogleMessagesSendAdapter? = null,
    ) {
        val applicationClass = runtime.applicationInfo.className
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { runtime.classLoader.loadClass(it) }.getOrNull() }
            ?: Application::class.java
        val onCreate = generateSequence(applicationClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz ->
                runCatching { clazz.getDeclaredMethod("onCreate") }.getOrNull()
            }.firstOrNull()
            ?.apply { isAccessible = true }
            ?: Application::class.java.getDeclaredMethod("onCreate").apply { isAccessible = true }

        fun register(application: Application?) {
            if (application == null || application.packageName != runtime.packageName) return
            val processName = currentProcessName(application) ?: runtime.packageName
            if (processName != runtime.packageName) {
                Log.i(TAG, "Skipping inbox bridge in secondary process=$processName")
                return
            }
            Log.i(TAG, "Host Application.onCreate observed package=${application.packageName}")
            runCatching {
                MessagingHostBridge.install(
                    context = application,
                    modulePackageName = modulePackageName,
                    aospReceiveAdapter = aospReceiveAdapter,
                    googleMessagesSmsDeliverAdapter = googleMessagesSmsDeliverAdapter,
                    aospSendAdapter = aospSendAdapter,
                    googleMessagesSendAdapter = googleMessagesSendAdapter,
                )
            }.onFailure {
                Log.e(TAG, "Could not register host inbox bridge", it)
            }
        }

        runtime.hooks.method(onCreate, "$hookPrefix.inbox-bridge.application-onCreate") {
            after { register(thisObject as? Application) }
        }

        // Keep Instrumentation as a second lifecycle entry point. The bridge is idempotent, so
        // apps that invoke both callbacks still register only one receiver per process.
        val instrumentationCreate = Instrumentation::class.java
            .getDeclaredMethod("callApplicationOnCreate", Application::class.java)
            .apply { isAccessible = true }
        runtime.hooks.method(instrumentationCreate, "$hookPrefix.inbox-bridge.instrumentation-onCreate") {
            after { register(arg(0) as? Application) }
        }

        Log.i(
            TAG,
            "Installed inbox lifecycle hooks appClass=${applicationClass.name} onCreate=${onCreate.declaringClass.name}",
        )
    }

    private fun currentProcessName(context: Application): String? {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
    }
}
