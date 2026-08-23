package com.anzhi.os.cdp

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.*
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * CDP WebView 服务——运行在独立 :webview 沙盒进程中。
 *
 * BUILD.md §陷阱 2：WebView 内存泄漏 → 核心服务被强杀
 *   防御方案：此 Service 在独立进程（android:process=":webview"），
 *   核心 Service 只持 WebView 代理引用。:webview 崩溃 → 只重启 WebView，不碰核心服务。
 *
 * 三个 WebView 实例：
 *   - wake  — 提醒窗口
 *   - chat  — 主聊天窗口
 *   - diary — 日记窗口
 *
 * CDP 操作协议（BUILD.md §2.2）：
 *   1. 注入 JS：打字模拟人类输入速度（每字符 50-200ms 随机延迟）
 *   2. 回复抓取：只提取 CAMI_MSG_START/END 之间的用户原文
 *   3. 系统上下文包裹在 SYSTEM_CONTEXT_START/END 中，抓取时丢弃
 *   4. DOM 变化监听，硬超时 15 秒
 *   5. CAPTCHA / Google 登出检测
 */
class CdpWebViewService : Service() {

    companion object {
        private const val TAG = "CdpWebViewService"

        // DOM 轮询间隔
        private const val POLL_INTERVAL_MS = 500L

        // 默认超时
        private const val DEFAULT_TIMEOUT_SEC = 15

        // Gemini 站点 key（对应 dom_selectors.json 的顶级 key）
        private const val SITE_GEMINI = "gemini.google.com"
        private const val SITE_ACCOUNTS = "accounts.google.com"
        private const val SITE_CAPTCHA = "captcha_patterns"

        // 人类打字速度范围
        private const val TYPING_DELAY_MIN_MS = 50L
        private const val TYPING_DELAY_MAX_MS = 200L

        // Handler 消息类型
        private const val MSG_INIT_WEBVIEW = 1
        private const val MSG_INJECT_AND_SEND = 2
        private const val MSG_DESTROY_WINDOW = 3
        private const val MSG_CAPTURE_SCREENSHOT = 4
    }

    // ── WebView 实例 ──
    private val webViews = ConcurrentHashMap<String, WebView>()
    private val webViewReady = ConcurrentHashMap<String, Boolean>()

    // ── DomRegistry（选择器从 dom_selectors.json 加载，禁止硬编码）──
    private lateinit var domRegistry: DomRegistry

    // ── 回调 ──
    private val callbacks = mutableListOf<ICdpCallback>()
    private val callbackDeathRecipients = ConcurrentHashMap<ICdpCallback, IBinder.DeathRecipient>()

