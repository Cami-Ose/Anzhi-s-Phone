package com.anzhi.os

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 安知的眼睛——静默读 UI 树。
 *
 * 设计原则（和 Enzo 的 CDP DOM 读取一模一样）：
 *   拿到当前界面的结构化信息，发给 DeepSeek 让安知"看懂"屏幕。
 *
 * 为什么优先用 UI 树而不是截图？
 *   1. 快——几毫秒就拿到了
 *   2. 便宜——不调用 Gemini，直接给 DeepSeek 文本
 *   3. 精确——每个元素的坐标、文本、是否可点击，一目了然
 *
 * 只在操作指令时读 UI 树。不做持续监听——避免产生大量无用数据。
 * 外界通过 [instance] 单例引用触发读取。
 */
class AnzhiAccessibility : AccessibilityService() {

    companion object {
        private const val TAG = "AnzhiAccessibility"

        // ── 安全上限（防 OOM / ANR）──
        private const val MAX_DEPTH = 20          // 最大递归深度
        private const val MAX_NODES = 200         // 单次最多收集节点数
        private const val NODE_TIMEOUT_MS = 500L  // 读树超时（毫秒）

        // 单例引用，让 AnzhiManagerService 可以触发 UI 树读取
        @Volatile
        var instance: AnzhiAccessibility? = null
            private set

        /** 服务死亡回调（陷阱 20：Manager 持僵尸 Binder 检测） */
        var onServiceDied: (() -> Unit)? = null
    }

    // ── 心跳防僵尸（陷阱 20）──
    private val handler = Handler(Looper.getMainLooper())
    private var lastPingResponse = 0L
    private val isAlive = AtomicBoolean(false)

    // 缓存显示尺寸用于坐标转换
    private val displaySize: Point by lazy {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        wm.defaultDisplay.getRealSize(size)
        size
    }

