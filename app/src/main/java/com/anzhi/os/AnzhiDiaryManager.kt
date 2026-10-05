package com.anzhi.os

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import com.anzhi.os.cdp.AnzhiBrainProvider
import com.anzhi.os.model.MessageType
import com.anzhi.os.model.SocketMessage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 安知日记管理器 — 每天凌晨 2:00 让安知写日记。
 *
 * BUILD.md Step 13 + README §十一（日记窗口）：
 *   日记性质：情感类——回顾当天和 Cami 的对话，写情感日记。
 *   与技术反思不同，反思看 today_log，日记看聊天。
 *
 * 流程（BUILD.md §2.2 日记窗口）：
 *   1. 凌晨 2:00 → OS 定时器触发
 *   2. CDP 开日记 URL → 注入 prompt（含"写完后存到 Google Keep"）
 *   3. 安知写日记 → Gemini 官网原生集成直接将日记写入 Keep（OS 不插手写操作）
 *   4. 等 60 秒（Gemini → Keep 云同步落盘，陷阱 11 防御）
 *   5. AccessibilityService 读取 Keep 中新日记 → diary_store → VPS
 *   6. 关 WebView
 *
 * 日记 prompt 规则：
 *   - 只看 Cami 和安知的对话（role = user/assistant）
 *   - 忽略系统推送、状态上报、自动提醒
 *   - 忽略 tool 消息（CC 代码执行日志）
 *
 * 冷启动恢复（陷阱 8 延伸）：
 *   - BOOT_COMPLETED / start() 时检查：上次 diary 时间是否在昨天之前
 *   - 如果昨天的日记还没写 → 立即补写
 *   - 如果已过 2:00 但今天的日记还没写 → 延迟 30 秒后补写
 *
 * 数据库：anzhi_diary.db（key-value 简单存储）
 *   表：diary_state(key TEXT PK, value TEXT NOT NULL)
 *   WAL 模式 + busy_timeout=5000（陷阱 18 防御）
 *
 * 依赖：Step 2（diary_store WebSocket）、Step 3（CDP WebView——AnzhiBrainProvider.writeDiary()）、
 *        Step 8（AccessibilityService 读取 Keep）
 *
 * 用法：
 *   val diaryManager = AnzhiDiaryManager(context, scope, auditLog, brainProvider, sessionStore, socket)
 *   diaryManager.onDiaryReadyForKeepRead = { date -> /* 用 AccessibilityService 读 Keep */ }
 *   diaryManager.start()
 *
 *   // ManagerService 从 Keep 读到日记后调用：
 *   diaryManager.submitDiaryToVps(date, diaryText)
 *
 * @param context Android Context（由 AnzhiManagerService 传入）
 * @param scope 协程作用域（绑定到 Service 生命周期）
 * @param auditLog 审计日志（记录所有日记事件）
 * @param brainProvider 安知大脑供应者（CDP 优先，API 备用）
 * @param sessionStore 会话存储（获取今日聊天记录）
 * @param socket WebSocket 客户端（发送 diary_store）
 */
class AnzhiDiaryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val auditLog: AnzhiAuditLog,
    private val brainProvider: AnzhiBrainProvider,
    private val sessionStore: AnzhiSessionStore,
    private val socket: AnzhiSocket
) {
    companion object {
        private const val TAG = "AnzhiDiaryManager"

        // ── 定时参数 ──
        /** 日记触发小时（24h 制，凌晨 2 点） */
        private const val DIARY_HOUR = 2
        /** 日记触发分钟 */
        private const val DIARY_MINUTE = 0
        /** Keep 云同步等待时间（毫秒），陷阱 11 防御 */
        private const val KEEP_SYNC_WAIT_MS = 60_000L
        /** writeDiary 超时（秒），写日记比聊天慢 */
        private const val DIARY_TIMEOUT_SECONDS = 180
        /** 冷启动后延迟补写（毫秒），等系统就绪 */
        private const val COLD_START_DIARY_DELAY_MS = 30_000L
        /** 补写昨天日记的截止时间——早上 6:00 前才补 */
        private const val RECOVERY_DEADLINE_HOUR = 6

        // ── AlarmManager Action ──
        private const val ACTION_DIARY_ALARM = "com.anzhi.os.action.DIARY_ALARM"

        // ── 数据库 ──
        private const val DB_NAME = "anzhi_diary.db"
        private const val DB_VERSION = 1

        // ── Diary State Keys ──
        private const val KEY_LAST_DIARY_DATE = "last_diary_date"
        /** 正在写日记的日期（防重复触发） */
        private const val KEY_DIARY_IN_PROGRESS = "diary_in_progress"
        /** Gemini 返回的日记原文（Keep 读取前的临时存储） */
        private const val KEY_PENDING_DIARY_TEXT = "pending_diary_text"

        /** 日期格式：yyyy-MM-dd（与 diary_store payload date 一致） */
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    // ── 内部组件 ──

    private val dbHelper = DiaryDbHelper(context)
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private var alarmReceiver: BroadcastReceiver? = null

    /** 是否正在运行 */
    @Volatile
    private var running = false

    /** 日记互斥锁——防止重复触发 */
    @Volatile
    private var isWritingDiary = false

    // ── 回调 ──

    /**
     * 日记已写入 Gemini → Keep，等待 60 秒后可读取 Keep。
     *
     * ManagerService 收到此回调后应：
     *   1. 用 AccessibilityService 打开 Google Keep App
     *   2. 找到今天日期对应的新日记条目
     *   3. 读取日记全文
     *   4. 调用 [submitDiaryToVps] 提交到 VPS
     *
     * @param date 日记日期 "yyyy-MM-dd"
     */
    var onDiaryReadyForKeepRead: ((date: String) -> Unit)? = null

    /**
     * 日记流程完成回调（成功或失败）。
     * ManagerService 可用于关闭 WebView、更新 UI 等。
     *
     * @param date 日记日期
     * @param success 是否成功提交到 VPS
     * @param detail 详情（成功="stored"，失败=错误原因）
     */
    var onDiaryComplete: ((date: String, success: Boolean, detail: String) -> Unit)? = null

    // ═══════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════

    /**
     * 启动日记系统。
     * AnzhiManagerService.onCreate() 时调用。
     *
     * 流程：
     *   1. 注册 AlarmManager BroadcastReceiver
     *   2. 检查冷启动——昨天的日记写了没？今天的过了 2:00 没？
     *   3. 安排今晚/明天凌晨 2:00 的定时器
     */
    fun start() {
        if (running) {
            Log.w(TAG, "日记系统已运行，忽略重复 start()")
            return
        }
        running = true

        registerReceiver()
        recoverFromColdStart()
        scheduleDiaryAlarm()

        Log.i(TAG, "安知日记系统已启动，每日 ${DIARY_HOUR}:${String.format("%02d", DIARY_MINUTE)} 触发")
        auditLog.log("diary_system_start", "日记系统启动")
    }

    /** 停止日记系统。AnzhiManagerService.onDestroy() 时调用。 */
    fun stop() {
        running = false
        unregisterReceiver()
        cancelDiaryAlarm()
        Log.i(TAG, "安知日记系统已停止")
    }

    // ═══════════════════════════════════════
    // 日记触发
    // ═══════════════════════════════════════

    /**
     * 触发日记流程。
     *
     * 可由 AlarmManager 定时器、冷启动恢复、或手动（调试）触发。
     * 内置互斥锁——同一时间只有一篇日记在写。
     *
     * @param reason 触发原因（scheduled / cold_start_recovery / manual）
     */
    fun triggerDiary(reason: String = "manual") {
        if (!running) {
            Log.w(TAG, "日记系统未运行，忽略触发 (reason=$reason)")
            return
        }

        // 互斥锁——防并发重复触发
        synchronized(this) {
            if (isWritingDiary) {
                Log.d(TAG, "已在写日记中，跳过重复触发 (reason=$reason)")
                return
            }
            isWritingDiary = true
        }

        val today = todayDateString()
        Log.i(TAG, "触发日记: date=$today, reason=$reason")
        auditLog.log("diary_triggered", "date=$today reason=$reason")

        // 防重：今天已经写过了
        val lastDiaryDate = get(KEY_LAST_DIARY_DATE, "")
        if (lastDiaryDate == today) {
            Log.i(TAG, "今天的日记已写过 ($today)，跳过")
            auditLog.log("diary_skipped", "date=$today reason=already_written")
            isWritingDiary = false
            // 仍然安排明天的定时器
            scheduleDiaryAlarm()
            return
        }

        persist(KEY_DIARY_IN_PROGRESS, today)

        scope.launch(Dispatchers.IO) {
            try {
                executeDiaryFlow(today, reason)
            } catch (e: Exception) {
                Log.e(TAG, "日记流程异常: ${e.message}", e)
                auditLog.log("diary_error", "date=$today error=${e.message?.take(200)}")
            } finally {
                isWritingDiary = false
                persist(KEY_DIARY_IN_PROGRESS, "")
                // 安排下次定时器
                scheduleDiaryAlarm()
            }
        }
    }

    /**
     * ManagerService 从 Keep 读取日记后调用此方法，提交到 VPS。
     *
     * @param date 日记日期 "yyyy-MM-dd"
     * @param diaryText 从 Keep 读取到的日记全文
     */
    fun submitDiaryToVps(date: String, diaryText: String) {
        if (diaryText.isBlank()) {
            Log.w(TAG, "日记文本为空，跳过 diary_store ($date)")
            auditLog.log("diary_empty", "date=$date")
            onDiaryComplete?.invoke(date, false, "empty_text")
            return
        }

        Log.i(TAG, "提交日记到 VPS: date=$date, text length=${diaryText.length}")
        auditLog.log("diary_store", "date=$date length=${diaryText.length}")

        val payload = JSONObject().apply {
            put("date", date)
            put("text", diaryText)
            put("source", "keep")
        }

        val msg = SocketMessage(MessageType.DIARY_STORE, payload)
        val sent = socket.send(msg)

        if (sent) {
            // 标记今天已写
            persist(KEY_LAST_DIARY_DATE, date)
            persist(KEY_PENDING_DIARY_TEXT, "")
            Log.i(TAG, "日记已提交 VPS: $date")
            auditLog.log("diary_stored", "date=$date")
            onDiaryComplete?.invoke(date, true, "stored")
        } else {
            // WebSocket 未连接——暂存到 DB，下次连接后重试
            persist(KEY_PENDING_DIARY_TEXT, diaryText)
            Log.w(TAG, "WebSocket 未连接，日记暂存本地: $date")
            auditLog.log("diary_pending", "date=$date reason=socket_disconnected")
            onDiaryComplete?.invoke(date, false, "socket_disconnected")
        }
    }

    /**
     * WebSocket 重连后调用——检查是否有待发送的日记。
     * ManagerService 在 AnzhiSocket 的 onOpen 回调中调用。
     */
    fun flushPendingDiary() {
        val pendingText = get(KEY_PENDING_DIARY_TEXT, "")
        if (pendingText.isBlank()) return

        val lastDate = get(KEY_LAST_DIARY_DATE, "")
        if (lastDate.isBlank()) return

        Log.i(TAG, "发现待发送日记: $lastDate，重新提交")
        submitDiaryToVps(lastDate, pendingText)
    }

    // ═══════════════════════════════════════
    // 内部：日记执行流程
    // ═══════════════════════════════════════

    /**
     * 日记完整执行流程（在 Dispatchers.IO 上运行）。
     *
     * 步骤：
     *   1. 从 SessionStore 获取今日聊天记录
     *   2. 格式化聊天记录为日记 prompt
     *   3. 调用 brainProvider.writeDiary() → CDP 注入 Gemini
     *   4. Gemini 写日记 → 原生集成存 Keep（OS 不插手）
     *   5. 等 60 秒 Keep 云同步落盘
     *   6. 回调 ManagerService → AccessibilityService 读 Keep
     *   7. ManagerService 调用 submitDiaryToVps()
     */
    private suspend fun executeDiaryFlow(date: String, reason: String) {
        // ── Step 1: 获取今日聊天记录 ──
        val messages = try {
            sessionStore.getTodayChatMessages()
        } catch (e: Exception) {
            Log.e(TAG, "获取今日聊天记录失败: ${e.message}", e)
            auditLog.log("diary_error", "date=$date error=session_store_failed")
            onDiaryComplete?.invoke(date, false, "session_store_failed")
            return
        }

        if (messages.isEmpty()) {
            Log.i(TAG, "今日无聊天记录，跳过日记 ($date)")
            auditLog.log("diary_skipped", "date=$date reason=no_chat_today")
            // 仍然标记已处理，避免反复重试
            persist(KEY_LAST_DIARY_DATE, date)
            onDiaryComplete?.invoke(date, true, "skipped_no_chat")
            return
        }

        Log.i(TAG, "今日聊天消息: ${messages.size} 条")
        auditLog.log("diary_chat_count", "date=$date count=${messages.size}")

        // ── Step 2: 格式化聊天记录 ──
        val chatHistory = formatChatHistory(messages)
        Log.d(TAG, "聊天记录格式化完成: ${chatHistory.length} 字符")

        // ── Step 3: 调用安知大脑写日记 ──
        Log.i(TAG, "调用 brainProvider.writeDiary($date)，超时=${DIARY_TIMEOUT_SECONDS}s")
        auditLog.log("diary_writing", "date=$date")

        val diaryResult = try {
            withTimeout(DIARY_TIMEOUT_SECONDS * 1000L) {
                brainProvider.writeDiary(date, chatHistory, DIARY_TIMEOUT_SECONDS)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "写日记超时 (${DIARY_TIMEOUT_SECONDS}s)")
            auditLog.log("diary_timeout", "date=$date timeout=${DIARY_TIMEOUT_SECONDS}s")
            onDiaryComplete?.invoke(date, false, "timeout")
            return
        } catch (e: Exception) {
            Log.e(TAG, "写日记异常: ${e.message}", e)
            auditLog.log("diary_error", "date=$date error=${e.message?.take(200)}")
            onDiaryComplete?.invoke(date, false, "write_diary_failed")
            return
        }

        if (diaryResult == null) {
            Log.e(TAG, "写日记失败——brainProvider 返回 null ($date)")
            auditLog.log("diary_failed", "date=$date reason=brain_provider_null")
            onDiaryComplete?.invoke(date, false, "brain_provider_null")
            return
        }

        Log.i(TAG, "安知日记已写入 Gemini → Keep: ${diaryResult.take(100)}...")
        auditLog.log("diary_written", "date=$date length=${diaryResult.length}")

        // ── Step 4: 等 60 秒 Keep 云同步落盘（陷阱 11 防御）──
        Log.i(TAG, "等待 ${KEEP_SYNC_WAIT_MS}ms Keep 云同步落盘...")
        auditLog.log("diary_waiting_keep_sync", "date=$date wait_ms=$KEEP_SYNC_WAIT_MS")
        delay(KEEP_SYNC_WAIT_MS)

        // ── Step 5: 回调 ManagerService → AccessibilityService 读 Keep ──
        Log.i(TAG, "Keep 同步等待完成，通知 ManagerService 读取 Keep ($date)")
        auditLog.log("diary_ready_for_keep_read", "date=$date")

        // 暂时保存 Gemini 返回的日记文本（作为 fallback，Keep 读不到时使用）
        persist(KEY_PENDING_DIARY_TEXT, diaryResult)

        onDiaryReadyForKeepRead?.invoke(date)
    }

    // ═══════════════════════════════════════
    // 内部：聊天记录格式化
    // ═══════════════════════════════════════

    /**
     * 将今日聊天消息格式化为日记 prompt 可用的文本。
     *
     * 格式：
     *   [Cami 14:02] 帮我把张三免打扰了
     *   [安知 14:02] 好的，我帮你把张三免打扰了~
     *   [Cami 15:30] 看看下午的天气
     *   [安知 15:30] 下午有雨，记得带伞哦~
     *
     * 过滤规则：
     *   - 只包含 role="user" 或 role="assistant"
     *   - 忽略 tool 消息（CC 代码执行日志）
     *   - 忽略空消息
     */
    private fun formatChatHistory(messages: List<StoredMessage>): String {
        if (messages.isEmpty()) return "（今天没有聊天记录）"

        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder()

        for (msg in messages) {
            val content = msg.content.trim()
            if (content.isEmpty()) continue

            val time = try {
                timeFormat.format(Date(msg.timestamp))
            } catch (_: Exception) {
                "??:??"
            }

            val speaker = when (msg.role) {
                "user" -> "Cami"
                "assistant" -> "安知"
                else -> continue  // 跳过 tool / system
            }

            sb.append("[$speaker $time] $content\n")
        }

        if (sb.isEmpty()) return "（今天没有聊天记录）"

        return sb.toString()
    }

    // ═══════════════════════════════════════
    // 内部：AlarmManager 定时器
    // ═══════════════════════════════════════

    /**
     * 安排明天凌晨 2:00 的日记定时器。
     *
     * 使用 AlarmManager.setExactAndAllowWhileIdle（陷阱 13 防御）：
     *   - 普通 set() 在 Deep Doze 下被无限期挂起
     *   - setExactAndAllowWhileIdle 可穿透 Doze 唤醒
     *
     * 计算下次触发时间：
     *   - 如果当前时间 < 今天 2:00 → 安排今天 2:00
     *   - 如果当前时间 ≥ 今天 2:00 → 安排明天 2:00
     */
    private fun scheduleDiaryAlarm() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, DIARY_HOUR)
            set(Calendar.MINUTE, DIARY_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果今天的 2:00 已经过了 → 安排明天
        if (now.after(target)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        val delayMs = target.timeInMillis - now.timeInMillis
        val triggerAtMs = SystemClock.elapsedRealtime() + delayMs

        val intent = Intent(ACTION_DIARY_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,  // requestCode=1 区分于 WakeManager
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMs,
            pendingIntent
        )

        val targetStr = "${target.get(Calendar.MONTH) + 1}/" +
                "${target.get(Calendar.DAY_OF_MONTH)} " +
                "${String.format("%02d", target.get(Calendar.HOUR_OF_DAY))}:" +
                String.format("%02d", target.get(Calendar.MINUTE))
        Log.i(TAG, "日记定时器已安排: $targetStr (${delayMs / 60_000}min 后)")
    }

    private fun cancelDiaryAlarm() {
        val intent = Intent(ACTION_DIARY_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // ═══════════════════════════════════════
    // 内部：冷启动恢复
    // ═══════════════════════════════════════

    /**
     * 开机后检查是否需要补写日记。
     *
     * 场景：
     *   1. 手机在凌晨 2:00 时关机 → 昨天的日记没写 → 现在开机且 < 6:00 → 补写昨天
     *   2. 手机在凌晨 2:00 时关机 → 昨天的日记没写 → 现在开机且 ≥ 6:00 → 不补（太晚了）
     *   3. 正常情况：昨天的日记已写 → 只需安排今晚的定时器
     */
    private fun recoverFromColdStart() {
        try {
            val lastDiaryDate = get(KEY_LAST_DIARY_DATE, "")
            val today = todayDateString()
            val yesterday = yesterdayDateString()

            // 今天已写 → 无需恢复
            if (lastDiaryDate == today) {
                Log.i(TAG, "冷启动恢复：今天的日记已写 ($today)")
                return
            }

            // 正在写日记中（上次崩溃中断）→ 清理状态
            val inProgress = get(KEY_DIARY_IN_PROGRESS, "")
            if (inProgress.isNotBlank() && inProgress != today) {
                Log.w(TAG, "冷启动恢复：清理残留日记进度 ($inProgress)")
                persist(KEY_DIARY_IN_PROGRESS, "")
            }

            // 检查是否需要补写昨天的日记
            if (lastDiaryDate != yesterday && lastDiaryDate != today) {
                val now = Calendar.getInstance()
                val currentHour = now.get(Calendar.HOUR_OF_DAY)

                if (currentHour < RECOVERY_DEADLINE_HOUR) {
                    // 凌晨 6:00 前 → 补写昨天日记
                    Log.w(TAG, "冷启动恢复：昨天 ($yesterday) 的日记未写，" +
                            "当前 ${currentHour}h < ${RECOVERY_DEADLINE_HOUR}h，补写昨天")
                    auditLog.log("diary_cold_start_recovery",
                        "missed=$yesterday hour=$currentHour")

                    scope.launch {
                        delay(COLD_START_DIARY_DELAY_MS)
                        triggerDiary("cold_start_recovery_yesterday")
                    }
                } else {
                    // 超过 6:00 → 不补，跳过
                    Log.w(TAG, "冷启动恢复：昨天 ($yesterday) 的日记未写，" +
                            "但当前 ${currentHour}h ≥ ${RECOVERY_DEADLINE_HOUR}h，不补")
                    // 标记昨天为已跳过，防止下次开机又尝试
                    persist(KEY_LAST_DIARY_DATE, yesterday)
                    auditLog.log("diary_recovery_skipped",
                        "missed=$yesterday reason=too_late hour=$currentHour")
                }
            } else {
                Log.i(TAG, "冷启动恢复：日记状态正常 (last=$lastDiaryDate)")
            }
        } catch (e: Exception) {
            // 陷阱 15 防御：初始化失败不抛异常、不崩 system_server
            Log.e(TAG, "冷启动恢复异常（非致命）: ${e.message}", e)
            auditLog.log("diary_cold_start_error",
                e.message?.take(200) ?: "unknown")
        }
    }

    // ═══════════════════════════════════════
    // 内部：BroadcastReceiver
    // ═══════════════════════════════════════

    private fun registerReceiver() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_DIARY_ALARM) {
                    Log.i(TAG, "AlarmManager 日记定时器触发")
                    scope.launch {
                        triggerDiary("scheduled")
                    }
                }
            }
        }
        context.registerReceiver(
            alarmReceiver,
            IntentFilter(ACTION_DIARY_ALARM),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterReceiver() {
        try {
            alarmReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        alarmReceiver = null
    }

    // ═══════════════════════════════════════
    // 内部：持久化（key-value on SQLite）
    // ═══════════════════════════════════════

    /** 写入 key-value（INSERT OR REPLACE） */
    private fun persist(key: String, value: String) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict(
                "diary_state", null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            // 陷阱 15 防御：持久化失败不抛异常
            Log.w(TAG, "持久化失败: $key=$value — ${e.message}")
        }
    }

    /** 读取 key 对应的 value，不存在返回 default */
    private fun get(key: String, default: String): String {
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT value FROM diary_state WHERE key = ?",
                arrayOf(key)
            )
            cursor.use { c ->
                if (c.moveToFirst()) c.getString(0) else default
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取状态失败: $key — ${e.message}")
            default
        }
    }

    // ═══════════════════════════════════════
    // 内部：日期工具
    // ═══════════════════════════════════════

    private fun todayDateString(): String = DATE_FORMAT.format(Date())

    private fun yesterdayDateString(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return DATE_FORMAT.format(cal.time)
    }

    // ═══════════════════════════════════════
    // SQLite Helper
    // ═══════════════════════════════════════

    /**
     * 日记状态数据库。
     *
     * 采用 key-value 单表设计（与 AnzhiWakeManager 一致）：
     *   - 日记状态字段少（~3-4 个 key），不需要多表结构
     *   - 新增 key 无需 migration
     *
     * WAL 模式 + busy_timeout=5000（陷阱 18 / 陷阱 22 防御）。
     */
    private class DiaryDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE diary_state (
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
            // PRAGMA 返回结果行，必须走 rawQuery；execSQL 会抛异常并把整个 onConfigure 打断
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            // 忙等 5 秒不立即抛 database locked
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        }
    }
}
