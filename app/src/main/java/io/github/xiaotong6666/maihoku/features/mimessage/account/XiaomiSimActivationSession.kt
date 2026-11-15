package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.content.Context
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig

/**
 * One explicit SIM activation attempt. Construction is local-only; every
 * network transition is an explicit method call made by the UI/work manager.
 */
internal class XiaomiSimActivationSession(
    context: Context,
    val attempt: SimActivationAttempt,
    private val deviceIdentifiers: XiaomiDeviceIdentifiers,
    private val mixinSimId: String,
    private val fidProvider: XiaomiActivationFidProvider? = null,
    private val passportTrace: (XiaomiPassportTraceEvent) -> Unit = {},
) {
    private val challengeUiContext = context
    private val appContext = context.applicationContext
    private val activationClient: XiaomiSimActivationClient

    init {
        require(mixinSimId.startsWith("1,") || mixinSimId.startsWith("2,")) {
            "Mixin sim_id must be derived from the selected physical SIM"
        }
        require(deviceIdentifiers.deviceIdHash == attempt.deviceIdHash) {
            "Activation attempt deviceId does not match the resolved Xiaomi device identity"
        }
        activationClient = XiaomiSimActivationClient(
            factory = XiaomiActivationRequestFactory(attempt, fidProvider),
            deviceIdHash = attempt.deviceIdHash,
        )
    }

    /** Read-only server check; does not send an SMS or create a SIM user. */
    fun checkClient(): XiaomiCheckClientResult = activationClient.checkClient()

    /** Call only after the owner has sent the displayed activation SMS. */
    fun verify(vkeys: XiaomiVKeyPair, retryVKeyNotFound: Boolean = true): XiaomiVerifiedPhone = activationClient.verify(vkeys, retryVKeyNotFound)

    /** Creates/updates the SIM user's Xiaomi activation account. */
    fun createOrUpdateUser(
        vkeys: XiaomiVKeyPair,
        verifiedPhone: XiaomiVerifiedPhone,
    ): XiaomiSimUserCredentials = activationClient.createOrUpdateUser(vkeys, verifiedPhone)

    /**
     * Exchanges the SIM passToken for the Mixin service token/security/cid.
     * Passport CAPTCHA/notification/security challenges are surfaced, never bypassed.
     */
    fun getSimAuthToken(
        simUser: XiaomiSimUserCredentials,
        stsDeviceIdHash: String? = null,
        userSpaceId: String? = null,
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiMixinServiceCredentials {
        val effectiveStsDeviceIdHash = stsDeviceIdHash ?: XiaomiAccountSdkDeviceIdProvider.resolve(appContext)
        val client = XiaomiSimServiceTokenClient(
            context = appContext,
            simUserId = simUser.userId,
            simPassToken = simUser.passToken,
            activationDeviceIdHash = attempt.deviceIdHash,
            serviceId = MiMessageConfig.SERVICE_ID,
            oaid = deviceIdentifiers.oaid,
            stsDeviceIdHash = effectiveStsDeviceIdHash,
            userSpaceId = userSpaceId,
            fidProvider = fidProvider,
            trace = passportTrace,
        )
        return try {
            client.getSimAuthToken(verificationResult, responseSetCookies)
        } catch (challenge: XiaomiPassportCaptchaRequiredException) {
            XiaomiPassportChallengeCoordinator.saveAndOpen(
                challengeUiContext,
                attempt.slotIndex,
                attempt.simIdHash,
                attempt.deviceIdHash,
                challenge.challenge,
                oaid = deviceIdentifiers.oaid,
                stsDeviceIdHash = effectiveStsDeviceIdHash,
                userSpaceId = userSpaceId,
            )
            throw challenge
        }
    }

    /**
     * Create the SIM activation account, persist its passToken
     * immediately, then exchange it for Mixin credentials without exposing or
     * manually copying either secret between stages.
     *
     * If Passport/STS fails, activation credentials remain in private storage so callers can
     * retry service-token exchange without repeating the SIM activation request.
     */
    fun createUserAndGetMixinCredentials(
        vkeys: XiaomiVKeyPair,
        verifiedPhone: XiaomiVerifiedPhone,
        stsDeviceIdHash: String? = null,
        userSpaceId: String? = null,
        beforePersistSimUser: () -> Unit = {},
        beforePersistMixin: () -> Unit = {},
    ): XiaomiMixinServiceCredentials = XiaomiSimActivationPipeline.createUserAndExchange(
        createSimUser = { createOrUpdateUser(vkeys, verifiedPhone) },
        persistSimUser = {
            beforePersistSimUser()
            saveSimUser(attempt.simIdHash, verifiedPhone, it)
        },
        exchangeMixin = { getSimAuthToken(it, stsDeviceIdHash, userSpaceId) },
        persistMixin = {
            beforePersistMixin()
            val resource = XiaomiSimActivationCredentialStore.getOrCreateResource(appContext, attempt.slotIndex)
            saveMixinCredentials(it, resource)
        },
    )

    /** Retry service-token exchange using this slot's saved activation credentials. */
    fun retryMixinCredentials(
        stsDeviceIdHash: String? = null,
        userSpaceId: String? = null,
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiMixinServiceCredentials {
        val simUser = XiaomiSimActivationCredentialStore.loadSimUser(
            appContext,
            attempt.slotIndex,
            attempt.simIdHash,
        ) ?: throw XiaomiActivationException(
            "SIM_ACTIVATION_CREDENTIALS_MISSING",
            "No matching activation userId/passToken exists for this SIM; complete SIM activation first",
        )
        return exchangeAndPersistMixinCredentials(
            simUser,
            stsDeviceIdHash,
            userSpaceId,
            verificationResult,
            responseSetCookies,
        )
    }

    private fun exchangeAndPersistMixinCredentials(
        simUser: XiaomiSimUserCredentials,
        stsDeviceIdHash: String?,
        userSpaceId: String?,
        verificationResult: XiaomiPassportVerificationResult? = null,
        responseSetCookies: List<String> = emptyList(),
    ): XiaomiMixinServiceCredentials {
        val credentials = getSimAuthToken(
            simUser,
            stsDeviceIdHash,
            userSpaceId,
            verificationResult,
            responseSetCookies,
        )
        val resource = XiaomiSimActivationCredentialStore.getOrCreateResource(appContext, attempt.slotIndex)
        saveMixinCredentials(credentials, resource)
        return credentials
    }

    /** Persist SIM activation credentials in app-private storage. */
    fun saveSimUser(simIdHash: String, verifiedPhone: XiaomiVerifiedPhone, simUser: XiaomiSimUserCredentials) {
        require(simIdHash == attempt.simIdHash) { "Persisted SIM ID must match the activation attempt" }
        check(
            XiaomiSimActivationCredentialStore.saveSimActivation(
                appContext,
                attempt.slotIndex,
                simIdHash,
                attempt.deviceIdHash,
                verifiedPhone.phone,
                mixinSimId,
                simUser,
            ),
        ) { "Could not save SIM activation credentials in MaiHoku private storage" }
    }

    /** Persist Mixin service credentials in app-private storage. */
    fun saveMixinCredentials(credentials: XiaomiMixinServiceCredentials, resource: String) {
        check(resource.isNotBlank()) { "A non-empty local Mixin resource is required" }
        check(
            XiaomiSimActivationCredentialStore.saveMixinCredentials(
                appContext,
                attempt.slotIndex,
                credentials,
                resource,
            ),
        ) { "Could not save Mixin service credentials in MaiHoku private storage" }
    }
}
