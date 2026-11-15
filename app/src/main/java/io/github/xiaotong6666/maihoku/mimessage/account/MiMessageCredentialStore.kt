package io.github.xiaotong6666.maihoku.mimessage.account

import android.content.Context
import android.content.SharedPreferences
import io.github.xiaotong6666.maihoku.mimessage.core.MiMessageConfig

/**
 * Stores Mi Message credentials as plain strings in MaiHoku's private SharedPreferences.
 *
 * This deliberately does not use Android Keystore or application-layer encryption. The file is
 * protected by MaiHoku's Android UID sandbox from ordinary third-party apps, but can be read on a
 * rooted/debuggable device or after compromise of MaiHoku itself. Google Messages must use the
 * account bridge and must never receive credential values.
 */
internal object MiMessageCredentialStore {
    private const val PREFERENCES_NAME = "maihoku_mx_credentials"

    fun get(context: Context, key: String): String? = preferences(context).getString(key, null)

    fun put(context: Context, key: String, value: String): Boolean = putAll(context, mapOf(key to value))

    /** Commits related activation and service credentials together. */
    fun putAll(context: Context, values: Map<String, String>): Boolean {
        val editor = preferences(context).edit()
        for ((key, value) in values) {
            if (value.isEmpty()) editor.remove(key) else editor.putString(key, value)
        }
        return editor.commit()
    }

    @Synchronized
    private fun preferences(context: Context): SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("schema_version", 0) >= 1) return prefs

        // Migrate development snapshots once. Normal readers only understand the current schema.
        val editor = prefs.edit()
        for (slot in 0..1) {
            val stateKey = MiMessageConfig.simKey(slot, "identity_state")
            val simHashKey = MiMessageConfig.simKey(slot, "activation_sim_id_hash")
            val userIdKey = MiMessageConfig.simKey(slot, "sim_user_id")
            if (prefs.getString(stateKey, null) == "m2c_pending") {
                editor.putString(stateKey, MiMessageIdentityReadiness.AWAITING_MIXIN_CREDENTIALS)
            }
            if (!prefs.getString(MiMessageConfig.simKey(slot, "sim_pass_token"), null).isNullOrBlank()) {
                if (!prefs.contains(simHashKey)) {
                    prefs.getString(MiMessageConfig.simKey(slot, "sim_id"), null)
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16}")) }
                        ?.let { editor.putString(simHashKey, it) }
                }
                if (!prefs.contains(userIdKey)) {
                    prefs.getString(MiMessageConfig.simKey(slot, "mid"), null)?.takeIf(String::isNotBlank)
                        ?.let { editor.putString(userIdKey, it) }
                }
            }
        }
        check(editor.putInt("schema_version", 1).commit()) { "Could not migrate Mi Message credential storage" }
        return prefs
    }
}
