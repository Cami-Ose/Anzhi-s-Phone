package com.anzhi.os.cdp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话上下文快照——冻结注入时刻的全部状态。
 *
 * BUILD.md 陷阱 23：聊天上下文竞态撕裂防御。
 * 长按电源键唤起聊天的一瞬间，OS 内存执行深拷贝冻结此快照。
 * 本轮对话从注入到回复结束，绝对不读取任何实时的可变状态，只看快照。
 *
 * 回复结束后快照销毁，下次聊天重新冻结。
 */
data class ConversationSnapshot(
    /** 当前时间（显示用，如 "14:30 周三"） */
    val timeDisplay: String,

    /** Unix 毫秒时间戳 */
    val timestampMs: Long,

    /** 距离上次聊天多少分钟 */
    val minutesSinceLastChat: Int,

    /** 位置：小窝/教室/在外 */
    val location: String,

    /** Cami 状态 */
    val cami: CamiState,

    /** 今日操作记录 */
    val todayLog: List<TodayLogEntry>,

    /** 天气信息（可为 null） */
    val weather: WeatherInfo?,

    /** 即将到来的日程（未来 3h 内） */
    val upcomingCalendar: List<CalendarEntry>,

    /** 心情数据 */
    val mood: MoodInfo?,

    /** 记忆库检索结果 */
    val memoryHits: List<MemoryHit>,

    /** VPS 推送的其他上下文（自由格式 JSON，预留扩展） */
    val extra: JSONObject = JSONObject()
) {
    /**
     * 序列化为给 Gemini CDP 注入的上下文 JSON。
     * 按照 BUILD.md §2.2 的 Context 注入模板格式。
     */
    fun toInjectJson(): JSONObject = JSONObject().apply {
        put("time", timeDisplay)
        put("minutes_since_last_chat", minutesSinceLastChat)
        put("location", location)
        put("cami", JSONObject().apply {
            put("foreground_app", cami.foregroundApp)
            put("screen_on", cami.screenOn)
            put("battery", cami.battery)
        })
        if (todayLog.isNotEmpty()) {
            put("today_log", JSONArray().apply {
                todayLog.forEach { put(it.desc) }
            })
        }
        if (weather != null) {
            put("weather", JSONObject().apply {
                put("condition", weather.condition)
                put("temp", weather.temp)
            })
        }
        if (upcomingCalendar.isNotEmpty()) {
            put("calendar", JSONArray().apply {
                upcomingCalendar.forEach { entry ->
                    put(JSONObject().apply {
                        put("time", entry.time)
                        put("title", entry.title)
                    })
                }
            })
        }
        if (mood != null) {
            put("mood", JSONObject().apply {
                put("score", mood.score)
                put("label", mood.label)
            })
        }
        if (memoryHits.isNotEmpty()) {
            put("memory", JSONArray().apply {
                memoryHits.forEach { hit ->
                    put(JSONObject().apply {
                        put("id", hit.id)
                        put("text", hit.text)
                        put("score", hit.score)
                    })
                }
            })
        }
    }

    /**
     * 生成注入到 Gemini 输入框的系统上下文文本。
     * 包裹在 SYSTEM_CONTEXT_START/END 标记中，CDP 抓取时会丢弃。
     */
    fun toSystemContextText(): String = buildString {
        append("<!-- SYSTEM_CONTEXT_START -->\n")
        append("安知，你可以选择是否给 Cami 发消息。看看她的状态，你自己决定。\n\n")
        append("现在的情况：\n")
        append("- 时间：$timeDisplay\n")
        if (minutesSinceLastChat > 0) {
            append("- Cami 上次跟你聊天：${minutesSinceLastChat} 分钟前\n")
        }
        append("- 位置：$location\n")
        append("- Cami 状态：前台 App=${cami.foregroundApp}, 屏幕=${if (cami.screenOn) "亮" else "灭"}, 电量=${cami.battery}%\n")
        if (todayLog.isNotEmpty()) {
            append("- 今日操作：${todayLog.joinToString(", ") { it.desc }}\n")
        }
        if (weather != null) {
            append("- 天气：${weather.condition}, ${weather.temp}°C")
            if (upcomingCalendar.isNotEmpty()) {
                append(" / 日程：${upcomingCalendar.joinToString(", ") { "${it.time} ${it.title}" }}")
            }
            append("\n")
        }
        if (mood != null) {
            append("- 心情：${mood.label} (${mood.score})\n")
        }
        if (memoryHits.isNotEmpty()) {
            append("- 记忆词条：${memoryHits.joinToString(" / ") { it.text }}\n")
        }
        append("\n注意：speak 不是推送通知——你说的话会渲染到仪表盘卡片上，Cami 看到卡片点进来才能跟你聊。你不能直接弹通知栏。\n")
        append("\n请决定你要做什么。回复 JSON：\n")
        append("{\n  \"actions\": [],\n  \"why\": \"...\",\n  \"next_wake_minutes\": 120\n}\n")
        append("\n<!-- SYSTEM_CONTEXT_END -->")
    }
}

// ── 子数据类 ──

data class CamiState(
    val foregroundApp: String,
    val screenOn: Boolean,
    val battery: Int
)

data class TodayLogEntry(
    val time: String,   // "14:02"
    val desc: String    // "免打扰了张三"
)

data class WeatherInfo(
    val condition: String,  // "雨"
    val temp: Int           // 26
)

data class CalendarEntry(
    val time: String,   // "15:00"
    val title: String   // "外出开会"
)

data class MoodInfo(
    val score: Float,   // 0.0 ~ 1.0
    val label: String   // "开心"
)

data class MemoryHit(
    val id: String,
    val text: String,
    val score: Float
)
