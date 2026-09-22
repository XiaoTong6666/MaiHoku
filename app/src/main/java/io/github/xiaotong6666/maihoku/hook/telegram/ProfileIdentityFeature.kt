package io.github.xiaotong6666.maihoku.hook.telegram

import android.content.Context
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.github.xiaotong6666.maihoku.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.floor
import kotlin.math.min

internal data class ProfileIdentitySymbols(
    val profileClass: Class<*>,
    val createView: Method,
    val onResume: Method,
    val didReceivedNotification: Method,
    val getArguments: Method,
    val getMessagesController: Method,
    val getConnectionsManager: Method,
    val isActionBarCrossfadeEnabled: Method,
    val statusViewArrayFields: List<Field>,
    val statusGetTextPaint: Method,
    val statusGetTextColor: Method,
    val statusGetExactWidth: Method,
    val itemOptions: TelegramItemOptionsSymbols?,
    val messagesControllerGetUser: Method,
    val userClass: Class<*>,
    val userPhotoField: Field,
    val userSelfField: Field,
    val photoDcIdField: Field,
    val currentDatacenterId: Method,
)

internal data class TelegramItemOptionsSymbols(
    val factory: Method,
    val add: Method,
    val addNeedsRedFlag: Boolean,
    val show: Method,
    val addToClipboard: Method,
)

private data class ProfileIdentityUi(
    val label: TextView,
    val nameAnchor: View,
    val statusAnchor: View,
    val profile: Any,
    var id: Long = 0,
    var dc: Int = 0,
)

internal object ProfileIdentityFeature : TelegramFeature<ProfileIdentitySymbols>() {
    override val id: String = "telegram.ui.profile_identity"

    private val instances = Collections.synchronizedMap(WeakHashMap<Any, ProfileIdentityUi>())

    override fun isEnabled(runtime: TelegramRuntime): Boolean = runtime.config.profileIdentityEnabled

    override fun resolve(runtime: TelegramRuntime): ProfileIdentitySymbols {
        val profileClass = Class.forName(
            "org.telegram.ui.ProfileActivity",
            false,
            runtime.classLoader,
        )

        val createView = profileClass.getDeclaredMethod("createView", Context::class.java).apply {
            isAccessible = true
        }
        check(View::class.java.isAssignableFrom(createView.returnType)) {
            "ProfileActivity.createView return type mismatch"
        }

        val onResume = profileClass.getDeclaredMethod("onResume").apply { isAccessible = true }
        val didReceivedNotification = profileClass.getDeclaredMethod(
            "didReceivedNotification",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Array<Any>::class.java,
        ).apply { isAccessible = true }

        val getArguments = profileClass.getMethod("getArguments").apply { isAccessible = true }
        check(getArguments.returnType == Bundle::class.java) {
            "ProfileActivity.getArguments return type mismatch"
        }

        val getMessagesController = profileClass.getMethod("getMessagesController").apply {
            isAccessible = true
        }
        val getConnectionsManager = profileClass.getMethod("getConnectionsManager").apply {
            isAccessible = true
        }
        val isActionBarCrossfadeEnabled = profileClass.getMethod(
            "isActionBarCrossfadeEnabled",
        ).apply { isAccessible = true }
        check(
            isActionBarCrossfadeEnabled.parameterCount == 0 &&
                isActionBarCrossfadeEnabled.returnType == Boolean::class.javaPrimitiveType,
        ) { "ProfileActivity.isActionBarCrossfadeEnabled shape mismatch" }
        val statusClass = Class.forName(
            "org.telegram.ui.ActionBar.r5",
            false,
            runtime.classLoader,
        )
        check(View::class.java.isAssignableFrom(statusClass)) {
            "Profile status text class is not a View"
        }
        val statusArrays = profileClass.declaredFields.filter { field ->
            field.type.isArray && field.type.componentType == statusClass
        }.onEach { it.isAccessible = true }
        check(statusArrays.size == 2) {
            "Expected name/status r5[] fields, found ${statusArrays.size}"
        }

        val statusGetTextPaint = statusClass.getMethod("getTextPaint").apply { isAccessible = true }
        val statusGetTextColor = statusClass.getMethod("getTextColor").apply { isAccessible = true }
        val statusGetExactWidth = statusClass.getMethod("getExactWidth").apply { isAccessible = true }

        val baseFragmentClass = profileClass.superclass
            ?: error("ProfileActivity has no BaseFragment superclass")
        val itemOptions = runCatching {
            resolveItemOptions(runtime, baseFragmentClass)
        }.onFailure { error ->
            android.util.Log.w(
                TelegramRuntime.TAG,
                "$id Telegram item-options unavailable; ID/DC display remains enabled",
                error,
            )
        }.getOrNull()

        val messagesController = Class.forName(
            "org.telegram.messenger.MessagesController",
            false,
            runtime.classLoader,
        )
        val messagesControllerGetUser = messagesController.getDeclaredMethod(
            "getUser",
            java.lang.Long::class.java,
        ).apply { isAccessible = true }

        val userClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$User",
            false,
            runtime.classLoader,
        )
        val userPhoto = userClass.getDeclaredField("photo").apply { isAccessible = true }
        val userSelf = userClass.getDeclaredField("self").apply { isAccessible = true }

