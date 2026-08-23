package com.anzhi.os.chat

import android.content.Context
import android.util.Log
import com.anzhi.os.AnzhiSessionStore
import com.anzhi.os.AnzhiSocket
import com.anzhi.os.AnzhiAuditLog
import com.anzhi.os.cdp.*
import com.anzhi.os.model.MessageType
import com.anzhi.os.model.SocketMessage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安知聊天会话——消息流编排器。
 *
 * M1 换源后 Chat 界面核心逻辑：
 *   消息流：Cami 打字 → 冻结快照 → 直连 VPS /proxy_chat（VPS 全权注入 + 内部查记忆库）→
 *           chat_sync 整轮上传 VPS → 显示
 *
 * 职责：
 *   1. 管理 ConversationSnapshot（陷阱 23——冻结注入时刻的全部状态）
 *   2. chat_sync 等 WebSocket 消息收发
 *   3. 调用 AnzhiBrainSwitcher → VpsBrainProvider（主）/ GeminiCdpWebProvider（备）
 *   4. 输入锁定/解锁协调（陷阱 14——聊天异步真空期吞输入）
 *   5. today_log 管理（主聊发送即毁）
 *
 * 铁律：
 *   - Invariant 1: Cami 看到的文字只来自安知大脑（M1 默认 VPS 直连，备用 CDP 官网）
 *   - Invariant 3: WebSocket 只同步，不生文
 *   - Invariant 6: 所有 ID 由手机生成
 *
 * @param context Android Context
 * @param sessionStore 会话持久化
 * @param socket WebSocket 客户端（连 VPS）
 * @param brainSwitcher 安知大脑供应者（VPS 直连主 → CDP 备用自动切换）
 * @param scope 协程作用域（绑定到 Activity 生命周期）
 */
