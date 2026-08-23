package com.anzhi.os

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 审计日志——"安知你今天做了什么？"
 *
 * 每笔操作记在本地 SQLite，可以回看。
 * 信任安知，但要可查。
 */
class AnzhiAuditLog(context: Context) : SQLiteOpenHelper(
    context, "anzhi_audit.db", null, 1
) {
    companion object {
        private const val TAG = "AnzhiAuditLog"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                action_type TEXT NOT NULL,
                payload TEXT,
                result TEXT,
                app_in_foreground TEXT
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    /** 记一条 */
    fun log(actionType: String, payload: String = "", result: String = "") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("action_type", actionType)
            put("payload", payload)
            put("result", result)
        }
        db.insert("audit_log", null, values)
    }

    /** 查最近 N 条 */
    fun getRecent(limit: Int = 50): List<Map<String, String>> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val result = mutableListOf<Map<String, String>>()
        while (cursor.moveToNext()) {
            result.add(mapOf(
                "time" to cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")).toString(),
                "action" to cursor.getString(cursor.getColumnIndexOrThrow("action_type")),
                "payload" to cursor.getString(cursor.getColumnIndexOrThrow("payload")),
                "result" to cursor.getString(cursor.getColumnIndexOrThrow("result"))
            ))
        }
        cursor.close()
        return result
    }
}
