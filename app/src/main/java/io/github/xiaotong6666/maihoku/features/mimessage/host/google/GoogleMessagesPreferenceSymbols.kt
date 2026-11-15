package io.github.xiaotong6666.maihoku.features.mimessage.host.google

import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/** Resolve the bundled AndroidX implementation once, without depending on R8 member names. */
internal data class GoogleMessagesPreferenceSymbols(
    val setTitle: Method,
    val setSummary: Method,
    val setEnabled: Method,
    val clearIconSpace: Method?,
) {
    companion object {
        fun resolve(runtime: HookRuntime): GoogleMessagesPreferenceSymbols = runtime.dexKit.useBridge { bridge ->
            val resolved = resolveData(bridge)
            GoogleMessagesPreferenceSymbols(
                setTitle = resolved.title.getMethodInstance(runtime.classLoader).apply { isAccessible = true },
                setSummary = resolved.summary.getMethodInstance(runtime.classLoader).apply { isAccessible = true },
                setEnabled = resolved.enabled.getMethodInstance(runtime.classLoader).apply { isAccessible = true },
                clearIconSpace = resolved.clearIconSpace?.getMethodInstance(runtime.classLoader)?.apply { isAccessible = true },
            )
        }

        /** Also usable by local APK compatibility diagnostics, without loading host classes. */
        internal fun resolveData(bridge: DexKitBridge): ResolvedData {
            val preferenceClass = checkNotNull(bridge.getClassData("androidx.preference.Preference"))
            val methods = preferenceClass.methods
            val summary = methods.single { method ->
                method.returnTypeName == "void" &&
                    method.paramTypeNames == listOf("java.lang.CharSequence") &&
                    "Preference already has a SummaryProvider set." in method.usingStrings
            }
            // setSummary invokes notifyChanged() on this class. Reuse that call edge to
            // distinguish setEnabled from persistence, visibility and selection setters.
            val notifyChanged = summary.invokes.single { method ->
                method.declaredClassName == preferenceClass.name &&
                    method.returnTypeName == "void" && method.paramCount == 0
            }
            val title = methods.single {
                it != summary && it.returnTypeName == "void" && it.paramTypeNames == listOf("java.lang.CharSequence")
            }
            val enabled = methods.single { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("boolean") &&
                    notifyChanged in method.invokes &&
                    method.invokes.any { called ->
                        called.declaredClassName == preferenceClass.name &&
                            called.returnTypeName == "boolean" && called.paramCount == 0
                    } &&
                    method.invokes.any { called ->
                        called.declaredClassName == preferenceClass.name &&
                            called.returnTypeName == "void" && called.paramTypeNames == listOf("boolean")
                    } &&
                    // Dependency/parent callbacks invert their argument before storing it;
                    // setEnabled stores the requested value directly.
                    method.opNames.none { it.startsWith("xor-int") } &&
                    method.usingFields.count {
                        it.usingType == FieldUsingType.Write && it.field.typeName == "boolean"
                    } == 1
            }
            // R8 specializes setIconSpaceReserved(false) to a no-argument method. Its flag
            // is read only by that method and the row binder, unlike the selectable flag
            // which is also read by performClick(). Cosmetic adaptation is optional.
            val clearIconSpace = methods.singleOrNull { method ->
                method.returnTypeName == "void" && method.paramCount == 0 &&
                    notifyChanged in method.invokes &&
                    method.usingFields.filter { it.usingType == FieldUsingType.Write }
                        .singleOrNull()?.field?.let { field ->
                            field.typeName == "boolean" && field.readers.all { reader ->
                                reader == method || reader.isConstructor ||
                                    (
                                        reader.returnTypeName == "void" && reader.paramCount == 1 &&
                                            reader.paramTypeNames.single() != "boolean"
                                        )
                            }
                        } == true
            }
            return ResolvedData(title, summary, enabled, clearIconSpace)
        }
    }

    internal data class ResolvedData(val title: MethodData, val summary: MethodData, val enabled: MethodData, val clearIconSpace: MethodData?)
}
