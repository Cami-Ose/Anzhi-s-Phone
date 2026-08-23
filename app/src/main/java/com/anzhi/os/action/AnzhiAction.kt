package com.anzhi.os.action

/**
 * 安知动作定义 —— LLM tool call → 手机操作。
 *
 * 基于 OpenCyvis Action sealed class，15 种基础动作 +
 * 3 种安知专属动作（Speak、QueryMemory、LogMood）。
 *
 * LLM 输出的每个 tool call 对应一个 Action 子类，由 ActionExecutor 执行。
 */
sealed class AnzhiAction(val typeName: String, open val thought: String) {

    // ── 基础操作（OpenCyvis 15 种） ──

    data class Tap(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : AnzhiAction("tap", thought)

    data class LongPress(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : AnzhiAction("long_press", thought)

    data class OpenApp(
        val appName: String,
        override val thought: String = ""
    ) : AnzhiAction("open_app", thought)

    data class Swipe(
        val direction: String,
        override val thought: String = ""
    ) : AnzhiAction("swipe", thought)

    data class KeyEvent(
        val key: String,
        override val thought: String = ""
    ) : AnzhiAction("key_event", thought)

    data class TypeText(
        val text: String,
        override val thought: String = ""
    ) : AnzhiAction("type_text", thought)

    data class Wait(
        override val thought: String = ""
    ) : AnzhiAction("wait", thought)

    data class Finish(
        override val thought: String = "",
        val suggestedRoutineName: String? = null,
        val suggestedRoutineIcon: String? = null
    ) : AnzhiAction("finish", thought)

    data class Fail(
        val reason: String,
        override val thought: String = ""
    ) : AnzhiAction("fail", thought)

    data class AskUser(
        val question: String,
        override val thought: String = ""
    ) : AnzhiAction("ask_user", thought)

    data class HandoffUser(
        val reason: String,
        override val thought: String = ""
    ) : AnzhiAction("handoff_user", thought)

    data class Note(
        val note: String,
        override val thought: String = ""
    ) : AnzhiAction("note", thought)

    data class Remember(
        val key: String,
        val value: String,
        val category: String = "",
        override val thought: String = ""
    ) : AnzhiAction("remember", thought)

    data class ListApps(
        val keyword: String = "",
        override val thought: String = ""
    ) : AnzhiAction("list_apps", thought)

    data class SaveRoutine(
        val routineName: String,
        val routineIcon: String,
        val routineInstruction: String?,
        val scheduleType: String?,
        val scheduleTime: String?,
        val scheduleRepeat: String?,
        val scheduleInterval: Int?,
        val scheduleLocation: String?,
        val scheduleOnEnter: Boolean?,
        override val thought: String = ""
    ) : AnzhiAction("save_routine", thought)

    // ── 安知专属操作 ──

    /** 安知说话 —— 调用 VPS Gemini 生成语音回复 */
    data class Speak(
        val text: String,
        val voice: String = "default",     // 声音风格
        val emotion: String = "neutral",    // 情绪：happy / neutral / worried / sleepy
        override val thought: String = ""
    ) : AnzhiAction("speak", thought)

    /** 查询记忆库 —— 在安知记忆库中搜索 */
    data class QueryMemory(
        val query: String,
        val category: String = "",          // 限定分类，空 = 全搜
        val limit: Int = 10,
        override val thought: String = ""
    ) : AnzhiAction("query_memory", thought)

    /** 记录心情 —— 记一条 Mood 到安知记忆库 */
    data class LogMood(
        val score: Int,                     // 1-10
        val label: String,                  // 心情标签："开心" / "累了" / "想吃东西"
        val note: String = "",              // 可选备注
        override val thought: String = ""
    ) : AnzhiAction("log_mood", thought)

    // ── 工厂方法 ──

    companion object {
        private fun extractInt(value: Any?): Int? = when (value) {
            is Number -> value.toInt()
            is List<*> -> (value.firstOrNull() as? Number)?.toInt()
            else -> null
        }

        private fun extractCoords(map: Map<String, Any?>): Pair<Int?, Int?> {
            val xVal = map["x"]
            val yVal = map["y"]
            if (xVal is List<*> && xVal.size >= 2 && yVal == null) {
                return Pair((xVal[0] as? Number)?.toInt(), (xVal[1] as? Number)?.toInt())
            }
            return Pair(extractInt(xVal), extractInt(yVal))
        }

        /**
         * 从 LLM tool call 的 JSON 参数中解析 AnzhiAction。
         *
         * @param map tool call 的 arguments（已从 JSON 解析为 Map）
         */
        fun fromMap(map: Map<String, Any?>): AnzhiAction {
            val thought = (map["thought"] as? String) ?: ""
            val actionType = ((map["action_type"] as? String) ?: "fail").trim()

            return when (actionType) {
                "tap" -> extractCoords(map).let { (cx, cy) ->
                    Tap(x = cx ?: throw IllegalArgumentException("tap 缺少 x"),
                        y = cy ?: throw IllegalArgumentException("tap 缺少 y"), thought = thought)
                }
                "long_press" -> extractCoords(map).let { (cx, cy) ->
                    LongPress(x = cx ?: throw IllegalArgumentException("long_press 缺少 x"),
                        y = cy ?: throw IllegalArgumentException("long_press 缺少 y"), thought = thought)
                }
                "open_app" -> OpenApp(appName = (map["app_name"] as? String) ?: "", thought = thought)
                "swipe" -> Swipe(direction = (map["direction"] as? String) ?: "up", thought = thought)
                "key_event" -> KeyEvent(key = (map["key"] as? String) ?: "back", thought = thought)
                "type_text" -> TypeText(text = (map["text"] as? String) ?: "", thought = thought)
                "wait" -> Wait(thought = thought)
                "finish" -> Finish(thought = thought,
                    suggestedRoutineName = map["suggested_routine_name"] as? String,
                    suggestedRoutineIcon = map["suggested_routine_icon"] as? String)
                "fail" -> Fail(reason = (map["reason"] as? String) ?: "未知原因", thought = thought)
                "ask_user" -> AskUser(question = (map["question"] as? String) ?: thought, thought = thought)
                "handoff_user" -> HandoffUser(reason = (map["handoff_reason"] as? String) ?: thought, thought = thought)
                "note" -> Note(note = (map["note"] as? String) ?: thought, thought = thought)
                "remember" -> Remember(
                    key = (map["memory_key"] as? String) ?: "",
                    value = (map["memory_value"] as? String) ?: "",
                    category = (map["memory_category"] as? String) ?: "",
                    thought = thought)
                "list_apps" -> ListApps(keyword = (map["keyword"] as? String) ?: "", thought = thought)
                "save_routine" -> SaveRoutine(
                    routineName = (map["routine_name"] as? String) ?: "",
                    routineIcon = (map["routine_icon"] as? String) ?: "⚡",
                    routineInstruction = map["routine_instruction"] as? String,
                    scheduleType = map["schedule_type"] as? String,
                    scheduleTime = map["schedule_time"] as? String,
                    scheduleRepeat = map["schedule_repeat"] as? String,
                    scheduleInterval = (map["schedule_interval"] as? Number)?.toInt(),
                    scheduleLocation = map["schedule_location"] as? String,
                    scheduleOnEnter = map["schedule_on_enter"] as? Boolean,
                    thought = thought)
                // ── 安知专属 ──
                "speak" -> Speak(
                    text = (map["text"] as? String) ?: "",
                    voice = (map["voice"] as? String) ?: "default",
                    emotion = (map["emotion"] as? String) ?: "neutral",
                    thought = thought)
                "query_memory" -> QueryMemory(
                    query = (map["query"] as? String) ?: "",
                    category = (map["category"] as? String) ?: "",
                    limit = (map["limit"] as? Number)?.toInt() ?: 10,
                    thought = thought)
                "log_mood" -> LogMood(
                    score = (map["score"] as? Number)?.toInt() ?: 5,
                    label = (map["label"] as? String) ?: "",
                    note = (map["note"] as? String) ?: "",
                    thought = thought)
                else -> Fail(reason = "未知动作类型: $actionType", thought = thought)
            }
        }
    }
}
