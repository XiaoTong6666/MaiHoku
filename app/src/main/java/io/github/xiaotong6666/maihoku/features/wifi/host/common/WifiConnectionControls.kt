package io.github.xiaotong6666.maihoku.features.wifi.host.common

import android.content.Context
import android.widget.EditText
import android.widget.Spinner
import java.util.WeakHashMap

internal object WifiConnectionState {
    private val controls = WeakHashMap<Any, Controls>()

    @Synchronized
    fun contains(owner: Any): Boolean = controls.containsKey(owner)

    @Synchronized
    fun get(owner: Any): Controls? = controls[owner]

    @Synchronized
    fun put(owner: Any, state: Controls) {
        controls[owner] = state
    }
}

internal data class Controls(
    val context: Context,
    val ssidFromEntry: String?,
    val security: Spinner,
    val pmf: Spinner,
    val bssid: EditText,
    val h2e: Spinner,
    val password: EditText?,
    val initialSecurity: Int,
    val initialSecurityTypes: List<Int>,
)
