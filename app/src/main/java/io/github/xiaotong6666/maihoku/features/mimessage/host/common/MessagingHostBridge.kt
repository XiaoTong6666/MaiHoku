package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Base64
import io.github.xiaotong6666.maihoku.AOSP_MESSAGING_PACKAGE
import io.github.xiaotong6666.maihoku.GOOGLE_MESSAGES_PACKAGE
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingComposeUi
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingReceiveActionAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.aosp.AospMessagingSendAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesPresenceUi
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesSendAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.host.google.GoogleMessagesSmsDeliverAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridgeReceiver
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageInboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutgoingStatus
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Host-UID message import, outgoing status and Presence endpoint for the active default SMS app. */
internal object MessagingHostBridge {
    private const val TAG = "MessagingInbox"
    private const val HOST_PREFS = "maihoku_network_sms_bridge"
    private const val TOKEN_KEY = "inbox_bridge_token"
    private const val SERVICE_CENTER_PREFIX = "MaiHoku:"

    private val receivers = ConcurrentHashMap<String, BroadcastReceiver>()
    private val inFlightAospImports = ConcurrentHashMap.newKeySet<String>()
    private val aospReceiveAdapters = ConcurrentHashMap<String, AospMessagingReceiveActionAdapter>()
    private val googleMessagesSmsDeliverAdapters = ConcurrentHashMap<String, GoogleMessagesSmsDeliverAdapter>()
    private val aospSendAdapters = ConcurrentHashMap<String, AospMessagingSendAdapter>()
    private val googleMessagesSendAdapters = ConcurrentHashMap<String, GoogleMessagesSendAdapter>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaiHoku-Messaging-Inbox-Import").apply { isDaemon = true }
    }

    fun install(
        context: Context,
        modulePackageName: String,
        aospReceiveAdapter: AospMessagingReceiveActionAdapter? = null,
        googleMessagesSmsDeliverAdapter: GoogleMessagesSmsDeliverAdapter? = null,
        aospSendAdapter: AospMessagingSendAdapter? = null,
        googleMessagesSendAdapter: GoogleMessagesSendAdapter? = null,
    ) {
        val appContext = context.applicationContext
        val hostPackage = appContext.packageName
        if (!MiMessageInboxBridge.isSupportedHost(hostPackage)) return
        if (hostPackage == AOSP_MESSAGING_PACKAGE) {
            if (aospReceiveAdapter == null) {
                Log.e(TAG, "AOSP receive adapter is unavailable; refusing to register an incomplete inbox bridge")
                return
            }
            aospReceiveAdapters[hostPackage] = aospReceiveAdapter
        }
        if (hostPackage == GOOGLE_MESSAGES_PACKAGE) {
            if (googleMessagesSmsDeliverAdapter == null) {
                Log.e(TAG, "Google Messages SMS_DELIVER adapter is unavailable; refusing to register an incomplete inbox bridge")
                return
            }
            googleMessagesSmsDeliverAdapters[hostPackage] = googleMessagesSmsDeliverAdapter
        }
        if (hostPackage == AOSP_MESSAGING_PACKAGE && aospSendAdapter != null) {
            aospSendAdapters[hostPackage] = aospSendAdapter
        }
        if (hostPackage == GOOGLE_MESSAGES_PACKAGE && googleMessagesSendAdapter != null) {
            googleMessagesSendAdapters[hostPackage] = googleMessagesSendAdapter
        }
        if (receivers.containsKey(hostPackage)) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    MiMessageInboxBridge.ACTION_IMPORT_INCOMING -> {
                        val pending = goAsync()
                        worker.execute {
                            try {
                                importBroadcast(context.applicationContext, intent, hostPackage, modulePackageName)
                            } catch (error: Throwable) {
                                Log.e(TAG, "Incoming SMS import receiver failed: ${error.javaClass.simpleName}")
                            } finally {
                                pending.finish()
                            }
                        }
                    }

                    MiMessageInboxBridge.ACTION_PRESENCE_CHANGED -> handlePresenceBroadcast(
                        context.applicationContext,
                        intent,
                        hostPackage,
                        modulePackageName,
                        if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1,
                    )

                    MiMessageInboxBridge.ACTION_OUTGOING_STATUS -> handleOutgoingStatusBroadcast(
                        context.applicationContext,
                        intent,
                        hostPackage,
                        modulePackageName,
                        if (Build.VERSION.SDK_INT >= 34) sentFromUid else -1,
                    )
                }
            }
        }

        if (receivers.putIfAbsent(hostPackage, receiver) != null) return
        try {
            val filter = IntentFilter().apply {
                addAction(MiMessageInboxBridge.ACTION_IMPORT_INCOMING)
                addAction(MiMessageInboxBridge.ACTION_PRESENCE_CHANGED)
                addAction(MiMessageInboxBridge.ACTION_OUTGOING_STATUS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (error: Throwable) {
            receivers.remove(hostPackage, receiver)
            Log.e(TAG, "Could not register the incoming SMS bridge: ${error.javaClass.simpleName}")
            return
        }

        Log.i(TAG, "Registered network SMS inbox receiver in default SMS app=$hostPackage")
        worker.execute { registerAndSync(appContext, hostPackage, modulePackageName) }
    }

    /** Ask MaiHoku's own UID to resolve a recipient and query its ChID=3 Presence. */
    fun requestPresence(context: Context, modulePackageName: String, phoneNumber: String) {
        val appContext = context.applicationContext
        val hostPackage = appContext.packageName
        if (!MiMessageInboxBridge.isSupportedHost(hostPackage)) return
        val defaultSmsPackage = MiMessageInboxBridge.defaultSmsHostPackage(appContext)
        val phoneKey = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber)
        Log.d(
            TAG,
            "Presence query preflight host=$hostPackage default_sms=${defaultSmsPackage ?: "none"} " +
                "module=$modulePackageName recipient_valid=${phoneKey != null}",
        )
        if (defaultSmsPackage != hostPackage || phoneKey == null) return
        sendModuleControl(
            context = appContext,
            modulePackageName = modulePackageName,
            action = MiMessageInboxBridge.ACTION_QUERY_PRESENCE,
            hostPackage = hostPackage,
            token = hostToken(appContext),
            extras = Bundle().apply { putString(MiMessageInboxBridge.KEY_PRESENCE_PHONE, phoneNumber) },
        )
        Log.d(
            TAG,
            "Presence query dispatched host=$hostPackage recipient_digits=${MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber)?.count(Char::isDigit) ?: 0}",
        )
    }

    /** Persist the native host row first, then hand the one-shot payload to MaiHoku's own UID. */
    fun queueOutgoing(
        context: Context,
        modulePackageName: String,
        requestId: String,
        phoneNumber: String,
        body: String,
    ): Boolean {
        val appContext = context.applicationContext
        val hostPackage = appContext.packageName
        if (!MiMessageInboxBridge.isSupportedHost(hostPackage) ||
            MiMessageInboxBridge.defaultSmsHostPackage(appContext) != hostPackage ||
            !requestId.matches(Regex("[A-Za-z0-9_-]{16,64}")) ||
            MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) == null ||
            body.isEmpty() || body.length > MiMessageOutboxStore.MAX_BODY_CHARS
        ) {
            return false
        }
        return runCatching {
            sendModuleControl(
                context = appContext,
                modulePackageName = modulePackageName,
                action = MiMessageInboxBridge.ACTION_QUEUE_OUTGOING,
                hostPackage = hostPackage,
                token = hostToken(appContext),
                extras = Bundle().apply {
                    putString(MiMessageInboxBridge.KEY_OUTGOING_REQUEST_ID, requestId)
                    putString(MiMessageInboxBridge.KEY_ADDRESS, phoneNumber)
                    putString(MiMessageInboxBridge.KEY_BODY, body)
                },
            )
            true
        }.onFailure { error ->
            Log.e(TAG, "Outgoing Mi Message control broadcast failed: ${error.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    private fun handlePresenceBroadcast(
        context: Context,
        intent: Intent,
        hostPackage: String,
        modulePackageName: String,
        senderUid: Int,
    ) {
        if (!MiMessageInboxBridge.isDefaultSmsHost(context, hostPackage)) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (senderUid >= 0 && context.packageManager.getPackagesForUid(senderUid)?.contains(modulePackageName) != true) {
                Log.w(TAG, "Rejected Presence update from a non-MaiHoku UID")
                return
            }
        }
        val expectedToken = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null)
        val suppliedToken = intent.getStringExtra(MiMessageInboxBridge.KEY_TOKEN)
        if (!constantTimeEquals(expectedToken, suppliedToken)) {
            Log.w(TAG, "Rejected Presence update with an invalid bridge token")
            return
        }
        val phoneNumber = intent.getStringExtra(MiMessageInboxBridge.KEY_PRESENCE_PHONE)
            ?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }
            ?: return
        val status = intent.getStringExtra(MiMessageInboxBridge.KEY_PRESENCE_STATUS)
            ?.takeIf {
                it == MiMessagePresencePolicy.STATUS_UNKNOWN ||
                    it == MiMessagePresencePolicy.STATUS_AVAILABLE ||
                    it == MiMessagePresencePolicy.STATUS_UNAVAILABLE
            }
            ?: return
        val capabilities = intent.getLongExtra(MiMessageInboxBridge.KEY_PRESENCE_CAPABILITIES, 0L)
        val expiresAt = intent.getLongExtra(MiMessageInboxBridge.KEY_PRESENCE_EXPIRES_AT, 0L)
        Log.i(
            TAG,
            "Presence update received host=$hostPackage status=${status.lowercase()} " +
                "recipient_digits=${phoneNumber.count(Char::isDigit)} sms=${capabilities and MiMessagePresencePolicy.CAP_SMS != 0L}",
        )
        if (hostPackage == AOSP_MESSAGING_PACKAGE) {
            AospMessagingComposeUi.onPresenceChanged(phoneNumber, status, capabilities, expiresAt)
        } else if (hostPackage == GOOGLE_MESSAGES_PACKAGE) {
            GoogleMessagesPresenceUi.onPresenceChanged(phoneNumber, status, capabilities, expiresAt)
        }
    }

    private fun handleOutgoingStatusBroadcast(
        context: Context,
        intent: Intent,
        hostPackage: String,
        modulePackageName: String,
        senderUid: Int,
    ) {
        if (!MiMessageInboxBridge.isDefaultSmsHost(context, hostPackage)) return
        if (Build.VERSION.SDK_INT >= 34 && senderUid >= 0 &&
            context.packageManager.getPackagesForUid(senderUid)?.contains(modulePackageName) != true
        ) {
            Log.w(TAG, "Rejected outgoing status from a non-MaiHoku UID")
            return
        }
        val expectedToken = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null)
        val suppliedToken = intent.getStringExtra(MiMessageInboxBridge.KEY_TOKEN)
        if (!constantTimeEquals(expectedToken, suppliedToken)) {
            Log.w(TAG, "Rejected outgoing status with an invalid bridge token")
            return
        }
        val requestId = intent.getStringExtra(MiMessageInboxBridge.KEY_OUTGOING_REQUEST_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,64}")) }
            ?: return
        val status = intent.getStringExtra(MiMessageInboxBridge.KEY_OUTGOING_STATUS)
            ?.takeIf { it in MiMessageOutgoingStatus.ALL }
            ?: return
        val error = intent.getStringExtra(MiMessageInboxBridge.KEY_OUTGOING_ERROR)
            ?.take(96)
        runCatching {
            MessagingHostOutgoingUi.onOutgoingStatus(
                context = context,
                hostPackage = hostPackage,
                requestId = requestId,
                status = status,
                error = error,
                aospAdapter = aospSendAdapters[hostPackage],
                googleAdapter = googleMessagesSendAdapters[hostPackage],
            )
        }.onFailure { failure ->
            Log.e(TAG, "Could not apply outgoing Mi Message status request_id_length=${requestId.length}: ${failure.javaClass.simpleName}")
        }
    }

    private fun registerAndSync(context: Context, hostPackage: String, modulePackageName: String) {
        try {
            val token = hostToken(context)
            sendModuleControl(
                context = context,
                modulePackageName = modulePackageName,
                action = MiMessageInboxBridge.ACTION_REGISTER_HOST,
                hostPackage = hostPackage,
                token = token,
            )
            Log.i(TAG, "Default SMS app registration sent to MaiHoku; pending inbox replay requested")
        } catch (error: Throwable) {
            Log.w(TAG, "Inbox bridge registration broadcast failed", error)
        }
    }

    private fun importBroadcast(context: Context, intent: Intent, hostPackage: String, modulePackageName: String) {
        val expectedToken = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null)
        val suppliedToken = intent.getStringExtra(MiMessageInboxBridge.KEY_TOKEN)
        if (!constantTimeEquals(expectedToken, suppliedToken)) {
            Log.w(TAG, "Rejected incoming SMS broadcast with an invalid bridge token")
            return
        }
        val message = Bundle().apply {
            putString(MiMessageInboxBridge.KEY_MESSAGE_ID, intent.getStringExtra(MiMessageInboxBridge.KEY_MESSAGE_ID))
            putString(MiMessageInboxBridge.KEY_PACKET_ID, intent.getStringExtra(MiMessageInboxBridge.KEY_PACKET_ID))
            putString(MiMessageInboxBridge.KEY_SENDER_MID, intent.getStringExtra(MiMessageInboxBridge.KEY_SENDER_MID))
            putString(MiMessageInboxBridge.KEY_SENDER_JID, intent.getStringExtra(MiMessageInboxBridge.KEY_SENDER_JID))
            putString(MiMessageInboxBridge.KEY_RECIPIENT_MID, intent.getStringExtra(MiMessageInboxBridge.KEY_RECIPIENT_MID))
            putString(MiMessageInboxBridge.KEY_ADDRESS, intent.getStringExtra(MiMessageInboxBridge.KEY_ADDRESS))
            putString(MiMessageInboxBridge.KEY_BODY, intent.getStringExtra(MiMessageInboxBridge.KEY_BODY))
            putLong(MiMessageInboxBridge.KEY_SENT_AT, intent.getLongExtra(MiMessageInboxBridge.KEY_SENT_AT, 0L))
            putLong(MiMessageInboxBridge.KEY_RECEIVED_AT, intent.getLongExtra(MiMessageInboxBridge.KEY_RECEIVED_AT, 0L))
            putInt(MiMessageInboxBridge.KEY_SUBSCRIPTION_ID, intent.getIntExtra(MiMessageInboxBridge.KEY_SUBSCRIPTION_ID, -1))
        }
        val messageId = message.getString(MiMessageInboxBridge.KEY_MESSAGE_ID)
        try {
            importStoredMessage(context, message, hostPackage, modulePackageName, suppliedToken.orEmpty())
        } catch (error: Throwable) {
            if (!messageId.isNullOrBlank()) {
                sendModuleControl(
                    context = context,
                    modulePackageName = modulePackageName,
                    action = MiMessageInboxBridge.ACTION_MARK_IMPORT_FAILED,
                    hostPackage = hostPackage,
                    token = suppliedToken.orEmpty(),
                    extras = Bundle().apply {
                        putString(MiMessageInboxBridge.KEY_MESSAGE_ID, messageId)
                        putString(MiMessageInboxBridge.KEY_ERROR, error.javaClass.simpleName)
                    },
                )
            }
            throw error
        }
    }

    private fun importStoredMessage(
        context: Context,
        message: Bundle,
        hostPackage: String,
        modulePackageName: String,
        token: String,
    ) {
        val messageId = message.getString(MiMessageInboxBridge.KEY_MESSAGE_ID)
            ?.takeIf { it.toLongOrNull()?.let { id -> id > 0L } == true }
            ?: throw IllegalArgumentException("Invalid network SMS message ID")
        val senderMid = message.getString(MiMessageInboxBridge.KEY_SENDER_MID)
            ?.takeIf { it.toLongOrNull()?.let { mid -> mid > 0L } == true }
            ?: throw IllegalArgumentException("Invalid network SMS sender MID")
        val senderJid = message.getString(MiMessageInboxBridge.KEY_SENDER_JID)
            ?.takeIf { it.length <= 256 && it.substringBefore('@').toLongOrNull() == senderMid.toLong() && it.substringAfter('@').startsWith("xiaomi.com") }
            ?: throw IllegalArgumentException("Invalid network SMS sender JID")
        val address = message.getString(MiMessageInboxBridge.KEY_ADDRESS)
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("Invalid network SMS address")
        val body = message.getString(MiMessageInboxBridge.KEY_BODY)
            ?.takeIf { it.isNotEmpty() && it.length <= MiMessageInboxStore.MAX_BODY_CHARS }
            ?: throw IllegalArgumentException("Invalid network SMS body")
        val marker = serviceCenterMarker(hostPackage, messageId)

        if (hostPackage == AOSP_MESSAGING_PACKAGE) {
            importThroughAospReceiveAction(
                context = context,
                message = message,
                messageId = messageId,
                address = address,
                body = body,
                marker = marker,
                hostPackage = hostPackage,
                modulePackageName = modulePackageName,
                token = token,
            )
            return
        }

        if (hostPackage == GOOGLE_MESSAGES_PACKAGE) {
            importThroughGoogleSmsDeliver(
                context = context,
                message = message,
                messageId = messageId,
                address = address,
                body = body,
                marker = marker,
                hostPackage = hostPackage,
                modulePackageName = modulePackageName,
                token = token,
            )
            return
        }

        error("Unsupported native SMS host: $hostPackage")
    }

    private fun importThroughGoogleSmsDeliver(
        context: Context,
        message: Bundle,
        messageId: String,
        address: String,
        body: String,
        marker: String,
        hostPackage: String,
        modulePackageName: String,
        token: String,
    ) {
        val existing = findExisting(context, marker)
        if (existing != null) {
            markImported(context, modulePackageName, hostPackage, token, messageId, existing)
            Log.i(TAG, "Google Messages already has the native SMS row; import deduplicated")
            return
        }
        if (!inFlightGoogleImports.add(messageId)) {
            Log.d(TAG, "Google native SMS receive already in flight message_id_length=${messageId.length}")
            return
        }

        try {
            val adapter = googleMessagesSmsDeliverAdapters[hostPackage]
                ?: throw IllegalStateException("Google Messages SMS_DELIVER adapter was not initialized")
            adapter.receive(
                context = context,
                messageId = messageId,
                address = address,
                body = body,
                receivedAt = message.getLong(MiMessageInboxBridge.KEY_RECEIVED_AT),
                subscriptionId = message.getInt(MiMessageInboxBridge.KEY_SUBSCRIPTION_ID, -1),
                serviceCenterMarker = marker,
            )

            // receive() waits for the native SmsReceivePipeline future, including its database
            // work. Verify the committed row once; do not block the serial inbox with polling.
            val importedUri = findExisting(context, marker)
                ?: throw IllegalStateException("GOOGLE_NATIVE_TELEPHONY_ROW_MISSING")
            markImported(context, modulePackageName, hostPackage, token, messageId, importedUri)
            Log.i(TAG, "Google native SmsReceivePipeline completed; Telephony row present")
        } catch (error: Throwable) {
            sendModuleControl(
                context = context,
                modulePackageName = modulePackageName,
                action = MiMessageInboxBridge.ACTION_MARK_IMPORT_FAILED,
                hostPackage = hostPackage,
                token = token,
                extras = Bundle().apply {
                    putString(MiMessageInboxBridge.KEY_MESSAGE_ID, messageId)
                    putString(MiMessageInboxBridge.KEY_ERROR, "GOOGLE_${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
                },
            )
            Log.e(TAG, "Could not complete Google native SMS receive: ${error.javaClass.simpleName}", error)
        } finally {
            inFlightGoogleImports.remove(messageId)
        }
    }

    /**
     * AOSP Messaging does not build its conversation list or notifications from a bare
     * TelephonyProvider row. Its native ReceiveSmsMessageAction inserts the provider row,
     * writes the Bugle conversation database, posts the normal SMS notification and calls
     * MessagingContentProvider.notifyMessagesChanged(). Reuse that exact native receive path.
     */
    private fun importThroughAospReceiveAction(
        context: Context,
        message: Bundle,
        messageId: String,
        address: String,
        body: String,
        marker: String,
        hostPackage: String,
        modulePackageName: String,
        token: String,
    ) {
        val existing = findExisting(context, marker)
        if (existing != null) {
            // A previous native action may have completed before its completion callback was
            // delivered to MaiHoku. Do not create a duplicate Bugle conversation message.
            markImported(context, modulePackageName, hostPackage, token, messageId, existing)
            Log.i(TAG, "AOSP Messaging already has the Telephony row; import deduplicated")
            return
        }
        if (!inFlightAospImports.add(messageId)) {
            Log.d(TAG, "AOSP native receive action already in flight message_id_length=${messageId.length}")
            return
        }

        try {
            val receivedAt = message.getLong(MiMessageInboxBridge.KEY_RECEIVED_AT)
                .takeIf { it > 0L } ?: System.currentTimeMillis()
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, receivedAt)
                put(Telephony.Sms.DATE_SENT, message.getLong(MiMessageInboxBridge.KEY_SENT_AT).coerceAtLeast(0L))
                put(Telephony.Sms.PROTOCOL, 0)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.REPLY_PATH_PRESENT, 0)
                put(Telephony.Sms.ERROR_CODE, 0)
                put(Telephony.Sms.SERVICE_CENTER, marker)
                message.getInt(MiMessageInboxBridge.KEY_SUBSCRIPTION_ID).takeIf { it >= 0 }?.let {
                    put(SUBSCRIPTION_ID_COLUMN, it)
                }
            }

            val adapter = aospReceiveAdapters[hostPackage]
                ?: throw IllegalStateException("AOSP native receive adapter was not initialized")
            adapter.start(
                values = values,
                onSucceeded = {
                    completeAospReceiveAction(
                        context,
                        modulePackageName,
                        hostPackage,
                        token,
                        messageId,
                        marker,
                        succeeded = true,
                    )
                },
                onFailed = {
                    completeAospReceiveAction(
                        context,
                        modulePackageName,
                        hostPackage,
                        token,
                        messageId,
                        marker,
                        succeeded = false,
                    )
                },
            )
            Log.i(
                TAG,
                "Started AOSP native ReceiveSmsMessageAction message_id_length=${messageId.length} " +
                    "address_length=${address.length} body_length=${body.length}",
            )
        } catch (error: Throwable) {
            inFlightAospImports.remove(messageId)
            sendModuleControl(
                context = context,
                modulePackageName = modulePackageName,
                action = MiMessageInboxBridge.ACTION_MARK_IMPORT_FAILED,
                hostPackage = hostPackage,
                token = token,
                extras = Bundle().apply {
                    putString(MiMessageInboxBridge.KEY_MESSAGE_ID, messageId)
                    putString(MiMessageInboxBridge.KEY_ERROR, "AOSP_${error.javaClass.simpleName}")
                },
            )
            Log.e(TAG, "Could not start AOSP native SMS receive pipeline: ${error.javaClass.simpleName}", error)
        }
    }

    private fun completeAospReceiveAction(
        context: Context,
        modulePackageName: String,
        hostPackage: String,
        token: String,
        messageId: String,
        marker: String,
        succeeded: Boolean,
    ) {
        // ActionMonitor callbacks are delivered on Messaging's main thread. Keep provider
        // queries and IPC off that thread, and only ACK the import after Bugle has completed.
        worker.execute {
            try {
                val uri = findExisting(context, marker)
                if (succeeded && uri != null) {
                    markImported(context, modulePackageName, hostPackage, token, messageId, uri)
                    Log.i(TAG, "AOSP native receive action completed; notification and conversation refresh requested")
                } else {
                    sendModuleControl(
                        context = context,
                        modulePackageName = modulePackageName,
                        action = MiMessageInboxBridge.ACTION_MARK_IMPORT_FAILED,
                        hostPackage = hostPackage,
                        token = token,
                        extras = Bundle().apply {
                            putString(MiMessageInboxBridge.KEY_MESSAGE_ID, messageId)
                            putString(MiMessageInboxBridge.KEY_ERROR, if (succeeded) "AOSP_TELEPHONY_ROW_MISSING" else "AOSP_RECEIVE_ACTION_FAILED")
                        },
                    )
                    Log.e(TAG, "AOSP native receive action failed or did not create its Telephony row")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Could not finalize AOSP native receive action: ${error.javaClass.simpleName}")
            } finally {
                inFlightAospImports.remove(messageId)
            }
        }
    }

    private fun markImported(
        context: Context,
        modulePackageName: String,
        hostPackage: String,
        token: String,
        messageId: String,
        uri: Uri,
    ) {
        sendModuleControl(
            context = context,
            modulePackageName = modulePackageName,
            action = MiMessageInboxBridge.ACTION_MARK_IMPORTED,
            hostPackage = hostPackage,
            token = token,
            extras = Bundle().apply {
                putString(MiMessageInboxBridge.KEY_MESSAGE_ID, messageId)
                putString(MiMessageInboxBridge.KEY_TELEPHONY_URI, uri.toString())
            },
        )
    }

    private fun findExisting(context: Context, marker: String): Uri? {
        val projection = arrayOf(Telephony.Sms._ID)
        return context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.SERVICE_CENTER}=?",
            arrayOf(marker),
            null,
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(Telephony.Sms.Inbox.CONTENT_URI, cursor.getLong(0))
            } else {
                null
            }
        }
    }

    private fun serviceCenterMarker(hostPackage: String, messageId: String): String = if (hostPackage == GOOGLE_MESSAGES_PACKAGE) "+99$messageId" else SERVICE_CENTER_PREFIX + messageId

    private fun sendModuleControl(
        context: Context,
        modulePackageName: String,
        action: String,
        hostPackage: String,
        token: String,
        extras: Bundle? = null,
    ) {
        val component = ComponentName(modulePackageName, MiMessageInboxBridgeReceiver::class.java.name)
        val intent = Intent(action)
            .setComponent(component)
            .putExtra(MiMessageInboxBridge.KEY_HOST_PACKAGE, hostPackage)
            .putExtra(MiMessageInboxBridge.KEY_TOKEN, token)
        extras?.let(intent::putExtras)
        context.sendBroadcast(intent)
    }

    private fun hostToken(context: Context): String {
        val prefs = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN_KEY, null)?.takeIf(String::isNotBlank)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        check(prefs.edit().putString(TOKEN_KEY, token).commit()) { "Could not persist the host inbox bridge token" }
        return token
    }

    private fun constantTimeEquals(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrEmpty() || actual.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }

    private const val SUBSCRIPTION_ID_COLUMN = "sub_id"
    private const val AOSP_MESSAGING_PACKAGE = "com.android.messaging"
    private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    private val inFlightGoogleImports = ConcurrentHashMap.newKeySet<String>()
}
