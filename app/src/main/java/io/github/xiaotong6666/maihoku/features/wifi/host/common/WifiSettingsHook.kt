package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.net.wifi.WifiConfiguration
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.host.aosp.injectAosp
import io.github.xiaotong6666.maihoku.features.wifi.host.miui.MiuiSurface
import io.github.xiaotong6666.maihoku.features.wifi.host.miui.injectMiui
import io.github.xiaotong6666.maihoku.features.wifi.host.miui.installMiuiAddNetwork
import io.github.xiaotong6666.maihoku.features.wifi.host.miui.installMiuiWifiDetail
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiPolicyCommitHook
import io.github.xiaotong6666.maihoku.hook.BaseHook
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Keeps the stock Settings Wi-Fi layout and save flow; adds an independent native-styled section. */
internal object WifiSettingsHook : BaseHook() {
    override val name = "WifiAdvancedConnection"
    private const val TAG = "WifiAdvancedConnection"
    override fun init(runtime: HookRuntime) {
        val targets = resolveTargets(runtime)
        if (targets.isEmpty()) {
            Log.w(TAG, "No structurally matching AOSP Wi-Fi controller; checking OEM fragment")
        }
        for (target in targets) {
            val id = target.configMethod.declaringClass.name
            for ((index, ctor) in target.constructors.withIndex()) {
                runtime.hooks.method(ctor.constructor, "wifi.advanced.controller-init.$id.$index") {
                    after {
                        val controller = thisObject ?: return@after
                        val view = arg(ctor.viewArg) as? View ?: return@after
                        val mode = arg(ctor.modeArg) as? Int ?: return@after
                        val entry = arg(ctor.entryArg)
                        runCatching {
                            // HyperOS WifiDialog2 reuses WifiConfigController2 but supplies a
                            // different XML than AOSP. Its native MIUIX rows need a separate path.
                            if (mode != 0 && hostView(
                                    view,
                                    view.context,
                                    runtime.packageName,
                                    "l_wifidialog",
                                ) != null &&
                                view.context.resources.getIdentifier(
                                    "wifi_add_network_layout",
                                    "layout",
                                    runtime.packageName,
                                ) != 0
                            ) {
                                Log.i(TAG, "HyperOS connection/edit dialog detected; using MIUIX UI")
                                view.post {
                                    runCatching {
                                        injectMiui(
                                            controller,
                                            view,
                                            runtime.packageName,
                                            MiuiSurface.WIFI_DIALOG,
                                            entry,
                                        )
                                    }.onFailure { Log.w(TAG, "MIUIX Wi-Fi dialog UI injection failed", it) }
                                }
                            } else {
                                injectAosp(controller, view, mode, entry, runtime.packageName)
                            }
                        }.onFailure { Log.w(TAG, "Unable to add advanced Wi-Fi fields", it) }
                    }
                }
            }
            runtime.hooks.method(target.configMethod, "wifi.advanced.configuration.$id") {
                after {
                    val controller = thisObject ?: return@after
                    val config = result as? WifiConfiguration ?: return@after
                    val state = WifiConnectionState.get(controller)
                        ?: return@after
                    runCatching { applyOptions(state, config) }
                        .onSuccess { updated ->
                            if (updated != null) {
                                result = updated.config
                                WifiPolicyCommitHook.stage(updated.config, state.context, updated.ssid, updated.expectation)
                            }
                        }
                        .onFailure {
                            // Fail closed: allowing Settings to save the unmodified native
                            // configuration would silently ignore the explicit user choice.
                            result = null
                            Log.w(TAG, "Invalid advanced options; blocking the attempted native configuration", it)
                            Toast.makeText(
                                state.context,
                                s(state.context, R.string.wifi_advanced_invalid),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            }
            // Stock Settings hides the original advanced checkbox after its first click.
            // Keep it visible and collapsible so both native and MaiHoku options remain accessible.
            val onChecked = target.configMethod.declaringClass.methods.firstOrNull { method ->
                method.name == "onCheckedChanged" &&
                    method.parameterTypes.contentEquals(arrayOf(CompoundButton::class.java, Boolean::class.javaPrimitiveType))
            }
            if (onChecked != null) {
                runtime.hooks.method(onChecked, "wifi.advanced.native-toggle.$id") {
                    after {
                        val checkBox = arg(0) as? CompoundButton ?: return@after
                        val toggleId = checkBox.context.resources.getIdentifier("wifi_advanced_togglebox", "id", runtime.packageName)
                        if (toggleId == 0 || checkBox.id != toggleId) return@after
                        checkBox.visibility = View.VISIBLE
                        val fieldsId = checkBox.context.resources.getIdentifier("wifi_advanced_fields", "id", runtime.packageName)
                        (checkBox.rootView.findViewById<View>(fieldsId))?.visibility =
                            if (checkBox.isChecked) View.VISIBLE else View.GONE
                    }
                }
            }
            Log.i(TAG, "DexKit Wi-Fi controller installed: $id")
        }
        installMiuiAddNetwork(runtime)
        installMiuiWifiDetail(runtime)
        installNativeSubmitGuards(runtime)
    }
}
