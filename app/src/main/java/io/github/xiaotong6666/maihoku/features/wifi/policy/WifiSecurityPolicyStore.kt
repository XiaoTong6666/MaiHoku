package io.github.xiaotong6666.maihoku.features.wifi.policy

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.util.Locale
import java.util.UUID

/**
 * Profiles are keyed by stable, versioned Wi-Fi identity components, NOT networkId.
 * Android reassigns WifiConfiguration.networkId when restoring WifiConfigStore on boot.
 * Do not persist framework getProfileKey()/getKey(): upstream warns their format can change.
 */
internal object WifiSecurityPolicyStore {
    private const val PROFILE_PREFIX = "maihoku.wifi.security.v3."

    fun keyForProfile(
        ssid: String,
        securityTypes: List<Int>,
        creatorUid: Int,
        shared: Boolean,
        bssid: String?,
    ): String? {
        if (securityTypes.isEmpty()) return null
        val signature = buildString {
            append(WifiSecurityPolicy.normalizeSsid(ssid))
            append('\u0000')
            append(securityTypes.distinct().sorted().joinToString(","))
            append('\u0000')
            append(creatorUid)
            append('\u0000')
            append(if (shared) 'S' else 'P')
            append('\u0000')
            append(bssid.orEmpty().lowercase(Locale.ROOT))
        }
        return PROFILE_PREFIX + WifiSecurityPolicy.hashIdentity(signature)
    }

    fun keyForProfile(config: WifiConfiguration): String? = keyForProfile(
        config.SSID.orEmpty(),
        declaredSecurityTypes(config),
        intField(config, "creatorUid", -1),
        boolField(config, "shared", true),
        config.BSSID,
    )

    /** New networks receive their creator UID only after they are saved by WifiManager. */
    fun keyForSubmission(config: WifiConfiguration): String? = keyForProfile(
        config.SSID.orEmpty(),
        declaredSecurityTypes(config),
        if (config.networkId < 0) Process.myUid() else intField(config, "creatorUid", Process.myUid()),
        boolField(config, "shared", true),
        config.BSSID,
    )

    /** Called only after a successful WifiManager action; the key holds no credentials. */
    fun putByKey(context: Context, key: String, mode: WifiSecurityMode): Boolean = key.startsWith(PROFILE_PREFIX) && Settings.Global.putString(
        context.contentResolver,
        key,
        mode.value,
    )

    // A short-lived intent is visible to system_server only during the current boot.
    private fun transientKey(key: String) = key.replace(PROFILE_PREFIX, "maihoku.wifi.pending.v1.")
    private fun boot(context: Context): Int = Settings.Global.getInt(context.contentResolver, "boot_count", -1)

    fun beginSubmission(context: Context, key: String?, mode: WifiSecurityMode): String? {
        if (key == null || !key.startsWith(PROFILE_PREFIX)) return null
        val boot = boot(context)
        if (boot < 0) return null
        val token = "${mode.value}:$boot:${SystemClock.elapsedRealtime()}:${UUID.randomUUID()}"
        return if (Settings.Global.putString(context.contentResolver, transientKey(key), token)) token else null
    }

    fun isCurrentSubmission(context: Context, key: String, token: String): Boolean = Settings.Global.getString(context.contentResolver, transientKey(key)) == token &&
        WifiPendingToken.mode(token, boot(context), SystemClock.elapsedRealtime()) != null

    fun endSubmission(context: Context, key: String?, token: String?) {
        if (key == null || token == null) return
        val pendingKey = transientKey(key)
        if (Settings.Global.getString(context.contentResolver, pendingKey) == token) {
            Settings.Global.putString(context.contentResolver, pendingKey, null)
        }
    }

    fun pendingMode(context: Context, config: WifiConfiguration): WifiSecurityMode? {
        val key = keyForProfile(config) ?: return null
        return WifiPendingToken.mode(
            Settings.Global.getString(context.contentResolver, transientKey(key)),
            boot(context),
            SystemClock.elapsedRealtime(),
        )
    }

