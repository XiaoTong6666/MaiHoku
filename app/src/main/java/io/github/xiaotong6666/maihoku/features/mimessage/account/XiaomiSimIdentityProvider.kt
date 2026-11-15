package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

internal data class XiaomiSimIdentity(
    val slotIndex: Int,
    val subscriptionId: Int,
    val phoneType: Int,
    /** Original MiuiMms/of.b Mixin identifier ("1," + ICCID or "2," + IMSI). */
    val mixinSimId: String,
    /** SimActivateService activation_info.simId: SHA1/Base64URL/16 of ICCID. */
    val simIdHash: String,
    val mccMnc: String,
    val simState: Int,
    val iccidLength: Int,
    val imsiLength: Int,
    val source: String,
) {
    override fun toString(): String = "XiaomiSimIdentity(slot=$slotIndex, subscriptionId=$subscriptionId, phoneType=$phoneType, " +
        "simIdHash=masked, mccMnc=$mccMnc, source=$source)"
}

/** Reproduces MiuiMms/of.b's Mixin sim_id selection and SimActivateService's ICCID hash. */
internal object XiaomiSimIdentityProvider {
    private val privilegedTargetPackages = setOf("com.android.mms", "com.android.phone")

    /**
     * Entry point used only by LSPosed code executing inside a privileged target process.
     * The UID is read from the current process and checked against the target package's UID;
     * no su/root child process and no MaiHoku-UID privileged read is involved.
     */
    fun resolveFromPrivilegedTarget(
        context: Context,
        slotIndex: Int,
        targetClassLoader: ClassLoader,
    ): XiaomiSimIdentity {
        val packageName = context.packageName
        if (packageName !in privilegedTargetPackages) {
            throw XiaomiActivationException(
                "SIM_IDENTITY_WRONG_TARGET_PACKAGE",
                "SIM identity must be read inside com.android.mms or com.android.phone",
            )
        }
        val processUid = Process.myUid()
        if (processUid != context.applicationInfo.uid) {
            throw XiaomiActivationException(
                "SIM_IDENTITY_TARGET_UID_MISMATCH",
                "Current process UID does not match the privileged target package UID",
            )
        }
        if (context.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") != PackageManager.PERMISSION_GRANTED) {
            throw XiaomiActivationException(
                "TARGET_PRIVILEGED_PHONE_STATE_NOT_GRANTED",
                "$packageName does not hold READ_PRIVILEGED_PHONE_STATE in this ROM",
            )
        }
        return resolve(context, slotIndex, targetClassLoader)
    }