    // ── 后台线程 ──
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // ── AIDL Binder ──
    private val binder = object : ICdpWebViewService.Stub() {

        override fun loadWindow(windowType: String, url: String): String {
            enforceMainThread()
            return loadWindowInternal(windowType, url)
        }

        override fun destroyWindow(windowType: String) {
            enforceMainThread()
            destroyWindowInternal(windowType)
        }

        override fun getWindowUrl(windowType: String): String {
            val wv = webViews[windowType]
            return wv?.url ?: ""
        }

        override fun isPageReady(windowType: String): Boolean {
            return webViewReady[windowType] == true
        }

        override fun injectAndSend(
            windowType: String,
            systemContext: String,
            userMessage: String,
            timeoutSeconds: Int
        ): String {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<String>()

            handler.post {
                try {
                    val result = injectAndSendInternal(
                        windowType, systemContext, userMessage,
                        if (timeoutSeconds > 0) timeoutSeconds else DEFAULT_TIMEOUT_SEC
                    )
                    resultRef.set(result)
                } catch (e: Exception) {
                    Log.e(TAG, "injectAndSend 异常: ${e.message}")
                    resultRef.set(errorResult("内部错误: ${e.message}"))
                } finally {
                    latch.countDown()
                }
            }

            val finished = latch.await(timeoutSeconds.toLong() + 10, TimeUnit.SECONDS)
            return if (finished) {
                resultRef.get() ?: errorResult("未收到结果")
            } else {
                errorResult("操作超时")
            }
        }

        override fun captureWebViewScreenshot(windowType: String): String {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<String>()

            handler.post {
                try {
                    val wv = webViews[windowType]
                    if (wv == null) {
                        resultRef.set("")
                    } else {
                        val bitmap = Bitmap.createBitmap(
                            wv.width, wv.height, Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        wv.draw(canvas)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                        val base64 = android.util.Base64.encodeToString(
                            stream.toByteArray(), android.util.Base64.NO_WRAP
                        )
                        bitmap.recycle()
                        stream.close()
                        resultRef.set(base64)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "截图失败: ${e.message}")
                    resultRef.set("")
                } finally {
                    latch.countDown()
                }
            }

            latch.await(5, TimeUnit.SECONDS)
            return resultRef.get() ?: ""
        }

        override fun registerCallback(callback: ICdpCallback) {
            try {
                val deathRecipient = IBinder.DeathRecipient {
                    synchronized(callbacks) { callbacks.remove(callback) }
                    callbackDeathRecipients.remove(callback)
                    Log.d(TAG, "回调已死亡，已移除")
                }
                callback.asBinder().linkToDeath(deathRecipient, 0)
                synchronized(callbacks) { callbacks.add(callback) }
                callbackDeathRecipients[callback] = deathRecipient
            } catch (e: Exception) {
                Log.w(TAG, "注册回调失败: ${e.message}")
            }
        }

        override fun unregisterCallback(callback: ICdpCallback) {
            synchronized(callbacks) { callbacks.remove(callback) }
            callbackDeathRecipients.remove(callback)?.let {
                callback.asBinder().unlinkToDeath(it, 0)
            }
        }
    }

    // ─────────────────────────────────────
    // Service 生命周期
    // ─────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CDP WebView Service 启动（进程: ${android.os.Process.myPid()}）")

        handlerThread = HandlerThread("CdpWebView").apply { start() }
        handler = Handler(handlerThread.looper)

        // 加载 DOM 选择器配置（禁止在逻辑代码里硬编码 document.querySelector）
        domRegistry = DomRegistry.load(this)
        Log.i(TAG, "DomRegistry 已加载，站点数: ${domRegistry.getSites().size}")

        // 初始化三个 WebView
        for (windowType in WindowUrlStore.ALL_WINDOWS) {
            handler.sendMessage(
                handler.obtainMessage(MSG_INIT_WEBVIEW, windowType)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "CDP WebView Service onBind")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "CDP WebView Service 停止")

        // 注销所有回调
        synchronized(callbacks) {
            for (cb in callbacks) {
                callbackDeathRecipients[cb]?.let {
                    cb.asBinder().unlinkToDeath(it, 0)
                }
            }
            callbacks.clear()
            callbackDeathRecipients.clear()
        }

        // 销毁所有 WebView
        for ((type, _) in webViews) {
            destroyWindowInternal(type)
        }
        webViews.clear()
        webViewReady.clear()

        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY  // WebView 进程不需要粘性重启
    }

    // ─────────────────────────────────────
    // Handler 消息处理
    // ─────────────────────────────────────

    private fun enforceMainThread() {
        // Handler 线程即 WebView 操作线程
    }

    // ─────────────────────────────────────
    // WebView 窗口管理
    // ─────────────────────────────────────

    private fun loadWindowInternal(windowType: String, url: String): String {
        val existingWv = webViews[windowType]

        if (existingWv != null && existingWv.url == url && webViewReady[windowType] == true) {
            Log.d(TAG, "窗口 $windowType 已加载且就绪: ${url.take(80)}...")
            return url
        }

        // 创建新的 WebView（需要在主线程）
        val latch = CountDownLatch(1)
        val resultUrl = AtomicReference(url)

        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(this).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        // 允许混合内容（Gemini 可能有 HTTP 资源）
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // 桌面模式 UA（避免被 Gemini 认为是移动端受限版）
                        userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        // 视口设置
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        // 允许文件访问
                        allowFileAccess = true
                        // 缓存
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }

                    // 注入 JS 接口（用于 DOM 操作回调）
                    addJavascriptInterface(CdpJsBridge(windowType), "__anzhiCdp")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "窗口 $windowType 页面加载完成: ${url?.take(80)}...")
                            webViewReady[windowType] = true

                            // 检查页面状态
                            view?.evaluateJavascript("""
                                (function() {
                                    var title = document.title || '';
                                    var currentUrl = window.location.href || '';
                                    return JSON.stringify({title: title, url: currentUrl});
                                })()
                            """.trimIndent()) { result ->
                                checkPageState(windowType, result)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            Log.e(TAG, "窗口 $windowType 加载失败: $errorCode $description")
                            webViewReady[windowType] = false
                            notifyPageLoadFailed(windowType, "Error $errorCode: $description")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            if (title != null && isCaptchaTitle(title)) {
                                notifyCaptchaDetected(windowType, title)
                            }
                        }
                    }

                    loadUrl(url)
                }

                // 销毁旧 WebView
                existingWv?.destroy()
                webViews[windowType] = wv
                webViewReady[windowType] = false

                Log.i(TAG, "窗口 $windowType WebView 已创建: ${url.take(80)}...")
                resultUrl.set(url)
            } catch (e: Exception) {
                Log.e(TAG, "创建 WebView 失败: ${e.message}")
                resultUrl.set("")
            } finally {
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        return resultUrl.get()
    }

    private fun destroyWindowInternal(windowType: String) {
        val wv = webViews.remove(windowType)
        webViewReady.remove(windowType)

        if (wv != null) {
            Handler(Looper.getMainLooper()).post {
                try {
                    wv.removeJavascriptInterface("__anzhiCdp")
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.destroy()
                    Log.d(TAG, "窗口 $windowType WebView 已销毁")
                } catch (e: Exception) {
                    Log.w(TAG, "销毁 WebView 异常: ${e.message}")
                }
            }
        }
    }

    // ─────────────────────────────────────
    // CDP 核心：注入 JS 并等待回复
    // ─────────────────────────────────────

    /**
     * 从 DomRegistry 构造输入框查找 JS 片段。
     * Google 改 DOM → 只需更新 dom_selectors.json，不动此文件。
     */
    private fun buildInputFinderJs(): String {
        val (primary, fallback) = domRegistry.getSelectorPair(SITE_GEMINI, "inputArea")
        return buildSelectorChainJs(primary, fallback)
    }

    /**
     * 从 DomRegistry 构造发送按钮查找 JS 片段。
     */
    private fun buildSendButtonFinderJs(): String {
        val (primary, fallback) = domRegistry.getSelectorPair(SITE_GEMINI, "sendButton")
        return buildSelectorChainJs(primary, fallback)
    }

    /**
     * 从 DomRegistry 构造回复检测的 CSS 选择器字符串。
     * 用于 querySelectorAll 的参数。
     */
    private fun buildResponseSelector(): String {
        val primary = domRegistry.getSelector(SITE_GEMINI, "responseArea")
        val lastText = domRegistry.getSelector(SITE_GEMINI, "lastResponseText")
        // 合并：回复区域 + 最后一条回复文本
        val parts = listOfNotNull(primary, lastText)
        return if (parts.isNotEmpty()) parts.joinToString(", ") else {
            // 硬兜底：DomRegistry 加载失败时的最后防线
            "[data-message-author=\"bot\"], model-response, .response-content"
        }
    }

    /**
     * 将 DomRegistry 的 selector + fallback 转为 JS querySelector 链。
     * 例：primary="[contenteditable]" fallback="textarea"
     *   → document.querySelector('[contenteditable]') || document.querySelector('textarea')
     */
    private fun buildSelectorChainJs(primary: String?, fallback: String?): String {
        val parts = mutableListOf<String>()
        if (!primary.isNullOrBlank()) {
            parts.add("document.querySelector('${primary.escapeJs()}')")
        }
        if (!fallback.isNullOrBlank()) {
            parts.add("document.querySelector('${fallback.escapeJs()}')")
        }
        // 如果 DomRegistry 完全没配置，用硬兜底
        if (parts.isEmpty()) {
            parts.add("document.querySelector('[contenteditable=\"true\"]')")
            parts.add("document.querySelector('textarea')")
        }
        return parts.joinToString(" || ")
    }

    /** 转义单引号（用于嵌入 JS 字符串） */
    private fun String.escapeJs(): String =
        this.replace("\\", "\\\\").replace("'", "\\'")

    // ─────────────────────────────────────
    // CDP 核心：注入 JS 并等待回复（实现）
    // ─────────────────────────────────────

    private fun injectAndSendInternal(
        windowType: String,
        systemContext: String,
        userMessage: String,
        timeoutSeconds: Int
    ): String {
        val wv = webViews[windowType]
            ?: return errorResult("窗口 $windowType 未初始化")

        if (webViewReady[windowType] != true) {
            return errorResult("窗口 $windowType 页面未就绪")
        }

        // 先检查 CAPTCHA / 登出
        val pageCheck = checkPageStateSync(windowType)
        if (pageCheck != null) return pageCheck

        // 构造注入 JS
        val escapedSystemContext = systemContext
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val escapedUserMessage = userMessage
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val injectJs = buildString {
            // 注入的内容用隐藏标记包裹
            append("(async function() {\n")
            append("  try {\n")
            // 找输入框（选择器全部来自 dom_selectors.json）
            append("    var inputEl = ${buildInputFinderJs()};\n")
            append("    if (!inputEl) return JSON.stringify({status:'error',error:'找不到输入框'});\n")
            append("\n")
            // 注入系统上下文 + Cami 原文
            append("    var fullText = '$escapedSystemContext';\n")
            if (userMessage.isNotEmpty()) {
                append("    fullText += '\\n<!-- CAMI_MSG_START -->\\n$escapedUserMessage\\n<!-- CAMI_MSG_END -->';\n")
            }
            append("\n")
            // 模拟人类逐字输入
            append("    var chars = fullText.split('');\n")
            append("    for (var i = 0; i < chars.length; i++) {\n")
            append("      var c = chars[i];\n")
            append("      if (c === '\\\\n' || c === '\\n') {\n")
            // 换行：模拟 Shift+Enter
            append("        inputEl.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',shiftKey:true,bubbles:true}));\n")
            append("        document.execCommand('insertLineBreak');\n")
            append("      } else {\n")
            append("        document.execCommand('insertText', false, c);\n")
            append("      }\n")
            // 随机 50-200ms 延迟
            append("      await new Promise(r => setTimeout(r, ${TYPING_DELAY_MIN_MS} + Math.random() * ${TYPING_DELAY_MAX_MS - TYPING_DELAY_MIN_MS}));\n")
            append("    }\n")
            append("\n")
            // 找发送按钮并点击（选择器全部来自 dom_selectors.json）
            append("    var sendBtn = ${buildSendButtonFinderJs()};\n")
            append("    if (sendBtn) {\n")
            append("      sendBtn.click();\n")
            append("      return JSON.stringify({status:'ok',message:'已发送'});\n")
            append("    } else {\n")
            append("      return JSON.stringify({status:'error',error:'找不到发送按钮'});\n")
            append("    }\n")
            append("  } catch(e) {\n")
            append("    return JSON.stringify({status:'error',error:e.message});\n")
            append("  }\n")
            append("})()")
        }

        // 同步执行注入 JS
        val injectLatch = CountDownLatch(1)
        val injectResultRef = AtomicReference<String>()

        Handler(Looper.getMainLooper()).post {
            wv.evaluateJavascript(injectJs) { result ->
                injectResultRef.set(result ?: "")
                injectLatch.countDown()
            }
        }

        val injectFinished = injectLatch.await(10, TimeUnit.SECONDS)
        if (!injectFinished) {
            return errorResult("注入 JS 超时")
        }

        val injectResult = parseJsResult(injectResultRef.get())
        val injectStatus = injectResult.optString("status", "error")
        if (injectStatus != "ok") {
            Log.w(TAG, "注入 JS 失败: ${injectResult}")
            return errorResult("注入失败: ${injectResult.optString("error", "未知错误")}")
        }

        Log.d(TAG, "窗口 $windowType 注入成功，等待 Gemini 回复...")

        // 轮询等待回复
        return waitForReply(windowType, timeoutSeconds)
    }

    /**
     * 轮询等待 Gemini 回复。
     *
     * 策略：
     *   1. 每 500ms 检查 DOM 中是否有新的回复内容
     *   2. 同时检查页面标题是否变成 CAPTCHA / Sign in
     *   3. 15 秒硬超时
     */
    private fun waitForReply(windowType: String, timeoutSeconds: Int): String {
        val wv = webViews[windowType] ?: return errorResult("WebView 已释放")
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L

        // 记录注入前的页面状态（用于 diff 检测新回复）
        val initialState = AtomicReference<String>()

        // 获取初始状态
        val initLatch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            wv.evaluateJavascript("""
                (function() {
                    var responses = document.querySelectorAll('${buildResponseSelector().escapeJs()}');
                    return JSON.stringify({
                        count: responses.length,
                        title: document.title || '',
                        url: window.location.href || ''
                    });
                })()
            """.trimIndent()) { result ->
                initialState.set(result ?: "")
                initLatch.countDown()
            }
        }
        initLatch.await(3, TimeUnit.SECONDS)

        val initial = parseJsResult(initialState.get())
        val initialCount = initial.optInt("count", 0)

        // 轮询循环
        while (System.currentTimeMillis() < deadline) {
            val pollLatch = CountDownLatch(1)
            val pollResultRef = AtomicReference<String>()

            Handler(Looper.getMainLooper()).post {
                wv.evaluateJavascript("""
                    (function() {
                        var responses = document.querySelectorAll('${buildResponseSelector().escapeJs()}');
                        var lastResponse = '';
                        if (responses.length > 0) {
                            var last = responses[responses.length - 1];
                            lastResponse = last.innerText || last.textContent || '';
                        }
                        return JSON.stringify({
                            count: responses.length,
                            lastText: lastResponse.substring(0, 5000),
                            title: document.title || '',
                            url: window.location.href || ''
                        });
                    })()
                """.trimIndent()) { result ->
                    pollResultRef.set(result ?: "")
                    pollLatch.countDown()
                }
            }

            pollLatch.await(2, TimeUnit.SECONDS)
            val current = parseJsResult(pollResultRef.get())
            val currentCount = current.optInt("count", 0)
            val lastText = current.optString("lastText", "")
            val title = current.optString("title", "")
            val url = current.optString("url", "")

            // 检查 CAPTCHA
            if (isCaptchaTitle(title)) {
                Log.w(TAG, "窗口 $windowType 检测到 CAPTCHA: $title")
                notifyCaptchaDetected(windowType, title)
                return captchaResult(title)
            }

            // 检查 Google 登出
            if (isSignInPage(title, url)) {
                Log.w(TAG, "窗口 $windowType 检测到 Google 登出: title=$title url=$url")
                notifyGoogleSignedOut(windowType)
                return signedOutResult()
            }

            // 有新回复
            if (currentCount > initialCount && lastText.isNotEmpty()) {
                // 提取 CAMI_MSG_START/END 之间的 Cami 原文
                val camiText = extractCamiText(lastText)
                // 丢弃 SYSTEM_CONTEXT 内容，只保留安知回复
                val replyText = cleanReply(lastText)

                Log.d(TAG, "窗口 $windowType 收到回复 (${replyText.length} chars, cami=${camiText.length} chars)")
                return successResult(replyText, camiText)
            }

            // 等待下一次轮询
            Thread.sleep(POLL_INTERVAL_MS)
        }

        // 超时：再检查一次是否为 CAPTCHA
        Log.w(TAG, "窗口 $windowType 回复等待超时 (${timeoutSeconds}s)")
        val timeoutCheck = checkPageStateSync(windowType)
        return timeoutCheck ?: timeoutResult()
    }

    // ─────────────────────────────────────
    // 页面状态检测
    // ─────────────────────────────────────

    /** 同步检查页面状态 */
    private fun checkPageStateSync(windowType: String): String? {
        val wv = webViews[windowType] ?: return null
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String>()

        Handler(Looper.getMainLooper()).post {
            wv.evaluateJavascript("""
                (function() {
                    return JSON.stringify({
                        title: document.title || '',
                        url: window.location.href || ''
                    });
                })()
            """.trimIndent()) { result ->
                resultRef.set(result ?: "")
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)
        val state = parseJsResult(resultRef.get())
        val title = state.optString("title", "")
        val url = state.optString("url", "")

        if (isCaptchaTitle(title)) {
            notifyCaptchaDetected(windowType, title)
            return captchaResult(title)
        }
        if (isSignInPage(title, url)) {
            notifyGoogleSignedOut(windowType)
            return signedOutResult()
        }
        return null
    }

    /** 异步检查页面状态（在 onPageFinished 中调用） */
    private fun checkPageState(windowType: String, jsResult: String) {
        val state = parseJsResult(jsResult)
        val title = state.optString("title", "")
        val url = state.optString("url", "")

        if (isCaptchaTitle(title)) {
            Log.w(TAG, "页面加载后检测到 CAPTCHA: $title")
            notifyCaptchaDetected(windowType, title)
        }
        if (isSignInPage(title, url)) {
            Log.w(TAG, "页面加载后检测到 Google 登出")
            notifyGoogleSignedOut(windowType)
        }
    }

    private fun isCaptchaTitle(title: String): Boolean {
        // 优先级：DomRegistry 配置 → 硬兜底
        val patterns = domRegistry.getCaptchaTitlePatterns().ifEmpty {
            listOf(
                "Just a moment...", "CAPTCHA", "are you a human",
                "Verify you are human", "Checking your browser",
                "One more step", "Please verify"
            )
        }
        return patterns.any { title.contains(it, ignoreCase = true) }
    }

    private fun isSignInPage(title: String, url: String): Boolean {
        return title.contains("Sign in", ignoreCase = true) ||
                title.contains("Google Account", ignoreCase = true) ||
                url.contains("accounts.google.com")
    }

    // ─────────────────────────────────────
    // 文本提取
    // ─────────────────────────────────────

    /**
     * 从回复中提取 Cami 原文。
     * 只提取 CAMI_MSG_START 和 CAMI_MSG_END 之间的内容。
     */
    private fun extractCamiText(rawText: String): String {
        val startMarker = "<!-- CAMI_MSG_START -->"
        val endMarker = "<!-- CAMI_MSG_END -->"
        val startIdx = rawText.indexOf(startMarker)
        val endIdx = rawText.indexOf(endMarker)
        return if (startIdx >= 0 && endIdx > startIdx) {
            rawText.substring(startIdx + startMarker.length, endIdx).trim()
        } else ""
    }

    /**
     * 清理回复文本：丢弃系统上下文标记内容，返回安知的纯文本回复。
     */
    private fun cleanReply(rawText: String): String {
        var cleaned = rawText
        // 丢弃 SYSTEM_CONTEXT_START/END
        val sysStart = "<!-- SYSTEM_CONTEXT_START -->"
        val sysEnd = "<!-- SYSTEM_CONTEXT_END -->"
        val sysStartIdx = cleaned.indexOf(sysStart)
        val sysEndIdx = cleaned.indexOf(sysEnd)
        if (sysStartIdx >= 0 && sysEndIdx > sysStartIdx) {
            cleaned = cleaned.substring(0, sysStartIdx) +
                    cleaned.substring(sysEndIdx + sysEnd.length)
        }
        // 丢弃 CAMI_MSG_START/END 标记（但保留中间的用户原文）
        cleaned = cleaned.replace("<!-- CAMI_MSG_START -->", "")
            .replace("<!-- CAMI_MSG_END -->", "")
        return cleaned.trim()
    }

    // ─────────────────────────────────────
    // 回调通知
    // ─────────────────────────────────────

    private fun notifyCaptchaDetected(windowType: String, pageTitle: String) {
        synchronized(callbacks) {
            for (cb in callbacks) {
                try { cb.onCaptchaDetected(windowType, pageTitle) }
                catch (e: Exception) { Log.w(TAG, "回调 onCaptchaDetected 失败: ${e.message}") }
            }
        }
    }

    private fun notifyGoogleSignedOut(windowType: String) {
        synchronized(callbacks) {
            for (cb in callbacks) {
                try { cb.onGoogleSignedOut(windowType) }
                catch (e: Exception) { Log.w(TAG, "回调 onGoogleSignedOut 失败: ${e.message}") }
            }
        }
    }

    private fun notifyPageLoadFailed(windowType: String, errorMessage: String) {
        synchronized(callbacks) {
            for (cb in callbacks) {
                try { cb.onPageLoadFailed(windowType, errorMessage) }
                catch (e: Exception) { Log.w(TAG, "回调 onPageLoadFailed 失败: ${e.message}") }
            }
        }
    }

    // ─────────────────────────────────────
    // JSON 构造
    // ─────────────────────────────────────

    private fun successResult(reply: String, camiText: String): String = JSONObject().apply {
        put("status", "ok")
        put("reply", reply)
        put("camiText", camiText)
    }.toString()

    private fun errorResult(message: String): String = JSONObject().apply {
        put("status", "error")
        put("message", message)
    }.toString()

    private fun timeoutResult(): String = JSONObject().apply {
        put("status", "timeout")
        put("message", "等待 Gemini 回复超时")
    }.toString()

    private fun captchaResult(title: String): String = JSONObject().apply {
        put("status", "captcha")
        put("title", title)
        put("message", "检测到 CAPTCHA 验证页面")
    }.toString()

    private fun signedOutResult(): String = JSONObject().apply {
        put("status", "signed_out")
        put("message", "Google 账号已登出，需要 Cami 重新登录")
    }.toString()

    /** 解析 evaluateJavascript 返回的 JSON 字符串（去掉外层引号） */
    private fun parseJsResult(raw: String): JSONObject {
        val trimmed = raw.trim()
        // evaluateJavascript 会在 JSON 外包裹引号，需要去掉
        val json = if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else trimmed
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "解析 JS 结果失败: ${json.take(200)}")
            JSONObject()
        }
    }

    // ─────────────────────────────────────
    // JS Bridge（供 WebView 内 JS 回调 Kotlin）
    // ─────────────────────────────────────

    /**
     * 注入到 WebView 的 JS 接口。
     * 用于 DOM 事件主动回调 Kotlin（如 MutationObserver 检测到变化）。
     */
    inner class CdpJsBridge(private val windowType: String) {
        @JavascriptInterface
        fun onReplyReady(replyText: String) {
            Log.d(TAG, "JS Bridge: 窗口 $windowType 回复就绪 (${replyText.length} chars)")
            // 由轮询机制处理，此接口作为补充
        }

        @JavascriptInterface
        fun onCaptchaDetected(title: String) {
            Log.w(TAG, "JS Bridge: 窗口 $windowType 检测到 CAPTCHA: $title")
            notifyCaptchaDetected(windowType, title)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "JS Console [$windowType]: $message")
        }
    }
}
