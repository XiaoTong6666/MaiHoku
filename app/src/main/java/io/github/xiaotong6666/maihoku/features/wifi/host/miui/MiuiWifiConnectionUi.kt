package io.github.xiaotong6666.maihoku.features.wifi.host.miui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiOriginalSettings
import io.github.xiaotong6666.maihoku.features.wifi.host.common.Controls
import io.github.xiaotong6666.maihoku.features.wifi.host.common.WifiConnectionState
import io.github.xiaotong6666.maihoku.features.wifi.host.common.clearIds
import io.github.xiaotong6666.maihoku.features.wifi.host.common.detach
import io.github.xiaotong6666.maihoku.features.wifi.host.common.dp
import io.github.xiaotong6666.maihoku.features.wifi.host.common.findTextDescendant
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hideIme
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostStyle
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostView
import io.github.xiaotong6666.maihoku.features.wifi.host.common.hostWifiTemplate
import io.github.xiaotong6666.maihoku.features.wifi.host.common.isVisibleInForm
import io.github.xiaotong6666.maihoku.features.wifi.host.common.label
import io.github.xiaotong6666.maihoku.features.wifi.host.common.s
import io.github.xiaotong6666.maihoku.features.wifi.host.common.takeEditRow
import io.github.xiaotong6666.maihoku.features.wifi.host.common.takeSpinnerRow
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityMode
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityPolicy
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityPolicyStore
import io.github.xiaotong6666.maihoku.hook.ModuleResources
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

/**
 * Uses Xiaomi's actual wifi_add_network_layout and MIUIX controls.
 * Never detaches a live native widget, so the original security, proxy, password,
 * and privacy controls continue to work exactly as implemented by Xiaomi.
 */
