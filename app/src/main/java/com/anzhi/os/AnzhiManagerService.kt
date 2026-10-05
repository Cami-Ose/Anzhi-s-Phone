package com.anzhi.os

import android.anzhi.IAnzhiCoreService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemProperties
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.anzhi.os.cdp.VpsBrainProvider
import com.anzhi.os.cc.CcLoopResult
import com.anzhi.os.cc.CcProgressCallback
import com.anzhi.os.cc.CcProvider
import com.anzhi.os.cc.CcToolCall
import com.anzhi.os.cc.DeepSeekCcProvider
import com.anzhi.os.cc.GeminiCcProvider
import com.anzhi.os.llm.DeepSeekClient
import com.anzhi.os.llm.GeminiClient
import com.anzhi.os.model.MessageType
import com.anzhi.os.model.SocketMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 安知手机 系统级管理服务。
 *
 * 基于 AAOSP LlmManagerService 的设计模式，适配安知架构：
 *   - 抄 AAOSP：onBootPhase 注册（此处用 Service.onCreate）、sessionStore、AIDL 暴露
 *   - 不抄：Qwen 本地推理 → WebSocket 连 VPS
 *   - 不抄：MCP 工具调用 → OpenCyvis ActionExecutor
 *   - 不抄：弹窗 HITL → 安知对话式同意
 *
 * 生命周期：
 *   - 开机自启（BOOT_COMPLETED）或由 AnzhiLauncher bindService 触发
 *   - 前台 Service，START_STICKY，永不死亡
 *
 * AIDL 调用链：
 *   Launcher → IAnzhiService.submit()
 *     → AnzhiManagerService → AnzhiSocket → VPS (DeepSeek/Gemini)
 *     → VPS 返回 tool_calls → ActionExecutor 执行 → 结果回 VPS
 *     → VPS 返回最终回复 → IAnzhiService.submit() 返回
 */
class AnzhiManagerService : Service() {

    companion object {
        private const val TAG = "AnzhiManagerService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "anzhi_manager"
        private const val ANZHI_SERVER_URL_DEFAULT = "ws://your-vps-ip:8080/anzhi"
        private const val PREF_KEY_SERVER_URL = "anzhi_server_url"
        private const val DEFAULT_MAX_TOOL_CALLS = 5
        private const val REQUEST_TIMEOUT_SEC = 120L

        /** 安知 speak → 仪表盘卡片的进程内广播（只发给自己） */
        const val ACTION_ANZHI_SPEAK = "com.anzhi.os.action.ANZHI_SPEAK"

        // ── AnzhiCore 系统服务组件（显式 bind 用；见 bindAnzhiCore） ──
        private const val CORE_PACKAGE = "com.anzhi.core"
        private const val CORE_SERVICE_CLASS = "com.anzhi.core.AnzhiCoreService"

        // ── CC API Key 系统属性名 ──
        private const val PROP_DEEPSEEK_KEY = "persist.vendor.anzhi.deepseek_key"
        private const val PROP_GEMINI_KEY = "persist.vendor.anzhi.gemini_key"

        /**
         * 读取系统属性（AOSP @hide API，需 platform_apis）。
         * 返回属性值，空字符串表示未设置。
         */
        private fun getSystemProperty(name: String): String {
            return SystemProperties.get(name, "")
        }

        // ── 唤醒系统的事件入口（README §九）──
        // AnzhiWakeManager 只活在 Service 实例里，但喂事件的两方（无障碍服务读窗口、
        // 聊天页发消息）拿不到 Service 的绑定。这里放一个进程内的弱引用点，
        // Service onCreate 赋值、onDestroy 清空；没有实例时调用方什么都不做。
        @Volatile
        private var wakeRef: AnzhiWakeManager? = null

        // ── AnzhiCore 的 AIDL 代理（bind 到手就缓存，全进程共享）──
        // 为什么不再查 ServiceManager.getService("anzhi_core")：AnzhiCoreService 是普通
        // app uid，addService 必被拒（见它的 onCreate），ServiceManager 名单里永远不会有
        // 这个名字 —— 查它 = 永远 null = 一条看起来像"还没连上"的假降级。
        // 真路径是 bindService：onBind 返回的就是同一个 binder，跨进程不需要 servicemanager。
        @Volatile
        var coreProxy: IAnzhiCoreService? = null
            private set

        fun feedForegroundApp(packageName: String?) {
            if (!packageName.isNullOrBlank()) wakeRef?.onAppSwitched(packageName)
        }

        fun feedCamiChatted() {
            wakeRef?.onCamiChatted()
        }
    }

    // ── 核心组件 ──

    private lateinit var sessionStore: AnzhiSessionStore
    private lateinit var deviceId: String
    private lateinit var auditLog: AnzhiAuditLog

