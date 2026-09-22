package io.github.xiaotong6666.maihoku.hook.telegram

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.xiaotong6666.maihoku.R
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

internal data class MutualContactSymbols(
    val userCellClass: Class<*>,
    val constructors: List<Constructor<*>>,
    val getCurrentObjectMethod: Method,
    val updateMethod: Method,
    val userClass: Class<*>,
    val mutualContactField: java.lang.reflect.Field,
)

internal object MutualContactFeature : TelegramFeature<MutualContactSymbols>() {
    override val id: String = "telegram.ui.mutual_contact"

    private val indicators = Collections.synchronizedMap(WeakHashMap<Any, TextView>())

    override fun isEnabled(runtime: TelegramRuntime): Boolean = runtime.config.mutualContactEnabled

    override fun resolve(runtime: TelegramRuntime): MutualContactSymbols {
        val resolution = runtime.dexKit.useBridge { bridge ->
            val candidates = bridge.findClass {
                matcher {
                    methods {
                        add {
                            name = "getCurrentObject"
                            returnType = "java.lang.Object"
                            paramCount = 0
                        }
                        add {
                            name = "setCallCellStyle"
                            returnType = "void"
                            paramTypes("int")
                        }
                        add {
                            name = "setAvatarPadding"
                            returnType = "void"
                            paramTypes("int")
                        }
                    }
                }
            }.filter { data ->
                runCatching {
                    FrameLayout::class.java.isAssignableFrom(data.getInstance(runtime.classLoader))
                }.getOrDefault(false)
            }

            check(candidates.size == 1) {
                "Expected one UserCell candidate, found ${candidates.size}"
            }
            val classData = candidates.single()
            val userCellClass = classData.getInstance(runtime.classLoader)

            val bindMethods = classData.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes(
                        "java.lang.Object",
                        "java.lang.CharSequence",
                        "java.lang.CharSequence",
                        "boolean",
                    )
                }
            }
            check(bindMethods.size == 1) {
                "Expected one UserCell core bind method, found ${bindMethods.size}"
            }
            val bindMethodData = bindMethods.single()

            val updateCandidates = bindMethodData.invokes.filter { method ->
                method.declaredClassName == classData.name &&
                    method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf("int")
            }
            check(updateCandidates.size == 1) {
                "Expected one UserCell update(int) invoked by bind, found ${updateCandidates.size}"
            }

            Triple(
                userCellClass,
                bindMethodData,
                updateCandidates.single().getMethodInstance(runtime.classLoader).apply {
                    isAccessible = true
                },
            )
        }
        val userCellClass = resolution.first
        val updateMethod = resolution.third

        val constructors = userCellClass.declaredConstructors
            .filter { constructor ->
                constructor.parameterTypes.any { it == Context::class.java } &&
                    constructor.parameterTypes.count { it == Int::class.javaPrimitiveType } >= 2
            }
            .onEach { it.isAccessible = true }
        check(constructors.isNotEmpty()) {
            "UserCell has no compatible constructor"
        }

        val getCurrentObject = userCellClass.getDeclaredMethod("getCurrentObject").apply {
            isAccessible = true
        }
        check(getCurrentObject.returnType == Any::class.java && getCurrentObject.parameterCount == 0) {
            "UserCell.getCurrentObject shape mismatch"
        }

        val userClass = Class.forName(
            "org.telegram.tgnet.TLRPC\$User",
            false,
            runtime.classLoader,
        )
        val mutualContact = userClass.getDeclaredField("mutual_contact").apply {
            isAccessible = true
        }
        check(mutualContact.type == Boolean::class.javaPrimitiveType) {
            "TLRPC.User.mutual_contact shape mismatch"
        }

        return MutualContactSymbols(
            userCellClass = userCellClass,
            constructors = constructors,
            getCurrentObjectMethod = getCurrentObject,
            updateMethod = updateMethod,
            userClass = userClass,
            mutualContactField = mutualContact,
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: MutualContactSymbols) {
        val hookIds = ArrayList<String>(resolution.constructors.size + 1)
        try {
            resolution.constructors.forEachIndexed { index, constructor ->
                val constructorId = "$id.constructor.$index"
                runtime.hooks.method(constructor, constructorId) {
                    after {
                        val cell = thisObject as? FrameLayout ?: return@after
                        if (!isContactsListCell(args)) return@after
                        ensureIndicator(runtime, cell)
                    }
                }
                hookIds += constructorId
            }

            val updateId = "$id.update"
            runtime.hooks.method(resolution.updateMethod, updateId) {
                after {
                    val cell = thisObject as? FrameLayout ?: return@after
                    val indicator = indicators[cell] ?: return@after
                    val obj = resolution.getCurrentObjectMethod.invoke(cell)
                    val mutual = obj != null &&
                        resolution.userClass.isInstance(obj) &&
                        resolution.mutualContactField.getBoolean(obj)
                    indicator.visibility = if (mutual) View.VISIBLE else View.GONE
                    indicator.contentDescription = runtime.strings.get(
                        cell.context,
                        R.string.telegram_mutual_contact,
                    )
                }
            }
            hookIds += updateId
        } catch (t: Throwable) {
            hookIds.asReversed().forEach(runtime.hooks::unhook)
            throw t
        }
    }

    private fun isContactsListCell(args: List<Any?>): Boolean {
        val ints = args.filterIsInstance<Int>()
        return 58 in ints && 1 in ints
    }

    private fun ensureIndicator(runtime: TelegramRuntime, cell: FrameLayout) {
        if (indicators.containsKey(cell)) return

        val context = cell.context
        val indicator = TextView(context).apply {
            text = "⇄"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            visibility = View.GONE
            contentDescription = runtime.strings.get(
                context,
                R.string.telegram_mutual_contact,
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {
                Toast.makeText(
                    context,
                    runtime.strings.get(context, R.string.telegram_mutual_contact_description),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val size = dp(context, 40f)
        val lp = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(context, 8f)
        }
        cell.addView(indicator, lp)
        indicators[cell] = indicator
    }

    private fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
