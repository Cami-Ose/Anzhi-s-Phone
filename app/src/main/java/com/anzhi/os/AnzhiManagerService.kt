package com.anzhi.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemProperties
import android.util.Log
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

        // ── CC API Key 系统属性名 ──
        private const val PROP_DEEPSEEK_KEY = "persist.anzhi.deepseek_key"
        private const val PROP_GEMINI_KEY = "persist.anzhi.gemini_key"

        /**
         * 读取系统属性（AOSP @hide API，需 platform_apis）。
         * 返回属性值，空字符串表示未设置。
         */
        private fun getSystemProperty(name: String): String {
            return SystemProperties.get(name, "")
        }
    }

    // ── 核心组件 ──

    private lateinit var sessionStore: AnzhiSessionStore
    private lateinit var deviceId: String
    private lateinit var auditLog: AnzhiAuditLog

    /** WebSocket 连接（延迟初始化，等 VPS 地址配置好） */
    private var socket: AnzhiSocket? = null

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

        // 连接安知后端
        connectToServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        socket?.disconnect()
        Log.i(TAG, "安知 Manager Service 停止")
        super.onDestroy()
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
     *   1. SystemProperties（AOSP 系统属性，adb shell setprop persist.anzhi.server_url）
     *   2. SharedPreferences（运行时配置，adb shell 修改）
     *   3. 编译期默认值
     */
    private fun resolveServerUrl(): String {
        // 1. 尝试 SystemProperties（需 platform_apis）
        val propValue = SystemProperties.get("persist.anzhi.server_url", "")
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
     *   1. SystemProperties（persist.anzhi.deepseek_key / persist.anzhi.gemini_key）
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
