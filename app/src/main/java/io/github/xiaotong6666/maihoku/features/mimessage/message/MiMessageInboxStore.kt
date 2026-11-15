package io.github.xiaotong6666.maihoku.features.mimessage.message

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class StoredIncomingMessage(
    val messageId: String,
    val packetId: String,
    val senderMid: String,
    val senderJid: String,
    val recipientMid: String,
    val address: String,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long,
    val subscriptionId: Int,
    val ackSentAt: Long,
    val ackAttempts: Int,
    val lastAckError: String?,
    val telephonyUri: String?,
    val telephonyImportedAt: Long,
    val lastImportError: String?,
)

internal data class PersistedIncomingMessage(
    val message: StoredIncomingMessage,
    val inserted: Boolean,
)

internal interface MiMessageInboxRepository {
    fun persist(event: MiMessageEventProcessor.Event.IncomingSms, subscriptionId: Int, receivedAt: Long): PersistedIncomingMessage
    fun markAckSent(messageId: String, timestamp: Long)
    fun markAckFailed(messageId: String, error: String)
    fun pendingAcks(limit: Int = 100): List<StoredIncomingMessage>
    fun pendingImports(limit: Int = 100): List<StoredIncomingMessage>
    fun markImported(messageId: String, uri: String, timestamp: Long)
    fun markImportFailed(messageId: String, error: String)
}

/** Durable, app-private inbox and de-duplication ledger for Xiaomi network SMS. */
internal class MiMessageInboxStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    MiMessageInboxRepository {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                message_id TEXT PRIMARY KEY NOT NULL,
                packet_id TEXT NOT NULL,
                sender_mid TEXT NOT NULL,
                sender_jid TEXT NOT NULL,
                recipient_mid TEXT NOT NULL,
                address TEXT NOT NULL,
                body TEXT NOT NULL,
                sent_at INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL,
                subscription_id INTEGER NOT NULL DEFAULT -1,
                ack_sent_at INTEGER NOT NULL DEFAULT 0,
                ack_attempts INTEGER NOT NULL DEFAULT 0,
                last_ack_error TEXT,
                telephony_uri TEXT,
                telephony_imported_at INTEGER NOT NULL DEFAULT 0,
                last_import_error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_mi_inbox_pending_ack ON $TABLE_MESSAGES(ack_sent_at, received_at)")
        db.execSQL("CREATE INDEX index_mi_inbox_pending_import ON $TABLE_MESSAGES(telephony_uri, received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes must use an explicit migration; never discard received message history.
        error("Unsupported Mi Message inbox database upgrade: $oldVersion -> $newVersion")
    }