        val photoClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$UserProfilePhoto",
            false,
            runtime.classLoader,
        )
        val photoDc = photoClass.getDeclaredField("dc_id").apply { isAccessible = true }
        check(photoDc.type == Int::class.javaPrimitiveType) {
            "UserProfilePhoto.dc_id shape mismatch"
        }

        val connectionsManager = Class.forName(
            "org.telegram.tgnet.ConnectionsManager",
            false,
            runtime.classLoader,
        )
        val currentDatacenterId = connectionsManager.getDeclaredMethod("getCurrentDatacenterId").apply {
            isAccessible = true
        }
        check(currentDatacenterId.returnType == Int::class.javaPrimitiveType) {
            "ConnectionsManager.getCurrentDatacenterId return type mismatch"
        }

        return ProfileIdentitySymbols(
            profileClass = profileClass,
            createView = createView,
            onResume = onResume,
            didReceivedNotification = didReceivedNotification,
            getArguments = getArguments,
            getMessagesController = getMessagesController,
            getConnectionsManager = getConnectionsManager,
            isActionBarCrossfadeEnabled = isActionBarCrossfadeEnabled,
            statusViewArrayFields = statusArrays,
            statusGetTextPaint = statusGetTextPaint,
            statusGetTextColor = statusGetTextColor,
            statusGetExactWidth = statusGetExactWidth,
            itemOptions = itemOptions,
            messagesControllerGetUser = messagesControllerGetUser,
            userClass = userClass,
            userPhotoField = userPhoto,
            userSelfField = userSelf,
            photoDcIdField = photoDc,
            currentDatacenterId = currentDatacenterId,
        )
    }

    private fun resolveItemOptions(
        runtime: TelegramRuntime,
        baseFragmentClass: Class<*>,
    ): TelegramItemOptionsSymbols {
        // This class and its fluent wrappers are renamed/optimized by R8 in production builds.
        // Resolve the popup by behavior instead of source names. In current builds the source
        // add(icon, text, action) wrapper is folded into add(icon, text, action, isRed), and
        // show() is optimized from returning the receiver to void.
        val resolved = runtime.dexKit.useBridge { bridge ->
            val factories = bridge.findMethod {
                matcher {
                    paramTypes(baseFragmentClass.name, View::class.java.name)
                }
            }.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnTypeName == method.declaredClassName
            }

            data class Candidate(
                val factoryData: org.luckypray.dexkit.result.MethodData,
                val addData: org.luckypray.dexkit.result.MethodData,
                val addNeedsRedFlag: Boolean,
                val showData: org.luckypray.dexkit.result.MethodData,
            )

            val candidates = factories.mapNotNull { factoryData ->
                val classData = factoryData.declaredClass ?: return@mapNotNull null

                val directAdds = classData.findMethod {
                    matcher {
                        returnType = classData.name
                        paramTypes(
                            Int::class.javaPrimitiveType!!.name,
                            CharSequence::class.java.name,
                            Runnable::class.java.name,
                        )
                    }
                }

                val foldedAdds = classData.findMethod {
                    matcher {
                        returnType = "void"
                        paramTypes(
                            Int::class.javaPrimitiveType!!.name,
                            CharSequence::class.java.name,
                            Runnable::class.java.name,
                            Boolean::class.javaPrimitiveType!!.name,
                        )
                    }
                }.filter { method ->
                    method.invokes.any { invoked ->
                        invoked.declaredClassName == classData.name &&
                            invoked.returnTypeName == "void" &&
                            invoked.paramTypeNames == listOf(
                                Int::class.javaPrimitiveType!!.name,
                                "android.graphics.drawable.Drawable",
                                CharSequence::class.java.name,
                                Int::class.javaPrimitiveType!!.name,
                                Int::class.javaPrimitiveType!!.name,
                                Runnable::class.java.name,
                            )
                    }
                }

                val addData: org.luckypray.dexkit.result.MethodData
                val addNeedsRedFlag: Boolean
                when {
                    directAdds.size == 1 -> {
                        addData = directAdds.single()
                        addNeedsRedFlag = false
                    }

                    foldedAdds.isNotEmpty() -> {
                        // R8 can retain both add(...) and addIf(...), with the same optimized
                        // signature. The normal add variant is the one reused by a five-argument
                        // conditional wrapper; if that relation is absent, the larger body is the
                        // non-guard wrapper in the supported production layout.
                        val calledByConditionalWrapper = foldedAdds.filter { method ->
                            method.callers.any { caller ->
                                caller.declaredClassName == classData.name &&
                                    caller.paramCount == 5 &&
                                    caller.paramTypeNames.count {
                                        it == Boolean::class.javaPrimitiveType!!.name
                                    } >= 2 &&
                                    caller.paramTypeNames.contains(Runnable::class.java.name)
                            }
                        }
                        addData = when {
                            calledByConditionalWrapper.size == 1 -> calledByConditionalWrapper.single()
                            foldedAdds.size == 1 -> foldedAdds.single()
                            else -> foldedAdds.maxBy { it.opCodes.size }
                        }
                        addNeedsRedFlag = true
                    }

                    else -> return@mapNotNull null
                }

                val shows = classData.findMethod {
                    matcher {
                        paramCount = 0
                    }
                }.filter { method ->
                    if (method.returnTypeName != "void" && method.returnTypeName != classData.name) {
                        return@filter false
                    }
                    val invokedNames = method.invokes.map { it.name }.toSet()
                    "getOverlayContainerView" in invokedNames &&
                        ("showAtLocation" in invokedNames || "showAsDropDown" in invokedNames)
                }
                if (shows.size != 1) return@mapNotNull null

                Candidate(
                    factoryData = factoryData,
                    addData = addData,
                    addNeedsRedFlag = addNeedsRedFlag,
                    showData = shows.single(),
                )
            }

            check(candidates.isNotEmpty()) {
                "Unable to resolve Telegram item-options popup"
            }

            // The normal two-argument factory is ordinarily the most referenced specialization.
            // Either specialization produces the same native popup, but preferring the common
            // one avoids enabling swipe-back behavior when R8 materializes both variants.
            candidates.maxBy { it.factoryData.callers.size }
        }

        val factory = resolved.factoryData.getMethodInstance(runtime.classLoader).apply {
            isAccessible = true
        }
        val itemOptionsClass = factory.returnType
        check(
            Modifier.isStatic(factory.modifiers) &&
                factory.parameterTypes.contentEquals(arrayOf(baseFragmentClass, View::class.java)) &&
                factory.returnType == factory.declaringClass,
        ) { "Telegram item-options factory shape mismatch" }

        val add = resolved.addData.getMethodInstance(runtime.classLoader).apply {
            isAccessible = true
        }
        check(add.declaringClass == itemOptionsClass) {
            "Telegram item-options add method belongs to a different class"
        }

        val show = resolved.showData.getMethodInstance(runtime.classLoader).apply {
            isAccessible = true
        }
        check(show.declaringClass == itemOptionsClass && show.parameterCount == 0) {
            "Telegram item-options show method shape mismatch"
        }

        val androidUtilities = Class.forName(
            "org.telegram.messenger.AndroidUtilities",
            false,
            runtime.classLoader,
        )
        val addToClipboard = androidUtilities.getDeclaredMethod(
            "addToClipboard",
            CharSequence::class.java,
        ).apply { isAccessible = true }
        return TelegramItemOptionsSymbols(
            factory = factory,
            add = add,
            addNeedsRedFlag = resolved.addNeedsRedFlag,
            show = show,
            addToClipboard = addToClipboard,
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: ProfileIdentitySymbols) {
        val hookIds = ArrayList<String>(3)
        try {
            val createId = "$id.create_view"
            runtime.hooks.method(resolution.createView, createId) {
                after {
                    val profile = thisObject ?: return@after
                    runCatching { attach(runtime, resolution, profile) }
                        .onFailure { runtime.logUnsupported("$id.attach", it) }
                }
            }
            hookIds += createId

            val resumeId = "$id.resume"
            runtime.hooks.method(resolution.onResume, resumeId) {
                after {
                    val profile = thisObject ?: return@after
                    runCatching { refresh(resolution, profile) }
                }
            }
            hookIds += resumeId

            val notificationId = "$id.notifications"
            runtime.hooks.method(resolution.didReceivedNotification, notificationId) {
                after {
                    val profile = thisObject ?: return@after
                    if (instances.containsKey(profile)) {
                        runCatching { refresh(resolution, profile) }
                    }
                }
            }
            hookIds += notificationId
        } catch (t: Throwable) {
            hookIds.asReversed().forEach(runtime.hooks::unhook)
            throw t
        }
    }

    private fun attach(
        runtime: TelegramRuntime,
        symbols: ProfileIdentitySymbols,
        profile: Any,
    ) {
        instances.remove(profile)?.label?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val (nameAnchor, statusAnchor) = findProfileAnchors(symbols, profile)
        val parent = statusAnchor.parent as? ViewGroup
            ?: error("Profile status view has no ViewGroup parent")

        val context = statusAnchor.context
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(context, 13.5f))
            gravity = Gravity.START
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(context, 4f).toInt(), dp(context, 2f).toInt(), dp(context, 4f).toInt(), dp(context, 2f).toInt())
            isLongClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val ui = ProfileIdentityUi(
            label = label,
            nameAnchor = nameAnchor,
            statusAnchor = statusAnchor,
            profile = profile,
        )
        label.setOnLongClickListener {
            if (ui.id == 0L && ui.dc == 0) return@setOnLongClickListener false
            if (symbols.itemOptions == null) return@setOnLongClickListener false
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            runCatching { showIdentityMenu(runtime, symbols, ui) }
                .onFailure { error ->
                    android.util.Log.w(
                        TelegramRuntime.TAG,
                        "$id failed to show identity popup",
                        error,
                    )
                }
            true
        }

        val statusLp = statusAnchor.layoutParams as? FrameLayout.LayoutParams
            ?: error("Profile status view does not use FrameLayout.LayoutParams")
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            statusLp.gravity,
        ).apply {
            // The third line shares the status row's base frame; only translation changes.
            leftMargin = statusLp.leftMargin
            topMargin = statusLp.topMargin
            rightMargin = statusLp.rightMargin
            bottomMargin = statusLp.bottomMargin
            marginStart = statusLp.marginStart
            marginEnd = statusLp.marginEnd
        }
        parent.addView(
            label,
            layoutParams,
        )
        label.bringToFront()
        instances[profile] = ui

        label.viewTreeObserver.addOnPreDrawListener {
            syncPosition(symbols, ui)
            true
        }

        refresh(symbols, profile)
        syncPosition(symbols, ui)
        android.util.Log.i(TelegramRuntime.TAG, "$id attached")
    }

    private fun findProfileAnchors(symbols: ProfileIdentitySymbols, profile: Any): Pair<View, View> {
        val candidates = symbols.statusViewArrayFields.mapNotNull { field ->
            val array = field.get(profile) as? Array<*> ?: return@mapNotNull null
            array.getOrNull(1) as? View
        }
        check(candidates.size == 2) {
            "Expected two populated profile text arrays, found ${candidates.size}"
        }

        val status = candidates.minBy { view ->
            val paint = symbols.statusGetTextPaint.invoke(view) as TextPaint
            paint.textSize
        }
        val name = candidates.maxBy { view ->
            val paint = symbols.statusGetTextPaint.invoke(view) as TextPaint
            paint.textSize
        }
        return name to status
    }

    private fun syncPosition(symbols: ProfileIdentitySymbols, ui: ProfileIdentityUi) {
        val name = ui.nameAnchor
        val status = ui.statusAnchor
        val label = ui.label
        if (!name.isAttachedToWindow || !status.isAttachedToWindow || !label.isAttachedToWindow) return
        if (ui.id == 0L) {
            label.visibility = View.GONE
            return
        }

        val statusLp = status.layoutParams as? FrameLayout.LayoutParams ?: return

        val labelLp = label.layoutParams as? FrameLayout.LayoutParams ?: return
        if (statusLp.width > 0 && label.maxWidth != statusLp.width) {
            // Keep the actual touch target WRAP_CONTENT. Making the injected view as wide as the
            // whole status row causes vertical swipes that start beside the text to land on this
            // overlay and can leave Telegram's header gesture in an intermediate state.
            label.maxWidth = statusLp.width
        }

        // Telegram exposes the exact inverse of isPulledDown through this BaseFragment override:
        // isActionBarCrossfadeEnabled() == !isPulledDown. Using it avoids confusing an ordinary
        // header position near x=16dp with the full-photo pulled-down state.
        val pulledDown = runCatching {
            !(symbols.isActionBarCrossfadeEnabled.invoke(ui.profile) as Boolean)
        }.getOrDefault(false)

        val diff = ((name.scaleX - 1f) / 0.12f).coerceIn(0f, 1f)

        // Do not run a second, reconstructed header animation for ID/DC. The native name/status
        // views already contain all scroll, pull-down, expand/collapse and account-specific
        // offsets. Follow the rendered status row directly so the third line has exactly the
        // same velocity and cannot race ahead during the 250 ms avatar expansion animation.
        val statusPhysicalLeft = status.left + status.translationX
        val labelTextWidth = label.paint.measureText(label.text?.toString().orEmpty()) +
            label.paddingLeft + label.paddingRight
        val labelWidth = if (label.maxWidth > 0) {
            min(labelTextWidth, label.maxWidth.toFloat())
        } else {
            labelTextWidth
        }

        val desiredLeft = if (pulledDown) {
            // Full-photo mode left-aligns both subtitle rows at the same 16dp anchor.
            statusPhysicalLeft
        } else {
            // In the ordinary header both rows are independently centered. Derive the center
            // from Telegram's exact rendered status width instead of the status View bounds.
            val statusExactWidth = runCatching {
                (symbols.statusGetExactWidth.invoke(status) as Number).toFloat()
            }.getOrDefault(status.width.toFloat())
            val visibleStatusWidth = if (statusLp.width > 0) {
                min(statusExactWidth, statusLp.width.toFloat())
            } else {
                statusExactWidth
            }
            val statusCenter = statusPhysicalLeft + visibleStatusWidth * 0.5f
            statusCenter - labelWidth * 0.5f
        }
        label.translationX = desiredLeft - label.left

        // Match the native third-row spacing instead of placing our TextView below the whole
        // measured status View. SimpleTextView's measured box is taller than its visible glyphs,
        // so using status.bottom pushed ID/DC several dp too far down and into the action cards.
        // The source header uses:
        //   onlineY = avatarBottom + 24dp + floor(11*density) * diff
        //   idY     = avatarBottom + 32dp + floor(22*density) * diff
        // Keep that relative offset while still inheriting the status row's real animation.
        val density = label.resources.displayMetrics.density
        val rowOffset = if (pulledDown) {
            dp(label.context, 18f)
        } else {
            dp(label.context, 8f) +
                (floor(22f * density) - floor(11f * density)) * diff
        }
        val desiredTop = status.top + status.translationY + rowOffset
        label.translationY = desiredTop - label.top

        label.alpha = status.alpha * diff
        label.visibility = if (status.visibility == View.VISIBLE && diff > 0.01f) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val color = symbols.statusGetTextColor.invoke(status) as? Int
        if (color != null) label.setTextColor(color)
    }

    private fun refresh(symbols: ProfileIdentitySymbols, profile: Any) {
        val ui = instances[profile] ?: return
        val arguments = symbols.getArguments.invoke(profile) as? Bundle ?: return
        val userId = arguments.getLong("user_id", 0L)

        if (userId == 0L) {
            ui.id = 0
            ui.dc = 0
            ui.label.text = ""
            ui.label.visibility = View.GONE
            return
        }

        val messagesController = symbols.getMessagesController.invoke(profile) ?: return
        val user = symbols.messagesControllerGetUser.invoke(
            messagesController,
            java.lang.Long.valueOf(userId),
        ) ?: return
        check(symbols.userClass.isInstance(user)) { "Profile user shape mismatch" }

        val photo = symbols.userPhotoField.get(user)
        var dc = if (photo != null) symbols.photoDcIdField.getInt(photo) else 0
        if (dc == 0 && symbols.userSelfField.getBoolean(user)) {
            val connectionsManager = symbols.getConnectionsManager.invoke(profile)
            if (connectionsManager != null) {
                dc = symbols.currentDatacenterId.invoke(connectionsManager) as? Int ?: 0
            }
        }

        ui.id = userId
        ui.dc = dc
        ui.label.text = if (dc != 0) {
            "ID: $userId, DC: $dc"
        } else {
            "ID: $userId"
        }
        ui.label.visibility = ui.statusAnchor.visibility
    }

    private fun showIdentityMenu(
        runtime: TelegramRuntime,
        symbols: ProfileIdentitySymbols,
        ui: ProfileIdentityUi,
    ) {
        val itemOptions = symbols.itemOptions ?: return
        val options = itemOptions.factory.invoke(null, ui.profile, ui.label) ?: return
        val resources = ui.label.resources
        val packageName = ui.label.context.packageName
        val copyIcon = resources.getIdentifier("msg_copy", "drawable", packageName)
        val dcIcon = resources.getIdentifier("msg_satellite", "drawable", packageName)

        if (ui.id != 0L) {
            addMenuItem(
                itemOptions,
                options,
                copyIcon,
                runtime.strings.get(ui.label.context, R.string.telegram_copy_id),
                Runnable { copy(symbols, ui.id.toString()) },
            )
        }
        if (ui.dc != 0) {
            addMenuItem(
                itemOptions,
                options,
                dcIcon.takeIf { it != 0 } ?: copyIcon,
                runtime.strings.get(
                    ui.label.context,
                    R.string.telegram_copy_dc,
                    formatDcString(ui.dc),
                ),
                Runnable { copy(symbols, ui.dc.toString()) },
            )
        }
        if (ui.id != 0L && ui.dc != 0) {
            addMenuItem(
                itemOptions,
                options,
                copyIcon,
                runtime.strings.get(ui.label.context, R.string.telegram_copy_id_dc),
                Runnable { copy(symbols, "ID: ${ui.id}, DC: ${ui.dc}") },
            )
        }
        itemOptions.show.invoke(options)
    }

    private fun addMenuItem(
        symbols: TelegramItemOptionsSymbols,
        options: Any,
        iconResId: Int,
        text: CharSequence,
        action: Runnable,
    ) {
        if (symbols.addNeedsRedFlag) {
            symbols.add.invoke(options, iconResId, text, action, false)
        } else {
            symbols.add.invoke(options, iconResId, text, action)
        }
    }

    private fun copy(symbols: ProfileIdentitySymbols, value: String) {
        symbols.itemOptions?.addToClipboard?.invoke(null, value)
    }

    private fun formatDcString(dc: Int): String {
        val name = when (dc) {
            1 -> "Pluto"
            2 -> "Venus"
            3 -> "Aurora"
            4 -> "Vesta"
            5 -> "Flora"
            else -> "Unknown"
        }
        val location = when (dc) {
            1, 3 -> "Miami"
            2, 4 -> "Amsterdam"
            5 -> "Singapore"
            else -> "Unknown"
        }
        return "DC$dc $name, $location"
    }

    private fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
}
