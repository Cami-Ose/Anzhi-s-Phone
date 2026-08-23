package com.anzhi.os.llm

import android.util.Base64
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
 * Gemini Vision API 直调客户端（识图）。
 *
 * BUILD.md Step 4 — GeminiClient：
 *   - 手机直调 Gemini Vision API，不经过 VPS
 *   - 用于操作失败时的截图兜底识图（BUILD.md §八 异常处理）
 *   - 也用于通知图片识别、二维码/验证码解析等场景
 *
 * 触发场景：
 *   - 路径缓存未命中 → UI 树搜索失败 → 截图 VirtualDisplay → Gemini 识图兜底
 *   - 安知好奇心：看到 Cami 频繁用的 App → 截图看看是什么
 *
 * 铁律：
 *   - 截屏必须截 VirtualDisplay 虚拟屏，绝不截主屏（陷阱 5 / Invariant 2）
 *   - API Key 通过 x-goog-api-key HTTP Header 传递，不经过 URL
 *   - 识图结果只给安知（DeepSeek）做路径规划用，不直接给 Cami 看
 *
 * API 文档：https://ai.google.dev/gemini-api/docs/vision
 *
 * @param apiKey Gemini API Key
 * @param model 模型名，默认 gemini-2.0-flash（支持图片，便宜）
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash"
) {
    companion object {
        private const val TAG = "GeminiClient"
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        // ── HTTP 超时 ──
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 60_000L

        // ── 重试 ──
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 2_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 从环境变量风格配置创建客户端。
         */
        fun fromEnv(
            apiKey: String,
            model: String = "gemini-2.0-flash"
        ): GeminiClient = GeminiClient(apiKey, model)
    }

    // ── OkHttp 连接池 ──
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)  // 自己管理重试
        .build()

    @Volatile
    var available: Boolean = true
        private set

    private var consecutiveFailures = 0

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /**
     * 单张图片识别。
     *
     * 典型用法——操作失败截图兜底：
     * ```
     * val prompt = """
     *   这是微信的设置页面截图。请找到"消息免打扰"开关的位置，
     *   用坐标描述它的屏幕位置（大约 x, y 比例，0-1 之间）。
     *   回复格式：{"found": true, "x": 0.5, "y": 0.3, "description": "消息免打扰开关在页面中部"}
     * """
     * val result = geminiClient.analyzeImage(screenshotBytes, "image/png", prompt)
     * // → {"found": true, "x": 0.5, "y": 0.3, ...}
     * ```
     *
     * @param imageBytes 图片二进制数据（PNG/JPEG）
     * @param mimeType 图片 MIME 类型（"image/png" / "image/jpeg"）
     * @param prompt 给 Gemini 的文字指令
     * @param temperature 温度，默认 0.1（识图用低温）
     * @return Gemini 回复文本，失败返回 null
     */
    suspend fun analyzeImage(
        imageBytes: ByteArray,
        mimeType: String = "image/png",
        prompt: String,
        temperature: Double = 0.1
    ): String? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "Gemini Vision 已被标记为不可用")
            return@withContext null
        }

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        analyzeWithImageData(base64Image, mimeType, prompt, temperature)
    }

    /**
     * 多张图片识别。
     *
     * 用于连续截图对比（操作前后对比）或多张通知截图分析。
     *
     * @param images 图片列表：每项为 Pair(二进制数据, MIME类型)
     * @param prompt 给 Gemini 的文字指令
     * @param temperature 温度
     * @return Gemini 回复文本，失败返回 null
     */
    suspend fun analyzeImages(
        images: List<Pair<ByteArray, String>>,
        prompt: String,
        temperature: Double = 0.1
    ): String? = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "Gemini Vision 已被标记为不可用")
            return@withContext null
        }
        if (images.isEmpty()) {
            Log.w(TAG, "analyzeImages: 空图片列表")
            return@withContext null
        }

        // 构建多图 parts
        val parts = JSONArray()
        for ((bytes, mimeType) in images) {
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            parts.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64)
                })
            })
        }
        parts.put(JSONObject().apply {
            put("text", prompt)
        })

        executeVisionRequest(parts, temperature)
    }

    /**
     * UI 元素定位——截图中找指定元素坐标。
     *
     * 这是识图兜底的最常用场景。返回 0-1 之间的相对坐标，
     * CoordinateMapper 再换算为屏幕绝对坐标。
     *
     * @param screenshotBytes 虚拟屏截图
     * @param targetDescription 目标描述："消息免打扰的开关按钮"
     * @return 解析后的坐标 JSONObject，含 x/y（0-1 比例），失败返回 null
     */
    suspend fun locateUIElement(
        screenshotBytes: ByteArray,
        targetDescription: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("这张截图中，请找到「$targetDescription」。\n")
            append("用 JSON 回复，格式：")
            append("""{"found": true/false, "x": 0.0-1.0, "y": 0.0-1.0, "description": "位置描述"}""")
            append("\n坐标用屏幕比例表示（0=最左/最上，1=最右/最下）。")
        }

        val raw = analyzeImage(screenshotBytes, "image/png", prompt, temperature = 0.0)
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
            Log.w(TAG, "Gemini locateUIElement 输出无法解析: ${raw.take(200)}", e)
            null
        }
    }

    /**
     * 截图对比——操作前后两张截图对比，判断操作是否成功。
     *
     * @param beforeBytes 操作前截图
     * @param afterBytes 操作后截图
     * @param expectedChange 期望的变化描述："应该已经进入免打扰设置页面"
     * @return JSONObject，含 changed (Boolean) 和 description (String)
     */
    suspend fun compareScreenshots(
        beforeBytes: ByteArray,
        afterBytes: ByteArray,
        expectedChange: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("下面是两张截图对比。第一张是操作前，第二张是操作后。\n")
            append("判断是否发生了这个变化：$expectedChange\n")
            append("用 JSON 回复：")
            append("""{"changed": true/false, "description": "变化描述"}""")
        }

        val raw = analyzeImages(
            listOf(
                beforeBytes to "image/png",
                afterBytes to "image/png"
            ),
            prompt,
            temperature = 0.0
        ) ?: return@withContext null

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
            Log.w(TAG, "Gemini compareScreenshots 输出无法解析: ${raw.take(200)}", e)
            null
        }
    }

    /**
     * 通用识图——任意 prompt + 图片。
     *
     * 安知好奇心：看到新 App 截图，想知道是什么。
     *
     * @param imageBytes 图片数据
     * @param prompt 自由格式的 prompt
     * @return Gemini 回复文本
     */
    suspend fun describeImage(
        imageBytes: ByteArray,
        prompt: String = "请描述这张截图里有什么。"
    ): String? = analyzeImage(imageBytes, "image/png", prompt, temperature = 0.3)

    /**
     * 重置熔断状态。
     */
    fun resetAvailable() {
        available = true
        consecutiveFailures = 0
        Log.i(TAG, "Gemini Vision 客户端熔断状态已重置")
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
            // 可能已被关闭
        }
        Log.i(TAG, "Gemini Vision 客户端已关闭")
    }

    // ═══════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════

    /**
     * 单图识图核心方法。
     */
    private suspend fun analyzeWithImageData(
        base64Image: String,
        mimeType: String,
        prompt: String,
        temperature: Double
    ): String? {
        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64Image)
                })
            })
            put(JSONObject().apply {
                put("text", prompt)
            })
        }

        return executeVisionRequest(parts, temperature)
    }

    /**
     * 执行 Gemini Vision API 请求，含重试。
     */
    private suspend fun executeVisionRequest(
        parts: JSONArray,
        temperature: Double
    ): String? {
        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            })
        }

        val requestBody = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("topK", 32)
                put("topP", 0.95)
                put("maxOutputTokens", 1024)
            })
        }

        val payloadStr = requestBody.toString()
        Log.d(TAG, "Gemini Vision 请求: ${payloadStr.length} chars, model=$model")

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val url = "$API_URL/$model:generateContent"
                val request = Request.Builder()
                    .url(url)
                    .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code

                if (code in 200..299) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    val text = extractText(body)
                    if (text != null) {
                        consecutiveFailures = 0
                        Log.d(TAG, "Gemini Vision 回复成功 (${text.length} chars)")
                        return text
                    } else {
                        Log.w(TAG, "Gemini Vision 返回空文本: ${body.take(300)}")
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
                        Log.w(TAG, "Gemini Vision 429 rate limited, ${waitMs}ms 后重试 (attempt ${attempt + 1})")
                        delay(waitMs)
                        continue
                    }
                    code in 500..599 -> {
                        Log.w(TAG, "Gemini Vision HTTP $code on attempt ${attempt + 1}: ${errorBody.take(200)}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                            continue
                        }
                    }
                    code in 400..499 -> {
                        Log.e(TAG, "Gemini Vision HTTP $code (不可重试): $errorBody")
                        consecutiveFailures++
                        checkAndMarkUnavailable()
                        return null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Gemini Vision 网络异常 (attempt ${attempt + 1}): ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                    continue
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Vision 调用异常: ${e.message}", e)
                consecutiveFailures++
                checkAndMarkUnavailable()
                return null
            }
        }

        consecutiveFailures++
        checkAndMarkUnavailable()
        Log.e(TAG, "Gemini Vision API 调用失败，已耗尽 $MAX_RETRIES 次重试")
        return null
    }

    /**
     * 从 Gemini API generateContent 响应中提取文本。
     * 兼容 Gemini API 的 candidates[0].content.parts[0].text 格式。
     */
    private fun extractText(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text", null)
        } catch (e: Exception) {
            Log.w(TAG, "解析 Gemini Vision 响应失败: ${e.message}")
            null
        }
    }

    /**
     * 检查连续失败次数，达到阈值后标记不可用。
     */
    private fun checkAndMarkUnavailable() {
        if (consecutiveFailures >= 5) {
            available = false
            Log.e(
                TAG,
                "Gemini Vision 连续失败 $consecutiveFailures 次，标记为不可用。" +
                "下次 AnzhiManagerService 唤醒时会重置。"
            )
        }
    }
}
