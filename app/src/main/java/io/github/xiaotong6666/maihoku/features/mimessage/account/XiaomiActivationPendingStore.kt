package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.content.Context
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig

/** Short-lived SMS verification checkpoint, kept only in MaiHoku's private preferences. */
internal data class XiaomiPendingActivation(
    val attempt: SimActivationAttempt,
    val device: XiaomiDeviceIdentifiers,
    /** Exact Mixin sim_id derived from the currently selected physical SIM. */
    val mixinSimId: String,
    val phoneType: Int,
    val vkeys: XiaomiVKeyPair,
    val smsState: String,
) {
    override fun toString(): String = "XiaomiPendingActivation(slot=${attempt.slotIndex}, subscriptionId=${attempt.subscriptionId}, " +
        "mixinSimId=redacted, simIdHash=masked, smsState=$smsState)"
}

internal object XiaomiActivationPendingStore {
    private fun key(slot: Int, field: String) = MiMessageConfig.simKey(slot, "pending_$field")

    fun save(
        context: Context,
        attempt: SimActivationAttempt,
        device: XiaomiDeviceIdentifiers,
        mixinSimId: String,
        phoneType: Int,
        vkeys: XiaomiVKeyPair,
    ): Boolean {
        require(attempt.slotIndex in 0..1)
        require(mixinSimId.startsWith("1,") || mixinSimId.startsWith("2,")) {
            "Mixin sim_id must use the original MiuiMms slot prefix"
        }
        return MiMessageCredentialStore.putAll(
            context,
            mapOf(
                key(attempt.slotIndex, "sim_hash") to attempt.simIdHash,
                key(attempt.slotIndex, "mixin_sim_id") to mixinSimId,
                key(attempt.slotIndex, "phone_type") to phoneType.toString(),
                key(attempt.slotIndex, "device_hash") to attempt.deviceIdHash,
                key(attempt.slotIndex, "device_source") to device.source,
                key(attempt.slotIndex, "oaid") to device.oaid.orEmpty(),
                key(attempt.slotIndex, "subscription_id") to attempt.subscriptionId.toString(),
                key(attempt.slotIndex, "mcc_mnc") to attempt.mccMnc,
                key(attempt.slotIndex, "activation_host") to attempt.activationHost,
                key(attempt.slotIndex, "gateway") to attempt.gatewayNumber.orEmpty(),
                key(attempt.slotIndex, "trace_id") to attempt.traceId,
                key(attempt.slotIndex, "t2") to attempt.t2,
                key(attempt.slotIndex, "feature") to attempt.feature.toString(),
                key(attempt.slotIndex, "vkey1") to vkeys.vkey1,
                key(attempt.slotIndex, "vkey2") to vkeys.vkey2,
                key(attempt.slotIndex, "sms_state") to "prepared",
            ),
        )
    }

    fun load(context: Context, slot: Int): XiaomiPendingActivation? {
        require(slot in 0..1)
        fun value(name: String) = MiMessageCredentialStore.get(context, key(slot, name))
        val simHash = value("sim_hash") ?: return null
        val mixinSimId = value("mixin_sim_id")?.takeIf { it.startsWith("1,") || it.startsWith("2,") } ?: return null
        // Old checkpoints did not persist phoneType; -1 makes them fail closed
        // when compared with a newly reported privileged SIM identity.
        val phoneType = value("phone_type")?.toIntOrNull() ?: -1
        val deviceHash = value("device_hash") ?: return null
        val subscriptionId = value("subscription_id")?.toIntOrNull() ?: return null
        val mccMnc = value("mcc_mnc") ?: return null
        val host = value("activation_host") ?: return null
        val traceId = value("trace_id") ?: return null
        val t2 = value("t2") ?: return null
        val feature = value("feature")?.toIntOrNull() ?: return null
        val vkey1 = value("vkey1") ?: return null
        val vkey2 = value("vkey2") ?: return null
        val attempt = runCatching {
            SimActivationAttempt(
                slotIndex = slot,
                subscriptionId = subscriptionId,
                simIdHash = simHash,
                deviceIdHash = deviceHash,
                mccMnc = mccMnc,
                activationHost = host,
                gatewayNumber = value("gateway")?.takeIf(String::isNotBlank),
                traceId = traceId,
                t2 = t2,
                feature = feature,
            )
        }.getOrNull() ?: return null
        val vkeys = runCatching { XiaomiVKeyPair(vkey1, vkey2) }.getOrNull() ?: return null
        val device = XiaomiDeviceIdentifiers(
            deviceIdHash = deviceHash,
            source = value("device_source") ?: "restored_pending_attempt",
            oaid = value("oaid")?.takeIf(String::isNotBlank),
        )
        return XiaomiPendingActivation(attempt, device, mixinSimId, phoneType, vkeys, value("sms_state") ?: "prepared")
    }

    fun matchesIdentity(pending: XiaomiPendingActivation, identity: XiaomiSimIdentity): Boolean = XiaomiSimIdentityTransferPolicy.matchesPendingIdentity(
        pending.attempt.slotIndex,
        pending.attempt.subscriptionId,
        pending.attempt.mccMnc,
        pending.attempt.simIdHash,
        pending.mixinSimId,
        pending.phoneType,
        identity,
    )

    fun markSmsState(context: Context, slot: Int, state: String): Boolean {
        require(slot in 0..1)
        require(state in setOf("prepared", "sending", "sent", "failed"))
        return MiMessageCredentialStore.put(context, key(slot, "sms_state"), state)
    }

    fun clear(context: Context, slot: Int): Boolean {
        require(slot in 0..1)
        val fields = listOf(
            "sim_hash", "mixin_sim_id", "phone_type", "device_hash", "device_source", "oaid", "subscription_id", "mcc_mnc",
            "activation_host", "gateway", "trace_id", "t2", "feature", "vkey1", "vkey2", "sms_state",
        )
        return MiMessageCredentialStore.putAll(context, fields.associate { key(slot, it) to "" })
    }
}
