package com.anzhi.os.cdp

/**
 * 工具调用描述（V1 阶段 VPS 端启用工具调用后返回）。
 * args 是 JSON 字符串。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val args: String
)

/**
 * 大脑聊天结果——文本 + 工具调用列表。
 *
 * M1 阶段 VPS /proxy_chat 只返回文本，toolCalls 恒为空列表。
 * V1 阶段 VPS 端启用工具调用后，这里会携带工具调用供手机执行。
 */
data class BrainChatResult(
    val text: String,
    val toolCalls: List<ToolCall>
)

/**
 * 安知大脑供应者接口。
 *
 * 实现 A（M1 默认·直连大脑）：VpsBrainProvider
 *   - 直连 VPS /proxy_chat HTTP API，手机端不持有 key，VPS 端全权注入上下文 + 查记忆库
 *
 * 实现 B（备用·官网灵魂）：GeminiCdpWebProvider
 *   - 走 Gemini 官网 CDP WebView，免费，语气纯正
 *
 * 自动切换逻辑：
 *   - primary（VPS 直连）连续失败 3 次 → 切到 fallback（CDP）
 *   - fallback（CDP）连续成功 2 次 → 切回 primary（VPS）
 */
interface AnzhiBrainProvider {

    /** 供应者名称（用于日志和状态栏显示） */
    val name: String

    /** 是否为默认供应者（实现 A = true，实现 B = false） */
    val isPrimary: Boolean

    /**
     * 向安知发送聊天请求。
     *
     * @param snapshot 对话上下文快照（冻结时刻的全部状态）
     * @param userMessage Cami 的原文
     * @param timeoutSeconds 超时秒数
     * @return 安知的回复结果（文本 + 工具调用），失败时返回 null
     */
    suspend fun chat(
        snapshot: ConversationSnapshot,
        userMessage: String,
        timeoutSeconds: Int = 60
    ): BrainChatResult?

    /**
     * 唤醒安知（让安知自己决定是否说话）。
     *
     * @param snapshot 当前上下文快照
     * @param timeoutSeconds 超时秒数
     * @return JSON 格式的安知决策：{"actions":[...], "why":"...", "next_wake_minutes":60}
     */
    suspend fun wake(
        snapshot: ConversationSnapshot,
        timeoutSeconds: Int = 30
    ): String?

    /**
     * 让安知写日记。
     *
     * @param date 日期字符串 "2026-06-28"
     * @param chatHistory 当天聊天记录摘要
     * @param timeoutSeconds 超时秒数
     * @return 日记文本，失败返回 null
     */
    suspend fun writeDiary(
        date: String,
        chatHistory: String,
        timeoutSeconds: Int = 120
    ): String?

    /**
     * 检查供应者当前是否可用。
     */
    fun isAvailable(): Boolean

    /**
     * 释放资源。
     */
    fun shutdown()
}