    fun resolve(context: Context, slotIndex: Int, targetClassLoader: ClassLoader? = null): XiaomiSimIdentity {
        require(slotIndex in 0..1) { "Only physical SIM slots 0 and 1 are supported" }
        val hasReadPhoneState = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPrivilegedPhoneState = context.checkSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") == PackageManager.PERMISSION_GRANTED
        if (!hasReadPhoneState && !hasPrivilegedPhoneState) {
            throw XiaomiActivationException(
                "READ_PHONE_STATE_PERMISSION_REQUIRED",
                "The target process needs READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE to enumerate the selected SIM subscription",
            )
        }

        val baseTelephony = context.getSystemService(TelephonyManager::class.java)
            ?: throw XiaomiActivationException("TELEPHONY_MANAGER_UNAVAILABLE", "TelephonyManager is unavailable")
        val slotSimState = runCatching { baseTelephony.getSimState(slotIndex) }.getOrElse {
            throw XiaomiActivationException("SIM_STATE_UNAVAILABLE", "Could not read SIM state for slot $slotIndex", it)
        }
        if (slotSimState == TelephonyManager.SIM_STATE_ABSENT) {
            throw XiaomiActivationException("SIM_NOT_PRESENT", "No SIM card is present in slot $slotIndex")
        }
        if (slotSimState != TelephonyManager.SIM_STATE_READY) {
            throw XiaomiActivationException("SIM_NOT_READY", "SIM slot $slotIndex is not READY (state=$slotSimState)")
        }

        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            ?: throw XiaomiActivationException("SUBSCRIPTION_MANAGER_UNAVAILABLE", "SubscriptionManager is unavailable")
        val subscription = subscriptions.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
            ?: throw XiaomiActivationException(
                "SIM_SUBSCRIPTION_NOT_READY",
                "SIM slot $slotIndex is READY but its active subscription mapping is not available yet",
            )
        val subscriptionId = subscription.subscriptionId
        val telephony = baseTelephony.createForSubscriptionId(subscriptionId)
        val simState = runCatching { telephony.simState }.getOrElse {
            throw XiaomiActivationException("SIM_STATE_UNAVAILABLE", "Could not read SIM state for slot $slotIndex", it)
        }
        if (simState != TelephonyManager.SIM_STATE_READY) {
            throw XiaomiActivationException("SIM_NOT_READY", "SIM slot $slotIndex is not READY (state=$simState)")
        }

        // Prefer the exact MIUI slot APIs used by MiuiMms/of.b; AOSP is the
        // explicit fallback for class-native ROMs without miui.cloud.telephony.
        val miuiPhoneType = miuiSlotCall("getPhoneTypeForSlot", slotIndex, targetClassLoader) as? Int
        val phoneType = miuiPhoneType
            ?: runCatching { telephony.phoneType }.getOrElse {
                throw XiaomiActivationException("PHONE_TYPE_UNAVAILABLE", "Could not read phone type for slot $slotIndex", it)
            }
        val iccid = (miuiSlotCall("getSimSerialNumberForSlot", slotIndex, targetClassLoader) as? String)
            ?.takeIf(String::isNotBlank)
            ?: runCatching { telephony.simSerialNumber?.takeIf(String::isNotBlank) }.getOrNull()
        val imsi = (miuiSlotCall("getSubscriberIdForSlot", slotIndex, targetClassLoader) as? String)
            ?.takeIf(String::isNotBlank)
            ?: runCatching { telephony.subscriberId?.takeIf(String::isNotBlank) }.getOrNull()
        // Mixin login sim_id and Activate activate_info.simId are different values:
        // MiuiMms/of.b builds "1,ICCID" or "2,IMSI", while SimActivateService
        // v0.b.c(subId) hashes the raw ICCID via m0.c.b(iccid, 8).
        val mixinSimId = XiaomiSimIdentityAlgorithm.buildSimId(phoneType, iccid, imsi)
        val activationIccid = runCatching { subscription.iccId?.takeIf(String::isNotBlank) }.getOrNull()
            ?: iccid?.takeIf(String::isNotBlank)
            ?: throw XiaomiActivationException("SIM_ICCID_UNAVAILABLE", "Activation SIM hash requires the raw ICCID")
        val mccMnc = runCatching { telephony.simOperator?.takeIf { it.matches(Regex("\\d{5,6}")) } }
            .getOrNull()
            ?: (subscription.mccString.orEmpty() + subscription.mncString.orEmpty())
                .takeIf { it.matches(Regex("\\d{5,6}")) }
            ?: throw XiaomiActivationException("SIM_MCC_MNC_UNAVAILABLE", "Could not read SIM MCC/MNC")

        return XiaomiSimIdentity(
            slotIndex = slotIndex,
            subscriptionId = subscriptionId,
            phoneType = phoneType,
            mixinSimId = mixinSimId,
            simIdHash = XiaomiActivationCrypto.hash16(activationIccid),
            mccMnc = mccMnc,
            simState = simState,
            // The activation hash may use SubscriptionInfo.iccId when TelephonyManager
            // redacts simSerialNumber. Record the length of the actual ICCID source used.
            iccidLength = activationIccid.length,
            imsiLength = imsi?.length ?: 0,
            source = (if (miuiPhoneType != null) "miui_slot_api" else "aosp_subscription_telephony") + when (phoneType) {
                1 -> "_gsm_mixin=imsi_activation_hash=iccid"
                2 -> "_cdma_mixin=iccid_activation_hash=iccid"
                else -> "_phone_type=$phoneType"
            },
        )
    }

    private fun miuiSlotCall(method: String, slotIndex: Int, targetClassLoader: ClassLoader?): Any? = runCatching {
        // MiuiMms/of.b imports miui.cloud.telephony.TelephonyManager (not
        // miui.telephony.TelephonyManager) for the original slot-level SIM ID API.
        val type = Class.forName("miui.cloud.telephony.TelephonyManager", false, targetClassLoader ?: javaClass.classLoader)
        val manager = type.getMethod("getDefault").invoke(null)
        type.getMethod(method, Int::class.javaPrimitiveType).invoke(manager, slotIndex)
    }.getOrNull()
}

/** Pure algorithm kept separate so the OEM selection rule can be tested on AOSP. */
internal object XiaomiSimIdentityAlgorithm {
    fun buildSimId(phoneType: Int, iccid: String?, imsi: String?): String {
        val cleanIccid = iccid?.takeIf(String::isNotBlank)
        val cleanImsi = imsi?.takeIf(String::isNotBlank)
        return when (phoneType) {
            2 -> "1,${cleanIccid ?: missing("ICCID", phoneType)}"

            1 -> "2,${cleanImsi ?: missing("IMSI", phoneType)}"

            else -> throw XiaomiActivationException(
                "SIM_PHONE_TYPE_UNSUPPORTED",
                "MiuiMms/of.b only creates Mixin sim_id for GSM or CDMA phone types (got $phoneType)",
            )
        }
    }

    private fun missing(identifier: String, phoneType: Int): Nothing = throw XiaomiActivationException(
        "SIM_${identifier}_UNAVAILABLE",
        "Phone type $phoneType requires $identifier to construct the original Xiaomi SIM ID",
    )
}
