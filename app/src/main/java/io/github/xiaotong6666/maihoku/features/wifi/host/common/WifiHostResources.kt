package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal data class HostSpinnerRow(val row: ViewGroup, val spinner: Spinner)
internal data class HostEditRow(val row: ViewGroup, val input: EditText)

/** A detached Settings XML template: system-owned styles are resolved in its own theme. */
internal fun hostWifiTemplate(
    context: Context,
    pkg: String,
    layoutName: String = "wifi_network_config",
): View? = runCatching {
    val layoutId = context.resources.getIdentifier(layoutName, "layout", pkg)
    if (layoutId == 0) return@runCatching null
    LayoutInflater.from(context).inflate(layoutId, null, false)
}.onFailure { Log.w(TAG, "Unable to inflate Settings Wi-Fi style template", it) }.getOrNull()

internal fun takeSpinnerRow(root: View, context: Context, pkg: String, name: String): HostSpinnerRow? {
    val spinner = hostView(root, context, pkg, name) as? Spinner ?: return null
    val row = spinner.parent as? ViewGroup ?: return null
    detach(row)
    clearIds(row)
    return HostSpinnerRow(row, spinner)
}

internal fun takeEditRow(root: View, context: Context, pkg: String, name: String): HostEditRow? {
    val input = hostView(root, context, pkg, name) as? EditText ?: return null
    val row = input.parent as? ViewGroup ?: return null
    // Some Settings layouts place the input inside a horizontal field wrapper.
    // Take the actual wifi_item containing the label as well as the edit control.
    val field = if (findTextDescendant(row) == null) row.parent as? ViewGroup ?: row else row
    detach(field)
    clearIds(field)
    field.visibility = View.VISIBLE
    return HostEditRow(field, input)
}

internal fun detach(view: View) {
    (view.parent as? ViewGroup)?.removeView(view)
}

internal fun clearIds(view: View) {
    view.id = View.NO_ID
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) clearIds(view.getChildAt(i))
    }
}

internal fun fieldRow(
    context: Context,
    hostPackage: String,
    parent: LinearLayout,
    labelId: Int,
    content: View,
) {
    val row = nativeRow(context, hostPackage)
    row.addView(label(context, hostPackage, labelId), LinearLayout.LayoutParams(-2, -2))
    row.addView(content, LinearLayout.LayoutParams(-1, -2))
    parent.addView(row, LinearLayout.LayoutParams(-1, -2))
}

internal fun nativeRow(context: Context, hostPackage: String): LinearLayout = LinearLayout(context, null, 0, hostStyle(context, hostPackage, "wifi_item")).apply {
    orientation = LinearLayout.VERTICAL
}

internal fun label(context: Context, hostPackage: String, id: Int): TextView = TextView(context, null, 0, hostStyle(context, hostPackage, "wifi_item_label")).apply {
    text = s(context, id)
}

/** The dialog is not attached to a window inside the constructor hook: isShown() is false then. */
internal fun isVisibleInForm(child: View, formRoot: View): Boolean {
    var current: View? = child
    while (current != null) {
        if (current.visibility != View.VISIBLE) return false
        if (current === formRoot) return true
        current = current.parent as? View
    }
    return false
}

internal fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

internal fun hideIme(context: Context, view: View) {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}

internal fun findTextDescendant(view: ViewGroup): TextView? {
    for (i in 0 until view.childCount) {
        val child = view.getChildAt(i)
        if (child is TextView) return child
        if (child is ViewGroup) findTextDescendant(child)?.let { return it }
    }
    return null
}

internal fun findLastTextDescendant(view: ViewGroup): TextView? {
    for (i in view.childCount - 1 downTo 0) {
        val child = view.getChildAt(i)
        if (child is ViewGroup) findLastTextDescendant(child)?.let { return it }
        if (child is TextView) return child
    }
    return null
}

internal fun hostStyle(context: Context, pkg: String, name: String): Int = context.resources.getIdentifier(name, "style", pkg)

internal fun hostView(view: View, context: Context, pkg: String, name: String): View? = context.resources.getIdentifier(name, "id", pkg).takeIf { it != 0 }?.let(view::findViewById)

internal fun s(context: Context, id: Int, vararg args: Any) = ModuleResources.text(context, id, *args)
