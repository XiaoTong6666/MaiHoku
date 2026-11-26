package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.net.wifi.WifiConfiguration
import android.view.View
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal const val CONFIG_FINGERPRINT = "Unknown phase2 method"

internal data class ControllerConstructor(
    val constructor: java.lang.reflect.Constructor<*>,
    val viewArg: Int,
    val entryArg: Int,
    val modeArg: Int,
)

internal data class Targets(
    val configMethod: Method,
    val constructors: List<ControllerConstructor>,
)

/** Detect the actual method by its code fingerprint, not its class or method name. */
internal fun resolveTargets(runtime: HookRuntime): List<Targets> = runCatching {
    runtime.dexKit.useBridge { bridge ->
        val candidates = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create().apply {
                    returnType = "android.net.wifi.WifiConfiguration"
                    paramTypes = emptyList()
                    usingStrings(CONFIG_FINGERPRINT)
                },
            ),
        ).mapNotNull { data ->
            runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
        }.filter { it.parameterCount == 0 && it.returnType == WifiConfiguration::class.java }
            .distinctBy { it.declaringClass }
        Log.i(TAG, "DexKit found ${candidates.size} Wi-Fi configuration candidates")
        candidates.mapNotNull { method ->
            val owner = method.declaringClass
            val constructors = owner.declaredConstructors.filter { candidate ->
                candidate.parameterTypes.contains(View::class.java) &&
                    candidate.parameterTypes.any {
                        it.name in setOf(
                            "com.android.wifitrackerlib.WifiEntry",
                            "com.android.settingslib.wifi.AccessPoint",
                        )
                    } &&
                    candidate.parameterTypes.contains(Int::class.javaPrimitiveType)
            }.map { ctor ->
                ctor.isAccessible = true
                val types = ctor.parameterTypes
                ControllerConstructor(
                    ctor,
                    viewArg = types.indexOf(View::class.java),
                    entryArg = types.indexOfFirst {
                        it.name in setOf(
                            "com.android.wifitrackerlib.WifiEntry",
                            "com.android.settingslib.wifi.AccessPoint",
                        )
                    },
                    modeArg = types.indexOf(Int::class.javaPrimitiveType),
                )
            }
            if (constructors.isEmpty()) return@mapNotNull null
            method.isAccessible = true
            Log.i(TAG, "Identified ${constructors.size} constructor overloads for ${owner.simpleName}")
            Targets(method, constructors)
        }
    }
}.onFailure { Log.w(TAG, "DexKit resolution failed", it) }.getOrDefault(emptyList())
