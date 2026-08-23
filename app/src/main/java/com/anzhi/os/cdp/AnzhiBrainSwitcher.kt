package com.anzhi.os.cdp

import android.util.Log

/**
 * 安知大脑自动切换器。
 *
 * M1 换源改造——主备反转：
 *   - primary（实现 A）：VpsBrainProvider（直连 VPS API，M1 默认大脑）
 *   - fallback（实现 B）：GeminiCdpWebProvider（Gemini 官网 CDP WebView）
 *
 * 自动切换逻辑：
 *   - primary 连续失败 3 次 → 切到 fallback
 *   - fallback 连续成功 2 次 → 切回 primary
 *
 * 切换时通知 OS → 安知下一句话告知 Cami。
 *
 * 用法：
 *   val switcher = AnzhiBrainSwitcher(primaryProvider = vpsProvider, fallbackProvider = cdpProvider)
 *   val reply = switcher.chat(snapshot, userMessage)  // 自动选择可用的供应者
 */
class AnzhiBrainSwitcher(
    private val primaryProvider: AnzhiBrainProvider,      // 实现 A：VPS 直连
    private val fallbackProvider: AnzhiBrainProvider,     // 实现 B：Gemini CDP 官网
    private val onSwitchToFallback: ((reason: String) -> Unit)? = null,
    private val onSwitchBackToPrimary: (() -> Unit)? = null
) : AnzhiBrainProvider {

    companion object {
        private const val TAG = "AnzhiBrainSwitcher"
        private const val PRIMARY_FAILURE_THRESHOLD = 3  // primary 连续失败 N 次后切换
        private const val FALLBACK_RECOVERY_THRESHOLD = 2  // fallback 连续成功 N 次后切回
    }

    override val name: String get() = currentProvider.name
    override val isPrimary: Boolean get() = currentProvider.isPrimary

    /** 当前活跃的供应者 */
    @Volatile
    private var currentProvider: AnzhiBrainProvider = primaryProvider

    /** 是否正在使用备用供应者 */
    @Volatile
    var isUsingFallback: Boolean = false
        private set

    /** 切换原因（用于告知 Cami） */
    var lastSwitchReason: String = ""
        private set

    /** 当前供应者连续失败次数（达到 PRIMARY_FAILURE_THRESHOLD 后切换） */
    private var consecutiveFailures = 0

    /** fallback 连续成功次数（达到 FALLBACK_RECOVERY_THRESHOLD 后切回 primary） */
    private var fallbackSuccessStreak = 0

    override fun isAvailable(): Boolean = primaryProvider.isAvailable() || fallbackProvider.isAvailable()

    override suspend fun chat(
        snapshot: ConversationSnapshot,
        userMessage: String,
        timeoutSeconds: Int
    ): BrainChatResult? {
        // 先尝试当前活跃的供应者
        val result = currentProvider.chat(snapshot, userMessage, timeoutSeconds)
        if (result != null) {
            handleSuccess()
            return result
        }

        // 当前供应者失败，尝试切换到另一个
        return tryFallback(snapshot, userMessage, timeoutSeconds)
    }

    override suspend fun wake(
        snapshot: ConversationSnapshot,
        timeoutSeconds: Int
    ): String? {
        val result = currentProvider.wake(snapshot, timeoutSeconds)
        if (result != null) {
            handleSuccess()
            return result
        }
        return if (!isUsingFallback) {
            switchToFallback("primary wake 连续失败")
            fallbackProvider.wake(snapshot, timeoutSeconds)
        } else null
    }

    override suspend fun writeDiary(
        date: String,
        chatHistory: String,
        timeoutSeconds: Int
    ): String? {
        val result = currentProvider.writeDiary(date, chatHistory, timeoutSeconds)
        if (result != null) {
            handleSuccess()
            return result
        }
        return if (!isUsingFallback) {
            switchToFallback("primary diary 失败")
            fallbackProvider.writeDiary(date, chatHistory, timeoutSeconds)
        } else null
    }

    override fun shutdown() {
        primaryProvider.shutdown()
        fallbackProvider.shutdown()
        Log.i(TAG, "安知大脑切换器已关闭")
    }

    // ─────────────────────────────────────
    // 切换逻辑
    // ─────────────────────────────────────

    private fun handleSuccess() {
        if (isUsingFallback) {
            // 备用供应者成功 → 累计成功次数，达到阈值切回 primary
            trySwitchBackToPrimary()
        } else {
            // primary 成功 → 清零连续失败计数
            consecutiveFailures = 0
        }
    }

    /**
     * 尝试备用路径：
     *   1. 如果用 primary 失败 → 检查是否达到切换阈值
     *   2. 切换到 fallback（CDP）
     */
    private suspend fun tryFallback(
        snapshot: ConversationSnapshot,
        userMessage: String,
        timeoutSeconds: Int
    ): BrainChatResult? {
        // 如果已经在用备用供应者且仍然失败 → 无可用的供应者
        if (isUsingFallback) {
            fallbackSuccessStreak = 0  // fallback 失败，恢复计数清零
            Log.e(TAG, "备用供应者也失败了，安知无法回复")
            return null
        }

        // primary 未配置（不可用）时直接切 fallback，不消耗失败额度（已确认 isUsingFallback 为 false，短路安全）
        if (!primaryProvider.isAvailable()) {
            switchToFallback("primary 当前不可用（未配置）")
            return fallbackProvider.chat(snapshot, userMessage, timeoutSeconds)
        }

        // primary 连续失败计数
        consecutiveFailures++
        if (consecutiveFailures >= PRIMARY_FAILURE_THRESHOLD) {
            switchToFallback("primary 连续失败 $consecutiveFailures 次")

            // 用备用供应者重试
            return fallbackProvider.chat(snapshot, userMessage, timeoutSeconds)
        }

        return null
    }

    private fun switchToFallback(reason: String) {
        if (isUsingFallback) return

        isUsingFallback = true
        lastSwitchReason = reason
        currentProvider = fallbackProvider
        fallbackSuccessStreak = 0

        Log.w(TAG, "⚠️ 切换到备用大脑: $reason")
        onSwitchToFallback?.invoke(reason)
    }

    /**
     * 尝试切回 primary。
     * fallback 从不可用恢复时，需要连续成功 N 次才切回，防止抖动。
     */
    fun trySwitchBackToPrimary(): Boolean {
        if (!isUsingFallback) return true

        if (!primaryProvider.isAvailable()) {
            fallbackSuccessStreak = 0
            return false
        }

        fallbackSuccessStreak++
        Log.d(TAG, "primary 恢复信号: streak=$fallbackSuccessStreak/$FALLBACK_RECOVERY_THRESHOLD")

        if (fallbackSuccessStreak >= FALLBACK_RECOVERY_THRESHOLD) {
            isUsingFallback = false
            currentProvider = primaryProvider
            consecutiveFailures = 0  // 切回 primary 成功，重置连续失败计数，避免切回后立刻再切走（振荡）
            fallbackSuccessStreak = 0
            lastSwitchReason = ""

            Log.i(TAG, "✅ 切回主大脑: primary 已恢复")
            onSwitchBackToPrimary?.invoke()
            return true
        }

        return false
    }

    /**
     * 获取当前状态的描述（给安知在对话中告知 Cami）。
     */
    fun getStatusMessage(): String? {
        if (!isUsingFallback) return null

        return when {
            !fallbackProvider.isAvailable() ->
                "直连大脑线路暂时连不上，备用官网线路也不可用，回复质量可能会受影响。"
            else ->
                "直连大脑线路暂时连不上，我现在在用官网备用线路跟你聊。"
        }
    }
}
