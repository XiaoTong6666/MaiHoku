package io.github.xiaotong6666.maihoku.features.wifi.host.miui

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.host.common.HostSpinnerRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.dp
import io.github.xiaotong6666.maihoku.features.wifi.host.common.findTextDescendant
import io.github.xiaotong6666.maihoku.features.wifi.host.common.label
import io.github.xiaotong6666.maihoku.features.wifi.host.common.s
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

/** Inflate the real Xiaomi preference switch with its native drawable and spring. */
internal fun inflateNativeMiuixSwitch(context: Context, pkg: String): CompoundButton? {
    for (name in listOf(
        "miuix_preference_widget_switch",
        "miuix_preference_widget_switch_compat",
    )) {
        val id = context.resources.getIdentifier(name, "layout", pkg)
        if (id == 0) continue
        val widget = runCatching {
            LayoutInflater.from(context).inflate(id, null, false) as? CompoundButton
        }.onFailure { Log.w(TAG, "MIUIX switch template $name failed", it) }.getOrNull()
        if (widget != null) {
            Log.i(
                TAG,
                "MIUIX native sliding switch inflated from $name: " +
                    widget.javaClass.name,
            )
            return widget
        }
    }
    return null
}

internal fun updateMiuiEditLabel(input: EditText, label: String): Boolean {
    val matching = input.javaClass.methods.firstOrNull { method ->
        method.parameterCount == 1 && method.name in setOf("setLabel", "setLabelText") &&
            method.parameterTypes[0] in listOf(CharSequence::class.java, String::class.java)
    } ?: return false
    return runCatching {
        matching.invoke(input, label)
        true
    }
        .onFailure { Log.w(TAG, "Could not replace MIUIX StateEditText label", it) }
        .getOrDefault(false)
}

/** Xiaomi Settings uses this same color for the native IP Settings press state. */
internal fun nativeMiuiHighlightColor(context: Context, pkg: String): Int {
    val id = context.resources.getIdentifier("bg_spinner_parent", "color", pkg)
    return if (id != 0) context.getColor(id) else 0
}

/**
 * Manual expanding header has no popup to dismiss, so implement the exact
 * MiuiUtils.setSpinnerDisplayLocation DOWN/UP pair on its native container.
 * Unlike Folme tint/Android ripple, bg_spinner_parent appears on ACTION_DOWN.
 */
internal fun installMiuiTouchFeedback(row: View, highlight: Int): (MotionEvent) -> Unit {
    val folme = runCatching {
        val loader = row.context.classLoader
        val clazz = Class.forName("miuix.animation.Folme", false, loader)
        val configClass = Class.forName("miuix.animation.base.AnimConfig", false, loader)
        val configArray = java.lang.reflect.Array.newInstance(configClass, 0)
        val views = arrayOf(row)
        val owner = clazz.getMethod("useAt", views.javaClass).invoke(null, views as Any)
        val touch = owner.javaClass.getMethod("touch").invoke(owner)
        val typesClass = Class.forName("miuix.animation.ITouchStyle\$TouchType", false, loader)
        val emptyTypes = java.lang.reflect.Array.newInstance(typesClass, 0)
        touch.javaClass.getMethod(
            "setScale",
            Float::class.javaPrimitiveType,
            emptyTypes.javaClass,
        ).invoke(touch, 1f, emptyTypes)
        val down = touch.javaClass.getMethod("touchDown", configArray.javaClass)
        val up = touch.javaClass.getMethod("touchUp", configArray.javaClass)
        Triple(touch, down to up, configArray)
    }.onFailure { Log.w(TAG, "MIUIX header Folme methods unavailable", it) }.getOrNull()
    return { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                folme?.let { (touch, methods, args) ->
                    runCatching { methods.first.invoke(touch, args) }
                        .onFailure { Log.w(TAG, "MIUIX header touchDown failed", it) }
                }
                row.setBackgroundColor(highlight)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                folme?.let { (touch, methods, args) ->
                    runCatching { methods.second.invoke(touch, args) }
                        .onFailure { Log.w(TAG, "MIUIX header touchUp failed", it) }
                }
                row.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }
}

/** Exactly Xiaomi's MiuiUtils.setSpinnerDisplayLocation(), not a fake ripple. */
private fun installNativeMiuiSpinnerPress(
    context: Context,
    pkg: String,
    spinner: Spinner,
): Boolean = runCatching {
    val utilsClass = Class.forName(
        "com.android.settings.MiuiUtils",
        false,
        context.classLoader,
    )
    val instance = utilsClass.getMethod("getInstance").invoke(null)
    val native = utilsClass.methods.firstOrNull { method ->
        method.name == "setSpinnerDisplayLocation" && method.parameterCount == 2 &&
            method.parameterTypes[0].isInstance(spinner) &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType
    } ?: return@runCatching false
    val highlight = nativeMiuiHighlightColor(context, pkg)
    native.invoke(instance, spinner, highlight)
    true
}.onFailure { Log.w(TAG, "Stock MiuiUtils spinner press unavailable", it) }
    .getOrDefault(false)

internal fun miuiSpinner(
    context: Context,
    pkg: String,
    parent: LinearLayout,
    labelId: Int,
    choices: Array<String>,
    row: HostSpinnerRow,
): Spinner {
    val field = row.row
    findTextDescendant(field)?.text = s(context, labelId)
    val item = context.resources.getIdentifier("miuix_appcompat_simple_spinner_layout_integrated", "layout", pkg)
        .takeIf { it != 0 } ?: android.R.layout.simple_spinner_item
    val dropdown = context.resources.getIdentifier("miuix_appcompat_simple_spinner_dropdown_item", "layout", pkg)
        .takeIf { it != 0 } ?: android.R.layout.simple_spinner_dropdown_item
    val adapter = object : ArrayAdapter<String>(context, item, android.R.id.text1, choices) {
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val nativeItem = super.getDropDownView(position, convertView, parent)
            val checked = nativeItem as? android.widget.CheckedTextView
            val mark = checked?.checkMarkDrawable
            // MIUIX CheckedTextView measures the text against checkmark width
            // but draws the glyph immediately where that text region ends.
            // Give the native glyph a 12dp leading inset inside its drawable:
            // the measured reserved area grows, with a real gap before the tick.
            if (checked != null && mark != null && mark !is InsetDrawable) {
                checked.setCheckMarkDrawable(InsetDrawable(mark, dp(context, 12), 0, 0, 0))
            }
            return nativeItem
        }
    }
    adapter.setDropDownViewResource(dropdown)
    row.spinner.adapter = adapter
    field.visibility = View.VISIBLE
    parent.addView(field, field.layoutParams ?: LinearLayout.LayoutParams(-1, -2))
    // The native method binds to spinner.parent, so attach the row first.
    installNativeMiuiSpinnerPress(context, pkg, row.spinner)
    return row.spinner
}
