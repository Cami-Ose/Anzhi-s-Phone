package com.anzhi.os.cdp

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 实现 A（M1 大脑）：直连 VPS API。
 *
 * M1 换源改造——把聊天大脑从"官网 CDP WebView"换成"直连 VPS API"：
 *   - 手机端不再持有任何 API key / model，全部配置在 VPS 端
 *   - 手机只 POST /proxy_chat，VPS 端用默认线路 + 全权注入上下文 + 内部查记忆库(5003)
 *   - 鉴权通过 HTTP Header X-Anzhi-Token 传递，不泄露到 URL
 *
 * 请求流程：
 *   1. 拼 messages（进程级历史 + user message）
 *   2. 附带 phone_snapshot = snapshot.toInjectJson()（直接作为嵌套 JSONObject 放入 body，VPS 端按 dict 解析）
 *   3. POST ${vpsBaseUrl}/proxy_chat
 *   4. 解析响应 {"role":"assistant","content": reply, "tool_calls":[...], "pending_messages":[...]}
 *
 * M2：V1 阶段 VPS 启用工具调度后 /proxy_chat 可能返回手机工具调用（tool_calls），
 * 本类续轮循环：手机执行工具 → 结果回传 VPS（role=tool）→ 直到 VPS 给出最终文本。
 * toolCalls 始终在循环内消化，chat() 返回的 toolCalls 恒为空列表。
 */
