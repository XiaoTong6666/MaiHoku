package io.github.xiaotong6666.maihoku.features.mimessage.host.common

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.xiaotong6666.maihoku.features.mimessage.ipc.MiMessageInboxBridge
import io.github.xiaotong6666.maihoku.features.mimessage.message.MiMessageOutboxStore

internal data class MessagingHostOutgoingRecord(
    val requestId: String,
    val hostPackage: String,
    val address: String,
    val body: String,
    val telephonyUri: String,
    val bugleMessageId: String?,
    val subscriptionId: Int,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Host-UID mapping from a MaiHoku request to the SMS/Bugle row owned by the default SMS app. */
internal class MessagingHostOutgoingStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_OUTGOING (
                request_id TEXT PRIMARY KEY NOT NULL,
                host_package TEXT NOT NULL,
                address TEXT NOT NULL,
                body TEXT NOT NULL,
                telephony_uri TEXT NOT NULL,
                bugle_message_id TEXT,
                subscription_id INTEGER NOT NULL DEFAULT -1,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_host_outbox_status_created ON $TABLE_OUTGOING(status, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported host outgoing-message database upgrade: $oldVersion -> $newVersion")
    }

    fun save(record: MessagingHostOutgoingRecord) {
        require(record.requestId.matches(REQUEST_ID_PATTERN))
        require(MiMessageInboxBridge.isSupportedHost(record.hostPackage))
        require(record.address.isNotBlank() && record.address.length <= 128)
        require(record.body.isNotEmpty() && record.body.length <= MiMessageOutboxStore.MAX_BODY_CHARS)
        require(record.telephonyUri.startsWith("content://sms/"))
        writableDatabase.insertWithOnConflict(
            TABLE_OUTGOING,
            null,
            ContentValues().apply {
                put(COLUMN_REQUEST_ID, record.requestId)
                put(COLUMN_HOST_PACKAGE, record.hostPackage)
                put(COLUMN_ADDRESS, record.address)
                put(COLUMN_BODY, record.body)
                put(COLUMN_TELEPHONY_URI, record.telephonyUri)
                put(COLUMN_BUGLE_MESSAGE_ID, record.bugleMessageId)
                put(COLUMN_SUBSCRIPTION_ID, record.subscriptionId)
                put(COLUMN_STATUS, record.status)
                put(COLUMN_CREATED_AT, record.createdAt)
                put(COLUMN_UPDATED_AT, record.updatedAt)
            },
            SQLiteDatabase.CONFLICT_ABORT,
        )
    }

    fun find(requestId: String): MessagingHostOutgoingRecord? = readableDatabase.query(
        TABLE_OUTGOING,
        null,
        "$COLUMN_REQUEST_ID=?",
        arrayOf(requestId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutgoingRecord() else null }

    fun updateStatus(requestId: String, status: String, now: Long = System.currentTimeMillis()): Boolean = writableDatabase.update(
        TABLE_OUTGOING,
        ContentValues().apply {
            put(COLUMN_STATUS, status)
            put(COLUMN_UPDATED_AT, now)
        },
        "$COLUMN_REQUEST_ID=?",
        arrayOf(requestId),
    ) == 1

    private fun Cursor.toOutgoingRecord() = MessagingHostOutgoingRecord(
        requestId = getString(getColumnIndexOrThrow(COLUMN_REQUEST_ID)),
        hostPackage = getString(getColumnIndexOrThrow(COLUMN_HOST_PACKAGE)),
        address = getString(getColumnIndexOrThrow(COLUMN_ADDRESS)),
        body = getString(getColumnIndexOrThrow(COLUMN_BODY)),
        telephonyUri = getString(getColumnIndexOrThrow(COLUMN_TELEPHONY_URI)),
        bugleMessageId = getString(getColumnIndexOrThrow(COLUMN_BUGLE_MESSAGE_ID)),
        subscriptionId = getInt(getColumnIndexOrThrow(COLUMN_SUBSCRIPTION_ID)),
        status = getString(getColumnIndexOrThrow(COLUMN_STATUS)),
        createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
    )

    companion object {
        const val DATABASE_NAME = "maihoku_network_sms_outgoing.db"
        const val DATABASE_VERSION = 1
        private const val TABLE_OUTGOING = "outgoing_messages"
        private const val COLUMN_REQUEST_ID = "request_id"
        private const val COLUMN_HOST_PACKAGE = "host_package"
        private const val COLUMN_ADDRESS = "address"
        private const val COLUMN_BODY = "body"
        private const val COLUMN_TELEPHONY_URI = "telephony_uri"
        private const val COLUMN_BUGLE_MESSAGE_ID = "bugle_message_id"
        private const val COLUMN_SUBSCRIPTION_ID = "subscription_id"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
    }
}
