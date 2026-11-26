package io.github.xiaotong6666.maihoku.features.wifi.host.aosp

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiOriginalSettings
import io.github.xiaotong6666.maihoku.features.wifi.host.common.Controls
import io.github.xiaotong6666.maihoku.features.wifi.host.common.HostSpinnerRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.WifiConnectionState
import io.github.xiaotong6666.maihoku.features.wifi.host.common.clearIds
import io.github.xiaotong6666.maihoku.features.wifi.host.common.detach
import io.github.xiaotong6666.maihoku.features.wifi.host.common.dp
import io.github.xiaotong6666.maihoku.features.wifi.host.common.fieldRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.findLastTextDescendant
import io.github.xiaotong6666.maihoku.features.wifi.host.common.findTextDescendant
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hideIme
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostStyle
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostView
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostWifiTemplate
import io.github.xiaotong6666.maihoku.features.wifi.host.common.isVisibleInForm
import io.github.xiaotong6666.maihoku.features.wifi.host.common.label
import io.github.xiaotong6666.maihoku.features.wifi.host.common.nativeRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.s
import io.github.xiaotong6666.maihoku.features.wifi.host.common.takeEditRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.takeSpinnerRow
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityMode
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityPolicyStore
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal fun injectAosp(controller: Any, view: View, mode: Int, entry: Any?, packageName: String) {
    if (WifiConnectionState.contains(controller)) return
    if (mode == 0) return
    val context = view.context
    val original = WifiOriginalSettings.fromEntry(entry)
    val ssid = entry?.javaClass?.methods?.firstOrNull {
        it.name == "getTitle" && it.parameterCount == 0
    }?.invoke(entry) as? String
    val prior = original.savedConfig?.let {
        WifiSecurityPolicyStore.get(context, it, allowLegacyMigration = true)
    } ?: WifiSecurityMode.SYSTEM
    val originalSecurity = WifiOriginalSettings.securityLabelId(original.securityTypes)
    val originalLabel = if (originalSecurity != null) {
        s(context, R.string.wifi_keep_system_security_value, s(context, originalSecurity))
    } else {
        s(context, R.string.wifi_keep_system_security)
    }

    // The stock layout's advanced toggle and fields are sibling nodes in its NestedScrollView.
    // Insert AFTER both, never into security_fields or another container with special visibility rules.
    val legacyToggle = hostView(view, context, packageName, "wifi_advanced_toggle")
    val modernToggle = hostView(view, context, packageName, "advanced_options_layout")
    val nativeToggle = legacyToggle ?: modernToggle ?: return
    val nativeFields = hostView(view, context, packageName, "wifi_advanced_fields") ?: return
    val parent = nativeFields.parent as? LinearLayout ?: return
    if (nativeToggle.parent !== parent || parent.orientation != LinearLayout.VERTICAL) return
    val expressive = legacyToggle == null && modernToggle != null

    // Inflate the host Settings layout with its real android:theme/style attributes,
    // then detach ONLY the elements we need. The live Settings tree is never modified.
    val template = hostWifiTemplate(context, packageName)
    val hostSpinnerRows = if (template != null) {
        listOf(
            takeSpinnerRow(template, context, packageName, "metered_settings"),
            takeSpinnerRow(template, context, packageName, "proxy_settings"),
            takeSpinnerRow(template, context, packageName, "ip_settings"),
        )
    } else {
        emptyList()
    }
    val hostEditRow = if (template != null) {
        takeEditRow(template, context, packageName, "proxy_pac")
    } else {
        null
    }
    val hostPasswordRow = if (template != null) {
        takeEditRow(template, context, packageName, "proxy_hostname")
    } else {
        null
    }
    val hostHelpRow = if (template != null) {
        (hostView(template, context, packageName, "proxy_warning_limited_support") as? ViewGroup)
            ?.also(::detach)?.apply { clearIds(this) }
    } else {
        null
    }
    val hostToggle = if (template != null) {
        hostView(
            template,
            context,
            packageName,
            if (expressive) "advanced_options_layout" else "wifi_advanced_toggle",
        )?.also(::detach)
    } else {
        null
    }
    val hostFields = if (template != null) {
        (hostView(template, context, packageName, "wifi_advanced_fields") as? LinearLayout)
            ?.also(::detach)?.apply {
                removeAllViews()
                visibility = View.GONE
                clearIds(this)
            }
    } else {
        null
    }
    val nativeStyleReady = hostToggle is ViewGroup && hostFields != null &&
        hostSpinnerRows.size == 3 && hostSpinnerRows.all { it != null } &&
        (expressive || hostView(hostToggle, context, packageName, "wifi_advanced_togglebox") is CheckBox)
    if (!nativeStyleReady) {
        Log.w(TAG, "Host layout cannot be cloned completely; retaining safe programmatic fallback")
    }
    val section = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val params = LinearLayout.LayoutParams(-1, -2)
        if (expressive && !nativeStyleReady) {
            (nativeFields.layoutParams as? ViewGroup.MarginLayoutParams)?.let { margin ->
                params.marginStart = margin.marginStart
                params.marginEnd = margin.marginEnd
            }
        } else if (!expressive && !nativeStyleReady) {
            val inset = (8f * context.resources.displayMetrics.density + 0.5f).toInt()
            params.marginStart = inset
            params.marginEnd = inset
        }
        layoutParams = params
    }
    val toggle: CheckBox?
    if (nativeStyleReady && !expressive) {
        val row = requireNotNull(hostToggle as? ViewGroup)
        val check = requireNotNull(
            hostView(
                row,
                context,
                packageName,
                "wifi_advanced_togglebox",
            ) as? CheckBox,
        )
        check.text = s(context, R.string.wifi_advanced_heading)
        check.contentDescription = s(context, R.string.wifi_advanced_heading)
        check.isChecked = false
        check.isClickable = true
        check.isFocusable = true
        clearIds(row)
        row.visibility = View.VISIBLE
        section.addView(row, row.layoutParams)
        toggle = check
    } else if (nativeStyleReady) {
        val row = requireNotNull(hostToggle as? ViewGroup)
        val titleView = findTextDescendant(row)
        titleView?.text = s(context, R.string.wifi_advanced_heading)
        val arrow = hostView(row, context, packageName, "advanced_options_icon") as? ImageView
        clearIds(row)
        row.visibility = View.VISIBLE
        row.isClickable = true
        row.isFocusable = true
        // Exact host padding, TextAppearance, background, icon container, and margins.
        section.addView(row, row.layoutParams)
        toggle = null
        row.setOnClickListener {
            val content = section.getChildAt(1)
            val open = content.visibility != View.VISIBLE
            content.visibility = if (open) View.VISIBLE else View.GONE
            arrow?.rotation = if (open) 180f else 0f
            if (open) hideIme(context, view)
        }
    } else if (expressive) {
        // Android 16 / Lineage 23 / crDroid 16 use a Material3 row rather than a checkbox.
        // Copy the host row's TextAppearance and background, not its listener or state.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val bg = nativeToggle.background?.constantState?.newDrawable(context.resources)?.mutate()
            if (bg != null) background = bg
            setPadding(
                nativeToggle.paddingLeft,
                nativeToggle.paddingTop,
                nativeToggle.paddingRight,
                nativeToggle.paddingBottom,
            )
        }
        val hostTitle = (nativeToggle as? ViewGroup)?.let(::findTextDescendant)
        val titleView = TextView(context).apply {
            text = s(context, R.string.wifi_advanced_heading)
            hostTitle?.let { source ->
                setTextColor(source.textColors)
                textSize = source.textSize / resources.displayMetrics.scaledDensity
                typeface = source.typeface
            }
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        val hostArrow = hostView(view, context, packageName, "advanced_options_icon") as? ImageView
        val arrow = ImageView(context).apply {
            hostArrow?.drawable?.constantState?.newDrawable(context.resources)?.mutate()?.let { setImageDrawable(it) }
            contentDescription = s(context, R.string.wifi_advanced_heading)
        }
        row.addView(
            arrow,
            LinearLayout.LayoutParams(
                hostArrow?.layoutParams?.width?.takeIf { it > 0 } ?: dp(context, 28),
                hostArrow?.layoutParams?.height?.takeIf { it > 0 } ?: dp(context, 40),
            ),
        )
        section.addView(row, LinearLayout.LayoutParams(-1, -2))
        toggle = null
        row.setOnClickListener {
            val open = section.getChildAt(1).visibility != View.VISIBLE
            section.getChildAt(1).visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 180f else 0f
            if (open) hideIme(context, view)
        }
    } else {
        val row = LinearLayout(context, null, 0, hostStyle(context, packageName, "wifi_item")).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                nativeToggle.paddingLeft,
                nativeToggle.paddingTop,
                nativeToggle.paddingRight,
                nativeToggle.paddingBottom,
            )
        }
        val check = CheckBox(context, null, 0, hostStyle(context, packageName, "wifi_advanced_toggle")).apply {
            text = s(context, R.string.wifi_advanced_heading)
            contentDescription = s(context, R.string.wifi_advanced_heading)
            isChecked = false
            isClickable = true
            isFocusable = true
        }
        row.addView(check, LinearLayout.LayoutParams(-1, -2))
        section.addView(row, LinearLayout.LayoutParams(-1, -2))
        toggle = check
    }
    val fields = if (nativeStyleReady) {
        requireNotNull(hostFields)
    } else {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
    }
    if (expressive && !nativeStyleReady) {
        nativeFields.background?.constantState?.newDrawable(context.resources)?.mutate()?.let {
            fields.background = it
        }
    }
    section.addView(fields, if (nativeStyleReady) fields.layoutParams else LinearLayout.LayoutParams(-1, -2))
    val nativeSpinner = (
        hostView(view, context, packageName, "proxy_settings")
            ?: hostView(view, context, packageName, "security")
        ) as? Spinner
    // The first item is a no-op, not a security type. Show the host's actual state.
    val securityOptions = ModuleResources.stringArray(context, R.array.wifi_security_modes)
    securityOptions[0] = originalLabel
    val security = spinner(
        context,
        packageName,
        fields,
        R.string.wifi_security_mode,
        securityOptions,
        nativeSpinner,
        hostSpinnerRows.getOrNull(0),
    )
        .apply { setSelection(WifiSecurityMode.entries.indexOf(prior)) }
    val pmfOptions = ModuleResources.stringArray(context, R.array.wifi_pmf_modes)
    pmfOptions[0] = when (original.pmfRequired) {
        true -> s(context, R.string.wifi_keep_pmf_required)
        false -> s(context, R.string.wifi_keep_pmf_optional)
        null -> s(context, R.string.wifi_keep_system_pmf)
    }
    val pmf = spinner(
        context,
        packageName,
        fields,
        R.string.wifi_pmf,
        pmfOptions,
        nativeSpinner,
        hostSpinnerRows.getOrNull(1),
    )
    val bssid = (hostEditRow?.input ?: EditText(context)).apply {
        hint = original.bssid?.takeIf { it.isNotBlank() }?.let {
            s(context, R.string.wifi_bssid_current, it)
        } ?: s(context, R.string.wifi_bssid_hint)
        setSingleLine(true)
        // Use an ASCII keyboard for MAC addresses without masking the displayed value.
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_FORCE_ASCII
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isCursorVisible = true
        showSoftInputOnFocus = true
    }
    if (nativeStyleReady && hostEditRow != null) {
        val nativeRow = hostEditRow.row
        findTextDescendant(nativeRow)?.text = s(context, R.string.wifi_bssid)
        fields.addView(nativeRow, nativeRow.layoutParams)
    } else {
        fieldRow(context, packageName, fields, R.string.wifi_bssid, bssid)
    }
    val h2eOptions = ModuleResources.stringArray(context, R.array.wifi_h2e_modes)
    h2eOptions[0] = when (original.saeH2eOnly) {
        true -> s(context, R.string.wifi_keep_h2e_enabled)
        false -> s(context, R.string.wifi_keep_h2e_disabled)
        null -> s(context, R.string.wifi_keep_system_h2e)
    }
    val h2e = spinner(
        context,
        packageName,
        fields,
        R.string.wifi_sae_h2e_only,
        h2eOptions,
        nativeSpinner,
        hostSpinnerRows.getOrNull(2),
    )
    val hostHelpText = hostHelpRow?.let(::findLastTextDescendant)
    if (nativeStyleReady && hostHelpRow != null && hostHelpText != null) {
        hostHelpText.text = s(context, R.string.wifi_advanced_help)
        // Settings' warning template has an empty title TextView. It is useful for
        // stock proxy warnings, but would create a large blank gap above our help.
        findTextDescendant(hostHelpRow)?.let { first ->
            if (first !== hostHelpText && first.text.isNullOrBlank()) first.visibility = View.GONE
        }
        hostHelpRow.visibility = View.VISIBLE
        fields.addView(hostHelpRow, hostHelpRow.layoutParams)
    } else {
        fields.addView(
            label(context, packageName, R.string.wifi_advanced_help),
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    // Reuse the stock password field when it exists: two password boxes are confusing.
    // Only expose a manual password when the native dialog has no visible password editor.
    val nativePassword = hostView(view, context, packageName, "password")
    val password = if (nativePassword == null || !isVisibleInForm(nativePassword, view)) {
        (hostPasswordRow?.input ?: EditText(context)).apply {
            hint = s(context, R.string.wifi_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
        }.also { input ->
            if (nativeStyleReady && hostPasswordRow != null) {
                findTextDescendant(hostPasswordRow.row)?.text = s(context, R.string.wifi_password_label)
                hostPasswordRow.row.visibility = View.VISIBLE
                fields.addView(hostPasswordRow.row, hostPasswordRow.row.layoutParams)
            } else {
                fieldRow(context, packageName, fields, R.string.wifi_password_label, input)
            }
        }
    } else {
        null
    }

    toggle?.setOnCheckedChangeListener { _, checked ->
        fields.visibility = if (checked) View.VISIBLE else View.GONE
        if (checked) hideIme(context, view)
    }
    parent.addView(section, parent.indexOfChild(nativeFields) + 1, section.layoutParams)
    WifiConnectionState.put(
        controller,
        Controls(
            context, ssid, security, pmf, bssid, h2e, password,
            initialSecurity = security.selectedItemPosition,
            initialSecurityTypes = original.securityTypes,
        ),
    )
    Log.i(TAG, "Advanced Wi-Fi section added after native advanced fields (existingNetwork=${entry != null})")
}

private fun spinner(
    context: Context,
    hostPackage: String,
    parent: LinearLayout,
    labelId: Int,
    choices: Array<String>,
    nativeSpinner: Spinner?,
    hostRow: HostSpinnerRow? = null,
): Spinner {
    if (hostRow != null) {
        val row = hostRow.row
        val spinner = hostRow.spinner
        findTextDescendant(row)?.text = s(context, labelId)
        // Keep the *inflated* native Spinner, including its themed background, corner
        // radii, minHeight, padding and text style. Override only the choices.
        val themed = spinner.context
        val itemLayout = themed.resources.getIdentifier("settings_expressive_spinner_view", "layout", hostPackage)
            .takeIf { it != 0 } ?: android.R.layout.simple_spinner_item
        val dropLayout = themed.resources.getIdentifier("settings_expressive_spinner_dropdown_view", "layout", hostPackage)
            .takeIf { it != 0 } ?: android.R.layout.simple_spinner_dropdown_item
        // Material3 dropdown root is a LinearLayout containing android.R.id.text1.
        // The four-argument constructor is required or ArrayAdapter crashes Settings.
        val adapter = object : ArrayAdapter<String>(themed, itemLayout, android.R.id.text1, choices) {
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = super.getDropDownView(position, convertView, parent)
                // Material3's native dropdown layout contains a check icon by default.
                // Only the selected entry may show it, as in Settings' own adapters.
                item.findViewById<View>(android.R.id.icon)?.visibility =
                    if (position == spinner.selectedItemPosition) View.VISIBLE else View.INVISIBLE
                return item
            }
        }
        adapter.setDropDownViewResource(dropLayout)
        spinner.adapter = adapter
        row.visibility = View.VISIBLE
        parent.addView(row, row.layoutParams)
        return spinner
    }
    val field = nativeRow(context, hostPackage)
    field.addView(label(context, hostPackage, labelId), LinearLayout.LayoutParams(-2, -2))
    val spinner = Spinner(context).apply {
        // Applying a Settings style as defStyleRes produces an empty widget on some ROMs.
        // Use the working framework Spinner, then copy appearance from an actual native one.
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, choices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        this.adapter = adapter
        if (nativeSpinner != null) {
            nativeSpinner.background?.constantState?.newDrawable(context.resources)?.mutate()?.let {
                background = it
            }
            minimumHeight = nativeSpinner.minimumHeight
        }
        // A host theme can give its own spinner text a different color than android.R.layout.
        // Copy the stock selected text's ColorStateList after both controls attach.
        val syncNativeTextColor: () -> Unit = {
            val nativeText = nativeSpinner?.selectedView as? TextView
            val ownText = selectedView as? TextView
            if (nativeText != null && ownText != null) ownText.setTextColor(nativeText.textColors)
        }
        post { syncNativeTextColor() }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                post { syncNativeTextColor() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    field.addView(spinner, LinearLayout.LayoutParams(-1, -2))
    parent.addView(field, LinearLayout.LayoutParams(-1, -2))
    return spinner
}
