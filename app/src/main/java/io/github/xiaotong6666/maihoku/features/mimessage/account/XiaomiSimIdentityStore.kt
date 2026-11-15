package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig

internal data class XiaomiCurrentSimSubscription(
    val slotIndex: Int,
    val subscriptionId: Int,
    val mccMnc: String,
)

/** Receives only derived SIM identity material from a privileged LSPosed target process. */
internal object XiaomiSimIdentityStore {
    const val EXTRA_SLOT = "sim_identity_slot"
    const val EXTRA_SUBSCRIPTION_ID = "sim_identity_subscription_id"
    const val EXTRA_PHONE_TYPE = "sim_identity_phone_type"
    const val EXTRA_MIXIN_SIM_ID = "sim_identity_mixin_sim_id"
    const val EXTRA_SIM_ID_HASH = "sim_identity_activation_hash"
    const val EXTRA_MCC_MNC = "sim_identity_mcc_mnc"
    const val EXTRA_SIM_STATE = "sim_identity_state"
    const val EXTRA_ICCID_LENGTH = "sim_identity_iccid_length"
    const val EXTRA_IMSI_LENGTH = "sim_identity_imsi_length"
    const val EXTRA_SOURCE = "sim_identity_api_source"
    const val EXTRA_SOURCE_PACKAGE = "sim_identity_source_package"
    const val EXTRA_SOURCE_UID = "sim_identity_source_uid"
    const val EXTRA_INVALIDATION_REASON = "sim_identity_invalidation_reason"
    const val EXTRA_INVALIDATION_REQUESTED_AT = "sim_identity_invalidation_requested_at"

    // A fresh privileged snapshot is required. SubscriptionManager provides an
    // independent current-subscription check before any activation operation.
    private const val FRESHNESS_MS = 2 * 60 * 1000L

    fun importFromIntent(context: Context, intent: Intent, authenticatedSenderUid: Int): XiaomiSimIdentity {
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
        val phoneType = intent.getIntExtra(EXTRA_PHONE_TYPE, -1)
        val mixinSimId = intent.getStringExtra(EXTRA_MIXIN_SIM_ID).orEmpty()
        val simIdHash = intent.getStringExtra(EXTRA_SIM_ID_HASH).orEmpty()
        val mccMnc = intent.getStringExtra(EXTRA_MCC_MNC).orEmpty()
        val simState = intent.getIntExtra(EXTRA_SIM_STATE, -1)
        val iccidLength = intent.getIntExtra(EXTRA_ICCID_LENGTH, 0)
        val imsiLength = intent.getIntExtra(EXTRA_IMSI_LENGTH, 0)
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        val claimedUid = intent.getIntExtra(EXTRA_SOURCE_UID, -1)
        val packagesForUid = context.packageManager.getPackagesForUid(authenticatedSenderUid).orEmpty().toSet()

        require(slot in 0..1) { "Invalid SIM slot" }
        require(subscriptionId > 0) { "Invalid Subscription ID" }
        require(phoneType == 1 || phoneType == 2) { "Unsupported SIM phone type" }
        require(simState == 5) { "SIM is not READY" }
        require(simIdHash.matches(Regex("[A-Za-z0-9_-]{16}"))) { "Invalid activation SIM hash" }
        require(mccMnc.matches(Regex("\\d{5,6}"))) { "Invalid SIM MCC/MNC" }
        require(XiaomiSimIdentityTransferPolicy.isTrustedSource(sourcePackage) && sourcePackage in packagesForUid) {
            "Broadcast sender does not match the claimed privileged target package"
        }
        require(claimedUid == authenticatedSenderUid) { "Broadcast sender UID does not match target process UID" }
        require(source.isNotBlank()) { "Missing SIM identity source" }

        require(XiaomiSimIdentityTransferPolicy.isValidMixinSimId(phoneType, mixinSimId, iccidLength, imsiLength)) {
            "Mixin sim_id does not match the original phoneType/identifier rule"
        }

        val current = currentSubscription(context, slot)
        if (!XiaomiSimIdentityTransferPolicy.matchesCurrentSubscription(
                subscriptionId,
                mccMnc,
                current.subscriptionId,
                current.mccMnc,
            )
        ) {
            throw XiaomiActivationException(
                "SIM_IDENTITY_IMPORT_SUBSCRIPTION_MISMATCH",
                "Privileged identity broadcast no longer matches the active subscription",
            )
        }

        val identity = XiaomiSimIdentity(
            slotIndex = slot,
            subscriptionId = subscriptionId,
            phoneType = phoneType,
            mixinSimId = mixinSimId,
            simIdHash = simIdHash,
            mccMnc = mccMnc,
            simState = simState,
            iccidLength = iccidLength,
            imsiLength = imsiLength,
            source = source,
        )
        check(save(context, identity, sourcePackage, authenticatedSenderUid)) {
            "Could not save privileged target SIM identity"
        }
        return identity
    }

