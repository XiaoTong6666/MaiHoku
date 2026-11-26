package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.net.wifi.WifiConfiguration
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

/**
 * Settings normally calls WifiConfigController.getConfig() *inside* onSubmit().
 * Returning null on an invalid explicit edit is not sufficient for connection:
 * the stock WifiSettings handler can fall back to WifiEntry.connect(). Reject
 * the submit itself when there was a genuine user edit that failed validation.
 *
 * Deliberately no effect on a completely untouched native configuration.
 */
internal fun installNativeSubmitGuards(runtime: HookRuntime) {
    // AOSP/LineageOS AddNetworkFragment has its own submit path and may finish
    // when getConfig() returns null. Keep the editor open on an invalid explicit
    // MaiHoku edit, rather than turning validation failure into a silent cancel.
    runCatching {
        val add = runtime.classLoader.loadClass("com.android.settings.wifi.AddNetworkFragment")
        val submit = add.getDeclaredMethod("handleSubmitAction")
        val controllerField = add.declaredFields.singleOrNull { field ->
            field.type.declaredMethods.any { method ->
                method.parameterCount == 0 &&
                    method.returnType == WifiConfiguration::class.java &&
                    method.name == "getConfig"
            }
        }?.apply { isAccessible = true }
        if (controllerField != null) {
            runtime.hooks.around(submit, "wifi.advanced.validate-add-network-submit") {
                val fragment = thisObject ?: return@around proceed()
                val controller = runCatching { controllerField.get(fragment) }.getOrNull()
                    ?: return@around proceed()
                val state = WifiConnectionState.get(controller)
                    ?: return@around proceed()
                if (!hasExplicitEdits(state)) return@around proceed()
                val preview = runCatching {
                    controller.javaClass.getMethod("getConfig").invoke(controller)
                        as? WifiConfiguration
                }.onFailure { Log.w(TAG, "AddNetwork submit validation failed", it) }
                    .getOrNull()
                if (preview == null) {
                    Log.w(TAG, "AddNetwork submit blocked by invalid explicit advanced edit")
                    return@around null
                }
                proceed()
            }
            Log.i(TAG, "AOSP AddNetworkFragment submit validation installed")
        }
    }.onFailure { Log.d(TAG, "AOSP AddNetworkFragment submit guard not available on this ROM") }

    val owners = listOf(
        "com.android.settings.wifi.WifiSettings",
        "com.android.settings.wifi.WifiDialogActivity",
    )
    for (ownerName in owners) {
        val owner = runCatching { runtime.classLoader.loadClass(ownerName) }.getOrNull()
            ?: continue
        val methods = owner.declaredMethods.filter { method ->
            method.name == "onSubmit" && method.parameterCount == 1 &&
                method.parameterTypes[0].simpleName in setOf("WifiDialog", "WifiDialog2")
        }
        for (method in methods) {
            runtime.hooks.around(
                method,
                "wifi.advanced.validate-submit.${method.declaringClass.name}." +
                    method.parameterTypes[0].simpleName,
            ) {
                val dialog = arg(0) ?: return@around proceed()
                val controller = runCatching {
                    dialog.javaClass.getMethod("getController").invoke(dialog)
                }.getOrNull() ?: return@around proceed()
                val state = WifiConnectionState.get(controller)
                    ?: return@around proceed()
                if (!hasExplicitEdits(state)) return@around proceed()
                val preview = runCatching {
                    controller.javaClass.getMethod("getConfig").invoke(controller) as? WifiConfiguration
                }.onFailure { Log.w(TAG, "Wi-Fi connection preview failed", it) }
                    .getOrNull()
                if (preview == null) {
                    Log.w(TAG, "Blocked native submit with invalid advanced Wi-Fi options")
                    return@around null
                }
                proceed()
            }
        }
        Log.i(TAG, "Native Wi-Fi submit guards installed for ${owner.simpleName}: ${methods.size}")
    }
}
