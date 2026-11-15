package io.github.xiaotong6666.maihoku.mimessage.host.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import io.github.xiaotong6666.maihoku.AOSP_MESSAGING_PACKAGE
import io.github.xiaotong6666.maihoku.GOOGLE_MESSAGES_PACKAGE
import io.github.xiaotong6666.maihoku.R
import io.github.xiaotong6666.maihoku.mimessage.host.aosp.AospMessagingSendAdapter
import io.github.xiaotong6666.maihoku.mimessage.host.google.GoogleMessagesSendAdapter
import io.github.xiaotong6666.maihoku.mimessage.message.MiMessageOutgoingStatus
import io.github.xiaotong6666.maihoku.mimessage.ui.MessagingResources
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Coordinates host-owned conversation rows with the MaiHoku-UID ChID=3 send queue. */
internal object MessagingHostOutgoingUi {
    private const val TAG = "MessagingSend"
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaiHoku-Messaging-Outgoing").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingEditors = ConcurrentHashMap<String, WeakReference<EditText>>()

    fun enqueue(
        context: Context,
        modulePackageName: String,
        phoneNumber: String,
        body: String,
        subscriptionId: Int,
        editor: EditText,
        adapter: MessagingHostSendAdapter,
    ) {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString().replace("-", "")
        pendingEditors[requestId] = WeakReference(editor)
        worker.execute {
            try {
                val localRecord = adapter.createPendingMessage(
                    context = appContext,
                    requestId = requestId,
                    phoneNumber = phoneNumber,
                    body = body,
                    subscriptionId = subscriptionId,
                )
                persistAndDispatch(appContext, modulePackageName, localRecord, adapter)
            } catch (error: Throwable) {
                pendingEditors.remove(requestId)
                Log.e(TAG, "Could not queue Mi Message from host UI: ${error.javaClass.simpleName}")
                showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_queue_failed_draft))
            }
        }
    }

    /** Google Messages already created its own pending Telephony/Bugle row before SmsManager. */
    fun enqueuePrepared(
        context: Context,
        modulePackageName: String,
        record: MessagingHostOutgoingRecord,
        adapter: MessagingHostSendAdapter,
    ) {
        val appContext = context.applicationContext
        worker.execute { persistAndDispatch(appContext, modulePackageName, record, adapter) }
    }

    private fun persistAndDispatch(
        context: Context,
        modulePackageName: String,
        record: MessagingHostOutgoingRecord,
        adapter: MessagingHostSendAdapter,
    ) {
        try {
            MessagingHostOutgoingStore(context).use { it.save(record) }
            check(
                MessagingHostBridge.queueOutgoing(
                    context = context,
                    modulePackageName = modulePackageName,
                    requestId = record.requestId,
                    phoneNumber = record.address,
                    body = record.body,
                ),
            ) { "Could not dispatch outgoing request to MaiHoku" }
            Log.i(TAG, "Outgoing Mi Message queued host=${record.hostPackage} body_chars=${record.body.length}")
        } catch (error: Throwable) {
            pendingEditors.remove(record.requestId)
            runCatching { adapter.applyStatus(context, record, MiMessageOutgoingStatus.FAILED, "HOST_QUEUE_FAILED") }
            runCatching {
                MessagingHostOutgoingStore(context).use {
                    it.updateStatus(record.requestId, MiMessageOutgoingStatus.FAILED)
                }
            }
            Log.e(TAG, "Could not queue Mi Message from host UI: ${error.javaClass.simpleName}")
            showToast(context, MessagingResources.text(context, R.string.mx_send_queue_failed))
        }
    }

    fun onOutgoingStatus(
        context: Context,
        hostPackage: String,
        requestId: String,
        status: String,
        error: String?,
        aospAdapter: AospMessagingSendAdapter?,
        googleAdapter: GoogleMessagesSendAdapter?,
    ) {
        val appContext = context.applicationContext
        worker.execute {
            val record = MessagingHostOutgoingStore(appContext).use { it.find(requestId) }
            if (record == null || record.hostPackage != hostPackage) {
                Log.w(TAG, "Outgoing status has no matching host row request_id_length=${requestId.length}")
                return@execute
            }
            if (record.status == status && status in TERMINAL_STATUSES) {
                Log.d(TAG, "Outgoing host row already reflects replayed status=$status request_id_length=${requestId.length}")
                return@execute
            }
            val adapter = when (hostPackage) {
                AOSP_MESSAGING_PACKAGE -> aospAdapter
                GOOGLE_MESSAGES_PACKAGE -> googleAdapter
                else -> null
            }
            if (status in TERMINAL_STATUSES) {
                if (adapter == null) {
                    Log.e(TAG, "Native send adapter is unavailable for terminal status=$status")
                    return@execute
                }
                runCatching { adapter.applyStatus(appContext, record, status, error) }
                    .onFailure { failure ->
                        Log.e(TAG, "Could not update native outgoing message status=$status: ${failure.javaClass.simpleName}")
                        return@execute
                    }
            }
            MessagingHostOutgoingStore(appContext).use { it.updateStatus(requestId, status) }
            when (status) {
                MiMessageOutgoingStatus.QUEUED -> {
                    clearSubmittedEditor(requestId, record.body)
                    showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_queued))
                }

                MiMessageOutgoingStatus.SERVER_ACCEPTED -> showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_sent))

                MiMessageOutgoingStatus.DELIVERED -> showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_delivered))

                MiMessageOutgoingStatus.FAILED -> showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_failed, error ?: MessagingResources.text(appContext, R.string.mx_send_failed_hint)))

                MiMessageOutgoingStatus.UNCONFIRMED -> showToast(appContext, MessagingResources.text(appContext, R.string.mx_send_unconfirmed))
            }
            Log.i(TAG, "Outgoing host row updated status=$status request_id_length=${requestId.length}")
        }
    }

    private fun clearSubmittedEditor(requestId: String, submittedBody: String) {
        val editor = pendingEditors.remove(requestId)?.get() ?: return
        mainHandler.post {
            if (editor.isAttachedToWindow && editor.text?.toString() == submittedBody) {
                editor.text?.clear()
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    private val TERMINAL_STATUSES = setOf(
        MiMessageOutgoingStatus.SERVER_ACCEPTED,
        MiMessageOutgoingStatus.DELIVERED,
        MiMessageOutgoingStatus.FAILED,
        MiMessageOutgoingStatus.UNCONFIRMED,
    )
    private const val AOSP_MESSAGING_PACKAGE = "com.android.messaging"
    private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
}
