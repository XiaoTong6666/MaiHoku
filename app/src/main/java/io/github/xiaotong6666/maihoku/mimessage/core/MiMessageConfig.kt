package io.github.xiaotong6666.maihoku.mimessage.core

internal object MiMessageConfig {
    const val ACCOUNT_BRIDGE_ACTION = "io.github.xiaotong6666.maihoku.action.ACCOUNT_BRIDGE"
    const val ACCOUNT_BRIDGE_METHOD_STATUS = "getStatus"
    const val ACCOUNT_BRIDGE_METHOD_REFRESH_MIXIN_CREDENTIALS = "refreshMixinCredentials"
    const val ACCOUNT_BRIDGE_METHOD_OPEN_PASSPORT = "openPassport"
    const val ACCOUNT_BRIDGE_METHOD_START_ACTIVATION = "startActivation"
    const val ACCOUNT_BRIDGE_METHOD_IMPORT_SIM_IDENTITY = "importPrivilegedSimIdentity"
    const val ACCOUNT_BRIDGE_METHOD_INVALIDATE_SIM_IDENTITY = "invalidatePrivilegedSimIdentity"
    const val ACCOUNT_BRIDGE_METHOD_START_PUSH_CORE = "startPushCore"
    const val ACCOUNT_BRIDGE_METHOD_STOP_PUSH_CORE = "stopPushCore"

    const val SERVICE_ID = "mixin"
    const val CHANNEL_ID = "3"
    const val AUTH_METHOD = "XIAOMI-PASS"

    const val DEFAULT_PUSH_HOST = "cn.app.chat.xiaomi.net"
    const val DEFAULT_PUSH_PORT = "443"
    const val DEFAULT_RESOLVER_HOST = "resolver.msg.xiaomi.net"
    const val DEFAULT_PUSH_FALLBACKS =
        "111.13.141.211:443;39.156.81.172:443;111.202.1.250:443;123.125.102.213:443"
    const val DEFAULT_RESOLVER_FALLBACKS = "111.13.142.153:443;111.202.1.252:443"
    const val DEFAULT_CAPABILITIES =
        "sms#mms#mx2audio#mxV2mms2#b2c_text_v10#b2c_media_v4"

    fun simKey(slot: Int, field: String): String = "maihoku_mx_sim${slot + 1}_$field"
}
