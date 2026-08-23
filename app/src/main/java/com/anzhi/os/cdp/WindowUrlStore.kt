package com.anzhi.os.cdp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Gemini 窗口 URL 持久化存储。
 *
 * 记录三个 CDP 窗口各自的 Gemini URL，保证：
 *   - 首次手动开三个 Gemini 对话 → OS 记 URL → 后续自动续期
 *   - Cookie 过期 / 对话消失时 → 自动 window.open 新建 → 更新 URL
 *   - 手机重启后从 SQLite 恢复 URL，不用重新手动开
 *
 * 数据库：window_urls.db（BUILD.md §3.5）
 * 表：gemini_windows(window_type TEXT PK, url TEXT, created_at INTEGER)
 */
class WindowUrlStore(context: Context) : SQLiteOpenHelper(
    context, "window_urls.db", null, 1
) {
    companion object {
        private const val TAG = "WindowUrlStore"

        /** 窗口类型常量 */
        const val WINDOW_WAKE = "wake"
        const val WINDOW_CHAT = "chat"
        const val WINDOW_DIARY = "diary"

        /** 所有窗口类型列表 */
        val ALL_WINDOWS = listOf(WINDOW_WAKE, WINDOW_CHAT, WINDOW_DIARY)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE gemini_windows (
                window_type TEXT PRIMARY KEY,
                url         TEXT NOT NULL,
                created_at  INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 初始版本，暂无升级逻辑
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL 模式：读写不互斥（陷阱 18）
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA busy_timeout=5000")
    }

    // ─────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────

    /** 存入或更新一个窗口的 URL */
    fun putUrl(windowType: String, url: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("window_type", windowType)
            put("url", url)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "gemini_windows", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.d(TAG, "窗口 URL 已存储: $windowType → ${url.take(80)}...")
    }

    /** 读取窗口 URL，无记录返回 null */
    fun getUrl(windowType: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT url FROM gemini_windows WHERE window_type = ?",
            arrayOf(windowType)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** 检查是否有该窗口的记录 */
    fun hasUrl(windowType: String): Boolean = getUrl(windowType) != null

    /** 删除窗口 URL */
    fun removeUrl(windowType: String) {
        writableDatabase.delete(
            "gemini_windows", "window_type = ?", arrayOf(windowType)
        )
        Log.d(TAG, "窗口 URL 已删除: $windowType")
    }

    /** 获取所有窗口 URL 的映射 */
    fun getAllUrls(): Map<String, String> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT window_type, url FROM gemini_windows", null
        )
        return cursor.use { c ->
            val map = mutableMapOf<String, String>()
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getString(1)
            }
            map
        }
    }

    /** 清空所有窗口 URL */
    fun clearAll() {
        writableDatabase.delete("gemini_windows", null, null)
        Log.d(TAG, "所有窗口 URL 已清空")
    }
}