    /** WebSocket 连接（延迟初始化，等 VPS 地址配置好） */
    private var socket: AnzhiSocket? = null

    /** 与 AnzhiCore 的绑定；非 null 表示已 bind，销毁时要 unbind */
    private var anzhiCoreConnection: ServiceConnection? = null

    // ── 唤醒自触发（README §九）──
    // scope 绑 Service 生命周期：onCreate 起、onDestroy 收。
    private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeManager: AnzhiWakeManager? = null
    private var screenReceiver: BroadcastReceiver? = null

    /** 待处理的请求：requestId → 响应 latch */
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /** 当前活跃会话 ID → 是否取消 */
    private val activeSessionCancelled = ConcurrentHashMap<String, AtomicBoolean>()

    // ── CC (Code Creation) 双模型供应者 ──

    /** DeepSeek CC 供应者（分析/分类/后端代码，便宜） */
    private var deepSeekCcProvider: DeepSeekCcProvider? = null

    /** Gemini CC 供应者（前端/UI/通用代码，Gemini 3.5 Flash） */
    private var geminiCcProvider: GeminiCcProvider? = null

    /** CC 供应者列表（用于路由选择） */
    private val ccProviders = mutableListOf<CcProvider>()

    // ── Binder ──

    private val binder = object : IAnzhiService.Stub() {

        override fun submit(prompt: String, options: Bundle?): String {
            enforceSubmitPermission()
            val opts = options ?: Bundle()

            val sessionId = opts.getString("sessionId")
                ?: sessionStore.createSession(null, Binder.getCallingUid())
            val maxToolCalls = opts.getInt("maxToolCalls", DEFAULT_MAX_TOOL_CALLS)

            // 取消信号复位
            activeSessionCancelled[sessionId] = AtomicBoolean(false)

            try {
                return doSubmit(sessionId, prompt, maxToolCalls)
            } finally {
                activeSessionCancelled.remove(sessionId)
            }
        }

        override fun getSessionHistory(sessionId: String): String {
            enforceSubmitPermission()
            return sessionStore.getSessionHistoryJson(sessionId, 100).toString()
        }

        override fun cancel() {
            enforceSubmitPermission()
            // 取消所有活跃会话
            activeSessionCancelled.values.forEach { it.set(true) }
            Log.i(TAG, "取消所有活跃请求")
        }

        override fun endSession(sessionId: String) {
            enforceSubmitPermission()
            sessionStore.deleteSession(sessionId, Binder.getCallingUid())
            activeSessionCancelled.remove(sessionId)
            Log.i(TAG, "结束会话: $sessionId")
        }

        override fun listSessions(limit: Int): String {
            enforceSubmitPermission()
            val uid = Binder.getCallingUid()
            val sessions = sessionStore.listSessions(uid, limit)
            val arr = JSONArray()
            for (s in sessions) {
                arr.put(JSONObject().apply {
                    put("sessionId", s.sessionId)
                    put("title", s.title)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                    put("messageCount", s.messageCount)
                })
            }
            return arr.toString()
        }
    }

    // ─────────────────────────────────────
    // Service 生命周期
    // ─────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "安知 Manager Service 启动")

        // 初始化存储
        sessionStore = AnzhiSessionStore(this)
        auditLog = AnzhiAuditLog(this)

