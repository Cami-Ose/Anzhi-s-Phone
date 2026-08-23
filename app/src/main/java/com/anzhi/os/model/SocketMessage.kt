package com.anzhi.os.model

import org.json.JSONObject
import java.util.UUID

/**
 * 插座协议消息体。和安知后端约定好的格式。
 *
 * 消息格式（BUILD.md §2.1）：
 *   { "id": "<uuid>", "type": "<消息类型>", "version": 1, "payload": { ... } }
 *
 * 铁律：所有消息强制包含 id（UUID）、type（String）、version（Int，当前=1）。
 * 心跳 id 可省略，version 不可省。
 * id 用于异步请求-响应配对，version 用于协议演进。
 *
 * 手机 → VPS：汇报状态、发 UI 树、发截图结果、记忆库读写、心情事件、聊天同步
 * VPS → 手机：记忆搜索结果、心情数据、diary ack
 */
data class SocketMessage(
    val type: MessageType,
    val payload: JSONObject = JSONObject(),
    val id: String = UUID.randomUUID().toString(),
    val version: Int = 1
) {
    companion object {
        fun parse(json: String): SocketMessage {
            val obj = JSONObject(json)
            val type = MessageType.fromString(obj.getString("type"))
            val payload = obj.optJSONObject("payload") ?: JSONObject()
            val id = obj.optString("id", "")
            val version = obj.optInt("version", 1)
            return SocketMessage(type, payload, id, version)
        }
    }

    fun toJson(): String = JSONObject().apply {
        if (id.isNotEmpty()) put("id", id)
        put("type", type.key)
        put("version", version)
        put("payload", payload)
    }.toString()
}

/**
 * 消息类型枚举（BUILD.md §2.1 协议 + §十六 WebSocket 消息类型汇总）。
 *
 * === 手机收到的（VPS → 手机）===
 *   MEMORY_RESULT  — 记忆检索结果
 *   MEMORY_ACK     — 记忆存储确认
 *   MOOD_DATA      — 心情数据
 *   DIARY_ACK      — 日记存储确认
 *
 * === 手机发出的（手机 → VPS）===
 *   MEMORY_SEARCH  — 向量检索记忆词条
 *   MEMORY_STORE   — 存入新记忆
 *   MOOD_UPDATE    — 上报心情相关事件
 *   CHAT_SYNC      — 聊天记录同步（整轮上传）
 *   STATUS_UPDATE  — 设备状态上报
 *   DIARY_STORE    — 从 Keep 读取新日记，提交 VPS 存储
 *   HEARTBEAT      — 心跳保活
 *
 * === 手机本地使用（不走 WebSocket，但保留枚举值）===
 *   以下类型用于 Manager ↔ ActionExecutor 内部通信，
 *   以及 VPS CC 模式的 tool call 往返（Phase 1 过渡期间）：
 *   TAP / SWIPE / TYPE / LONG_PRESS / OPEN_APP / GO_HOME /
 *   BACK / SCREENCAP / GET_UI_TREE / KEYCODE / SPEAK /
 *   DASHBOARD_CARD / WIDGET_UPDATE / CLEAR_NOTIFICATION /
 *   WAKE / GET_STATE / FORCE_STOP / CLEAR_CACHE /
 *   UI_TREE / SCREENSHOT / NOTIFICATION / APP_OPENED /
 *   ACTION_DONE / TODAY_LOG / ERROR / USER_MESSAGE /
 *   TOOL_CALL / TOOL_RESULT / FINAL_RESPONSE
 */
enum class MessageType(val key: String) {
    // ── VPS → 手机（WebSocket，BUILD.md §2.1）──
    MEMORY_RESULT("memory_result"),
    MEMORY_ACK("memory_ack"),
    MOOD_DATA("mood_data"),
    DIARY_ACK("diary_ack"),

    // ── 手机 → VPS（WebSocket，BUILD.md §2.1）──
    MEMORY_SEARCH("memory_search"),
    MEMORY_STORE("memory_store"),
    MOOD_UPDATE("mood_update"),
    CHAT_SYNC("chat_sync"),
    STATUS_UPDATE("status_update"),
    DIARY_STORE("diary_store"),
    HEARTBEAT("heartbeat"),

    // ── VPS CC 模式：tool call 往返（过渡期，VPS 端 LLM 代理）──
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    FINAL_RESPONSE("final_response"),

    // ── 手机本地：安知 → ActionExecutor（不经过 WebSocket）──
    TAP("tap"),
    SWIPE("swipe"),
    TYPE("type"),
    LONG_PRESS("long_press"),
    OPEN_APP("open_app"),
    GO_HOME("go_home"),
    BACK("back"),
    SCREENCAP("screencap"),
    GET_UI_TREE("get_ui_tree"),
    KEYCODE("keycode"),
    SPEAK("speak"),
    DASHBOARD_CARD("dashboard_card"),
    WIDGET_UPDATE("widget_update"),
    CLEAR_NOTIFICATION("clear_notification"),
    WAKE("wake"),
    GET_STATE("get_state"),
    FORCE_STOP("force_stop"),
    CLEAR_CACHE("clear_cache"),

    // ── 手机本地：ActionExecutor → 安知（不经过 WebSocket）──
    UI_TREE("ui_tree"),
    SCREENSHOT("screenshot"),
    NOTIFICATION("notification"),
    APP_OPENED("app_opened"),
    ACTION_DONE("action_done"),
    TODAY_LOG("today_log"),
    ERROR("error"),
    USER_MESSAGE("user_message"),

    // ── 兜底 ──
    UNKNOWN("");

    companion object {
        fun fromString(key: String): MessageType =
            entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}
