package io.github.xiaotong6666.maihoku.hook

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ScreenRecorder120FpsHook : BaseHook() {
    override val name: String = "ScreenRecorder120FpsHook"

    private const val MODULE_PACKAGE = "io.github.xiaotong6666.maihoku"
    private const val TARGET_FPS = "120"
    private const val TARGET_LABEL = "120fps"
    private val knownFrameValues = setOf("15", "24", "30", "48", "60", "90", TARGET_FPS)

    private data class ResolvedTarget(
        val configClass: Class<*>,
        val initMethod: Method,
    )

    @Volatile
    private var bootstrapped = false

    @Volatile
    private var dexKitLoaded = false

    override fun init() {
        MethodFinder.fromClass(Application::class)
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .first()
            .createHook {
                after { param ->
                    val context = param.arg(0) as? Context ?: return@after
                    bootstrap(context)
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
    private fun bootstrap(context: Context) {
        if (bootstrapped) return
        ensureDexKitLoaded(context)

        val classLoader = context.classLoader ?: return
        val apkPath = context.applicationInfo?.sourceDir ?: return
        val resolved = resolveTarget(apkPath, classLoader) ?: run {
            Log.e(name, "Unable to resolve screen recorder frame config class with DexKit")
            return
        }

        resolved.initMethod.createHook {
            after { param ->
                val targetContext = param.arg(0) as? Context ?: context
                if (!supports120Fps(targetContext)) return@after
                enable120FpsOption(resolved.configClass)
            }
        }
        bootstrapped = true
        Log.i(name, "Hooked ${resolved.configClass.name}.${resolved.initMethod.name}(Context) via DexKit")
    }

    @Synchronized
    private fun ensureDexKitLoaded(context: Context) {
        if (dexKitLoaded) return

        val nativeLibraryDir = try {
            context.packageManager
                .getApplicationInfo(MODULE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
                .nativeLibraryDir
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).nativeLibraryDir
            } catch (_: Throwable) {
                null
            }
        }
        if (!nativeLibraryDir.isNullOrBlank()) {
            val libraryFile = File(nativeLibraryDir, "libdexkit.so")
            if (libraryFile.exists()) {
                System.load(libraryFile.absolutePath)
                dexKitLoaded = true
                Log.i(name, "Loaded DexKit from ${libraryFile.absolutePath}")
                return
            }
        }

        System.loadLibrary("dexkit")
        dexKitLoaded = true
        Log.i(name, "Loaded DexKit via System.loadLibrary")
    }

    private fun resolveTarget(apkPath: String, classLoader: ClassLoader): ResolvedTarget? {
        DexKitBridge.create(apkPath).use { bridge ->
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
            }.firstOrNull() ?: return null

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
                ?: return null

            return ResolvedTarget(configClass, initMethod.apply { isAccessible = true })
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
