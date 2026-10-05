package com.anzhi.os

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 安知唤醒管理器 — 自触发系统。
 *
 * BUILD.md Step 11 + README §九：OS 按时叫醒安知，把全部上下文给他。
 * 说不说、做什么——全是安知自己的事。
 *
 * 唤醒时机：
 *   - 定时：每 60-180 分钟随机（AlarmManager.setExactAndAllowWhileIdle，陷阱 13）
 *   - 事件打断：连切 5 App / 连续使用 2h / 电量 15%
 *   - 紧急破冰：电量≤15% / CPU过热≥50°C / 日程到时间 → 无视冷却期（最高优先级）
 *
 * 沉默冷却：
 *   - Cami 4h 未回 → 不再主动（紧急可打破）
 *   - Cami 划掉卡片 → 下次唤醒提醒安知
 *
 * 冷启动恢复（陷阱 8 防御）：
 *   - BOOT_COMPLETED 时从 SQLite 读 last_wake_time + next_wake_minutes
 *   - now - last_wake_time > next_wake_minutes → 立即触发苏醒
 *   - 否则从剩余差值恢复 AlarmManager 倒计时
 *   - next_wake 变更时同步持久化到 SQLite
 *
 * 数据库：anzhi_wake.db（key-value 简单存储，不建多张表）
 *   表：wake_state(key TEXT PK, value TEXT NOT NULL)
 *   WAL 模式 + busy_timeout=5000（陷阱 18 防御）
 *
 * 依赖：Step 3（CDP WebView——安知大脑回答）、Step 6（Chat 界面——点通知进入聊天）
 *
 * 用法：
 *   val wakeManager = AnzhiWakeManager(context, scope, auditLog)
 *   wakeManager.onWakeTriggered = { ctx -> /* 启动 CDP WebView 注入 ctx */ }
 *   wakeManager.onSpeakRequested = { text, emergency -> /* 仪表盘卡片 + 锁屏 */ }
 *   wakeManager.start()
 *
 * @param context Android Context（由 AnzhiManagerService 传入）
 * @param scope 协程作用域（绑定到 Service 生命周期）
 * @param auditLog 审计日志（记录所有唤醒事件）
 */
class AnzhiWakeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val auditLog: AnzhiAuditLog
) {
    companion object {
        private const val TAG = "AnzhiWakeManager"

        // ── 定时参数 ──
        /** 最短唤醒间隔（分钟） */
        private const val WAKE_MIN_MINUTES = 60L
        /** 最长唤醒间隔（分钟） */
        private const val WAKE_MAX_MINUTES = 180L
        /** 默认下次唤醒间隔（分钟），安知未指定时使用 */
        private const val DEFAULT_NEXT_WAKE_MINUTES = 90

        // ── 事件检测参数 ──
        /** 连切 App 次数阈值 */
        private const val APP_SWITCH_THRESHOLD = 5
        /** 连续使用时长阈值（分钟） */
        private const val CONTINUOUS_USE_THRESHOLD_MIN = 120L
        /** 电量低阈值（百分比） */
        private const val BATTERY_LOW_THRESHOLD = 15
        /** CPU 过热阈值（摄氏度） */
        private const val CPU_OVERHEAT_THRESHOLD = 50
        /** App 切换计数窗口（毫秒），超过此窗口重置计数 */
        private const val APP_SWITCH_WINDOW_MS = 30_000L

        // ── 沉默冷却参数 ──
        /** 沉默冷却时长（小时） */
        private const val SILENCE_COOLDOWN_HOURS = 4L
        /** 沉默冷却时长（毫秒） */
        private const val SILENCE_COOLDOWN_MS = SILENCE_COOLDOWN_HOURS * 3600 * 1000L

        // ── 通知 ──
        private const val WAKE_NOTIFICATION_ID = 3001
        private const val WAKE_CHANNEL_ID = "anzhi_wake"
        private const val WAKE_CHANNEL_NAME = "安知唤醒"

        // ── AlarmManager Action ──
        private const val ACTION_WAKE_ALARM = "com.anzhi.os.action.WAKE_ALARM"

        // ── 状态检查间隔 ──
        /** 连续使用检查间隔（毫秒） */
        private const val CONTINUOUS_USE_CHECK_INTERVAL_MS = 60_000L
        /** 冷启动后延迟唤醒（毫秒），等系统就绪 */
        private const val COLD_START_WAKE_DELAY_MS = 10_000L

        // ── 数据库 ──
        private const val DB_NAME = "anzhi_wake.db"
        private const val DB_VERSION = 1

        // ── Wake State Keys（SQLite key-value 存储）──
        private const val KEY_LAST_WAKE_TIME = "last_wake_time"
        private const val KEY_NEXT_WAKE_MINUTES = "next_wake_minutes"
        private const val KEY_SILENCE_START = "silence_start"
        private const val KEY_LAST_CHAT_TIME = "last_chat_time"
        private const val KEY_APP_SWITCH_COUNT = "app_switch_count"
        private const val KEY_APP_SWITCH_WINDOW_START = "app_switch_window_start"
        private const val KEY_SESSION_START_TIME = "session_start_time"
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_CONSECUTIVE_SILENCE = "consecutive_silence"
        private const val KEY_CAMI_DISMISSED = "cami_dismissed"
    }

    // ── 内部组件 ──

    private val dbHelper = WakeDbHelper(context)
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var alarmReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var continuousUseJob: Job? = null

    /** 是否正在运行 */
    @Volatile
    private var running = false

    /** 唤醒互斥锁——防止重复触发 */
    private val wakingLock = AtomicBoolean(false)

    // ── 内存状态（从 DB 恢复，运行中在内存更新）──

    @Volatile private var currentAppSwitchCount = 0
    @Volatile private var appSwitchWindowStart = 0L
    @Volatile private var sessionStartTime = 0L
    @Volatile private var lastApp: String = ""
    @Volatile private var isScreenOn = false

    // ── 回调 ──

    /**
     * 唤醒触发回调。
     * ManagerService 收到后应：启动 CDP WebView 提醒窗口 →
     * CDP 注入 context JSON → Gemini 回复 → 调用 [onWakeResponse]
     */
    var onWakeTriggered: ((context: JSONObject) -> Unit)? = null

    /**
     * speak action 回调。
     * ManagerService 收到后应显示仪表盘卡片或降级通知。
     * @param text 安知要说的话
     * @param isEmergency 是否为紧急唤醒
     */
    var onSpeakRequested: ((text: String, isEmergency: Boolean) -> Unit)? = null

    /**
     * 唤醒通知被点击时启动的 Activity 类名（默认 Chat Activity）。
     * 如果 Chat Activity 尚未编译，通知点击无响应但不会崩溃。
     */
    var chatActivityClassName: String = "com.anzhi.os.chat.AnzhiChatActivity"

    // ═══════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════

    /**
     * 启动唤醒系统。
     * AnzhiManagerService.onCreate() 时调用。
     *
     * 流程：
     *   1. 创建通知频道
     *   2. 注册 BroadcastReceiver（Alarm + Battery）
     *   3. 从 DB 恢复状态 → 检查冷启动真空期（陷阱 8）
     *   4. 恢复或新建 AlarmManager 定时器
     *   5. 若屏幕当前亮着 → 开始连续使用监控
     */
    fun start() {
        if (running) {
            Log.w(TAG, "唤醒系统已运行，忽略重复 start()")
            return
        }
        running = true

        // 从 DB 恢复内存状态
        restoreState()

        createNotificationChannel()
        registerReceivers()
        recoverFromColdStart()       // 陷阱 8：填补冷启动真空期
        scheduleNextWakeFromDb()     // 从 DB 恢复/新建定时器

        // 如果当前屏幕亮着 → 开始连续使用监控
        if (powerManager.isInteractive) {
            isScreenOn = true
            beginSession()
        }

        Log.i(TAG, "安知唤醒系统已启动")
        auditLog.log("wake_system_start", "唤醒系统启动")
    }

    /** 停止唤醒系统。AnzhiManagerService.onDestroy() 时调用。 */
    fun stop() {
        running = false
        unregisterReceivers()
        cancelWakeAlarm()
        continuousUseJob?.cancel()
        continuousUseJob = null
        Log.i(TAG, "安知唤醒系统已停止")
    }

    // ═══════════════════════════════════════
    // 事件输入（由 AnzhiManagerService 喂入）
    // ═══════════════════════════════════════

    /**
     * 前台 App 切换时调用。
     * 在 30 秒窗口内连续切换 ≥5 个不同 App → 触发事件唤醒。
     */
    fun onAppSwitched(packageName: String) {
        if (!running || packageName.isBlank()) return

        val now = System.currentTimeMillis()

        // 同一 App 不算切换
        if (packageName == lastApp) return

        // 窗口管理：30 秒外的切换重置计数
        if (appSwitchWindowStart == 0L ||
            now - appSwitchWindowStart > APP_SWITCH_WINDOW_MS
        ) {
            appSwitchWindowStart = now
            currentAppSwitchCount = 0
        }

        currentAppSwitchCount++
        lastApp = packageName

        persist(KEY_APP_SWITCH_COUNT, currentAppSwitchCount.toString())
        persist(KEY_APP_SWITCH_WINDOW_START, appSwitchWindowStart.toString())
        persist(KEY_LAST_APP, packageName)

        Log.d(TAG, "App 切换: $packageName (#$currentAppSwitchCount)")

        // 连切 5 App → 触发事件唤醒
        if (currentAppSwitchCount >= APP_SWITCH_THRESHOLD) {
            Log.i(TAG, "检测到连切 $currentAppSwitchCount 个 App，触发事件唤醒")
            currentAppSwitchCount = 0  // 重置，防重复触发
            triggerWake(WakeReason.APP_SWITCHING, isEmergency = false)
        }
    }

    /** 屏幕亮起时调用（由 ManagerService 传入，或自行注册 SCREEN_ON 广播） */
    fun onScreenOn() {
        if (!running) return
        isScreenOn = true
        beginSession()
    }

    /** 屏幕熄灭时调用 */
    fun onScreenOff() {
        isScreenOn = false
        endSession()
    }

    /**
     * 电量变化时调用。
     * battery ≤ 15% 且未充电 → 紧急唤醒（无视冷却期）。
     */
    fun onBatteryChanged(level: Int, charging: Boolean) {
        if (!running) return

        if (level <= BATTERY_LOW_THRESHOLD && !charging) {
            Log.w(TAG, "电量低至 $level%，触发紧急唤醒")
            triggerWake(WakeReason.BATTERY_LOW, isEmergency = true)
        }
    }

    /**
     * CPU 温度变化时调用。
     * cpu_temp ≥ 50°C → 紧急唤醒（无视冷却期）。
     */
    fun onCpuTempChanged(temp: Int) {
        if (!running) return

        if (temp >= CPU_OVERHEAT_THRESHOLD) {
            Log.w(TAG, "CPU 过热 ${temp}°C，触发紧急唤醒")
            triggerWake(WakeReason.CPU_OVERHEAT, isEmergency = true)
        }
    }

    /**
     * 日程即将到时间时调用。
     * 无视冷却期，强行唤醒安知。
     */
    fun onCalendarEventDue(title: String, timeMinutes: Int) {
        if (!running) return
        Log.i(TAG, "日程「$title」将在 ${timeMinutes}min 后开始，触发紧急唤醒")
        triggerWake(WakeReason.CALENDAR_EVENT, isEmergency = true)
    }

    /**
     * Cami 跟安知聊天了 → 重置沉默冷却、清零连续沉默计数。
     * ManagerService 在 chat_sync 完成后调用。
     */
    fun onCamiChatted() {
        val now = System.currentTimeMillis()
        persist(KEY_LAST_CHAT_TIME, now.toString())
        persist(KEY_SILENCE_START, "0")
        persist(KEY_CONSECUTIVE_SILENCE, "0")
        persist(KEY_CAMI_DISMISSED, "false")
        Log.d(TAG, "Cami 已聊天，沉默冷却已重置")
        auditLog.log("wake_silence_reset", "Cami chatted")
    }

    /**
     * Cami 划掉了安知的卡片/唤醒通知。
     * 递增连续沉默计数，下次唤醒时告知安知"上次被划掉了"。
     */
    fun onCamiDismissedCard() {
        persist(KEY_CAMI_DISMISSED, "true")
        val consecutive =
            (get(KEY_CONSECUTIVE_SILENCE, "0").toIntOrNull() ?: 0) + 1
        persist(KEY_CONSECUTIVE_SILENCE, consecutive.toString())
        Log.d(TAG, "Cami 划掉卡片，连续沉默: $consecutive")
        auditLog.log("wake_card_dismissed", "consecutive=$consecutive")
    }

    // ═══════════════════════════════════════
    // 唤醒流程
    // ═══════════════════════════════════════

    /**
     * 构建唤醒上下文 JSON。
     *
     * BUILD.md §2.2 唤醒 Context 注入模板：
     *   安知，你可以选择是否给 Cami 发消息。看看她的状态，你自己决定。
     *
     * 注入规则（BUILD.md §2.2）：
     *   - 时间每条都带
     *   - 天气变了才带（调用方 diff 后传入）
     *   - 日程未来 3h 内有才带（调用方过滤后传入）
     *   - 心情变化 >0.1 才带（调用方 diff 后传入）
     *   - 记忆词条每条都带（VPS 搜索结果）
     *   - today_log 只送新条目（OS 本地跟踪已发条目）
     *
     * @return 完整的唤醒上下文 JSON，可直接注入 CDP Gemini WebView
     */
    fun buildWakeContext(
        time: String = currentTimeString(),
        dayOfWeek: String = currentDayOfWeek(),
        minutesSinceLastChat: Long = computeMinutesSinceLastChat(),
        location: String = "未知",
        foregroundApp: String = lastApp,
        screenOn: Boolean = isScreenOn,
        battery: Int = 100,
        todayLog: List<String> = emptyList(),
        weatherCondition: String = "",
        weatherTemp: Int = 0,
        calendarEvents: List<Pair<String, String>> = emptyList(),  // (title, time)
        moodScore: Double = 0.5,
        moodLabel: String = "平静",
        memoryHits: List<String> = emptyList(),
        isEmergency: Boolean = false
    ): JSONObject {
        val camiDismissed = get(KEY_CAMI_DISMISSED, "false").toBoolean()
        val consecutiveSilence =
            get(KEY_CONSECUTIVE_SILENCE, "0").toIntOrNull() ?: 0

        val obj = JSONObject()
        obj.put("action", "wake")
        obj.put("reminder",
            buildReminderText(isEmergency, camiDismissed, consecutiveSilence))

        val ctx = JSONObject()
        ctx.put("time", time)
        ctx.put("day", dayOfWeek)
        ctx.put("minutes_since_last_chat", minutesSinceLastChat)
        ctx.put("location", location)
        ctx.put("current_device", "phone")

        // 心情
        val mood = JSONObject()
        mood.put("score", moodScore)
        mood.put("label", moodLabel)
        ctx.put("mood", mood)

        // Cami 当前状态
        val cami = JSONObject()
        cami.put("foreground_app", foregroundApp)
        cami.put("screen_on", screenOn)
        cami.put("battery", battery)
        ctx.put("cami", cami)

        // today_log（由调用方管理已发条目，这里只传未发过的）
        val logArr = JSONArray()
        todayLog.forEach { logArr.put(it) }
        ctx.put("today_log", logArr)

        // 天气（变了才带——调用方 diff 后传入，空字符串 = 不带）
        if (weatherCondition.isNotBlank()) {
            val w = JSONObject()
            w.put("condition", weatherCondition)
            w.put("temp", weatherTemp)
            ctx.put("weather", w)
        }

        // 日程（未来 3h 内有才带——调用方过滤后传入）
        if (calendarEvents.isNotEmpty()) {
            val calArr = JSONArray()
            calendarEvents.forEach { (title, calTime) ->
                val ev = JSONObject()
                ev.put("title", title)
                ev.put("time", calTime)
                calArr.put(ev)
            }
            ctx.put("calendar", calArr)
        }

        // 记忆词条（VPS memory_search 结果，每条都带）
        if (memoryHits.isNotEmpty()) {
            val memArr = JSONArray()
            memoryHits.forEach { memArr.put(it) }
            ctx.put("memory_hits", memArr)
        }

        // 沉默状态——安知的内部信息
        ctx.put("cami_dismissed_last", camiDismissed)
        ctx.put("consecutive_silence_count", consecutiveSilence)
        ctx.put("is_emergency", isEmergency)

        obj.put("context", ctx)
        obj.put("note",
            "speak 不是推送通知——你说的话会渲染到仪表盘卡片上，" +
            "Cami 看到卡片点进来才能跟你聊。你不能直接弹通知栏。"
        )

        return obj
    }

    /**
     * 安知回复后调用——解析 actions、处理 speak、安排下次唤醒。
     *
     * Gemini 回复格式（BUILD.md §九）：
     * ```
     * { "actions": [...], "why": "...", "next_wake_minutes": 90 }
     * ```
     *
     * @param responseJson Gemini 安知返回的原始 JSON
     * @param onNonSpeakAction 非 speak action 的回调（ManagerService → ActionExecutor）
     */
    fun onWakeResponse(
        responseJson: JSONObject,
        onNonSpeakAction: ((type: String, text: String) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()

        // 记录本次唤醒
        persist(KEY_LAST_WAKE_TIME, now.toString())
        persist(KEY_CAMI_DISMISSED, "false")

        val actions = responseJson.optJSONArray("actions")
        val nextWakeMinutes = responseJson.optInt(
            "next_wake_minutes", DEFAULT_NEXT_WAKE_MINUTES
        )
        val why = responseJson.optString("why", "")

        Log.i(TAG, "安知唤醒响应: actions=${actions?.length() ?: 0}, " +
                "next=${nextWakeMinutes}min, why=${why.take(100)}")
        auditLog.log("wake_response",
            "actions=${actions?.length() ?: 0} " +
            "next=${nextWakeMinutes}min why=${why.take(100)}")

        // 处理 actions
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val action = actions.getJSONObject(i)
                val type = action.optString("type", "")
                val text = action.optString("text", "")

                when (type) {
                    "speak" -> {
                        val isEmergency = responseJson.optBoolean(
                            "is_emergency", false
                        )
                        handleSpeakAction(text, isEmergency)
                    }
                    else -> {
                        // go_home / open_app / silent_mode / clear_cache /
                        // force_stop / tap / swipe / type
                        // → 由 ManagerService 的 ActionExecutor 执行
                        Log.d(TAG, "非 speak action: $type → ActionExecutor")
                        onNonSpeakAction?.invoke(type, text)
                    }
                }
            }
        }

        // 若没有 actions 且安知什么都没做 → 仍安排下次唤醒
        if (actions == null || actions.length() == 0) {
            Log.d(TAG, "安知无 action (why=$why)，安排下次定时唤醒")
        }

        // 安排下次唤醒
        scheduleNextWake(nextWakeMinutes)

        // 解锁唤醒互斥
        wakingLock.set(false)
    }

    /** 手动触发唤醒（紧急/调试用）。沉默冷却对非紧急触发生效。 */
    fun triggerWake(reason: WakeReason, isEmergency: Boolean) {
        if (!running) {
            Log.w(TAG, "唤醒系统未运行，忽略触发 (reason=$reason)")
            return
        }

        // 唤醒互斥——防止并发重复触发（Atomic CAS，无需 synchronized）
        if (!wakingLock.compareAndSet(false, true)) {
            Log.d(TAG, "已在唤醒流程中，跳过重复触发 (reason=$reason)")
            return
        }

        // 沉默冷却检查（紧急破冰无视）
        if (!isEmergency && isInSilenceCooldown()) {
            Log.d(TAG, "处于沉默冷却期，跳过非紧急唤醒 (reason=$reason)")
            wakingLock.set(false)
            return
        }

        Log.i(TAG, "触发唤醒: reason=$reason, emergency=$isEmergency")
        auditLog.log("wake_triggered", "reason=$reason emergency=$isEmergency")

        val context = buildWakeContext(isEmergency = isEmergency)
        context.put("wake_reason", reason.name)

        onWakeTriggered?.invoke(context)
    }

    // ═══════════════════════════════════════
    // 内部：定时器
    // ═══════════════════════════════════════

    /**
     * 安排下次唤醒。
     *
     * 使用 AlarmManager.setExactAndAllowWhileIdle（陷阱 13 防御）：
     *   - 普通 set() 在 Deep Doze 下被无限期挂起
     *   - setExactAndAllowWhileIdle 可穿透 Doze 唤醒
     *
     * @param delayMinutes 距现在多少分钟后唤醒，会被 clamp 到 [60, 180]
     */
    private fun scheduleNextWake(delayMinutes: Int) {
        val clamped = delayMinutes.coerceIn(
            WAKE_MIN_MINUTES.toInt(), WAKE_MAX_MINUTES.toInt()
        )
        persist(KEY_NEXT_WAKE_MINUTES, clamped.toString())

        val triggerAtMs = SystemClock.elapsedRealtime() + clamped * 60_000L
        val intent = Intent(ACTION_WAKE_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMs,
                pendingIntent
            )
        } else {
            // SCHEDULE_EXACT_ALARM 是 signature|privileged：manifest 声明 + privapp 白名单两条都齐了才拿得到
            // （2026-10-05 就是漏了白名单那条，system_server 起不来）。拿不到时不许静默降级——
            // 非精确闹钟在 Doze 下能晚几十分钟，"按时叫醒"这条设计就不成立了，所以报警并记审计。
            Log.e(TAG, "精确闹钟被拒（canScheduleExactAlarms=false），退化为非精确窗口，唤醒时间可能大幅偏后")
            auditLog.log("wake_exact_alarm_denied", "delay=${clamped}min")
            alarmManager.setWindow(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMs,
                clamped * 60_000L / 3,
                pendingIntent
            )
        }

        Log.i(TAG, "下次唤醒: ${clamped}min 后 (预计 ${formatEta(triggerAtMs)})")
        auditLog.log("wake_scheduled", "delay=${clamped}min")
    }

    /** 从 DB 恢复定时器（或首次启动随机设置） */
    private fun scheduleNextWakeFromDb() {
        val lastWakeTime = get(KEY_LAST_WAKE_TIME, "0").toLongOrNull() ?: 0L
        val nextWakeMinutes =
            get(KEY_NEXT_WAKE_MINUTES, "0").toIntOrNull() ?: 0

        if (lastWakeTime == 0L || nextWakeMinutes == 0) {
            // 首次启动——随机延迟后首次唤醒
            val initialDelay =
                Random.nextLong(WAKE_MIN_MINUTES, WAKE_MAX_MINUTES + 1)
            Log.i(TAG, "首次启动，${initialDelay}min 后首次唤醒")
            scheduleNextWake(initialDelay.toInt())
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMinutes = (now - lastWakeTime) / 60_000L

        if (elapsedMinutes >= nextWakeMinutes) {
            // 已经过点了 → 延迟几秒等系统稳定后立即触发
            Log.i(TAG, "已过唤醒时间 (${elapsedMinutes}min ≥ " +
                    "${nextWakeMinutes}min)，${COLD_START_WAKE_DELAY_MS}ms 后触发")
            scope.launch {
                delay(COLD_START_WAKE_DELAY_MS)
                triggerWake(WakeReason.SCHEDULED, isEmergency = false)
            }
        } else {
            // 还没到 → 从剩余时间继续
            val remaining = nextWakeMinutes - elapsedMinutes.toInt()
            Log.i(TAG, "恢复定时器: ${remaining}min 后唤醒")
            scheduleNextWake(remaining)
        }
    }

    private fun cancelWakeAlarm() {
        val intent = Intent(ACTION_WAKE_ALARM).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // ═══════════════════════════════════════
    // 内部：沉默冷却
    // ═══════════════════════════════════════

    /**
     * 检查是否处于沉默冷却期。
     *
     * 规则：
     *   - Cami 最后聊天 >4h → 进入冷却
     *   - 冷却期间非紧急唤醒被抑制
     *   - 紧急破冰（电量≤15%/CPU过热/日程）无视冷却
     */
    private fun isInSilenceCooldown(): Boolean {
        // 先检查是否需要开始冷却
        maybeStartSilenceCooldown()

        val silenceStart = get(KEY_SILENCE_START, "0").toLongOrNull() ?: 0L
        if (silenceStart == 0L) return false

        val now = System.currentTimeMillis()
        return now - silenceStart >= SILENCE_COOLDOWN_MS
    }

    /**
     * 检查是否满足沉默冷却的进入条件。
     * 条件：距上次聊天 ≥4h 且尚未标记冷却开始。
     */
    private fun maybeStartSilenceCooldown() {
        val lastChatTime =
            get(KEY_LAST_CHAT_TIME, "0").toLongOrNull() ?: 0L
        if (lastChatTime == 0L) return

        val now = System.currentTimeMillis()
        val elapsedHours = (now - lastChatTime) / 3600_000L

        if (elapsedHours >= SILENCE_COOLDOWN_HOURS) {
            if (get(KEY_SILENCE_START, "0").toLongOrNull() == 0L) {
                persist(KEY_SILENCE_START, now.toString())
                Log.i(TAG, "进入沉默冷却期 (${elapsedHours}h 未聊天)")
                auditLog.log("wake_silence_start", "${elapsedHours}h")
            }
        }
    }

    // ═══════════════════════════════════════
    // 内部：speak action 处理
    // ═══════════════════════════════════════

    /**
     * 处理安知的 speak action。
     *
     * BUILD.md Step 11 + 12：
     *   speak → 仪表盘卡片 + 锁屏文字
     *
     * Step 12 上线后：
     *   - 锁屏文字通过 AnzhiLockScreenBridge（KeyguardIndicationController API）
     *   - 仪表盘卡片通过 onSpeakRequested → AnzhiDashboardActivity
     *   - 通知栏只在仪表盘未在前台时降级使用
     *
     * Invariant 8：speak 不进通知栏——渲染到仪表盘卡片。
     * 通知栏仅作为仪表盘未激活时的降级过渡方案。
     */
    private fun handleSpeakAction(text: String, isEmergency: Boolean) {
        Log.i(TAG, "安知 speak: ${text.take(100)} (紧急=$isEmergency)")
        auditLog.log("wake_speak", text.take(200))

        // Step 12：锁屏文字（持久 + 瞬态）
        try {
            val lockScreen = AnzhiLockScreenBridge.getInstance(context)
            lockScreen.setPersistentMessage(text)
            lockScreen.showTransient(text)
        } catch (e: Exception) {
            Log.w(TAG, "锁屏桥接调用失败（非致命）: ${e.message}")
        }

        // 通知栏降级（仪表盘在前台时由仪表盘接管，不在时降级通知）
        showWakeNotification(text, isEmergency)

        // 通知 ManagerService → 仪表盘卡片（供 AnzhiDashboardActivity 接收）
        onSpeakRequested?.invoke(text, isEmergency)
    }

    /**
     * 显示唤醒通知。
     * 点击通知 → 启动 Chat Activity（Cami 点卡片 → 开主聊天窗口）。
     */
    private fun showWakeNotification(text: String, isEmergency: Boolean) {
        val intent = Intent().apply {
            setClassName(context.packageName, chatActivityClassName)
            putExtra("from_wake", true)
            putExtra("wake_text", text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priority = if (isEmergency) {
            Notification.PRIORITY_HIGH
        } else {
            Notification.PRIORITY_DEFAULT
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, WAKE_CHANNEL_ID)
                .setContentTitle("安知")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(priority)
                .setCategory(
                    if (isEmergency) Notification.CATEGORY_ALARM
                    else Notification.CATEGORY_RECOMMENDATION
                )
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("安知")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(priority)
                .build()
        }

        notificationManager.notify(WAKE_NOTIFICATION_ID, notification)
    }

    // ═══════════════════════════════════════
    // 内部：Session 跟踪（连续使用检测）
    // ═══════════════════════════════════════

    /**
     * 开始新的使用会话（屏幕亮起时）。
     * 启动协程轮询——每 60 秒检查一次连续使用是否超过 2h。
     */
    private fun beginSession() {
        sessionStartTime = System.currentTimeMillis()
        persist(KEY_SESSION_START_TIME, sessionStartTime.toString())

        // 取消旧 job
        continuousUseJob?.cancel()

        continuousUseJob = scope.launch(Dispatchers.IO) {
            while (isActive && running && isScreenOn) {
                delay(CONTINUOUS_USE_CHECK_INTERVAL_MS)
                checkContinuousUse()
            }
        }
    }

    private fun endSession() {
        sessionStartTime = 0L
        continuousUseJob?.cancel()
        continuousUseJob = null
    }

    private suspend fun checkContinuousUse() {
        val startTime =
            get(KEY_SESSION_START_TIME, "0").toLongOrNull() ?: return
        if (startTime == 0L) return

        val elapsedMinutes = (System.currentTimeMillis() - startTime) / 60_000L
        if (elapsedMinutes >= CONTINUOUS_USE_THRESHOLD_MIN) {
            Log.i(TAG, "连续使用 ${elapsedMinutes}min ≥ " +
                    "${CONTINUOUS_USE_THRESHOLD_MIN}min，触发事件唤醒")
            withContext(Dispatchers.Main) {
                triggerWake(WakeReason.CONTINUOUS_USE, isEmergency = false)
            }
        }
    }

    // ═══════════════════════════════════════
    // 内部：冷启动恢复（陷阱 8 防御）
    // ═══════════════════════════════════════

    /**
     * BOOT_COMPLETED / start() 时调用——检查冷启动真空期。
     *
     * 陷阱 8 防御完整逻辑：
     *   - 读取 last_wake_time + next_wake_minutes
     *   - now - last_wake_time > next_wake_minutes → 立即补唤醒
     *   - 否则 → 从剩余差值恢复 AlarmManager 倒计时
     *   - 该检查在 AnzhiManagerService.onCreate() 中通过 start() 最先执行
     *
     * 熔断保护（陷阱 15 延伸）：
     *   - WakeManager 的 SQLite 操作全部用 try-catch 包裹
     *   - 初始化失败不抛异常、不崩 system_server
     *   - 失败时记录到 audit log，用默认值继续
     */
    private fun recoverFromColdStart() {
        try {
            val lastWakeTime =
                get(KEY_LAST_WAKE_TIME, "0").toLongOrNull() ?: 0L
            val nextWakeMinutes =
                get(KEY_NEXT_WAKE_MINUTES, "0").toIntOrNull() ?: 0

            if (lastWakeTime == 0L || nextWakeMinutes == 0) {
                Log.i(TAG, "冷启动恢复：无历史唤醒记录（首次开机或 DB 为空）")
                return
            }

            val now = System.currentTimeMillis()
            val elapsedMinutes = (now - lastWakeTime) / 60_000L

            if (elapsedMinutes >= nextWakeMinutes) {
                Log.w(TAG, "冷启动恢复：已错过唤醒 " +
                        "(${elapsedMinutes}min > ${nextWakeMinutes}min)，" +
                        "${COLD_START_WAKE_DELAY_MS}ms 后补唤醒")
                auditLog.log("wake_cold_start_recovery",
                    "missed=${elapsedMinutes}min")

                scope.launch {
                    delay(COLD_START_WAKE_DELAY_MS)  // 等系统完全就绪
                    triggerWake(WakeReason.COLD_START_RECOVERY,
                        isEmergency = false)
                }
            } else {
                val remaining = nextWakeMinutes - elapsedMinutes.toInt()
                Log.i(TAG, "冷启动恢复：距下次唤醒还有 ${remaining}min，恢复倒计时")
                // scheduleNextWakeFromDb() 已在外层调用，这里只做日志
            }
        } catch (e: Exception) {
            // 陷阱 15 防御：初始化失败不抛异常、不崩 system_server
            Log.e(TAG, "冷启动恢复异常（非致命）: ${e.message}", e)
            auditLog.log("wake_cold_start_error",
                e.message?.take(200) ?: "unknown")
        }
    }

    // ═══════════════════════════════════════
    // 内部：BroadcastReceiver
    // ═══════════════════════════════════════

    private fun registerReceivers() {
        // Wake Alarm Receiver — 定时器触发
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_WAKE_ALARM) {
                    Log.i(TAG, "AlarmManager 定时器触发")
                    scope.launch {
                        triggerWake(WakeReason.SCHEDULED,
                            isEmergency = false)
                    }
                }
            }
        }
        context.registerReceiver(
            alarmReceiver,
            IntentFilter(ACTION_WAKE_ALARM),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Battery Receiver — 电量变化检测
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(
                        BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(
                        BatteryManager.EXTRA_SCALE, 100)
                    val status = intent.getIntExtra(
                        BatteryManager.EXTRA_STATUS, -1)

                    if (level >= 0 && scale > 0) {
                        val pct = level * 100 / scale
                        val charging =
                            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        onBatteryChanged(pct, charging)
                    }
                }
            }
        }
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    private fun unregisterReceivers() {
        try {
            alarmReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        alarmReceiver = null

        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        batteryReceiver = null
    }

    // ═══════════════════════════════════════
    // 内部：通知频道
    // ═══════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WAKE_CHANNEL_ID,
                WAKE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "安知的主动消息和提醒（对话邀请）"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
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
                "wake_state", null, values,
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
                "SELECT value FROM wake_state WHERE key = ?",
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

    /** 从 DB 恢复内存状态 */
    private fun restoreState() {
        currentAppSwitchCount =
            get(KEY_APP_SWITCH_COUNT, "0").toIntOrNull() ?: 0
        appSwitchWindowStart =
            get(KEY_APP_SWITCH_WINDOW_START, "0").toLongOrNull() ?: 0L
        sessionStartTime =
            get(KEY_SESSION_START_TIME, "0").toLongOrNull() ?: 0L
        lastApp = get(KEY_LAST_APP, "")
    }

    /** 距上次聊天的分钟数 */
    private fun computeMinutesSinceLastChat(): Long {
        val lastChatTime =
            get(KEY_LAST_CHAT_TIME, "0").toLongOrNull() ?: 0L
        if (lastChatTime == 0L) return 9999L  // 从未聊过
        return (System.currentTimeMillis() - lastChatTime) / 60_000L
    }

    // ═══════════════════════════════════════
    // 内部：辅助方法
    // ═══════════════════════════════════════

    /** 构建安知的提醒文字（根据紧急度和沉默状态变化） */
    private fun buildReminderText(
        isEmergency: Boolean,
        camiDismissed: Boolean,
        consecutiveSilence: Int
    ): String {
        val sb = StringBuilder()
        sb.append("安知，你可以选择是否给 Cami 发消息。" +
                  "看看她的状态，你自己决定。")

        if (isEmergency) {
            sb.append("（这是紧急情况——无需遵守沉默冷却，请立即判断是否需要行动。）")
        }

        if (camiDismissed) {
            sb.append(" 上次你的消息被 Cami 划掉了，她可能当时不想被打扰。")
        }

        if (consecutiveSilence > 1) {
            sb.append(" Cami 已经连续 $consecutiveSilence 次没有回应了，" +
                      "她可能很忙或在休息。")
        }

        return sb.toString()
    }

    private fun currentTimeString(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.HOUR_OF_DAY)}:" +
               String.format("%02d", cal.get(Calendar.MINUTE))
    }

    private fun currentDayOfWeek(): String {
        val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return days[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
    }

    /** 格式化 AlarmManager 触发时间为人可读的 HH:MM */
    private fun formatEta(elapsedRealtimeMs: Long): String {
        val remainingMs = elapsedRealtimeMs - SystemClock.elapsedRealtime()
        val remainingMin = remainingMs / 60_000L
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, remainingMin.toInt())
        return "${cal.get(Calendar.HOUR_OF_DAY)}:" +
               String.format("%02d", cal.get(Calendar.MINUTE))
    }

    // ═══════════════════════════════════════
    // 枚举
    // ═══════════════════════════════════════

    /** 唤醒原因——记录为什么叫醒安知 */
    enum class WakeReason {
        /** 定时器到期 */
        SCHEDULED,
        /** Cami 连续切换了 ≥5 个 App */
        APP_SWITCHING,
        /** Cami 连续使用手机超过 2 小时 */
        CONTINUOUS_USE,
        /** 电量低于 15%（紧急） */
        BATTERY_LOW,
        /** CPU 过热（紧急） */
        CPU_OVERHEAT,
        /** 日程即将开始（紧急） */
        CALENDAR_EVENT,
        /** 冷启动恢复——上次关机前设定的唤醒被错过了 */
        COLD_START_RECOVERY
    }

    // ═══════════════════════════════════════
    // SQLite Helper
    // ═══════════════════════════════════════

    /**
     * 唤醒状态数据库。
     *
     * 采用 key-value 单表设计（而非多张专用表）：
     *   - 唤醒状态字段少（~11 个 key），不需要规范化的多表结构
     *   - 新增 key 无需 migration，灵活性最高
     *   - SQLite 单行读写极快（< 1ms）
     *
     * WAL 模式 + busy_timeout=5000（陷阱 18 / 陷阱 22 防御）。
     */
    private class WakeDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE wake_state (
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
            // 未来版本按 BUILD.md 陷阱 22 累加 ALTER TABLE / 新增 key
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // 陷阱 18 防御：WAL 模式读写不互斥
            // PRAGMA 会返回结果行，execSQL 遇到返回行的语句直接抛
            // "Queries can be performed using SQLiteDatabase query or rawQuery methods only"，
            // 于是 onConfigure 整体失败 → 这个 helper 的每次读写都报错（唤醒状态从来没存下来过）。
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            // 忙等 5 秒不立即抛 database locked
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        }
    }
}
