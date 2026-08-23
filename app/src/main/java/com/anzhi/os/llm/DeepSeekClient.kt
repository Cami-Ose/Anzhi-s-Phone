package com.anzhi.os.llm

import android.util.Log
import com.anzhi.os.cc.CcFileOutput
import com.anzhi.os.cc.CcLoopResult
import com.anzhi.os.cc.CcProgressCallback
import com.anzhi.os.cc.CcToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 直调客户端。
 *
 * BUILD.md Step 4 — DeepSeekClient：
 *   - 手机直调 DeepSeek API，不经过 VPS
 *   - 支持 json_mode（response_format: { type: "json_object" }）
 *   - 用于推理、分类、意图解析、路径规划
 *
 * 铁律（BUILD.md Invariant 7）：
 *   - DeepSeek 永远服务安知，不服务 Cami
 *   - Cami 不调 DeepSeek，安知不把 DeepSeek 输出原样暴露给 Cami
 *   - DeepSeek 输出只应是 JSON 指令，不是对话文本
 *
 * 架构（BUILD.md §八 决策引擎）：
 *   - DeepSeek 做推理/规划/分类，不做看图
 *   - 自然语言解析意图（App + 操作类型）→ 拼缓存 key
 *
 * Step 5 扩展（同文件，tool calling agent loop）：
 *   - 本文件预留 tool calling 能力
 *   - MAX_ROUNDS = 15 熔断器（陷阱 7）
 *
 * 重试策略：
 *   - 指数退避：1s → 2s → 4s → 8s → 16s（最多 5 次）
 *   - 429 Rate Limit → 读 Retry-After header，最小等待 5s
 *   - 5xx Server Error → 退避重试
 *   - 连续 5 次失败 → 标记不可用，等下次唤醒再试
 *
 * @param apiKey DeepSeek API Key（sk-xxx）
 * @param baseUrl API 基础 URL，默认 https://api.deepseek.com/v1
 * @param model 模型名，默认 deepseek-chat
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com/v1",
    private val model: String = "deepseek-chat"
) {
    companion object {
        private const val TAG = "DeepSeekClient"

        // ── HTTP 超时 ──
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 90_000L
        private const val WRITE_TIMEOUT_MS = 30_000L

        // ── 重试 ──
        private const val MAX_RETRIES = 5
        private const val BASE_RETRY_DELAY_MS = 1_000L

        // ── CC 熔断器（陷阱 7，Step 5 启用）──
        const val MAX_TOOL_ROUNDS = 15

        // ── CC 工作目录 ──
        const val CC_WORKSPACE_DIR = "/data/local/tmp/anzhi_cc"

        // ── run_command 超时（毫秒）──
        private const val CC_COMMAND_TIMEOUT_MS = 60_000L

        // ── run_command 禁止的 Android 系统命令前缀 ──
        // DeepSeek 不知道手机的存在，CC 边界仅限代码/编译/脚本/文件操作
        private val CC_BLOCKED_COMMANDS = setOf(
            "input", "am", "pm", "cmd", "settings", "svc",
            "dumpsys", "service", "content", "monkey", "uiautomator",
            "adb", "fastboot", "reboot", "setprop", "mount",
            "wm", "ime", "telecom", "phone", "media", "audio",
            "dpm", "bmgr", "bu", "appops", "device_config"
        )

        // ── 默认生成参数 ──
        private const val DEFAULT_TEMPERATURE = 0.1   // 推理用低温
        private const val DEFAULT_MAX_TOKENS = 2048

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 从环境变量风格配置创建客户端。
         * 实际 API Key 从 Android SharedPreferences 或系统属性读取。
         */
        fun fromEnv(
            apiKey: String,
            baseUrl: String = "https://api.deepseek.com/v1",
            model: String = "deepseek-chat"
        ): DeepSeekClient = DeepSeekClient(apiKey, baseUrl, model)
    }

    // ── OkHttp 连接池 ──
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(3, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)  // 自己管理重试
        .build()

    /** 连续失败次数，用于熔断判断 */
    private var consecutiveFailures = 0

    /** 是否已被标记为不可用 */
    @Volatile
    var available: Boolean = true
        private set

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /**
     * 标准聊天补全（非 json_mode）。
     *
     * 用于安知把计划文本发给 DeepSeek 拆解，返回 JSON 指令。
     * 即使不设 json_mode，DeepSeek 在 system prompt 里要求 JSON 输出也能稳定产出。
     *
     * @param systemPrompt 系统提示词（安知的规划指令）
     * @param userMessage 用户消息（安知说"我要写个东西"）
     * @param temperature 温度参数，默认 0.1（推理用低温）
     * @param maxTokens 最大输出 token，默认 2048
     * @return DeepSeek 回复文本，失败返回 null
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "DeepSeek 已被标记为不可用")
            return@withContext null
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        executeChatCompletion(messages, temperature, maxTokens, jsonMode = false)
    }

    /**
     * JSON 模式聊天补全。
     *
     * DeepSeek 的 json_mode 会强制模型输出合法 JSON。
     * system prompt 必须以 "JSON" 结尾（DeepSeek 官方要求）。
     *
     * 用于：
     *   - 通知分类：通知内容 → {importance: "important"|"normal"|"spam", reason: "..."}
     *   - 意图解析：Cami 自然语言 → {app: "微信", operation: "设免打扰", confidence: 0.9}
     *   - 缓存 key 拼装：{app, operation} → "ui:com.tencent.mm:设免打扰"
     *
     * @param systemPrompt 系统提示词（必须以 "JSON" 结尾或包含输出格式说明）
     * @param userMessage 用户消息
     * @param temperature 温度参数，默认 0.1
     * @param maxTokens 最大输出 token，默认 2048
     * @return 解析后的 JSONObject，失败返回 null
     */
    suspend fun chatJsonMode(
        systemPrompt: String,
        userMessage: String,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "DeepSeek 已被标记为不可用")
            return@withContext null
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val raw = executeChatCompletion(messages, temperature, maxTokens, jsonMode = true)
            ?: return@withContext null

        // 解析 JSON 输出
        try {
            val trimmed = raw.trim()
            // 容忍 markdown code fence
            val json = when {
                trimmed.startsWith("```json") ->
                    trimmed.removePrefix("```json").removeSuffix("```").trim()
                trimmed.startsWith("```") ->
                    trimmed.removePrefix("```").removeSuffix("```").trim()
                else -> trimmed
            }
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "DeepSeek json_mode 输出无法解析为 JSON: ${raw.take(200)}", e)
            consecutiveFailures++
            null
        }
    }

    /**
     * 多轮对话（带历史消息）。
     *
     * Step 5 CC agent loop 使用——把之前的 tool_call / tool_result 喂回 DeepSeek。
     *
     * @param messages OpenAI 格式的消息列表，每项含 role + content
     * @param temperature 温度参数
     * @param maxTokens 最大输出 token
     * @return DeepSeek 回复文本，失败返回 null
     */
    suspend fun chatWithHistory(
        messages: List<Map<String, Any>>,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "DeepSeek 已被标记为不可用")
            return@withContext null
        }

        val messagesArray = convertMessagesToJson(messages)
        executeChatCompletion(messagesArray, temperature, maxTokens, jsonMode = false)
    }

    /**
     * 多轮对话（带 tool calling 历史），返回 JSON 模式。
     *
     * Step 5 CC agent loop 使用——DeepSeek 返回 tool_call 或 finish。
     *
     * @param messages OpenAI 格式消息列表（含 assistant tool_calls + tool results）
     * @param temperature 温度
     * @param maxTokens 最大 token
     * @return 解析后的 JSONObject，失败返回 null
     */
    suspend fun chatWithHistoryJsonMode(
        messages: List<Map<String, Any>>,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): JSONObject? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "DeepSeek 已被标记为不可用")
            return@withContext null
        }

        val messagesArray = convertMessagesToJson(messages)
        val raw = executeChatCompletion(messagesArray, temperature, maxTokens, jsonMode = true)
            ?: return@withContext null

        try {
            val trimmed = raw.trim()
            val json = when {
                trimmed.startsWith("```json") ->
                    trimmed.removePrefix("```json").removeSuffix("```").trim()
                trimmed.startsWith("```") ->
                    trimmed.removePrefix("```").removeSuffix("```").trim()
                else -> trimmed
            }
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "DeepSeek json_mode 多轮输出解析失败: ${raw.take(200)}", e)
            consecutiveFailures++
            null
        }
    }

    // ═══════════════════════════════════════════
    // Step 5 — CC (DeepSeek tool calling) agent loop
    // ═══════════════════════════════════════════

    /**
     * 执行 CC agent loop：DeepSeek tool calling 自主写代码/编译/跑脚本。
     *
     * 流程（BUILD.md Step 5）：
     *   while round < MAX_ROUNDS (15):
     *     DeepSeek API 返回 tool_call → Kotlin 执行 → 结果喂回 →
     *     直到 DeepSeek 调用 finish 或无 tool_call → 退出
     *
     * run_command 边界：
     *   仅限写代码/编译/跑脚本/文件操作。Android 系统命令（input/am/pm 等）被拦截拒绝。
     *   CC 产物如需 Android 操作，由上层 AnzhiManagerService 在 CC 退出后接管。
     *
     * @param systemPrompt 系统提示词（安知的规划指令，说明要做什么）
     * @param userTask 安知说的「我要写个东西」——具体任务描述
     * @param workspaceDir CC 工作目录，默认为 /data/local/tmp/anzhi_cc
     * @param callback 进度回调，用于状态栏眼睛图标切换等
     * @return CcLoopResult — 成功/失败、轮次、产出文件、最终消息
     */
    suspend fun executeCcLoop(
        systemPrompt: String,
        userTask: String,
        workspaceDir: String = CC_WORKSPACE_DIR,
        callback: CcProgressCallback? = null
    ): CcLoopResult = withContext(Dispatchers.IO) {
        if (!available) {
            val msg = "DeepSeek 已被标记为不可用（连续失败 $consecutiveFailures 次），CC loop 拒绝启动"
            Log.w(TAG, msg)
            return@withContext CcLoopResult(
                success = false, rounds = 0,
                filesProduced = emptyList(),
                finalMessage = null, errorReason = msg
            )
        }

        // 确保工作目录存在
        val wsDir = File(workspaceDir)
        if (!wsDir.exists() && !wsDir.mkdirs()) {
            val msg = "无法创建 CC 工作目录: $workspaceDir"
            Log.e(TAG, msg)
            return@withContext CcLoopResult(
                success = false, rounds = 0,
                filesProduced = emptyList(),
                finalMessage = null, errorReason = msg
            )
        }

        // 初始化消息历史
        val messages = mutableListOf<MutableMap<String, Any>>()
        messages.add(mutableMapOf(
            "role" to "system",
            "content" to systemPrompt
        ))
        messages.add(mutableMapOf(
            "role" to "user",
            "content" to userTask
        ))

        val filesProduced = mutableListOf<CcFileOutput>()
        var round = 0
        var finalMessage: String? = null
        var errorReason: String? = null

        val toolsDef = buildCcToolDefinitions()

        Log.i(TAG, "CC agent loop 启动，workspace=$workspaceDir")

        while (round < MAX_TOOL_ROUNDS) {
            round++
            callback?.onRoundStart(round)
            Log.d(TAG, "CC round $round / $MAX_TOOL_ROUNDS")

            // 调用 DeepSeek（带 tool 定义）
            val response = executeChatCompletionWithTools(
                messages = convertMessagesToJson(messages),
                tools = toolsDef,
                temperature = 0.1,
                maxTokens = 2048
            )

            if (response == null) {
                errorReason = "CC round $round: DeepSeek API 调用失败"
                Log.e(TAG, errorReason)
                break
            }

            val toolCalls = response.toolCalls
            val content = response.content

            // 情况 1：没有 tool_calls → 任务结束（自然完成或 DeepSeek 放弃）
            if (toolCalls.isNullOrEmpty()) {
                finalMessage = content
                Log.i(TAG, "CC loop 结束：DeepSeek 无 tool_call，round=$round")
                break
            }

            // 情况 2：有 tool_calls → 执行
            // 先把 assistant 消息加入历史
            val assistantMsg = mutableMapOf<String, Any>(
                "role" to "assistant",
                "content" to (content ?: "")
            )
            val tcList = mutableListOf<Map<String, Any>>()
            for (tc in toolCalls) {
                tcList.add(mapOf(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tc.name,
                        "arguments" to tc.arguments.toString()
                    )
                ))
            }
            assistantMsg["tool_calls"] = tcList
            messages.add(assistantMsg)

            // 执行每个 tool_call
            for (tc in toolCalls) {
                callback?.onToolCall(tc)

                when (tc.name) {
                    "finish" -> {
                        // finish tool → 任务完成
                        val summary = tc.arguments.optString("summary", "任务完成")
                        finalMessage = summary
                        Log.i(TAG, "CC loop 结束：DeepSeek 调用 finish — $summary")
                        // 也要给 finish 加 tool result 以便完整记录
                        messages.add(mutableMapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to "finish acknowledged: $summary"
                        ))
                        // 跳出 tool 循环，外层 while 会检测 finalMessage 并 break
                    }
                    "write_file" -> {
                        val path = tc.arguments.optString("path", "")
                        val fileContent = tc.arguments.optString("content", "")
                        val (ok, msg) = executeWriteFile(workspaceDir, path, fileContent)
                        callback?.onToolResult("write_file", ok, msg)
                        messages.add(mutableMapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to (if (ok) "OK: $msg" else "ERROR: $msg")
                        ))
                        if (ok) {
                            filesProduced.add(CcFileOutput(path, fileContent))
                        }
                    }
                    "read_file" -> {
                        val path = tc.arguments.optString("path", "")
                        val (ok, msg) = executeReadFile(workspaceDir, path)
                        callback?.onToolResult("read_file", ok, msg)
                        messages.add(mutableMapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to (if (ok) msg else "ERROR: $msg")
                        ))
                    }
                    "run_command" -> {
                        val cmd = tc.arguments.optString("cmd", "")
                        val (ok, msg) = executeRunCommand(cmd)
                        callback?.onToolResult("run_command", ok, msg)
                        messages.add(mutableMapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to (if (ok) msg else "ERROR: $msg")
                        ))
                    }
                    else -> {
                        val msg = "未知 tool: ${tc.name}"
                        Log.w(TAG, msg)
                        callback?.onToolResult(tc.name, false, msg)
                        messages.add(mutableMapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to "ERROR: $msg"
                        ))
                    }
                }

                // finish 被调用后退出外层循环
                if (tc.name == "finish") break
            }

            // 如果某轮有 finish → 退出
            if (toolCalls.any { it.name == "finish" }) break
        }

        // ── 熔断检查 ──
        if (round >= MAX_TOOL_ROUNDS && finalMessage == null) {
            errorReason = "CC loop 达到最大轮次 $MAX_TOOL_ROUNDS，强制中止。" +
                "等安知下次唤醒时向 Cami 求助（陷阱 7）"
            Log.e(TAG, errorReason)
        }

        val success = finalMessage != null && errorReason == null
        val result = CcLoopResult(
            success = success,
            rounds = round,
            filesProduced = filesProduced,
            finalMessage = finalMessage,
            errorReason = errorReason
        )
        callback?.onLoopEnd(result)
        Log.i(TAG, "CC agent loop 结束: success=$success, rounds=$round, files=${filesProduced.size}")
        result
    }

    /**
     * 重置熔断状态。
     * 在 VPS 恢复或下次唤醒时调用。
     */
    fun resetAvailable() {
        available = true
        consecutiveFailures = 0
        Log.i(TAG, "DeepSeek 客户端熔断状态已重置")
    }

    /**
     * 获取连续失败次数。
     */
    fun getConsecutiveFailures(): Int = consecutiveFailures

    /**
     * 关闭 HTTP 客户端，释放连接。
     */
    fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {
            // 可能已经被关闭
        }
        Log.i(TAG, "DeepSeek 客户端已关闭")
    }

    // ═══════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════

    /**
     * 执行 /chat/completions 请求，含重试逻辑。
     */
    private suspend fun executeChatCompletion(
        messages: JSONArray,
        temperature: Double,
        maxTokens: Int,
        jsonMode: Boolean
    ): String? {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) {
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }
        }

        val payloadStr = requestBody.toString()
        Log.d(TAG, "DeepSeek API 请求: ${payloadStr.length} chars, jsonMode=$jsonMode")

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code

                if (code in 200..299) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    val text = extractContent(body)
                    if (text != null) {
                        consecutiveFailures = 0
                        Log.d(TAG, "DeepSeek 回复成功 (${text.length} chars)")
                        return text
                    } else {
                        Log.w(TAG, "DeepSeek 返回空内容: ${body.take(300)}")
                        consecutiveFailures++
                        return null
                    }
                }

                // ── 错误处理 ──
                val errorBody = response.body?.string()?.take(500) ?: ""
                response.close()

                when {
                    code == 429 -> {
                        // Rate Limit — 读 Retry-After 或等退避
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        val waitMs = retryAfter?.times(1000)?.coerceAtLeast(5_000)
                            ?: BASE_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "DeepSeek 429 rate limited, ${waitMs}ms 后重试 (attempt ${attempt + 1})")
                        delay(waitMs)
                        continue
                    }
                    code in 500..599 -> {
                        Log.w(TAG, "DeepSeek HTTP $code on attempt ${attempt + 1}: ${errorBody.take(200)}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                            continue
                        }
                    }
                    code in 400..499 -> {
                        // 4xx（非 429）：不重试
                        Log.e(TAG, "DeepSeek HTTP $code (不可重试): $errorBody")
                        consecutiveFailures++
                        checkAndMarkUnavailable()
                        return null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "DeepSeek 网络异常 (attempt ${attempt + 1}): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                    continue
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek 调用异常: ${e.message}", e)
                consecutiveFailures++
                checkAndMarkUnavailable()
                return null
            }
        }

        // 耗尽重试
        consecutiveFailures++
        checkAndMarkUnavailable()
        Log.e(TAG, "DeepSeek API 调用失败，已耗尽 $MAX_RETRIES 次重试")
        return null
    }

    /**
     * 从 /chat/completions 响应中提取 content 文本。
     */
    private fun extractContent(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).getJSONObject("message")
            message.optString("content", null)
        } catch (e: Exception) {
            Log.w(TAG, "解析 DeepSeek 响应失败: ${e.message}")
            null
        }
    }

    /**
     * 将消息列表转换为 JSONArray。
     * 支持嵌套 Map（用于 vision 格式等）。
     */
    private fun convertMessagesToJson(messages: List<Map<String, Any>>): JSONArray {
        val array = JSONArray()
        for (msg in messages) {
            val jsonMsg = JSONObject()
            jsonMsg.put("role", msg["role"])

            when (val content = msg["content"]) {
                is String -> jsonMsg.put("content", content)
                is List<*> -> {
                    val contentArray = JSONArray()
                    for (item in content) {
                        if (item is Map<*, *>) {
                            contentArray.put(mapToJsonObject(item))
                        }
                    }
                    jsonMsg.put("content", contentArray)
                }
                else -> jsonMsg.put("content", content?.toString() ?: "")
            }

            // 可选字段：tool_calls, tool_call_id, name
            msg["tool_calls"]?.let { toolCalls ->
                when (toolCalls) {
                    is List<*> -> {
                        // 将 Kotlin List<Map> 转为 JSONArray
                        val tcArray = JSONArray()
                        for (tc in toolCalls) {
                            when (tc) {
                                is Map<*, *> -> tcArray.put(mapToJsonObject(tc))
                                is JSONObject -> tcArray.put(tc)
                                else -> tcArray.put(tc)
                            }
                        }
                        jsonMsg.put("tool_calls", tcArray)
                    }
                    else -> jsonMsg.put("tool_calls", toolCalls)
                }
            }
            msg["tool_call_id"]?.let { jsonMsg.put("tool_call_id", it) }
            msg["name"]?.let { jsonMsg.put("name", it) }

            array.put(jsonMsg)
        }
        return array
    }

    /** 递归将 Map 转为 JSONObject */
    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> obj.put(k.toString(), mapToJsonObject(v))
                else -> obj.put(k.toString(), v)
            }
        }
        return obj
    }

    /**
     * 检查连续失败次数，达到阈值后标记不可用。
     */
    private fun checkAndMarkUnavailable() {
        if (consecutiveFailures >= 5) {
            available = false
            Log.e(
                TAG,
                "DeepSeek 连续失败 $consecutiveFailures 次，标记为不可用。" +
                "下次 AnzhiManagerService 唤醒时会重置。"
            )
        }
    }

    // ═══════════════════════════════════════════
    // Step 5 — CC 内部方法
    // ═══════════════════════════════════════════

    /** API 响应：同时包含 content 和 tool_calls */
    private data class ChatResponse(
        val content: String?,
        val toolCalls: List<CcToolCall>?
    )

    /**
     * 构建 CC tool 定义 JSON。
     * 四个 tool：write_file / read_file / run_command / finish
     */
    private fun buildCcToolDefinitions(): JSONArray {
        return JSONArray().apply {
            // write_file
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "write_file")
                    put("description", "Write content to a file in the CC workspace. " +
                        "Use this to create source code files, config files, build scripts, etc.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "File path relative to CC workspace")
                            })
                            put("content", JSONObject().apply {
                                put("type", "string")
                                put("description", "Full file content to write")
                            })
                        })
                        put("required", JSONArray(listOf("path", "content")))
                    })
                })
            })
            // read_file
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "read_file")
                    put("description", "Read content from a file in the CC workspace. " +
                        "Use this to check existing code before editing.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "File path relative to CC workspace")
                            })
                        })
                        put("required", JSONArray(listOf("path")))
                    })
                })
            })
            // run_command
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "run_command")
                    put("description", "Execute a shell command on the Android device. " +
                        "Only for: code compilation, running scripts, file operations " +
                        "(ls, mkdir, cp, mv, rm, cat, grep, chmod, find, wc). " +
                        "NOT for Android system commands (input tap, am start, pm, etc.) — " +
                        "those will be rejected. You are writing code/scripts, not controlling the phone.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("cmd", JSONObject().apply {
                                put("type", "string")
                                put("description", "Shell command to execute in the CC workspace. " +
                                    "The command runs with CWD set to the workspace directory.")
                            })
                        })
                        put("required", JSONArray(listOf("cmd")))
                    })
                })
            })
            // finish
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "finish")
                    put("description", "Signal that the task is complete. " +
                        "Call this when all work is done — all files written, all commands executed successfully.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("summary", JSONObject().apply {
                                put("type", "string")
                                put("description", "Summary of what was accomplished. " +
                                    "List the files created/modified and what they do. " +
                                    "If a compilation or test was run, include the result.")
                            })
                        })
                        put("required", JSONArray(listOf("summary")))
                    })
                })
            })
        }
    }

    /**
     * 执行带 tool 定义的 /chat/completions 请求。
     * 与 executeChatCompletion 类似，但发送 tools 并解析 tool_calls。
     */
    private suspend fun executeChatCompletionWithTools(
        messages: JSONArray,
        tools: JSONArray,
        temperature: Double,
        maxTokens: Int
    ): ChatResponse? {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("tools", tools)
            put("tool_choice", "auto")
        }

        val payloadStr = requestBody.toString()
        Log.d(TAG, "DeepSeek tool-calling 请求: ${payloadStr.length} chars")

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code

                if (code in 200..299) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    val chatResponse = extractContentAndToolCalls(body)
                    if (chatResponse != null) {
                        consecutiveFailures = 0
                        val tcCount = chatResponse.toolCalls?.size ?: 0
                        Log.d(TAG, "DeepSeek 回复: content=${(chatResponse.content?.length ?: 0)} chars, tool_calls=$tcCount")
                        return chatResponse
                    } else {
                        Log.w(TAG, "DeepSeek tool-calling 解析失败: ${body.take(300)}")
                        consecutiveFailures++
                        return null
                    }
                }

                val errorBody = response.body?.string()?.take(500) ?: ""
                response.close()

                when {
                    code == 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        val waitMs = retryAfter?.times(1000)?.coerceAtLeast(5_000)
                            ?: BASE_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "DeepSeek tool-calling 429, ${waitMs}ms 后重试 (attempt ${attempt + 1})")
                        delay(waitMs)
                        continue
                    }
                    code in 500..599 -> {
                        Log.w(TAG, "DeepSeek tool-calling HTTP $code (attempt ${attempt + 1}): ${errorBody.take(200)}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                            continue
                        }
                    }
                    code in 400..499 -> {
                        Log.e(TAG, "DeepSeek tool-calling HTTP $code (不可重试): $errorBody")
                        consecutiveFailures++
                        checkAndMarkUnavailable()
                        return null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "DeepSeek tool-calling 网络异常 (attempt ${attempt + 1}): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                    continue
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek tool-calling 调用异常: ${e.message}", e)
                consecutiveFailures++
                checkAndMarkUnavailable()
                return null
            }
        }

        consecutiveFailures++
        checkAndMarkUnavailable()
        Log.e(TAG, "DeepSeek tool-calling 请求耗尽 $MAX_RETRIES 次重试")
        return null
    }

    /**
     * 从 /chat/completions 响应中同时提取 content 和 tool_calls。
     */
    private fun extractContentAndToolCalls(responseBody: String): ChatResponse? {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).getJSONObject("message")
            val content = message.optString("content", null)

            val toolCalls = mutableListOf<CcToolCall>()
            val tcArray = message.optJSONArray("tool_calls")
            if (tcArray != null) {
                for (i in 0 until tcArray.length()) {
                    val tc = tcArray.getJSONObject(i)
                    val id = tc.getString("id")
                    val function = tc.getJSONObject("function")
                    val name = function.getString("name")
                    val arguments = try {
                        JSONObject(function.getString("arguments"))
                    } catch (e: Exception) {
                        // arguments 解析失败，用空对象
                        Log.w(TAG, "tool_call arguments 解析失败: ${function.optString("arguments", "").take(100)}")
                        JSONObject()
                    }
                    toolCalls.add(CcToolCall(id, name, arguments))
                }
            }

            // content 和 tool_calls 可以都为 null（例如纯 tool call 无文本）
            ChatResponse(
                content = if (content.isNullOrBlank()) null else content,
                toolCalls = toolCalls.ifEmpty { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 DeepSeek tool-calling 响应失败: ${e.message}")
            null
        }
    }

    /**
     * 执行 write_file tool。
     * 路径相对于 CC workspace，拒绝路径穿越。
     */
    private fun executeWriteFile(workspaceDir: String, path: String, content: String): Pair<Boolean, String> {
        val resolved = resolveWorkspacePath(workspaceDir, path)
        if (resolved == null) {
            return Pair(false, "路径穿越拒绝: $path")
        }
        if (path.isBlank()) {
            return Pair(false, "path 不能为空")
        }
        return try {
            val file = File(resolved)
            // 确保父目录存在
            file.parentFile?.mkdirs()
            file.writeText(content)
            val size = file.length()
            Log.i(TAG, "CC write_file: $path (${size} bytes)")
            Pair(true, "已写入 $path (${size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "CC write_file 失败: $path — ${e.message}", e)
            Pair(false, "写入失败: ${e.message}")
        }
    }

    /**
     * 执行 read_file tool。
     * 路径相对于 CC workspace，拒绝路径穿越。
     */
    private fun executeReadFile(workspaceDir: String, path: String): Pair<Boolean, String> {
        val resolved = resolveWorkspacePath(workspaceDir, path)
        if (resolved == null) {
            return Pair(false, "路径穿越拒绝: $path")
        }
        if (path.isBlank()) {
            return Pair(false, "path 不能为空")
        }
        return try {
            val file = File(resolved)
            if (!file.exists()) {
                Pair(false, "文件不存在: $path")
            } else if (!file.canRead()) {
                Pair(false, "文件不可读: $path")
            } else {
                val text = file.readText()
                val limit = 50_000  // 单次最大 50KB 返回
                if (text.length > limit) {
                    Pair(true, text.take(limit) + "\n…[截断: 全文 ${text.length} bytes]")
                } else {
                    Pair(true, text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CC read_file 失败: $path — ${e.message}", e)
            Pair(false, "读取失败: ${e.message}")
        }
    }

    /**
     * 执行 run_command tool。
     * 安全检查：拒绝 Android 系统命令前缀。
     * 命令在工作目录下通过 /system/bin/sh 执行。
     */
    private fun executeRunCommand(cmd: String): Pair<Boolean, String> {
        if (cmd.isBlank()) {
            return Pair(false, "cmd 不能为空")
        }

        // 安全检查：拒绝 Android 系统命令
        val firstWord = cmd.trimStart().split(" ", "\t").firstOrNull()?.lowercase()?.trim() ?: ""
        if (firstWord in CC_BLOCKED_COMMANDS) {
            val msg = "已拒绝: '$firstWord' 是 Android 系统命令。CC run_command 仅限代码/编译/脚本/文件操作（陷阱 7 边界）。"
            Log.w(TAG, "CC run_command 拦截: $cmd")
            return Pair(false, msg)
        }

        // 额外检查：禁止包含可疑路径的命令
        val dangerousPaths = listOf("/system/bin/", "/vendor/bin/", "/sbin/")
        for (dp in dangerousPaths) {
            if (cmd.contains(dp)) {
                val msg = "已拒绝: 命令中包含系统路径 '$dp'。CC 不能直接调用系统二进制。"
                Log.w(TAG, "CC run_command 拦截: $cmd")
                return Pair(false, msg)
            }
        }

        return try {
            // 在 CC 工作目录下执行
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", cmd),
                arrayOf(),  // envp
                File(CC_WORKSPACE_DIR)  // CWD
            )

            // 读取 stdout 和 stderr（在子线程中读取，避免管道阻塞）
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            // 等待进程结束，带超时
            val finished = process.waitFor(CC_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                Pair(false, "命令超时 (${CC_COMMAND_TIMEOUT_MS / 1000}s): ${cmd.take(100)}")
            } else {
                val exitCode = process.exitValue()
                val builder = StringBuilder()
                if (stdout.isNotBlank()) builder.append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append("[stderr] ").append(stderr.trimEnd())
                }
                val output = builder.toString().let { if (it.isBlank()) "(无输出)" else it }
                // 截断过长输出
                val result = if (output.length > 10_000) {
                    output.take(10_000) + "\n…[截断]"
                } else output

                if (exitCode == 0) {
                    Log.i(TAG, "CC run_command 成功: ${cmd.take(80)}")
                    Pair(true, result)
                } else {
                    Log.w(TAG, "CC run_command 失败 (exit=$exitCode): ${cmd.take(80)}")
                    Pair(false, "exit=$exitCode\n$result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CC run_command 异常: ${e.message}", e)
            Pair(false, "命令执行异常: ${e.message}")
        }
    }

    /**
     * 解析 CC 工作路径，拒绝路径穿越。
     * @return 解析后的绝对路径，如果检测到穿越则返回 null
     */
    private fun resolveWorkspacePath(workspaceDir: String, path: String): String? {
        return try {
            val wsCanonical = File(workspaceDir).canonicalPath
            val resolved = File(workspaceDir, path).canonicalPath
            if (!resolved.startsWith(wsCanonical + File.separator) && resolved != wsCanonical) {
                Log.w(TAG, "CC 路径穿越检测: workspace=$wsCanonical, requested=$path, resolved=$resolved")
                null
            } else {
                resolved
            }
        } catch (e: Exception) {
            Log.e(TAG, "CC 路径解析失败: $path — ${e.message}")
            null
        }
    }
}
