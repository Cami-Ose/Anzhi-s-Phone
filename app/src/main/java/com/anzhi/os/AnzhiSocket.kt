package com.anzhi.os

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.anzhi.os.model.MessageType
import com.anzhi.os.model.SocketMessage
import okhttp3.*
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端——手机和安知后端之间的电话线。
 *
 * 一根线，两种用法：
 *   send()  → 把消息发给 VPS
 *   messageCallback → 收到 VPS 的消息时触发
 *
 * 自带：断线重连（指数退避）、心跳保活、连接状态回调
 *
 * 重连策略（BUILD.md 陷阱 19）：
 *   - 指数退避：3s → 6s → 12s → 24s → 60s（封顶）
 *   - 连续重连 10 次后切换为 5 分钟间隔
 *   - 深夜（0:00-6:00）间隔统一 ×2
 *   - 连接恢复后重置退避计数器
 */
class AnzhiSocket(
    private val serverUrl: String,       // 例：ws://your-vps:8080/anzhi
    private val deviceId: String          // 设备唯一标识，安知用来认"这是 Cami 的 Pixel"
) {
    companion object {
        private const val TAG = "AnzhiSocket"
        private const val HEARTBEAT_INTERVAL_SEC = 30L   // 每 30 秒心跳

        // ── 指数退避参数（陷阱 19）──
        private const val BASE_DELAY_MS = 3000L          // 初始 3 秒
        private const val MAX_DELAY_MS = 60_000L         // 封顶 60 秒
        private const val SLOW_MODE_INTERVAL_MS = 300_000L  // 10 次失败后 5 分钟
        private const val SLOW_MODE_THRESHOLD = 10       // 触发慢模式的连续失败次数
    }

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var isManualDisconnect = false

    /** 真实 WebSocket 连接状态（由 onOpen/onClosed/onFailure 维护） */
    @Volatile
    private var _connected = false

    /** 连接状态：connecting / connected / disconnected */
    var statusCallback: ((String) -> Unit)? = null

    /** 收到消息时触发这个 lambda */
    var messageCallback: ((SocketMessage) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────
    // 连接
    // ─────────────────────────────────────

    fun connect() {
        isManualDisconnect = false
        statusCallback?.invoke("connecting")
        Log.i(TAG, "正在连接安知后端: $serverUrl")

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 不超时
            .build()
        val request = Request.Builder()
            .url("$serverUrl?device=$deviceId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "✅ 已连接到安知后端")
                _connected = true
                reconnectAttempts = 0  // 重置退避计数器
                statusCallback?.invoke("connected")
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "← 收到: ${text.take(200)}")
                try {
                    val msg = SocketMessage.parse(text)
                    if (msg.type != MessageType.HEARTBEAT) {
                        messageCallback?.invoke(msg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "消息解析失败: ${e.message} | raw=${text.take(100)}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket 连接失败: ${t.message}")
                _connected = false
                statusCallback?.invoke("disconnected")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "连接已关闭 (code=$code): $reason")
                _connected = false
                statusCallback?.invoke("disconnected")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        isManualDisconnect = true
        stopHeartbeat()
        cancelPendingReconnect()
        webSocket?.close(1000, "用户断开")
        webSocket = null
        statusCallback?.invoke("disconnected")
    }

    // ─────────────────────────────────────
    // 发送
    // ─────────────────────────────────────

    fun send(msg: SocketMessage): Boolean {
        val json = msg.toJson()
        Log.d(TAG, "→ 发送: ${json.take(200)}")
        return webSocket?.send(json) ?: false.also {
            Log.w(TAG, "WebSocket 未连接，无法发送")
        }
    }

    /** 快捷方法：直接发 type + key-value */
    fun send(type: MessageType, vararg pairs: Pair<String, Any>): Boolean {
        val payload = JSONObject().apply {
            pairs.forEach { (k, v) -> put(k, v) }
        }
        return send(SocketMessage(type, payload))
    }

    // ─────────────────────────────────────
    // 心跳
    // ─────────────────────────────────────

    private var heartbeatRunnable: Runnable? = null

    private fun startHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (webSocket != null) {
                    val msg = SocketMessage(
                        type = MessageType.HEARTBEAT,
                        payload = JSONObject().apply {
                            put("ts", System.currentTimeMillis() / 1000)
                        }
                    )
                    send(msg)
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_SEC * 1000L)
                }
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_SEC * 1000L)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ─────────────────────────────────────
    // 重连（指数退避 — 陷阱 19）
    // ─────────────────────────────────────

    private var reconnectRunnable: Runnable? = null

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        reconnectAttempts++

        val delay = computeBackoff(reconnectAttempts)

        Log.i(
            TAG,
            "${delay}ms 后第 ${reconnectAttempts} 次重连…" +
            if (isNightTime()) " [深夜模式 ×2]" else ""
        )

        reconnectRunnable = Runnable {
            connect()
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * 计算指数退避延迟。
     *
     * 规则：
     *   1. 指数退避：3s → 6s → 12s → 24s → 60s（封顶）
     *   2. 连续失败 ≥10 次 → 固定 5 分钟间隔
     *   3. 深夜 0:00-6:00 → ×2
     */
    private fun computeBackoff(attempt: Int): Long {
        // 连续 10 次失败 → 慢模式（5 分钟间隔）
        if (attempt > SLOW_MODE_THRESHOLD) {
            return applyNightMultiplier(SLOW_MODE_INTERVAL_MS)
        }

        // 指数退避：3s * 2^(attempt-1)，封顶 60s
        val exponential = BASE_DELAY_MS * (1L shl (attempt - 1))
        val capped = exponential.coerceAtMost(MAX_DELAY_MS)

        return applyNightMultiplier(capped)
    }

    /** 深夜（0:00-6:00）间隔 ×2 */
    private fun applyNightMultiplier(delay: Long): Long =
        if (isNightTime()) delay * 2 else delay

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in 0..5
    }

    /** 检查是否已连接（基于真实 WebSocket 连接状态） */
    fun isConnected(): Boolean = _connected
}
