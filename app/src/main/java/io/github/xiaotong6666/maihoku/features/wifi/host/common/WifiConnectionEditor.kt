package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiConfigurationOverride
import io.github.xiaotong6666.maihoku.features.wifi.core.WifiEditDetection
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSaveExpectation
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityMode
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityPolicy
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

private const val TAG = "WifiAdvancedConnection"

internal fun hasExplicitEdits(state: Controls): Boolean = WifiEditDetection.hasChanges(
    state.initialSecurity,
    state.security.selectedItemPosition,
    state.pmf.selectedItemPosition,
    state.h2e.selectedItemPosition,
    state.bssid.text.toString().trim(),
    state.password?.text?.toString().orEmpty(),
)

internal data class EditedConfig(val config: WifiConfiguration, val ssid: String, val expectation: WifiSaveExpectation)

/** Null means absolutely no edit. Never re-save a policy or rewrite the native config for a no-op. */
internal fun applyOptions(state: Controls, config: WifiConfiguration): EditedConfig? {
    val securityChanged = state.security.selectedItemPosition != state.initialSecurity
    val pmfChanged = state.pmf.selectedItemPosition != 0
    val h2eChanged = state.h2e.selectedItemPosition != 0
    val rawBssid = state.bssid.text.toString().trim()
    val suppliedPassword = state.password?.text?.toString().orEmpty()
    if (!WifiEditDetection.hasChanges(
            state.initialSecurity,
            state.security.selectedItemPosition,
            state.pmf.selectedItemPosition,
            state.h2e.selectedItemPosition,
            rawBssid,
            suppliedPassword,
        )
    ) {
        Log.d(TAG, "No user changes: leaving the native WifiConfiguration and policy untouched")
        return null
    }
    val ssid = config.SSID ?: state.ssidFromEntry ?: return null
    val mode = WifiSecurityMode.entries[state.security.selectedItemPosition]
    // Xiaomi's manual-add screen can have its stock password input hidden when
    // changing an Open network to WPA2/WPA3 through this independent selector.
    // Reject an incomplete secured profile rather than silently saving it.
    if (securityChanged && mode in setOf(
            WifiSecurityMode.WPA2,
            WifiSecurityMode.WPA3,
            WifiSecurityMode.TRANSITION,
        ) &&
        config.preSharedKey.isNullOrBlank() && suppliedPassword.isBlank()
    ) {
        state.password?.error = s(state.context, R.string.wifi_password_invalid)
        throw IllegalArgumentException("Security mode requires a password")
    }
    if (rawBssid.isNotEmpty() && rawBssid != "*" && !WifiSecurityPolicy.isValidBssid(rawBssid)) {
        state.bssid.error = s(state.context, R.string.wifi_bssid_invalid)
        throw IllegalArgumentException("Invalid BSSID")
    }
    val requiresPmf = if (securityChanged) {
        mode == WifiSecurityMode.WPA3 || mode == WifiSecurityMode.OWE
    } else {
        val types = state.initialSecurityTypes
        (4 in types && 2 !in types) || (6 in types && 0 !in types)
    }
    if (state.pmf.selectedItemPosition == 2 && requiresPmf) {
        throw IllegalArgumentException("The selected Wi-Fi security mode requires PMF")
    }
    if (suppliedPassword.isNotEmpty()) {
        val isRawPsk = mode == WifiSecurityMode.WPA2 && suppliedPassword.matches(Regex("^[0-9a-fA-F]{64}$"))
        if (!isRawPsk && (suppliedPassword.length < 8 || suppliedPassword.length > 63)) {
            state.password?.error = s(state.context, R.string.wifi_password_invalid)
            throw IllegalArgumentException("Invalid pre-shared key length")
        }
    }
    // Modify a copy. If validation/reflection fails, the original host result is untouched.
    val modified = if (Build.VERSION.SDK_INT >= 30) {
        WifiConfiguration(config)
    } else {
        val parcel = Parcel.obtain()
        try {
            config.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val creator = WifiConfiguration::class.java.getField("CREATOR").get(null) as Parcelable.Creator<WifiConfiguration>
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
    if (securityChanged) WifiConfigurationOverride.applySecurity(modified, mode)
    if (suppliedPassword.isNotEmpty()) {
        val isRawPsk = mode == WifiSecurityMode.WPA2 && suppliedPassword.matches(Regex("^[0-9a-fA-F]{64}$"))
        modified.preSharedKey = if (isRawPsk) suppliedPassword else "\"$suppliedPassword\""
    }
    if (rawBssid == "*") {
        modified.BSSID = null
    } else if (rawBssid.isNotEmpty()) {
        modified.BSSID = rawBssid.lowercase()
    }
    if (pmfChanged) WifiConfigurationOverride.applyPmf(modified, state.pmf.selectedItemPosition == 1)
    if (h2eChanged) WifiConfigurationOverride.applySaeH2eOnly(modified, state.h2e.selectedItemPosition == 1)
    Log.i(TAG, "Prepared only explicitly modified Wi-Fi fields (no policy committed yet)")
    return EditedConfig(
        modified,
        ssid,
        WifiSaveExpectation(
            mode = mode.takeIf { securityChanged },
            pmf = (state.pmf.selectedItemPosition == 1).takeIf { pmfChanged },
            h2e = (state.h2e.selectedItemPosition == 1).takeIf { h2eChanged },
            bssidChanged = rawBssid.isNotEmpty(),
            bssid = modified.BSSID,
        ),
    )
}
