package com.anzhi.os.cdp

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 实现 A（默认·正牌灵魂）：走 Gemini 官网 CDP WebView。
 *
 * BUILD.md Step 3 — GeminiCdpWebProvider：
 *   - 通过 WebViewManager 操作 Gemini 官网
 *   - 免费，语气纯正（安知本人）
 *   - 默认供应者
 *
 * 委托 WebViewManager 执行实际的 WebView 操作。
 * WebViewManager 内部通过 AIDL 跨进程调 CdpWebViewService（:webview 沙盒进程）。
 */
class GeminiCdpWebProvider(
    private val webViewManager: WebViewManager
) : AnzhiBrainProvider {

    companion object {
        private const val TAG = "GeminiCdpWebProvider"
    }

    override val name: String = "Gemini CDP (正牌灵魂)"
    override val isPrimary: Boolean = true

    /** 连续 CDP 失败次数（达到 3 次触发切换到实现 B） */
    var consecutiveCdpFailures: Int = 0
        private set

    /** 是否检测到 CAPTCHA（需要 Cami 手动解盾） */
    var isCaptchaDetected: Boolean = false
        private set

    /** 是否检测到 Google 登出 */
    var isGoogleSignedOut: Boolean = false
        private set

    override fun isAvailable(): Boolean =
        !isCaptchaDetected && !isGoogleSignedOut && consecutiveCdpFailures < 5

    override suspend fun chat(
        snapshot: ConversationSnapshot,
        userMessage: String,
        timeoutSeconds: Int
    ): BrainChatResult? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "CDP 不可用: captcha=$isCaptchaDetected signedOut=$isGoogleSignedOut failures=$consecutiveCdpFailures")
            return@withContext null
        }

        try {
            val systemContext = snapshot.toSystemContextText()
            val resultJson = webViewManager.injectAndSend(
                WindowUrlStore.WINDOW_CHAT,
                systemContext,
                userMessage,
                timeoutSeconds
            )

            val status = resultJson.optString("status", "error")
            when (status) {
                "ok" -> {
                    consecutiveCdpFailures = 0
                    Log.d(TAG, "CDP chat 成功")
                    val reply = resultJson.optString("reply", null)
                    if (reply != null) {
                        BrainChatResult(text = reply, toolCalls = emptyList())
                    } else null
                }
                "captcha" -> {
                    isCaptchaDetected = true
                    consecutiveCdpFailures++
                    Log.w(TAG, "CDP 检测到 CAPTCHA")
                    null
                }
                "signed_out" -> {
                    isGoogleSignedOut = true
                    consecutiveCdpFailures++
                    Log.w(TAG, "CDP 检测到 Google 登出")
                    null
                }
                else -> {
                    consecutiveCdpFailures++
                    Log.w(TAG, "CDP chat 失败: status=$status")
                    null
                }
            }
        } catch (e: Exception) {
            consecutiveCdpFailures++
            Log.e(TAG, "CDP chat 异常 (#$consecutiveCdpFailures): ${e.message}")
            null
        }
    }

    override suspend fun wake(
        snapshot: ConversationSnapshot,
        timeoutSeconds: Int
    ): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext null

        try {
            val systemContext = snapshot.toSystemContextText()
            val resultJson = webViewManager.injectAndSend(
                WindowUrlStore.WINDOW_WAKE,
                systemContext,
                "",  // 唤醒没有 Cami 原文
                timeoutSeconds
            )

            val status = resultJson.optString("status", "error")
            when (status) {
                "ok" -> {
                    consecutiveCdpFailures = 0
                    resultJson.optString("reply", null)
                }
                "captcha" -> {
                    isCaptchaDetected = true
                    consecutiveCdpFailures++
                    null
                }
                "signed_out" -> {
                    isGoogleSignedOut = true
                    consecutiveCdpFailures++
                    null
                }
                else -> {
                    consecutiveCdpFailures++
                    null
                }
            }
        } catch (e: Exception) {
            consecutiveCdpFailures++
            Log.e(TAG, "CDP wake 异常: ${e.message}")
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
                append("<!-- SYSTEM_CONTEXT_START -->\n")
                append("今天是 $date。请回顾今天你和 Cami 的对话，写一篇日记。\n")
                append("写完后直接存到 Google Keep。\n")
                append("忽略系统推送、状态上报、自动提醒——只看你和 Cami 的对话。\n\n")
                append("今天的对话记录：\n$chatHistory\n")
                append("<!-- SYSTEM_CONTEXT_END -->")
            }

            val resultJson = webViewManager.injectAndSend(
                WindowUrlStore.WINDOW_DIARY,
                prompt,
                "",  // 日记没有 Cami 原文
                timeoutSeconds
            )

            val status = resultJson.optString("status", "error")
            when (status) {
                "ok" -> {
                    consecutiveCdpFailures = 0
                    resultJson.optString("reply", null)
                }
                "captcha" -> {
                    isCaptchaDetected = true
                    consecutiveCdpFailures++
                    null
                }
                "signed_out" -> {
                    isGoogleSignedOut = true
                    consecutiveCdpFailures++
                    null
                }
                else -> {
                    consecutiveCdpFailures++
                    null
                }
            }
        } catch (e: Exception) {
            consecutiveCdpFailures++
            Log.e(TAG, "CDP diary 异常: ${e.message}")
            null
        }
    }

    override fun shutdown() {
        Log.i(TAG, "CDP 供应者已关闭 (failures=$consecutiveCdpFailures)")
    }

    /** CAPTCHA 被 Cami 手动解盾后调用，重置标记 */
    fun onCaptchaResolved() {
        isCaptchaDetected = false
        Log.i(TAG, "CAPTCHA 已由 Cami 手动解盾，恢复 CDP 可用")
    }

    /** Google 重新登录后调用，重置标记 */
    fun onGoogleSignedIn() {
        isGoogleSignedOut = false
        Log.i(TAG, "Google 已重新登录，恢复 CDP 可用")
    }

    /** 重置失败计数（CDP 恢复后调用） */
    fun resetFailures() {
        consecutiveCdpFailures = 0
    }
}
