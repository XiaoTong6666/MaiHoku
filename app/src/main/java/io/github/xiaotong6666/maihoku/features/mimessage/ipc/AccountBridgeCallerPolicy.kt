package io.github.xiaotong6666.maihoku.features.mimessage.ipc

import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig

/**
 * Authenticates the actual sender identity supplied by the Android broadcast dispatcher.
 * Neither Intent extras nor the Binder identity of onReceive are trusted evidence.
 */
internal object AccountBridgeCallerPolicy {
    private val messagingHosts = setOf(
        "com.google.android.apps.messaging",
        "com.android.messaging",
    )
    private val privilegedIdentitySources = setOf(
        "com.android.mms",
        "com.android.phone",
    )
    private val trustedPackages = messagingHosts + privilegedIdentitySources

    fun isTrusted(senderUid: Int, senderPackage: String?, packagesForUid: Array<String>?): Boolean {
        if (senderUid < 0 || senderPackage == null || senderPackage !in trustedPackages) return false
        return packagesForUid?.contains(senderPackage) == true
    }

    /** SIM identity reporting belongs to phone processes, not the user-facing SMS clients. */
    fun canInvoke(senderPackage: String?, method: String): Boolean = when (method) {
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_IMPORT_SIM_IDENTITY,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_INVALIDATE_SIM_IDENTITY,
        -> senderPackage in privilegedIdentitySources

        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STATUS,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_REFRESH_MIXIN_CREDENTIALS,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_OPEN_PASSPORT,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_ACTIVATION,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_START_PUSH_CORE,
        MiMessageConfig.ACCOUNT_BRIDGE_METHOD_STOP_PUSH_CORE,
        -> senderPackage in messagingHosts

        else -> false
    }
}
