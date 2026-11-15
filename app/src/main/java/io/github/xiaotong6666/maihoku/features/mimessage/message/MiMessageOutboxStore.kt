package io.github.xiaotong6666.maihoku.features.mimessage.message

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.features.mimessage.presence.MiMessagePresencePolicy

internal data class MiMessageOutgoingRecord(
    val requestId: String,
    val hostPackage: String,
    val phoneNumber: String,
    val body: String,
    val status: String,
    val packetId: String?,
    val recipientMid: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Durable one-shot send queue. SUBMITTED is never replayed after process death to avoid duplicates. */
internal class MiMessageOutboxStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_OUTGOING (
                request_id TEXT PRIMARY KEY NOT NULL,
                host_package TEXT NOT NULL,
                phone_number TEXT NOT NULL,
                body TEXT NOT NULL,
                status TEXT NOT NULL,
                packet_id TEXT,
                recipient_mid TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_mi_outbox_status_created ON $TABLE_OUTGOING(status, created_at)")
        db.execSQL("CREATE UNIQUE INDEX index_mi_outbox_packet_id ON $TABLE_OUTGOING(packet_id) WHERE packet_id IS NOT NULL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported Mi Message outbox database upgrade: $oldVersion -> $newVersion")
    }

    fun enqueue(
        requestId: String,
        hostPackage: String,
        phoneNumber: String,
        body: String,
        now: Long = System.currentTimeMillis(),
    ): MiMessageOutgoingRecord {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Invalid outgoing request ID" }
        require(MiMessageInboxBridge.isSupportedHost(hostPackage)) { "Unsupported SMS host package" }
        require(MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) != null) { "Invalid outgoing recipient" }
        require(body.isNotEmpty() && body.length <= MAX_BODY_CHARS) { "Outgoing message body is empty or too large" }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readByRequestId(db, requestId)
            if (existing != null) {
                check(existing.hostPackage == hostPackage && existing.phoneNumber == phoneNumber && existing.body == body) {
                    "Outgoing request ID was reused with different message content"
                }
                db.setTransactionSuccessful()
                return existing
            }
            db.insertOrThrow(
                TABLE_OUTGOING,
                null,
                ContentValues().apply {
                    put(COLUMN_REQUEST_ID, requestId)
                    put(COLUMN_HOST_PACKAGE, hostPackage)
                    put(COLUMN_PHONE_NUMBER, phoneNumber)
                    put(COLUMN_BODY, body)
                    put(COLUMN_STATUS, MiMessageOutgoingStatus.QUEUED)
                    put(COLUMN_CREATED_AT, now)
                    put(COLUMN_UPDATED_AT, now)
                },
            )
            val inserted = requireNotNull(readByRequestId(db, requestId))
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    fun pendingToSend(limit: Int = 32): List<MiMessageOutgoingRecord> = readableDatabase.query(
        TABLE_OUTGOING,
        null,
        "$COLUMN_STATUS=?",
        arrayOf(MiMessageOutgoingStatus.QUEUED),
        null,
        null,
        "$COLUMN_CREATED_AT ASC",
        limit.coerceIn(1, 100).toString(),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toOutgoingRecord()) }
    }

    /** A process died while resolving/sending; do not replay a possibly-written SECMSG. */
    fun recoverInterrupted(now: Long = System.currentTimeMillis()): List<MiMessageOutgoingRecord> {
        val db = writableDatabase
        db.update(
            TABLE_OUTGOING,
            ContentValues().apply {
                put(COLUMN_STATUS, MiMessageOutgoingStatus.UNCONFIRMED)
                put(COLUMN_ERROR, "PROCESS_RESTART_DURING_SEND")
                put(COLUMN_UPDATED_AT, now)
            },
            "$COLUMN_STATUS IN (?, ?)",
            arrayOf(MiMessageOutgoingStatus.RESOLVING, MiMessageOutgoingStatus.SUBMITTED),
        )
        return db.query(
            TABLE_OUTGOING,
            null,
            "$COLUMN_STATUS=?",
            arrayOf(MiMessageOutgoingStatus.UNCONFIRMED),
            null,
            null,
            "$COLUMN_UPDATED_AT DESC",
            "100",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toOutgoingRecord()) }
        }
    }

    /** Replay terminal/ambiguous states to the SMS host after a Core process restart. */
    fun recentHostStatusUpdates(limit: Int = 100): List<MiMessageOutgoingRecord> = readableDatabase.query(
        TABLE_OUTGOING,
        null,
        "$COLUMN_STATUS IN (?, ?, ?, ?)",
        arrayOf(
            MiMessageOutgoingStatus.SERVER_ACCEPTED,
            MiMessageOutgoingStatus.DELIVERED,
            MiMessageOutgoingStatus.FAILED,
            MiMessageOutgoingStatus.UNCONFIRMED,
        ),
        null,
        null,
        "$COLUMN_UPDATED_AT DESC",
        limit.coerceIn(1, 100).toString(),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toOutgoingRecord()) }
    }

    fun markResolving(requestId: String, now: Long = System.currentTimeMillis()): Boolean = transition(
        requestId,
        MiMessageOutgoingStatus.QUEUED,
        MiMessageOutgoingStatus.RESOLVING,
        now,
    )

    fun markSubmitted(
        requestId: String,
        packetId: String,
        recipientMid: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(packetId.isNotBlank() && packetId.length <= 160)
        require(recipientMid.toLongOrNull()?.let { it > 0L } == true)
        val values = ContentValues().apply {
            put(COLUMN_STATUS, MiMessageOutgoingStatus.SUBMITTED)
            put(COLUMN_PACKET_ID, packetId)
            put(COLUMN_RECIPIENT_MID, recipientMid)
            put(COLUMN_UPDATED_AT, now)
            putNull(COLUMN_ERROR)
        }
        return writableDatabase.update(
            TABLE_OUTGOING,
            values,
            "$COLUMN_REQUEST_ID=? AND $COLUMN_STATUS=?",
            arrayOf(requestId, MiMessageOutgoingStatus.RESOLVING),
        ) == 1
    }

    fun markServerAccepted(packetId: String, now: Long = System.currentTimeMillis()): MiMessageOutgoingRecord? = updateByPacketId(
        packetId,
        MiMessageOutgoingStatus.SERVER_ACCEPTED,
        null,
        now,
        arrayOf(MiMessageOutgoingStatus.SUBMITTED, MiMessageOutgoingStatus.SERVER_ACCEPTED, MiMessageOutgoingStatus.UNCONFIRMED),
    )

    fun markDelivered(packetId: String, now: Long = System.currentTimeMillis()): MiMessageOutgoingRecord? = updateByPacketId(
        packetId,
        MiMessageOutgoingStatus.DELIVERED,
        null,
        now,
        arrayOf(MiMessageOutgoingStatus.SUBMITTED, MiMessageOutgoingStatus.SERVER_ACCEPTED, MiMessageOutgoingStatus.UNCONFIRMED),
    )

    fun markFailed(
        requestId: String,
        error: String,
        now: Long = System.currentTimeMillis(),
    ): MiMessageOutgoingRecord? {
        writableDatabase.update(
            TABLE_OUTGOING,
            ContentValues().apply {
                put(COLUMN_STATUS, MiMessageOutgoingStatus.FAILED)
                put(COLUMN_ERROR, error.take(96))
                put(COLUMN_UPDATED_AT, now)
            },
            "$COLUMN_REQUEST_ID=? AND $COLUMN_STATUS IN (?, ?, ?, ?)",
            arrayOf(
                requestId,
                MiMessageOutgoingStatus.QUEUED,
                MiMessageOutgoingStatus.RESOLVING,
                MiMessageOutgoingStatus.SUBMITTED,
                MiMessageOutgoingStatus.SERVER_ACCEPTED,
                MiMessageOutgoingStatus.UNCONFIRMED,
            ),
        )
        return findByRequestId(requestId)
    }

    fun markUnconfirmed(packetId: String, now: Long = System.currentTimeMillis()): MiMessageOutgoingRecord? = updateByPacketId(
        packetId,
        MiMessageOutgoingStatus.UNCONFIRMED,
        "SERVER_SENT_RECEIPT_TIMEOUT",
        now,
        arrayOf(MiMessageOutgoingStatus.SUBMITTED, MiMessageOutgoingStatus.SERVER_ACCEPTED),
    )

    fun findByRequestId(requestId: String): MiMessageOutgoingRecord? = readByRequestId(readableDatabase, requestId)

    fun findByPacketId(packetId: String): MiMessageOutgoingRecord? = readableDatabase.query(
        TABLE_OUTGOING,
        null,
        "$COLUMN_PACKET_ID=?",
        arrayOf(packetId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutgoingRecord() else null }

    private fun transition(requestId: String, from: String, to: String, now: Long): Boolean = writableDatabase.update(
        TABLE_OUTGOING,
        ContentValues().apply {
            put(COLUMN_STATUS, to)
            put(COLUMN_UPDATED_AT, now)
        },
        "$COLUMN_REQUEST_ID=? AND $COLUMN_STATUS=?",
        arrayOf(requestId, from),
    ) == 1

    private fun updateByPacketId(
        packetId: String,
        status: String,
        error: String?,
        now: Long,
        allowedFrom: Array<String>,
    ): MiMessageOutgoingRecord? {
        val values = ContentValues().apply {
            put(COLUMN_STATUS, status)
            if (error == null) putNull(COLUMN_ERROR) else put(COLUMN_ERROR, error)
            put(COLUMN_UPDATED_AT, now)
        }
        writableDatabase.update(
            TABLE_OUTGOING,
            values,
            "$COLUMN_PACKET_ID=? AND $COLUMN_STATUS IN (${allowedFrom.joinToString(",") { "?" }})",
            arrayOf(packetId, *allowedFrom),
        )
        return findByPacketId(packetId)
    }

    private fun readByRequestId(db: SQLiteDatabase, requestId: String): MiMessageOutgoingRecord? = db.query(
        TABLE_OUTGOING,
        null,
        "$COLUMN_REQUEST_ID=?",
        arrayOf(requestId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutgoingRecord() else null }

    private fun Cursor.toOutgoingRecord() = MiMessageOutgoingRecord(
        requestId = getString(getColumnIndexOrThrow(COLUMN_REQUEST_ID)),
        hostPackage = getString(getColumnIndexOrThrow(COLUMN_HOST_PACKAGE)),
        phoneNumber = getString(getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
        body = getString(getColumnIndexOrThrow(COLUMN_BODY)),
        status = getString(getColumnIndexOrThrow(COLUMN_STATUS)),
        packetId = getString(getColumnIndexOrThrow(COLUMN_PACKET_ID)),
        recipientMid = getString(getColumnIndexOrThrow(COLUMN_RECIPIENT_MID)),
        error = getString(getColumnIndexOrThrow(COLUMN_ERROR)),
        createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
    )

    companion object {
        const val DATABASE_NAME = "mi_message_outbox.db"
        const val DATABASE_VERSION = 1
        const val MAX_BODY_CHARS = 8192
        private const val TABLE_OUTGOING = "outgoing_messages"
        private const val COLUMN_REQUEST_ID = "request_id"
        private const val COLUMN_HOST_PACKAGE = "host_package"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_BODY = "body"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_PACKET_ID = "packet_id"
        private const val COLUMN_RECIPIENT_MID = "recipient_mid"
        private const val COLUMN_ERROR = "error"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
    }
}

internal object MiMessageOutgoingStatus {
    const val QUEUED = "QUEUED"
    const val RESOLVING = "RESOLVING"
    const val SUBMITTED = "SUBMITTED"
    const val SERVER_ACCEPTED = "SERVER_ACCEPTED"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
    const val UNCONFIRMED = "UNCONFIRMED"
    val ALL = setOf(QUEUED, RESOLVING, SUBMITTED, SERVER_ACCEPTED, DELIVERED, FAILED, UNCONFIRMED)
}
