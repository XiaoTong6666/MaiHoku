package io.github.xiaotong6666.maihoku.hook.telegram

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SmsAuthProfileSymbols(
    val buildVars: BuildVarsSymbols,
    val codeSettingsAllowFirebase: Field,
    val codeSettingsSerialize: Method,
    val firebaseVerified: Field,
    val firebaseReadParams: Method,
)

internal object SmsAuthProfileFeature : TelegramFeature<SmsAuthProfileSymbols>() {
    override val id: String = "telegram.sms.auth_profile"

    override fun isEnabled(runtime: TelegramRuntime): Boolean =
        runtime.config.smsProfileEnabled

    override fun resolve(runtime: TelegramRuntime): SmsAuthProfileSymbols {
        val buildVars = StableSymbols.resolveBuildVars(runtime)

        val codeSettings = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_codeSettings",
            false,
            runtime.classLoader,
        )
        val allowFirebase = codeSettings.getDeclaredField("allow_firebase").apply {
            isAccessible = true
        }
        check(!Modifier.isStatic(allowFirebase.modifiers) && allowFirebase.type == Boolean::class.javaPrimitiveType) {
            "TL_codeSettings.allow_firebase shape mismatch"
        }
        val codeSettingsSerialize = uniqueVoidMethod(codeSettings, "serializeToStream", 1)

        val sentCodeType = Class.forName(
            "org.telegram.tgnet.TLRPC\$auth_SentCodeType",
            false,
            runtime.classLoader,
        )
        val verifiedFirebase = sentCodeType.getDeclaredField("verifiedFirebase").apply {
            isAccessible = true
        }
        check(
            !Modifier.isStatic(verifiedFirebase.modifiers) &&
                verifiedFirebase.type == Boolean::class.javaPrimitiveType,
        ) { "auth_SentCodeType.verifiedFirebase shape mismatch" }

        val firebaseSms = Class.forName(
            "org.telegram.tgnet.TLRPC\$TL_auth_sentCodeTypeFirebaseSms",
            false,
            runtime.classLoader,
        )
        val firebaseReadParams = uniqueVoidMethod(firebaseSms, "readParams", 2)

        return SmsAuthProfileSymbols(
            buildVars = buildVars,
            codeSettingsAllowFirebase = allowFirebase,
            codeSettingsSerialize = codeSettingsSerialize,
            firebaseVerified = verifiedFirebase,
            firebaseReadParams = firebaseReadParams,
        )
    }

    override fun install(runtime: TelegramRuntime, resolution: SmsAuthProfileSymbols) {
        val hookIds = ArrayList<String>()
        var capturedBuildVars = false
        var oldSafetyNetKey: String? = null
        var oldSupportsPasskeys = false

        fun applyBuildVarsProfile() {
            if (!capturedBuildVars) {
                oldSafetyNetKey = resolution.buildVars.safetyNetKey.get(null) as? String
                oldSupportsPasskeys = resolution.buildVars.supportsPasskeys.getBoolean(null)
                capturedBuildVars = true
            }

            resolution.buildVars.safetyNetKey.set(null, "")
            resolution.buildVars.supportsPasskeys.setBoolean(null, false)

            check(resolution.buildVars.safetyNetKey.get(null) == "") {
                "SAFETYNET_KEY write verification failed"
            }
            check(!resolution.buildVars.supportsPasskeys.getBoolean(null)) {
                "SUPPORTS_PASSKEYS write verification failed"
            }
        }

        try {
            val buildVarsHook = "$id.buildvars"
            runtime.hooks.classInitializer(resolution.buildVars.clazz, buildVarsHook) {
                after { applyBuildVarsProfile() }
            }
            hookIds += buildVarsHook
            applyBuildVarsProfile()

            val codeSettingsHook = "$id.code_settings"
            runtime.hooks.method(resolution.codeSettingsSerialize, codeSettingsHook) {
                before {
                    val settings = thisObject ?: return@before
                    resolution.codeSettingsAllowFirebase.setBoolean(settings, false)
                }
            }
            hookIds += codeSettingsHook

            val firebaseSmsHook = "$id.firebase_sms"
            runtime.hooks.method(resolution.firebaseReadParams, firebaseSmsHook) {
                after {
                    val type = thisObject ?: return@after
                    resolution.firebaseVerified.setBoolean(type, true)
                }
            }
            hookIds += firebaseSmsHook

            Log.i(TelegramRuntime.TAG, "$id installed")
        } catch (t: Throwable) {
            hookIds.asReversed().forEach(runtime.hooks::unhook)
            if (capturedBuildVars) {
                runCatching { resolution.buildVars.safetyNetKey.set(null, oldSafetyNetKey) }
                runCatching {
                    resolution.buildVars.supportsPasskeys.setBoolean(null, oldSupportsPasskeys)
                }
            }
            throw t
        }
    }

    private fun uniqueVoidMethod(clazz: Class<*>, name: String, parameterCount: Int): Method {
        val methods = clazz.declaredMethods.filter {
            it.name == name && it.parameterCount == parameterCount && it.returnType == Void.TYPE
        }
        check(methods.size == 1) {
            "${clazz.name}.$name expected one method, found ${methods.size}"
        }
        return methods.single().apply { isAccessible = true }
    }
}
