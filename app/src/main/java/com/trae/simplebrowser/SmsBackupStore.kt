package com.trae.simplebrowser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.trae.simplebrowser.core.SmsBackupLogic
import com.trae.simplebrowser.core.SmsMsg

/**
 * 12368 短信上存队列与已传记录。
 * pending：收到但尚未上存成功的短信（断网/无令牌时暂存，永不丢弃）。
 * sent：已成功上存的内容指纹，按服务地址隔离——换地址（或测试指向本地服务）不会
 * 污染正式地址的记录，正式地址首次部署后回扫可完整补传。
 */
class SmsBackupStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class PendingRow(val id: Long, val msg: SmsMsg)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pending(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL," +
                "tries INTEGER DEFAULT 0, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE sent(" +
                "hash TEXT NOT NULL, server TEXT NOT NULL, sent_at_ms INTEGER NOT NULL," +
                "PRIMARY KEY(hash, server))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 首版，无历史版本需要迁移
    }

    @Synchronized
    fun enqueue(list: List<SmsMsg>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (m in list) {
                val cv = ContentValues().apply {
                    put("sender", m.sender)
                    put("body", m.body)
                    put("sent_at", m.sentAt)
                    put("created_at", now)
                }
                db.insertWithOnConflict("pending", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    @Synchronized
    fun pendingSnapshot(limit: Int): List<PendingRow> {
        val out = ArrayList<PendingRow>()
        readableDatabase.rawQuery(
            "SELECT id, sender, body, sent_at FROM pending ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(PendingRow(
                    id = c.getLong(0),
                    msg = SmsMsg(c.getString(1), c.getString(2), c.getLong(3))
                ))
            }
        }
        return out
    }

    @Synchronized
    fun bumpTries(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE pending SET tries = tries + 1 WHERE id IN (${ids.joinToString(",")})"
        )
    }

    /** 批次上存成功：记 sent + 删 pending，事务保证不出现"记了没删/删了没记"。 */
    @Synchronized
    fun markSentAndRemovePending(rows: List<PendingRow>, server: String) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                recordSentLocked(db, listOf(SmsBackupLogic.contentHash(r.msg.sender, r.msg.body, r.msg.sentAt)), server)
                db.delete("pending", "id=?", arrayOf(r.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 回扫批次的已传记录（只记 sent，不涉及 pending）。 */
    @Synchronized
    fun recordSent(hashes: List<String>, server: String) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            recordSentLocked(db, hashes, server)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun recordSentLocked(db: SQLiteDatabase, hashes: List<String>, server: String) {
        for (h in hashes) {
            if (h.isBlank()) continue
            val cv = ContentValues().apply {
                put("hash", h)
                put("server", server)
                put("sent_at_ms", System.currentTimeMillis())
            }
            db.insertWithOnConflict("sent", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    @Synchronized
    fun sentHashes(server: String): MutableSet<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT hash FROM sent WHERE server=?", arrayOf(server)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    @Synchronized
    fun sentCount(server: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sent WHERE server=?", arrayOf(server)
        ).use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    companion object {
        private const val DB_NAME = "sms_backup.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: SmsBackupStore? = null

        fun get(context: Context): SmsBackupStore =
            instance ?: synchronized(this) {
                instance ?: SmsBackupStore(context.applicationContext).also { instance = it }
            }
    }
}
