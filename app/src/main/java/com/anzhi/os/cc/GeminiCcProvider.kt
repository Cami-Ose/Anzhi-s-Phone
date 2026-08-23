package com.anzhi.os.cc

import android.util.Log
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini 3.5 Flash CC 供应者。
 *
 * 用 Gemini 3.5 Flash 的 function calling 能力执行 CC agent loop。
 * 前端/UI 代码质量优于 DeepSeek，适合安知写网页、小组件等场景。
 *
 * API 文档：https://ai.google.dev/gemini-api/docs/function-calling
 *
 * @param apiKey Gemini API Key
 * @param model Gemini 模型名，默认 gemini-3.5-flash
 */
class GeminiCcProvider(
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : CcProvider {

    companion object {
        private const val TAG = "GeminiCcProvider"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        // ── HTTP 超时 ──
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 120_000L  // Gemini 可能在想，给久一点

        // ── 重试 ──
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 2_000L

        // ── 熔断 ──
        private const val FAILURE_THRESHOLD = 5

        // ── Agent loop ──
        private const val MAX_TOOL_ROUNDS = 15

        // ── token 限制 ──
        private const val DEFAULT_MAX_TOKENS = 8192

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override val name: String get() = "Gemini $model"

    @Volatile
    override var available: Boolean = true
        private set

    private var consecutiveFailures = 0
    private var currentExecutor: CcExecutor? = null

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 重置熔断状态 */
    fun resetAvailable() {
        available = true
        consecutiveFailures = 0
        Log.i(TAG, "熔断状态已重置")
    }

    // ═══════════════════════════════════════════
    // CcProvider 接口实现
    // ═══════════════════════════════════════════

    override suspend fun executeCcLoop(
        systemPrompt: String,
        userTask: String,
        workspaceDir: String,
        callback: CcProgressCallback?
    ): CcLoopResult = withContext(Dispatchers.IO) {
        if (!available) {
            val msg = "Gemini 已被标记为不可用（连续失败 $consecutiveFailures 次）"
            Log.w(TAG, msg)
            return@withContext CcLoopResult(false, 0, emptyList(), null, msg)
        }

        // 确保工作目录存在
        val wsDir = java.io.File(workspaceDir)
        if (!wsDir.exists() && !wsDir.mkdirs()) {
            val msg = "无法创建 CC 工作目录: $workspaceDir"
            Log.e(TAG, msg)
            return@withContext CcLoopResult(false, 0, emptyList(), null, msg)
        }

        // 创建执行器（每个 loop 一个新的，以防 workspace 变动）
        val executor = CcExecutor(workspaceDir).also { currentExecutor = it }

        // ── Gemini 用 contents（消息历史）──
        val contents = mutableListOf<JSONObject>()

        // 1. 系统指令（Gemini 用 system_instruction，不是 contents 第一条）
        //    我们会合到第一个 user message 里
        // 2. 用户任务
        contents.add(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "$systemPrompt\n\n$userTask")
                })
            })
        })

        val filesProduced = mutableListOf<CcFileOutput>()
        var round = 0
        var finalMessage: String? = null
        var errorReason: String? = null

        val toolsDef = buildGeminiToolDefinitions()

        Log.i(TAG, "Gemini CC agent loop 启动，workspace=$workspaceDir")

        while (round < MAX_TOOL_ROUNDS) {
            round++
            callback?.onRoundStart(round)

            // 调用 Gemini API
            val response = executeGeminiChat(contents, toolsDef)
            if (response == null) {
                errorReason = "Gemini CC round $round: API 调用失败"
                Log.e(TAG, errorReason)
                break
            }

            val (content, functionCalls) = response

            // 情况 1：无 function_call → 任务结束
            if (functionCalls.isEmpty()) {
                finalMessage = content
                Log.i(TAG, "Gemini CC loop 结束：无 function_call，round=$round")
                break
            }

            // 情况 2：有 function_call → 执行
            // 先把 model 回复加入 contents
            val modelParts = JSONArray()
            if (content != null) {
                modelParts.put(JSONObject().apply { put("text", content) })
            }
            for (fc in functionCalls) {
                modelParts.put(JSONObject().apply {
                    put("functionCall", JSONObject().apply {
                        put("name", fc.name)
                        put("args", fc.args)
                    })
                })
            }
            contents.add(JSONObject().apply {
                put("role", "model")
                put("parts", modelParts)
            })

            // 执行每个 function_call
            var hasFinish = false
            val functionResponses = JSONArray()

            for (fc in functionCalls) {
                val tc = CcToolCall("gemini_${fc.name}", fc.name, fc.args)
                callback?.onToolCall(tc)

                when (fc.name) {
                    "finish" -> {
                        val summary = fc.args.optString("summary", "任务完成")
                        finalMessage = summary
                        hasFinish = true
                        Log.i(TAG, "Gemini CC 结束：finish — $summary")
                        functionResponses.put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", "finish")
                                put("response", JSONObject().apply {
                                    put("result", "finish acknowledged: $summary")
                                })
                            })
                        })
                    }
                    "write_file" -> {
                        val path = fc.args.optString("path", "")
                        val fileContent = fc.args.optString("content", "")
                        val (ok, msg) = executor.executeWriteFile(path, fileContent)
                        callback?.onToolResult("write_file", ok, msg)
                        if (ok) filesProduced.add(CcFileOutput(path, fileContent))
                        functionResponses.put(buildFunctionResponse("write_file", ok, msg))
                    }
                    "read_file" -> {
                        val path = fc.args.optString("path", "")
                        val (ok, msg) = executor.executeReadFile(path)
                        callback?.onToolResult("read_file", ok, msg)
                        functionResponses.put(buildFunctionResponse("read_file", ok, msg))
                    }
                    "run_command" -> {
                        val cmd = fc.args.optString("cmd", "")
                        val (ok, msg) = executor.executeRunCommand(cmd)
                        callback?.onToolResult("run_command", ok, msg)
                        functionResponses.put(buildFunctionResponse("run_command", ok, msg))
                    }
                    else -> {
                        val msg = "未知 tool: ${fc.name}"
                        Log.w(TAG, msg)
                        callback?.onToolResult(fc.name, false, msg)
                        functionResponses.put(buildFunctionResponse(fc.name, false, msg))
                    }
                }
            }

            // 把 function responses 加入 contents（Gemini 用 role="function"）
            // Gemini 要求每个 functionResponse 单独一条 content
            // 但也可合并为多条。为简单，我们全塞到一条 content 的 parts 里
            // 实际上 Gemini 的 role="function" 要求每个 response 独立一条 content
            // 所以我们每条 functionResponse 独立加
            for (i in 0 until functionResponses.length()) {
                val fr = functionResponses.getJSONObject(i)
                contents.add(JSONObject().apply {
                    put("role", "function")
                    put("parts", JSONArray().put(fr))
                })
            }

            if (hasFinish) break
        }

        // 熔断检查
        if (round >= MAX_TOOL_ROUNDS && finalMessage == null) {
            errorReason = "Gemini CC loop 达到最大轮次 $MAX_TOOL_ROUNDS，强制中止。" +
                "等安知下次唤醒时向 Cami 求助。"
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
        Log.i(TAG, "Gemini CC agent loop 结束: success=$success, rounds=$round, files=${filesProduced.size}")
        result
    }

    // ═══════════════════════════════════════════
    // Gemini API function calling
    // ═══════════════════════════════════════════

    /**
     * Gemini function calling 响应解析结果。
     */
    private data class GeminiResponse(
        val content: String?,
        val functionCalls: List<GeminiFunctionCall>
    )

    private data class GeminiFunctionCall(
        val name: String,
        val args: JSONObject
    )

    /**
     * 构建 Gemini 格式的 tool definitions。
     */
    private fun buildGeminiToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("functionDeclarations", JSONArray().apply {
                    // write_file
                    put(JSONObject().apply {
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
                    // read_file
                    put(JSONObject().apply {
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
                    // run_command
                    put(JSONObject().apply {
                        put("name", "run_command")
                        put("description", "Execute a shell command in the CC workspace. " +
                            "Use this to run code, compile, test, install dependencies, etc. " +
                            "The command runs with CWD set to the workspace directory.")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("cmd", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Shell command to execute")
                                })
                            })
                            put("required", JSONArray(listOf("cmd")))
                        })
                    })
                    // finish
                    put(JSONObject().apply {
                        put("name", "finish")
                        put("description", "Signal that the task is complete. " +
                            "Call this when all work is done — all files written, all commands executed successfully.")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("summary", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Summary of what was accomplished. " +
                                        "List the files created/modified and what they do.")
                                })
                            })
                            put("required", JSONArray(listOf("summary")))
                        })
                    })
                })
            })
        }
    }

    /**
     * 调用 Gemini API 的 generateContent 端点。
     */
    private suspend fun executeGeminiChat(
        contents: List<JSONObject>,
        tools: JSONArray
    ): GeminiResponse? {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                for (c in contents) put(c)
            })
            put("tools", tools)
            put("tool_config", JSONObject().apply {
                put("function_calling_config", JSONObject().apply {
                    put("mode", "auto")
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", DEFAULT_MAX_TOKENS)
                put("temperature", 0.1)
            })
            // system_instruction 作为顶层字段
            // 但我们已合到第一个 user message
        }

        val payloadStr = requestBody.toString()
        val url = "$API_BASE/$model:generateContent?key=$apiKey"

        Log.d(TAG, "Gemini CC 请求: ${payloadStr.length} chars, url=$url")

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string() ?: ""
                response.close()

                if (code in 200..299) {
                    consecutiveFailures = 0
                    return parseGeminiResponse(body)
                }

                when {
                    code == 429 -> {
                        val waitMs = BASE_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "Gemini CC 429, ${waitMs}ms 后重试 (attempt ${attempt + 1})")
                        delay(waitMs)
                    }
                    code in 500..599 -> {
                        Log.w(TAG, "Gemini CC HTTP $code (attempt ${attempt + 1}): ${body.take(200)}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                        } else {
                            markFailure()
                            return null
                        }
                    }
                    else -> {
                        Log.e(TAG, "Gemini CC HTTP $code: ${body.take(300)}")
                        markFailure()
                        return null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Gemini CC 网络异常 (attempt ${attempt + 1}): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                } else {
                    markFailure()
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini CC 异常: ${e.message}", e)
                markFailure()
                return null
            }
        }

        markFailure()
        return null
    }

    /**
     * 解析 Gemini 响应，提取 text 和 functionCall。
     */
    private fun parseGeminiResponse(body: String): GeminiResponse? {
        return try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                // 检查是否有 prompt feedback
                val feedback = json.optJSONObject("promptFeedback")
                if (feedback != null) {
                    val blockReason = feedback.optString("blockReason", "")
                    Log.w(TAG, "Gemini 请求被阻止: $blockReason")
                }
                return null
            }

            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: return null
            val parts = content.optJSONArray("parts") ?: JSONArray()

            var text: String? = null
            val functionCalls = mutableListOf<GeminiFunctionCall>()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    val t = part.getString("text")
                    if (t.isNotBlank()) text = t
                }
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    functionCalls.add(GeminiFunctionCall(name, args))
                }
            }

            GeminiResponse(
                content = text,
                functionCalls = functionCalls
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 Gemini 响应失败: ${e.message}")
            null
        }
    }

    /**
     * 构建 Gemini functionResponse part。
     */
    private fun buildFunctionResponse(
        name: String,
        success: Boolean,
        message: String
    ): JSONObject {
        return JSONObject().apply {
            put("functionResponse", JSONObject().apply {
                put("name", name)
                put("response", JSONObject().apply {
                    put("result", if (success) "OK: $message" else "ERROR: $message")
                })
            })
        }
    }

    private fun markFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            available = false
            Log.e(TAG, "Gemini 连续失败 $consecutiveFailures 次，标记为不可用")
        }
    }
}
