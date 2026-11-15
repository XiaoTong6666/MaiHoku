package io.github.xiaotong6666.maihoku.features.mimessage.host.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.GOOGLE_MESSAGES_PACKAGE
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostOutgoingRecord
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostOutgoingUi
import io.github.xiaotong6666.maihoku.features.mimessage.host.common.MessagingHostSendAdapter
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutgoingStatus
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import io.github.xiaotong6666.maihoku.features.mimessage.ui.pendingIntent
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/**
 * DexKit-resolved Google Messages SMS transport adapter.
 * Google creates its own Telephony/Bugle pending row first; we replace only the SmsManager
 * transport call and complete Google's original PendingIntents from Xiaomi Push receipts.
 */
internal class GoogleMessagesSendAdapter private constructor(
    private val modulePackageName: String,
    private val recipientGetters: List<Method>,
) : MessagingHostSendAdapter {

    private data class SendContext(
        val phoneNumber: String,
        val body: String,
        val messageUri: Uri?,
        val subscriptionId: Int,
        val googleMessageId: String,
        val timestamp: Long,
    )

    private data class PendingCallbacks(
        val sent: MutableList<PendingIntent>,
        val delivered: MutableList<PendingIntent>,
    )

    private val pendingCallbacks = ConcurrentHashMap<String, PendingCallbacks>()

    override fun createPendingMessage(
        context: Context,
        requestId: String,
        phoneNumber: String,
        body: String,
        subscriptionId: Int,
    ): MessagingHostOutgoingRecord = error("Google Messages creates its native pending row before SmsManager is called")

    override fun applyStatus(
        context: Context,
        record: MessagingHostOutgoingRecord,
        status: String,
        error: String?,
    ) {
        val callbacks = pendingCallbacks[record.requestId]
        when (status) {
            MiMessageOutgoingStatus.SERVER_ACCEPTED -> {
                callbacks?.sent?.toList()?.forEach { sendPendingIntent(context, it, Activity.RESULT_OK) }
                callbacks?.sent?.clear()
                updateTelephonyStatus(context, record, Telephony.Sms.STATUS_COMPLETE)
            }

            MiMessageOutgoingStatus.FAILED -> {
                callbacks?.sent?.toList()?.forEach { sendPendingIntent(context, it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
                callbacks?.delivered?.toList()?.forEach { sendPendingIntent(context, it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
                pendingCallbacks.remove(record.requestId)
                updateTelephonyStatus(context, record, Telephony.Sms.STATUS_FAILED)
            }

            MiMessageOutgoingStatus.DELIVERED -> {
                callbacks?.sent?.toList()?.forEach { sendPendingIntent(context, it, Activity.RESULT_OK) }
                callbacks?.delivered?.toList()?.forEach { sendPendingIntent(context, it, Activity.RESULT_OK) }
                pendingCallbacks.remove(record.requestId)
                updateTelephonyStatus(context, record, Telephony.Sms.STATUS_COMPLETE)
            }

            MiMessageOutgoingStatus.UNCONFIRMED -> Log.w(
                TAG,
                "Google Messages send result is unconfirmed; keeping native PendingIntent state request_id_length=${record.requestId.length}",
            )
        }
        Log.i(TAG, "Google native send status applied status=$status request_id_length=${record.requestId.length}")
    }

    private fun interceptSingleSend(args: List<Any?>): Boolean {
        val context = currentSend.get()
        if (context == null) {
            if (!GoogleMessagesPresenceUi.shouldInterceptCurrentConversation()) return false
            (args.getOrNull(3) as? PendingIntent)?.let {
                sendPendingIntent(GoogleMessagesPresenceUi.applicationContext(), it, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            }
            GoogleMessagesPresenceUi.showSendError(R.string.mx_send_context_missing)
            Log.e(TAG, "Blocked Google Messages carrier fallback because Mi Message was selected but sender context was unavailable")
            return true
        }
        val destination = args.getOrNull(0) as? String ?: context.phoneNumber
        if (!GoogleMessagesPresenceUi.shouldInterceptMiMessage(destination)) return false
        val sentIntent = args.getOrNull(3) as? PendingIntent
        val deliveryIntent = args.getOrNull(4) as? PendingIntent
        queueCurrentSend(context, destination, context.body, listOfNotNull(sentIntent), listOfNotNull(deliveryIntent))
        return true
    }

    private fun interceptMultipartSend(args: List<Any?>): Boolean {
        val context = currentSend.get()
        if (context == null) {
            if (!GoogleMessagesPresenceUi.shouldInterceptCurrentConversation()) return false
            (args.getOrNull(3) as? Iterable<*>)
                ?.filterIsInstance<PendingIntent>()
                ?.forEach { sendPendingIntent(GoogleMessagesPresenceUi.applicationContext(), it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
            GoogleMessagesPresenceUi.showSendError(R.string.mx_send_context_missing)
            Log.e(TAG, "Blocked Google Messages multipart carrier fallback because Mi Message was selected but sender context was unavailable")
            return true
        }
        val destination = args.getOrNull(0) as? String ?: context.phoneNumber
        if (!GoogleMessagesPresenceUi.shouldInterceptMiMessage(destination)) return false
        val sent = (args.getOrNull(3) as? Iterable<*>)?.filterIsInstance<PendingIntent>().orEmpty()
        val delivered = (args.getOrNull(4) as? Iterable<*>)?.filterIsInstance<PendingIntent>().orEmpty()
        val parts = (args.getOrNull(2) as? Iterable<*>)?.filterIsInstance<String>().orEmpty()
        val body = context.body.ifEmpty { parts.joinToString(separator = "") }
        queueCurrentSend(context, destination, body, sent, delivered)
        return true
    }

    private fun queueCurrentSend(
        send: SendContext,
        destination: String,
        body: String,
        sentIntents: List<PendingIntent>,
        deliveryIntents: List<PendingIntent>,
    ) {
        val appContext = GoogleMessagesPresenceUi.applicationContext()
        if (appContext == null) {
            sentIntents.forEach { sendPendingIntent(null, it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
            GoogleMessagesPresenceUi.showSendError(R.string.mx_send_page_unavailable)
            return
        }
        if (!GoogleMessagesPresenceUi.canSendMiMessage(destination)) {
            sentIntents.forEach { sendPendingIntent(appContext, it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
            GoogleMessagesPresenceUi.showSendError(R.string.mx_send_recipient_unavailable)
            Log.w(TAG, "Blocked Google Messages Mi Message send because recipient Presence is not fresh")
            return
        }
        val rowUri = send.messageUri?.takeIf { it.toString().startsWith("content://sms/") }
            ?: findRecentSentRow(appContext, destination, body, send.timestamp)
        if (rowUri == null) {
            sentIntents.forEach { sendPendingIntent(appContext, it, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
            GoogleMessagesPresenceUi.showSendError(R.string.mx_send_record_unavailable)
            Log.e(TAG, "Google Messages send interception found no native Telephony row")
            return
        }
        val requestId = UUID.randomUUID().toString().replace("-", "")
        pendingCallbacks[requestId] = PendingCallbacks(sentIntents.toMutableList(), deliveryIntents.toMutableList())
        val record = MessagingHostOutgoingRecord(
            requestId = requestId,
            hostPackage = GOOGLE_MESSAGES_PACKAGE,
            address = destination,
            body = body,
            telephonyUri = rowUri.toString(),
            bugleMessageId = send.googleMessageId,
            subscriptionId = send.subscriptionId,
            status = MiMessageOutgoingStatus.QUEUED,
            createdAt = send.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        MessagingHostOutgoingUi.enqueuePrepared(appContext, modulePackageName, record, this)
        Log.i(
            TAG,
            "Google Messages native SMS transport replaced with Xiaomi Push request_id_length=${requestId.length} " +
                "sent_callbacks=${sentIntents.size} delivery_callbacks=${deliveryIntents.size} body_chars=${body.length}",
        )
    }

    private fun findRecentSentRow(context: Context, address: String, body: String, timestamp: Long): Uri? {
        val since = (timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()) - ROW_LOOKBACK_MS
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.DATE}>=?",
            arrayOf(address, body, Telephony.Sms.MESSAGE_TYPE_SENT.toString(), since.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Uri.withAppendedPath(
                    Telephony.Sms.CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)).toString(),
                )
            }
        }
    }

    private fun updateTelephonyStatus(context: Context, record: MessagingHostOutgoingRecord, status: Int) {
        runCatching {
            context.contentResolver.update(
                Uri.parse(record.telephonyUri),
                android.content.ContentValues().apply { put(Telephony.Sms.STATUS, status) },
                null,
                null,
            )
        }.onFailure { error ->
            Log.w(TAG, "Google Messages Telephony status update failed: ${error.javaClass.simpleName}")
        }
    }

    private fun sendPendingIntent(context: Context?, pendingIntent: PendingIntent, resultCode: Int) {
        runCatching { pendingIntent.send(context, resultCode, null) }
            .onFailure { error -> Log.w(TAG, "Google Messages native send callback failed: ${error.javaClass.simpleName}") }
    }

    private fun captureContext(method: Method, receiver: Any?, args: List<Any?>): SendContext? {
        val message = args.getOrNull(0) ?: return null
        val phone = extractPhone(message) ?: return null
        val body = args.getOrNull(1) as? String ?: return null
        val uri = args.getOrNull(2) as? Uri
        val subId = args.getOrNull(3) as? Int ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val messageId = args.getOrNull(6)?.toString().orEmpty()
        val timestamp = args.getOrNull(7) as? Long ?: System.currentTimeMillis()
        if (body.isEmpty() || body.length > MiMessageOutboxStore.MAX_BODY_CHARS) return null
        GoogleMessagesPresenceUi.onRecipientResolvedBySender(phone)
        return SendContext(phone, body, uri, subId, messageId, timestamp)
    }

    private fun extractPhone(message: Any): String? {
        val semanticGetters = recipientGetters.filter { it.declaringClass.isInstance(message) }
        val candidates = semanticGetters
            .distinctBy { it.declaringClass.name + "#" + it.name }
            .mapNotNull { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(message) as? String
                }.getOrNull()
            }
        return candidates.mapNotNull(MiMessagePresencePolicy::normalizePhoneNumber).distinct().singleOrNull()
    }

    companion object {
        private const val TAG = "GoogleMsgSend"
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        private const val SENDER_FINGERPRINT = "sending message. destination:"
        private const val SENDER_RESULT_FINGERPRINT = "SMS sent"
        private const val ROW_LOOKBACK_MS = 30_000L
        private val currentSend = ThreadLocal<SendContext?>()

        fun resolve(runtime: HookRuntime, modulePackageName: String): GoogleMessagesSendAdapter? = runCatching {
            val resolved = runtime.dexKit.useBridge { bridge ->
                val candidates = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().apply {
                            usingStrings(SENDER_FINGERPRINT, SENDER_RESULT_FINGERPRINT)
                        },
                    ),
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
                }.filter(::isSmsSenderMethod)
                val sender = candidates.singleOrNull()
                    ?: error("DexKit did not resolve one Google Messages SMS transport method")
                val getters = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().apply { usingStrings("getNormalizedDestination") },
                    ),
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
                }.filter { method ->
                    method.parameterCount == 0 && method.returnType == String::class.java
                }.distinctBy { it.declaringClass.name + "#" + it.name }
                check(getters.isNotEmpty()) { "DexKit could not resolve Google's normalized-recipient getter" }
                sender to getters
            }
            val sender = resolved.first
            val adapter = GoogleMessagesSendAdapter(modulePackageName, resolved.second)

            runtime.hooks.around(sender, "google-messages.outgoing.capture-sms-context") {
                val sendContext = adapter.captureContext(sender, thisObject, args)
                if (sendContext == null) {
                    Log.w(TAG, "Google Messages SMS sender context could not be extracted; leaving native SMS path unchanged")
                    proceed()
                } else {
                    val previous = currentSend.get()
                    currentSend.set(sendContext)
                    try {
                        proceed()
                    } finally {
                        if (previous == null) currentSend.remove() else currentSend.set(previous)
                    }
                }
            }

            val smsManagerMethods = SmsManager::class.java.declaredMethods.filter(::isSmsManagerSendMethod)
            check(smsManagerMethods.isNotEmpty()) { "No SmsManager text-send overloads were found" }
            smsManagerMethods.forEachIndexed { index, method ->
                method.isAccessible = true
                val multipart = method.name == "sendMultipartTextMessage"
                runtime.hooks.around(method, "google-messages.outgoing.replace-smsmanager-$index") {
                    val intercepted = if (multipart) adapter.interceptMultipartSend(args) else adapter.interceptSingleSend(args)
                    if (intercepted) null else proceed()
                }
            }
            Log.i(
                TAG,
                "DexKit resolved Google Messages SMS sender ${sender.declaringClass.name}.${sender.name}; " +
                    "SmsManager overloads hooked=${smsManagerMethods.size}",
            )
            adapter
        }.onFailure { error ->
            Log.e(TAG, "Could not resolve Google Messages outgoing SMS adapter via DexKit", error)
        }.getOrNull()

        private fun isSmsSenderMethod(method: Method): Boolean {
            if (Modifier.isStatic(method.modifiers) || method.parameterCount != 8) return false
            val p = method.parameterTypes
            if (p[1] != String::class.java || p[2] != Uri::class.java ||
                p[3] != Int::class.javaPrimitiveType || p[4] != String::class.java ||
                p[5] != Boolean::class.javaPrimitiveType || p[7] != Long::class.javaPrimitiveType
            ) {
                return false
            }
            return p[6].name == "com.google.android.apps.messaging.shared.datamodel.data.datatypes.MessageIdType" &&
                p[0].declaredMethods.any { it.parameterCount == 0 && it.returnType == String::class.java }
        }

        private fun isSmsManagerSendMethod(method: Method): Boolean {
            val p = method.parameterTypes
            return when (method.name) {
                "sendTextMessage" ->
                    p.size in 5..6 &&
                        p[0] == String::class.java && p[1] == String::class.java && p[2] == String::class.java &&
                        p[3] == PendingIntent::class.java && p[4] == PendingIntent::class.java &&
                        (p.size == 5 || p[5] == Long::class.javaPrimitiveType)

                "sendMultipartTextMessage" ->
                    p.size in 5..6 &&
                        p[0] == String::class.java && p[1] == String::class.java &&
                        java.util.List::class.java.isAssignableFrom(p[2]) &&
                        java.util.List::class.java.isAssignableFrom(p[3]) &&
                        java.util.List::class.java.isAssignableFrom(p[4]) &&
                        (p.size == 5 || p[5] == Long::class.javaPrimitiveType)

                else -> false
            }
        }
    }
}
