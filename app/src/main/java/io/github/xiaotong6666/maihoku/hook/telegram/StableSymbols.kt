package io.github.xiaotong6666.maihoku.hook.telegram

import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal data class BuildVarsSymbols(
    val clazz: Class<*>,
    val appId: Field,
    val appHash: Field,
    val safetyNetKey: Field,
    val supportsPasskeys: Field,
    val receipts: List<FieldReceipt>,
)

internal object StableSymbols {
    private const val BUILD_VARS = "org.telegram.messenger.BuildVars"

    fun resolveBuildVars(runtime: TelegramRuntime): BuildVarsSymbols {
        check(ClassLoaderProvider.safeClassLoader === runtime.classLoader) {
            "EzXHelper ClassLoader does not match Telegram target ClassLoader"
        }

        val clazz = Class.forName(BUILD_VARS, false, runtime.classLoader)
        val appId = clazz.getDeclaredField("APP_ID").apply { isAccessible = true }
        val appHash = clazz.getDeclaredField("APP_HASH").apply { isAccessible = true }
        val safetyNetKey = clazz.getDeclaredField("SAFETYNET_KEY").apply { isAccessible = true }
        val supportsPasskeys = clazz.getDeclaredField("SUPPORTS_PASSKEYS").apply { isAccessible = true }

        verifyStaticField(appId, Int::class.javaPrimitiveType!!)
        verifyStaticField(appHash, String::class.java)
        verifyStaticField(safetyNetKey, String::class.java)
        verifyStaticField(supportsPasskeys, Boolean::class.javaPrimitiveType!!)

        return BuildVarsSymbols(
            clazz = clazz,
            appId = appId,
            appHash = appHash,
            safetyNetKey = safetyNetKey,
            supportsPasskeys = supportsPasskeys,
            receipts = listOf(
                receipt("telegram.buildvars.app_id", appId, "STATIC_INT"),
                receipt("telegram.buildvars.app_hash", appHash, "STATIC_STRING"),
                receipt("telegram.buildvars.safetynet_key", safetyNetKey, "STATIC_STRING"),
                receipt("telegram.buildvars.supports_passkeys", supportsPasskeys, "STATIC_BOOLEAN"),
            ),
        )
    }

    private fun verifyStaticField(field: Field, expectedType: Class<*>) {
        check(Modifier.isStatic(field.modifiers)) { "${field.name} is not static" }
        check(field.type == expectedType) {
            "${field.name} type mismatch: ${field.type.name}, expected ${expectedType.name}"
        }
    }

    private fun receipt(id: String, field: Field, evidence: String) = FieldReceipt(
        id = id,
        declaringClassName = field.declaringClass.name,
        fieldName = field.name,
        typeName = field.type.name,
        evidence = setOf(evidence),
    )
}