        // 设备标识
        deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )

        // ── 初始化 CC 供应者 ──
        initCcProviders()

        // 前台 Service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 全局水印：解锁后、桌面上、任何应用之上都要看得见这是安知的机器
        showWatermark()

        // 连接安知后端
        connectToServer()

        // 把 AnzhiCore 拉起来（它的 addService 只在自己的 onCreate 里跑，没人 bind 就永远不注册）
        bindAnzhiCore()

        // 唤醒自触发系统（定时 / 连切 App / 电量 / 亮屏）
        startWakeSystem()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        unbindAnzhiCore()
        stopWakeSystem()
        hideWatermark()
        socket?.disconnect()
        Log.i(TAG, "安知 Manager Service 停止")
        super.onDestroy()
    }

    // ─────────────────────────────────────
    // AnzhiCore 系统服务：把它拉起来
    // ─────────────────────────────────────

    /**
     * 把 AnzhiCore 拉起来并保持连接。
     *
     * 它是个普通 Service，没有任何一方 bind/start 它的 onCreate 就不会跑；
     * 而它 onCreate 里的 ServiceManager.addService("anzhi_core") 在普通 app uid 下一定失败
     * （已实测：注册不了，且曾经因此让 :core 进程每 1000ms 崩一次）。
     * 所以本函数真正的作用不是"等它注册进 ServiceManager"，而是走 bindService 拿到 IBinder
     * 存进 [coreProxy] —— 这是唯一一条能用到 AnzhiCore 能力的路，保持到本 Service 销毁。
     */
    private fun bindAnzhiCore() {
        if (anzhiCoreConnection != null) return
        val intent = Intent().setComponent(ComponentName(CORE_PACKAGE, CORE_SERVICE_CLASS))
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                // bind 成功 → 立刻把这个 IBinder 转成代理存进 companion，ActionExecutor 读它。
                coreProxy = IAnzhiCoreService.Stub.asInterface(service)
                // pingBinder 是唯一能证明"这个代理真能打通远端"的廉价手段；
                // asInterface 本身不校验，返回一个能调但可能已死的代理。
                val alive = try {
                    service.pingBinder()
                } catch (e: Exception) {
                    Log.e(TAG, "pingBinder(AnzhiCore) 异常: ${e.message}")
                    false
                }
                if (alive) {
                    Log.i(TAG, "AnzhiCore 已连上，AIDL 代理可用（pingBinder=true）")
                } else {
                    // 连上但打不通：不能当没事发生，调用方会掉到 InputManager 降级
                    Log.e(TAG, "AnzhiCore 已连上但 binder 不响应（记审计）")
                    auditLog.log("anzhi_core_proxy_dead", "bound=$name")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // :core 进程死了，缓存的代理必须清掉 —— 留着它，调用方会一直对死 binder 发请求
                coreProxy = null
                Log.e(TAG, "AnzhiCore 进程断开，缓存的 AIDL 代理已失效（记审计）")
                auditLog.log("anzhi_core_disconnected", "name=$name")
            }
        }
        val bound = try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService(AnzhiCore) 抛异常: ${e.message}")
            auditLog.log("anzhi_core_bind_threw", e.message ?: "unknown")
            false
        }
        if (!bound) {
            Log.e(TAG, "AnzhiCore bind 失败：这条降级仍在（输入注入继续走 InputManager 隐藏 API）")
            auditLog.log("anzhi_core_bind_failed", "package=$CORE_PACKAGE")
            return
        }
        anzhiCoreConnection = connection
    }

    private fun unbindAnzhiCore() {
        coreProxy = null
        val connection = anzhiCoreConnection ?: return
        anzhiCoreConnection = null
        try {
            unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbindService(AnzhiCore) 异常: ${e.message}")
        }
    }

    // ─────────────────────────────────────
    // 唤醒自触发（README §九）
    // ─────────────────────────────────────

    private fun startWakeSystem() {
        val wm = AnzhiWakeManager(this, wakeScope, auditLog)
        wm.onWakeTriggered = { ctx -> runWakeRound(ctx) }
        wm.onSpeakRequested = { text, isEmergency -> broadcastSpeak(text, isEmergency) }
        wakeManager = wm
        wakeRef = wm
        wm.start()

        // 亮/熄屏是唤醒的会话输入（连续使用 2h 那条），WakeManager 自己只注册了闹钟和电量
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> wakeManager?.onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> wakeManager?.onScreenOff()
                }
            }
        }
        try {
            registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            )
            screenReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "亮/熄屏监听注册失败，连续使用那条唤醒时机不可用: ${e.message}")
            auditLog.log("wake_screen_receiver_failed", e.message ?: "")
        }
    }

    private fun stopWakeSystem() {
        wakeRef = null
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        screenReceiver = null
        wakeManager?.stop()
        wakeManager = null
    }

    /**
     * 一轮唤醒：把 WakeManager 组装好的上下文发给 VPS 的安知，取回 actions JSON。
     * 无论成功、失败还是回复不是 JSON，都必须走 onWakeResponse 把互斥锁放开并排下次闹钟，
     * 否则一次网络抖动就能让唤醒系统永久卡死。
     */
    private fun runWakeRound(wakeContext: JSONObject) {
        val url = VpsConfig.apiUrl(this)
        val token = VpsConfig.apiToken(this)
        if (url.isBlank() || token.isBlank()) {
            Log.w(TAG, "唤醒轮次没有发出：VPS 地址或令牌还没在设置界面填")
            auditLog.log("wake_skipped_unconfigured", "url_blank=${url.isBlank()} token_blank=${token.isBlank()}")
            wakeManager?.onWakeResponse(JSONObject())
            return
        }
        val wm = wakeManager ?: return
        wakeScope.launch {
            val provider = VpsBrainProvider(url, token)
            val reply = try {
                provider.wakeWithContext(wakeContext)
            } catch (e: Exception) {
                Log.e(TAG, "唤醒轮次调用失败: ${e.message}")
                null
            }
            val parsed = reply?.let { extractJsonObject(it) }
            if (parsed == null) {
                Log.e(TAG, "安知的唤醒回复不是 actions JSON，本轮按\"什么都不做\"处理。原文=${reply?.take(200) ?: "null"}")
                auditLog.log("wake_reply_unparsed", (reply ?: "null").take(200))
                wm.onWakeResponse(JSONObject())
            } else {
                wm.onWakeResponse(parsed) { type, text ->
                    Log.w(TAG, "唤醒 action「$type」还没有执行端接线（text=${text.take(60)}）——记在欠账里")
                    auditLog.log("wake_action_not_wired", "type=$type text=${text.take(120)}")
                }
            }
        }
    }

    /** 模型常把 JSON 包在 ```json 围栏或前后废话里，这里只取第一个 { 到最后一个 } */
    private fun extractJsonObject(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    /** speak → 仪表盘卡片。锁屏文字和通知栏降级由 WakeManager 自己做了。 */
    private fun broadcastSpeak(text: String, isEmergency: Boolean) {
        try {
            sendBroadcast(
                Intent(ACTION_ANZHI_SPEAK)
                    .setPackage(packageName)
                    .putExtra("text", text)
                    .putExtra("emergency", isEmergency)
            )
        } catch (e: Exception) {
            Log.w(TAG, "speak 广播没发出去（非致命，通知栏卡片已发）: ${e.message}")
        }
    }

    // ─────────────────────────────────────
    // 全局水印（TYPE_APPLICATION_OVERLAY，盖在桌面与所有应用之上）
    // ─────────────────────────────────────

    private var watermarkView: TextView? = null

    private fun showWatermark() {
        if (watermarkView != null) return
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "enzosphere"
            setTextColor(android.graphics.Color.WHITE)
            alpha = 0.42f
            textSize = 12f
            letterSpacing = 0.18f
            // TextView 只有 getShadowRadius/getShadowColor 的 getter，没有独立 setter，
            // Kotlin 属性是 val；阴影必须走 setShadowLayer(radius, dx, dy, color)
            setShadowLayer(2f, 0f, 0f, 0x80000000.toInt())
            typeface = try {
                resources.getFont(R.font.fusion_pixel)
            } catch (_: Exception) {
                Typeface.DEFAULT_BOLD
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (16 * density).toInt()
            y = (96 * density).toInt()
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            watermarkView = view
            Log.i(TAG, "全局水印已挂上")
        } catch (e: Exception) {
            Log.w(TAG, "全局水印挂载失败: ${e.message}")
        }
    }

    private fun hideWatermark() {
        watermarkView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        watermarkView = null
    }

    // ─────────────────────────────────────
    // 核心：submit → VPS → 工具调用 → 返回
    // ─────────────────────────────────────

    /**
     * 提交对话的核心逻辑。
     *
     * 流程（对应 AAOSP runChain）：
     *   1. 创建/继续会话
     *   2. 存用户消息
     *   3. 加载历史注入 prompt
     *   4. 发 WebSocket 给 VPS
     *   5. 等待 VPS 返回（可能是 tool_call 或 final_response）
     *   6. 如果是 tool_call → 执行 → 发结果回 VPS → 回到步骤 5
     *   7. 如果是 final_response → 存回复 → 返回
     */
    private fun doSubmit(sessionId: String, prompt: String, maxToolCalls: Int): String {
        // 1. 确保会话存在
        sessionStore.createSession(sessionId, Binder.getCallingUid())

        // 2. 存用户消息
        sessionStore.addMessage(sessionId, "user", prompt)
        sessionStore.updateSessionTitle(sessionId, prompt.take(50))
        auditLog.log("user_prompt", prompt)

        // 3. 加载历史
        val history = sessionStore.getSessionHistory(sessionId, 20)
        val historyJson = sessionStore.getSessionHistoryJson(sessionId, 20)

        // 4. 构造发给 VPS 的请求
        val requestId = UUID.randomUUID().toString()
        val requestPayload = JSONObject().apply {
            put("request_id", requestId)
            put("session_id", sessionId)
            put("device_id", deviceId)
            put("prompt", prompt)
            put("history", historyJson)
            put("max_tool_calls", maxToolCalls)
        }

        // 5. 发送到 VPS（同步等待响应）
        val ws = socket
        if (ws == null || !ws.isConnected()) {
            return errorResponse("WebSocket 未连接，请稍后再试")
        }

        // 注册等待器
        val latch = CountDownLatch(1)
        val responseRef = AtomicReference<String>()

        val pending = PendingRequest(
            requestId = requestId,
            sessionId = sessionId,
            maxToolCalls = maxToolCalls,
            toolCallCount = 0,
            latch = latch,
            responseRef = responseRef
        )
        pendingRequests[requestId] = pending

        try {
            // 发送请求
            ws.send(SocketMessage(
                type = MessageType.USER_MESSAGE,
                payload = requestPayload
            ))

            // 等待响应（超时 120 秒）
            val finished = latch.await(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                auditLog.log("request_timeout", requestId)
                return errorResponse("请求超时（${REQUEST_TIMEOUT_SEC}秒）")
            }

            val response = responseRef.get()
                ?: return errorResponse("未收到响应")

            // 解析最终响应
            val result = JSONObject(response)
            val finalText = result.optString("response", "")
            val status = result.optString("status", "done")

            // 存助手回复
            if (finalText.isNotBlank()) {
                sessionStore.addMessage(sessionId, "assistant", finalText)
                auditLog.log("assistant_response", finalText.take(200))
            }

            return JSONObject().apply {
                put("sessionId", sessionId)
                put("response", finalText)
                put("status", status)
                put("toolCallsExecuted", pending.toolCallCount)
            }.toString()

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            auditLog.log("request_interrupted", requestId)
            return errorResponse("请求被中断")
        } catch (e: Exception) {
            Log.e(TAG, "submit 异常", e)
            auditLog.log("request_error", "${requestId}: ${e.message}")
            return errorResponse("内部错误: ${e.message}")
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    // ─────────────────────────────────────
    // 工具调用处理
    // ─────────────────────────────────────

    /**
     * 处理 VPS 发来的 tool_call 指令。
     *
     * VPS 发来的格式（对应 OpenCyvis Action.fromMap）：
     * {
     *   "request_id": "...",
     *   "tool": {
     *     "action_type": "tap",
     *     "x": 500, "y": 300,
     *     "thought": "点击发送按钮"
     *   }
     * }
     */
    private fun handleToolCall(msg: SocketMessage, pending: PendingRequest) {
        val requestId = msg.payload.optString("request_id", "")
        if (requestId != pending.requestId) return

        // 检查取消
        if (isCancelled(pending.sessionId)) {
            pending.responseRef.set(errorResponse("已取消"))
            pending.latch.countDown()
            return
        }

        // 检查工具调用次数上限
        pending.toolCallCount++
        if (pending.toolCallCount > pending.maxToolCalls) {
            Log.w(TAG, "超过最大工具调用次数（${pending.maxToolCalls}）")
            // 发送回 VPS，让 LLM 做最终回复
            socket?.send(SocketMessage(
                type = MessageType.TOOL_RESULT,
                payload = JSONObject().apply {
                    put("request_id", requestId)
                    put("session_id", pending.sessionId)
                    put("result", JSONObject().apply {
                        put("error", "max_tool_calls_reached")
                        put("message", "已达到最大工具调用次数")
                    })
                }
            ))
            return
        }

        val toolJson = msg.payload.optJSONObject("tool") ?: return
        val actionType = toolJson.optString("action_type", "unknown")
        val thought = toolJson.optString("thought", "")

        Log.i(TAG, "执行工具调用 #${pending.toolCallCount}: $actionType — $thought")

        // 审计
        auditLog.log("tool_call_$actionType", toolJson.toString())

        // 执行动作
        val startTime = System.currentTimeMillis()
        val (success, resultText) = executeAction(actionType, toolJson)
        val latency = System.currentTimeMillis() - startTime

        // 存工具消息
        sessionStore.addToolMessage(
            sessionId = pending.sessionId,
            toolName = actionType,
            argsJson = toolJson.toString(),
            resultJson = resultText
        )

        if (success) {
            sessionStore.recordToolSuccess(actionType, "android", latency)
        } else {
            sessionStore.recordToolError(actionType, "android", resultText)
        }

        // 发结果回 VPS
        socket?.send(SocketMessage(
            type = MessageType.TOOL_RESULT,
            payload = JSONObject().apply {
                put("request_id", requestId)
                put("session_id", pending.sessionId)
                put("iteration", pending.toolCallCount)
                put("action_type", actionType)
                put("success", success)
                put("result", resultText)
                put("latency_ms", latency)
            }
        ))
    }

    /**
     * 处理 VPS 发来的最终回复。
     */
    private fun handleFinalResponse(msg: SocketMessage, pending: PendingRequest) {
        val requestId = msg.payload.optString("request_id", "")
        if (requestId != pending.requestId) return

        val response = msg.payload.optString("response", "")
        pending.responseRef.set(JSONObject().apply {
            put("response", response)
            put("status", "done")
            put("tool_calls", pending.toolCallCount)
        }.toString())
        pending.latch.countDown()
    }

    // ─────────────────────────────────────
    // 动作执行器（Phase 2 正式接入）
    // ─────────────────────────────────────

    /**
     * 执行单个动作。
     *
     * Phase 2 之后会替换为 ActionExecutor.execute()。
     * 当前先用 shell 命令兜底。
     *
     * @return Pair(success, description)
     */
    private fun executeAction(actionType: String, args: JSONObject): Pair<Boolean, String> {
        // 尝试用反射加载 ActionExecutor（Phase 2 产物）
        try {
            return executeViaActionExecutor(actionType, args)
        } catch (_: ClassNotFoundException) {
            // ActionExecutor 尚未编译，使用 shell 兜底
        } catch (e: Exception) {
            Log.w(TAG, "ActionExecutor 调用失败，回退到 shell: ${e.message}")
        }

        // 兜底：shell 命令
        return executeViaShell(actionType, args)
    }

    /** 通过 ActionExecutor 执行（Phase 2） */
    private fun executeViaActionExecutor(actionType: String, args: JSONObject): Pair<Boolean, String> {
        // Phase 2 集成后，这里会调用：
        //   val action = AnzhiAction.fromMap(args.toMap())
        //   val result = actionExecutor.execute(action, step)
        //   return Pair(result.success, result.detail)
        throw ClassNotFoundException("ActionExecutor 尚未实现（Phase 2）")
    }

    /** shell 命令兜底执行 */
    private fun executeViaShell(actionType: String, args: JSONObject): Pair<Boolean, String> {
        return try {
            when (actionType) {
                "tap" -> {
                    val x = args.getInt("x")
                    val y = args.getInt("y")
                    exec("input tap $x $y")
                    true to "点击 ($x, $y)"
                }
                "swipe" -> {
                    val x1 = args.getInt("x1")
                    val y1 = args.getInt("y1")
                    val x2 = args.getInt("x2")
                    val y2 = args.getInt("y2")
                    val duration = args.optInt("duration", 300)
                    exec("input swipe $x1 $y1 $x2 $y2 $duration")
                    true to "滑动 ($x1,$y1) → ($x2,$y2)"
                }
                "long_press" -> {
                    val x = args.getInt("x")
                    val y = args.getInt("y")
                    val duration = args.optInt("duration", 1000)
                    exec("input swipe $x $y $x $y $duration")
                    true to "长按 ($x, $y)"
                }
                "type_text" -> {
                    val text = args.optString("text", "")
                    val escaped = text.replace("'", "'\\''")
                    exec("input text '$escaped'")
                    true to "输入: $text"
                }
                "key_event" -> {
                    val key = args.optString("key", "back")
                    val code = keyToCode(key)
                    exec("input keyevent $code")
                    true to "按键: $key (code=$code)"
                }
                "open_app" -> {
                    val appName = args.optString("app_name", "")
                    exec("monkey -p $appName -c android.intent.category.LAUNCHER 1")
                    true to "打开: $appName"
                }
                "screencap" -> {
                    val path = "/sdcard/anzhi_screen.png"
                    exec("screencap -p $path")
                    val bytes = java.io.File(path).readBytes()
                    val base64 = android.util.Base64.encodeToString(
                        bytes, android.util.Base64.NO_WRAP
                    )
                    true to base64
                }
                "wait" -> {
                    Thread.sleep(2000)
                    true to "等待 2 秒"
                }
                else -> false to "未知动作类型: $actionType"
            }
        } catch (e: Exception) {
            false to "执行失败: ${e.message}"
        }
    }

    /** 键名 → keycode 映射 */
    private fun keyToCode(key: String): Int = when (key.lowercase()) {
        "back" -> 4
        "home" -> 3
        "enter" -> 66
        "recent" -> 187
        "power" -> 26
        "volume_up" -> 24
        "volume_down" -> 25
        else -> 0
    }

    /** 执行 shell 命令，10 秒超时 */
    private fun exec(command: String): String {
        Log.d(TAG, "shell: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val result = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                Log.e(TAG, "命令超时: $command")
                ""
            } else result
        } catch (e: Exception) {
            Log.e(TAG, "命令执行失败: ${e.message}")
            ""
        }
    }

    // ─────────────────────────────────────
    // WebSocket 连接
    // ─────────────────────────────────────

    /**
     * 解析 VPS 服务器 URL。
     *
     * 优先级（从高到低）：
     *   1. SystemProperties（AOSP 系统属性，adb shell setprop persist.vendor.anzhi.server_url）
     *   2. SharedPreferences（运行时配置，adb shell 修改）
     *   3. 编译期默认值
     */
    private fun resolveServerUrl(): String {
        // 1. 尝试 SystemProperties（需 platform_apis）
        val propValue = SystemProperties.get("persist.vendor.anzhi.server_url", "")
        if (propValue.isNotBlank()) {
            Log.i(TAG, "从 SystemProperties 读取 server URL: $propValue")
            return propValue
        }

        // 2. 尝试 SharedPreferences（支持 adb shell 运行时修改）
        try {
            val prefs = getSharedPreferences("anzhi_config", MODE_PRIVATE)
            val prefValue = prefs.getString(PREF_KEY_SERVER_URL, "")
            if (!prefValue.isNullOrBlank()) {
                Log.i(TAG, "从 SharedPreferences 读取 server URL: $prefValue")
                return prefValue
            }
        } catch (_: Exception) {
            // SharedPreferences 不可用
        }

        // 3. 默认值
        Log.i(TAG, "使用默认 server URL: $ANZHI_SERVER_URL_DEFAULT")
        return ANZHI_SERVER_URL_DEFAULT
    }

    private fun connectToServer() {
        val url = resolveServerUrl()

        // 未配置就不连：ANZHI_SERVER_URL_DEFAULT 里的 "your-vps-ip" 是文档占位符，
        // VPS 侧根本没有 WebSocket 服务（8080 只绑 127.0.0.1，见 SYSTEM_ENTRIES_PLAN.md §五
        // "明确不做：persist.anzhi.server_url 填值——填了只会无限重连"）。
        // 实机日志证据：刷机后 30 分钟内 AnzhiSocket 已重连 15 次，每次都是
        // "Unable to resolve host \"your-vps-ip\"" 后 300000ms 再试，永久空转。
        // README §十三/§十六 的口径也是这套 WebSocket 属旧链路、不在新主链路上。
        // 需要恢复时：在 SharedPreferences(anzhi_config/server_url) 或
        // persist.vendor.anzhi.server_url 里填真实地址，这条闸门自动放行。
        if (url.contains("your-vps-ip")) {
            Log.i(TAG, "WebSocket 旧链路未配置（仍是占位地址 $url）——跳过连接，不再无限重连")
            return
        }

        socket = AnzhiSocket(url, deviceId).also { s ->
            s.statusCallback = { status ->
                Log.i(TAG, "连接状态: $status")
            }
            // 永久 messageCallback：按 requestId 路由到对应 pending request
            s.messageCallback = { msg ->
                val requestId = msg.payload.optString("request_id", "")
                val pending = pendingRequests[requestId]
                if (pending != null) {
                    when (msg.type) {
                        MessageType.TOOL_CALL -> handleToolCall(msg, pending)
                        MessageType.FINAL_RESPONSE -> handleFinalResponse(msg, pending)
                        else -> Log.w(TAG, "未匹配消息类型: ${msg.type} (id=$requestId)")
                    }
                } else {
                    Log.w(TAG, "未匹配 requestId: $requestId")
                }
            }
        }
        socket?.connect()
    }

    // ─────────────────────────────────────
    // CC (Code Creation) 双模型路由
    // ─────────────────────────────────────

    /**
     * 初始化 CC 供应者。
     *
     * API Key 优先级：
     *   1. SystemProperties（persist.vendor.anzhi.deepseek_key / persist.vendor.anzhi.gemini_key）
     *   2. BuildConfig（编译时打入）
     *   3. SharedPreferences（运行时配置）
     */
    private fun initCcProviders() {
        // 读取 API Key
        val deepseekKey = resolveApiKey(PROP_DEEPSEEK_KEY, "DEEPSEEK_API_KEY")
        val geminiKey = resolveApiKey(PROP_GEMINI_KEY, "GEMINI_API_KEY")

        if (deepseekKey != null) {
            val client = DeepSeekClient(deepseekKey)
            deepSeekCcProvider = DeepSeekCcProvider(client)
            ccProviders.add(deepSeekCcProvider!!)
            Log.i(TAG, "DeepSeek CC 供应者已就绪")
        } else {
            Log.w(TAG, "未配置 DEEPSEEK_API_KEY，DeepSeek CC 不可用")
        }

        if (geminiKey != null) {
            geminiCcProvider = GeminiCcProvider(geminiKey)
            ccProviders.add(geminiCcProvider!!)
            Log.i(TAG, "Gemini CC 供应者已就绪（Gemini 3.5 Flash）")
        } else {
            Log.w(TAG, "未配置 GEMINI_API_KEY，Gemini CC 不可用")
        }
    }

    /**
     * 解析 API Key。
     */
    private fun resolveApiKey(propName: String, buildConfigField: String): String? {
        // 1. 系统属性
        getSystemProperty(propName).ifBlank { null }?.let { return it }
        // 2. SharedPreferences
        try {
            val prefs = getSharedPreferences("anzhi_config", MODE_PRIVATE)
            val key = prefs.getString(buildConfigField, null)
            if (!key.isNullOrBlank()) return key
        } catch (_: Exception) {}
        return null
    }

    /**
     * 安知选模型 — 根据计划文本自动选择 CcProvider。
     *
     * 选择逻辑：
     *   1. 如果计划文本包含 "Gemini"（中英文皆可）→ 选 GeminiCcProvider
     *   2. 如果计划文本包含 "DeepSeek" → 选 DeepSeekCcProvider
     *   3. 如果计划涉及 UI/前端/网页/界面 → 选 Gemini（前端代码 DeepSeek 不行）
     *   4. 默认 → Gemini（写代码场景为主，Gemini 更强）
     *
     * @param planText 安知的计划文本（来自 Gemini CDP 聊天）
     * @return 选中的 CcProvider，如果都没有可用则返回 null
     */
    private fun selectCcProvider(planText: String): CcProvider? {
        // 关键词检测（不区分大小写）
        val lower = planText.lowercase()

        val wantsGemini = lower.contains("gemini") ||
            lower.contains("用 gemini") ||
            lower.contains("前端") ||
            lower.contains("ui") ||
            lower.contains("界面") ||
            lower.contains("网页") ||
            lower.contains("html") ||
            lower.contains("css") ||
            lower.contains("javascript") ||
            lower.contains("组件") ||
            lower.contains("小组件") ||
            lower.contains("卡片") ||
            lower.contains("样式")

        val wantsDeepSeek = lower.contains("deepseek") ||
            lower.contains("用 deepseek") ||
            lower.contains("分析") ||
            lower.contains("分类") ||
            lower.contains("json") ||
            lower.contains("数据") ||
            lower.contains("后端")

        // 按意愿路由
        if (wantsGemini && geminiCcProvider?.available == true) {
            Log.i(TAG, "CC 路由 → Gemini 3.5 Flash（安知指定/前端代码）")
            return geminiCcProvider
        }
        if (wantsDeepSeek && deepSeekCcProvider?.available == true) {
            Log.i(TAG, "CC 路由 → DeepSeek（安知指定/分析分类）")
            return deepSeekCcProvider
        }

        // 没有明确意愿，或指定了但不可用时：默认用 Gemini（写代码强）
        if (geminiCcProvider?.available == true) {
            Log.i(TAG, "CC 路由 → Gemini 3.5 Flash（默认，代码能力优先）")
            return geminiCcProvider
        }
        if (deepSeekCcProvider?.available == true) {
            Log.i(TAG, "CC 路由 → DeepSeek（降级，Gemini 不可用）")
            return deepSeekCcProvider
        }

        Log.w(TAG, "没有可用的 CC 供应者")
        return null
    }

    /**
     * 执行 CC agent loop。
     *
     * 由上层（Chat CDP 聊天解析层）调用——安知说"我要写个东西"时触发。
     *
     * @param planText 安知的计划文本
     * @param systemPrompt 系统提示词
     * @param callback 进度回调
     * @return CC 执行结果
     */
    suspend fun executeCC(
        planText: String,
        systemPrompt: String = "你是安知的代码助手。安知给你一个任务，请用 write_file / read_file / run_command / finish 四个工具完成它。",
        callback: CcProgressCallback? = null
    ): CcLoopResult {
        val provider = selectCcProvider(planText)
            ?: return CcLoopResult(
                success = false, rounds = 0,
                filesProduced = emptyList(),
                finalMessage = null,
                errorReason = "没有可用的 CC 供应者。请检查 API Key 配置。"
            )

        auditLog.log("cc_start", "${provider.name}: ${planText.take(200)}")

        val result = provider.executeCcLoop(
            systemPrompt = systemPrompt,
            userTask = planText,
            workspaceDir = DeepSeekClient.CC_WORKSPACE_DIR,
            callback = callback
        )

        if (result.success) {
            auditLog.log("cc_success", "${provider.name}: rounds=${result.rounds}, files=${result.filesProduced.size}")
        } else {
            auditLog.log("cc_failure", "${provider.name}: ${result.errorReason}")
        }

        return result
    }

    // ─────────────────────────────────────
    // 权限检查
    // ─────────────────────────────────────

    private fun enforceSubmitPermission() {
        // AAOSP 使用 "android.permission.SUBMIT_LLM_REQUEST"
        // 安知使用自定义权限（需要在 manifest 中声明）
        // 当前阶段：检查调用方是否为系统 UID 或同一应用
        val callingUid = Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        if (callingUid != myUid && callingUid != android.os.Process.SYSTEM_UID) {
            // 宽松模式：允许同应用调用。生产环境应使用 signature|privileged 权限。
            Log.w(TAG, "非系统调用方 uid=$callingUid，允许通过（开发模式）")
        }
    }

    private fun isCancelled(sessionId: String): Boolean =
        activeSessionCancelled[sessionId]?.get() == true

    // ─────────────────────────────────────
    // 通知
    // ─────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "安知手机 管理",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "安知手机 系统服务在后台运行" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("安知手机")
                .setContentText("系统服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("安知手机")
                .setContentText("系统服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build()
        }
    }

    // ─────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────

    private fun errorResponse(message: String): String = JSONObject().apply {
        put("sessionId", "")
        put("response", "")
        put("status", "error")
        put("error", message)
    }.toString()
}

/**
 * 内部类：待处理的请求状态。
 */
private data class PendingRequest(
    val requestId: String,
    val sessionId: String,
    val maxToolCalls: Int,
    var toolCallCount: Int,
    val latch: CountDownLatch,
    val responseRef: AtomicReference<String>
)
