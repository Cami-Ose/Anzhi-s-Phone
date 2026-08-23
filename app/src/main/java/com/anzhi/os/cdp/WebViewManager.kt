package com.anzhi.os.cdp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CDP WebView 管理器——跑在主进程，持有 :webview 进程的 Binder 代理。
 *
 * BUILD.md §陷阱 2 防御方案：
 *   - 核心 Service（AnzhiManagerService）只持此代理引用，不为 WebView 内存兜底
 *   - :webview 进程崩溃 → 只重启 WebView，不碰核心服务和 WebSocket 连接
 *   - 通过 bindService 获取 Binder 代理后远程调用
 *
 * 职责：
 *   1. bind/unbind CdpWebViewService（:webview 进程）
 *   2. 管理窗口 URL 持久化（WindowUrlStore）
 *   3. 提供简化的 injectAndSend 接口给 GeminiCdpWebProvider
 *   4. CAPTCHA / Google 登出事件回调到上层
 *   5. CDP 不可用时自动创建新窗口
 */
class WebViewManager(private val context: Context) {

    companion object {
        private const val TAG = "WebViewManager"
        private const val BIND_TIMEOUT_SEC = 10L
        private const val GEMINI_BASE_URL = "https://gemini.google.com/app"

        /** CAPTCHA / 登出通知的 action */
        const val ACTION_CAPTCHA_DETECTED = "com.anzhi.os.CAPTCHA_DETECTED"
        const val ACTION_GOOGLE_SIGNED_OUT = "com.anzhi.os.GOOGLE_SIGNED_OUT"
        const val EXTRA_WINDOW_TYPE = "window_type"
        const val EXTRA_PAGE_TITLE = "page_title"
    }

    // ── AIDL 代理 ──
    private var service: ICdpWebViewService? = null
    private var isBound = AtomicBoolean(false)

    // ── 窗口 URL 持久化 ──
    private val urlStore = WindowUrlStore(context)

    // ── 回调 ──
    private var captchaCallback: ((windowType: String, pageTitle: String) -> Unit)? = null
    private var signOutCallback: ((windowType: String) -> Unit)? = null
    private var crashCallback: ((windowType: String) -> Unit)? = null

    // ── CDP 回调实现（从 :webview 进程回调到主进程）──
    private val cdpCallback = object : ICdpCallback.Stub() {
        override fun onCaptchaDetected(windowType: String, pageTitle: String) {
            Log.w(TAG, "CDP 回调: CAPTCHA 检测到 — $windowType: $pageTitle")
            captchaCallback?.invoke(windowType, pageTitle)
            // 发送广播通知 UI 层
            sendBroadcast(ACTION_CAPTCHA_DETECTED, windowType, pageTitle)
        }

        override fun onGoogleSignedOut(windowType: String) {
            Log.w(TAG, "CDP 回调: Google 登出 — $windowType")
            signOutCallback?.invoke(windowType)
            sendBroadcast(ACTION_GOOGLE_SIGNED_OUT, windowType, null)
        }

        override fun onWebViewCrashed(windowType: String) {
            Log.e(TAG, "CDP 回调: WebView 崩溃 — $windowType")
            crashCallback?.invoke(windowType)
            // 尝试重新绑定
            Handler(Looper.getMainLooper()).postDelayed({
                bindService()
            }, 1000)
        }

        override fun onPageLoadFailed(windowType: String, errorMessage: String) {
            Log.w(TAG, "CDP 回调: 页面加载失败 — $windowType: $errorMessage")
        }
    }

    // ── ServiceConnection ──
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ICdpWebViewService.Stub.asInterface(binder)
            isBound.set(true)
            Log.i(TAG, "已连接到 CDP WebView Service（:webview 进程）")

            // 注册回调
            try {
                service?.registerCallback(cdpCallback)
            } catch (e: Exception) {
                Log.w(TAG, "注册 CDP 回调失败: ${e.message}")
            }

            // 恢复窗口（从 SQLite 加载 URL）
            restoreWindows()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound.set(false)
            Log.w(TAG, "CDP WebView Service 断开连接（:webview 进程可能崩溃）")

