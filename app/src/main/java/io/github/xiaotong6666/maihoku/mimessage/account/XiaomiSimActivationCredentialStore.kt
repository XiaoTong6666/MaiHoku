package io.github.xiaotong6666.maihoku.mimessage.account

import android.content.Context
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig

/** Persists activation and Mixin identities without coupling storage to HTTP protocol logic. */
internal object XiaomiSimActivationCredentialStore {
    fun saveSimActivation(
        context: Context,
        slot: Int,
        simIdHash: String,
        activationDeviceIdHash: String,
        phone: String,
        mixinSimId: String,
        credentials: XiaomiSimUserCredentials,
    ): Boolean = MiMessageCredentialStore.putAll(
        context,
        buildSimActivationValues(slot, simIdHash, activationDeviceIdHash, phone, mixinSimId, credentials),
    )

    /** A new activation identity invalidates credentials belonging to the previous identity. */
    internal fun buildSimActivationValues(
        slot: Int,
        simIdHash: String,
        activationDeviceIdHash: String,
        phone: String,
        mixinSimId: String,
        credentials: XiaomiSimUserCredentials,
    ): Map<String, String> {
        require(slot in 0..1)
        require(simIdHash.matches(Regex("[A-Za-z0-9_-]{16}")))
        require(activationDeviceIdHash.matches(Regex("[A-Za-z0-9_-]{16}")))
        require(mixinSimId.startsWith("1,") || mixinSimId.startsWith("2,"))
        return mapOf(
            // Mixin's original SIM ID differs from activation_info.simId's 16-character hash.
            MiMessageConfig.simKey(slot, "sim_id") to mixinSimId,
            MiMessageConfig.simKey(slot, "identity_source") to "maihoku_sim_activation",
            MiMessageConfig.simKey(slot, "identity_state") to MiMessageIdentityReadiness.AWAITING_MIXIN_CREDENTIALS,
            MiMessageConfig.simKey(slot, "mid") to "",
            MiMessageConfig.simKey(slot, "token") to "",
            MiMessageConfig.simKey(slot, "security") to "",
            MiMessageConfig.simKey(slot, "user_cid") to "",
            MiMessageConfig.simKey(slot, "resource") to "",
            MiMessageConfig.simKey(slot, "activation_sim_id_hash") to simIdHash,
            // Service-token exchange must reuse the device identity used to activate this SIM.
            MiMessageConfig.simKey(slot, "activation_device_id_hash") to activationDeviceIdHash,
            MiMessageConfig.simKey(slot, "phone") to phone,
            MiMessageConfig.simKey(slot, "sim_user_id") to credentials.userId,
            MiMessageConfig.simKey(slot, "sim_pass_token") to credentials.passToken,
            MiMessageConfig.simKey(slot, "target_mixin_sim_id") to "",
            MiMessageConfig.simKey(slot, "target_sim_id_hash") to "",
            MiMessageConfig.simKey(slot, "target_subscription_id") to "",
            MiMessageConfig.simKey(slot, "target_phone_type") to "",
            MiMessageConfig.simKey(slot, "target_mcc_mnc") to "",
            MiMessageConfig.simKey(slot, "target_sim_state") to "",
            MiMessageConfig.simKey(slot, "target_iccid_length") to "",
            MiMessageConfig.simKey(slot, "target_imsi_length") to "",
            MiMessageConfig.simKey(slot, "target_source") to "",
            MiMessageConfig.simKey(slot, "target_source_package") to "",
            MiMessageConfig.simKey(slot, "target_source_uid") to "",
            MiMessageConfig.simKey(slot, "target_timestamp") to "",
        )
    }

    fun saveMixinCredentials(context: Context, slot: Int, credentials: XiaomiMixinServiceCredentials, resource: String): Boolean {
        val source = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "identity_source"))
        val simId = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "sim_id"))
        return MiMessageCredentialStore.putAll(context, buildMixinCredentialValues(slot, credentials, resource, source, simId))
    }

    /** An activated identity becomes usable only after its Mixin credentials are saved. */
    internal fun buildMixinCredentialValues(
        slot: Int,
        credentials: XiaomiMixinServiceCredentials,
        resource: String,
        identitySource: String?,
        existingMixinSimId: String?,
    ): Map<String, String> {
        require(slot in 0..1)
        require(resource.isNotBlank())
        val values = linkedMapOf(
            MiMessageConfig.simKey(slot, "mid") to credentials.mid,
            MiMessageConfig.simKey(slot, "token") to credentials.userToken,
            MiMessageConfig.simKey(slot, "security") to credentials.userSecurity,
            MiMessageConfig.simKey(slot, "user_cid") to credentials.userCid,
            MiMessageConfig.simKey(slot, "resource") to resource,
        )
        if (identitySource == "maihoku_sim_activation") {
            check(!existingMixinSimId.isNullOrBlank()) { "SIM activation identity is missing its Mixin sim_id" }
            values[MiMessageConfig.simKey(slot, "identity_state")] = MiMessageIdentityReadiness.READY
        }
        return values
    }

    fun loadSimUser(context: Context, slot: Int, expectedSimIdHash: String): XiaomiSimUserCredentials? {
        require(slot in 0..1)
        val simId = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash")) ?: return null
        if (simId != expectedSimIdHash) {
            throw XiaomiActivationException("SIM_ACTIVATION_IDENTITY_MISMATCH", "Stored activation credentials belong to a different SIM identity")
        }
        val userId = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "sim_user_id")) ?: return null
        val passToken = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "sim_pass_token")) ?: return null
        if (userId.isBlank() || passToken.isBlank()) return null
        return XiaomiSimUserCredentials(userId, passToken)
    }

    fun getOrCreateResource(context: Context, slot: Int): String {
        require(slot in 0..1)
        val key = MiMessageConfig.simKey(slot, "resource")
        MiMessageCredentialStore.get(context, key)?.takeIf(String::isNotBlank)?.let { return it }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        val resource = buildString(16) { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
        check(MiMessageCredentialStore.put(context, key, resource)) { "Could not save the Mixin resource identifier" }
        return resource
    }
}
