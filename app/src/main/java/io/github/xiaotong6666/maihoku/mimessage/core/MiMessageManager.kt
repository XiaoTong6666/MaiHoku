package io.github.xiaotong6666.maihoku.mimessage.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import io.github.xiaotong6666.maihoku.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.mimessage.message.MiMessageOutboxStore
import io.github.xiaotong6666.maihoku.mimessage.presence.MiMessagePresencePolicy
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Entry point for the MaiHoku-UID Push core. Call from MaiHoku's own process, not a host app UID. */
internal object MiMessageManager {
    private const val PREFS = "maihoku_mx_runtime"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PUSH_STATE = "push_state"
    private const val KEY_PUSH_UPDATED_AT = "push_updated_at"
    private const val KEY_PUSH_PROCESS_ID = "push_process_id"
    private const val KEY_BOUND_SUBSCRIPTION_ID = "bound_subscription_id"
    private const val KEY_BOUND_SIM_HASH = "bound_sim_hash"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        try {
            enable(appContext)
            recordPushState(appContext, MiMessageRuntimeStatus.CONNECTING)
            startService(appContext)
        } catch (error: Throwable) {
            disable(appContext)
            runCatching { recordPushState(appContext, MiMessageRuntimeStatus.FAILED) }
            throw error
        }
    }

    /** Restores an explicitly enabled Push service after boot, unlock, or package replacement. */
    fun resumeIfEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return false
        return runCatching {
            startService(appContext)
            true
        }.getOrElse { error ->
            recordPushState(appContext, MiMessageRuntimeStatus.DISCONNECTED)
            throw error
        }
    }

    private fun startService(context: Context) {
        val intent = Intent(context, MiMessageCoreService::class.java).setAction(MiMessageCoreService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        disable(appContext)
        try {
            recordPushState(appContext, MiMessageRuntimeStatus.DISCONNECTED)
        } finally {
            appContext.stopService(Intent(appContext, MiMessageCoreService::class.java))
        }
    }

    /** Request a contact Presence check through the already-running (or explicitly enabled) Core Service. */
    fun requestPresence(context: Context, phoneNumber: String) {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) {
            Log.d(TAG, "Ignored Presence request because Mi Message is disabled")
            return
        }
        val phoneKey = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return
        Log.d(TAG, "Starting Core Service for Presence request recipient_digits=${phoneKey.count(Char::isDigit)}")
        val intent = Intent(appContext, MiMessageCoreService::class.java)
            .setAction(MiMessageCoreService.ACTION_QUERY_PRESENCE)
            .putExtra(MiMessageCoreService.EXTRA_PRESENCE_PHONE, phoneNumber)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    /** Queue one host-originated Mi Message request in the long-lived ChID=3 Core Service. */
    fun queueOutgoing(
        context: Context,
        requestId: String,
        hostPackage: String,
        phoneNumber: String,
        body: String,
    ): Boolean {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return false
        require(MiMessageInboxBridge.isSupportedHost(hostPackage))
        require(requestId.matches(Regex("[A-Za-z0-9_-]{16,64}")))
        require(MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) != null)
        require(body.isNotEmpty() && body.length <= MiMessageOutboxStore.MAX_BODY_CHARS)
        val intent = Intent(appContext, MiMessageCoreService::class.java)
            .setAction(MiMessageCoreService.ACTION_QUEUE_OUTGOING)
            .putExtra(MiMessageCoreService.EXTRA_OUTGOING_REQUEST_ID, requestId)
            .putExtra(MiMessageCoreService.EXTRA_OUTGOING_HOST_PACKAGE, hostPackage)
            .putExtra(MiMessageCoreService.EXTRA_OUTGOING_PHONE, phoneNumber)
            .putExtra(MiMessageCoreService.EXTRA_OUTGOING_BODY, body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        return true
    }

    private const val TAG = "MiMessageManager"

    fun recordPushState(context: Context, state: String) {
        check(
            preferences(context).edit()
                .putString(KEY_PUSH_STATE, state)
                .putLong(KEY_PUSH_UPDATED_AT, System.currentTimeMillis())
                .putInt(KEY_PUSH_PROCESS_ID, Process.myPid())
                .commit(),
        ) { "Could not persist Mi Message Push runtime state" }
    }

    fun recordBoundIdentity(context: Context, subscriptionId: Int, simIdHash: String) {
        require(subscriptionId > 0)
        require(simIdHash.matches(Regex("[A-Za-z0-9_-]{16}")))
        check(
            preferences(context).edit()
                .putInt(KEY_BOUND_SUBSCRIPTION_ID, subscriptionId)
                .putString(KEY_BOUND_SIM_HASH, simIdHash)
                .commit(),
        ) { "Could not persist the SIM identity bound to the Push connection" }
    }

    fun runtimeSnapshot(context: Context): Snapshot {
        val prefs = preferences(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val storedState = prefs.getString(KEY_PUSH_STATE, MiMessageRuntimeStatus.DISCONNECTED)
            ?: MiMessageRuntimeStatus.DISCONNECTED
        val processId = prefs.getInt(KEY_PUSH_PROCESS_ID, -1)
        return Snapshot(
            enabled = enabled,
            pushState = MiMessageRuntimeStatus.effectiveState(enabled, storedState, processId, Process.myPid()),
            updatedAt = prefs.getLong(KEY_PUSH_UPDATED_AT, 0L),
            boundSubscriptionId = prefs.getInt(KEY_BOUND_SUBSCRIPTION_ID, -1),
            boundSimIdHash = prefs.getString(KEY_BOUND_SIM_HASH, null),
        )
    }

    /** Stop a live connection if a fresh SIM snapshot or subscription mapping no longer matches it. */
    fun invalidateBindingIfChanged(
        context: Context,
        currentSubscriptionId: Int?,
        currentSimIdHash: String?,
    ): Boolean {
        val snapshot = runtimeSnapshot(context)
        if (!snapshot.enabled || snapshot.boundSubscriptionId <= 0) return false
        val reason = when {
            currentSubscriptionId == null || currentSubscriptionId <= 0 -> MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE

            currentSubscriptionId != snapshot.boundSubscriptionId -> MiMessageRuntimeStatus.SIM_CHANGED

            !currentSimIdHash.isNullOrBlank() && !snapshot.boundSimIdHash.isNullOrBlank() &&
                currentSimIdHash != snapshot.boundSimIdHash -> MiMessageRuntimeStatus.SIM_CHANGED

            else -> null
        } ?: return false

        disable(context)
        recordPushState(context, reason)
        context.applicationContext.stopService(Intent(context, MiMessageCoreService::class.java))
        return true
    }

    fun disableForSimChange(context: Context, state: String) {
        require(state == MiMessageRuntimeStatus.SIM_CHANGED || state == MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE)
        disable(context)
        recordPushState(context, state)
    }

    fun disableForStartupFailure(context: Context, state: String) {
        require(
            state == MiMessageRuntimeStatus.FAILED ||
                state == MiMessageRuntimeStatus.SIM_CHANGED ||
                state == MiMessageRuntimeStatus.SIM_IDENTITY_UNAVAILABLE,
        )
        disable(context)
        recordPushState(context, state)
    }

    internal fun enable(context: Context) {
        check(preferences(context).edit().putBoolean(KEY_ENABLED, true).commit()) {
            "Could not persist Mi Message enabled state"
        }
    }

    internal fun disableAfterFatalError(context: Context) {
        disable(context)
    }

    private fun disable(context: Context) {
        preferences(context).edit().putBoolean(KEY_ENABLED, false).commit()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val enabled: Boolean,
        val pushState: String,
        val updatedAt: Long,
        val boundSubscriptionId: Int,
        val boundSimIdHash: String?,
    )
}
