package io.github.xiaotong6666.maihoku.hook.telegram

import io.github.xiaotong6666.maihoku.BuildConfig

internal data class TelegramConfig(
    val identityEnabled: Boolean,
    val apiId: Int,
    val apiHash: String,
    val smsProfileEnabled: Boolean,
    val mutualContactEnabled: Boolean,
    val profileIdentityEnabled: Boolean,
) {
    val hasValidIdentity: Boolean
        get() = identityEnabled && apiId > 0 && apiHash.isNotBlank()

    companion object {
        fun load(runtime: TelegramRuntime): TelegramConfig {
            val prefs = runCatching { runtime.module.getRemotePreferences("telegram") }.getOrNull()
            val buildIdentityEnabled = BuildConfig.TELEGRAM_IDENTITY_CONFIGURED &&
                BuildConfig.TELEGRAM_API_ID > 0 &&
                BuildConfig.TELEGRAM_API_HASH.isNotBlank()

            return TelegramConfig(
                identityEnabled = prefs?.getBoolean("identity.enabled", buildIdentityEnabled)
                    ?: buildIdentityEnabled,
                apiId = prefs?.getInt("identity.api_id", BuildConfig.TELEGRAM_API_ID)
                    ?: BuildConfig.TELEGRAM_API_ID,
                apiHash = prefs?.getString("identity.api_hash", BuildConfig.TELEGRAM_API_HASH)
                    ?.takeIf(String::isNotBlank)
                    ?: BuildConfig.TELEGRAM_API_HASH,
                smsProfileEnabled = prefs?.getBoolean("sms.profile_enabled", true) ?: true,
                mutualContactEnabled = prefs?.getBoolean("ui.mutual_contact_enabled", true) ?: true,
                profileIdentityEnabled = prefs?.getBoolean(
                    "ui.profile_identity_enabled",
                    prefs.getBoolean("ui.user_card_dc_enabled", true),
                ) ?: true,
            )
        }
    }
}
