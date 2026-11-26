package io.github.xiaotong6666.maihoku.features.wifi.service

import android.content.Context
import android.net.wifi.WifiConfiguration
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiConfigurationOverride
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityMode
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityPolicyStore
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Method
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Prevent selected legacy networks from being transparently re-upgraded to SAE/OWE. */
internal object WifiServicePolicyHook : BaseHook() {
    override val name = "WifiSecurityPolicyService"
    private const val TAG = "WifiPolicyService"

    override fun init(runtime: HookRuntime) {
        // service-wifi.jar is NOT on SYSTEMSERVERCLASSPATH. SystemServiceManager loads it
        // through a dedicated PathClassLoader during startServiceFromJar(). Hooking that
        // exact startup path avoids a global ClassLoader.loadClass hook.
        val serviceManager = runtime.classLoader.loadClass("com.android.server.SystemServiceManager")
        val startService = serviceManager.getDeclaredMethod("startService", Class::class.java)
        runtime.hooks.method(startService, "wifi.policy.service-loader") {
            before {
                val service = arg(0) as? Class<*> ?: return@before
                // The Wi-Fi APEX service is loaded with its own ClassLoader. Match the
                // service family, then verify its actual config API before installing.
                if (!service.name.contains("wifi", ignoreCase = true)) return@before
                if (service.classLoader == null) return@before
                runCatching { installWifiServiceHook(runtime, service.classLoader) }
                    .onFailure { Log.e(TAG, "Failed to install per-SSID Wi-Fi policy", it) }
            }
        }
        Log.i(TAG, "Waiting for the APEX Wi-Fi service ClassLoader")
    }

    fun isInstalled(): Boolean = installed

    /** Handle late framework dispatch using the actual local Wi-Fi binder's loader. */
    fun tryRunningService(runtime: HookRuntime) {
        if (installed) return
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String::class.java).invoke(null, "wifi")
                ?: return
            val loader = binder.javaClass.classLoader ?: return
            if (!binder.javaClass.name.startsWith("com.android.server.wifi.")) return
            installWifiServiceHook(runtime, loader)
        }.onFailure { Log.w(TAG, "Late Wi-Fi service discovery failed", it) }
    }

    @Synchronized
    private fun installWifiServiceHook(runtime: HookRuntime, loader: ClassLoader) {
        if (installed) return
        val addSecurityTypes = resolveUpgradeMethod(runtime, loader) ?: run {
            Log.w(TAG, "Unable to identify a unique Wi-Fi auto-upgrade method; original service unchanged")
            return
        }
        runtime.hooks.method(addSecurityTypes, "wifi.policy.suppress-auto-upgrade") {
            after {
                val config = arg(0) as? WifiConfiguration ?: return@after
                val ssid = config.SSID ?: return@after
                if (ssid.isBlank()) return@after
                try {
                    val context = serviceContext(loader) ?: return@after
                    val requested = WifiSecurityPolicyStore.pendingMode(context, config)
                        ?: WifiSecurityPolicyStore.get(context, config)
                    val type = when (requested) {
                        WifiSecurityMode.WPA2 -> 2
                        WifiSecurityMode.OPEN -> 0
                        else -> return@after
                    }
                    if (WifiConfigurationOverride.keepOnlySecurityType(config, type)) {
                        Log.d(TAG, "Excluded automatic security upgrade for a ${requested.value} network")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot enforce security selection; keeping stock Wi-Fi behavior", t)
                }
            }
        }
        installed = true
        Log.i(TAG, "Per-profile security policy installed on Wi-Fi APEX ClassLoader")
    }

    private fun resolveUpgradeMethod(runtime: HookRuntime, loader: ClassLoader): Method? {
        // Prefer a verified signature when present: avoid expensive native DEX scans
        // during boot on legacy/low-memory devices such as the Xiaomi Mi 5s.
        val knownMethod = runCatching {
            loader.loadClass("com.android.server.wifi.WifiConfigurationUtil").getDeclaredMethod(
                "addUpgradableSecurityTypeIfNecessary",
                WifiConfiguration::class.java,
            )
        }.getOrNull()
        if (knownMethod != null && knownMethod.returnType == Boolean::class.javaPrimitiveType) {
            Log.i(TAG, "Found Wi-Fi auto-upgrade method using verified AOSP signature")
            return knownMethod
        }
        // The distinctive error text occurs in addUpgradableSecurityTypeIfNecessary() in
        // LineageOS 22/23 and crDroid 15/16. It survives private class/method renames.
        val paths = listOf(
            "/apex/com.android.wifi/javalib/service-wifi.jar",
            "/system/framework/service-wifi.jar",
        )
        for (path in paths) {
            if (!File(path).isFile) continue
            val found = runCatching {
                runtime.dexKit.useBridgeForPath(path) { bridge ->
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().apply {
                                returnType = "boolean"
                                paramTypes = listOf("android.net.wifi.WifiConfiguration")
                                usingStrings("Failed to add upgradable security type")
                            },
                        ),
                    ).mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                        .filter {
                            it.returnType == Boolean::class.javaPrimitiveType &&
                                it.parameterTypes.contentEquals(arrayOf(WifiConfiguration::class.java))
                        }
                        .distinct()
                }
            }.onFailure { Log.w(TAG, "Wi-Fi DexKit scan not available", it) }.getOrNull()
            if (found?.size == 1) {
                Log.i(TAG, "Found auto-upgrade method using DexKit Wi-Fi APEX fingerprint")
                return found.single()
            }
            if (found != null && found.size > 1) {
                Log.w(TAG, "Ambiguous Wi-Fi upgrade fingerprint; skipping unsafe method selection")
                return null
            }
        }
        return null
    }

    @Volatile private var installed = false

    private fun serviceContext(loader: ClassLoader): Context? {
        val injector = loader.loadClass("com.android.server.wifi.WifiInjector")
        val singleton = injector.getDeclaredMethod("getInstance").invoke(null) ?: return null
        return injector.getDeclaredMethod("getContext").invoke(singleton) as? Context
    }
}
