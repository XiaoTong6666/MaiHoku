package io.github.xiaotong6666.maihoku.features.wifi.core

/** Pure decision function: opening or saving a dialog without edits must not touch its configuration. */
internal object WifiEditDetection {
    fun hasChanges(
        initialSecurity: Int,
        selectedSecurity: Int,
        selectedPmf: Int,
        selectedH2e: Int,
        bssidText: String,
        additionalPassword: String,
    ): Boolean = initialSecurity != selectedSecurity ||
        selectedPmf != 0 || selectedH2e != 0 ||
        bssidText.isNotBlank() || additionalPassword.isNotEmpty()
}
