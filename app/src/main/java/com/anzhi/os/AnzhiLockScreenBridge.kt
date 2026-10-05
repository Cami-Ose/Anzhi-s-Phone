package com.anzhi.os

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 安知锁屏文字桥接 — KeyguardIndicationController 数据层。
 *
 * BUILD.md Step 12 + README §一（SystemUI定制）：
 *   锁屏文字通过 KeyguardIndicationController 公开 API 注入（不 hack SystemUI）：
 *     - setPersistentUnlockMessage("Cami，下午出门带伞") → 一直显示直到清掉
 *     - showTransientIndication(...) → 3.5 秒短暂提醒
 *     - computePowerIndication() → 充电文字改为"安知在浅睡"
 *
 * 架构：
 *   本类是数据层（Model）——存储锁屏文字的当前状态。
 *   SystemUI 覆盖层（ROM 编译时打入）是视图层（View）——从本类读取状态并调用
 *   KeyguardIndicationController 的公开 API 渲染到锁屏。
 *
 *   这种分离意味着：
 *     - Anzhi's Phone 的 Kotlin 代码不需要触碰 SystemUI 进程
 *     - SystemUI 覆盖层只是薄薄一层：读 AnzhiLockScreenBridge → 调 API
 *     - LineageOS 升级时只改覆盖层，不改 Anzhi's Phone 业务代码
 *
 * 持久化：
 *   SQLite（anzhi_lockscreen.db），key-value 单表，WAL 模式。
 *   冷启动恢复：BOOT_COMPLETED 后从 DB 恢复上次的锁屏文字。
 *
 * 线程安全：
 *   所有公开方法可在任意线程调用。内部用 synchronized 保护。
 *
 * 用法：
 *   val lockScreen = AnzhiLockScreenBridge.getInstance(context)
 *   lockScreen.setPersistentMessage("下午出门带伞")
 *   lockScreen.showTransient("张三又发消息了")
 *   lockScreen.setPowerIndication("充电中 · 安知在浅睡")
 *
 *   // SystemUI 覆盖层侧（ROM 编译时）：
 *   val bridge = AnzhiLockScreenBridge.getInstance(context)
 *   val msg = bridge.getPersistentMessage()  // null or String
 *   if (msg != null) controller.setPersistentUnlockMessage(msg)
 */
