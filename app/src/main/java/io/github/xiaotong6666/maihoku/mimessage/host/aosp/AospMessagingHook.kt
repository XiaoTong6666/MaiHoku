package io.github.xiaotong6666.maihoku.mimessage.host.aosp

import android.os.Bundle
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostApplicationHook
import java.lang.reflect.Method
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** DexKit adapter for the AOSP/Lineage Messaging client (com.android.messaging). */
object AospMessagingHook : BaseHook() {
    override val name: String = "AospMessagingXiaomiNetworkMessages"

    // ApplicationSettingsActivity$ApplicationSettingsFragment writes this stable preference
    // namespace before inflating its XML. It is a method-body fingerprint, not an R8 name.
    private const val SETTINGS_PREFERENCE_FILE = "bugle"
    private const val PREFERENCE_FRAGMENT_CLASS = "androidx.preference.PreferenceFragmentCompat"
    private const val TAG = "AospMsg"

    private data class Targets(
        val settingsFragment: Class<*>,
        val createPreferences: Method,
        val resume: Method,
        val pause: Method,
    )

    override fun init(runtime: HookRuntime) {
        val receiveAdapter = AospMessagingReceiveActionAdapter.resolve(runtime)
        val sendAdapter = AospMessagingSendAdapter.resolve(runtime)
        if (receiveAdapter == null) {
            Log.e(TAG, "AOSP native receive adapter could not be resolved; incoming network SMS will remain pending")
        }
        if (sendAdapter == null) {
            Log.e(TAG, "AOSP native outgoing-message adapter could not be resolved; Mi Message sending will remain blocked")
        }
        MessagingHostApplicationHook.install(
            runtime = runtime,
            hookPrefix = "aosp-messaging",
            modulePackageName = runtime.module.moduleApplicationInfo.packageName,
            aospReceiveAdapter = receiveAdapter,
            aospSendAdapter = sendAdapter,
        )
        runCatching {
            AospMessagingPresenceHook.install(runtime, runtime.module.moduleApplicationInfo.packageName, sendAdapter)
        }.onFailure {
            Log.e(TAG, "Could not install AOSP Mi Message Presence UI hooks", it)
        }
        val targets = resolveTargets(runtime) ?: run {
            Log.e(TAG, "Could not resolve AOSP Messaging settings fragment; Xiaomi SMS entry was not installed")
            return
        }

        runtime.hooks.method(targets.createPreferences, "aosp-messaging.xiaomi-settings.preferences") {
            after {
                val fragment = thisObject ?: return@after
                runCatching {
                    AospMessagingPreferences.install(
                        fragment = fragment,
                        classLoader = runtime.classLoader,
                        modulePackageName = runtime.module.moduleApplicationInfo.packageName,
                    )
                }.onFailure {
                    Log.e(TAG, "Unable to install Xiaomi SMS settings in AOSP Messaging", it)
                }
            }
        }

        runtime.hooks.method(targets.resume, "aosp-messaging.xiaomi-settings.refresh-on-resume") {
            after {
                val fragment = thisObject ?: return@after
                if (!targets.settingsFragment.isInstance(fragment)) return@after
                Log.i(TAG, "AOSP Messaging settings fragment resumed")
                runCatching {
                    AospMessagingPreferences.refreshConfigurationPage(
                        fragment = fragment,
                        classLoader = runtime.classLoader,
                        modulePackageName = runtime.module.moduleApplicationInfo.packageName,
                    )
                }.onFailure {
                    Log.w(TAG, "Could not refresh AOSP Messaging SIM status on resume", it)
                }
            }
        }

        runtime.hooks.method(targets.pause, "aosp-messaging.xiaomi-settings.stop-runtime-refresh-on-pause") {
            after {
                val fragment = thisObject ?: return@after
                if (!targets.settingsFragment.isInstance(fragment)) return@after
                AospMessagingPreferences.stopRuntimeRefresh(fragment)
            }
        }

        Log.i(
            TAG,
            "Resolved AOSP Messaging settings fragment ${targets.settingsFragment.name} " +
                "and ${targets.createPreferences.name}(Bundle, String) via DexKit",
        )
    }

    private fun resolveTargets(runtime: HookRuntime): Targets? = runtime.dexKit.useBridge { bridge ->
        val preferenceFragmentClass = runtime.classLoader.loadClass(PREFERENCE_FRAGMENT_CLASS)
        val candidates = bridge.findClass {
            matcher {
                methods {
                    add {
                        returnType = "void"
                        paramTypes("android.os.Bundle", "java.lang.String")
                        usingStrings(SETTINGS_PREFERENCE_FILE)
                    }
                }
            }
        }.mapNotNull { data ->
            runCatching { data.getInstance(runtime.classLoader) }.getOrNull()
        }.filter { clazz ->
            preferenceFragmentClass.isAssignableFrom(clazz) &&
                clazz.declaredMethods.any(::isCreatePreferencesMethod)
        }.distinct()

        if (candidates.size != 1) {
            Log.e(TAG, "Expected one AOSP Messaging PreferenceFragmentCompat using '$SETTINGS_PREFERENCE_FILE', found ${candidates.size}")
            return@useBridge null
        }

        val fragment = candidates.single()
        val method = fragment.declaredMethods.singleOrNull(::isCreatePreferencesMethod)
            ?.apply { isAccessible = true }
            ?: return@useBridge null
        val resume = generateSequence(fragment as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { candidate ->
                candidate.name == "onResume" && candidate.returnType == Void.TYPE && candidate.parameterCount == 0
            }?.apply { isAccessible = true } ?: run {
            Log.e(TAG, "AOSP Messaging settings fragment does not expose onResume()")
            return@useBridge null
        }
        val pause = generateSequence(fragment as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { candidate ->
                candidate.name == "onPause" && candidate.returnType == Void.TYPE && candidate.parameterCount == 0
            }?.apply { isAccessible = true } ?: run {
            Log.e(TAG, "AOSP Messaging settings fragment does not expose onPause()")
            return@useBridge null
        }
        Targets(fragment, method, resume, pause)
    }

    private fun isCreatePreferencesMethod(method: Method): Boolean = method.name == "onCreatePreferences" &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.contentEquals(arrayOf(Bundle::class.java, String::class.java))
}
