package com.anzhi.os.cdp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * CAPTCHA 手动解盾 Activity。
 *
 * BUILD.md §陷阱 10 防御方案：
 *   后台 headless WebView 无法凭空渲染到前台。
 *   必须预埋此 Activity（透明主题），收到解盾通知后 Cami 点击通知 →
 *   此 Activity 启动 → 把 :webview 进程的渲染 Surface 抢到前台 →
 *   Cami 手动点斑马线 → 完成后 Activity 自毁，Surface 归还后台。
 *
 * 流程：
 *   1. WebViewManager 检测到 CAPTCHA → 发送通知
 *   2. Cami 点击通知 → 启动 CaptchaActivity
 *   3. CaptchaActivity 加载 CAPTCHA 页面的 URL
 *   4. Cami 手动解决 CAPTCHA
 *   5. Cami 点"完成"按钮 → Activity 自毁
 *   6. WebViewManager 收到 CAPTCHA 已解决 → 恢复正常
 */
class CaptchaActivity : Activity() {

    companion object {
        private const val TAG = "CaptchaActivity"

        /** 启动 CaptchaActivity 所需参数的 Intent extra keys */
        const val EXTRA_WINDOW_TYPE = "window_type"
        const val EXTRA_PAGE_TITLE = "page_title"
        const val EXTRA_CURRENT_URL = "current_url"
    }

    private var webView: WebView? = null
    private var windowType: String = ""
    private var captchaResolved = false
    private var onCaptchaResolved: ((String) -> Unit)? = null

    private lateinit var domRegistry: DomRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 加载 DOM 选择器配置
        domRegistry = DomRegistry.load(this)

        windowType = intent?.getStringExtra(EXTRA_WINDOW_TYPE) ?: "wake"
        val pageTitle = intent?.getStringExtra(EXTRA_PAGE_TITLE) ?: "CAPTCHA"
        val currentUrl = intent?.getStringExtra(EXTRA_CURRENT_URL) ?: ""

        Log.i(TAG, "CAPTCHA Activity 启动: windowType=$windowType")

        // 设置窗口属性：浮在最上面，不干扰正常 Activity 栈
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android 8+ 使用 TYPE_APPLICATION_OVERLAY
        }
        window?.apply {
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
        }

        // 创建布局
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF_FFFFFF.toInt())
        }

        // 顶部提示栏
        val headerBar = FrameLayout(this).apply {
            setBackgroundColor(0xFF_2196F3.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56)
            ).apply { gravity = Gravity.TOP }
        }

        val titleView = TextView(this).apply {
            text = "Google 以为安知是机器人 🦓"
            textSize = 16f
            setTextColor(0xFF_FFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        headerBar.addView(titleView)

        // WebView 显示 CAPTCHA 页面
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 标准 Android 浏览器 UA（不是桌面版，CAPTCHA 验证通常期望移动端）
                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "CAPTCHA WebView 页面加载: $url")
                    // 检查是否已经绕过 CAPTCHA（页面标题不再是验证页面）
                    view?.evaluateJavascript("""
                        (function() {
                            var title = document.title || '';
                            return title;
                        })()
                    """.trimIndent()) { result ->
                        val title = result?.trim()?.removeSurrounding("\"") ?: ""
                        if (!isCaptchaTitle(title)) {
                            Log.i(TAG, "CAPTCHA 似乎已解决，页面标题: $title")
                            captchaResolved = true
                        }
                    }
                }
            }

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dpToPx(56)
                bottomMargin = dpToPx(64)
            }

            if (currentUrl.isNotEmpty()) {
                loadUrl(currentUrl)
            }
        }

        // 底部按钮栏
        val bottomBar = FrameLayout(this).apply {
            setBackgroundColor(0xFF_F5F5F5.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(64)
            ).apply { gravity = Gravity.BOTTOM }
        }

        val doneButton = Button(this).apply {
            text = "✓ 搞定了"
            textSize = 16f
            setBackgroundColor(0xFF_4CAF50.toInt())
            setTextColor(0xFF_FFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            setOnClickListener {
                onDoneClicked()
            }
        }
        bottomBar.addView(doneButton)

        val closeButton = Button(this).apply {
            text = "✗ 算了"
            textSize = 14f
            setBackgroundColor(0xFF_9E9E9E.toInt())
            setTextColor(0xFF_FFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER or Gravity.END
                marginEnd = dpToPx(16)
            }
            setOnClickListener {
                captchaResolved = false
                finish()
            }
        }
        bottomBar.addView(closeButton)

        rootLayout.addView(headerBar)
        webView?.let { rootLayout.addView(it) }
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun onDoneClicked() {
        Log.i(TAG, "Cami 点击完成，CAPTCHA 解盾流程结束: windowType=$windowType")

        // 通知 WebViewManager CAPTCHA 已解决
        val intent = Intent("com.anzhi.os.CAPTCHA_RESOLVED").apply {
            setPackage(packageName)
            putExtra(EXTRA_WINDOW_TYPE, windowType)
        }
        sendBroadcast(intent)

        captchaResolved = true
        finish()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        Log.i(TAG, "CAPTCHA Activity 销毁: resolved=$captchaResolved")
        super.onDestroy()
    }

    private fun isCaptchaTitle(title: String): Boolean {
        // 从 DomRegistry 加载模式，兜底硬编码
        val patterns = domRegistry.getCaptchaTitlePatterns().ifEmpty {
            listOf(
                "Just a moment...", "CAPTCHA", "are you a human",
                "Verify you are human", "Checking your browser",
                "One more step", "Please verify"
            )
        }
        return patterns.any { title.contains(it, ignoreCase = true) }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
