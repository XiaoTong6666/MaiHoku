package io.github.xiaotong6666.maihoku.hook.telegram

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

internal data class SourceFreeForwardSymbols(
    val contextMenuBuilder: Method,
    val configurePopupRow: Method,
    val setRightIcon: Method,
    val getRightIcon: Method,
    val addPopupItem: Method,
    val popupLayoutConstructor: Constructor<*>,
    val popupLayoutConstructorWithFlags: Constructor<*>,
    val setFitItems: Method,
    val getSwipeBack: Method,
    val addSwipePage: Method,
    val openForeground: Method,
    val closeForeground: Method,
    val processSelectedOption: Method,
    val actionButtonsConstructor: Constructor<*>,
    val getForwardButton: Method,
    val setForwardButtonOnClickListener: Method,
    val actionButtonsResourceProvider: Field,
    val actionMenuItemConstructor: Constructor<*>,
    val actionMenuAddSubItem: Method,
    val actionMenuShow: Method,
    val actionMenuDismiss: Method,
    val actionMenuSetLongClickEnabled: Method,
    val actionMenuSetSubMenuOpenSide: Method,
    val actionMenuSetAdditionalYOffset: Method,
    val actionMenuSetShowedFromBottom: Method,
    val sendForwardMethods: List<Method>,
)

internal object SourceFreeForwardFeature : TelegramFeature<SourceFreeForwardSymbols>() {
    override val id: String = "telegram.forward.source_free"

    private const val NORMAL_SUB_ITEM_ID = -0x4d4801
    private const val SOURCE_FREE_SUB_ITEM_ID = -0x4d4802
    private const val PREFS_NAME = "maihoku_forward"
    private const val PREF_SOURCE_FREE = "source_free_selected"
    private const val ARM_TIMEOUT_MS = 5 * 60 * 1000L
    private const val SEND_BATCH_GRACE_MS = 3_000L

    private val installedRows = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val pageIndexes = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val bottomForwardMenus = Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val buildingMessageMenu = ThreadLocal<Boolean>()

    private val stateLock = Any()
    private var generation = 0L
    private var armedAt = 0L
    private var sendVersion = 0L

    @Volatile
    private var sourceFreeSelected: Boolean? = null

    override fun isEnabled(runtime: TelegramRuntime): Boolean =
        runtime.config.sourceFreeForwardEnabled