    // ── 生命周期 ──────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        isAlive.set(true)
        // displaySize 通过 lazy 延迟初始化
        Log.i(TAG, "👁️ 安知之眼已开启")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 配置服务信息：要什么事件、什么能力
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100 // ms，快速响应
        }
        setServiceInfo(info)
        Log.i(TAG, "👁️ 安知之眼服务已连接，配置已应用")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不主动读 UI 树。只在 AnzhiManagerService 通过 instance 引用
        // 显式调用 getUITree() / findNodeBy*() 时才读。
        // 这里只做轻量日志。
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "窗口切换: ${event?.packageName} → ${event?.className}")
                // 唤醒链路的事件输入之一：连切 5 个 App → 叫安安知看一眼（README §九）。
                // 只交包名，不去读 UI 树。
                AnzhiManagerService.feedForegroundApp(event?.packageName?.toString())
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化，不记录（太频繁）
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "安知之眼被系统中断")
    }

    override fun onDestroy() {
        isAlive.set(false)
        instance = null
        onServiceDied?.invoke()
        Log.i(TAG, "安知之眼已关闭（死亡遗嘱已发送）")
        super.onDestroy()
    }

    // ── 心跳（陷阱 20：防 Manager 持僵尸 Binder）──

    /**
     * Manager 通过 Messenger 发来 ping，此方法响应。
     * 连续 3 次无响应 → Manager 判定 Service 死亡 → 重新 bind。
     */
    fun pong(): Long {
        lastPingResponse = System.currentTimeMillis()
        return lastPingResponse
    }

    fun isServiceAlive(): Boolean = isAlive.get()

    // ═════════════════════════════════════════════
    // 核心：读 UI 树，序列化为 JSON
    // ═════════════════════════════════════════════

    /**
     * 获取当前屏幕 UI 树的 JSON 表示。
     *
     * 输出格式（给 DeepSeek 看）：
     * {
     *   "app": "com.tencent.mm",
     *   "window_title": "微信",
     *   "node_count": 45,
     *   "truncated": false,
     *   "nodes": [
     *     {
     *       "class": "Button", "text": "发送", "desc": "",
     *       "id": "com.tencent.mm:id/send_btn",
     *       "bounds": [100, 200, 200, 300],
     *       "norm_center": [150, 250],
     *       "clickable": true, "long_clickable": false,
     *       "scrollable": false, "editable": false,
     *       "checkable": false, "checked": false,
     *       "enabled": true, "focused": false
     *     },
     *     ...
     *   ]
     * }
     *
     * "norm_center" 是归一化中心坐标 (0-1000)，可直接存入路径缓存。
     */
    fun getUITree(): JSONObject {
        val startTime = System.currentTimeMillis()
        val root = getRootSafely() ?: return JSONObject().apply {
            put("error", "no_root")
            put("elapsed_ms", System.currentTimeMillis() - startTime)
        }

        val result = JSONObject()
        result.put("app", root.packageName?.toString() ?: "unknown")
        result.put("window_title", root.window?.title?.toString() ?: "")
        result.put("elapsed_ms", 0) // 会在下面更新

        val nodeCount = AtomicInteger(0)
        val timedOut = AtomicBoolean(false)

        val nodes = serializeNode(root, depth = 0, nodeCount = nodeCount, timedOut = timedOut,
            deadlineMs = startTime + NODE_TIMEOUT_MS)
        root.recycle()

        result.put("node_count", nodeCount.get())
        result.put("truncated", nodeCount.get() >= MAX_NODES || timedOut.get())
        result.put("nodes", nodes)
        result.put("elapsed_ms", System.currentTimeMillis() - startTime)
        return result
    }

    /**
     * 获取紧凑版 UI 树——去掉冗余字段，适合塞进 LLM 上下文。
     *
     * 输出格式（每节点一行信息）：
     * {
     *   "app": "com.tencent.mm",
     *   "text": "class=Button text='发送' norm=[150,250] clickable editable=false",
     *   "nodes": ["class=...", "class=...", ...]
     * }
     *
     * 紧凑模式下只保留：class、text、desc、norm_center、isClickable、isEditable。
     * 比完整模式节省 ~60% token。
     */
    fun getUITreeCompact(): JSONObject {
        val startTime = System.currentTimeMillis()
        val root = getRootSafely() ?: return JSONObject().apply {
            put("error", "no_root")
        }

        val result = JSONObject()
        result.put("app", root.packageName?.toString() ?: "unknown")

        val nodeCount = AtomicInteger(0)
        val timedOut = AtomicBoolean(false)
        val strings = JSONArray()

        serializeNodeCompact(root, depth = 0, nodeCount = nodeCount, timedOut = timedOut,
            deadlineMs = startTime + NODE_TIMEOUT_MS, target = strings)
        root.recycle()

        result.put("node_count", nodeCount.get())
        result.put("truncated", nodeCount.get() >= MAX_NODES || timedOut.get())
        result.put("nodes", strings)
        result.put("elapsed_ms", System.currentTimeMillis() - startTime)
        return result
    }

    // ── 序列化（完整模式）─────────────────────────

    private fun serializeNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        nodeCount: AtomicInteger,
        timedOut: AtomicBoolean,
        deadlineMs: Long
    ): JSONArray {
        val list = JSONArray()

        // 安全阀：深度/数量/超时
        if (depth > MAX_DEPTH || nodeCount.get() >= MAX_NODES) return list
        if (System.currentTimeMillis() > deadlineMs) {
            timedOut.set(true)
            return list
        }

        if (isInterestingNode(node)) {
            nodeCount.incrementAndGet()
            list.put(serializeOneNode(node))
        }

        // 递归子节点
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (nodeCount.get() >= MAX_NODES || System.currentTimeMillis() > deadlineMs) break
            try {
                val child = node.getChild(i)
                if (child != null) {
                    val childNodes = serializeNode(child, depth + 1, nodeCount, timedOut, deadlineMs)
                    for (j in 0 until childNodes.length()) {
                        list.put(childNodes.getJSONObject(j))
                    }
                    child.recycle()
                }
            } catch (e: Exception) {
                // getChild() 偶尔抛 NPE（App 在节点遍历中途退出），静默跳过
                Log.d(TAG, "getChild($i) 失败: ${e.message}")
            }
        }

        return list
    }

    private fun serializeOneNode(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val normCenter = uiTreeBoundsToNormalizedCenter(rect)

        return JSONObject().apply {
            put("class", node.className?.toString() ?: "unknown")
            put("text", node.text?.toString() ?: "")
            put("desc", node.contentDescription?.toString() ?: "")
            node.viewIdResourceName?.let { put("id", it) }

            // 像素坐标
            put("bounds", JSONArray().apply {
                put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
            })

            // 归一化中心坐标（给路径缓存用）
            put("norm_center", JSONArray().apply {
                put(normCenter.x); put(normCenter.y)
            })

            // 交互能力
            put("clickable", node.isClickable)
            put("long_clickable", node.isLongClickable)
            put("scrollable", node.isScrollable)
            put("editable", node.isEditable)
            put("checkable", node.isCheckable)
            put("checked", node.isChecked)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
        }
    }

    // ── 序列化（紧凑模式）─────────────────────────

    private fun serializeNodeCompact(
        node: AccessibilityNodeInfo,
        depth: Int,
        nodeCount: AtomicInteger,
        timedOut: AtomicBoolean,
        deadlineMs: Long,
        target: JSONArray
    ) {
        if (depth > MAX_DEPTH || nodeCount.get() >= MAX_NODES) return
        if (System.currentTimeMillis() > deadlineMs) {
            timedOut.set(true)
            return
        }

        if (isInterestingNode(node)) {
            nodeCount.incrementAndGet()
            target.put(serializeOneNodeCompact(node))
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (nodeCount.get() >= MAX_NODES || System.currentTimeMillis() > deadlineMs) break
            try {
                val child = node.getChild(i)
                if (child != null) {
                    serializeNodeCompact(child, depth + 1, nodeCount, timedOut, deadlineMs, target)
                    child.recycle()
                }
            } catch (e: Exception) {
                Log.d(TAG, "getChild($i) 失败(compact): ${e.message}")
            }
        }
    }

    private fun serializeOneNodeCompact(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val nc = uiTreeBoundsToNormalizedCenter(rect)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(40)?.replace("'", "\\'") ?: ""
        val desc = node.contentDescription?.toString()?.take(40)?.replace("'", "\\'") ?: ""
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""

        val sb = StringBuilder()
        sb.append("class=$cls")
        if (text.isNotEmpty()) sb.append(" text='$text'")
        if (desc.isNotEmpty()) sb.append(" desc='$desc'")
        if (id.isNotEmpty()) sb.append(" id=$id")
        sb.append(" norm=[${nc.x},${nc.y}]")
        if (node.isClickable) sb.append(" clickable")
        if (node.isEditable) sb.append(" editable")
        if (node.isScrollable) sb.append(" scrollable")
        if (!node.isEnabled) sb.append(" disabled")
        return sb.toString()
    }

    // ═════════════════════════════════════════════
    // 搜索：在当前 UI 树中定位元素
    // ═════════════════════════════════════════════

    /**
     * 按文本查找元素，返回中心像素坐标。
     * 在 Dispatchers.IO 上调用，复杂界面可能卡几百毫秒。
     */
    fun findNodeByText(text: String): Pair<Int, Int>? {
        val root = getRootSafely() ?: return null
        try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes.firstOrNull()
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                node.recycle()
                return Pair(cx, cy)
            }
            return null
        } finally {
            root.recycle()
        }
    }

    /**
     * 按文本查找，返回归一化中心坐标 + 像素坐标。
     * 归一化坐标可直接存入路径缓存。
     */
    fun findNodeByTextNormalized(text: String): NodeLocation? {
        val root = getRootSafely() ?: return null
        try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes.firstOrNull() ?: return null
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val norm = uiTreeBoundsToNormalizedCenter(rect)
            node.recycle()
            return NodeLocation(
                pixelX = rect.centerX(), pixelY = rect.centerY(),
                normX = norm.x, normY = norm.y,
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                viewId = node.viewIdResourceName ?: ""
            )
        } finally {
            root.recycle()
        }
    }

    /**
     * 按 resource-id 查找元素。
     * 例如：findNodeById("com.tencent.mm:id/send_btn")
     */
    fun findNodeById(resourceId: String): NodeLocation? {
        val root = getRootSafely() ?: return null
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            val node = nodes.firstOrNull() ?: return null
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val norm = uiTreeBoundsToNormalizedCenter(rect)
            val loc = NodeLocation(
                pixelX = rect.centerX(), pixelY = rect.centerY(),
                normX = norm.x, normY = norm.y,
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                viewId = node.viewIdResourceName ?: ""
            )
            node.recycle()
            return loc
        } finally {
            root.recycle()
        }
    }

    /**
     * 按 contentDescription 查找元素。
     */
    fun findNodeByDesc(desc: String): NodeLocation? {
        val root = getRootSafely() ?: return null
        try {
            val node = findNodeByDescRecursive(root, desc)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val norm = uiTreeBoundsToNormalizedCenter(rect)
                val loc = NodeLocation(
                    pixelX = rect.centerX(), pixelY = rect.centerY(),
                    normX = norm.x, normY = norm.y,
                    className = node.className?.toString() ?: "",
                    text = node.text?.toString() ?: "",
                    viewId = node.viewIdResourceName ?: ""
                )
                node.recycle()
                return loc
            }
            return null
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByDescRecursive(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findNodeByDescRecursive(child, desc)
                child.recycle()
                if (found != null) return found
            } catch (_: Exception) { continue }
        }
        return null
    }

    /**
     * 按类名查找所有匹配元素。
     * 例如：findNodesByClass("EditText") → 当前界面所有输入框。
     */
    fun findNodesByClass(className: String): List<NodeLocation> {
        val root = getRootSafely() ?: return emptyList()
        val results = mutableListOf<NodeLocation>()
        try {
            findNodesByClassRecursive(root, className, results)
        } finally {
            root.recycle()
        }
        return results
    }

    private fun findNodesByClassRecursive(
        node: AccessibilityNodeInfo,
        className: String,
        results: MutableList<NodeLocation>
    ) {
        if (results.size >= MAX_NODES) return
        val cls = node.className?.toString() ?: ""
        if (cls.contains(className, ignoreCase = true)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val norm = uiTreeBoundsToNormalizedCenter(rect)
            results.add(NodeLocation(
                pixelX = rect.centerX(), pixelY = rect.centerY(),
                normX = norm.x, normY = norm.y,
                className = cls,
                text = node.text?.toString() ?: "",
                viewId = node.viewIdResourceName ?: ""
            ))
        }
        for (i in 0 until node.childCount) {
            if (results.size >= MAX_NODES) break
            try {
                val child = node.getChild(i) ?: continue
                findNodesByClassRecursive(child, className, results)
                child.recycle()
            } catch (_: Exception) { continue }
        }
    }

    // ═════════════════════════════════════════════
    // 操作：在当前 UI 树上执行动作
    // ═════════════════════════════════════════════

    /**
     * 对当前聚焦的节点执行 click action。
     * 适用场景：DeepSeek 已经通过 findNodeBy* 定位到了节点，
     * 但更安全的做法是通过 AccessibilityService 直接 click 而非 inject tap。
     *
     * 优势：不走 injectInputEvent，不需要 INJECT_EVENTS 权限，
     * 且 AccessibilityService 的 click 更可靠。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "clickNode 失败: ${e.message}")
            false
        }
    }

    /**
     * 长按节点。
     */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "longClickNode 失败: ${e.message}")
            false
        }
    }

    /**
     * 对可滚动的节点向上滚动。
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } catch (e: Exception) {
            Log.w(TAG, "scrollForward 失败: ${e.message}")
            false
        }
    }

    /**
     * 对可滚动的节点向下滚动。
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } catch (e: Exception) {
            Log.w(TAG, "scrollBackward 失败: ${e.message}")
            false
        }
    }

    /**
     * 向可编辑节点注入文本（不经过系统剪贴板！见陷阱 16）。
     *
     * Android 15 对后台剪贴板访问施加了驱动级封杀。
     * 此方法通过 ACTION_SET_TEXT 直接向文本框句柄注入字符流，
     * 完全绕过 ClipboardManager——系统无法检测，也不会弹窗。
     */
    fun setTextDirect(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "setTextDirect 失败: ${e.message}")
            false
        }
    }

    // ═════════════════════════════════════════════
    // 内部工具
    // ═════════════════════════════════════════════

    /**
     * 安全获取根节点。连续 3 次拿不到 → 返回 null。
     * 短间隔重试可应对 AccessibilityService 瞬时无窗口的边界情况。
     */
    private fun getRootSafely(): AccessibilityNodeInfo? {
        for (attempt in 1..3) {
            try {
                val root = rootInActiveWindow
                if (root != null) return root
                Log.d(TAG, "getRoot 返回 null，第 $attempt 次重试")
                Thread.sleep(30)
            } catch (e: Exception) {
                Log.w(TAG, "getRoot 异常(attempt $attempt): ${e.message}")
            }
        }
        Log.w(TAG, "getRoot 连续 3 次失败，当前无活跃窗口")
        return null
    }

    /**
     * UI 树节点像素边界 → 归一化中心点 (0-1000)。
     * 替代已删除的 CoordinateMapper。
     */
    private fun uiTreeBoundsToNormalizedCenter(rect: Rect): Point {
        val cx = (rect.left + rect.right) / 2
        val cy = (rect.top + rect.bottom) / 2
        val nx = (cx * 1000 / displaySize.x.coerceAtLeast(1)).coerceIn(0, 1000)
        val ny = (cy * 1000 / displaySize.y.coerceAtLeast(1)).coerceIn(0, 1000)
        return Point(nx, ny)
    }

    /**
     * 判断节点是否值得发给安知。
     * 过滤掉空容器、不可见元素等噪音。
     */
    private fun isInterestingNode(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isLongClickable
                || node.isEditable || node.isCheckable
                || node.isScrollable
        return hasText || hasDesc || isInteractive
    }
}

/**
 * UI 树节点定位信息。
 *
 * @param pixelX / pixelY 屏幕像素中心坐标（给 InputInjector）
 * @param normX / normY 归一化中心坐标 0-1000（给路径缓存）
 * @param className Android 类名（如 "android.widget.Button"）
 * @param text 节点文本内容
 * @param viewId resource-id（如 "com.tencent.mm:id/send_btn"）
 */
data class NodeLocation(
    val pixelX: Int,
    val pixelY: Int,
    val normX: Int,
    val normY: Int,
    val className: String,
    val text: String,
    val viewId: String
)
