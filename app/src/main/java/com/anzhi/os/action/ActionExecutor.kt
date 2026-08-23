package com.anzhi.os.action

import android.content.Context
import android.graphics.Point
import android.hardware.input.InputManager
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.anzhi.os.ActionTransactionLock
import android.anzhi.IAnzhiCoreService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 动作执行器 —— AnzhiAction → StepResult。
 *
 * 从 OpenCyvis ActionExecutor 搬运并适配安知专属动作。
 * 使用 platform_apis 直接调用隐藏 API，不再依赖 PrivilegeBackend 体系。
 *
 * 后端优先级：
 *   1. AnzhiCoreService AIDL（若已注册）
 *   2. InputManager hidden API（需 platform_apis）
 *
 * @param displayId 目标显示 ID（0=物理屏，>0=虚拟屏）
 * @param onOpenAppSuccess 打开应用成功回调
 * @param onSpeak 安知说话回调（由外部实现 VPS 调用）
 * @param onQueryMemory 记忆库查询回调（由外部实现）
 * @param onLogMood 心情记录回调（由外部实现）
 */
class ActionExecutor(
    private val context: Context,
    private val displayId: Int = 0,
    displaySize: Point? = null,
    private val onOpenAppSuccess: ((packageName: String) -> Unit)? = null,
    private val blacklistedPackages: Set<String> = emptySet(),
    // 安知专属回调
    private val onSpeak: ((text: String, voice: String, emotion: String) -> Unit)? = null,
    private val onQueryMemory: ((query: String, category: String, limit: Int) -> String)? = null,
    private val onLogMood: ((score: Int, label: String, note: String) -> Unit)? = null
) {
    // ═══════════════════════════════════════════
    // 核心后端
    // ═══════════════════════════════════════════

    /** AnzhiCoreService AIDL 代理（优先路径） */
    private val coreService: IAnzhiCoreService? by lazy {
        try {
            val binder = ServiceManager.getService("anzhi_core")
            if (binder != null) IAnzhiCoreService.Stub.asInterface(binder) else null
        } catch (e: Exception) {
            Log.w(TAG, "AnzhiCoreService 不可用: ${e.message}")
            null
        }
    }

    /** InputManager 隐藏 API 备用（需 platform_apis） */
    private val inputManager: InputManager by lazy {
        context.getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    private val appLauncher = AppLauncher(context, displayId)

    // ── 显示尺寸（归一化坐标 0-1000 → 像素）──

    private val displayWidth: Int
    private val displayHeight: Int

    init {
        if (displaySize != null) {
            displayWidth = displaySize.x
            displayHeight = displaySize.y
        } else {
            val w = coreService?.displayWidth
            val h = coreService?.displayHeight
            displayWidth = if (w != null && w > 0) w else 1080
            displayHeight = if (h != null && h > 0) h else 2400
        }
    }

    private val swipeDirections = mapOf(
        "up" to intArrayOf(500, 700, 500, 300),
        "down" to intArrayOf(500, 300, 500, 700),
        "left" to intArrayOf(700, 500, 300, 500),
        "right" to intArrayOf(300, 500, 700, 500)
    )

    // ═══════════════════════════════════════════
    // 坐标转换
    // ═══════════════════════════════════════════

    /** 归一化坐标轴 (0-1000) → 像素 */
    private fun normToPx(n: Int, dimension: Int): Int {
        return (n.coerceIn(0, 1000) * dimension) / 1000
    }

    private fun nx(n: Int) = normToPx(n, displayWidth)
    private fun ny(n: Int) = normToPx(n, displayHeight)

    // ═══════════════════════════════════════════
    // 输入注入（隐藏 API，需 platform_apis）
    // ═══════════════════════════════════════════

    private fun buildMotionEvent(
        downTime: Long, eventTime: Long, action: Int, x: Float, y: Float
    ): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1.0f; size = 1.0f
        }
        return MotionEvent.obtain(downTime, eventTime, action, 1,
            arrayOf(pp), arrayOf(pc), 0, 0, 1.0f, 1.0f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun injectEvent(event: MotionEvent): Boolean {
        return inputManager.injectInputEvent(event, INJECT_MODE_WAIT_FOR_FINISH)
    }

    /** 点击 (像素坐标) */
    private fun injectTap(px: Int, py: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = buildMotionEvent(t, t, MotionEvent.ACTION_DOWN, px.toFloat(), py.toFloat())
        val up = buildMotionEvent(t, t + 50, MotionEvent.ACTION_UP, px.toFloat(), py.toFloat())
        val ok = injectEvent(down) && injectEvent(up)
        down.recycle(); up.recycle()
        return ok
    }

    /** 按键 (KeyEvent) */
    private fun injectKeyPress(keyCode: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(t, t + 50, KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return inputManager.injectInputEvent(down, INJECT_MODE_WAIT_FOR_FINISH) &&
               inputManager.injectInputEvent(up, INJECT_MODE_WAIT_FOR_FINISH)
    }

    /** 滑动动画 (像素坐标) */
    private suspend fun injectSwipe(px1: Int, py1: Int, px2: Int, py2: Int, durationMs: Long = 300): Boolean {
        val t = SystemClock.uptimeMillis()
        val down = buildMotionEvent(t, t, MotionEvent.ACTION_DOWN, px1.toFloat(), py1.toFloat())
        val downOk = injectEvent(down); down.recycle()
        if (!downOk) return false

        val moveOk = suspendCancellableCoroutine { cont ->
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            animator.duration = durationMs
            var allInjected = true
            animator.addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val mx = px1 + (px2 - px1) * fraction
                val my = py1 + (py2 - py1) * fraction
                val move = buildMotionEvent(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, mx, my)
                if (!inputManager.injectInputEvent(move, INJECT_MODE_ASYNC)) allInjected = false
                move.recycle()
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    cont.resume(allInjected)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cont.resume(false)
                }
            })
            cont.invokeOnCancellation { animator.cancel() }
            animator.start()
        }

        val up = buildMotionEvent(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px2.toFloat(), py2.toFloat())
        val upOk = injectEvent(up); up.recycle()
        return downOk && moveOk && upOk
    }

    /** 文字输入：非 ASCII 走剪贴板粘贴，ASCII 走 KeyEvent 序列 */
    private suspend fun injectTypeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val hasNonAscii = text.any { it.code > 127 }
        return if (hasNonAscii) typeViaClipboard(text) else typeViaKeyEvents(text)
    }

    private suspend fun typeViaKeyEvents(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray()) ?: return typeViaClipboard(text)
        var success = true
        for (event in events) {
            if (!inputManager.injectInputEvent(event, INJECT_MODE_WAIT_FOR_FINISH)) success = false
            delay(5)
        }
        return success
    }

    private fun typeViaClipboard(text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        return injectKeyPress(KeyEvent.KEYCODE_PASTE)
    }

    private fun errorResponse(message: String): String = "错误: $message"

    companion object {
        private const val TAG = "ActionExecutor"
        private const val INJECT_MODE_ASYNC = 0
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2

        private val KEY_MAP = mapOf(
            "back" to KeyEvent.KEYCODE_BACK,
            "home" to KeyEvent.KEYCODE_HOME,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "recent" to KeyEvent.KEYCODE_APP_SWITCH,
            "power" to KeyEvent.KEYCODE_POWER,
            "volume_up" to KeyEvent.KEYCODE_VOLUME_UP,
            "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN
        )
    }

    // ═══════════════════════════════════════════
    // 核心：execute()
    // ═══════════════════════════════════════════

    /**
     * 执行一个动作，返回 StepResult。
     */
    suspend fun execute(action: AnzhiAction, step: Int): StepResult {
        val startTime = System.currentTimeMillis()

        val (success, detail) = try {
            when (action) {
                is AnzhiAction.Tap -> {
                    val ok = injectTap(nx(action.x), ny(action.y))
                    ok to "点击 (${action.x}, ${action.y})"
                }
                is AnzhiAction.LongPress -> {
                    val px = nx(action.x); val py = ny(action.y)
                    val t = SystemClock.uptimeMillis()
                    val down = buildMotionEvent(t, t, MotionEvent.ACTION_DOWN, px.toFloat(), py.toFloat())
                    val downOk = injectEvent(down); down.recycle()
                    delay(1000)
                    val up = buildMotionEvent(t, t + 1000, MotionEvent.ACTION_UP, px.toFloat(), py.toFloat())
                    val upOk = injectEvent(up); up.recycle()
                    (downOk && upOk) to "长按 (${action.x}, ${action.y})"
                }
                is AnzhiAction.OpenApp -> {
                    val result = appLauncher.launch(action.appName)
                    if (result.packageName != null && result.packageName in blacklistedPackages) {
                        false to "无法打开受保护应用: ${action.appName}"
                    } else {
                        if (result.packageName != null) onOpenAppSuccess?.invoke(result.packageName!!)
                        (result.packageName != null) to result.description
                    }
                }
                is AnzhiAction.Swipe -> {
                    val coords = swipeDirections[action.direction.lowercase()]
                    if (coords != null) {
                        val ok = injectSwipe(
                            nx(coords[0]), ny(coords[1]),
                            nx(coords[2]), ny(coords[3])
                        )
                        ok to "滑动 ${action.direction}"
                    } else false to "未知滑动方向: ${action.direction}"
                }
                is AnzhiAction.KeyEvent -> {
                    val keyCode = KEY_MAP[action.key.lowercase()]
                    if (keyCode != null) {
                        val ok = injectKeyPress(keyCode)
                        ok to "按键: ${action.key}"
                    } else false to "未知按键: ${action.key}"
                }
                is AnzhiAction.TypeText -> {
                    val ok = injectTypeText(action.text)
                    ok to "输入: ${action.text}"
                }
                is AnzhiAction.Wait -> {
                    delay(2000); true to "等待 2 秒"
                }
                is AnzhiAction.Finish -> true to "任务完成"
                is AnzhiAction.Fail -> false to "任务失败: ${action.reason}"
                is AnzhiAction.AskUser -> true to "询问用户: ${action.question}"
                is AnzhiAction.HandoffUser -> true to "转交用户: ${action.reason}"
                is AnzhiAction.Note -> true to "笔记: ${action.note}"
                is AnzhiAction.Remember -> true to "记住: ${action.key}"
                is AnzhiAction.ListApps -> {
                    val apps = appLauncher.listApps(action.keyword.ifBlank { null })
                    val kw = if (action.keyword.isNotBlank()) " 匹配 '${action.keyword}'" else ""
                    if (apps.isEmpty() && action.keyword.isNotBlank()) {
                        true to "未找到匹配 '${action.keyword}' 的应用，尝试换个关键词"
                    } else {
                        true to "已安装应用$kw (${apps.size}): ${apps.joinToString(", ")}"
                    }
                }
                is AnzhiAction.SaveRoutine -> true to "保存例行: ${action.routineName}"
                // ── 安知专属 ──
                is AnzhiAction.Speak -> {
                    onSpeak?.invoke(action.text, action.voice, action.emotion)
                    true to "安知说: ${action.text.take(100)}"
                }
                is AnzhiAction.QueryMemory -> {
                    val result = onQueryMemory?.invoke(action.query, action.category, action.limit)
                        ?: "记忆库查询暂未连接"
                    true to result
                }
                is AnzhiAction.LogMood -> {
                    onLogMood?.invoke(action.score, action.label, action.note)
                    true to "记录心情: ${action.label} (${action.score}/10)"
                }
            }
        } catch (e: Exception) {
            false to "执行异常: ${e.message}"
        }

        val duration = System.currentTimeMillis() - startTime
        return StepResult(
            step = step, actionType = action.typeName, thought = action.thought,
            success = success, detail = detail, durationMs = duration, completed = false
        )
    }

    // ═══════════════════════════════════════════
    // 批量执行（ActionTransactionLock 集成）
    // ═══════════════════════════════════════════

    /**
     * 通过全局事务锁批量执行动作序列。
     *
     * 任何改变 UI 状态的多步操作（tap→tap→tap）必须走此方法，
     * 绝不存在"快速旁路"或"紧急直通"（Invariant 2）。
     */
    suspend fun executeBatch(
        actions: List<AnzhiAction>,
        description: String = "批量操作",
        canCancelPrevious: Boolean = true
    ): List<StepResult> {
        if (actions.isEmpty()) return emptyList()

        val deferred = CompletableDeferred<List<StepResult>>()
        val results = mutableListOf<StepResult>()

        ActionTransactionLock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = canCancelPrevious,
            description = description,
            action = {
                for ((i, action) in actions.withIndex()) {
                    try {
                        val result = execute(action, step = i + 1)
                        results.add(result)
                        if (!result.success) {
                            Log.w(TAG, "步骤 ${i + 1}/${actions.size} 失败: ${result.detail}，终止后续步骤")
                            break
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "批量操作被取消: $description (已完成 ${results.size}/${actions.size} 步)")
                        deferred.complete(results.toList())
                        throw e
                    }
                }
                deferred.complete(results.toList())
            }
        ))

        return deferred.await()
    }

    /**
     * 通过全局事务锁执行单个动作。
     */
    suspend fun executeLocked(
        action: AnzhiAction,
        description: String = action.typeName,
        canCancelPrevious: Boolean = true
    ): StepResult {
        val results = executeBatch(listOf(action), description, canCancelPrevious)
        return results.firstOrNull() ?: StepResult(
            step = 0, actionType = action.typeName, thought = action.thought,
            success = false, detail = "操作被取消", durationMs = 0, completed = false
        )
    }
}
