package io.github.xiaotong6666.maihoku.hook.telegram

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest

internal data class AuthRequestIdentitySymbols(
    val logicalId: String,
    val clazz: Class<*>,
    val apiId: Field,
    val apiHash: Field,
    val serializeToStream: Method,
)

internal object IdentityAuditFeature : TelegramFeature<List<AuthRequestIdentitySymbols>>() {
    override val id: String = "telegram.identity.audit"

    override fun resolve(runtime: TelegramRuntime): List<AuthRequestIdentitySymbols> = listOf(
        resolveRequest(
            runtime = runtime,
            logicalId = "send_code",
            className = "org.telegram.tgnet.TLRPC\$TL_auth_sendCode",
        ),
        resolveRequest(
            runtime = runtime,
            logicalId = "export_login_token",
            className = "org.telegram.tgnet.TLRPC\$TL_auth_exportLoginToken",
        ),
    )

    override fun install(
        runtime: TelegramRuntime,
        resolution: List<AuthRequestIdentitySymbols>,
    ) {
        val installedIds = ArrayList<String>(resolution.size)
        try {
            for (request in resolution) {
                val hookId = "telegram.identity.audit.${request.logicalId}"
                runtime.hooks.method(request.serializeToStream, hookId) {
                    before {
                        val instance = thisObject ?: return@before
                        val apiId = request.apiId.getInt(instance)
                        val apiHash = (request.apiHash.get(instance) as? String).orEmpty()
                        Log.i(
                            TelegramRuntime.TAG,
                            "identity.audit ${request.logicalId} apiId=$apiId hash=${fingerprint(apiHash)}",
                        )
                    }
                }
                installedIds += hookId
            }
        } catch (t: Throwable) {
            installedIds.asReversed().forEach(runtime.hooks::unhook)
            throw t
        }
    }

    private fun resolveRequest(
        runtime: TelegramRuntime,
        logicalId: String,
        className: String,
    ): AuthRequestIdentitySymbols {
        val clazz = Class.forName(className, false, runtime.classLoader)
        val apiId = clazz.getDeclaredField("api_id").apply { isAccessible = true }
        val apiHash = clazz.getDeclaredField("api_hash").apply { isAccessible = true }

        check(!Modifier.isStatic(apiId.modifiers) && apiId.type == Int::class.javaPrimitiveType) {
            "$className.api_id shape mismatch"
        }
        check(!Modifier.isStatic(apiHash.modifiers) && apiHash.type == String::class.java) {
            "$className.api_hash shape mismatch"
        }

        val serializers = clazz.declaredMethods.filter {
            it.name == "serializeToStream" &&
                it.parameterCount == 1 &&
                it.returnType == Void.TYPE
        }
        check(serializers.size == 1) {
            "$className expected one serializeToStream method, found ${serializers.size}"
        }

        return AuthRequestIdentitySymbols(
            logicalId = logicalId,
            clazz = clazz,
            apiId = apiId,
            apiHash = apiHash,
            serializeToStream = serializers.single().apply { isAccessible = true },
        )
    }

    private fun fingerprint(value: String): String {
        if (value.isEmpty()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