    override fun persist(
        event: MiMessageEventProcessor.Event.IncomingSms,
        subscriptionId: Int,
        receivedAt: Long,
    ): PersistedIncomingMessage {
        require(event.messageId.toLongOrNull()?.let { it > 0L } == true) { "Incoming Mi Message ID is invalid" }
        require(event.packetId.isNotBlank() && event.packetId.length <= 160) { "Incoming XMPP packet ID is invalid" }
        require(event.senderMid.toLongOrNull()?.let { it > 0L } == true) { "Incoming sender MID is invalid" }
        require(event.senderJid.length <= 256 && event.senderJid.contains("@xiaomi.com")) {
            "Incoming sender JID is invalid"
        }
        require(event.threadAddress.isNotBlank() && event.threadAddress.length <= 128) { "Incoming SMS address is invalid" }
        require(event.body.isNotEmpty() && event.body.length <= MAX_BODY_CHARS) { "Incoming SMS body is empty or too large" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE_MESSAGES,
                null,
                "message_id=?",
                arrayOf(event.messageId),
                null,
                null,
                null,
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toStoredMessage() else null }

            if (existing != null) {
                check(
                    existing.senderMid == event.senderMid &&
                        existing.recipientMid == event.recipientMid &&
                        existing.address == event.threadAddress &&
                        existing.body == event.body,
                ) { "Mi Message ID collision: existing message content does not match the retransmission" }

                // The server may redeliver the same message with a fresh XMPP stanza ID.
                // Keep the latest packet ID so a deferred received ACK confirms this delivery.
                db.update(
                    TABLE_MESSAGES,
                    ContentValues().apply {
                        put("packet_id", event.packetId)
                        put("sender_jid", event.senderJid)
                        put("subscription_id", subscriptionId)
                    },
                    "message_id=?",
                    arrayOf(event.messageId),
                )
                val updated = requireNotNull(readByMessageId(db, event.messageId))
                db.setTransactionSuccessful()
                return PersistedIncomingMessage(updated, inserted = false)
            }

            db.insertOrThrow(
                TABLE_MESSAGES,
                null,
                ContentValues().apply {
                    put("message_id", event.messageId)
                    put("packet_id", event.packetId)
                    put("sender_mid", event.senderMid)
                    put("sender_jid", event.senderJid)
                    put("recipient_mid", event.recipientMid)
                    put("address", event.threadAddress)
                    put("body", event.body)
                    put("sent_at", event.sentAt)
                    put("received_at", receivedAt)
                    put("subscription_id", subscriptionId)
                },
            )
            val inserted = requireNotNull(readByMessageId(db, event.messageId))
            db.setTransactionSuccessful()
            return PersistedIncomingMessage(inserted, inserted = true)
        } finally {
            db.endTransaction()
        }
    }

    override fun markAckSent(messageId: String, timestamp: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_MESSAGES SET ack_sent_at=?, ack_attempts=ack_attempts+1, last_ack_error=NULL WHERE message_id=?",
            arrayOf<Any>(timestamp, messageId),
        )
    }

    override fun markAckFailed(messageId: String, error: String) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_MESSAGES SET ack_sent_at=0, ack_attempts=ack_attempts+1, last_ack_error=? WHERE message_id=?",
            arrayOf(error.take(80), messageId),
        )
    }

    override fun pendingAcks(limit: Int): List<StoredIncomingMessage> = queryMessages(
        selection = "ack_sent_at=0",
        orderBy = "received_at ASC",
        limit = limit,
    )

    override fun pendingImports(limit: Int): List<StoredIncomingMessage> = queryMessages(
        selection = "telephony_uri IS NULL",
        orderBy = "received_at ASC",
        limit = limit,
    )

    override fun markImported(messageId: String, uri: String, timestamp: Long) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply {
                put("telephony_uri", uri)
                put("telephony_imported_at", timestamp)
                putNull("last_import_error")
            },
            "message_id=?",
            arrayOf(messageId),
        )
    }

    override fun markImportFailed(messageId: String, error: String) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply { put("last_import_error", error.take(80)) },
            "message_id=?",
            arrayOf(messageId),
        )
    }

    private fun queryMessages(selection: String, orderBy: String, limit: Int): List<StoredIncomingMessage> {
        val safeLimit = limit.coerceIn(1, 500).toString()
        return readableDatabase.query(
            TABLE_MESSAGES,
            null,
            selection,
            null,
            null,
            null,
            orderBy,
            safeLimit,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toStoredMessage())
            }
        }
    }

    private fun readByMessageId(db: SQLiteDatabase, messageId: String): StoredIncomingMessage? = db.query(TABLE_MESSAGES, null, "message_id=?", arrayOf(messageId), null, null, null, "1")
        .use { cursor -> if (cursor.moveToFirst()) cursor.toStoredMessage() else null }

    private fun Cursor.toStoredMessage() = StoredIncomingMessage(
        messageId = getString(getColumnIndexOrThrow("message_id")),
        packetId = getString(getColumnIndexOrThrow("packet_id")),
        senderMid = getString(getColumnIndexOrThrow("sender_mid")),
        senderJid = getString(getColumnIndexOrThrow("sender_jid")),
        recipientMid = getString(getColumnIndexOrThrow("recipient_mid")),
        address = getString(getColumnIndexOrThrow("address")),
        body = getString(getColumnIndexOrThrow("body")),
        sentAt = getLong(getColumnIndexOrThrow("sent_at")),
        receivedAt = getLong(getColumnIndexOrThrow("received_at")),
        subscriptionId = getInt(getColumnIndexOrThrow("subscription_id")),
        ackSentAt = getLong(getColumnIndexOrThrow("ack_sent_at")),
        ackAttempts = getInt(getColumnIndexOrThrow("ack_attempts")),
        lastAckError = getString(getColumnIndexOrThrow("last_ack_error")),
        telephonyUri = getString(getColumnIndexOrThrow("telephony_uri")),
        telephonyImportedAt = getLong(getColumnIndexOrThrow("telephony_imported_at")),
        lastImportError = getString(getColumnIndexOrThrow("last_import_error")),
    )

    companion object {
        const val DATABASE_NAME = "mi_message_inbox.db"
        const val TABLE_MESSAGES = "incoming_messages"
        const val DATABASE_VERSION = 1
        const val MAX_BODY_CHARS = 8192
    }
}
