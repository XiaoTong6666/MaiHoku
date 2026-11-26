package io.github.xiaotong6666.maihoku.features.wifi.host.miui

import android.net.wifi.WifiConfiguration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.host.common.CONFIG_FINGERPRINT
import io.github.xiaotong6666.maihoku.features.wifi.host.common.WifiConnectionState
import io.github.xiaotong6666.maihoku.features.wifi.host.common.applyOptions
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hasExplicitEdits
import io.github.xiaotong6666.maihoku.features.wifi.host.common.s
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiPolicyCommitHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal enum class MiuiSurface { MANUAL_ADD, WIFI_DIALOG, SAVED_EDIT }

/**
 * Xiaomi's saved-network editor is a separate `wifi_edit_layout` fragment. It returns
 * a WifiConfiguration in its final Bundle; it does NOT call WifiConfigController2.getConfig().
 * The hook is installed on the stable EditPreferenceFragment.onSave(Bundle) entry, but
 * only for the exact fragment identified by the DexKit bytecode fingerprint.
 */
internal fun installMiuiWifiDetail(runtime: HookRuntime) {
    val matches = runCatching {
        runtime.dexKit.useBridge { bridge ->
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().apply {
                        returnType = "void"
                        paramTypes = listOf("android.os.Bundle")
                        usingStrings("AmlMiuiWifiService")
                    },
                ),
            ).mapNotNull {
                runCatching {
                    it.getMethodInstance(runtime.classLoader)
                }.getOrNull()
            }.filter { method ->
                val owner = method.declaringClass
                owner.superclass?.simpleName == "EditPreferenceFragment" &&
                    owner.declaredMethods.any {
                        it.name == "onSave" &&
                            it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                    }
            }.map { it.declaringClass }.distinct()
        }
    }.onFailure { Log.w(TAG, "DexKit Xiaomi edit-network fingerprint failed", it) }
        .getOrNull()
    if (matches.isNullOrEmpty()) {
        Log.i(TAG, "No Xiaomi saved-network editor recognized")
        return
    }
    if (matches.size != 1) {
        Log.w(TAG, "Multiple Xiaomi saved-network editors matched; skipping unsafe injection")
        return
    }
    val fragmentClass = matches.single()
    val lifecycle = fragmentClass.getMethod("onActivityCreated", Bundle::class.java)
    val viewMethod = fragmentClass.getMethod("getView")
    val saveMethod = fragmentClass.superclass.getDeclaredMethod("onSave", Bundle::class.java)
        .apply { isAccessible = true }
    val entryField = fragmentClass.declaredFields.firstOrNull {
        it.type.name == "com.android.wifitrackerlib.WifiEntry"
    }?.apply { isAccessible = true }
    runtime.hooks.method(lifecycle, "wifi.advanced.miui.detail-init") {
        after {
            val fragment = thisObject ?: return@after
            val view = runCatching { viewMethod.invoke(fragment) as? View }.getOrNull()
                ?: return@after
            view.post {
                val entry = runCatching { entryField?.get(fragment) }.getOrNull()
                runCatching {
                    injectMiui(fragment, view, runtime.packageName, MiuiSurface.SAVED_EDIT, entry)
                }.onFailure { Log.w(TAG, "Xiaomi saved Wi-Fi editor UI injection failed", it) }
            }
        }
    }
    runtime.hooks.around(saveMethod, "wifi.advanced.miui.detail-save") {
        val fragment = thisObject
        val bundle = args.firstOrNull() as? Bundle
        val state = fragment?.let { WifiConnectionState.get(it) }
        if (fragment == null || !fragmentClass.isInstance(fragment) || state == null || bundle == null) {
            return@around proceed()
        }
        @Suppress("DEPRECATION")
        val nativeConfig = bundle.getParcelable<WifiConfiguration>("config") ?: return@around proceed()
        val updated = runCatching { applyOptions(state, nativeConfig) }.getOrElse { failure ->
            Log.w(TAG, "Rejected invalid saved Wi-Fi edits; retaining Settings editor", failure)
            Toast.makeText(
                state.context,
                s(state.context, R.string.wifi_advanced_invalid),
                Toast.LENGTH_LONG,
            ).show()
            return@around null
        }
        if (updated != null) {
            bundle.putParcelable("config", updated.config)
            WifiPolicyCommitHook.stage(updated.config, state.context, updated.ssid, updated.expectation)
            Log.i(TAG, "Xiaomi saved Wi-Fi editor passed explicitly changed fields to native save")
        } else {
            Log.d(TAG, "Xiaomi saved Wi-Fi editor preserving original configuration")
        }
        proceed()
    }
    Log.i(TAG, "DexKit installed Xiaomi saved-network editor hooks: ${fragmentClass.name}")
}

