package io.github.xiaotong6666.maihoku.features.wifi.core

import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import io.github.xiaotong6666.maihoku.features.wifi.policy.WifiSecurityMode
import java.lang.reflect.Method

/** Uses framework security parameters rather than editing WifiConfigStore.xml. */
internal object WifiConfigurationOverride {
    private val configClass = WifiConfiguration::class.java
    private val setByType: Method by lazy {
        configClass.getDeclaredMethod("setSecurityParams", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
    }

    private val getByType: Method by lazy { resolveGetByType() }

    // These non-SDK methods are intentionally resolved in hooked Settings/system_server,
    // not called by an ordinary app. Unsupported host signatures still fail at runtime.
    @SuppressLint("BlockedPrivateApi")
    private fun resolveGetByType(): Method = configClass.getDeclaredMethod("getSecurityParams", Int::class.javaPrimitiveType).apply {
        isAccessible = true
    }

    fun applySecurity(config: WifiConfiguration, mode: WifiSecurityMode) {
        when (mode) {
            WifiSecurityMode.SYSTEM -> Unit

            WifiSecurityMode.WPA2, WifiSecurityMode.WPA3,
            WifiSecurityMode.OPEN, WifiSecurityMode.OWE,
            ->
                setByType.invoke(config, mode.securityType!!)

            WifiSecurityMode.TRANSITION -> {
                setByType.invoke(config, 2)
                // Explicitly configured PSK+SAE; neither is an auto-upgrade placeholder.
                val params = createSecurityParams(4)
                configClass.getDeclaredMethod("addSecurityParams", params.javaClass)
                    .apply { isAccessible = true }.invoke(config, params)
            }
        }
        if (mode == WifiSecurityMode.OPEN || mode == WifiSecurityMode.OWE) {
            config.preSharedKey = null
        }
    }

    /** Update legacy field AND each SecurityParams copy, then replace the complete list. */
    fun applyPmf(config: WifiConfiguration, required: Boolean) {
        val params = securityParamsCopies(config)
        if (params.isEmpty()) throw IllegalStateException("Missing security parameters")
        val types = params.map {
            it.javaClass.getMethod("getSecurityType").invoke(it) as Int
        }
        val mandatory = setOf(4, 6, 9, 10)
        if (!required && types.all { it in mandatory }) {
            throw IllegalArgumentException("All selected security types require PMF")
        }
        for ((param, type) in params.zip(types)) {
            // Mixed WPA2/SAE can leave WPA2 PMF optional while SAE remains required.
            param.javaClass.getMethod("setRequirePmf", Boolean::class.javaPrimitiveType)
                .invoke(param, required || type in mandatory)
        }
        setSecurityParamsList(config, params)
        // Modern framework releases may drop the deprecated legacy field.
        runCatching {
            configClass.getDeclaredField("requirePmf").apply { isAccessible = true }
                .setBoolean(config, required)
        }
    }

    /** WifiConfiguration.getSecurityParams(type) returns a COPY; this edits the real object. */
    @SuppressLint("BlockedPrivateApi")
    fun applySaeH2eOnly(config: WifiConfiguration, enabled: Boolean) {
        val method = configClass.getDeclaredMethod("enableSaeH2eOnlyMode", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        if (getByType.invoke(config, 4) == null) {
            throw IllegalArgumentException("SAE security parameters not present")
        }
        method.invoke(config, enabled)
    }

    fun verifySaeH2eOnly(config: WifiConfiguration, expected: Boolean): Boolean {
        val sae = getByType.invoke(config, 4) ?: return false
        return (sae.javaClass.getMethod("isSaeH2eOnlyMode").invoke(sae) as? Boolean) == expected
    }

    @SuppressLint("BlockedPrivateApi")
    fun securityParamsCopies(config: WifiConfiguration): List<Any> {
        val method = configClass.getDeclaredMethod("getSecurityParamsList").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val params = method.invoke(config) as List<Any>
        return params.map { p ->
            p.javaClass.getConstructor(p.javaClass).newInstance(p)
        }
    }

    fun setSecurityParamsList(config: WifiConfiguration, params: List<Any>) {
        val setter = configClass.declaredMethods.singleOrNull {
            it.name == "setSecurityParams" && it.parameterTypes.size == 1 &&
                java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: throw NoSuchMethodException("setSecurityParams(List)")
        setter.isAccessible = true
        setter.invoke(config, params)
    }

    fun applyMacRandomization(config: WifiConfiguration, setting: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            config.macRandomizationSetting = setting
        } else {
            throw UnsupportedOperationException("MAC randomization configuration requires API 33")
        }
    }

    /** Invoked only by the system_server hook for SSIDs explicitly pinned to WPA2 / Open. */
    fun keepOnlySecurityType(config: WifiConfiguration, targetType: Int): Boolean {
        val desired = getByType.invoke(config, targetType) ?: return false
        setSecurityParamsList(config, listOf(desired))
        return true
    }

    data class SavedFlags(val type: Int, val pmf: Boolean?, val h2e: Boolean?)

    fun savedFlags(config: WifiConfiguration): List<SavedFlags> = runCatching {
        securityParamsCopies(config).filter { param ->
            param.javaClass.getMethod("isEnabled").invoke(param) == true
        }.map { param ->
            val type = param.javaClass.getMethod("getSecurityType").invoke(param) as Int
            val pmf = runCatching { param.javaClass.getMethod("isRequirePmf").invoke(param) as Boolean }.getOrNull()
            val h2e = if (type == 4) {
                runCatching {
                    param.javaClass.getMethod("isSaeH2eOnlyMode").invoke(param) as Boolean
                }.getOrNull()
            } else {
                null
            }
            SavedFlags(type, pmf, h2e)
        }
    }.getOrDefault(emptyList())

    private fun createSecurityParams(type: Int): Any = Class.forName("android.net.wifi.SecurityParams")
        .getMethod("createSecurityParamsBySecurityType", Int::class.javaPrimitiveType)
        .invoke(null, type)
}