class VpsBrainProvider(
    private val vpsBaseUrl: String,
    private val apiToken: String,
    /** M2：手机 DS 执行器（可空，保持向后兼容）。为 null 时收到工具调用返回占位文本给 VPS。 */
    private val executor: DsExecutor? = null
) : AnzhiBrainProvider {

    companion object {
        private const val TAG = "VpsBrainProvider"
        /** 手机工具续轮循环上限，防死循环 */
        private const val MAX_TOOL_ROUNDS = 15
    }

    override val name: String = "VPS 直连大脑"
    override val isPrimary: Boolean = true

    /** 进程级累积对话历史（含 tool 消息），VpsBrainProvider 实例存活期内持续累积 */
    private val messageHistory = mutableListOf<Map<String, Any>>()

    override fun isAvailable(): Boolean = vpsBaseUrl.isNotBlank() && apiToken.isNotBlank()

    override suspend fun chat(
        snapshot: ConversationSnapshot,
        userMessage: String,
        timeoutSeconds: Int
    ): BrainChatResult? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "VPS 直连不可用（api_url 或 token 为空）")
            return@withContext null
        }

        try {
            // 首轮 messages = 进程级累积历史 + 当前用户消息
            var messages: List<Map<String, Any>> =
                messageHistory.toMutableList() + listOf(mapOf("role" to "user", "content" to userMessage))
            val phoneSnapshot = snapshot.toInjectJson()

            // 续轮循环：VPS 可返回手机工具调用，手机执行后带结果回传，直到 VPS 给出最终文本
            for (round in 0 until MAX_TOOL_ROUNDS) {
                val response = postProxyChat(messages, phoneSnapshot, timeoutSeconds)
                    ?: return@withContext null

                // content 非 null → 本轮完成（VPS 已消化全部工具）
                val content: String? = if (response.has("content") && !response.isNull("content")) {
                    response.optString("content")
                } else null
                if (content != null) {
                    messageHistory.clear()
                    messageHistory.addAll(messages)
                    messageHistory.add(mapOf("role" to "assistant", "content" to content))
                    Log.d(TAG, "VPS 直连回复成功 (${content.length} chars, round=$round)")
                    return@withContext BrainChatResult(text = content, toolCalls = emptyList())
                }

                // tool_calls 非空 → 手机执行工具，构造 tool 结果消息，下一轮用 pending_messages 续传
                val toolCallsArray = response.optJSONArray("tool_calls")
                if (toolCallsArray != null && toolCallsArray.length() > 0) {
                    val dsExecutor = this@VpsBrainProvider.executor
                    if (dsExecutor == null) {
                        Log.w(TAG, "收到手机工具调用但 DS 执行器未接线（M2 未启用）")
                        return@withContext BrainChatResult("（工具执行器未接线）", emptyList())
                    }

                    val toolCalls = (0 until toolCallsArray.length()).map { i ->
                        val tc = toolCallsArray.getJSONObject(i)
                        ToolCall(
                            id = tc.optString("id", ""),
                            name = tc.optString("name", ""),
                            args = tc.optString("args", "{}")
                        )
                    }
                    Log.d(TAG, "收到 ${toolCalls.size} 个手机工具调用, round=$round")

                    val toolResultMessages = toolCalls.map { tc ->
                        val resultText = dsExecutor.execute(tc)
                        mapOf(
                            "role" to "tool",
                            "tool_call_id" to tc.id,
                            "content" to resultText
                        )
                    }

                    // 下一轮 messages = VPS 返回的 pending_messages + 本次 tool 结果
                    val pending = response.optJSONArray("pending_messages")
                    val pendingList = if (pending != null && pending.length() > 0) {
                        pendingMessagesToList(pending)
                    } else emptyList()
                    messages = pendingList + toolResultMessages
                    continue
                }

                // 协议异常：既无 content 也无 tool_calls
                Log.w(TAG, "VPS /proxy_chat 响应缺少 content 与 tool_calls（round=$round）")
                return@withContext null
            }

            // 轮数耗尽，防死循环
            Log.w(TAG, "VPS 工具续轮循环达到上限($MAX_TOOL_ROUNDS)，终止")
            BrainChatResult("（工具循环超限）", emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "VPS 直连 chat 调用失败: ${e.message}")
            null
        }
    }

    /**
     * 把 VPS 返回的 pending_messages（JSONArray）转成可回传的 List<Map>。
     * 元素为 OpenAI 风格消息对象（role/content/tool_call_id/tool_calls 等），
     * JSON 值原样保留（JSONObject/JSONArray 直接嵌套，null 用 JSONObject.NULL）。
     */
    private fun pendingMessagesToList(pending: JSONArray): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (i in 0 until pending.length()) {
            val item = pending.opt(i)
            if (item is JSONObject) {
                val m = LinkedHashMap<String, Any>()
                item.keys().forEach { key -> m[key] = item.opt(key) }
                result.add(m)
            } else if (item != null) {
                // 非对象消息兜底（理论上不会出现）
                result.add(mapOf("role" to "user", "content" to item.toString()))
            }
        }
        return result
    }

    override suspend fun wake(
        snapshot: ConversationSnapshot,
        timeoutSeconds: Int
    ): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null

        try {
            val wakePrompt = "根据当前状态判断你是否需要主动说话，如果需要返回 speak 指令 JSON，否则返回空"
            val messages = listOf(mapOf("role" to "user", "content" to wakePrompt))
            val phoneSnapshot = snapshot.toInjectJson()
            val response = postProxyChat(messages, phoneSnapshot, timeoutSeconds)
                ?: return@withContext null

            response.optString("content", null)
        } catch (e: Exception) {
            Log.e(TAG, "VPS 直连 wake 调用失败: ${e.message}")
            null
        }
    }

    /**
     * README §九 唤醒协议的直连实现（唤醒上下文版）。
     *
     * 上下文由 AnzhiWakeManager.buildWakeContext 组装，形如
     *   { "action": "wake", "reminder": "...", "context": {...}, "note": "..." }
     * 这里把外层原样作为 user message、内层 context 作为 phone_snapshot 上传，
     * 安知的回复应当是 { "actions": [...], "next_wake_minutes": N }。
     *
     * 与 wake(snapshot) 的区别：那条走 ConversationSnapshot（聊天侧的数据结构），
     * 唤醒链路手里只有 WakeManager 的 JSON，不再造一份快照对象。
     */
    suspend fun wakeWithContext(
        wakeContext: JSONObject,
        timeoutSeconds: Int = 90
    ): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "唤醒轮次无法发出：api_url 或 token 为空（在设置界面填一次即可）")
            return@withContext null
        }
        try {
            val messages = listOf(
                mapOf("role" to "user", "content" to wakeContext.toString())
            )
            val phoneSnapshot = wakeContext.optJSONObject("context") ?: wakeContext
            val response = postProxyChat(messages, phoneSnapshot, timeoutSeconds)
                ?: return@withContext null
            if (response.isNull("content")) null else response.optString("content", null)
        } catch (e: Exception) {
            Log.e(TAG, "VPS 直连唤醒调用失败: ${e.message}")
            null
        }
    }

    override suspend fun writeDiary(
        date: String,
        chatHistory: String,
        timeoutSeconds: Int
    ): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null

        try {
            val prompt = buildString {
                append("今天是 $date。请回顾今天你和 Cami 的对话，写一篇日记。\n")
                append("写完后直接存到 Google Keep。\n")
                append("忽略系统推送、状态上报、自动提醒——只看你和 Cami 的对话。\n\n")
                append("今天的对话记录：\n$chatHistory")
            }
            val messages = listOf(mapOf("role" to "user", "content" to prompt))
            // 写日记不携带手机快照
            val response = postProxyChat(messages, null, timeoutSeconds)
                ?: return@withContext null

            response.optString("content", null)
        } catch (e: Exception) {
            Log.e(TAG, "VPS 直连 diary 调用失败: ${e.message}")
            null
        }
    }

    override fun shutdown() {
        Log.i(TAG, "VPS 直连供应者已关闭")
    }

    /**
     * POST ${vpsBaseUrl}/proxy_chat。
     *
     * header：Content-Type: application/json + X-Anzhi-Token: <apiToken>
     * body：{"messages": [...], "phone_snapshot": {...}（非空时，嵌套 JSONObject，VPS 端按 dict 解析）}
     * messages 支持任意 OpenAI 风格消息（user/assistant/tool），
     * 每个消息对象的所有 key（role/content/tool_call_id 等）原样序列化。
     *
     * 注意：不带 provider 字段——VPS 端会用默认线路，手机端不持有 key。
     * 所有异常 catch 掉返回 null。
     */
    private fun postProxyChat(
        messages: List<Map<String, Any>>,
        phoneSnapshot: JSONObject?,
        timeoutSeconds: Int
    ): JSONObject? {
        return try {
            val url = URL("${vpsBaseUrl.trimEnd('/')}/proxy_chat")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Anzhi-Token", apiToken)
            }

            val body = JSONObject().apply {
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply {
                            // 通用序列化：role/content/tool_call_id/tool_calls 等所有 key 原样保留
                            m.forEach { (k, v) -> put(k, v) }
                        })
                    }
                })
                if (phoneSnapshot != null) {
                    put("phone_snapshot", phoneSnapshot)
                }
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val responseText = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.w(TAG, "VPS /proxy_chat HTTP ${conn.responseCode}: ${errorBody.take(300)}")
                conn.disconnect()
                return null
            }

            conn.disconnect()
            JSONObject(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "VPS /proxy_chat 调用异常: ${e.message}")
            null
        }
    }
}