/**
 * HyperOS manual-add uses a *separate* Fragment, not WifiConfigController2.
 * Match the actual getConfig bytecode with DexKit and verify its Fragment lifecycle.
 * The log tag is a fingerprint, not a class name used for reflection.
 */
internal fun installMiuiAddNetwork(runtime: HookRuntime) {
    val candidate = runCatching {
        runtime.dexKit.useBridge { bridge ->
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().apply {
                        returnType = "android.net.wifi.WifiConfiguration"
                        paramTypes = emptyList()
                        usingStrings("MiuiAddNetworkFragment", CONFIG_FINGERPRINT)
                    },
                ),
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
            }.filter { method ->
                method.parameterCount == 0 && method.returnType == WifiConfiguration::class.java &&
                    method.declaringClass.methods.any {
                        it.name == "onActivityCreated" &&
                            it.parameterTypes.contentEquals(arrayOf(android.os.Bundle::class.java))
                    } && method.declaringClass.methods.any {
                        it.name == "getView" && it.parameterCount == 0 &&
                            View::class.java.isAssignableFrom(it.returnType)
                    }
            }.distinct()
        }
    }.onFailure { Log.w(TAG, "MIUI DexKit target resolution failed", it) }.getOrNull()
    if (candidate.isNullOrEmpty()) return
    if (candidate.size != 1) {
        Log.w(TAG, "Ambiguous MIUI add-network configuration targets; leaving native Settings untouched")
        return
    }
    val configMethod = candidate.single()
    val fragmentClass = configMethod.declaringClass
    val onCreated = fragmentClass.getMethod("onActivityCreated", android.os.Bundle::class.java)
    val getView = fragmentClass.getMethod("getView")
    runtime.hooks.method(onCreated, "wifi.advanced.miui.onActivityCreated") {
        after {
            val fragment = thisObject ?: return@after
            val view = runCatching { getView.invoke(fragment) as? View }.getOrNull()
                ?: run {
                    Log.w(TAG, "MIUI view unavailable after onActivityCreated")
                    return@after
                }
            // Xiaomi initializes the Fragment inside a MIUIX SpringBackLayout; wait for
            // that layout to attach before inserting our cloned native controls.
            view.post {
                runCatching {
                    injectMiui(
                        fragment,
                        view,
                        runtime.packageName,
                        MiuiSurface.MANUAL_ADD,
                    )
                }
                    .onFailure { Log.w(TAG, "Unable to attach native MIUI Wi-Fi fields", it) }
            }
        }
    }
    runtime.hooks.method(configMethod, "wifi.advanced.miui.configuration") {
        after {
            val fragment = thisObject ?: return@after
            val config = result as? WifiConfiguration ?: return@after
            val state = WifiConnectionState.get(fragment)
                ?: return@after
            runCatching { applyOptions(state, config) }
                .onSuccess { updated ->
                    if (updated != null) {
                        result = updated.config
                        WifiPolicyCommitHook.stage(updated.config, state.context, updated.ssid, updated.expectation)
                    }
                }.onFailure { error ->
                    result = null
                    Log.w(TAG, "Invalid MIUI Wi-Fi settings; refusing to submit native configuration", error)
                    Toast.makeText(
                        state.context,
                        s(state.context, R.string.wifi_advanced_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }
    // The native onSave() would otherwise finish the Activity even if getConfig()
    // was rejected. Validate first and leave the editor open on invalid input.
    val onSave = fragmentClass.getDeclaredMethod("onSave")
    runtime.hooks.around(onSave, "wifi.advanced.miui.validate-before-save") {
        val fragment = thisObject ?: return@around proceed()
        val state = WifiConnectionState.get(fragment)
            ?: return@around proceed()
        if (!hasExplicitEdits(state)) return@around proceed()
        val preview = runCatching { configMethod.invoke(fragment) as? WifiConfiguration }
            .onFailure { Log.w(TAG, "MIUI pre-save config validation failed", it) }
            .getOrNull()
        if (preview == null) {
            Log.w(TAG, "MIUI advanced validation rejected save; keeping editor open")
            return@around null
        }
        proceed()
    }
    Log.i(TAG, "DexKit installed MIUI native add-network hooks: ${fragmentClass.name}")
}
