package io.github.xiaotong6666.maihoku.mimessage.account

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageManager
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageRuntimeStatus
import io.github.xiaotong6666.maihoku.mimessage.core.XiaomiSimAccountStatusPolicy
import io.github.xiaotong6666.maihoku.mimessage.ui.PassportCaptchaActivity
import io.github.xiaotong6666.maihoku.mimessage.ui.XiaomiSimActivationActivity
import io.github.xiaotong6666.maihoku.mimessage.ui.pendingIntent
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** SIM account operations and credential persistence run in MaiHoku; privileged SIM reads arrive from the LSPosed target UID. */
internal object XiaomiSimAccountBridge {
    const val RESULT = "result"
    const val KEY_SIM_USER_AVAILABLE = "sim_user_available"
    const val KEY_MIXIN_READY = "mixin_ready"
    const val KEY_CHALLENGE_PENDING = "challenge_pending"
    const val KEY_SIM_IDENTITY_AVAILABLE = "sim_identity_available"
    const val KEY_SIM_IDENTITY_MATCH = "sim_identity_match"
    const val KEY_SIM_IDENTITY_MISMATCH = "sim_identity_mismatch"
    const val KEY_ACTIVATION_STATUS = "activation_status"
    const val KEY_CURRENT_SUBSCRIPTION_ID = "current_subscription_id"
    const val KEY_CURRENT_MCC_MNC = "current_mcc_mnc"
    const val KEY_RUNTIME_ENABLED = "runtime_enabled"
    const val KEY_PUSH_STATE = "push_state"
    const val KEY_PUSH_UPDATED_AT = "push_updated_at"
    const val KEY_PUSH_CONNECTION_SUMMARY = "push_connection_summary"
    const val KEY_ERROR_KIND = "error_kind"
    const val KEY_PENDING_INTENT = "pending_intent"

    fun importPrivilegedSimIdentity(context: Context, intent: Intent, senderUid: Int): Bundle = try {
        val identity = XiaomiSimIdentityStore.importFromIntent(context, intent, senderUid)
        val sourcePackage = XiaomiSimIdentityStore.sourcePackage(context, identity.slotIndex)
        Log.i(
            TAG,
            "Privileged SIM identity stored slot=${identity.slotIndex} sourcePackage=$sourcePackage " +
                "senderUid=$senderUid subId=${identity.subscriptionId} phoneType=${identity.phoneType} " +
                "mixinSimIdPresent=true activationHashPresent=true; values redacted",
        )
        Bundle().apply {
            putString(RESULT, "success")
            putBoolean("sim_identity_ready", true)
            putString("source_package", sourcePackage)
        }
    } catch (error: Throwable) {
        val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
        Log.w(TAG, "Privileged SIM identity import rejected senderUid=$senderUid kind=$kind")
        failure("SIM_IDENTITY_IMPORT_REJECTED:$kind")
    }

    fun invalidatePrivilegedSimIdentity(context: Context, intent: Intent, senderUid: Int): Bundle = try {
        val slot = intent.getIntExtra(XiaomiSimIdentityStore.EXTRA_SLOT, -1)
        val cleared = XiaomiSimIdentityStore.invalidateFromIntent(context, intent, senderUid)
        val reason = intent.getStringExtra(XiaomiSimIdentityStore.EXTRA_INVALIDATION_REASON).orEmpty()
        Log.i(
            TAG,
            "Privileged SIM identity invalidation slot=$slot senderUid=$senderUid " +
                "cleared=$cleared reason=$reason",
        )
        Bundle().apply {
            putString(RESULT, "success")
            putBoolean("sim_identity_ready", false)
            putBoolean("sim_identity_invalidated", cleared)
        }
    } catch (error: Throwable) {
        val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
        Log.w(TAG, "Privileged SIM identity invalidation rejected senderUid=$senderUid kind=$kind")
        failure("SIM_IDENTITY_INVALIDATION_REJECTED:$kind")
    }

