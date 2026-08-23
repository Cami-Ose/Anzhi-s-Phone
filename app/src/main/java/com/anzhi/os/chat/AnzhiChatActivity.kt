package com.anzhi.os.chat

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemProperties
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.anzhi.os.AnzhiAuditLog
import com.anzhi.os.AnzhiSessionStore
import com.anzhi.os.AnzhiSocket
import com.anzhi.os.action.ActionExecutor
import com.anzhi.os.cdp.*
import com.anzhi.os.ui.chat.*
import com.anzhi.os.ui.theme.AnzhiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 安知聊天 Activity — Cami 长按电源键唤起的主聊天界面（Compose 版）。
 *
 * 原 WebView 版 → 纯 Kotlin Compose 迁移。
 * M1 换源：大脑从"官网 CDP"换成"直连 VPS API"，CDP 降为备用。
 *   - VPS 直连大脑（VpsBrainProvider，主）
 *   - CDP WebView 管理（WebViewManager → GeminiCdpWebProvider，备用）
 *   - 自动切换器（AnzhiBrainSwitcher）
 *   - 消息流编排（AnzhiChatSession）
 *   - WebSocket（AnzhiSocket）→ chat_sync / today_log
 *
 * 数据流：
 *   CDP/ChatSession 回调 → MutableStateFlow<ChatState> → Compose collectAsState() 自动重组
 *
 * 铁律：
 *   - Invariant 8: speak 不进通知栏——安知回复渲染在聊天界面，不弹通知
 */
class AnzhiChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnzhiChatActivity"

        // VPS WebSocket 地址默认值（SystemProperties persist.anzhi.server_url 未设置时兜底）
        private const val VPS_WS_URL_DEFAULT = "ws://your-vps-ip:8080/anzhi"

        fun launch(context: Context, sessionId: String? = null) {
            val intent = Intent(context, AnzhiChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (sessionId != null) {
                    putExtra("sessionId", sessionId)
                }
            }
            context.startActivity(intent)
        }
    }

    // ── 核心组件 ──

    private lateinit var sessionStore: AnzhiSessionStore
    private lateinit var auditLog: AnzhiAuditLog
    private lateinit var socket: AnzhiSocket
    private lateinit var webViewManager: WebViewManager
    /** CDP 备用供应者（CAPTCHA 解盾广播接收器需要用它重置状态） */
    private var cdpProvider: GeminiCdpWebProvider? = null
    /** M2：手机 DS 执行器（VPS 工具调用 → 手机操作 → 结果回传），仅用于日志/后续扩展 */
    private var dsExecutor: DsExecutor? = null
    private lateinit var brainSwitcher: AnzhiBrainSwitcher
    private lateinit var chatSession: AnzhiChatSession
    private lateinit var deviceId: String

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Compose 状态 ──

    private val _chatState = MutableStateFlow(ChatState())

    // ── 电池 ──

    private var batteryLevel = 50

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100 / scale)
            }
        }
    }

    /** 监听 CaptchaActivity 解盾完成广播（"com.anzhi.os.CAPTCHA_RESOLVED"）→ 重置 CDP 卡死状态 */
    private val captchaResolvedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "收到 CAPTCHA_RESOLVED 广播 → 恢复 CDP 可用")
            // 仅改布尔/Int 加日志，直接调即可
            cdpProvider?.onCaptchaResolved()
            cdpProvider?.resetFailures()
        }
    }

    // ─────────────────────────────────────
    // Activity 生命周期
    // ─────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "安知聊天 Activity 启动（Compose）")

        // 全屏 + 保持屏幕常亮
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 初始化基础设施
        initInfrastructure()

        // 初始化 CDP 组件
        initCdp()

        // 初始化聊天会话
        val sessionId = intent.getStringExtra("sessionId")
        initChatSession(sessionId)

        // 注册电池广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // ── Compose 内容 ──
        setContent {
            AnzhiTheme {
                ChatScreen(
                    state = _chatState.collectAsState().value,
                    onSendMessage = this::onSendMessage,
                    onTextChange = this::onTextChange,
                    onSwitchSession = this::onSwitchSession,
                    onNewSession = this::onNewSession,
                    onToggleSidebar = { toggleSidebar() },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "安知聊天 Activity 销毁")
        scope.cancel()
        chatSession.destroy()
        brainSwitcher.shutdown()
        webViewManager.unbindService()
        socket.disconnect()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(captchaResolvedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            Log.i(TAG, "电源键长按 → 已唤起聊天")
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // ─────────────────────────────────────
    // 初始化
    // ─────────────────────────────────────

    private fun initInfrastructure() {
        deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""

        sessionStore = AnzhiSessionStore(this)
        auditLog = AnzhiAuditLog(this)

        // VPS WebSocket 地址：SystemProperties 优先（persist.anzhi.server_url），未设置用默认
        val wsUrl = SystemProperties.get("persist.anzhi.server_url", VPS_WS_URL_DEFAULT)
        socket = AnzhiSocket(wsUrl, deviceId).also { s ->
            s.statusCallback = { status ->
                Log.i(TAG, "WS 状态: $status")
                if (status == "disconnected") {
                    chatSession.onStateChanged?.invoke(
                        AnzhiChatSession.ChatState.DISCONNECTED
                    )
                }
            }
        }
        socket.connect()
    }

    private fun initCdp() {
        webViewManager = WebViewManager(this)
        val cdpProvider = GeminiCdpWebProvider(webViewManager)
        this.cdpProvider = cdpProvider

        // VPS 直连大脑配置：SystemProperties 读取（persist.anzhi.api_url / persist.anzhi.token），缺省为空
        val vpsApiUrl = SystemProperties.get("persist.anzhi.api_url", "")
        val apiToken = SystemProperties.get("persist.anzhi.token", "")

        // M2：手机 DS 执行器——VPS 返回工具调用（action/launch_app/speak/pop_content/screenshot）
        // 手机执行 → 结果回传 VPS 续轮，直到拿到最终文本。onSpeak 接聊天界面渲染路径。
        val actionExecutor = ActionExecutor(
            context = this,
            displayId = 0,
            onSpeak = { text, voice, emotion ->
                Log.i(TAG, "安知说话(DS): $text (voice=$voice, emotion=$emotion)")
                // Invariant 8: speak 渲染进聊天界面，不弹通知
                scope.launch(Dispatchers.Main) { addAnzhiMessage(text) }
            }
        )
        val dsExecutor = DsExecutor(
            context = this,
            actionExecutor = actionExecutor,
            onPopContent = { title, text, html ->
                scope.launch(Dispatchers.Main) { showContentCard(title, text, html) }
            }
        )
        this.dsExecutor = dsExecutor

        val apiProvider = VpsBrainProvider(vpsApiUrl, apiToken, executor = dsExecutor)

        brainSwitcher = AnzhiBrainSwitcher(
            primaryProvider = apiProvider,
            fallbackProvider = cdpProvider,
            onSwitchToFallback = { reason ->
                Log.w(TAG, "大脑切换到备用: $reason")
                addSystemMessage("大脑切换到备用模式：$reason")
                _chatState.update { s -> s.copy(brainOnline = false) }
            },
            onSwitchBackToPrimary = {
                Log.i(TAG, "大脑切回主供应者")
                addSystemMessage("已恢复连接，我又回来啦~")
                _chatState.update { s -> s.copy(brainOnline = true) }
            }
        )

        webViewManager.onCaptchaDetected { windowType, title ->
            Log.w(TAG, "CAPTCHA 检测: $windowType - $title")
            addSystemMessage("Google 以为我是机器人…需要你在 Gemini 网页上点验证 🦓")
            _chatState.update { s -> s.copy(captchaRequired = true) }
        }

        webViewManager.onGoogleSignedOut { windowType ->
            Log.w(TAG, "Google 登出: $windowType")
            addSystemMessage("Google 账号登出了，需要重新登录一下~")
        }

        webViewManager.bindService()

        // 监听 CAPTCHA 解盾完成广播（CaptchaActivity 同包名作用域发送），重置 CDP 状态
        registerReceiver(captchaResolvedReceiver, IntentFilter("com.anzhi.os.CAPTCHA_RESOLVED"))
    }

    private fun initChatSession(sessionId: String?) {
        chatSession = AnzhiChatSession(
            context = this,
            sessionStore = sessionStore,
            socket = socket,
            brainSwitcher = brainSwitcher,
            scope = scope
        )

        chatSession.initSession(sessionId)

        // 状态回调 → StateFlow
        chatSession.onStateChanged = { state ->
            _chatState.update { s ->
                when (state) {
                    AnzhiChatSession.ChatState.LOCKED ->
                        s.copy(status = ChatStatus.LOCKED, inputLocked = true)
                    AnzhiChatSession.ChatState.IDLE ->
                        s.copy(status = ChatStatus.IDLE, inputLocked = false)
                    AnzhiChatSession.ChatState.THINKING ->
                        s.copy(status = ChatStatus.THINKING, inputLocked = true)
                    AnzhiChatSession.ChatState.DISCONNECTED ->
                        s.copy(status = ChatStatus.DISCONNECTED)
                    else -> s
                }
            }
        }

        // 回复回调 → 添加消息到 state
        chatSession.onReplyReceived = { anzhiReply, camiText ->
            // 先添加用户消息（如果存在）
            if (camiText.isNotBlank()) {
                addUserMessage(camiText)
            }
            // 添加安知回复
            addAnzhiMessage(anzhiReply)
            // 解锁输入
            _chatState.update { s ->
                s.copy(inputLocked = false, status = ChatStatus.IDLE)
            }
        }

        // 加载当前会话的历史消息
        loadHistory()
    }

    // ═══════════════════════════════════════
    // Compose 交互回调
    // ═══════════════════════════════════════

    private fun onSendMessage() {
        val text = _chatState.value.inputText.trim()
        if (text.isBlank()) return

        // 清空输入
        _chatState.update { s -> s.copy(inputText = "") }

        Log.d(TAG, "发送消息: ${text.take(50)}...")

        scope.launch {
            try {
                // 构建快照数据源
                val snapshotSource = SnapshotSource(
                    minutesSinceLastChat = estimateMinutesSinceLastChat(),
                    location = "小窝",
                    foregroundApp = "",
                    screenOn = true,
                    battery = batteryLevel,
                    weather = null,
                    upcomingCalendar = emptyList(),
                    mood = null
                )

                // 锁定输入并显示思考状态
                _chatState.update { s ->
                    s.copy(
                        inputLocked = true,
                        status = ChatStatus.THINKING
                    )
                }

                val result = chatSession.processMessage(text, snapshotSource)

                if (result == null) {
                    // 失败：解锁输入
                    _chatState.update { s ->
                        s.copy(
                            inputLocked = false,
                            status = ChatStatus.IDLE
                        )
                    }
                    addSystemMessage("安知暂时无法回复，请稍后再试…")
                }
                // 成功时 onReplyReceived 回调会自动处理
            } catch (e: Exception) {
                Log.e(TAG, "处理消息异常: ${e.message}", e)
                _chatState.update { s ->
                    s.copy(inputLocked = false, status = ChatStatus.IDLE)
                }
            }
        }
    }

    private fun onTextChange(text: String) {
        _chatState.update { s -> s.copy(inputText = text) }
    }

    private fun onSwitchSession(sessionId: String) {
        Log.i(TAG, "切换到会话: $sessionId")
        val historyJson = chatSession.switchSession(sessionId)

        // 清空当前消息并加载新会话历史
        _chatState.update { s ->
            s.copy(
                currentSessionId = sessionId,
                messages = parseHistoryFromJson(historyJson),
                sidebarOpen = false
            )
        }
    }

    private fun onNewSession() {
        Log.i(TAG, "新建会话")
        chatSession.destroy()
        chatSession.initSession(null)
        _chatState.update { s ->
            s.copy(
                messages = emptyList(),
                currentSessionId = chatSession.getSessionId(),
                sidebarOpen = false
            )
        }
    }

    private fun toggleSidebar() {
        _chatState.update { s ->
            if (!s.sidebarOpen) {
                // 打开侧栏时加载会话列表
                s.copy(
                    sidebarOpen = true,
                    sessions = loadSessionsFromStore()
                )
            } else {
                s.copy(sidebarOpen = false)
            }
        }
    }

    // ═══════════════════════════════════════
    // 消息管理
    // ═══════════════════════════════════════

    private fun addUserMessage(text: String) {
        val msg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}_user",
            role = MessageRole.USER,
            content = text
        )
        _chatState.update { s ->
            s.copy(messages = s.messages + msg)
        }
    }

    private fun addAnzhiMessage(text: String) {
        val msg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}_anzhi",
            role = MessageRole.ANZHI,
            content = text
        )
        _chatState.update { s ->
            s.copy(messages = s.messages + msg)
        }
    }

    private fun addSystemMessage(text: String) {
        val msg = ChatMessage(
            id = "msg_${System.currentTimeMillis()}_sys",
            role = MessageRole.SYSTEM,
            content = text
        )
        _chatState.update { s ->
            s.copy(messages = s.messages + msg)
        }
    }

    /** 从 JSON 解析历史消息 */
    private fun parseHistoryFromJson(json: String): List<ChatMessage> {
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = obj.optString("role", "user")
                val content = obj.optString("content", "")
                val id = "hist_${i}_${System.currentTimeMillis()}"
                list.add(ChatMessage(
                    id = id,
                    role = if (role == "user") MessageRole.USER else MessageRole.ANZHI,
                    content = content
                ))
            }
            return list
        } catch (e: Exception) {
            Log.w(TAG, "解析历史消息异常: ${e.message}")
            return emptyList()
        }
    }

    /** 加载当前会话历史 */
    private fun loadHistory() {
        val historyJson = chatSession.loadHistory()
        val messages = parseHistoryFromJson(historyJson)
        _chatState.update { s ->
            s.copy(
                messages = messages,
                currentSessionId = chatSession.getSessionId()
            )
        }
    }

    /** 从 SessionStore 加载会话列表 */
    private fun loadSessionsFromStore(): List<ChatSessionInfo> {
        return try {
            val sessions = sessionStore.listSessions(0, 20)
            sessions.map { s ->
                ChatSessionInfo(
                    sessionId = s.sessionId,
                    title = s.title,
                    updatedAt = s.updatedAt,
                    messageCount = s.messageCount
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载会话列表异常: ${e.message}")
            emptyList()
        }
    }

    // ═══════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════

    /**
     * M2 pop_content：弹内容卡片（Dialog）。
     * 主线程调用（DsExecutor 的 onPopContent 已 scope.launch(Dispatchers.Main) 转发）。
     * html 非空 → WebView loadData 渲染；否则 TextView 纯文本。M3 再做精美版。
     */
    private fun showContentCard(title: String?, text: String?, html: String?) {
        try {
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (density * v).toInt()

            val dialog = android.app.Dialog(this)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setBackgroundColor(android.graphics.Color.WHITE)
            }

            title?.takeIf { it.isNotBlank() }?.let { t ->
                card.addView(android.widget.TextView(this).apply {
                    this.text = t
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#202124"))
                    setPadding(0, 0, 0, dp(12))
                })
            }

            if (html != null && html.isNotBlank()) {
                card.addView(android.webkit.WebView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(520)
                    )
                    settings.javaScriptEnabled = true
                    settings.textZoom = 100
                    background = null
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                })
            } else {
                text?.takeIf { it.isNotBlank() }?.let { t ->
                    card.addView(android.widget.TextView(this).apply {
                        this.text = t
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#3c4043"))
                        setLineSpacing(0f, 1.2f)
                        setPadding(0, 0, 0, dp(8))
                    })
                }
            }

            card.addView(android.widget.Button(this).apply {
                this.text = "关闭"
                setOnClickListener { dialog.dismiss() }
            })

            dialog.setContentView(card)
            val params = android.view.WindowManager.LayoutParams()
            params.copyFrom(dialog.window?.attributes)
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            dialog.window?.attributes = params
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "弹内容卡片失败: ${e.message}")
        }
    }

    private fun estimateMinutesSinceLastChat(): Int {
        return try {
            val sessions = sessionStore.listSessions(0, 1)
            if (sessions.isNotEmpty()) {
                val lastTime = sessions.first().updatedAt
                ((System.currentTimeMillis() - lastTime) / 60_000).toInt()
            } else 240
        } catch (e: Exception) {
            240
        }
    }
}