            // 尝试重新绑定
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isBound.get()) {
                    Log.i(TAG, "尝试重新绑定 CDP WebView Service...")
                    bindService()
                }
            }, 3000)
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(TAG, "CDP WebView Service Binder 死亡")
            service = null
            isBound.set(false)
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "CDP WebView Service 返回 null Binder")
            service = null
            isBound.set(false)
        }
    }

    // ─────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────

    /** 绑定到 :webview 进程的 CdpWebViewService */
    fun bindService(): Boolean {
        if (isBound.get()) {
            Log.d(TAG, "已绑定，跳过")
            return true
        }

        val intent = Intent(context, CdpWebViewService::class.java)
        return try {
            val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (result) {
                Log.i(TAG, "正在绑定 CDP WebView Service...")
            } else {
                Log.e(TAG, "bindService 返回 false")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "绑定服务失败: ${e.message}")
            false
        }
    }

    /** 解绑 */
    fun unbindService() {
        try {
            service?.unregisterCallback(cdpCallback)
        } catch (_: Exception) {}
        try {
            context.unbindService(serviceConnection)
        } catch (_: Exception) {}
        service = null
        isBound.set(false)
        Log.i(TAG, "已解绑 CDP WebView Service")
    }

    /** 检查服务是否已连接且可用 */
    fun isReady(): Boolean = isBound.get() && service != null

    // ─────────────────────────────────────
    // 公开接口
    // ─────────────────────────────────────

    /**
     * 向 Gemini 注入上下文并发送消息，等待回复。
     *
     * @param windowType 窗口类型（wake/chat/diary）
     * @param systemContext 系统上下文文本（包裹在 SYSTEM_CONTEXT 标记中）
     * @param userMessage Cami 原文（包裹在 CAMI_MSG 标记中）
     * @param timeoutSeconds 等待回复超时秒数
     * @return JSON: {"status":"ok"|"error"|"captcha"|"signed_out"|"timeout", "reply":"...", ...}
     */
    fun injectAndSend(
        windowType: String,
        systemContext: String,
        userMessage: String,
        timeoutSeconds: Int
    ): JSONObject {
        val svc = ensureService() ?: return errorJson("CDP 服务未连接")

        // 获取或创建窗口 URL
        val url = getOrCreateWindowUrl(windowType)

        // 加载窗口（如果还没加载或 URL 变了）
        val currentUrl = svc.loadWindow(windowType, url)
        if (currentUrl.isEmpty()) {
            return errorJson("加载窗口 $windowType 失败")
        }

        // 等待页面就绪
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (svc.isPageReady(windowType)) break
            Thread.sleep(500)
        }

        if (!svc.isPageReady(windowType)) {
            return errorJson("页面未就绪（$windowType）")
        }

        // 执行注入
        val resultJson = svc.injectAndSend(windowType, systemContext, userMessage, timeoutSeconds)
        val result = JSONObject(resultJson)

        // 如果 CAPTCHA 或登出，通知 UI
        when (result.optString("status")) {
            "captcha" -> {
                captchaCallback?.invoke(windowType, result.optString("title", ""))
            }
            "signed_out" -> {
                signOutCallback?.invoke(windowType)
            }
        }

        return result
    }

    /**
     * 截取 WebView 内容截图（用于 CAPTCHA 通知附图）。
     */
    fun captureScreenshot(windowType: String): String? {
        val svc = ensureService() ?: return null
        return try {
            val base64 = svc.captureWebViewScreenshot(windowType)
            if (base64.isNotEmpty()) base64 else null
        } catch (e: Exception) {
            Log.w(TAG, "截图失败: ${e.message}")
            null
        }
    }

    /**
     * 获取当前窗口 URL（从 :webview 进程查询）。
     */
    fun getCurrentUrl(windowType: String): String? {
        val svc = ensureService() ?: return urlStore.getUrl(windowType)
        return try {
            val url = svc.getWindowUrl(windowType)
            if (url.isNotEmpty()) url else urlStore.getUrl(windowType)
        } catch (e: Exception) {
            urlStore.getUrl(windowType)
        }
    }

    /**
     * 销毁一个窗口（释放 WebView 内存）。
     */
    fun destroyWindow(windowType: String) {
        try {
            service?.destroyWindow(windowType)
        } catch (_: Exception) {}
    }

    /** 设置 CAPTCHA 检测回调 */
    fun onCaptchaDetected(callback: (windowType: String, pageTitle: String) -> Unit) {
        captchaCallback = callback
    }

    /** 设置 Google 登出回调 */
    fun onGoogleSignedOut(callback: (windowType: String) -> Unit) {
        signOutCallback = callback
    }

    /** 设置 WebView 崩溃回调 */
    fun onWebViewCrashed(callback: (windowType: String) -> Unit) {
        crashCallback = callback
    }

    // ─────────────────────────────────────
    // 窗口 URL 管理
    // ─────────────────────────────────────

    /**
     * 获取或创建 Gemini 窗口 URL。
     *
     * 首次使用：返回 Gemini 首页 URL，Cami 手动打开后 OS 会调用 putWindowUrl 存入。
     * 后续使用：从 SQLite 读取已存储的 URL。
     * URL 失效（Cookie 过期/对话消失）：自动 window.open 新建 → 记新 URL。
     */
    private fun getOrCreateWindowUrl(windowType: String): String {
        val saved = urlStore.getUrl(windowType)
        if (saved != null) {
            return saved
        }

        // 未存储 → 返回 Gemini 首页
        // Cami 需要首次手动打开三个对话，OS 记下 URL
        val defaultUrl = when (windowType) {
            WindowUrlStore.WINDOW_WAKE -> GEMINI_BASE_URL
            WindowUrlStore.WINDOW_CHAT -> GEMINI_BASE_URL
            WindowUrlStore.WINDOW_DIARY -> GEMINI_BASE_URL
            else -> GEMINI_BASE_URL
        }
        Log.i(TAG, "窗口 $windowType 无已存储 URL，使用默认: $defaultUrl")
        return defaultUrl
    }

    /** 存入窗口 URL（Cami 手动打开对话后调用） */
    fun putWindowUrl(windowType: String, url: String) {
        urlStore.putUrl(windowType, url)
    }

    /** 所有窗口 URL 是否都已配置 */
    fun areAllWindowsConfigured(): Boolean {
        return WindowUrlStore.ALL_WINDOWS.all { urlStore.hasUrl(it) }
    }

    // ─────────────────────────────────────
    // 内部方法
    // ─────────────────────────────────────

    private fun ensureService(): ICdpWebViewService? {
        if (!isBound.get() || service == null) {
            bindService()
            // 等待绑定完成
            val deadline = System.currentTimeMillis() + BIND_TIMEOUT_SEC * 1000
            while (System.currentTimeMillis() < deadline && (service == null || !isBound.get())) {
                Thread.sleep(200)
            }
        }
        return if (isBound.get()) service else null
    }

    /** 服务连接后恢复所有窗口 */
    private fun restoreWindows() {
        val svc = service ?: return
        val allUrls = urlStore.getAllUrls()
        for ((windowType, url) in allUrls) {
            try {
                svc.loadWindow(windowType, url)
                Log.d(TAG, "已恢复窗口: $windowType")
            } catch (e: Exception) {
                Log.w(TAG, "恢复窗口 $windowType 失败: ${e.message}")
            }
        }
    }

    private fun sendBroadcast(action: String, windowType: String, pageTitle: String?) {
        try {
            val intent = Intent(action).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_WINDOW_TYPE, windowType)
                if (pageTitle != null) putExtra(EXTRA_PAGE_TITLE, pageTitle)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "发送广播失败: ${e.message}")
        }
    }

    private fun errorJson(message: String): JSONObject = JSONObject().apply {
        put("status", "error")
        put("message", message)
    }

    private fun errorJson(message: String, status: String): JSONObject = JSONObject().apply {
        put("status", status)
        put("message", message)
    }
}