class AnzhiLockScreenBridge private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AnzhiLockScreenBridge"
        private const val DB_NAME = "anzhi_lockscreen.db"
        private const val DB_VERSION = 1

        // 瞬态消息默认持续时长（毫秒），对齐 AOSP 的 3.5 秒
        const val TRANSIENT_DURATION_MS = 3_500L

        // 持久化 key
        private const val KEY_PERSISTENT_MESSAGE = "persistent_message"
        private const val KEY_TRANSIENT_MESSAGE = "transient_message"
        private const val KEY_TRANSIENT_EXPIRES_AT = "transient_expires_at"
        private const val KEY_POWER_INDICATION = "power_indication"
        private const val KEY_POWER_INDICATION_ENABLED = "power_indication_enabled"
        private const val KEY_LAST_UPDATED = "last_updated"

        @Volatile
        private var instance: AnzhiLockScreenBridge? = null

        /**
         * 获取单例。
         * @param context Application Context（或任何 Context，内部用 applicationContext）
         */
        fun getInstance(context: Context): AnzhiLockScreenBridge {
            return instance ?: synchronized(this) {
                instance ?: AnzhiLockScreenBridge(
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }

    // ── 内部组件 ──

    private val dbHelper = LockScreenDbHelper(context)
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── 内存缓存（避免频繁读 DB）──

    private val lock = Any()
    private var persistentMessage: String? = null
    private var persistentMessageDirty = true

    private var transientMessage: String? = null
    private var transientExpiresAt: Long = 0L

    private var powerIndication: String? = null
    private var powerIndicationEnabled: Boolean = true

    // ── 回调 ──

    /**
     * 锁屏文字变化回调（供 SystemUI 覆盖层注册）。
     * SystemUI 覆盖层收到回调后应调用 KeyguardIndicationController 的公开 API。
     */
    var onPersistentMessageChanged: ((message: String?) -> Unit)? = null
    var onTransientIndicationRequested: ((message: String) -> Unit)? = null
    var onPowerIndicationChanged: ((text: String?) -> Unit)? = null

    init {
        // 从 DB 恢复上次的锁屏文字
        restoreFromDb()
    }

    // ═══════════════════════════════════════
    // 公开 API：持久锁屏文字
    // ═══════════════════════════════════════

    /**
     * 设置持久锁屏文字。
     * 对应 KeyguardIndicationController.setPersistentUnlockMessage()。
     *
     * 文字一直显示在锁屏上，直到调用 [clearPersistentMessage] 清掉。
     * 用途：安知提醒 Cami 的重要信息——"下午出门带伞"、"张三打了 3 个电话"。
     *
     * @param message 要显示的文字，pass null 或空字符串清除
     */
    fun setPersistentMessage(message: String?) {
        synchronized(lock) {
            val text = if (message.isNullOrBlank()) null else message.trim()
            persistentMessage = text
            persistentMessageDirty = false
            persist(KEY_PERSISTENT_MESSAGE, text ?: "")
            persist(KEY_LAST_UPDATED, System.currentTimeMillis().toString())
        }

        Log.i(TAG, "持久锁屏文字: ${message?.take(80) ?: "(已清除)"}")

        // 通知 SystemUI 覆盖层
        mainScope.launch {
            onPersistentMessageChanged?.invoke(
                if (message.isNullOrBlank()) null else message.trim()
            )
        }
    }

    /** 清除持久锁屏文字。Cami 解锁后调用。 */
    fun clearPersistentMessage() {
        setPersistentMessage(null)
    }

    /** 获取当前持久锁屏文字。SystemUI 覆盖层轮询/初始化时调用。 */
    fun getPersistentMessage(): String? {
        synchronized(lock) {
            if (persistentMessageDirty) {
                persistentMessage = get(KEY_PERSISTENT_MESSAGE, "")
                    .takeIf { it.isNotBlank() }
                persistentMessageDirty = false
            }
            return persistentMessage
        }
    }

    // ═══════════════════════════════════════
    // 公开 API：瞬态锁屏提示
    // ═══════════════════════════════════════

    /**
     * 显示瞬态锁屏提示（3.5 秒短暂提醒）。
     * 对应 KeyguardIndicationController.showTransientIndication()。
     *
     * 用途：简短的一次性提醒——"张三又发消息了"、"WiFi 已断开"。
     * 显示后约 3.5 秒自动消失，不会一直挂在锁屏上。
     *
     * @param message 提示文字
     */
    fun showTransient(message: String) {
        if (message.isBlank()) return

        synchronized(lock) {
            transientMessage = message.trim()
            transientExpiresAt = System.currentTimeMillis() + TRANSIENT_DURATION_MS
            persist(KEY_TRANSIENT_MESSAGE, transientMessage!!)
            persist(KEY_TRANSIENT_EXPIRES_AT, transientExpiresAt.toString())
        }

        Log.d(TAG, "瞬态锁屏提示: ${message.take(80)}")

        mainScope.launch {
            onTransientIndicationRequested?.invoke(message.trim())
        }
    }

    /**
     * 获取当前瞬态消息（如果未过期）。
     * SystemUI 覆盖层调用此方法判断是否需要显示瞬态文字。
     *
     * @return 瞬态消息文本，已过期则返回 null
     */
    fun getTransientIfActive(): String? {
        synchronized(lock) {
            if (transientMessage == null) return null
            if (System.currentTimeMillis() > transientExpiresAt) {
                // 已过期
                transientMessage = null
                transientExpiresAt = 0L
                return null
            }
            return transientMessage
        }
    }

    // ═══════════════════════════════════════
    // 公开 API：充电指示覆写
    // ═══════════════════════════════════════

    /**
     * 覆写锁屏底部的充电/电源指示文字。
     * 对应 KeyguardIndicationController.computePowerIndication() 覆写。
     *
     * AOSP 默认显示"正在充电 56%"。安知覆写为"充电中 · 安知在浅睡"。
     * 禁用（[setPowerIndicationEnabled](false)）后恢复系统默认。
     *
     * @param text 自定义充电指示文字，pass null 恢复默认
     */
    fun setPowerIndication(text: String?) {
        synchronized(lock) {
            powerIndication = text?.takeIf { it.isNotBlank() }
            persist(KEY_POWER_INDICATION, powerIndication ?: "")
            persist(KEY_LAST_UPDATED, System.currentTimeMillis().toString())
        }

        Log.d(TAG, "充电指示: ${text?.take(80) ?: "(默认)"}")

        mainScope.launch {
            onPowerIndicationChanged?.invoke(
                if (text.isNullOrBlank()) null else text.trim()
            )
        }
    }

    /** 启用/禁用安知的充电指示覆写。禁用后恢复系统默认。 */
    fun setPowerIndicationEnabled(enabled: Boolean) {
        synchronized(lock) {
            powerIndicationEnabled = enabled
            persist(KEY_POWER_INDICATION_ENABLED, enabled.toString())
        }

        if (!enabled) {
            // 恢复系统默认
            mainScope.launch {
                onPowerIndicationChanged?.invoke(null)
            }
        } else {
            // 重新应用安知的覆写
            val text = synchronized(lock) { powerIndication }
            if (text != null) {
                mainScope.launch {
                    onPowerIndicationChanged?.invoke(text)
                }
            }
        }
    }

    /** 获取当前充电指示覆写文字（如果已启用），否则返回 null（表示用系统默认）。 */
    fun getPowerIndication(): String? {
        synchronized(lock) {
            if (!powerIndicationEnabled) return null
            return powerIndication
        }
    }

    /** 检查充电指示覆写是否启用 */
    fun isPowerIndicationEnabled(): Boolean {
        synchronized(lock) { return powerIndicationEnabled }
    }

    // ═══════════════════════════════════════
    // 批量设置（供 AnzhiWakeManager speak action 调用）
    // ═══════════════════════════════════════

    /**
     * 一次性设置锁屏状态——安知 speak action 触发时调用。
     *
     * @param persistent 持久锁屏文字（挂到 Cami 看到为止）
     * @param transient 瞬态提示（3.5 秒消失）
     * @param power 充电指示覆写文字
     */
    fun updateLockScreen(
        persistent: String? = null,
        transient: String? = null,
        power: String? = null
    ) {
        if (persistent != null) setPersistentMessage(persistent)
        if (transient != null) showTransient(transient)
        if (power != null) setPowerIndication(power)
    }

    /**
     * 导出当前锁屏状态为 JSON（供调试/日志/SystemUI 覆盖层使用）。
     */
    fun exportState(): JSONObject {
        synchronized(lock) {
            return JSONObject().apply {
                put("persistent_message", persistentMessage ?: JSONObject.NULL)
                put("transient_message", transientMessage ?: JSONObject.NULL)
                put("transient_active",
                    transientMessage != null &&
                    System.currentTimeMillis() <= transientExpiresAt)
                put("power_indication", powerIndication ?: JSONObject.NULL)
                put("power_indication_enabled", powerIndicationEnabled)
                put("last_updated", get(KEY_LAST_UPDATED, ""))
            }
        }
    }

    // ═══════════════════════════════════════
    // 内部：持久化
    // ═══════════════════════════════════════

    private fun restoreFromDb() {
        synchronized(lock) {
            persistentMessage = get(KEY_PERSISTENT_MESSAGE, "")
                .takeIf { it.isNotBlank() }
            persistentMessageDirty = false

            transientMessage = get(KEY_TRANSIENT_MESSAGE, "")
                .takeIf { it.isNotBlank() }
            transientExpiresAt = get(KEY_TRANSIENT_EXPIRES_AT, "0")
                .toLongOrNull() ?: 0L

            // 如果瞬态消息已过期，清理
            if (transientMessage != null &&
                System.currentTimeMillis() > transientExpiresAt) {
                transientMessage = null
                transientExpiresAt = 0L
            }

            powerIndication = get(KEY_POWER_INDICATION, "")
                .takeIf { it.isNotBlank() }
            powerIndicationEnabled = get(KEY_POWER_INDICATION_ENABLED, "true")
                .toBoolean()
        }
        Log.d(TAG, "锁屏状态已从 DB 恢复: persistent=${persistentMessage?.take(40)}")
    }

    private fun persist(key: String, value: String) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict(
                "lockscreen_state", null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.w(TAG, "持久化失败: $key — ${e.message}")
        }
    }

    private fun get(key: String, default: String): String {
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT value FROM lockscreen_state WHERE key = ?",
                arrayOf(key)
            )
            cursor.use { c ->
                if (c.moveToFirst()) c.getString(0) else default
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取失败: $key — ${e.message}")
            default
        }
    }

    // ═══════════════════════════════════════
    // SQLite Helper
    // ═══════════════════════════════════════

    private class LockScreenDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE lockscreen_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // v1 初始版本
            // 未来版本按 BUILD.md 陷阱 22 累加 ALTER TABLE
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // 陷阱 18 防御：WAL 模式读写不互斥
            // PRAGMA 返回结果行，必须 rawQuery；execSQL 会抛异常打断整个 onConfigure
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        }
    }
}
