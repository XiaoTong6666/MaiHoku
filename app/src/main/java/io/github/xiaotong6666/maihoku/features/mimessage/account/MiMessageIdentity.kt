package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.util.Base64

/** In-memory ChID=3 identity. Secret fields must never be logged or exposed through the host IPC. */
internal data class MiMessageIdentity(
    val mid: String,
    val token: String,
    val security: String,
    val simId: String,
    val resource: String,
    val phone: String,
) {
    override fun toString(): String = "MiMessageIdentity(midPresent=${mid.isNotBlank()}, token=redacted, security=redacted, " +
        "simId=redacted, resourcePresent=${resource.isNotBlank()}, phone=redacted)"

    fun validate() {
        require(mid.toLongOrNull() != null) { "Mixin MID is not numeric" }
        require(token.isNotBlank()) { "Mixin token is missing" }
        require(simId.isNotBlank()) { "Mixin SIM ID is missing" }
        require(resource.isNotBlank()) { "Mi Message Resource is missing" }
        require(phone.isNotBlank()) { "SIM activation phone is missing" }
        require(runCatching { Base64.decode(security, Base64.DEFAULT).size == 16 }.getOrDefault(false)) {
            "Mixin Security is not a 16-byte key"
        }
    }
}

internal object MiMessageIdentityReadiness {
    const val AWAITING_MIXIN_CREDENTIALS = "awaiting_mixin_credentials"
    const val READY = "ready"

    /** An activated SIM cannot reuse old service credentials while its new credentials are pending. */
    fun isUsable(identitySource: String?, identityState: String?): Boolean = identityState != AWAITING_MIXIN_CREDENTIALS &&
        (identitySource != "maihoku_sim_activation" || identityState == READY)
}
