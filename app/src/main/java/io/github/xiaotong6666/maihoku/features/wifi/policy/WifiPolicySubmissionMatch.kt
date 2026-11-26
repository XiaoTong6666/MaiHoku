package io.github.xiaotong6666.maihoku.features.wifi.policy

/** Fail-closed identity matching for parcelled WifiConfiguration submissions. */
internal object WifiPolicySubmissionMatch {
    fun obsoleteKeys(previous: Set<String>, retained: Set<String>, otherProfiles: Set<String>): Set<String> = previous - retained - otherProfiles

    fun matches(expectedKey: String?, actualKey: String?, expectedId: Int, actualId: Int): Boolean {
        if (expectedKey == null || actualKey == null || expectedKey != actualKey) return false
        return expectedId < 0 || actualId < 0 || expectedId == actualId
    }
}

/** The save callback only confirms acceptance, not the resulting persisted security shape. */
internal object WifiSavedSecurityVerification {
    fun matches(mode: WifiSecurityMode, enabledTypes: Set<Int>): Boolean = when (mode) {
        WifiSecurityMode.SYSTEM -> enabledTypes.isNotEmpty()
        WifiSecurityMode.WPA2 -> enabledTypes == setOf(2)
        WifiSecurityMode.WPA3 -> enabledTypes == setOf(4)
        WifiSecurityMode.TRANSITION -> enabledTypes == setOf(2, 4)
        WifiSecurityMode.OPEN -> enabledTypes == setOf(0)
        WifiSecurityMode.OWE -> enabledTypes == setOf(6)
    }
}

/** Only the latest in-flight operation for a network ID or exact profile may publish. */
internal class WifiSubmissionGenerations {
    private val active = mutableMapOf<String, Long>()
    private var next = 0L

    fun begin(aliases: Set<String>): Long {
        val generation = ++next
        val replaced = aliases.mapNotNull(active::get).toSet()
        active.entries.removeAll { it.value in replaced }
        aliases.forEach { active[it] = generation }
        return generation
    }

    fun isCurrent(generation: Long, aliases: Set<String>): Boolean = aliases.isNotEmpty() && aliases.all { active[it] == generation }

    fun finish(generation: Long) {
        active.entries.removeAll { it.value == generation }
    }
}

/** A boot-relative deadline must not be interpreted in a different boot. */
internal object WifiPendingToken {
    fun mode(raw: String?, boot: Int, now: Long): WifiSecurityMode? {
        val parts = raw?.split(':') ?: return null
        if (parts.size != 4 || boot < 0 || parts[1].toIntOrNull() != boot) return null
        val started = parts[2].toLongOrNull() ?: return null
        if (started < 0 || started > now || now - started > 20_000L || parts[3].isBlank()) return null
        return WifiSecurityMode.entries.firstOrNull { it.value == parts[0] }
    }
}

/** Expectations contain no password or other credential. Null means untouched. */
internal data class WifiSaveExpectation(
    val mode: WifiSecurityMode?,
    val pmf: Boolean? = null,
    val h2e: Boolean? = null,
    val bssidChanged: Boolean = false,
    val bssid: String? = null,
) {
    fun matches(
        enabledTypes: Set<Int>,
        pmfByType: Map<Int, Set<Boolean?>>,
        saeH2e: Set<Boolean?>,
        bssids: Set<String?>,
    ): Boolean {
        if (enabledTypes.isEmpty() || (mode != null && !WifiSavedSecurityVerification.matches(mode, enabledTypes))) return false
        if (pmf != null && enabledTypes.any { type ->
                pmfByType[type] != setOf(pmf || type in setOf(4, 6, 9, 10))
            }
        ) {
            return false
        }
        if (h2e != null && (4 !in enabledTypes || saeH2e != setOf(h2e))) return false
        if (bssidChanged && bssids != setOf(bssid?.lowercase(java.util.Locale.ROOT))) return false
        return true
    }
}

/** One configured-network row. OEMs may return separate security rows for one networkId. */
internal data class WifiProfileIdentity(
    val id: Int,
    val ssid: String,
    val types: List<Int>,
    val creatorUid: Int,
    val shared: Boolean,
    val bssid: String?,
) {
    fun key(types: List<Int> = this.types): String? = WifiSecurityPolicyStore.keyForProfile(ssid, types, creatorUid, shared, bssid)
}

internal object WifiProfileRows {
    fun canonicalKey(rows: List<WifiProfileIdentity>): String? {
        if (rows.isEmpty() || rows.map { it.id }.distinct().size != 1) return null
        val types = rows.flatMap { it.types }.distinct()
        return rows.map { it.key(types) }.distinct().singleOrNull()
    }

    fun keys(rows: List<WifiProfileIdentity>): Set<String> = rows.mapNotNull { it.key() }.toSet() + listOfNotNull(canonicalKey(rows))
}