internal fun injectMiui(
    owner: Any,
    view: View,
    pkg: String,
    surface: MiuiSurface,
    entry: Any? = null,
) {
    if (WifiConnectionState.contains(owner)) return
    val context = view.context
    val template = hostWifiTemplate(context, pkg, "wifi_add_network_layout") ?: run {
        Log.w(TAG, "MIUI manual-add layout unavailable; leaving host page unchanged")
        return
    }
    val requiredTemplateIds = listOf(
        "wifi_advanced_fields",
        "privacy_settings",
        "hidden_settings",
        "hidden_gbk_settings",
        "domain",
        "l_password",
    )
    if (requiredTemplateIds.any { hostView(template, context, pkg, it) == null }) {
        Log.w(TAG, "MIUI native Wi-Fi template structure changed; no UI inserted")
        return
    }
    val headerTemplate = hostWifiTemplate(context, pkg, "wifi_add_network_layout") ?: return
    // Keep the original Wi-Fi row typography, but replace its right-hand
    // double-chevron with Xiaomi's genuine spring-animated SlidingSwitch.
    // This is the same widget inflated by miuix_preference_widget_switch.
    val header = takeSpinnerRow(headerTemplate, context, pkg, "hidden_settings") ?: return
    val headerRow = header.row
    val headerLabel = findTextDescendant(headerRow) ?: return
    val hostSwitch = inflateNativeMiuixSwitch(context, pkg) ?: run {
        Log.w(TAG, "MIUIX sliding switch unavailable; native Wi-Fi layout left unchanged")
        return
    }
    (header.spinner.parent as? ViewGroup)?.removeView(header.spinner)
    val titleRow = headerRow as? LinearLayout ?: return
    // wifi_spinner_title is defined with a 0dp width. It relied on the
    // original Spinner to allocate weights and, after replacing that widget,
    // the switch appeared immediately after the text at x342 instead of
    // the native right edge. Reassign the remaining width to the title.
    val labelParams = (headerLabel.layoutParams as? LinearLayout.LayoutParams)
        ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    labelParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
    labelParams.weight = 0f
    headerLabel.layoutParams = labelParams
    // Xiaomi's wifi_spinner_layout declares an explicit weightSum=3 for the
    // original title+Spinner; preserve its padding but normalize the weight
    // sum for the new title + spacer + SlidingSwitch composition.
    titleRow.weightSum = 1f
    titleRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    titleRow.addView(
        hostSwitch,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_VERTICAL },
    )
    val fields = hostView(template, context, pkg, "wifi_advanced_fields") as? LinearLayout ?: return
    // These are three different *actual* MIUIX spinner rows from Xiaomi's XML.
    val securityRow = takeSpinnerRow(template, context, pkg, "privacy_settings") ?: return
    val pmfRow = takeSpinnerRow(template, context, pkg, "hidden_settings") ?: return
    val h2eRow = takeSpinnerRow(template, context, pkg, "hidden_gbk_settings") ?: return
    // StateEditText is the native HyperOS text input, not a recreated Android EditText.
    val bssidRow = takeEditRow(template, context, pkg, "domain") ?: return
    val passwordRow = takeEditRow(template, context, pkg, "l_password") ?: return
    // MIUI manually adds networks through a SpringBackLayout; its stock advanced
    // fields start GONE and can be controlled by Xiaomi's own visibility logic.
    // Anchor after the *visible* Hidden Network row, not the hidden advanced group.
    val anchor = when (surface) {
        MiuiSurface.MANUAL_ADD -> "hidden_settings_fields"
        MiuiSurface.WIFI_DIALOG, MiuiSurface.SAVED_EDIT -> "wifi_advanced_fields"
    }
    val target = hostView(view, context, pkg, anchor) ?: run {
        Log.w(TAG, "MIUI injection: required anchor $anchor is missing for $surface")
        return
    }
    val parent = target.parent as? LinearLayout ?: run {
        Log.w(TAG, "MIUI injection: hidden_settings_fields parent is not LinearLayout")
        return
    }
    if (hostView(view, context, pkg, "ssid") !is TextView) {
        Log.w(TAG, "MIUI injection: host SSID view not found")
        return
    }

    // A real Settings `wifi_spinner_layout` row matches the stock Wi-Fi options.
    // Keep the native spinner only as a themed double-chevron on the right;
    // opening this section never invokes its dropdown menu.
    detach(fields)
    clearIds(fields)
    fields.removeAllViews()
    fields.visibility = View.GONE
    headerLabel.text = s(context, R.string.wifi_advanced_heading)
    headerLabel.isEnabled = true
    headerRow.visibility = View.VISIBLE
    headerRow.isClickable = true
    headerRow.isFocusable = true
    headerRow.isEnabled = true
    headerRow.contentDescription = s(context, R.string.wifi_advanced_heading)
    headerLabel.maxLines = 1
    headerLabel.ellipsize = TextUtils.TruncateAt.END
    hostSwitch.isChecked = false
    // Keep the advanced panel expansion as *UI state* only, not as a change
    // to the underlying Wi-Fi configuration. Scope the setting to the
    // network's SSID, so editing another AP does not inherit this toggle.
    val displaySsid = runCatching {
        entry?.javaClass?.getMethod("getSsid")?.invoke(entry) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val expansionKey = "expanded." + WifiSecurityPolicy.hashIdentity("${surface.name}\u0000" + (displaySsid ?: "<unspecified>"))
    val expansionPrefs = context.getSharedPreferences(
        "maihoku_wifi_advanced_ui",
        Context.MODE_PRIVATE,
    )
    val restoreExpansion = expansionPrefs.getBoolean(expansionKey, false)
    hostSwitch.isFocusable = true
    hostSwitch.isClickable = true
    hostSwitch.contentDescription = s(context, R.string.wifi_advanced_heading)
    // MIUIX Folme owns the touch tint. Do not install the Android ripple,
    // which was only visible on release on some HyperOS Settings builds.
    headerRow.background = null
    // Correct labels from the actual native label, not from the checked state
    // of the old CheckBox or from a hardcoded theme color.
    val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    section.addView(
        headerRow,
        headerRow.layoutParams ?: LinearLayout.LayoutParams(-1, -2),
    )
    // KernelSU Monet-like AnimatedVisibility: animate a *clipping viewport*,
    // never repeatedly remeasure MIUIX Spinners as the height changes. The
    // full-size content stays laid out at a stable height inside this frame.
    val revealViewport = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
    }
    fields.visibility = View.VISIBLE
    revealViewport.addView(
        fields,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    section.addView(revealViewport, LinearLayout.LayoutParams(-1, 0))
    val securityOptions = ModuleResources.stringArray(context, R.array.wifi_security_modes)
    securityOptions[0] = s(context, R.string.wifi_keep_system_security)
    val prior = WifiOriginalSettings.fromEntry(entry).savedConfig?.let {
        WifiSecurityPolicyStore.get(context, it, allowLegacyMigration = true)
    } ?: WifiSecurityMode.SYSTEM
    val security = miuiSpinner(
        context,
        pkg,
        fields,
        R.string.wifi_security_mode,
        securityOptions,
        securityRow,
    ).apply {
        setSelection(WifiSecurityMode.entries.indexOf(prior))
    }
    val pmfOptions = ModuleResources.stringArray(context, R.array.wifi_pmf_modes)
    pmfOptions[0] = s(context, R.string.wifi_keep_system_pmf)
    val pmf = miuiSpinner(context, pkg, fields, R.string.wifi_pmf, pmfOptions, pmfRow)
    val bssid = bssidRow.input.apply {
        text = null
        hint = s(context, R.string.wifi_bssid_hint)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_FORCE_ASCII
        isFocusable = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = true
    }
    // StateEditText has a Xiaomi-specific *floating label* independent of hint.
    // Reset it as well, otherwise the cloned EAP `domain` control still says 域名.
    updateMiuiEditLabel(bssid, s(context, R.string.wifi_bssid))
    findTextDescendant(bssidRow.row)?.takeIf { it !== bssid }
        ?.text = s(context, R.string.wifi_bssid)
    bssidRow.row.visibility = View.VISIBLE
    // MIUIX StateEditText already has its own floating label/hint, so do not attach
    // an AOSP TextView that would use a different TextAppearance and spacing.
    fields.addView(
        bssidRow.row,
        bssidRow.row.layoutParams ?: LinearLayout.LayoutParams(-1, -2),
    )
    val h2eOptions = ModuleResources.stringArray(context, R.array.wifi_h2e_modes)
    h2eOptions[0] = s(context, R.string.wifi_keep_system_h2e)
    val h2e = miuiSpinner(context, pkg, fields, R.string.wifi_sae_h2e_only, h2eOptions, h2eRow)
    val password = passwordRow.input.apply {
        text = null
        hint = s(context, R.string.wifi_password_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isFocusableInTouchMode = true
        showSoftInputOnFocus = true
    }
    passwordRow.row.visibility = View.GONE
    fields.addView(
        passwordRow.row,
        passwordRow.row.layoutParams ?: LinearLayout.LayoutParams(-1, -2),
    )
    security.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
            val mode = WifiSecurityMode.entries.getOrNull(position)
            val previousVisibility = passwordRow.row.visibility
            passwordRow.row.visibility = if (mode in setOf(
                    WifiSecurityMode.WPA2,
                    WifiSecurityMode.WPA3,
                    WifiSecurityMode.TRANSITION,
                ) &&
                hostView(view, context, pkg, "password")
                    ?.let { isVisibleInForm(it, view) } != true
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (passwordRow.row.visibility != previousVisibility) {
                // Only genuinely dynamic form changes need a new content height.
                // The expansion animation itself must not remeasure Spinners.
                fields.post {
                    if (revealViewport.isAttachedToWindow && revealViewport.height > 0) {
                        val width = revealViewport.width.coerceAtLeast(1)
                        fields.layoutParams = fields.layoutParams.apply {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        fields.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        val natural = fields.measuredHeight
                        fields.layoutParams = fields.layoutParams.apply { height = natural }
                        revealViewport.layoutParams = revealViewport.layoutParams.apply {
                            height = natural
                        }
                    }
                }
            }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    // Match the unobtrusive description beneath MIUIX Settings preferences:
    // native 14sp secondary text, theme-resolved text color, and the same
    // 28dp horizontal inset as Xiaomi's preference item summaries.  Do not
    // reuse wifi_item_label, which is a prominent primary field label.
    val footerStyle = hostStyle(
        context,
        pkg,
        "Miuix.AppCompat.TextAppearance.List.Secondary.Preference",
    )
        .takeIf { it != 0 } ?: hostStyle(
        context,
        pkg,
        "Miuix.AppCompat.TextAppearance.List.Secondary",
    )
    val footerStart = context.resources.getIdentifier("miuix_preference_item_padding_start", "dimen", pkg)
        .takeIf { it != 0 }?.let(context.resources::getDimensionPixelSize)
        ?: dp(context, 28)
    val footerEnd = context.resources.getIdentifier("miuix_preference_item_padding_end", "dimen", pkg)
        .takeIf { it != 0 }?.let(context.resources::getDimensionPixelSize)
        ?: dp(context, 28)
    val helpText = TextView(context).apply {
        text = s(context, R.string.wifi_advanced_help)
        if (footerStyle != 0) setTextAppearance(footerStyle)
        setPaddingRelative(footerStart, dp(context, 12), footerEnd, dp(context, 8))
    }
    fields.addView(helpText, LinearLayout.LayoutParams(-1, -2))
    val nativeHighlight = nativeMiuiHighlightColor(context, pkg)
    val onHeaderTouch = installMiuiTouchFeedback(headerRow, nativeHighlight)
    revealViewport.alpha = 0f
    var nativeFolme: NativeMiuiExpansion? = null
    var attemptedNativeFolme = false
    var expandedTarget = false
    var animationGeneration = 0L
    var runningAnimation: ValueAnimator? = null
    val expandEasing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    // The children of revealViewport always have their natural fixed height.
    // Animating only the viewport's clip bounds prevents native MIUIX Spinner
    // selection labels from bouncing as their parent is remeasured each frame.
    val toggleHeader: (Boolean) -> Unit = { expand ->
        expandedTarget = expand
        val generation = ++animationGeneration
        val currentHeight = revealViewport.height
        runningAnimation?.cancel()
        runningAnimation = null
        // A previous Folme spring may still be writing ViewProperty.HEIGHT
        // after a subsequent instantaneous layout update. Cancel it *before*
        // measuring or snapping the new state, including the from==to path.
        nativeFolme?.cancel?.invoke()
        val contentWidth = (section.width.takeIf { it > 0 } ?: parent.width)
            .coerceAtLeast(1)
        fields.layoutParams = fields.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        fields.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = fields.measuredHeight.coerceAtLeast(1)
        // Lock the *child* layout during the entire transition. Only the
        // outer viewport height and alpha may change on an animation frame.
        fields.layoutParams = fields.layoutParams.apply { height = fullHeight }
        val from = currentHeight.coerceIn(0, fullHeight)
        val to = if (expand) fullHeight else 0
        // The host's StretchableWidgetPreference uses Folme with height +
        // alpha and spring ease(-2, 1f, .2f). Prefer that actual engine to
        // our fixed-duration ValueAnimator; retain the latter only as a
        // defensive fallback for older Xiaomi builds without these APIs.
        if (!attemptedNativeFolme) {
            nativeFolme = createNativeMiuixFolmeExpansion(revealViewport)
            attemptedNativeFolme = true
        }
        if (from == to || !ValueAnimator.areAnimatorsEnabled()) {
            revealViewport.layoutParams = revealViewport.layoutParams.apply {
                height = to
            }
            fields.layoutParams = fields.layoutParams.apply { height = fullHeight }
            revealViewport.alpha = if (expand) 1f else 0f
            nativeFolme?.snap?.invoke(to, expand)
        } else if (nativeFolme?.animate?.invoke(to, expand) == true) {
            // Native ViewProperty.HEIGHT updates only the outer viewport, not
            // the measured positions/labels of the MIUIX Spinner children.
            // The verification below is generation-gated, so an old
            // animation completion cannot overwrite a newer switch state.
        } else {
            val animation = ValueAnimator.ofInt(from, to).apply {
                duration = 280L
                interpolator = expandEasing
            }
            runningAnimation = animation
            revealViewport.layoutParams = revealViewport.layoutParams.apply { height = from }
            revealViewport.alpha = (from.toFloat() / fullHeight).coerceIn(0f, 1f)
            animation.addUpdateListener { animator ->
                if (runningAnimation !== animation) return@addUpdateListener
                val height = animator.animatedValue as Int
                revealViewport.layoutParams = revealViewport.layoutParams.apply {
                    this.height = height
                }
                revealViewport.alpha = (height.toFloat() / fullHeight).coerceIn(0f, 1f)
            }
            animation.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animator: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animator: Animator) {
                    if (cancelled || runningAnimation !== animation) return
                    runningAnimation = null
                    // Keep the exact measured final height; reverting to
                    // WRAP_CONTENT changed 727px to 726px and made MIUIX
                    // selected-value TextViews visibly twitch at animation end.
                    revealViewport.layoutParams = revealViewport.layoutParams.apply {
                        height = to
                    }
                    fields.layoutParams = fields.layoutParams.apply { height = fullHeight }
                    revealViewport.alpha = 1f
                }
            })
            animation.start()
        }
        // A native spring takes a variable amount of time and may be
        // interrupted by a fast reversal. At settle, the *actual switch*
        // controls visibility: never trust an old animation's captured target.
        revealViewport.postDelayed({
            if (generation != animationGeneration || !revealViewport.isAttachedToWindow) {
                return@postDelayed
            }
            val checked = hostSwitch.isChecked
            val expected = if (checked) {
                fields.layoutParams.height.takeIf { it > 0 } ?: fullHeight
            } else {
                0
            }
            val actual = revealViewport.height
            val alphaWrong = if (checked) {
                revealViewport.alpha < 0.98f
            } else {
                revealViewport.alpha > 0.02f
            }
            if (kotlin.math.abs(actual - expected) > 1 || alphaWrong) {
                Log.w(
                    TAG,
                    "MIUIX expansion mismatch corrected: checked=$checked " +
                        "actual=$actual expected=$expected alpha=${revealViewport.alpha} " +
                        "generation=$generation",
                )
                runningAnimation?.cancel()
                runningAnimation = null
                nativeFolme?.cancel?.invoke()
                nativeFolme?.snap?.invoke(expected, checked)
                revealViewport.layoutParams = revealViewport.layoutParams.apply {
                    height = expected
                }
                revealViewport.alpha = if (checked) 1f else 0f
            }
        }, 1100L)
        if (expand) hideIme(context, view)
    }
    // The real SlidingSwitch dispatches its own drag/tap events and animates
    // the circular thumb with Xiaomi's SpringAnimation (no homemade indicator).
    hostSwitch.setOnCheckedChangeListener { _, enabled ->
        expansionPrefs.edit().putBoolean(expansionKey, enabled).apply()
        if (enabled != expandedTarget) toggleHeader(enabled)
    }
    // Run after attachment: this drives the same native switch and Folme
    // transition as a user tap without modifying network security options.
    if (restoreExpansion) {
        hostSwitch.post {
            if (hostSwitch.isAttachedToWindow) hostSwitch.isChecked = true
        }
    }
    hostSwitch.setOnTouchListener { _, event ->
        // Use the same native bright background highlight for the complete row.
        // Return false so SlidingSwitchHelper keeps its spring and haptics.
        onHeaderTouch(event)
        false
    }
    headerRow.setOnClickListener {
        // Forward a *tap* to SlidingSwitch's public touch implementation, instead
        // of setChecked(): programmatic setChecked() skips its thumb spring.
        val now = SystemClock.uptimeMillis()
        val x = hostSwitch.width / 2f
        val y = hostSwitch.height / 2f
        if (hostSwitch.width > 0 && hostSwitch.height > 0) {
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, x, y, 0)
            try {
                hostSwitch.onTouchEvent(down)
                hostSwitch.onTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        } else {
            hostSwitch.performClick()
        }
    }
    headerRow.setOnTouchListener { _, event ->
        onHeaderTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) headerRow.performClick()
        true
    }
    parent.addView(section, parent.indexOfChild(target) + 1, LinearLayout.LayoutParams(-1, -2))
    val original = WifiOriginalSettings.fromEntry(entry)
    WifiConnectionState.put(
        owner,
        Controls(
            context, null, security, pmf, bssid, h2e,
            password, initialSecurity = security.selectedItemPosition,
            initialSecurityTypes = original.securityTypes,
        ),
    )
    Log.i(TAG, "Native MIUIX Wi-Fi advanced UI inserted: surface=$surface")
}
