package io.github.xiaotong6666.maihoku.hook.telegram

import android.os.Bundle
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SmsFeeBillingSymbols(
    val useInvoiceBilling: Method,
    val smsFeePageMethod: Method,
)

internal object SmsFeeBillingFeature : TelegramFeature<SmsFeeBillingSymbols>() {
    override val id: String = "telegram.sms.fee_billing"

    override fun isEnabled(runtime: TelegramRuntime): Boolean = runtime.config.smsProfileEnabled

    override fun resolve(runtime: TelegramRuntime): SmsFeeBillingSymbols {
        val buildVars = StableSymbols.resolveBuildVars(runtime)
        val useInvoiceBilling = buildVars.clazz.getDeclaredMethod("useInvoiceBilling").apply {
            isAccessible = true
        }
        check(
            Modifier.isStatic(useInvoiceBilling.modifiers) &&
                useInvoiceBilling.parameterCount == 0 &&
                useInvoiceBilling.returnType == Boolean::class.javaPrimitiveType,
        ) { "BuildVars.useInvoiceBilling() shape mismatch" }

        return SmsFeeBillingSymbols(
            useInvoiceBilling = useInvoiceBilling,
            smsFeePageMethod = resolveSmsFeePageMethod(runtime),
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: SmsFeeBillingSymbols) {
        val deoptimized = runtime.module.deoptimize(resolution.smsFeePageMethod)
        if (!deoptimized) {
            Log.w(
                TelegramRuntime.TAG,
                "$id could not deoptimize ${resolution.smsFeePageMethod.declaringClass.name}.${resolution.smsFeePageMethod.name}; invoice hook may be inlined",
            )
        }

        runtime.hooks.around(resolution.useInvoiceBilling, id) {
            if (isCalledFrom(resolution.smsFeePageMethod)) true else proceed()
        }

        Log.i(
            TelegramRuntime.TAG,
            "$id installed smsFee=${resolution.smsFeePageMethod.declaringClass.name}.${resolution.smsFeePageMethod.name} deopt=$deoptimized",
        )
    }

    private fun resolveSmsFeePageMethod(runtime: TelegramRuntime): Method {
        val candidates = runtime.dexKit.useBridge { bridge ->
            bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("android.os.Bundle", "boolean")
                    usingStrings(
                        "product",
                        "phoneFormated",
                        "phoneHash",
                        "currency",
                        "premium_days",
                    )
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
            }
        }

        val verified = candidates.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(Bundle::class.java, Boolean::class.javaPrimitiveType),
                )
        }
        check(verified.size == 1) {
            "Expected one SMS fee page method, found ${verified.size} (${candidates.size} DexKit candidates)"
        }
        return verified.single().apply { isAccessible = true }
    }

    private fun isCalledFrom(method: Method): Boolean {
        val className = method.declaringClass.name
        val methodName = method.name
        return Thread.currentThread().stackTrace.any {
            it.className == className && it.methodName == methodName
        }
    }
}