class AnzhiChatSession(
    private val context: Context,
    private val sessionStore: AnzhiSessionStore,
    private val socket: AnzhiSocket,
    private val brainSwitcher: AnzhiBrainSwitcher,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AnzhiChatSession"
        private const val CHAT_SYNC_TIMEOUT_MS = 10_000L
    }

    // ── 会话状态 ──

    /** 当前会话 ID */
    private var currentSessionId: String = ""

    /** 当前对话快照（陷阱 23——冻结后绝不读实时状态） */
    @Volatile
    private var activeSnapshot: ConversationSnapshot? = null

    /** 是否有正在进行的聊天请求 */
    private val isProcessing = AtomicBoolean(false)

    /** 本轮聊天是否被取消 */
    private val isCancelled = AtomicBoolean(false)

    /** 已发送到提醒窗口的 today_log 条目索引（用于互斥） */
    private val sentToWake = mutableSetOf<Int>()

    /** 内存中的 today_log（BUILD.md §3.6） */
    private val todayLogEntries = mutableListOf<TodayLogEntry>()

    /** 事件回调 */
    var onStateChanged: ((ChatState) -> Unit)? = null
    var onReplyReceived: ((anzhiReply: String, camiText: String) -> Unit)? = null
    var onBrainSwitched: ((reason: String) -> Unit)? = null
    var onCaptchaDetected: ((windowType: String, title: String) -> Unit)? = null
    var onGoogleSignedOut: ((windowType: String) -> Unit)? = null

    /** 当前状态 */
    enum class ChatState {
        IDLE,           // 等待输入
        LOCKED,         // 输入锁定（等待上一轮完成）
        SEARCHING,      // 正在搜索记忆库
        THINKING,       // 安知正在思考（CDP/Gemini API 调用中）
        REPLYING,       // 收到回复，正在渲染
        DISCONNECTED    // WebSocket 未连接
    }

    // ─────────────────────────────────────
    // 公开方法
    // ─────────────────────────────────────

    /** 初始化或恢复会话 */
    fun initSession(sessionId: String? = null) {
        currentSessionId = sessionId ?: UUID.randomUUID().toString()
        // 确保会话在 DB 中存在
        sessionStore.createSession(currentSessionId)
        Log.i(TAG, "聊天会话初始化: $currentSessionId")
    }

    /** 获取当前会话 ID */
    fun getSessionId(): String = currentSessionId

    /**
     * 加载当前会话的历史消息（JSON 数组字符串）。
     * 恢复会话时由 Activity 调用，灌回 WebView 渲染。
     * @return JSON 数组字符串: [{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
     */
    fun loadHistory(): String {
        if (currentSessionId.isEmpty()) return "[]"
        val json = sessionStore.getSessionHistoryJson(currentSessionId, 100)
        return json.toString()
    }

    /**
     * 切换到指定会话。
     * @param sessionId 目标会话 ID
     * @return 历史消息 JSON 数组字符串，供 WebView 重新渲染
     */
    fun switchSession(sessionId: String): String {
        currentSessionId = sessionId
        Log.i(TAG, "切换到会话: $sessionId")
        return loadHistory()
    }

    /** 检查是否正在处理中 */
    fun isBusy(): Boolean = isProcessing.get()

    /**
     * 处理 Cami 发送的消息——完整消息流。
     *
     * 流程（M1 主聊天）：
     *   1. 锁输入框（陷阱 14）
     *   2. 冻结 ConversationSnapshot（陷阱 23）
     *   3. 直连 VPS /proxy_chat（VPS 端全权注入上下文 + 内部查记忆库）
     *   4. 解锁输入框
     *   5. chat_sync 整轮上传 VPS
     *
     * @param userMessage Cami 原文
     * @param snapshotSource 快照数据源（由 Activity 从实时状态冻结）
     * @return Pair(anzhiReply, camiText) 或 null（失败时）
     */
    suspend fun processMessage(
        userMessage: String,
        snapshotSource: SnapshotSource
    ): Pair<String, String>? {
        if (isProcessing.getAndSet(true)) {
            Log.w(TAG, "已有进行中的请求，忽略新消息")
            return null
        }
        isCancelled.set(false)

        try {
            // Step 1: 锁输入 → 通知 UI
            emitState(ChatState.LOCKED)

            // Step 2: 冻结 ConversationSnapshot（陷阱 23）
            val snapshot = freezeSnapshot(snapshotSource)
            activeSnapshot = snapshot
            Log.d(TAG, "快照已冻结: time=${snapshot.timeDisplay}")

            // Step 3: 直连 VPS 大脑（M1）——VPS 端全权注入上下文 + 内部查记忆库(5003)。
            // 手机不再本地拼注入文本、不再发 memory_search。toolCalls 暂忽略（M1 不返回，V1 再做）。
            emitState(ChatState.THINKING)
            val chatResult = brainSwitcher.chat(snapshot, userMessage, 60)
                ?: return handleChatFailure()
            val reply = chatResult.text

            val camiText = userMessage  // Cami 原文就是传入的 userMessage
            val cleanReply = reply.trim()

            // Step 4: 解锁输入 → 通知 UI 显示回复
            emitState(ChatState.REPLYING)
            onReplyReceived?.invoke(cleanReply, camiText)

            // Step 5: chat_sync 整轮上传 VPS
            uploadChatSync(userMessage, cleanReply)

            // 标记 today_log 已发送（主聊发送即毁）
            markTodayLogSent()

            // 存会话历史
            sessionStore.createSession(currentSessionId)
            sessionStore.addMessage(currentSessionId, "user", userMessage)
            sessionStore.addMessage(currentSessionId, "assistant", cleanReply)

            Log.i(TAG, "聊天轮次完成: session=$currentSessionId, reply=${cleanReply.length} chars")
            return Pair(cleanReply, camiText)

        } catch (e: CancellationException) {
            Log.w(TAG, "聊天请求被取消")
            isCancelled.set(true)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "聊天处理异常: ${e.message}", e)
            return null
        } finally {
            isProcessing.set(false)
            activeSnapshot = null
            if (!isCancelled.get()) {
                emitState(ChatState.IDLE)
            }
        }
    }

    /** 取消当前正在处理的请求 */
    fun cancelCurrent() {
        isCancelled.set(true)
        Log.i(TAG, "取消当前聊天请求")
    }

    /** 添加 today_log 条目 */
    fun addTodayLog(desc: String) {
        val time = currentTimeStr()
        todayLogEntries.add(TodayLogEntry(time, desc))
        Log.d(TAG, "today_log 追加: [$time] $desc")
    }

    /** 获取未发送到提醒窗口的 today_log */
    fun getUnsentToWake(): List<TodayLogEntry> {
        return todayLogEntries.filterIndexed { index, _ -> index !in sentToWake }
    }

    /** 标记已发送到提醒窗口 */
    fun markSentToWake() {
        sentToWake.addAll(todayLogEntries.indices)
    }

    /** 销毁会话，释放资源 */
    fun destroy() {
        Log.i(TAG, "聊天会话销毁: $currentSessionId")
        cancelCurrent()
        activeSnapshot = null
        todayLogEntries.clear()
        sentToWake.clear()
    }

    // ─────────────────────────────────────
    // 内部：消息流各步骤
    // ─────────────────────────────────────

    /**
     * Step 2: 冻结对话快照。
     *
     * 陷阱 23：长按电源键唤起聊天的一瞬间，深拷贝冻结全部状态。
     * 本轮对话从此之后绝不读取任何实时可变状态。
     */
    private fun freezeSnapshot(source: SnapshotSource): ConversationSnapshot {
        return ConversationSnapshot(
            timeDisplay = buildTimeDisplay(),
            timestampMs = System.currentTimeMillis(),
            minutesSinceLastChat = source.minutesSinceLastChat,
            location = source.location,
            cami = CamiState(
                foregroundApp = source.foregroundApp,
                screenOn = source.screenOn,
                battery = source.battery
            ),
            todayLog = todayLogEntries.toList(),
            weather = source.weather,
            upcomingCalendar = source.upcomingCalendar,
            mood = source.mood,
            memoryHits = emptyList(),  // M1：VPS /proxy_chat 内部查记忆库，手机端不再填充
            extra = JSONObject()
        )
    }

    // Step 3 由 VPS /proxy_chat 内部完成（全权注入 + 查记忆库 5003），手机端不再有 searchMemory / buildInjectionContext

    /**
     * Step 8: chat_sync 整轮上传 VPS。
     *
     * Cami 原文 + 安知回复 + 本轮 today_log 一起上传。
     * Cami 原文 raw 显示，安知回复二次渲染后显示。
     */
    private suspend fun uploadChatSync(camiText: String, anzhiReply: String) {
        if (!socket.isConnected()) {
            Log.w(TAG, "WebSocket 未连接，chat_sync 暂存本地")
            return
        }

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("text", camiText)
                    put("render", "raw")
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("text", anzhiReply)
                    put("render", "filtered")
                })
            }

            val todayLogArray = JSONArray()
            todayLogEntries.takeLast(5).forEach { entry ->
                todayLogArray.put("[${entry.time}] ${entry.desc}")
            }

            val msg = SocketMessage(
                type = MessageType.CHAT_SYNC,
                payload = JSONObject().apply {
                    put("session_id", currentSessionId)
                    put("window", "main")
                    put("messages", messages)
                    put("today_log", todayLogArray)
                    put("time", java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ssXXX",
                        java.util.Locale.US
                    ).format(java.util.Date()))
                }
            )
            socket.send(msg)
            Log.d(TAG, "chat_sync 已发送: session=$currentSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "chat_sync 发送失败: ${e.message}")
        }
    }

    // ─────────────────────────────────────
    // 内部：上下文管理
    // ─────────────────────────────────────

    /** 标记 today_log 已发送（主聊发送即毁） */
    private fun markTodayLogSent() {
        // 主聊发送即毁——清空已发送的条目
        // 但保留给提醒窗口的互斥逻辑
        todayLogEntries.clear()
        sentToWake.clear()
    }

    /** 聊天失败处理 */
    private suspend fun handleChatFailure(): Pair<String, String>? {
        val statusMsg = brainSwitcher.getStatusMessage()
        if (statusMsg != null) {
            Log.w(TAG, "安知大脑切换: $statusMsg")
            onBrainSwitched?.invoke(statusMsg)
        }
        return null
    }

    // ─────────────────────────────────────
    // 内部：工具方法
    // ─────────────────────────────────────

    private fun emitState(state: ChatState) {
        onStateChanged?.invoke(state)
        Log.d(TAG, "状态: $state")
    }

    private fun buildTimeDisplay(): String {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val dayOfWeek = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            java.util.Calendar.SATURDAY -> "周六"
            java.util.Calendar.SUNDAY -> "周日"
            else -> ""
        }
        return "${String.format("%02d", hour)}:${String.format("%02d", minute)} $dayOfWeek"
    }

    private fun currentTimeStr(): String {
        val cal = java.util.Calendar.getInstance()
        return "${String.format("%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))}:" +
               "${String.format("%02d", cal.get(java.util.Calendar.MINUTE))}"
    }
}

/**
 * 快照数据源——由 Activity 在每次发送消息时冻结。
 * 不持有引用，只做数据传递。
 */
data class SnapshotSource(
    val minutesSinceLastChat: Int,
    val location: String,
    val foregroundApp: String,
    val screenOn: Boolean,
    val battery: Int,
    val weather: WeatherInfo?,
    val upcomingCalendar: List<CalendarEntry>,
    val mood: MoodInfo?
)
