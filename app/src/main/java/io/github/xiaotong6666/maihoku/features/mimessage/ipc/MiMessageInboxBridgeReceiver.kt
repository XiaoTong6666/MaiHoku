package io.github.xiaotong6666.maihoku.features.mimessage.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.xiaotong6666.maihoku.features.mimessage.core.MiMessageManager
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageInboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutboxStore
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutgoingStatus
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy
import java.util.concurrent.Executors
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Receives registration/import-completion messages from the currently selected default SMS app. */
class MiMessageInboxBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        worker.execute {
            try {
                handle(context.applicationContext, intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Inbox bridge control message failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val hostPackage = intent.getStringExtra(MiMessageInboxBridge.KEY_HOST_PACKAGE)
            ?.takeIf(MiMessageInboxBridge::isSupportedHost)
            ?: return
        if (!isTrustedDefaultSmsHost(context, hostPackage)) {
            Log.w(TAG, "Rejected inbox bridge action from a non-default SMS host")
            return
        }

        val token = intent.getStringExtra(MiMessageInboxBridge.KEY_TOKEN)
            ?.takeIf { it.length in 40..64 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
            ?: return

        when (intent.action) {
            MiMessageInboxBridge.ACTION_REGISTER_HOST -> {
                MiMessageInboxBridge.storeHostToken(context, hostPackage, token)
                val pendingMessages = MiMessageInboxStore(context).use { it.pendingImports(100) }
                pendingMessages.forEach { message ->
                    MiMessageInboxBridge.dispatchToHost(context, hostPackage, token, message)
                }
                val outgoingUpdates = MiMessageOutboxStore(context).use { it.recentHostStatusUpdates() }
                    .filter { it.hostPackage == hostPackage }
                outgoingUpdates.forEach { record ->
                    MiMessageInboxBridge.dispatchOutgoingStatus(
                        context,
                        hostPackage,
                        record.requestId,
                        record.status,
                        record.error,
                    )
                }
                Log.i(
                    TAG,
                    "Default SMS host registered; pending imports=${pendingMessages.size} " +
                        "outgoing status updates=${outgoingUpdates.size}",
                )
            }

            MiMessageInboxBridge.ACTION_QUERY_PRESENCE -> {
                // The caller has already passed the default-SMS-package and sender-UID checks.
                // Refresh the host token here as well so a first compose query cannot race the
                // asynchronous inbox-bridge registration sent during Application.onCreate.
                MiMessageInboxBridge.storeHostToken(context, hostPackage, token)
                val phoneNumber = intent.getStringExtra(MiMessageInboxBridge.KEY_PRESENCE_PHONE)
                    ?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }
                    ?: return
                Log.i(TAG, "Accepted Presence query from default SMS host=$hostPackage recipient_digits=${phoneNumber.count(Char::isDigit)}")
                MiMessageManager.requestPresence(context, phoneNumber)
            }

            MiMessageInboxBridge.ACTION_QUEUE_OUTGOING -> {
                if (!MiMessageInboxBridge.isValidHostToken(context, hostPackage, token)) {
                    MiMessageInboxBridge.storeHostToken(context, hostPackage, token)
                }
                val requestId = intent.getStringExtra(MiMessageInboxBridge.KEY_OUTGOING_REQUEST_ID)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,64}")) }
                    ?: return
                val phoneNumber = intent.getStringExtra(MiMessageInboxBridge.KEY_ADDRESS)
                    ?.takeIf { MiMessagePresencePolicy.normalizePhoneNumber(it) != null }
                    ?: return
                val body = intent.getStringExtra(MiMessageInboxBridge.KEY_BODY)
                    ?.takeIf { it.isNotEmpty() && it.length <= MiMessageOutboxStore.MAX_BODY_CHARS }
                    ?: return
                val accepted = runCatching {
                    MiMessageManager.queueOutgoing(context, requestId, hostPackage, phoneNumber, body)
                }.onFailure { error ->
                    Log.e(TAG, "Could not queue outgoing Mi Message: ${error.javaClass.simpleName}")
                }.getOrDefault(false)
                if (accepted) {
                    MiMessageInboxBridge.dispatchOutgoingStatus(
                        context,
                        hostPackage,
                        requestId,
                        MiMessageOutgoingStatus.QUEUED,
                    )
                    Log.i(TAG, "Accepted outgoing Mi Message request_id_length=${requestId.length} host=$hostPackage")
                } else {
                    MiMessageInboxBridge.dispatchOutgoingStatus(
                        context,
                        hostPackage,
                        requestId,
                        MiMessageOutgoingStatus.FAILED,
                        "MI_MESSAGE_DISABLED_OR_CORE_UNAVAILABLE",
                    )
                    Log.w(TAG, "Rejected outgoing Mi Message because the Push core is not enabled")
                }
            }

            MiMessageInboxBridge.ACTION_MARK_IMPORTED -> {
                if (!MiMessageInboxBridge.isValidHostToken(context, hostPackage, token)) return
                val messageId = intent.getStringExtra(MiMessageInboxBridge.KEY_MESSAGE_ID)
                    ?: return
                val uri = intent.getStringExtra(MiMessageInboxBridge.KEY_TELEPHONY_URI)
                    ?.takeIf { it.startsWith("content://sms/") }
                    ?: return
                MiMessageInboxStore(context).use { it.markImported(messageId, uri, System.currentTimeMillis()) }
                Log.i(TAG, "Default SMS host confirmed import message_id_length=${messageId.length}")
            }

            MiMessageInboxBridge.ACTION_MARK_IMPORT_FAILED -> {
                if (!MiMessageInboxBridge.isValidHostToken(context, hostPackage, token)) return
                val messageId = intent.getStringExtra(MiMessageInboxBridge.KEY_MESSAGE_ID)
                    ?: return
                val error = intent.getStringExtra(MiMessageInboxBridge.KEY_ERROR) ?: "HOST_IMPORT_FAILED"
                MiMessageInboxStore(context).use { it.markImportFailed(messageId, error) }
                Log.w(TAG, "Default SMS host import failed message_id_length=${messageId.length} error=$error")
            }
        }
    }

    private fun isTrustedDefaultSmsHost(context: Context, hostPackage: String): Boolean {
        if (!MiMessageInboxBridge.isDefaultSmsHost(context, hostPackage)) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val senderUid = sentFromUid
            if (senderUid >= 0) {
                return context.packageManager.getPackagesForUid(senderUid)?.contains(hostPackage) == true
            }
        }
        // On API 26-33 the manifest receiver also requires SEND_SMS. Combined with the current
        // default-SMS-role check and the per-install random token this is the compatibility path.
        return true
    }

    companion object {
        private const val TAG = "MiMessageInbox"
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MaiHoku-Inbox-Bridge-Control").apply { isDaemon = true }
        }
    }
}
