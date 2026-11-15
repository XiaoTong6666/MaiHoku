package io.github.xiaotong6666.maihoku.features.mimessage.account

import android.content.Context
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageConfig

/** Loads and validates the service identity belonging to an activated SIM slot. */
internal object MiMessageIdentityStore {
    fun load(context: Context, slot: Int = 0): MiMessageIdentity {
        require(slot in 0..1) { "Only SIM slots 0 and 1 are supported" }
        fun field(name: String) = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, name))
            ?: error("Mi Message SIM ${slot + 1} $name is unavailable")

        val source = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "identity_source"))
        val state = MiMessageCredentialStore.get(context, MiMessageConfig.simKey(slot, "identity_state"))
        check(MiMessageIdentityReadiness.isUsable(source, state)) {
            "SIM activation is complete but Mixin credentials are not ready"
        }
        return MiMessageIdentity(
            mid = field("mid"),
            token = field("token"),
            security = field("security"),
            simId = field("sim_id"),
            resource = field("resource"),
            phone = field("phone"),
        ).also(MiMessageIdentity::validate)
    }
}
