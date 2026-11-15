package io.github.xiaotong6666.maihoku.mimessage.core

import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.mimessage.ui.MessagingText

/** Pure runtime-state policy shared by the Push service, account bridge and settings UIs. */
internal object MiMessageRuntimeStatus {
    const val DISCONNECTED = "DISCONNECTED"
    const val CONNECTING = "CONNECTING"
    const val AUTHENTICATING = "AUTHENTICATING"
    const val BINDING = "BINDING"
    const val BOUND = "BOUND"
    const val RECONNECTING = "RECONNECTING"
    const val FAILED = "FAILED"
    const val CLOSED = "CLOSED"
    const val SIM_CHANGED = "SIM_CHANGED"
    const val SIM_IDENTITY_UNAVAILABLE = "SIM_IDENTITY_UNAVAILABLE"

    private val liveStates = setOf(CONNECTING, AUTHENTICATING, BINDING, BOUND, RECONNECTING)

    /** A persisted BIND is not evidence of a live socket after the owning app process has changed. */
    fun effectiveState(
        enabled: Boolean,
        storedState: String,
        storedProcessId: Int,
        currentProcessId: Int,
    ): String {
        val state = if (storedState == "BINDED") BOUND else storedState
        return if ((!enabled || (storedProcessId > 0 && storedProcessId != currentProcessId)) && state in liveStates) {
            DISCONNECTED
        } else {
            state
        }
    }

    fun connectionSummary(enabled: Boolean, state: String): MessagingText {
        val feature = if (enabled) R.string.mx_feature_enabled else R.string.mx_feature_disabled
        val connection = when (state) {
            BOUND -> R.string.mx_connection_bound
            CONNECTING -> R.string.mx_connection_connecting
            AUTHENTICATING -> R.string.mx_connection_authenticating
            BINDING -> R.string.mx_connection_binding
            RECONNECTING -> R.string.mx_connection_reconnecting
            FAILED -> R.string.mx_connection_failed
            SIM_CHANGED -> R.string.mx_connection_sim_changed
            SIM_IDENTITY_UNAVAILABLE -> R.string.mx_connection_sim_unavailable
            CLOSED -> R.string.mx_connection_closed
            else -> R.string.mx_connection_disconnected
        }
        return MessagingText(R.string.mx_connection_summary, listOf(MessagingText(feature), MessagingText(connection)))
    }

    fun featureSummary(enabled: Boolean): MessagingText = MessagingText(if (enabled) R.string.mx_toggle_disable else R.string.mx_toggle_enable)
}

/** Keeps account activation, SIM matching and Mixin readiness as separate facts. */
internal object XiaomiSimAccountStatusPolicy {
    const val ACTIVATED = "activated"
    const val NOT_ACTIVATED = "not_activated"
    const val SIM_MISMATCH = "sim_mismatch"
    const val SIM_IDENTITY_UNAVAILABLE = "sim_identity_unavailable"

    fun activationStatus(
        currentSimIdentityAvailable: Boolean,
        storedActivationSimHash: String?,
        currentSimHash: String?,
        matchingSimUserAvailable: Boolean,
    ): String = when {
        !currentSimIdentityAvailable || currentSimHash.isNullOrBlank() -> SIM_IDENTITY_UNAVAILABLE
        !storedActivationSimHash.isNullOrBlank() && storedActivationSimHash != currentSimHash -> SIM_MISMATCH
        matchingSimUserAvailable -> ACTIVATED
        else -> NOT_ACTIVATED
    }

    fun isMixinReady(
        activationStatus: String,
        identityState: String?,
        requiredFieldsPresent: Boolean,
    ): Boolean = activationStatus == ACTIVATED && identityState == "ready" && requiredFieldsPresent
}
