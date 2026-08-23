package com.anzhi.os.ui.chat

/**
 * 安知聊天状态 — 所有聊天 UI 状态集中管理。
 *
 * 由 AnzhiChatActivity 持有 MutableStateFlow<ChatState>,
 * CDP/WebSocket 回调更新字段 → Compose collectAsState() 自动重组。
 */
data class ChatState(
    // ── 消息 ──
    val messages: List<ChatMessage> = emptyList(),

    // ── 会话 ──
    val currentSessionId: String = "",
    val sessions: List<ChatSessionInfo> = emptyList(),
    val sidebarOpen: Boolean = false,

    // ── 输入 ──
    val inputText: String = "",
    val inputLocked: Boolean = false,

    // ── 状态 ──
    val status: ChatStatus = ChatStatus.IDLE,

    // ── CDP 状态 ──
    val brainOnline: Boolean = true,
    val captchaRequired: Boolean = false
)

/** 聊天消息 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

enum class MessageRole {
    USER,        // Cami
    ANZHI,       // 安知 AI
    SYSTEM       // 系统提示
}

/** 聊天状态枚举 */
enum class ChatStatus {
    IDLE,           // 空闲
    THINKING,       // 安知正在思考/回复
    LOCKED,         // 输入锁定（如 CDP 冲突）
    DISCONNECTED,   // 与服务器断开
    CAPTCHA         // 需要解盾
}

/** 侧栏会话信息 */
data class ChatSessionInfo(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)