    fun invalidateFromIntent(context: Context, intent: Intent, authenticatedSenderUid: Int): Boolean {
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        val claimedUid = intent.getIntExtra(EXTRA_SOURCE_UID, -1)
        val packagesForUid = context.packageManager.getPackagesForUid(authenticatedSenderUid).orEmpty().toSet()
        require(slot in 0..1) { "Invalid SIM slot" }
        require(XiaomiSimIdentityTransferPolicy.isTrustedSource(sourcePackage) && sourcePackage in packagesForUid) {
            "Invalidation sender does not match the claimed privileged target package"
        }
        require(claimedUid == authenticatedSenderUid) { "Invalidation sender UID does not match target process UID" }
        val requestedAt = intent.getLongExtra(EXTRA_INVALIDATION_REQUESTED_AT, 0L)
        val storedAt = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "target_timestamp"))
            ?.toLongOrNull() ?: 0L
        if (requestedAt > 0L && storedAt > requestedAt) {
            // A newer identity import overtook this invalidation broadcast.
            return false
        }
        check(clear(context, slot)) { "Could not clear stale privileged SIM identity" }
        return true
    }

    fun load(context: Context, slot: Int): XiaomiSimIdentity? {
        require(slot in 0..1)
        fun value(field: String) = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "target_$field"))
        val timestamp = value("timestamp")?.toLongOrNull() ?: return null
        val age = System.currentTimeMillis() - timestamp
        if (age !in 0..FRESHNESS_MS) return null
        val sourcePackage = sourcePackage(context, slot)
        val sourceUid = sourceUid(context, slot)
        if (sourcePackage == null || !XiaomiSimIdentityTransferPolicy.isTrustedSource(sourcePackage) || sourceUid == null || sourceUid <= 0) {
            return null
        }
        val identity = runCatching {
            XiaomiSimIdentity(
                slotIndex = slot,
                subscriptionId = value("subscription_id")?.toInt() ?: return null,
                phoneType = value("phone_type")?.toInt() ?: return null,
                mixinSimId = value("mixin_sim_id") ?: return null,
                simIdHash = value("sim_id_hash") ?: return null,
                mccMnc = value("mcc_mnc") ?: return null,
                simState = value("sim_state")?.toInt() ?: return null,
                iccidLength = value("iccid_length")?.toInt() ?: return null,
                imsiLength = value("imsi_length")?.toInt() ?: return null,
                source = value("source") ?: return null,
            )
        }.getOrNull() ?: return null
        return identity.takeIf { validateStored(it) }
    }

    fun currentSubscription(context: Context, slot: Int): XiaomiCurrentSimSubscription {
        require(slot in 0..1)
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") != PackageManager.PERMISSION_GRANTED
        ) {
            throw XiaomiActivationException(
                "SIM_CURRENT_SUBSCRIPTION_PERMISSION_REQUIRED",
                "MaiHoku needs READ_PHONE_STATE to verify the current subscription before using a cached privileged SIM identity",
            )
        }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            ?: throw XiaomiActivationException("SIM_CURRENT_SUBSCRIPTION_MANAGER_UNAVAILABLE", "SubscriptionManager is unavailable")
        // Do not gate on TelephonyManager.getSimState(slot): some AOSP/vendor
        // multi-SIM builds report ABSENT for that slot-index overload while the
        // active SubscriptionInfo and per-slot gsm.sim.state are already LOADED.
        // The active subscription is the authoritative slot mapping here; the
        // separately imported com.android.phone snapshot must still be READY
        // (simState=5), fresh, and match this exact subscription below.
        val info = runCatching {
            subscriptions.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slot && it.subscriptionId > 0 }
        }.getOrElse {
            throw XiaomiActivationException(
                "SIM_CURRENT_SUBSCRIPTION_QUERY_FAILED",
                "Could not enumerate active subscriptions for SIM slot $slot",
                it,
            )
        }
            ?: throw XiaomiActivationException(
                "SIM_CURRENT_SUBSCRIPTION_UNAVAILABLE",
                "No active subscription is mapped to current SIM slot $slot",
            )
        val infoOperator = (
            if (Build.VERSION.SDK_INT >= 29) {
                info.mccString.orEmpty() + info.mncString.orEmpty()
            } else {
                String.format(java.util.Locale.US, "%03d%02d", info.mcc, info.mnc)
            }
            )
            .takeIf { it.matches(Regex("\\d{5,6}")) }
        val mccMnc = infoOperator
            ?: throw XiaomiActivationException("SIM_CURRENT_MCC_MNC_UNAVAILABLE", "Could not read current SIM MCC/MNC")
        return XiaomiCurrentSimSubscription(slot, info.subscriptionId, mccMnc)
    }

    fun loadForCurrentSubscription(context: Context, slot: Int): XiaomiSimIdentity {
        val current = currentSubscription(context, slot)
        val stored = load(context, slot) ?: run {
            clear(context, slot)
            throw XiaomiActivationException(
                "SIM_IDENTITY_PRIVILEGED_SNAPSHOT_MISSING",
                "Waiting for com.android.phone to report a fresh privileged SIM identity",
            )
        }
        if (!XiaomiSimIdentityTransferPolicy.matchesCurrentSubscription(
                stored.subscriptionId,
                stored.mccMnc,
                current.subscriptionId,
                current.mccMnc,
            )
        ) {
            clear(context, slot)
            throw XiaomiActivationException(
                "SIM_IDENTITY_STALE_SUBSCRIPTION",
                "Cached privileged SIM identity belongs to a different subscription; stale identity was cleared",
            )
        }
        return stored
    }

    fun sourcePackage(context: Context, slot: Int): String? = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "target_source_package"))

    fun sourceUid(context: Context, slot: Int): Int? = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "target_source_uid"))?.toIntOrNull()

    fun ageMillis(context: Context, slot: Int): Long? = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "target_timestamp"))
        ?.toLongOrNull()?.let { System.currentTimeMillis() - it }

    fun clear(context: Context, slot: Int): Boolean {
        require(slot in 0..1)
        return MiMessageCredentialStore.putAll(
            context,
            TARGET_FIELDS.associate {
                MiMessageConfig.simKey(slot, "target_$it") to ""
            },
        )
    }

    private fun save(context: Context, identity: XiaomiSimIdentity, sourcePackage: String, sourceUid: Int): Boolean {
        val currentPackage = sourcePackage(context, identity.slotIndex)
        val currentAge = ageMillis(context, identity.slotIndex)
        val currentHash = MiMessageCredentialStore.get(
            context,
            MiMessageConfig.simKey(identity.slotIndex, "target_sim_id_hash"),
        )
        val currentSubscriptionId = MiMessageCredentialStore.get(
            context,
            MiMessageConfig.simKey(identity.slotIndex, "target_subscription_id"),
        )?.toIntOrNull()
        val currentMccMnc = MiMessageCredentialStore.get(
            context,
            MiMessageConfig.simKey(identity.slotIndex, "target_mcc_mnc"),
        )
        val sameSubscription = currentSubscriptionId == identity.subscriptionId && currentMccMnc == identity.mccMnc
        if (currentPackage != null && currentAge != null && currentAge in 0..FRESHNESS_MS &&
            sameSubscription &&
            !XiaomiSimIdentityTransferPolicy.shouldReplace(currentPackage, sourcePackage, currentHash.orEmpty(), identity.simIdHash)
        ) {
            return true
        }

        val slot = identity.slotIndex
        return MiMessageCredentialStore.putAll(
            context,
            mapOf(
                MiMessageConfig.simKey(slot, "target_mixin_sim_id") to identity.mixinSimId,
                MiMessageConfig.simKey(slot, "target_sim_id_hash") to identity.simIdHash,
                MiMessageConfig.simKey(slot, "target_subscription_id") to identity.subscriptionId.toString(),
                MiMessageConfig.simKey(slot, "target_phone_type") to identity.phoneType.toString(),
                MiMessageConfig.simKey(slot, "target_mcc_mnc") to identity.mccMnc,
                MiMessageConfig.simKey(slot, "target_sim_state") to identity.simState.toString(),
                MiMessageConfig.simKey(slot, "target_iccid_length") to identity.iccidLength.toString(),
                MiMessageConfig.simKey(slot, "target_imsi_length") to identity.imsiLength.toString(),
                MiMessageConfig.simKey(slot, "target_source") to identity.source,
                MiMessageConfig.simKey(slot, "target_source_package") to sourcePackage,
                MiMessageConfig.simKey(slot, "target_source_uid") to sourceUid.toString(),
                MiMessageConfig.simKey(slot, "target_timestamp") to System.currentTimeMillis().toString(),
            ),
        )
    }

    private fun validateStored(identity: XiaomiSimIdentity): Boolean = identity.phoneType in 1..2 && identity.simState == 5 &&
        identity.subscriptionId > 0 && identity.simIdHash.matches(Regex("[A-Za-z0-9_-]{16}")) &&
        identity.mccMnc.matches(Regex("\\d{5,6}")) &&
        XiaomiSimIdentityTransferPolicy.isValidMixinSimId(
            identity.phoneType,
            identity.mixinSimId,
            identity.iccidLength,
            identity.imsiLength,
        )
    private val TARGET_FIELDS = listOf(
        "mixin_sim_id", "sim_id_hash", "subscription_id", "phone_type", "mcc_mnc", "sim_state",
        "iccid_length", "imsi_length", "source", "source_package", "source_uid", "timestamp",
    )
}

