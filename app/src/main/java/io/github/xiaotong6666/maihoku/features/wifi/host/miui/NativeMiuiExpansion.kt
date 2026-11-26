package io.github.xiaotong6666.maihoku.features.wifi.host.miui

import android.view.View
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal data class NativeMiuiExpansion(
    val animate: (Int, Boolean) -> Boolean,
    val cancel: () -> Unit,
    val snap: (Int, Boolean) -> Boolean,
)

/**
 * Reuse the actual MIUIX Folme engine and spring physics from Xiaomi's
 * StretchableWidgetPreference, but animate ViewProperty.HEIGHT on a stable
 * FrameLayout viewport. The native preference uses a ValueTarget string
 * property (widgetHeight), which cannot drive a plain Wi-Fi ViewGroup.
 */
internal fun createNativeMiuixFolmeExpansion(viewport: View): NativeMiuiExpansion? = runCatching {
    val loader = viewport.context.classLoader
    val folmeClass = Class.forName("miuix.animation.Folme", false, loader)
    val viewPropertyClass = Class.forName("miuix.animation.property.ViewProperty", false, loader)
    val height = viewPropertyClass.getField("HEIGHT").get(null)
    val alpha = viewPropertyClass.getField("ALPHA").get(null)
    val views = arrayOf(viewport)
    val folme = folmeClass.getMethod("useAt", views.javaClass).invoke(null, views as Any)
    val state = folme.javaClass.getMethod("state").invoke(folme)
    val ease = state.javaClass.getMethod(
        "setEase",
        Int::class.javaPrimitiveType,
        FloatArray::class.java,
    )
    ease.invoke(state, -2, floatArrayOf(1f, 0.2f))
    val toMethod = state.javaClass.getMethod("to", Array<Any>::class.java)
    val setToMethod = state.javaClass.getMethod("setTo", Array<Any>::class.java)
    val cancelMethod = state.javaClass.getMethod("cancel")
    Log.i(
        TAG,
        "Native MIUIX Folme IStateStyle installed: " +
            "state=${state.javaClass.name} height=${height != null} alpha=${alpha != null}",
    )
    NativeMiuiExpansion(
        animate = { toHeight, expanded ->
            runCatching {
                toMethod.invoke(
                    state,
                    arrayOf(
                        height,
                        toHeight,
                        alpha,
                        if (expanded) 1f else 0f,
                    ) as Any,
                )
                true
            }.onFailure { Log.w(TAG, "Native Folme state.to failed", it) }
                .getOrDefault(false)
        },
        cancel = {
            runCatching { cancelMethod.invoke(state) }
                .onFailure { Log.w(TAG, "Native Folme cancel failed", it) }
        },
        snap = { toHeight, expanded ->
            runCatching {
                setToMethod.invoke(
                    state,
                    arrayOf(
                        height,
                        toHeight,
                        alpha,
                        if (expanded) 1f else 0f,
                    ) as Any,
                )
                true
            }.onFailure { Log.w(TAG, "Native Folme snap failed", it) }
                .getOrDefault(false)
        },
    )
}.onFailure { Log.w(TAG, "Native MIUIX Folme animation unavailable", it) }
    .getOrNull()