    fun status(context: Context, slot: Int): Bundle {
        if (slot !in 0..1) return failure("INVALID_SLOT")
        val storedActivationHash = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
        val currentSubscription = runCatching { XiaomiSimIdentityStore.currentSubscription(context, slot) }.getOrNull()
        val currentIdentity = runCatching { XiaomiSimIdentityStore.loadForCurrentSubscription(context, slot) }.getOrNull()
        val currentSimHash = currentIdentity?.simIdHash
        val activationMatches = !storedActivationHash.isNullOrBlank() &&
            !currentSimHash.isNullOrBlank() && storedActivationHash == currentSimHash
        val simUser = if (activationMatches) {
            runCatching { XiaomiSimActivationCredentialStore.loadSimUser(context, slot, requireNotNull(storedActivationHash)) }.getOrNull()
        } else {
            null
        }
        val requiredMixinFieldsPresent = listOf("mid", "token", "security", "user_cid", "resource").all { field ->
            !MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, field)).isNullOrBlank()
        }
        val activationStatus = XiaomiSimAccountStatusPolicy.activationStatus(
            currentSimIdentityAvailable = currentIdentity != null,
            storedActivationSimHash = storedActivationHash,
            currentSimHash = currentSimHash,
            matchingSimUserAvailable = simUser != null,
        )
        val mixinReady = XiaomiSimAccountStatusPolicy.isMixinReady(
            activationStatus = activationStatus,
            identityState = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "identity_state")),
            requiredFieldsPresent = requiredMixinFieldsPresent,
        )
        val challenge = XiaomiPassportChallengeStore.load(context, slot)
        val challengePending = challenge != null && currentSimHash != null && challenge.simIdHash == currentSimHash
        if (challenge != null && currentSimHash != null && challenge.simIdHash != currentSimHash) {
            XiaomiPassportChallengeStore.clear(context, slot)
        }
        val result = Bundle().apply {
            putString(RESULT, "ok")
            putBoolean(KEY_SIM_USER_AVAILABLE, simUser != null)
            putBoolean(KEY_MIXIN_READY, mixinReady)
            putBoolean(KEY_CHALLENGE_PENDING, challengePending)
            putBoolean(KEY_SIM_IDENTITY_AVAILABLE, currentIdentity != null)
            putBoolean(KEY_SIM_IDENTITY_MATCH, activationMatches)
            putBoolean(KEY_SIM_IDENTITY_MISMATCH, activationStatus == XiaomiSimAccountStatusPolicy.SIM_MISMATCH)
            putString(KEY_ACTIVATION_STATUS, activationStatus)
            currentSubscription?.let {
                putInt(KEY_CURRENT_SUBSCRIPTION_ID, it.subscriptionId)
                putString(KEY_CURRENT_MCC_MNC, it.mccMnc)
            }
        }
        if (slot == 0) {
            MiMessageManager.invalidateBindingIfChanged(
                context,
                currentSubscription?.subscriptionId,
                currentSimHash,
            )
            val runtime = MiMessageManager.runtimeSnapshot(context)
            result.putBoolean(KEY_RUNTIME_ENABLED, runtime.enabled)
            result.putString(KEY_PUSH_STATE, runtime.pushState)
            result.putLong(KEY_PUSH_UPDATED_AT, runtime.updatedAt)
            result.putString(
                KEY_PUSH_CONNECTION_SUMMARY,
                MiMessageRuntimeStatus.connectionSummary(runtime.enabled, runtime.pushState).resolve(context),
            )
        }
        return result
    }

    fun refreshMixinCredentials(context: Context, slot: Int): Bundle {
        if (slot !in 0..1) return failure("INVALID_SLOT")
        return try {
            val currentIdentity = XiaomiSimIdentityStore.loadForCurrentSubscription(context, slot)
            val simHash = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
                ?: throw XiaomiActivationException("ACTIVATION_SIM_HASH_MISSING", "No activated SIM identity is stored")
            if (simHash != currentIdentity.simIdHash) {
                throw XiaomiActivationException(
                    "SIM_ACTIVATION_IDENTITY_MISMATCH",
                    "Stored activation credentials belong to a different SIM than the currently active subscription",
                )
            }
            val simUser = XiaomiSimActivationCredentialStore.loadSimUser(context, slot, simHash)
                ?: throw XiaomiActivationException("SIM_ACTIVATION_CREDENTIALS_MISSING", "No matching SIM activation credentials are stored")
            val currentDevice = XiaomiDeviceIdProvider.resolve(context)
            val activationDeviceIdHash = MiMessageCredentialStore.get(
                context,
                MiMessageConfig.simKey(slot, "activation_device_id_hash"),
            ) ?: currentDevice.deviceIdHash
            val stsDeviceIdHash = XiaomiAccountSdkDeviceIdProvider.resolve(context)
            val client = XiaomiSimServiceTokenClient(
                context = context,
                simUserId = simUser.userId,
                simPassToken = simUser.passToken,
                activationDeviceIdHash = activationDeviceIdHash,
                serviceId = MiMessageConfig.SERVICE_ID,
                oaid = currentDevice.oaid,
                stsDeviceIdHash = stsDeviceIdHash,
                trace = { event -> Log.i(TAG, event.toString()) },
            )
            val credentials = client.getSimAuthToken()
            val resource = XiaomiSimActivationCredentialStore.getOrCreateResource(context, slot)
            check(XiaomiSimActivationCredentialStore.saveMixinCredentials(context, slot, credentials, resource)) {
                "Could not save Mixin credentials to MaiHoku private SharedPreferences"
            }
            XiaomiPassportChallengeStore.clear(context, slot)
            Log.i(
                TAG,
                "Mixin credential refresh succeeded slot=$slot midPresent=${credentials.mid.isNotBlank()} " +
                    "tokenPresent=${credentials.userToken.isNotBlank()} securityPresent=${credentials.userSecurity.isNotBlank()} " +
                    "cidPresent=${credentials.userCid.isNotBlank()}; values remain in MaiHoku UID",
            )
            Bundle().apply {
                putString(RESULT, "success")
                putBoolean(KEY_MIXIN_READY, true)
            }
        } catch (challenge: XiaomiPassportCaptchaRequiredException) {
            runCatching {
                val simHash = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
                    ?: throw XiaomiActivationException("ACTIVATION_SIM_HASH_MISSING", "No activation SIM identity is stored")
                val device = XiaomiDeviceIdProvider.resolve(context)
                val stsDeviceIdHash = XiaomiAccountSdkDeviceIdProvider.resolve(context)
                check(
                    XiaomiPassportChallengeStore.save(
                        context,
                        XiaomiPassportChallengeSession(
                            slotIndex = slot,
                            simIdHash = simHash,
                            activationDeviceIdHash = device.deviceIdHash,
                            challenge = challenge.challenge,
                            oaid = device.oaid,
                            stsDeviceIdHash = stsDeviceIdHash,
                        ),
                    ),
                ) { "Could not save Passport challenge to MaiHoku private SharedPreferences" }
            }.getOrElse { saveError ->
                Log.e(TAG, "Passport challenge checkpoint failed: ${saveError.javaClass.simpleName}")
                return failure("PASSPORT_CHECKPOINT_FAILED")
            }
            Log.i(TAG, "Mixin credential refresh requires Passport verification slot=$slot code=${challenge.challenge.businessCode}")
            Bundle().apply {
                putString(RESULT, "verification_required")
                putString(KEY_ERROR_KIND, challenge.kind)
                putBoolean(KEY_CHALLENGE_PENDING, true)
            }
        } catch (error: Throwable) {
            val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
            Log.w(TAG, "Mixin credential refresh failed slot=$slot kind=$kind")
            failure(kind)
        }
    }

    fun openPassport(context: Context, slot: Int): Bundle {
        if (slot !in 0..1) return failure("INVALID_SLOT")
        val challenge = XiaomiPassportChallengeStore.load(context, slot) ?: return failure("NO_PENDING_CHALLENGE")
        val currentIdentity = runCatching { XiaomiSimIdentityStore.loadForCurrentSubscription(context, slot) }.getOrElse {
            return failure((it as? XiaomiActivationException)?.kind ?: "SIM_IDENTITY_UNAVAILABLE")
        }
        if (challenge.simIdHash != currentIdentity.simIdHash) {
            XiaomiPassportChallengeStore.clear(context, slot)
            return failure("PASSPORT_CHALLENGE_SIM_IDENTITY_MISMATCH")
        }
        val intent = Intent(context, PassportCaptchaActivity::class.java)
            .putExtra(XiaomiPassportChallengeCoordinator.EXTRA_SLOT, slot)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0x5040 + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityPendingIntentOptions(),
        )
        return Bundle().apply {
            putString(RESULT, "ok")
            putParcelable(KEY_PENDING_INTENT, pendingIntent)
        }
    }

    /** Return a one-shot visible Activity launch; the foreground Activity starts the long-lived Push service. */
    fun startPushCore(context: Context, slot: Int): Bundle {
        if (slot != 0) return failure("PUSH_CORE_SLOT_UNSUPPORTED")
        return try {
            val identity = XiaomiSimIdentityStore.loadForCurrentSubscription(context, slot)
            val activationHash = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
                ?: throw XiaomiActivationException("ACTIVATION_SIM_HASH_MISSING", "No activated SIM identity is stored")
            if (activationHash != identity.simIdHash) {
                throw XiaomiActivationException("SIM_ACTIVATION_IDENTITY_MISMATCH", "Activation credentials belong to a different active SIM")
            }
            XiaomiSimActivationCredentialStore.loadSimUser(context, slot, activationHash)
                ?: throw XiaomiActivationException("SIM_ACTIVATION_CREDENTIALS_MISSING", "Matching SIM activation credentials are unavailable")
            val requiredFields = listOf("mid", "token", "security", "user_cid", "resource")
            if (MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "identity_state")) != "ready" ||
                requiredFields.any { MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, it)).isNullOrBlank() }
            ) {
                throw XiaomiActivationException("MIXIN_CREDENTIALS_NOT_READY", "Mixin credentials are not fully saved")
            }
            MiMessageIdentityStore.load(context, slot)
            val intent = Intent(context, XiaomiSimActivationActivity::class.java)
                .putExtra(XiaomiSimActivationActivity.EXTRA_SLOT, slot)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0x4D30 + slot,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                activityPendingIntentOptions(),
            )
            Bundle().apply {
                putString(RESULT, "ok")
                putParcelable(KEY_PENDING_INTENT, pendingIntent)
            }
        } catch (error: Throwable) {
            val kind = (error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName
            Log.w(TAG, "ChID=3 Push core start preflight failed slot=$slot kind=$kind")
            failure(kind)
        }
    }

    fun stopPushCore(context: Context, slot: Int): Bundle {
        if (slot != 0) return failure("PUSH_CORE_SLOT_UNSUPPORTED")
        return runCatching {
            MiMessageManager.stop(context.applicationContext)
            Bundle().apply {
                putString(RESULT, "success")
                putBoolean(KEY_RUNTIME_ENABLED, false)
                putString(KEY_PUSH_STATE, MiMessageRuntimeStatus.DISCONNECTED)
            }
        }.getOrElse { error ->
            failure((error as? XiaomiActivationException)?.kind ?: error.javaClass.simpleName)
        }
    }

    fun startActivation(context: Context, slot: Int): Bundle {
        if (slot !in 0..1) return failure("INVALID_SLOT")
        val simHash = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "activation_sim_id_hash"))
        val currentHash = runCatching { XiaomiSimIdentityStore.loadForCurrentSubscription(context, slot).simIdHash }.getOrNull()
        if (simHash != null && simHash == currentHash && XiaomiSimActivationCredentialStore.loadSimUser(context, slot, simHash) != null) {
            return failure("SIM_ACTIVATION_ALREADY_COMPLETE")
        }
        val intent = Intent(context, XiaomiSimActivationActivity::class.java)
            .putExtra(XiaomiSimActivationActivity.EXTRA_SLOT, slot)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0xA100 + slot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityPendingIntentOptions(),
        )
        return Bundle().apply {
            putString(RESULT, "ok")
            putParcelable(KEY_PENDING_INTENT, pendingIntent)
        }
    }

    private fun failure(kind: String) = Bundle().apply {
        putString(RESULT, "failed")
        putString(KEY_ERROR_KIND, kind)
    }

    private fun activityPendingIntentOptions(): Bundle? = if (android.os.Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    } else {
        null
    }

    private const val TAG = "AccountBridge"
}
