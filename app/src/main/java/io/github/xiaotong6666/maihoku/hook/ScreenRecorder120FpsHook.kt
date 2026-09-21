package io.github.xiaotong6666.maihoku.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ScreenRecorder120FpsHook : BaseHook() {
    override val name: String = "ScreenRecorder120FpsHook"

    private const val TARGET_FPS = "120"
    private const val TARGET_LABEL = "120fps"
    private val knownFrameValues = setOf("15", "24", "30", "48", "60", "90", TARGET_FPS)

    private data class ResolvedTarget(
        val configClass: Class<*>,
        val initMethod: Method,
    )

    @Volatile
    private var bootstrapped = false

    override fun init(runtime: HookRuntime) {
        val attach = MethodFinder.fromClass(Application::class)
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .first()

        runtime.hooks.method(attach, "screenrecorder.application.attach") {
            after {
                val context = arg(0) as? Context ?: return@after
                bootstrap(runtime, context)
            }
        }
    }

    private fun supports120Fps(context: Context): Boolean {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display ?: windowManager.defaultDisplay
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val refreshRates = readSupportedRefreshRates(display)
        return refreshRates.maxOrNull()?.let { it >= 120f } == true
    }

    private fun readSupportedRefreshRates(display: Display): FloatArray {
        @Suppress("DEPRECATION")
        return display.supportedRefreshRates ?: floatArrayOf(display.refreshRate)
    }

    @Synchronized
    private fun bootstrap(runtime: HookRuntime, context: Context) {
        if (bootstrapped) return

        val classLoader = context.classLoader ?: return
        val resolved = resolveTarget(runtime, classLoader) ?: run {
            Log.e(name, "Unable to resolve screen recorder frame config class with DexKit")
            return
        }

        runtime.hooks.method(resolved.initMethod, "screenrecorder.config.init") {
            after {
                val targetContext = arg(0) as? Context ?: context
                if (!supports120Fps(targetContext)) return@after
                enable120FpsOption(resolved.configClass)
            }
        }
        bootstrapped = true
        Log.i(name, "Hooked ${resolved.configClass.name}.${resolved.initMethod.name}(Context) via DexKit")
    }

    private fun resolveTarget(runtime: HookRuntime, classLoader: ClassLoader): ResolvedTarget? {
        return runtime.dexKit.useBridge { bridge ->
            val classData = bridge.findClass {
                matcher {
                    fields {
                        add {
                            modifiers = Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL
                            type = "int[]"
                        }
                        add {
                            modifiers = Modifier.PUBLIC or Modifier.STATIC
                            type = "java.util.ArrayList"
                        }
                    }
                    methods {
                        add {
                            returnType = "void"
                            paramTypes("android.content.Context")
                            usingStrings("initBitrateFrameValue")
                        }
                        add {
                            returnType = "void"
                            usingStrings("defaultFrames")
                        }
                    }
                    usingStrings("ScreenRecorderConfig", "defaultFrames")
                }
            }.firstOrNull() ?: return@useBridge null

            val configClass = classData.getInstance(classLoader)
            val initMethod = classData.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("android.content.Context")
                    usingStrings("initBitrateFrameValue")
                }
            }.firstOrNull()?.getMethodInstance(classLoader)
                ?: configClass.declaredMethods.firstOrNull {
                    it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(Context::class.java))
                }
                ?: return@useBridge null

            ResolvedTarget(configClass, initMethod.apply { isAccessible = true })
        }
    }

    private fun enable120FpsOption(configClass: Class<*>) {
        val staticLists = configClass.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }

        val valueField = staticLists.firstOrNull { field ->
            val values = field.get(null) as? List<*> ?: return@firstOrNull false
            isFrameValueList(values)
        } ?: return

        @Suppress("UNCHECKED_CAST")
        val values = valueField.get(null) as? MutableList<Any?> ?: return
        if (values.any { it?.toString() == TARGET_FPS }) return

        val labels = staticLists.firstNotNullOfOrNull { field ->
            @Suppress("UNCHECKED_CAST")
            val items = field.get(null) as? MutableList<Any?>
                ?: return@firstNotNullOfOrNull null
            if (items === values) return@firstNotNullOfOrNull null
            if (isFrameLabelList(items, values)) items else null
        } ?: return

        values.add(TARGET_FPS)
        labels.add(TARGET_LABEL)
        Log.i(name, "Injected 120fps option into ${configClass.name}")
    }

    private fun isFrameValueList(values: List<*>): Boolean {
        val strings = values.mapNotNull { it?.toString() }
        if (strings.size < 4) return false
        if (!strings.all { it.toIntOrNull() != null && it in knownFrameValues }) return false
        return strings.containsAll(listOf("24", "30", "48", "60"))
    }

    private fun isFrameLabelList(labels: List<*>, values: List<*>): Boolean {
        if (labels.size != values.size) return false
        val labelStrings = labels.mapNotNull { it?.toString() }
        val valueStrings = values.mapNotNull { it?.toString() }
        if (labelStrings.size != valueStrings.size) return false
        return labelStrings.zip(valueStrings).all { (label, value) -> label.contains(value) }
    }
}
