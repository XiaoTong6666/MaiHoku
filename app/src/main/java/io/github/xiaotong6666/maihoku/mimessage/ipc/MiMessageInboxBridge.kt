package io.github.xiaotong6666.maihoku.mimessage.ipc

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import io.github.xiaotong6666.maihoku.mimessage.message.StoredIncomingMessage
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Token-authenticated message, Presence and send-status IPC with the default SMS app. */
internal object MiMessageInboxBridge {
    const val ACTION_IMPORT_INCOMING = "io.github.xiaotong6666.maihoku.action.IMPORT_INCOMING_NETWORK_SMS"
    const val ACTION_REGISTER_HOST = "io.github.xiaotong6666.maihoku.action.REGISTER_MESSAGING_INBOX_BRIDGE"
    const val ACTION_MARK_IMPORTED = "io.github.xiaotong6666.maihoku.action.MARK_NETWORK_SMS_IMPORTED"
    const val ACTION_MARK_IMPORT_FAILED = "io.github.xiaotong6666.maihoku.action.MARK_NETWORK_SMS_IMPORT_FAILED"
    const val ACTION_QUERY_PRESENCE = "io.github.xiaotong6666.maihoku.action.QUERY_MI_MESSAGE_PRESENCE"
    const val ACTION_PRESENCE_CHANGED = "io.github.xiaotong6666.maihoku.action.MI_MESSAGE_PRESENCE_CHANGED"
    const val ACTION_QUEUE_OUTGOING = "io.github.xiaotong6666.maihoku.action.QUEUE_MI_MESSAGE_OUTGOING"
    const val ACTION_OUTGOING_STATUS = "io.github.xiaotong6666.maihoku.action.MI_MESSAGE_OUTGOING_STATUS"

    const val KEY_HOST_PACKAGE = "host_package"
    const val KEY_TOKEN = "bridge_token"
    const val KEY_MESSAGE_ID = "message_id"
    const val KEY_PACKET_ID = "packet_id"
    const val KEY_SENDER_MID = "sender_mid"
    const val KEY_SENDER_JID = "sender_jid"
    const val KEY_RECIPIENT_MID = "recipient_mid"
    const val KEY_ADDRESS = "address"
    const val KEY_BODY = "body"
    const val KEY_SENT_AT = "sent_at"
    const val KEY_RECEIVED_AT = "received_at"
    const val KEY_SUBSCRIPTION_ID = "subscription_id"
    const val KEY_TELEPHONY_URI = "telephony_uri"
    const val KEY_ERROR = "error"
    const val KEY_PRESENCE_PHONE = "presence_phone"
    const val KEY_PRESENCE_STATUS = "presence_status"
    const val KEY_PRESENCE_CAPABILITIES = "presence_capabilities"
    const val KEY_PRESENCE_EXPIRES_AT = "presence_expires_at"
    const val KEY_OUTGOING_REQUEST_ID = "outgoing_request_id"
    const val KEY_OUTGOING_STATUS = "outgoing_status"
    const val KEY_OUTGOING_ERROR = "outgoing_error"

    private const val PREFS = "maihoku_mi_inbox_bridge"
    private const val TOKEN_PREFIX = "host_token:"
    private const val TAG = "MiMessageInbox"

    fun dispatch(context: Context, message: StoredIncomingMessage): Boolean {
        val appContext = context.applicationContext
        val hostPackage = defaultSmsHostPackage(appContext)
            ?.takeIf(::isSupportedHost)
            ?: run {
                Log.w(TAG, "SMS provider import deferred: no supported default SMS app")
                return false
            }
        val token = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_PREFIX + hostPackage, null)
            ?.takeIf(String::isNotBlank)
            ?: run {
                Log.i(TAG, "SMS provider import deferred: default SMS app has not registered its inbox bridge")
                return false
            }

