package io.github.xiaotong6666.maihoku.hook.telegram

import android.util.Log
import java.security.MessageDigest

internal object ApiIdentityFeature : TelegramFeature<BuildVarsSymbols>() {
    override val id: String = "telegram.identity.buildvars"

    override fun isEnabled(runtime: TelegramRuntime): Boolean = runtime.config.hasValidIdentity

    override fun resolve(runtime: TelegramRuntime): BuildVarsSymbols =
        StableSymbols.resolveBuildVars(runtime)

    override fun install(runtime: TelegramRuntime, resolution: BuildVarsSymbols) {
        runtime.hooks.classInitializer(resolution.clazz, id) {
            after {
                applyIdentity(runtime, resolution)
            }
        }

        try {
            // Static reflective field access triggers <clinit> when it has not run yet. The hook is
            // already installed, so this also covers the not-yet-initialized case. If Telegram already
            // initialized BuildVars before PackageReady, this write is the fallback path.
            applyIdentity(runtime, resolution)
        } catch (t: Throwable) {
            runtime.hooks.unhook(id)
            throw t
        }
    }

    private fun applyIdentity(runtime: TelegramRuntime, symbols: BuildVarsSymbols) {
        val config = runtime.config
        check(config.hasValidIdentity) { "Telegram API identity is not configured" }

        symbols.appId.setInt(null, config.apiId)
        symbols.appHash.set(null, config.apiHash)

        check(symbols.appId.getInt(null) == config.apiId) { "APP_ID write verification failed" }
        check(symbols.appHash.get(null) == config.apiHash) { "APP_HASH write verification failed" }

        Log.i(
            TelegramRuntime.TAG,
            "$id applied apiId=${config.apiId} hash=${fingerprint(config.apiHash)}",
        )
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