    override fun resolve(runtime: TelegramRuntime): SourceFreeForwardSymbols {
        val profileClass = Class.forName(
            "org.telegram.ui.ProfileActivity",
            false,
            runtime.classLoader,
        )
        val actionBarClass = profileClass.getMethod("getActionBar").returnType
        val actionModeClass = actionBarClass.getMethod("getActionMode").returnType

        val addActionItem = actionModeClass.declaredMethods.single { method ->
            val params = method.parameterTypes
            params.size == 8 &&
                params[0] == Int::class.javaPrimitiveType &&
                params[1] == Int::class.javaPrimitiveType &&
                params[2] == CharSequence::class.java &&
                params[3] == Int::class.javaPrimitiveType &&
                android.graphics.drawable.Drawable::class.java.isAssignableFrom(params[4]) &&
                params[5] == Int::class.javaPrimitiveType &&
                params[6] == CharSequence::class.java &&
                View::class.java.isAssignableFrom(method.returnType)
        }.apply { isAccessible = true }

        val actionBarMenuItemClass = addActionItem.returnType
        val addPopupItem = actionBarMenuItemClass.declaredMethods.single { method ->
            val params = method.parameterTypes
            Modifier.isStatic(method.modifiers) &&
                params.size == 7 &&
                params[0] == Boolean::class.javaPrimitiveType &&
                params[1] == Boolean::class.javaPrimitiveType &&
                ViewGroup::class.java.isAssignableFrom(params[2]) &&
                params[3] == Int::class.javaPrimitiveType &&
                params[4] == CharSequence::class.java &&
                params[5] == Boolean::class.javaPrimitiveType &&
                View::class.java.isAssignableFrom(method.returnType)
        }.apply { isAccessible = true }

        val popupSubItemClass = addPopupItem.returnType
        val resourceProviderClass = addPopupItem.parameterTypes[6]
        val configurePopupRow = popupSubItemClass.declaredMethods.single { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        CharSequence::class.java,
                    ),
                )
        }.apply { isAccessible = true }

        val setRightIcon = popupSubItemClass.getMethod(
            "setRightIcon",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val getRightIcon = popupSubItemClass.getMethod("getRightIcon").apply {
            isAccessible = true
        }

        val popupLayoutClass = Class.forName(
            "org.telegram.ui.ActionBar.ActionBarPopupWindow\$ActionBarPopupWindowLayout",
            false,
            runtime.classLoader,
        )
        val popupLayoutConstructor = popupLayoutClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    Context::class.java,
                    resourceProviderClass,
                ),
            )
        }.apply { isAccessible = true }
        val popupLayoutConstructorWithFlags = popupLayoutClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Context::class.java,
                    resourceProviderClass,
                ),
            )
        }.apply { isAccessible = true }
        val setFitItems = popupLayoutClass.getMethod(
            "setFitItems",
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val getSwipeBack = popupLayoutClass.getMethod("getSwipeBack").apply {
            isAccessible = true
        }
        val addSwipePage = popupLayoutClass.declaredMethods.single { method ->
            method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(View::class.java))
        }.apply { isAccessible = true }

        val swipeBackClass = getSwipeBack.returnType
        data class ResolvedMethods(
            val contextMenuBuilder: Method,
            val openForeground: Method,
            val closeForeground: Method,
            val processSelectedOption: Method,
        )

        val resolvedMethods = runtime.dexKit.useBridge { bridge ->
            val swipeData = bridge.getClassData(swipeBackClass)
                ?: error("Unable to inspect Telegram popup swipe-back class")

            val openCandidates = swipeData.methods.filter { method ->
                method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf(Int::class.javaPrimitiveType!!.name) &&
                    method.invokes.any { invoked ->
                        invoked.declaredClassName == swipeData.name &&
                            invoked.returnTypeName == "void" &&
                            invoked.paramTypeNames == listOf(
                                Float::class.javaPrimitiveType!!.name,
                                Float::class.javaPrimitiveType!!.name,
                            )
                    }
            }
            check(openCandidates.size == 1) {
                "Expected one popup openForeground method, found ${openCandidates.size}"
            }

            val closeCandidates = swipeData.methods.filter { method ->
                method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf(Boolean::class.javaPrimitiveType!!.name) &&
                    method.invokes.any { invoked ->
                        invoked.declaredClassName == swipeData.name &&
                            invoked.returnTypeName == "void" &&
                            invoked.paramTypeNames == listOf(
                                Float::class.javaPrimitiveType!!.name,
                                Float::class.javaPrimitiveType!!.name,
                            )
                    }
            }
            check(closeCandidates.size == 1) {
                "Expected one popup closeForeground method, found ${closeCandidates.size}"
            }

            val popupSubItemData = bridge.getClassData(popupSubItemClass)
                ?: error("Unable to inspect Telegram popup row class")
            val configureData = popupSubItemData.methods.single { method ->
                method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf(
                        Int::class.javaPrimitiveType!!.name,
                        CharSequence::class.java.name,
                    )
            }
            val contextBuilders = configureData.callers.filter { caller ->
                caller.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
                    caller.paramTypeNames == listOf(
                        View::class.java.name,
                        Boolean::class.javaPrimitiveType!!.name,
                        Boolean::class.javaPrimitiveType!!.name,
                        Float::class.javaPrimitiveType!!.name,
                        Float::class.javaPrimitiveType!!.name,
                        Boolean::class.javaPrimitiveType!!.name,
                        Boolean::class.javaPrimitiveType!!.name,
                        Boolean::class.javaPrimitiveType!!.name,
                    )
            }.distinctBy { it.methodSign }
            check(contextBuilders.size == 1) {
                "Expected one Telegram message context-menu builder, found ${contextBuilders.size}"
            }
            val contextMenuBuilderData = contextBuilders.single()
            val chatClass = contextMenuBuilderData.declaredClass
                ?: error("Telegram message context-menu builder has no declaring class")

            val optionHandlers = chatClass.methods.filter { method ->
                method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf(Int::class.javaPrimitiveType!!.name) &&
                    "onlySelect" in method.usingStrings &&
                    "dialogsType" in method.usingStrings &&
                    "messagesCount" in method.usingStrings
            }
            check(optionHandlers.size == 1) {
                "Expected one Telegram selected-option handler, found ${optionHandlers.size}"
            }

            ResolvedMethods(
                contextMenuBuilder = contextMenuBuilderData.getMethodInstance(runtime.classLoader),
                openForeground = openCandidates.single().getMethodInstance(runtime.classLoader),
                closeForeground = closeCandidates.single().getMethodInstance(runtime.classLoader),
                processSelectedOption = optionHandlers.single().getMethodInstance(runtime.classLoader),
            )
        }

        val contextMenuBuilder = resolvedMethods.contextMenuBuilder.apply { isAccessible = true }
        val openForeground = resolvedMethods.openForeground.apply { isAccessible = true }
        val closeForeground = resolvedMethods.closeForeground.apply { isAccessible = true }
        val processSelectedOption = resolvedMethods.processSelectedOption.apply { isAccessible = true }

        val actionButtonsClass = processSelectedOption.declaringClass.declaredFields
            .map { it.type }
            .distinct()
            .filter { candidate ->
                runCatching {
                    val getForward = candidate.getMethod("getForwardButton")
                    val setForward = candidate.getMethod(
                        "setForwardButtonOnClickListener",
                        View.OnClickListener::class.java,
                    )
                    View::class.java.isAssignableFrom(getForward.returnType) &&
                        setForward.returnType == Void.TYPE
                }.getOrDefault(false)
            }
        check(actionButtonsClass.size == 1) {
            "Expected one Telegram bottom action-buttons class, found ${actionButtonsClass.size}"
        }
        val bottomButtonsClass = actionButtonsClass.single()
        val actionButtonsConstructor = bottomButtonsClass.declaredConstructors.single().apply {
            isAccessible = true
        }
        val getForwardButton = bottomButtonsClass.getMethod("getForwardButton").apply {
            isAccessible = true
        }
        val setForwardButtonOnClickListener = bottomButtonsClass.getMethod(
            "setForwardButtonOnClickListener",
            View.OnClickListener::class.java,
        ).apply { isAccessible = true }
        val actionButtonsResourceProvider = bottomButtonsClass.declaredFields.single { field ->
            field.type == resourceProviderClass
        }.apply { isAccessible = true }

        val actionMenuItemConstructor = actionBarMenuItemClass.getConstructor(
            Context::class.java,
            actionModeClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionMenuAddSubItem = actionBarMenuItemClass.declaredMethods.single { method ->
            method.parameterTypes.contentEquals(
                arrayOf(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    resourceProviderClass,
                ),
            ) && View::class.java.isAssignableFrom(method.returnType)
        }.apply { isAccessible = true }
        val actionMenuShow = actionBarMenuItemClass.declaredMethods.single { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        View::class.java,
                        actionBarMenuItemClass,
                    ),
                )
        }.apply { isAccessible = true }
        val actionMenuDismiss = actionBarMenuItemClass.getMethod("n").apply {
            isAccessible = true
        }
        val actionMenuSetLongClickEnabled = actionBarMenuItemClass.getMethod(
            "setLongClickEnabled",
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionMenuSetSubMenuOpenSide = actionBarMenuItemClass.getMethod(
            "setSubMenuOpenSide",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionMenuSetAdditionalYOffset = actionBarMenuItemClass.getMethod(
            "setAdditionalYOffset",
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val actionMenuSetShowedFromBottom = actionBarMenuItemClass.getMethod(
            "setShowedFromBottom",
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val sendMessagesHelper = Class.forName(
            "org.telegram.messenger.SendMessagesHelper",
            false,
            runtime.classLoader,
        )
        val sendForwardMethods = sendMessagesHelper.declaredMethods.filter { method ->
            method.name == "sendMessage" &&
                Modifier.isPublic(method.modifiers) &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterCount >= 7 &&
                method.parameterTypes[0] == ArrayList::class.java &&
                method.parameterTypes[1] == Long::class.javaPrimitiveType &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[4] == Boolean::class.javaPrimitiveType
        }.onEach { it.isAccessible = true }
        check(sendForwardMethods.isNotEmpty()) {
            "Unable to resolve Telegram forward send methods"
        }

        return SourceFreeForwardSymbols(
            contextMenuBuilder = contextMenuBuilder,
            configurePopupRow = configurePopupRow,
            setRightIcon = setRightIcon,
            getRightIcon = getRightIcon,
            addPopupItem = addPopupItem,
            popupLayoutConstructor = popupLayoutConstructor,
            popupLayoutConstructorWithFlags = popupLayoutConstructorWithFlags,
            setFitItems = setFitItems,
            getSwipeBack = getSwipeBack,
            addSwipePage = addSwipePage,
            openForeground = openForeground,
            closeForeground = closeForeground,
            processSelectedOption = processSelectedOption,
            actionButtonsConstructor = actionButtonsConstructor,
            getForwardButton = getForwardButton,
            setForwardButtonOnClickListener = setForwardButtonOnClickListener,
            actionButtonsResourceProvider = actionButtonsResourceProvider,
            actionMenuItemConstructor = actionMenuItemConstructor,
            actionMenuAddSubItem = actionMenuAddSubItem,
            actionMenuShow = actionMenuShow,
            actionMenuDismiss = actionMenuDismiss,
            actionMenuSetLongClickEnabled = actionMenuSetLongClickEnabled,
            actionMenuSetSubMenuOpenSide = actionMenuSetSubMenuOpenSide,
            actionMenuSetAdditionalYOffset = actionMenuSetAdditionalYOffset,
            actionMenuSetShowedFromBottom = actionMenuSetShowedFromBottom,
            sendForwardMethods = sendForwardMethods,
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: SourceFreeForwardSymbols) {
        val hookIds = ArrayList<String>()
        try {
            Log.i(
                TelegramRuntime.TAG,
                "$id resolved row=${resolution.configurePopupRow.declaringClass.name}." +
                    "${resolution.configurePopupRow.name} option=" +
                    "${resolution.processSelectedOption.declaringClass.name}." +
                    "${resolution.processSelectedOption.name} swipe=" +
                    "${resolution.openForeground.declaringClass.name}." +
                    "${resolution.openForeground.name}/${resolution.closeForeground.name}",
            )

            val contextBuilderHookId = "$id.context_builder"
            runtime.hooks.around(resolution.contextMenuBuilder, contextBuilderHookId) {
                buildingMessageMenu.set(true)
                try {
                    proceed()
                } finally {
                    buildingMessageMenu.remove()
                }
            }
            hookIds += contextBuilderHookId

            val popupConstructorHookId = "$id.popup_constructor"
            runtime.hooks.method(
                resolution.popupLayoutConstructorWithFlags,
                popupConstructorHookId,
            ) {
                before {
                    if (buildingMessageMenu.get() == true) {
                        val oldFlags = arg(1) as Int
                        val newFlags = oldFlags or 1
                        if (newFlags != oldFlags) {
                            replaceArg(1, newFlags)
                            Log.i(
                                TelegramRuntime.TAG,
                                "$id enabled swipe-back on message popup flags=$oldFlags->$newFlags",
                            )
                        }
                    }
                }
            }
            hookIds += popupConstructorHookId

            val bottomConstructorHookId = "$id.bottom_constructor"
            runtime.hooks.method(resolution.actionButtonsConstructor, bottomConstructorHookId) {
                after {
                    val layout = thisObject as? ViewGroup ?: return@after
                    runCatching {
                        attachBottomForwardMenu(resolution, layout)
                    }.onFailure { error ->
                        Log.w(
                            TelegramRuntime.TAG,
                            "$id failed to attach bottom forward menu",
                            error,
                        )
                    }
                }
            }
            hookIds += bottomConstructorHookId

            val bottomClickHookId = "$id.bottom_click"
            runtime.hooks.method(
                resolution.setForwardButtonOnClickListener,
                bottomClickHookId,
            ) {
                before {
                    val original = arg(0) as? View.OnClickListener ?: return@before
                    replaceArg(
                        0,
                        View.OnClickListener { view ->
                            val selected = isSelected(view)
                            if (selected) {
                                armSourceFree()
                            } else {
                                clearArmedState()
                            }
                            Log.i(
                                TelegramRuntime.TAG,
                                "$id bottom forward click selected=$selected",
                            )
                            original.onClick(view)
                        },
                    )
                }
            }
            hookIds += bottomClickHookId

            val rowHookId = "$id.context_row"
            runtime.hooks.method(resolution.configurePopupRow, rowHookId) {
                after {
                    val item = thisObject as? View ?: return@after
                    val iconResId = arg(0) as? Int ?: return@after
                    if (!isForwardIcon(item, iconResId)) return@after
                    if (installedRows.put(item, true) != null) return@after

                    runCatching {
                        attachContextForwardRow(resolution, item)
                        updateForwardLabel(item)
                        Log.i(
                            TelegramRuntime.TAG,
                            "$id context forward row attached selected=${isSelected(item)}",
                        )
                    }.onFailure { error ->
                        installedRows.remove(item)
                        Log.w(
                            TelegramRuntime.TAG,
                            "$id failed to attach message context forward row",
                            error,
                        )
                    }
                }
            }
            hookIds += rowHookId

            val optionHookId = "$id.selected_option"
            runtime.hooks.method(resolution.processSelectedOption, optionHookId) {
                before {
                    val option = arg(0) as? Int ?: return@before
                    if (option != 2) return@before

                    val selected = sourceFreeSelected == true
                    if (selected) {
                        armSourceFree()
                    } else {
                        clearArmedState()
                    }
                    Log.i(
                        TelegramRuntime.TAG,
                        "$id forward option dispatched selected=$selected",
                    )
                }
            }
            hookIds += optionHookId

            resolution.sendForwardMethods.forEachIndexed { index, method ->
                val hookId = "$id.send.$index"
                runtime.hooks.method(method, hookId) {
                    before {
                        if (consumeForSend()) {
                            replaceArg(2, true)
                            scheduleBatchClear()
                            Log.i(
                                TelegramRuntime.TAG,
                                "$id forcing forwardFromMyName=true method=$index",
                            )
                        }
                    }
                }
                hookIds += hookId
            }
        } catch (t: Throwable) {
            hookIds.asReversed().forEach(runtime.hooks::unhook)
            throw t
        }
    }

    private fun attachContextForwardRow(
        symbols: SourceFreeForwardSymbols,
        item: View,
    ) {
        val arrowResId = item.resources.getIdentifier(
            "msg_arrowright",
            "drawable",
            item.context.packageName,
        )
        if (arrowResId != 0) {
            symbols.setRightIcon.invoke(item, arrowResId)
            (symbols.getRightIcon.invoke(item) as? View)?.setOnClickListener {
                openForwardOptions(symbols, item)
            }
        }

        item.contentDescription = if (isSelected(item)) {
            sourceFreeText()
        } else {
            forwardText()
        }
        item.setOnLongClickListener {
            Log.i(TelegramRuntime.TAG, "$id context forward long-press")
            openForwardOptions(symbols, item)
        }
    }

    private fun attachBottomForwardMenu(
        symbols: SourceFreeForwardSymbols,
        layout: ViewGroup,
    ) {
        val button = symbols.getForwardButton.invoke(layout) as? View
            ?: error("Telegram bottom forward button is missing")
        if (bottomForwardMenus.containsKey(button)) return

        val parent = button.parent as? ViewGroup
            ?: error("Telegram bottom forward button has no parent")
        val index = parent.indexOfChild(button)
        check(index >= 0) { "Telegram bottom forward button is detached" }
        val parentLayoutParams = button.layoutParams

        val menu = symbols.actionMenuItemConstructor.newInstance(
            button.context,
            null,
            0,
            0,
        ) as ViewGroup
        symbols.actionMenuSetSubMenuOpenSide.invoke(menu, 2)
        symbols.actionMenuSetLongClickEnabled.invoke(menu, true)

        parent.removeViewAt(index)
        menu.addView(
            button,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        parent.addView(menu, index, parentLayoutParams)

        val resourcesProvider = symbols.actionButtonsResourceProvider.get(layout)
        val forwardIcon = button.resources.getIdentifier(
            "msg_forward",
            "drawable",
            button.context.packageName,
        )
        val normal = symbols.actionMenuAddSubItem.invoke(
            menu,
            NORMAL_SUB_ITEM_ID,
            forwardIcon,
            forwardText(),
            resourcesProvider,
        ) as View
        val sourceFree = symbols.actionMenuAddSubItem.invoke(
            menu,
            SOURCE_FREE_SUB_ITEM_ID,
            forwardIcon,
            sourceFreeText(),
            resourcesProvider,
        ) as View

        normal.setOnClickListener {
            setSelected(button, false)
            clearArmedState()
            symbols.actionMenuDismiss.invoke(menu)
            Log.i(TelegramRuntime.TAG, "$id bottom mode switched to normal")
            button.performClick()
        }
        sourceFree.setOnClickListener {
            setSelected(button, true)
            armSourceFree()
            symbols.actionMenuDismiss.invoke(menu)
            Log.i(TelegramRuntime.TAG, "$id bottom mode switched to source-free")
            button.performClick()
        }

        symbols.actionMenuSetAdditionalYOffset.invoke(
            menu,
            -(109f * button.resources.displayMetrics.density).toInt(),
        )
        symbols.actionMenuSetShowedFromBottom.invoke(menu, true)
        button.setOnLongClickListener {
            Log.i(
                TelegramRuntime.TAG,
                "$id bottom forward long-press selected=${isSelected(button)}",
            )
            runCatching {
                symbols.actionMenuShow.invoke(menu, null, null)
                Log.i(
                    TelegramRuntime.TAG,
                    "$id bottom forward menu opened selected=${isSelected(button)}",
                )
            }.onFailure { error ->
                Log.w(
                    TelegramRuntime.TAG,
                    "$id failed to show bottom forward menu",
                    error,
                )
            }.isSuccess
        }

        bottomForwardMenus[button] = menu
        updateForwardLabel(button)
        Log.i(
            TelegramRuntime.TAG,
            "$id bottom forward menu attached selected=${isSelected(button)}",
        )
    }

    private fun openForwardOptions(
        symbols: SourceFreeForwardSymbols,
        item: View,
    ): Boolean {
        return runCatching {
            val popupLayoutClass = symbols.popupLayoutConstructor.declaringClass
            var parent: View? = item
            while (parent != null && !popupLayoutClass.isInstance(parent)) {
                parent = parent.parent as? View
            }
            val popupLayout = parent
                ?: error("Unable to find Telegram popup layout for forward row")
            val swipeBack = symbols.getSwipeBack.invoke(popupLayout)
                ?: error("Telegram popup has no swipe-back container")

            val existingIndex = pageIndexes[item]
            if (existingIndex != null) {
                symbols.openForeground.invoke(swipeBack, existingIndex)
                Log.i(
                    TelegramRuntime.TAG,
                    "$id reopened forward mode page index=$existingIndex",
                )
                return@runCatching true
            }

            val page = symbols.popupLayoutConstructor.newInstance(item.context, null) as ViewGroup
            symbols.setFitItems.invoke(page, true)

            val backIcon = item.resources.getIdentifier(
                "msg_arrow_back",
                "drawable",
                item.context.packageName,
            )
            val forwardIcon = item.resources.getIdentifier(
                "msg_forward",
                "drawable",
                item.context.packageName,
            )

            val back = addPopupRow(
                symbols,
                page,
                backIcon,
                backText(),
            )
            back.setOnClickListener {
                symbols.closeForeground.invoke(swipeBack, true)
            }

            val normal = addPopupRow(
                symbols,
                page,
                forwardIcon,
                forwardText(),
            )
            normal.setOnClickListener {
                setSelected(item, false)
                clearArmedState()
                Log.i(TelegramRuntime.TAG, "$id mode switched to normal")
                item.performClick()
            }

            val sourceFree = addPopupRow(
                symbols,
                page,
                forwardIcon,
                sourceFreeText(),
            )
            sourceFree.setOnClickListener {
                setSelected(item, true)
                armSourceFree()
                Log.i(TelegramRuntime.TAG, "$id mode switched to source-free")
                item.performClick()
            }

            val index = symbols.addSwipePage.invoke(popupLayout, page) as Int
            pageIndexes[item] = index
            symbols.openForeground.invoke(swipeBack, index)
            Log.i(
                TelegramRuntime.TAG,
                "$id opened forward mode page index=$index",
            )
            true
        }.onFailure { error ->
            Log.w(
                TelegramRuntime.TAG,
                "$id failed to open forward mode page",
                error,
            )
        }.getOrDefault(false)
    }

    private fun addPopupRow(
        symbols: SourceFreeForwardSymbols,
        parent: ViewGroup,
        iconResId: Int,
        text: CharSequence,
    ): View = symbols.addPopupItem.invoke(
        null,
        false,
        false,
        parent,
        iconResId,
        text,
        false,
        null,
    ) as View

    private fun isForwardIcon(item: View, iconResId: Int): Boolean {
        if (iconResId == 0) return false
        return runCatching {
            item.resources.getResourceEntryName(iconResId) == "msg_forward"
        }.getOrDefault(false)
    }

    private fun isSelected(item: View): Boolean {
        sourceFreeSelected?.let { return it }
        return synchronized(stateLock) {
            sourceFreeSelected ?: item.context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_SOURCE_FREE, false)
                .also { sourceFreeSelected = it }
        }
    }

    private fun setSelected(item: View, selected: Boolean) {
        sourceFreeSelected = selected
        item.context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SOURCE_FREE, selected)
            .apply()
        item.contentDescription = if (selected) {
            sourceFreeText()
        } else {
            forwardText()
        }
        updateForwardLabel(item)
        synchronized(bottomForwardMenus) {
            bottomForwardMenus.keys.toList().forEach(::updateForwardLabel)
        }
    }

    private fun updateForwardLabel(view: View) {
        val text = if (isSelected(view)) sourceFreeText() else forwardText()
        findTextView(view)?.text = text
        view.contentDescription = text
    }

    private fun findTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTextView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun armSourceFree() {
        synchronized(stateLock) {
            generation++
            armedAt = SystemClock.elapsedRealtime()
            sendVersion = 0L
        }
    }

    private fun clearArmedState() {
        synchronized(stateLock) {
            generation++
            armedAt = 0L
            sendVersion = 0L
        }
    }

    private fun consumeForSend(): Boolean = synchronized(stateLock) {
        if (armedAt == 0L) return@synchronized false
        val now = SystemClock.elapsedRealtime()
        if (now - armedAt > ARM_TIMEOUT_MS) {
            generation++
            armedAt = 0L
            sendVersion = 0L
            return@synchronized false
        }
        sendVersion++
        true
    }

    private fun scheduleBatchClear() {
        val snapshot = synchronized(stateLock) {
            generation to sendVersion
        }
        mainHandler.postDelayed(
            {
                synchronized(stateLock) {
                    if (generation == snapshot.first && sendVersion == snapshot.second) {
                        generation++
                        armedAt = 0L
                        sendVersion = 0L
                    }
                }
            },
            SEND_BATCH_GRACE_MS,
        )
    }

    private fun forwardText(): String =
        if (isChinese()) "转发" else "Forward"

    private fun sourceFreeText(): String =
        if (isChinese()) "无来源转发" else "Forward without sender"

    private fun backText(): String =
        if (isChinese()) "返回" else "Back"

    private fun isChinese(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)
}
