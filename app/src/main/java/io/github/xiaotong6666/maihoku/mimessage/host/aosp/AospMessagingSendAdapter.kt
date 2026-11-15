package io.github.xiaotong6666.maihoku.mimessage.host.aosp

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.AOSP_MESSAGING_PACKAGE
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostOutgoingRecord
import io.github.xiaotong6666.maihoku.mimessage.host.common.MessagingHostSendAdapter
import io.github.xiaotong6666.maihoku.mimessage.message.MiMessageOutgoingStatus
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Uses Bugle's native outgoing-row pipeline without ever scheduling its carrier-SMS sender. */
internal class AospMessagingSendAdapter private constructor(
    private val insertActionConstructor: Constructor<*>,
    private val executeInsertAction: Method,
    private val lastSentTimestamp: Method,
    private val dataModelGet: Method,
    private val dataModelGetDatabase: Method,
    private val readMessageBySmsUri: Method,
    private val messageIdGetter: Method,
    private val messageSmsUriGetter: Method,
    private val processSentActionConstructor: Constructor<*>,
    private val processSentResult: Method,
) : MessagingHostSendAdapter {

    override fun createPendingMessage(
        context: Context,
        requestId: String,
        phoneNumber: String,
        body: String,
        subscriptionId: Int,
    ): MessagingHostOutgoingRecord {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "AOSP Bugle outgoing-row insertion must run off the main thread"
        }
        val startedAt = System.currentTimeMillis()
        val selectedSubId = subscriptionId.takeIf { it >= 0 }
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()

        // InsertNewMessageAction creates both the Telephony Sent row and Bugle's local
        // ConversationMessageData, but schedules ProcessPendingMessagesAction at the end.
        // Suppress only that schedule call on this worker thread: Xiaomi Push owns delivery.
        val insertedDraft = suppressCarrierSend {
            val action = insertActionConstructor.newInstance(selectedSubId, phoneNumber, body, "")
            executeInsertAction.invoke(action)
        } ?: error("AOSP InsertNewMessageAction returned no draft MessageData")

        val timestamp = (lastSentTimestamp.invoke(null) as? Long)?.takeIf { it > 0L } ?: startedAt
        val telephonyUri = findInsertedSentRow(context, phoneNumber, body, timestamp, startedAt)
        val dataModel = dataModelGet.invoke(null) ?: error("AOSP DataModel singleton is unavailable")
        val database = dataModelGetDatabase.invoke(dataModel) ?: error("AOSP Bugle database is unavailable")
        val message = readMessageBySmsUri.invoke(null, database, telephonyUri)
            ?: error("AOSP Bugle database did not contain the inserted outgoing SMS row")
        val messageId = messageIdGetter.invoke(message) as? String
            ?: error("AOSP outgoing Bugle message has no message ID")
        val modelUri = messageSmsUriGetter.invoke(message) as? Uri ?: telephonyUri
        Log.i(
            TAG,
            "AOSP native outgoing row created request_id_length=${requestId.length} " +
                "bugle_message_id_length=${messageId.length} telephony_row=true carrier_send_suppressed=true",
        )
        return MessagingHostOutgoingRecord(
            requestId = requestId,
            hostPackage = AOSP_MESSAGING_PACKAGE,
            address = phoneNumber,
            body = body,
            telephonyUri = modelUri.toString(),
            bugleMessageId = messageId,
            subscriptionId = selectedSubId,
            status = MiMessageOutgoingStatus.QUEUED,
            createdAt = startedAt,
            updatedAt = startedAt,
        )
    }

    override fun applyStatus(
        context: Context,
        record: MessagingHostOutgoingRecord,
        status: String,
        error: String?,
    ) {
        val messageId = record.bugleMessageId ?: return
        when (status) {
            MiMessageOutgoingStatus.SERVER_ACCEPTED -> processSentStatus(messageId, record.subscriptionId, succeeded = true)

            MiMessageOutgoingStatus.DELIVERED -> {
                if (record.status !in setOf(MiMessageOutgoingStatus.SERVER_ACCEPTED, MiMessageOutgoingStatus.DELIVERED)) {
                    processSentStatus(messageId, record.subscriptionId, succeeded = true)
                }
            }

            MiMessageOutgoingStatus.FAILED -> processSentStatus(messageId, record.subscriptionId, succeeded = false)

            // A timeout is ambiguous: leave Bugle's sending state intact to discourage a
            // duplicate retry until the server's final state can be checked.
            MiMessageOutgoingStatus.UNCONFIRMED -> Log.w(
                TAG,
                "AOSP Mi Message status is unconfirmed; leaving local row pending request_id_length=${record.requestId.length}",
            )
        }
        if (status == MiMessageOutgoingStatus.DELIVERED) {
            runCatching {
                context.contentResolver.update(
                    Uri.parse(record.telephonyUri),
                    ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE) },
                    null,
                    null,
                )
            }.onFailure { failure ->
                Log.w(TAG, "Could not update AOSP Telephony delivery status: ${failure.javaClass.simpleName}")
            }
        }
        Log.i(
            TAG,
            "AOSP outgoing row status applied status=$status request_id_length=${record.requestId.length} " +
                "error=${error ?: "none"}",
        )
    }

    private fun processSentStatus(messageId: String, subscriptionId: Int, succeeded: Boolean) {
        suppressCarrierSend {
            // processResult(requestStatus=0) marks the Bugle MessageData sent and updates the
            // Telephony row. requestStatus=2 follows Bugle's native failed-send path.
            processSentResult.invoke(
                null,
                messageId,
                null,
                if (succeeded) REQUEST_STATUS_SUCCESS else REQUEST_STATUS_FAILED,
                if (succeeded) RAW_STATUS_OK else SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                true,
                processSentActionConstructor.newInstance(),
                subscriptionId,
                if (succeeded) Activity.RESULT_OK else SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                0,
            )
        }
    }

    private fun findInsertedSentRow(
        context: Context,
        phoneNumber: String,
        body: String,
        timestamp: Long,
        startedAt: Long,
    ): Uri {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.DATE}>=?"
        return context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            arrayOf(phoneNumber, body, Telephony.Sms.MESSAGE_TYPE_SENT.toString(), minOf(timestamp, startedAt).toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "AOSP Telephony Sent row was not created" }
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)))
        } ?: error("AOSP Telephony provider query returned null")
    }

    private inline fun <T> suppressCarrierSend(block: () -> T): T {
        suppressPendingSend.set(true)
        return try {
            block()
        } finally {
            suppressPendingSend.remove()
        }
    }

    companion object {
        private const val TAG = "AospMsgSend"
        private const val REQUEST_STATUS_SUCCESS = 0
        private const val REQUEST_STATUS_FAILED = 2
        private const val RAW_STATUS_OK = 0
        private const val AOSP_MESSAGING_PACKAGE = "com.android.messaging"
        private const val INSERT_ACTION_CLASS = "com.android.messaging.datamodel.action.InsertNewMessageAction"
        private const val PENDING_ACTION_CLASS = "com.android.messaging.datamodel.action.ProcessPendingMessagesAction"
        private const val ACTION_BASE_CLASS = "com.android.messaging.datamodel.action.Action"
        private const val DATA_MODEL_CLASS = "com.android.messaging.datamodel.DataModel"
        private const val BUGLE_DATABASE_CLASS = "com.android.messaging.datamodel.BugleDatabaseOperations"
        private const val MESSAGE_DATA_CLASS = "com.android.messaging.datamodel.data.MessageData"
        private const val PROCESS_SENT_CLASS = "com.android.messaging.datamodel.action.ProcessSentMessageAction"

        private val suppressPendingSend = ThreadLocal.withInitial { false }

        fun resolve(runtime: HookRuntime): AospMessagingSendAdapter? = runCatching {
            val loader = runtime.classLoader
            val actionClass = loader.loadClass(INSERT_ACTION_CLASS)
            val actionBaseClass = loader.loadClass(ACTION_BASE_CLASS)
            val pendingClass = loader.loadClass(PENDING_ACTION_CLASS)
            val dataModelClass = loader.loadClass(DATA_MODEL_CLASS)
            val bugleDatabaseClass = loader.loadClass(BUGLE_DATABASE_CLASS)
            val messageDataClass = loader.loadClass(MESSAGE_DATA_CLASS)
            val processSentClass = loader.loadClass(PROCESS_SENT_CLASS)

            val constructor = actionClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val execute = actionClass.getDeclaredMethod("executeAction").apply { isAccessible = true }
            val schedulePending = pendingClass.getDeclaredMethod(
                "scheduleProcessPendingMessagesAction",
                Boolean::class.javaPrimitiveType!!,
                actionBaseClass,
            ).apply { isAccessible = true }
            val timestamp = actionClass.getDeclaredMethod("getLastSentMessageTimestamp").apply { isAccessible = true }
            val dataModelGet = dataModelClass.getDeclaredMethod("get").apply { isAccessible = true }
            val getDatabase = dataModelClass.getDeclaredMethod("getDatabase").apply { isAccessible = true }
            val readMessage = bugleDatabaseClass.declaredMethods.single { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == "readMessageData" &&
                    method.parameterTypes.contentEquals(arrayOf(getDatabase.returnType, Uri::class.java)) &&
                    method.returnType == messageDataClass
            }.apply { isAccessible = true }
            val messageId = messageDataClass.getDeclaredMethod("getMessageId").apply { isAccessible = true }
            val smsUri = messageDataClass.getDeclaredMethod("getSmsMessageUri").apply { isAccessible = true }
            val processSentActionConstructor = processSentClass.getDeclaredConstructor().apply { isAccessible = true }
            val processResult = processSentClass.getDeclaredMethod(
                "processResult",
                String::class.java,
                Uri::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType!!,
                actionBaseClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            runtime.hooks.around(schedulePending, "aosp-messaging.outgoing.prevent-carrier-send") {
                if (suppressPendingSend.get() == true) {
                    Log.i(TAG, "Suppressed Bugle ProcessPendingMessagesAction for Xiaomi network SMS")
                    null
                } else {
                    proceed()
                }
            }
            Log.i(TAG, "Resolved native AOSP InsertNewMessageAction + ProcessSentMessageAction outgoing pipeline")
            AospMessagingSendAdapter(
                constructor,
                execute,
                timestamp,
                dataModelGet,
                getDatabase,
                readMessage,
                messageId,
                smsUri,
                processSentActionConstructor,
                processResult,
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not resolve AOSP native outgoing-message pipeline", error)
        }.getOrNull()
    }
}
