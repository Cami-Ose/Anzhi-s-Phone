package com.anzhi.os.ui.dashboard

import com.anzhi.os.ui.components.TempCardData

/**
 * 安知仪表盘统一状态 — 所有组件数据 + 全局控制状态。
 *
 * 由 AnzhiDashboardActivity 持有 MutableStateFlow<DashboardState>,
 * 系统回调更新字段 → Compose collectAsState() 自动重组。
 */
data class DashboardState(
    // ── 基础信息 ──
    val time: String = "",
    val dayOfWeek: String = "",
    val date: String = "",
    val greetingOverride: String? = null,

    // ── 天气 ──
    val weather: WeatherData = WeatherData(),

    // ── 消息 ──
    val messages: List<MessageItem> = emptyList(),

    // ── 日历 ──
    val calendar: List<CalendarEvent> = emptyList(),

    // ── 待办 ──
    val todos: List<TodoItem> = emptyList(),

    // ── 临时卡片 ──
    val tempCard: TempCardData? = null,
    val tempCards: List<TempCardData> = emptyList(),

    // ── 状态 ──
    val batteryPercent: Int = 50,
    val batteryCharging: Boolean = false,
    val location: String = "",
    val wifiOn: Boolean = false,
    val dndOn: Boolean = false,
    val brightness: Int = 80,
    val volume: Int = 60,
    val bodyConnected: Boolean = false,

    // ── 布局 ──
    val widgetOrder: List<String> = listOf(
        "greeting", "weather", "messages", "calendar", "todos", "tempcard"
    ),

    // ── 面板状态 ──
    val activePanel: ActivePanel = ActivePanel.NONE,

    // ── 锁屏桥接状态 ──
    val lockScreenMessage: String? = null
)

/** 活跃面板枚举 */
enum class ActivePanel {
    NONE, CONTROL, DRAWER, CHAT
}

// ── 子数据类 ──

data class WeatherData(
    val condition: String = "—",
    val temp: Int = 0,
    val high: Int = 0,
    val low: Int = 0,
    val icon: String = "",
    val available: Boolean = false
)

data class MessageItem(
    val id: String,
    val sender: String,
    val text: String,
    val time: Long,
    val isImportant: Boolean = false,
    val isAnzhiMessage: Boolean = false
)

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean = false,
    val location: String = ""
)

data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val priority: String = "normal"
)