/** Pure transfer policy so the source-UID trust and SIM ID mapping can be unit-tested. */
internal object XiaomiSimIdentityTransferPolicy {
    fun isTrustedSource(packageName: String): Boolean = packageName == "com.android.mms" || packageName == "com.android.phone"

    fun isValidMixinSimId(phoneType: Int, mixinSimId: String, iccidLength: Int, imsiLength: Int): Boolean {
        val expectedPrefix = when (phoneType) {
            1 -> "2,"
            2 -> "1,"
            else -> return false
        }
        val selectedLength = if (phoneType == 1) imsiLength else iccidLength
        return selectedLength in 10..22 &&
            mixinSimId.startsWith(expectedPrefix) &&
            mixinSimId.length == selectedLength + 2 &&
            mixinSimId.substring(2).all(Char::isDigit)
    }

    fun shouldReplace(currentPackage: String, incomingPackage: String, currentSimHash: String, incomingSimHash: String): Boolean = currentSimHash != incomingSimHash || sourcePriority(incomingPackage) >= sourcePriority(currentPackage)

    fun matchesCurrentSubscription(
        identitySubscriptionId: Int,
        identityMccMnc: String,
        currentSubscriptionId: Int,
        currentMccMnc: String,
    ): Boolean = identitySubscriptionId == currentSubscriptionId && identityMccMnc == currentMccMnc

    fun matchesPendingIdentity(
        pendingSlot: Int,
        pendingSubscriptionId: Int,
        pendingMccMnc: String,
        pendingSimHash: String,
        pendingMixinSimId: String,
        pendingPhoneType: Int,
        identity: XiaomiSimIdentity,
    ): Boolean = pendingSlot == identity.slotIndex &&
        pendingSubscriptionId == identity.subscriptionId &&
        pendingMccMnc == identity.mccMnc &&
        pendingSimHash == identity.simIdHash &&
        pendingMixinSimId == identity.mixinSimId &&
        pendingPhoneType == identity.phoneType

    private fun sourcePriority(packageName: String): Int = when (packageName) {
        "com.android.mms" -> 100

        // MIUI Messages: exact OEM slot API and original call site.
        "com.android.phone" -> 50

        // AOSP/Radio process: privileged Telephony fallback.
        else -> 0
    }
}
