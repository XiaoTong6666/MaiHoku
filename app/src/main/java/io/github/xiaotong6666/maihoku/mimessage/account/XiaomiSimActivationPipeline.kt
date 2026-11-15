package io.github.xiaotong6666.maihoku.mimessage.account

/**
 * The ordering contract between SIM activation and service-token exchange.
 * Activation credentials are persisted before Passport is called so a transient
 * Passport/STS failure never forces the owner to repeat SIM activation.
 */
internal object XiaomiSimActivationPipeline {
    fun createUserAndExchange(
        createSimUser: () -> XiaomiSimUserCredentials,
        persistSimUser: (XiaomiSimUserCredentials) -> Unit,
        exchangeMixin: (XiaomiSimUserCredentials) -> XiaomiMixinServiceCredentials,
        persistMixin: (XiaomiMixinServiceCredentials) -> Unit,
    ): XiaomiMixinServiceCredentials {
        val simUser = createSimUser()
        persistSimUser(simUser)
        val mixin = exchangeMixin(simUser)
        persistMixin(mixin)
        return mixin
    }
}
