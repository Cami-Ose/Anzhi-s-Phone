package com.anzhi.os

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 路径缓存 —— 记下操作路径，下次 ¥0 直接执行。
 *
 * 缓存 key 格式："ui:com.tencent.mm:设免打扰"
 * 缓存 val：JSON 数组 [{type:"tap",hint:"聊天tab"}, ...]
 *
 * 流程：
 *   DeepSeek 输出 key → lookup() 查缓存
 *     命中 → 返回 List<PathStep> → 交 Step 9 执行（¥0）
 *     未命中 → 返回 null → 上游调 UI 树搜索 → 成功后 store()
 *     失败 → markPathFailed() → 同 App 连续 3 次 → 失败账本
 *
 * 失败账本：
 *   同一 App 连续失败 3 次 → 不再盲目重试 → 等主聊天时安知吐槽
 *   任何时候该 App 有一条路径成功 → 重置计数器
 *
 * 数据库：path_cache.db（BUILD.md §3.3）
 * 表：
 *   path_cache(key TEXT PK, steps TEXT, app TEXT, operation TEXT,
 *              created_at, last_used, hit_count, fail_count)
 *   failure_ledger(app TEXT PK, consecutive_failures, last_failure_at, acknowledged)
 */
class PathCache(context: Context) : SQLiteOpenHelper(
    context, "path_cache.db", null, DATABASE_VERSION
) {
    companion object {
        private const val TAG = "PathCache"
        private const val DATABASE_VERSION = 1

        /** 失败账本阈值：同一 App 连续失败 3 次即入账 */
        const val FAILURE_LEDGER_THRESHOLD = 3

        /** 缓存 key 前缀 */
        const val KEY_PREFIX = "ui"

        // ── 数据类 ──

        /** 单步操作 */
        data class PathStep(
            val type: String,   // "tap" | "find_and_tap" | "swipe" | "type" | "long_press" | "key_event"
            val hint: String    // 人类可读描述，如 "聊天tab"、"联系人"、"设置"
        ) {
            fun toJson(): JSONObject = JSONObject().apply {
                put("type", type)
                put("hint", hint)
            }

            companion object {
                fun fromJson(obj: JSONObject): PathStep = PathStep(
                    type = obj.optString("type", "tap"),
                    hint = obj.optString("hint", "")
                )
            }
        }

        /** 失败账本条目 */
        data class FailureLedgerEntry(
            val app: String,
            val consecutiveFailures: Int,
            val lastFailureAt: Long,
            val acknowledged: Boolean
        )

        // ── 工具方法 ──

        /** 拼缓存 key：ui:包名:操作描述 */
        fun buildKey(packageName: String, operation: String): String =
            "$KEY_PREFIX:$packageName:$operation"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE path_cache (
                key         TEXT PRIMARY KEY,
                steps       TEXT NOT NULL,
                app         TEXT NOT NULL,
                operation   TEXT NOT NULL,
                created_at  INTEGER NOT NULL,
                last_used   INTEGER NOT NULL,
                hit_count   INTEGER DEFAULT 1,
                fail_count  INTEGER DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE failure_ledger (
                app                   TEXT PRIMARY KEY,
                consecutive_failures  INTEGER DEFAULT 0,
                last_failure_at       INTEGER NOT NULL,
                acknowledged          INTEGER DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 初始版本；未来版本按 BUILD.md 陷阱 22 累加 ALTER TABLE
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL 模式：读写不互斥（陷阱 18）
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA busy_timeout=5000")
    }

    // ─────────────────────────────────────
    // 缓存读写
    // ─────────────────────────────────────

    /**
     * 带失败账本守卫的缓存查找。
     *
     * 先调 isAppInFailureLedger(app) 检查——若该 App 已连续失败 ≥3 次，
     * 直接返回 null（不再盲目重试），等主聊天时安知吐槽后 resetAppFailures()。
     * 若未入账则正常 lookup()。
     *
     * @param key  缓存 key（"ui:包名:操作"）
     * @param app  Android 包名（用于失败账本检查）
     * @return 命中返回步骤列表，未命中/入账返回 null
     */
    fun lookupSafe(key: String, app: String): List<PathStep>? {
        if (isAppInFailureLedger(app)) {
            Log.d(TAG, "失败账本拦截, 跳过缓存: $app")
            return null
        }
        return lookup(key)
    }

    /**
     * 查缓存。
     * @return 命中返回步骤列表，未命中返回 null
     */
    fun lookup(key: String): List<PathStep>? {
        // writableDatabase：lookup 内有 UPDATE 操作（last_used + hit_count），不能走只读连接
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT steps FROM path_cache WHERE key = ?", arrayOf(key)
        )
        return cursor.use { c ->
            if (!c.moveToFirst()) {
                Log.d(TAG, "缓存未命中: $key")
                return null
            }
            val stepsJson = c.getString(0)
            Log.d(TAG, "缓存命中: $key")

            // 更新 last_used + hit_count
            db.execSQL(
                "UPDATE path_cache SET last_used = ?, hit_count = hit_count + 1 WHERE key = ?",
                arrayOf(System.currentTimeMillis(), key)
            )

            parseSteps(stepsJson)
        }
    }

    /**
     * 存入成功路径。命中后写入，步骤永久有效。
     */
    fun store(key: String, app: String, operation: String, steps: List<PathStep>) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val stepsJson = JSONArray().apply {
            steps.forEach { put(it.toJson()) }
        }.toString()

        val values = ContentValues().apply {
            put("key", key)
            put("steps", stepsJson)
            put("app", app)
            put("operation", operation)
            put("created_at", now)
            put("last_used", now)
            put("hit_count", 1)
            put("fail_count", 0)
        }
        db.insertWithOnConflict(
            "path_cache", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.d(TAG, "缓存已存储: $key (${steps.size} 步)")

        // 该 App 任一路径成功 → 重置失败账本
        resetAppFailures(app)
    }

    /**
     * 路径执行失败 → 递增 fail_count。
     * @return 递增后的 fail_count
     */
    fun markPathFailed(key: String): Int {
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT app, fail_count FROM path_cache WHERE key = ?", arrayOf(key)
        )
        val (app, oldCount) = cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getInt(1)
            else null to 0
        }
        if (app == null) {
            Log.w(TAG, "markPathFailed: key 不存在: $key")
            return 0
        }

        val newCount = oldCount + 1
        db.execSQL(
            "UPDATE path_cache SET fail_count = ? WHERE key = ?",
            arrayOf(newCount, key)
        )
        Log.d(TAG, "路径失败: $key (fail_count=$newCount)")

        // 同步推进失败账本
        recordAppFailure(app)

        // 连续失败 ≥ 阈值 → 自动删除坏路径（不保留半成功）
        if (newCount >= FAILURE_LEDGER_THRESHOLD) {
            invalidatePath(key)
            Log.w(TAG, "路径连续失败 $newCount 次，自动失效: $key")
        }

        return newCount
    }

    /**
     * 路径彻底失效 → 删除缓存条目。
     * 下次走全路径重做（BUILD.md: "失败→全路径重做"）
     */
    fun invalidatePath(key: String) {
        writableDatabase.delete("path_cache", "key = ?", arrayOf(key))
        Log.d(TAG, "路径已失效并删除: $key")
    }

    /**
     * 获取某条缓存当前的 fail_count（不修改）
     */
    fun getFailCount(key: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT fail_count FROM path_cache WHERE key = ?", arrayOf(key)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ─────────────────────────────────────
    // 失败账本
    // ─────────────────────────────────────

    /**
     * 记录 App 一次失败 → 递增 consecutive_failures。
     * 内部方法，由 markPathFailed 自动调用。
     */
    private fun recordAppFailure(app: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        // 尝试 UPDATE
        db.execSQL("""
            INSERT INTO failure_ledger (app, consecutive_failures, last_failure_at, acknowledged)
            VALUES (?, 1, ?, 0)
            ON CONFLICT(app) DO UPDATE SET
                consecutive_failures = consecutive_failures + 1,
                last_failure_at = ?,
                acknowledged = 0
        """, arrayOf(app, now, now))

        val count = getAppFailCount(app)
        if (count >= FAILURE_LEDGER_THRESHOLD) {
            Log.w(TAG, "⚠️ App 进入失败账本: $app (连续失败 $count 次)")
        }
    }

    /** 重置某 App 的失败计数器（任一路径成功后调用） */
    fun resetAppFailures(app: String) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE failure_ledger SET consecutive_failures = 0 WHERE app = ?",
            arrayOf(app)
        )
    }

    /** 获取某 App 当前连续失败次数 */
    fun getAppFailCount(app: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT consecutive_failures FROM failure_ledger WHERE app = ?", arrayOf(app)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 检查 App 是否在失败账本中（≥3 次连续失败） */
    fun isAppInFailureLedger(app: String): Boolean =
        getAppFailCount(app) >= FAILURE_LEDGER_THRESHOLD

    /**
     * 获取所有在失败账本中的 App（连续失败 ≥3 次且未被 acknowledged）。
     * 主聊天窗口打开时调用——安知据此向 Cami 吐槽。
     */
    fun getFailureLedgerEntries(): List<FailureLedgerEntry> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT app, consecutive_failures, last_failure_at, acknowledged
            FROM failure_ledger
            WHERE consecutive_failures >= ?
            ORDER BY consecutive_failures DESC
        """, arrayOf(FAILURE_LEDGER_THRESHOLD.toString()))
        return cursor.use { c ->
            val result = mutableListOf<FailureLedgerEntry>()
            while (c.moveToNext()) {
                result.add(FailureLedgerEntry(
                    app = c.getString(0),
                    consecutiveFailures = c.getInt(1),
                    lastFailureAt = c.getLong(2),
                    acknowledged = c.getInt(3) != 0
                ))
            }
            result
        }
    }

    /** 标记失败账本条为已告知（安知已向 Cami 吐槽过） */
    fun acknowledgeFailure(app: String) {
        writableDatabase.execSQL(
            "UPDATE failure_ledger SET acknowledged = 1 WHERE app = ?",
            arrayOf(app)
        )
        Log.d(TAG, "失败账本已告知: $app")
    }

    /** 清空某 App 的失败账本（Cami/安知手动解决后） */
    fun clearFailureLedger(app: String) {
        writableDatabase.delete("failure_ledger", "app = ?", arrayOf(app))
        Log.d(TAG, "失败账本已清除: $app")
    }

    // ─────────────────────────────────────
    // 查询 & 维护
    // ─────────────────────────────────────

    /** 获取某 App 的所有缓存 key */
    fun getAppKeys(app: String): List<String> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT key FROM path_cache WHERE app = ? ORDER BY last_used DESC",
            arrayOf(app)
        )
        return cursor.use { c ->
            val result = mutableListOf<String>()
            while (c.moveToNext()) result.add(c.getString(0))
            result
        }
    }

    /** 缓存总数 */
    fun count(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM path_cache", null)
        return cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 清空所有缓存（调试用） */
    fun clearAll() {
        writableDatabase.apply {
            delete("path_cache", null, null)
            delete("failure_ledger", null, null)
        }
        Log.d(TAG, "所有路径缓存已清空")
    }

    // ─────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────

    private fun parseSteps(stepsJson: String): List<PathStep> {
        val arr = JSONArray(stepsJson)
        val steps = mutableListOf<PathStep>()
        for (i in 0 until arr.length()) {
            steps.add(PathStep.fromJson(arr.getJSONObject(i)))
        }
        return steps
    }
}
