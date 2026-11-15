package io.github.xiaotong6666.maihoku.mimessage.message

internal fun interface MiMessageReceivedAckSender {
    /** originalPacketId is the XMPP message stanza ID, not the JSON sms.msgId. */
    fun send(originalSenderJid: String, originalPacketId: String)
}

internal fun interface MiMessageInboxDispatcher {
    /** Requests import into the current default SMS app. Import completion is reported separately. */
    fun dispatch(message: StoredIncomingMessage): Boolean
}

internal data class MiMessageInboxReceiveResult(
    val inserted: Boolean,
    val ackFrameWritten: Boolean,
    val importDispatched: Boolean,
)

/** Enforces durable-local-write -> received ACK -> default SMS app import ordering. */
internal class MiMessageInboxCoordinator(
    private val repository: MiMessageInboxRepository,
    private val ackSender: MiMessageReceivedAckSender,
    private val dispatcher: MiMessageInboxDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun receive(
        event: MiMessageEventProcessor.Event.IncomingSms,
        subscriptionId: Int,
    ): MiMessageInboxReceiveResult {
        // The SQLite transaction commits before any ACK is sent. If persistence fails, the
        // exception propagates and the server can redeliver the stanza.
        val persisted = repository.persist(event, subscriptionId, clock())

        // A received ACK confirms durable receipt, not host import or read status. Send it even
        // for a duplicate: an earlier ACK may have been lost after the local transaction committed.
        val ackWritten = runCatching {
            ackSender.send(event.senderJid, event.packetId)
            repository.markAckSent(event.messageId, clock())
        }.onFailure { error ->
            repository.markAckFailed(event.messageId, error.javaClass.simpleName)
        }.isSuccess

        // Host import has its own acknowledgement and runs under the default SMS app's UID.
        // Import failure must not roll back durable receipt or trigger redelivery of a stored SMS.
        val importDispatched = runCatching {
            dispatcher.dispatch(persisted.message)
        }.onFailure { error ->
            repository.markImportFailed(event.messageId, error.javaClass.simpleName)
        }.getOrDefault(false)

        return MiMessageInboxReceiveResult(
            inserted = persisted.inserted,
            ackFrameWritten = ackWritten,
            importDispatched = importDispatched,
        )
    }

    /** Retries only messages whose received ACK frame was never successfully written. */
    fun retryPendingAcks(): Int {
        var written = 0
        repository.pendingAcks().forEach { message ->
            runCatching {
                ackSender.send(message.senderJid, message.packetId)
                repository.markAckSent(message.messageId, clock())
                written++
            }.onFailure { error ->
                repository.markAckFailed(message.messageId, error.javaClass.simpleName)
            }
        }
        return written
    }
}