    /** Ignore automatic SAE/OWE candidates: these must not change the profile's identity. */
    fun declaredSecurityTypes(config: WifiConfiguration): List<Int> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val params = WifiConfiguration::class.java.getMethod("getSecurityParamsList")
            .invoke(config) as? List<Any> ?: return@runCatching emptyList()
        params.filter { param ->
            val type = param.javaClass
            val enabled = runCatching { type.getMethod("isEnabled").invoke(param) as Boolean }
                .getOrDefault(true)
            val auto = runCatching { type.getMethod("isAddedByAutoUpgrade").invoke(param) as Boolean }
                .getOrDefault(false)
            enabled && !auto
        }.mapNotNull { param ->
            runCatching { param.javaClass.getMethod("getSecurityType").invoke(param) as Int }
                .getOrNull()
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    /** Includes auto-added SAE/OWE: a user-requested *only* mode must not leave them enabled. */
    fun enabledSecurityTypes(config: WifiConfiguration): List<Int> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val params = WifiConfiguration::class.java.getMethod("getSecurityParamsList")
            .invoke(config) as? List<Any> ?: return@runCatching emptyList()
        params.filter { param ->
            runCatching { param.javaClass.getMethod("isEnabled").invoke(param) as Boolean }
                .getOrDefault(true)
        }.mapNotNull { param ->
            runCatching { param.javaClass.getMethod("getSecurityType").invoke(param) as Int }
                .getOrNull()
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    private fun intField(config: WifiConfiguration, name: String, default: Int): Int = runCatching {
        WifiConfiguration::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(config)
    }.getOrDefault(default)

    private fun boolField(config: WifiConfiguration, name: String, default: Boolean): Boolean = runCatching {
        WifiConfiguration::class.java.getDeclaredField(name).apply { isAccessible = true }.getBoolean(config)
    }.getOrDefault(default)

    fun get(context: Context, config: WifiConfiguration, allowLegacyMigration: Boolean = false): WifiSecurityMode {
        val key = keyForProfile(config) ?: return WifiSecurityMode.SYSTEM
        val stored = Settings.Global.getString(context.contentResolver, key)
        if (stored != null) return WifiSecurityMode.fromStorage(stored)
        // Do not make binder calls from inside the Wi-Fi service; that can deadlock system_server.
        if (!allowLegacyMigration) return WifiSecurityMode.SYSTEM
        val rows = savedNetworks(context, config.SSID.orEmpty())
        return storedModeForProfiles(
            context,
            rows.filter { it.networkId == config.networkId },
            rows.map { it.networkId }.distinct().size == 1,
        ) ?: WifiSecurityMode.SYSTEM
    }

    /** OEM split rows may expose several keys for the same saved profile. */
    fun storedModeForProfiles(context: Context, rows: List<WifiConfiguration>, uniqueSsid: Boolean): WifiSecurityMode? {
        if (rows.isEmpty()) return null
        val modes = profileKeys(rows).mapNotNull { storedMode(context, it) }.distinct()
        if (modes.isNotEmpty()) return modes.singleOrNull()
        if (!uniqueSsid) return null
        val config = rows.first()
        val ssid = config.SSID.orEmpty()
        val oldV2 = Settings.Global.getString(
            context.contentResolver,
            "maihoku.wifi.security.v2." + WifiSecurityPolicy.hashIdentity(WifiSecurityPolicy.normalizeSsid(ssid) + "\u0000" + config.networkId),
        )
        return (
            oldV2 ?: Settings.Global.getString(
                context.contentResolver,
                WifiSecurityPolicy.keyForSsid(ssid),
            )
            )?.let(WifiSecurityMode::fromStorage)
    }

    fun storedMode(context: Context, key: String): WifiSecurityMode? = Settings.Global.getString(context.contentResolver, key)?.let(WifiSecurityMode::fromStorage)

    fun clearKey(context: Context, key: String): Boolean = key.startsWith(PROFILE_PREFIX) && Settings.Global.putString(context.contentResolver, key, null)

    fun clearLegacy(context: Context, ssid: String, id: Int, lastProfile: Boolean): Boolean {
        val v2 = Settings.Global.putString(
            context.contentResolver,
            "maihoku.wifi.security.v2." + WifiSecurityPolicy.hashIdentity(WifiSecurityPolicy.normalizeSsid(ssid) + "\u0000" + id),
            null,
        )
        val v1 = !lastProfile || Settings.Global.putString(context.contentResolver, WifiSecurityPolicy.keyForSsid(ssid), null)
        return v1 && v2
    }

    fun savedNetworksById(context: Context, id: Int): List<WifiConfiguration> = if (id < 0) emptyList() else readSavedNetworks(context).orEmpty().filter { it.networkId == id }

    private fun identities(configs: List<WifiConfiguration>): List<WifiProfileIdentity> = configs.map { c ->
        WifiProfileIdentity(
            c.networkId,
            c.SSID.orEmpty(),
            declaredSecurityTypes(c),
            intField(c, "creatorUid", -1),
            boolField(c, "shared", true),
            c.BSSID,
        )
    }

    /** Include a combined key for OEMs returning separate PSK/SAE rows for one network. */
    fun profileKeys(configs: List<WifiConfiguration>): Set<String> = WifiProfileRows.keys(identities(configs))

    fun canonicalKey(configs: List<WifiConfiguration>): String? = WifiProfileRows.canonicalKey(identities(configs))

    /** Never call WifiManager from inside the service hook. */
    @android.annotation.SuppressLint("MissingPermission")
    fun readSavedNetworks(context: Context): List<WifiConfiguration>? = runCatching {
        // Uses the host app's permissions; denied access returns null.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.configuredNetworks?.filter { it.networkId >= 0 }
    }.getOrNull()

    fun savedNetworks(context: Context, ssid: String): List<WifiConfiguration> = readSavedNetworks(context).orEmpty().filter {
        WifiSecurityPolicy.normalizeSsid(it.SSID.orEmpty()) == WifiSecurityPolicy.normalizeSsid(ssid)
    }
}
