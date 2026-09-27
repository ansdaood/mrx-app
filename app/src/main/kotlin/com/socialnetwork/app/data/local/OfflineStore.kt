package com.socialnetwork.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Small private SQLite store for offline-first operation. */
class OfflineStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "mrx_offline.db"
        private const val DB_VERSION = 5
        private const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DB_BYTES = 24L * 1024L * 1024L
        private const val MAX_CACHE_ENTRIES = 200
        private const val MAX_CACHE_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_PENDING_BODY_BYTES = 512 * 1024
        private const val MAX_PENDING_ENTRIES = 500

        private val OWNER_ID_REGEX = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        private fun isValidOwner(ownerUserId: String?): Boolean =
            ownerUserId?.let(OWNER_ID_REGEX::matches) == true

        private fun isValidOperationId(operationId: String?): Boolean =
            operationId?.let { it.length in 1..80 && OPERATION_ID_REGEX.matches(it) } == true

        private val OPERATION_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,80}$")

        private fun key(url: String, ownerUserId: String): String = try {
            val md = MessageDigest.getInstance("SHA-256")
            val d = md.digest((ownerUserId + "\n" + url).toByteArray(StandardCharsets.UTF_8))
            val b = StringBuilder(d.size * 2)
            for (x in d) b.append(String.format(java.util.Locale.US, "%02x", x.toInt() and 0xff))
            b.toString()
        } catch (_: Exception) {
            (ownerUserId + "\n" + url).hashCode().toString(16)
        }
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA busy_timeout=2500")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (k TEXT PRIMARY KEY, url TEXT NOT NULL, body TEXT NOT NULL, owner_user_id TEXT, saved_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX cache_saved_at ON cache(saved_at)")
        db.execSQL("CREATE INDEX cache_expires_at ON cache(expires_at)")
        db.execSQL("CREATE INDEX cache_owner_expires_at ON cache(owner_user_id, expires_at)")
        db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, method TEXT NOT NULL, url TEXT NOT NULL, body TEXT, owner_user_id TEXT, operation_id TEXT, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX pending_created_at ON pending(created_at, id)")
        db.execSQL("CREATE TABLE sync_state (resource TEXT PRIMARY KEY, cursor TEXT, synced_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX sync_state_dirty ON sync_state(dirty, synced_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cache ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pending ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS cache_expires_at ON cache(expires_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (resource TEXT PRIMARY KEY, cursor TEXT, synced_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS sync_state_dirty ON sync_state(dirty, synced_at)")
            db.execSQL("UPDATE cache SET expires_at=saved_at+600000 WHERE expires_at=0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE pending ADD COLUMN owner_user_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS pending_owner_created_at ON pending(owner_user_id, created_at, id)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE cache ADD COLUMN owner_user_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS cache_owner_expires_at ON cache(owner_user_id, expires_at)")
            db.delete("cache", null, null)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE pending ADD COLUMN operation_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS pending_owner_operation ON pending(owner_user_id, operation_id, created_at, id)")
        }
    }

    fun getCached(url: String?, ownerUserId: String?): String? {
        if (url.isNullOrEmpty() || !isValidOwner(ownerUserId)) return null
        val owner = ownerUserId ?: return null
        val cacheKey = key(url, owner)
        return try {
            readableDatabase.query(
                "cache", arrayOf("body", "expires_at"), "k=? AND owner_user_id=?",
                arrayOf(cacheKey, owner), null, null, null, "1"
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                val expires = c.getLong(1)
                if (expires >= System.currentTimeMillis()) c.getString(0)
                else {
                    writableDatabase.delete("cache", "k=? AND owner_user_id=?", arrayOf(cacheKey, owner))
                    null
                }
            }
        } catch (_: Exception) { null }
    }

    fun putCached(url: String?, body: String?, ownerUserId: String?) {
        if (url.isNullOrEmpty() || body == null || !isValidOwner(ownerUserId) || body.toByteArray(StandardCharsets.UTF_8).size > MAX_CACHE_BODY_BYTES) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val owner = ownerUserId ?: return
        val v = ContentValues().apply {
            put("k", key(url, owner))
            put("url", url)
            put("body", body)
            put("owner_user_id", owner)
            put("saved_at", now)
            put("expires_at", now + DEFAULT_CACHE_TTL_MS)
        }
        db.insertWithOnConflict("cache", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        try {
            db.delete("cache", "expires_at<?", arrayOf(now.toString()))
            var count = 0
            db.rawQuery("SELECT COUNT(*) FROM cache", null).use { if (it.moveToFirst()) count = it.getInt(0) }
            if (count > MAX_CACHE_ENTRIES) db.execSQL("DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY saved_at ASC LIMIT ?)", arrayOf(count - MAX_CACHE_ENTRIES))
        } catch (_: Exception) { }
    }

    fun clearCache() {
        runCatching { writableDatabase.delete("cache", null, null) }
    }

    fun markSync(resource: String?, cursor: String?, dirty: Boolean) {
        if (resource.isNullOrEmpty()) return
        val v = ContentValues().apply {
            put("resource", resource)
            put("cursor", cursor)
            put("synced_at", System.currentTimeMillis())
            put("dirty", if (dirty) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("sync_state", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun enqueue(method: String?, url: String?, body: String?, ownerUserId: String?, operationId: String?): Long {
        if (method.isNullOrEmpty() || url.isNullOrEmpty() || ownerUserId.isNullOrEmpty() || operationId.isNullOrEmpty() || operationId.length > 80) return -1L
        if (!isValidOwner(ownerUserId) || !isValidOperationId(operationId) ||
            body?.toByteArray(StandardCharsets.UTF_8)?.size?.let { it > MAX_PENDING_BODY_BYTES } == true) return -1L
        val db = writableDatabase
        // Never evict an existing user mutation when the offline queue is full.
        db.rawQuery("SELECT COUNT(*) FROM pending", null).use { if (it.moveToFirst() && it.getInt(0) >= MAX_PENDING_ENTRIES) return -1L }
        val v = ContentValues().apply {
            put("method", method)
            put("url", url)
            body?.let { put("body", it) }
            put("owner_user_id", ownerUserId)
            put("operation_id", operationId)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
        }
        return db.insert("pending", null, v)
    }

    fun enqueue(method: String?, url: String?, body: String?, ownerUserId: String?): Long =
        enqueue(method, url, body, ownerUserId, java.util.UUID.randomUUID().toString())

    fun pendingOperations(limit: Int, ownerUserId: String?): List<PendingOperation> {
        val out = mutableListOf<PendingOperation>()
        if (ownerUserId.isNullOrEmpty()) return out
        readableDatabase.query(
            "pending",
            arrayOf("id", "method", "url", "body", "owner_user_id", "operation_id"),
            "owner_user_id=?",
            arrayOf(ownerUserId),
            null,
            null,
            "created_at ASC, id ASC",
            maxOf(1, minOf(limit, 100)).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += cursor.toPendingOperation()
            }
        }
        return out
    }

    fun unboundPendingOperations(limit: Int): List<PendingOperation> {
        val out = mutableListOf<PendingOperation>()
        readableDatabase.query(
            "pending",
            arrayOf("id", "method", "url", "body", "owner_user_id", "operation_id"),
            "owner_user_id IS NULL OR owner_user_id=''",
            null,
            null,
            null,
            "created_at ASC, id ASC",
            maxOf(1, minOf(limit, 100)).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += cursor.toPendingOperation()
            }
        }
        return out
    }

    fun bindPendingToUser(id: Long, ownerUserId: String?) {
        if (id <= 0 || !isValidOwner(ownerUserId)) return
        writableDatabase.execSQL("UPDATE pending SET owner_user_id=? WHERE id=? AND (owner_user_id IS NULL OR owner_user_id='')", arrayOf(ownerUserId, id))
    }

    fun deletePending(id: Long) {
        if (id <= 0L) return
        writableDatabase.delete("pending", "id=?", arrayOf(id.toString()))
    }

    fun pendingAttempts(id: Long): Int = try {
        readableDatabase.query("pending", arrayOf("attempts"), "id=?", arrayOf(id.toString()), null, null, null, "1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (_: Exception) { 0 }

    fun incrementPendingAttempts(id: Long): Int {
        writableDatabase.execSQL("UPDATE pending SET attempts=attempts+1 WHERE id=?", arrayOf(id))
        return pendingAttempts(id)
    }

    fun compact() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.delete("cache", "expires_at<?", arrayOf(now.toString()))
        try {
            db.rawQuery("SELECT COUNT(*) FROM cache", null).use { c ->
                if (c.moveToFirst()) {
                    val excess = c.getInt(0) - MAX_CACHE_ENTRIES
                    if (excess > 0) db.execSQL("DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY saved_at ASC LIMIT ?)", arrayOf(excess))
                }
            }
            db.rawQuery("PRAGMA page_count", null).use { c ->
                db.rawQuery("PRAGMA page_size", null).use { p ->
                    if (c.moveToFirst() && p.moveToFirst() && c.getLong(0) * p.getLong(0) > MAX_DB_BYTES) db.execSQL("PRAGMA incremental_vacuum")
                }
            }
        } catch (_: Exception) { }
    }

    fun hasPending(): Boolean = try { readableDatabase.rawQuery("SELECT 1 FROM pending LIMIT 1", null).use { it.moveToFirst() } } catch (_: Exception) { false }

    fun hasRetryablePending(maxAttempts: Int, ownerUserId: String?): Boolean {
        if (ownerUserId.isNullOrEmpty()) return false
        return try { readableDatabase.rawQuery("SELECT 1 FROM pending WHERE owner_user_id=? AND attempts<? LIMIT 1", arrayOf(ownerUserId, maxAttempts.toString())).use { it.moveToFirst() } } catch (_: Exception) { false }
    }

    private fun Cursor.toPendingOperation(): PendingOperation = PendingOperation(
        id = getLong(0),
        method = getString(1),
        url = getString(2),
        body = getStringOrNull(3),
        ownerUserId = getString(4).orEmpty(),
        operationId = getString(5).orEmpty()
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    data class PendingOperation(
        val id: Long,
        val method: String,
        val url: String,
        val body: String?,
        val ownerUserId: String,
        val operationId: String
    )
}
