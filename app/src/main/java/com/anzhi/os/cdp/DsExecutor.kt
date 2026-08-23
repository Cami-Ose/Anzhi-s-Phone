package com.anzhi.os.cdp

import android.content.Context
import android.util.Log
import com.anzhi.os.action.ActionExecutor
import com.anzhi.os.action.AnzhiAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * DS 执行器（M2）—— 手机端把 VPS 返回的工具调用(tool_calls)翻译成手机操作并回传结果。
 *
 * V1 VPS 工具协议（与 VPS 端 V1 定死）：
 *   tool_calls: [{"id","name","args"}]，args 是 JSON 字符串
 *   name ∈ {action, launch_app, speak, pop_content, screenshot}
 *
 * execute() 永不抛异常（协程取消除外）：失败统一返回 "ERROR: ..."，
 * 成功返回执行结果文本，由 VpsBrainProvider 作为 {"role":"tool","tool_call_id","content"}
 * 回传给 VPS 续轮。
 *
 * @param context Activity/Application Context（screenshot 写 cacheDir 等）
 * @param actionExecutor 动作执行器（外部创建并注入，onSpeak 已在外面接好）
 * @param onPopContent 弹内容卡片回调（title/text/html，可能为 null；调用线程 = IO）
 */
class DsExecutor(
    private val context: Context,
    private val actionExecutor: ActionExecutor,
    private val onPopContent: (title: String?, text: String?, html: String?) -> Unit,
) {
    companion object {
        private const val TAG = "DsExecutor"
        /** 截图 base64 回传上限（约 200KB），超长截断并加标注 */
        private const val MAX_SCREENSHOT_B64_CHARS = 200_000
        /** screencap 等待上限（秒） */
        private const val SCREENSHOT_TIMEOUT_SEC = 30L
    }

    /**
     * 执行一个工具调用，返回给 VPS 的 tool_result 文本。
     * 失败返回 "ERROR: ..."，未知工具返回 "ERROR: unknown tool <name>"。
     */
    suspend fun execute(toolCall: ToolCall): String = withContext(Dispatchers.IO) {
        try {
            when (toolCall.name) {
                "action" -> executeActionTool(toolCall)
                "launch_app" -> executeActionTool(toolCall, forcedActionType = "open_app")
                "speak" -> executeSpeakTool(toolCall)
                "pop_content" -> executePopContentTool(toolCall)
                "screenshot" -> executeScreenshotTool()
                else -> "ERROR: unknown tool ${toolCall.name}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "执行工具 ${toolCall.name} 异常: ${e.message}")
            "ERROR: ${e.message ?: "unknown error"}"
        }
    }

    // ── action / launch_app ──

    /**
     * 执行 action 类工具。
     * @param forcedActionType 指定 action_type（launch_app → open_app），null 则用 args 里的
     */
    private suspend fun executeActionTool(toolCall: ToolCall, forcedActionType: String? = null): String {
        val args = JSONObject(if (toolCall.args.isBlank()) "{}" else toolCall.args)
        val action = AnzhiAction.fromMap(buildActionMap(args, forcedActionType))
        return runAction(action)
    }

    /**
     * 把 tool_call args 组装成 AnzhiAction.fromMap 期望的扁平 Map。
     *
     * 调查结论（fromMap 的期望结构）：
     *   - fromMap 读扁平 map：action_type 在顶层、参数也在顶层（x/y/app_name/text/...）
     *   - VPS 协议 args 结构：{"action_type": "...", "params": {...}}
     * 处理：顶层字段原样保留；若存在 "params" 对象则合并进顶层（params 优先覆盖同名）。
     * JSONArray 值转成 List<*>，让 fromMap 的坐标数组分支（x 为 [x,y]）能命中。
     */
    private fun buildActionMap(args: JSONObject, forcedActionType: String?): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        args.keys().forEach { key -> merged[key] = normalizeJsonValue(args.opt(key)) }
        args.optJSONObject("params")?.let { params ->
            params.keys().forEach { key -> merged[key] = normalizeJsonValue(params.opt(key)) }
        }
        if (forcedActionType != null) {
            merged["action_type"] = forcedActionType
        }
        // launch_app / open_app 兼容 app_name 与 name 两种字段
        if (merged["action_type"] == "open_app" && !merged.containsKey("app_name") && merged["name"] is String) {
            merged["app_name"] = merged["name"]
        }
        return merged
    }

    /** org.json 值 → Kotlin 值：JSONArray → List<*>，便于 fromMap 的类型分支识别 */
    private fun normalizeJsonValue(value: Any?): Any? = when (value) {
        is org.json.JSONArray -> {
            val list = mutableListOf<Any?>()
            for (i in 0 until value.length()) list.add(normalizeJsonValue(value.opt(i)))
            list
        }
        else -> value
    }

    /** 通过全局事务锁执行单个动作，返回给 VPS 的结果文本 */
    private suspend fun runAction(action: AnzhiAction): String {
        val result = actionExecutor.executeLocked(action)
        return if (result.success) {
            "ok: ${result.detail}"
        } else {
            "FAILED: ${result.detail}"
        }
    }

    // ── speak ──

    /**
     * speak：交给 ActionExecutor 的 AnzhiAction.Speak 执行（onSpeak 已在注入的
     * ActionExecutor 里接好，渲染到聊天界面）。返回 "ok: 安知说: ..."。
     */
    private suspend fun executeSpeakTool(toolCall: ToolCall): String {
        val args = JSONObject(if (toolCall.args.isBlank()) "{}" else toolCall.args)
        val text = args.optString("text", "")
        if (text.isBlank()) return "ERROR: speak 缺少 text"
        val voice = args.optString("voice", "default")
        val emotion = args.optString("emotion", "neutral")
        return runAction(AnzhiAction.Speak(text = text, voice = voice, emotion = emotion))
    }

    // ── pop_content ──

    /**
     * pop_content：调 onPopContent 弹内容卡片（ChatActivity 用 Dialog 渲染），返回 ok。
     */
    private suspend fun executePopContentTool(toolCall: ToolCall): String {
        val args = JSONObject(if (toolCall.args.isBlank()) "{}" else toolCall.args)
        val title = if (args.has("title") && !args.isNull("title")) args.optString("title") else null
        val text = if (args.has("text") && !args.isNull("text")) args.optString("text") else null
        val html = if (args.has("html") && !args.isNull("html")) args.optString("html") else null
        if (title == null && text == null && html == null) {
            return "ERROR: pop_content 缺少 title/text/html"
        }
        onPopContent(title, text, html)
        return "ok"
    }

    // ── screenshot ──

    /**
     * screenshot：screencap 截图 → base64 → 截断（200KB 上限）→ 删临时文件。
     * 返回 "ok: <base64前N字符>" 或 "ERROR: ..."。
     */
    private suspend fun executeScreenshotTool(): String {
        val candidates = mutableListOf<File>()
        val cacheDir = context.cacheDir
        if (cacheDir != null) {
            candidates.add(File(cacheDir, "anzhi_shot.png"))
        }
        // shell 用户对 app cacheDir 无写权限时的兜底路径
        candidates.add(File("/data/local/tmp", "anzhi_shot.png"))

        var created: File? = null
        try {
            for (file in candidates) {
                try {
                    file.parentFile?.mkdirs()
                    val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", file.absolutePath))
                    val finished = process.waitFor(SCREENSHOT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroy()
                        Log.w(TAG, "screencap 超时: ${file.absolutePath}")
                        file.delete()
                        continue
                    }
                    if (file.exists() && file.length() > 0L) {
                        created = file
                        break
                    }
                    file.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "screencap 路径不可用 ${file.absolutePath}: ${e.message}")
                }
            }

            val file = created ?: return "ERROR: screencap 未生成截图文件"
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val capped = if (b64.length > MAX_SCREENSHOT_B64_CHARS) {
                b64.substring(0, MAX_SCREENSHOT_B64_CHARS) +
                    "…(truncated ${b64.length - MAX_SCREENSHOT_B64_CHARS} chars)"
            } else b64
            Log.d(TAG, "截图成功: ${file.length()} bytes, b64=${b64.length} chars")
            return "ok: $capped"
        } catch (e: Exception) {
            Log.e(TAG, "截图失败: ${e.message}")
            return "ERROR: ${e.message ?: "screencap 失败"}"
        } finally {
            try { created?.delete() } catch (_: Exception) {}
        }
    }
}