        return dispatchToHost(appContext, hostPackage, token, message)
    }

    fun dispatchToHost(context: Context, hostPackage: String, token: String, message: StoredIncomingMessage): Boolean {
        if (!isSupportedHost(hostPackage) || token.isBlank()) return false
        val intent = Intent(ACTION_IMPORT_INCOMING)
            .setPackage(hostPackage)
            .putExtra(KEY_TOKEN, token)
            .putExtra(KEY_MESSAGE_ID, message.messageId)
            .putExtra(KEY_PACKET_ID, message.packetId)
            .putExtra(KEY_SENDER_MID, message.senderMid)
            .putExtra(KEY_SENDER_JID, message.senderJid)
            .putExtra(KEY_RECIPIENT_MID, message.recipientMid)
            .putExtra(KEY_ADDRESS, message.address)
            .putExtra(KEY_BODY, message.body)
            .putExtra(KEY_SENT_AT, message.sentAt)
            .putExtra(KEY_RECEIVED_AT, message.receivedAt)
            .putExtra(KEY_SUBSCRIPTION_ID, message.subscriptionId)
        context.applicationContext.sendBroadcast(intent)
        Log.i(TAG, "Incoming SMS import dispatched host=$hostPackage message_id_length=${message.messageId.length}")
        return true
    }

    /** Send a token-authenticated Presence update to the currently selected SMS host. */
    fun dispatchPresenceChanged(
        context: Context,
        phoneNumber: String,
        status: String,
        capabilities: Long,
        expiresAt: Long,
    ): Boolean {
        val appContext = context.applicationContext
        val hostPackage = defaultSmsHostPackage(appContext)
            ?.takeIf(::isSupportedHost)
            ?: return false
        val token = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_PREFIX + hostPackage, null)
            ?.takeIf(String::isNotBlank)
            ?: return false
        appContext.sendBroadcast(
            Intent(ACTION_PRESENCE_CHANGED)
                .setPackage(hostPackage)
                .putExtra(KEY_TOKEN, token)
                .putExtra(KEY_PRESENCE_PHONE, phoneNumber)
                .putExtra(KEY_PRESENCE_STATUS, status)
                .putExtra(KEY_PRESENCE_CAPABILITIES, capabilities)
                .putExtra(KEY_PRESENCE_EXPIRES_AT, expiresAt),
        )
        return true
    }

    /** Report a durable send-state transition to the SMS host that originated the request. */
    fun dispatchOutgoingStatus(
        context: Context,
        hostPackage: String,
        requestId: String,
        status: String,
        error: String? = null,
    ): Boolean {
        val appContext = context.applicationContext
        if (!isSupportedHost(hostPackage) || !isDefaultSmsHost(appContext, hostPackage)) return false
        val token = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_PREFIX + hostPackage, null)
            ?.takeIf(String::isNotBlank)
            ?: return false
        appContext.sendBroadcast(
            Intent(ACTION_OUTGOING_STATUS)
                .setPackage(hostPackage)
                .putExtra(KEY_TOKEN, token)
                .putExtra(KEY_OUTGOING_REQUEST_ID, requestId)
                .putExtra(KEY_OUTGOING_STATUS, status)
                .putExtra(KEY_OUTGOING_ERROR, error),
        )
        return true
    }

    fun storeHostToken(context: Context, hostPackage: String, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putString(TOKEN_PREFIX + hostPackage, token).commit()) {
            "Could not persist the Messaging inbox bridge token"
        }
    }

    fun isValidHostToken(context: Context, hostPackage: String, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_PREFIX + hostPackage, null)
            ?: return false
        return java.security.MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    fun isSupportedHost(packageName: String): Boolean = packageName == "com.android.messaging" || packageName == "com.google.android.apps.messaging"

    /** Android 10+ stores the default SMS app in RoleManager; the legacy Telephony helper can return null. */
    fun defaultSmsHostPackage(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true && isSupportedHost(context.packageName)) {
                return context.packageName
            }
        }
        return runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
    }

    fun isDefaultSmsHost(context: Context, packageName: String): Boolean = defaultSmsHostPackage(context) == packageName
}
