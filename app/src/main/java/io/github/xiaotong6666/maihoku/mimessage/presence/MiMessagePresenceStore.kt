package io.github.xiaotong6666.maihoku.mimessage.presence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class MiMessagePresenceCacheRecord(
    val mid: String,
    val status: String,
    val capabilities: Long,
    val updatedAt: Long,
    val expiresAt: Long,
)

/**
 * App-private contact identity index and 60-second Presence cache.
 * Phone↔MID mappings survive process restarts; stale Presence is retained only for the short
 * revalidation grace window and is then removed if the server does not refresh it.
 */
internal class MiMessagePresenceStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONTACTS (
                phone_key TEXT PRIMARY KEY NOT NULL,
                phone_number TEXT NOT NULL,
                mid TEXT NOT NULL,
                resolved_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_mi_presence_contacts_mid ON $TABLE_CONTACTS(mid)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_PRESENCE (
                mid TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                capabilities INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_mi_presence_expiry ON $TABLE_PRESENCE(expires_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported Mi Message Presence database upgrade: $oldVersion -> $newVersion")
    }

    fun saveContact(phoneNumber: String, mid: String, resolvedAt: Long = System.currentTimeMillis()) {
        val phoneKey = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber)
            ?: throw IllegalArgumentException("Invalid contact phone number")
        require(mid.toLongOrNull()?.let { it > 0L } == true) { "Invalid Xiaomi contact MID" }
        writableDatabase.insertWithOnConflict(
            TABLE_CONTACTS,
            null,
            ContentValues().apply {
                put(COLUMN_PHONE_KEY, phoneKey)
                put(COLUMN_PHONE_NUMBER, phoneNumber.trim())
                put(COLUMN_MID, mid)
                put(COLUMN_RESOLVED_AT, resolvedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findMid(phoneNumber: String): String? {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return null
        return findMidByPhoneKey(key)
    }

    fun findMidByPhoneKey(phoneKey: String): String? = readableDatabase.query(
        TABLE_CONTACTS,
        arrayOf(COLUMN_MID),
        "$COLUMN_PHONE_KEY=?",
        arrayOf(phoneKey),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun findPhoneNumber(phoneNumber: String): String? {
        val key = MiMessagePresencePolicy.normalizePhoneNumber(phoneNumber) ?: return null
        return readableDatabase.query(
            TABLE_CONTACTS,
            arrayOf(COLUMN_PHONE_NUMBER),
            "$COLUMN_PHONE_KEY=?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /** Reverse association used when an incoming Presence contains only MID@xiaomi.com/resource. */
    fun findPhoneNumbers(mid: String): List<String> = readableDatabase.query(
        TABLE_CONTACTS,
        arrayOf(COLUMN_PHONE_NUMBER),
        "$COLUMN_MID=?",
        arrayOf(mid),
        null,
        null,
        "$COLUMN_RESOLVED_AT DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    fun getPresence(mid: String, now: Long = System.currentTimeMillis()): MiMessagePresenceCacheRecord? {
        val record = readPresence(mid) ?: return null
        return record.takeIf { MiMessagePresencePolicy.isFresh(it.expiresAt, now) }
    }

    fun hasPresenceRecord(mid: String): Boolean = readPresence(mid) != null

    /** Returns true when the state changed or the previous cache entry had expired. */
    fun updatePresence(
        mid: String,
        status: String,
        capabilities: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        require(mid.toLongOrNull()?.let { it > 0L } == true) { "Invalid Presence MID" }
        require(status == MiMessagePresencePolicy.STATUS_AVAILABLE || status == MiMessagePresencePolicy.STATUS_UNAVAILABLE) {
            "Invalid Presence status"
        }
        val previous = readPresence(mid)
        val changed = previous == null ||
            previous.expiresAt <= now ||
            previous.status != status ||
            previous.capabilities != capabilities
        writableDatabase.insertWithOnConflict(
            TABLE_PRESENCE,
            null,
            ContentValues().apply {
                put(COLUMN_MID, mid)
                put(COLUMN_STATUS, status)
                put(COLUMN_CAPABILITIES, capabilities)
                put(COLUMN_UPDATED_AT, now)
                put(COLUMN_EXPIRES_AT, now + MiMessagePresencePolicy.CACHE_TTL_MS)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return changed
    }

    /** Return expired MIDs without deleting them so a service restart can resume revalidation. */
    fun expireDue(now: Long = System.currentTimeMillis()): List<String> = readableDatabase.query(
        TABLE_PRESENCE,
        arrayOf(COLUMN_MID),
        "$COLUMN_EXPIRES_AT<=?",
        arrayOf(now.toString()),
        null,
        null,
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    fun deletePresence(mid: String) {
        writableDatabase.delete(TABLE_PRESENCE, "$COLUMN_MID=?", arrayOf(mid))
    }

    private fun readPresence(mid: String): MiMessagePresenceCacheRecord? = readableDatabase.query(
        TABLE_PRESENCE,
        arrayOf(COLUMN_STATUS, COLUMN_CAPABILITIES, COLUMN_UPDATED_AT, COLUMN_EXPIRES_AT),
        "$COLUMN_MID=?",
        arrayOf(mid),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toPresenceRecord(mid) else null }

    private fun Cursor.toPresenceRecord(mid: String) = MiMessagePresenceCacheRecord(
        mid = mid,
        status = getString(getColumnIndexOrThrow(COLUMN_STATUS)),
        capabilities = getLong(getColumnIndexOrThrow(COLUMN_CAPABILITIES)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
        expiresAt = getLong(getColumnIndexOrThrow(COLUMN_EXPIRES_AT)),
    )

    companion object {
        const val DATABASE_NAME = "mi_message_presence.db"
        const val DATABASE_VERSION = 1
        private const val TABLE_CONTACTS = "contact_mids"
        private const val TABLE_PRESENCE = "presence_cache"
        private const val COLUMN_PHONE_KEY = "phone_key"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_MID = "mid"
        private const val COLUMN_RESOLVED_AT = "resolved_at"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_CAPABILITIES = "capabilities"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_EXPIRES_AT = "expires_at"
    }
}
