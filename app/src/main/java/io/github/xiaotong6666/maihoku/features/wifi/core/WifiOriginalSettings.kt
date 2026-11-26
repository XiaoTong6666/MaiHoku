package io.github.xiaotong6666.maihoku.features.wifi.core

import android.net.wifi.WifiConfiguration
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.host.common.s

/** Snapshot of the Wi-Fi settings the host UI is already using. No stored credentials are read. */
internal data class WifiOriginalSettings(
    val securityTypes: List<Int>,
    val pmfRequired: Boolean?,
    val bssid: String?,
    val saeH2eOnly: Boolean?,
    val hasSavedConfiguration: Boolean,
    val savedConfig: WifiConfiguration?,
) {
    companion object {
        fun fromEntry(entry: Any?): WifiOriginalSettings {
            val isSaved = runCatching {
                entry?.javaClass?.getMethod("isSaved")?.invoke(entry) as? Boolean
            }.getOrNull() == true
            val config = if (isSaved) {
                runCatching {
                    entry?.javaClass?.getMethod("getWifiConfiguration")?.invoke(entry) as? WifiConfiguration
                }.getOrNull()
            } else {
                null
            }
            // For a new network, WifiEntry's scan-derived security types are the authoritative
            // candidates; for a saved one, prefer saved SecurityParams and exclude auto-upgrades.
            val scanTypes = runCatching {
                @Suppress("UNCHECKED_CAST")
                (entry?.javaClass?.getMethod("getSecurityTypes")?.invoke(entry) as? List<*>)
                    ?.filterIsInstance<Int>().orEmpty()
            }.getOrDefault(emptyList())
            // Settings itself uses WifiEntry.getSecurity() when deciding the initial
            // connect mode (its enum differs from SecurityParams security types).
            val selectedType = runCatching {
                securityFromWifiEntry(entry?.javaClass?.getMethod("getSecurity")?.invoke(entry) as? Int)
            }.getOrNull()
            val savedParams = config?.let { c ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (WifiConfiguration::class.java.getMethod("getSecurityParamsList").invoke(c) as? List<*>)
                        ?.filterNotNull().orEmpty()
                }.getOrDefault(emptyList())
            }.orEmpty()
            val savedTypes = savedParams.filter { param ->
                runCatching { param.javaClass.getMethod("isEnabled").invoke(param) as Boolean }
                    .getOrDefault(true) &&
                    !runCatching { param.javaClass.getMethod("isAddedByAutoUpgrade").invoke(param) as Boolean }
                        .getOrDefault(false)
            }.mapNotNull { param ->
                runCatching { param.javaClass.getMethod("getSecurityType").invoke(param) as Int }.getOrNull()
            }.distinct()
            val sae = savedParams.firstOrNull { param ->
                runCatching { param.javaClass.getMethod("getSecurityType").invoke(param) == 4 }
                    .getOrDefault(false)
            }
            val h2e = sae?.let { param ->
                runCatching { param.javaClass.getMethod("isSaeH2eOnlyMode").invoke(param) as Boolean }
                    .getOrNull()
            }
            return WifiOriginalSettings(
                // Saved profiles reflect saved parameters; new networks reflect the actual
                // native security choice rather than all capabilities advertised by the AP.
                securityTypes = savedTypes.ifEmpty { selectedType?.let(::listOf) ?: scanTypes },
                pmfRequired = config?.let { c ->
                    runCatching {
                        WifiConfiguration::class.java.getDeclaredField("requirePmf")
                            .apply { isAccessible = true }.getBoolean(c)
                    }.getOrNull()
                },
                bssid = config?.BSSID,
                saeH2eOnly = h2e,
                hasSavedConfiguration = config != null,
                savedConfig = config,
            )
        }

        /** WifiTracker's getSecurity() values differ from SecurityParams.getSecurityType(). */
        fun securityFromWifiEntry(code: Int?): Int? = when (code) {
            0 -> 0

            // Open
            1 -> 1

            // WEP
            2 -> 2

            // PSK
            3 -> 3

            // EAP
            4 -> 6

            // OWE
            5 -> 4

            // SAE
            6, 7 -> 5

            // WPA3 Enterprise
            else -> null
        }

        /** UI labels live only in resources; this mapper returns a resource ID. */
        fun securityLabelId(types: List<Int>): Int? = when {
            2 in types && 4 in types -> R.string.wifi_security_type_psk_sae
            4 in types -> R.string.wifi_security_type_sae
            2 in types -> R.string.wifi_security_type_psk
            6 in types && 0 in types -> R.string.wifi_security_type_open_owe
            6 in types -> R.string.wifi_security_type_owe
            0 in types -> R.string.wifi_security_type_open
            5 in types -> R.string.wifi_security_type_wpa3_enterprise
            3 in types -> R.string.wifi_security_type_wpa2_enterprise
            1 in types -> R.string.wifi_security_type_wep
            else -> null
        }
    }
}
