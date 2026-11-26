package io.github.xiaotong6666.maihoku.features.wifi.policy

import io.github.xiaotong6666.maihoku.features.wifi.host.common.s
import java.security.MessageDigest
import java.util.Locale

/** Security selection is stored per SSID, never with the network's password or BSSID. */
internal enum class WifiSecurityMode(val value: String, val securityType: Int?) {
    SYSTEM("auto", null),
    WPA2("wpa2", 2),
    WPA3("wpa3", 4),
    TRANSITION("transition", 2),
    OPEN("open", 0),
    OWE("owe", 6),
    ;

    companion object {
        fun fromStorage(raw: String?): WifiSecurityMode = entries.firstOrNull { it.value == raw } ?: SYSTEM
    }
}

internal object WifiSecurityPolicy {
    const val PREF_PREFIX = "maihoku.wifi.security.v1."

    fun normalizeSsid(ssid: String): String = if (ssid.length >= 2 && ssid.first() == '"' && ssid.last() == '"') {
        ssid.substring(1, ssid.lastIndex)
    } else {
        ssid
    }

    fun keyForSsid(ssid: String): String = PREF_PREFIX + hashIdentity(normalizeSsid(ssid))

    fun hashIdentity(identity: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        val digest = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        return digest
    }

    fun isValidBssid(raw: String): Boolean {
        if (!Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(raw)) return false
        val bytes = raw.split(':').map { it.toInt(16) }
        // A BSSID must identify an individual AP rather than a multicast/broadcast address.
        return bytes[0] and 1 == 0 && bytes.any { it != 0 }
    }
}
