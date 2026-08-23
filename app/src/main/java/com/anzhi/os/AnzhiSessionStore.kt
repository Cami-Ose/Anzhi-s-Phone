package com.anzhi.os

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 安知会话持久化存储。
 *
 * 基于 AAOSP LlmSessionStore 的设计模式：
 *   - sessions 表：会话元数据（id, uid, title, created_at, updated_at）
 *   - messages 表：每条对话消息（session_id, role, content, timestamp）
 *   - tool_calls 表：工具调用审计（session_id, tool_name, args, result, latency_ms）
 *
 * 数据库路径：/data/data/com.anzhi.os/databases/anzhi_sessions.db
 */
// 存储的消息记录（顶层类，避免 K2 跨文件引用 companion 嵌套类的 bug）
data class StoredMessage(
    val id: Long,
    val sessionId: String,
    val role: String,        // "user" | "assistant" | "tool"
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResultJson: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class AnzhiSessionStore(context: Context) : SQLiteOpenHelper(
    context, "anzhi_sessions.db", null, 1
) {
    companion object {
        private const val TAG = "AnzhiSessionStore"

        // ── 数据类 ──

        data class SessionSummary(
            val sessionId: String,
            val title: String,
            val createdAt: Long,
            val updatedAt: Long,
            val messageCount: Int
        )

        data class ToolStats(
            val toolName: String,
            val callCount: Int,
            val errorCount: Int,
            val totalLatencyMs: Long
        ) {
            fun successRate(): Float =
                if (callCount > 0) (callCount - errorCount).toFloat() / callCount else 1f

            fun avgLatencyMs(): Long =
                if (callCount > 0) totalLatencyMs / callCount else 0
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                uid INTEGER NOT NULL,
                title TEXT DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_name TEXT,
                tool_args_json TEXT,
                tool_result_json TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
        """)
        db.execSQL("""
            CREATE INDEX idx_messages_session ON messages(session_id, timestamp)
        """)
        db.execSQL("""
            CREATE TABLE tool_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                package_name TEXT NOT NULL,
                args_json TEXT,
                result_json TEXT,
                error TEXT,
                latency_ms INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 初始版本，暂无升级逻辑
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ─────────────────────────────────────
    // 会话操作
    // ─────────────────────────────────────

    /**
     * 创建新会话。如果 sessionId 为 null 则自动生成。
     * @return 会话 ID
     */
    fun createSession(sessionId: String? = null, uid: Int = 0): String {
        val id = sessionId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val values = ContentValues().apply {
            put("session_id", id)
            put("uid", uid)
            put("title", "")
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        Log.d(TAG, "创建会话: $id (uid=$uid)")
        return id
    }

    /** 更新会话标题（取用户第一条消息的前 50 个字符） */
    fun updateSessionTitle(sessionId: String, title: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title.take(50))
            put("updated_at", System.currentTimeMillis())
        }
        db.update("sessions", values, "session_id = ?", arrayOf(sessionId))
    }

    /** 列出当前 uid 的会话（按更新时间倒序） */
    fun listSessions(uid: Int, limit: Int): List<SessionSummary> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT s.session_id, s.title, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.session_id) AS msg_count
            FROM sessions s
            WHERE s.uid = ?
            ORDER BY s.updated_at DESC
            LIMIT ?
        """, arrayOf(uid.toString(), limit.toString()))
        return cursor.use { c ->
            val result = mutableListOf<SessionSummary>()
            while (c.moveToNext()) {
                result.add(SessionSummary(
                    sessionId = c.getString(0),
                    title = c.getString(1).ifBlank { "新对话" },
                    createdAt = c.getLong(2),
                    updatedAt = c.getLong(3),
                    messageCount = c.getInt(4)
                ))
            }
            result
        }
    }

    /** 删除一个会话及其所有消息 */
    fun deleteSession(sessionId: String, uid: Int) {
        val db = writableDatabase
        db.delete("sessions", "session_id = ? AND uid = ?", arrayOf(sessionId, uid.toString()))
        Log.d(TAG, "删除会话: $sessionId")
    }

    // ─────────────────────────────────────
    // 消息操作
    // ─────────────────────────────────────

    /** 添加一条消息 */
    fun addMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgsJson: String? = null,
        toolResultJson: String? = null
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("content", content)
            if (toolName != null) put("tool_name", toolName)
            if (toolArgsJson != null) put("tool_args_json", toolArgsJson)
            if (toolResultJson != null) put("tool_result_json", toolResultJson)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("messages", null, values)

        // 同时更新会话的 updated_at
        val sessionValues = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
        }
        db.update("sessions", sessionValues, "session_id = ?", arrayOf(sessionId))
    }

    /** 添加工具消息（快捷方法） */
    fun addToolMessage(
        sessionId: String,
        toolName: String,
        argsJson: String,
        resultJson: String
    ) {
        addMessage(sessionId, "tool", "调用 $toolName",
            toolName = toolName,
            toolArgsJson = argsJson,
            toolResultJson = resultJson
        )
    }

    /** 获取会话历史消息 */
    fun getSessionHistory(sessionId: String, limit: Int = 100): List<StoredMessage> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT id, session_id, role, content, tool_name, tool_args_json, tool_result_json, timestamp
            FROM messages
            WHERE session_id = ?
            ORDER BY timestamp ASC
            LIMIT ?
        """, arrayOf(sessionId, limit.toString()))
        return cursor.use { c ->
            val result = mutableListOf<StoredMessage>()
            while (c.moveToNext()) {
                result.add(cursorToMessage(c))
            }
            result
        }
    }

    /** 将消息历史转为 JSON 数组（给 LLM 看） */
    fun getSessionHistoryJson(sessionId: String, limit: Int = 100): JSONArray {
        val arr = JSONArray()
        for (m in getSessionHistory(sessionId, limit)) {
            val obj = JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            }
            if (m.toolName != null) {
                obj.put("toolName", m.toolName)
                obj.put("toolArgs", m.toolArgsJson ?: "")
                obj.put("toolResult", m.toolResultJson ?: "")
            }
            arr.put(obj)
        }
        return arr
    }

    /**
     * 获取今天所有会话的聊天消息（供日记模块使用）。
     *
     * 日记 prompt 规则（BUILD.md Step 13）：
     *   - 只看 Cami 和安知的对话（role = "user" | "assistant"）
     *   - 忽略 tool 消息、系统推送、状态上报
     *
     * @return 按时间升序排列的今日消息列表
     */
    fun getTodayChatMessages(): List<StoredMessage> {
        val db = readableDatabase
        val startOfToday = startOfTodayMs()
        val cursor = db.rawQuery("""
            SELECT id, session_id, role, content, tool_name, tool_args_json, tool_result_json, timestamp
            FROM messages
            WHERE timestamp >= ? AND role IN ('user', 'assistant')
            ORDER BY timestamp ASC
        """, arrayOf(startOfToday.toString()))
        return cursor.use { c ->
            val result = mutableListOf<StoredMessage>()
            while (c.moveToNext()) {
                result.add(cursorToMessage(c))
            }
            result
        }
    }

    /** 获取今天 00:00:00.000 的 Unix 毫秒时间戳 */
    private fun startOfTodayMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ─────────────────────────────────────
    // 工具调用统计
    // ─────────────────────────────────────

    /** 记录一次成功的工具调用 */
    fun recordToolSuccess(toolName: String, packageName: String, latencyMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("session_id", "_stats_")
            put("tool_name", toolName)
            put("package_name", packageName)
            put("latency_ms", latencyMs)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("tool_calls", null, values)
    }

    /** 记录一次失败的工具调用 */
    fun recordToolError(toolName: String, packageName: String, error: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("session_id", "_stats_")
            put("tool_name", toolName)
            put("package_name", packageName)
            put("error", error)
            put("latency_ms", -1)
            put("timestamp", System.currentTimeMillis())
        }
        db.insert("tool_calls", null, values)
    }

    /** 获取某个工具的统计信息 */
    fun getToolStats(indexKey: String): ToolStats? {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT tool_name, COUNT(*) AS total,
                   SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) AS errors,
                   SUM(CASE WHEN latency_ms > 0 THEN latency_ms ELSE 0 END) AS total_latency
            FROM tool_calls
            WHERE tool_name = ? AND session_id = '_stats_'
            GROUP BY tool_name
        """, arrayOf(indexKey))
        return cursor.use { c ->
            if (c.moveToFirst()) {
                ToolStats(
                    toolName = c.getString(0),
                    callCount = c.getInt(1),
                    errorCount = c.getInt(2),
                    totalLatencyMs = c.getLong(3)
                )
            } else null
        }
    }

    // ─────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────

    private fun cursorToMessage(c: Cursor): StoredMessage = StoredMessage(
        id = c.getLong(0),
        sessionId = c.getString(1),
        role = c.getString(2),
        content = c.getString(3),
        toolName = c.getStringOrNull(4),
        toolArgsJson = c.getStringOrNull(5),
        toolResultJson = c.getStringOrNull(6),
        timestamp = c.getLong(7)
    )

    private fun Cursor.getStringOrNull(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }
}
