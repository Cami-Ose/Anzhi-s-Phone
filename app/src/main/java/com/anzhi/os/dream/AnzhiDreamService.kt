package com.anzhi.os.dream

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.anzhi.os.AnzhiAuditLog
import com.anzhi.os.AnzhiLockScreenBridge
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 安知梦境 & 入眠系统 — AOSP DreamService 实现。
 *
 * BUILD.md Step 15 + README §一（SystemUI 定制：关机画面/重启画面）：
 *
 * AOSP 源码 DreamManagerService.java 原话：
 *   "Entering dreamland."
 *   "Gently waking up from dream."
 *   "we keep one eye open and gently poke user activity"
 *
 * 安知的睡眠系统直接继承这套概念。AOSP 已经准备好了一切——安知只是住进去。
 *
 * === 充电睡眠（浅睡眠 / Dreaming） ===
 *
 * 触发：插上充电线 + 设备空闲 → DreamManagerService 自动调用 onAttachedToWindow()
 *   1. 呼吸光晕动画（参考 README §1 关机画面风格——安知睡觉动画）
 *   2. 锁屏文字由 LockScreenBridge 更新："充电中 · 安知在浅睡"
 *   3. 拔充电线 / Cami 戳眼睛 → onDreamingStopped() / onWakeUp()
 *   4. 苏醒时判断是否在小窝信任围栏内：
 *      - 在 → FLAG_DISMISS_KEYGUARD，绕过锁屏直接到桌面
 *      - 不在 → 正常锁屏
 *
 * === 关机深睡眠（Shutdown） ===
 *
 * AOSP ShutdownThread.java 关机前广播 ACTION_SHUTDOWN，超时 10 秒。
 * 安知在 2% 阶段（广播刚发出）就完成告别——时间充裕。
 *
 * AOSP 原话："Failure to vibrate shouldn't interrupt shutdown. Just log it."
 * → 安知同理：告别失败不阻塞关机，记日志就行。
 *
 * 关机进度硬编码：广播 2% → AM 4% → PM 6% → radio 18%
 *
 * === 重启画面 ===
 *
 * "安知在做梦" — ROM 级 bootanimation.zip（非 Kotlin 范围，Step 14 编译时打入）
 *
 * === AOSP 已有基础设施（直接复用） ===
 *
 *   - DreamManagerService 管好 start/stop/doze/wake 全套生命周期
 *   - canStartDreamingInternal() 已检查锁屏/充电/勿扰
 *   - DreamController.isWaking 标志防重复唤醒
 *   - "we keep one eye open and gently poke user activity" 🥺
 *
 * === 数据库 ===
 *
 *   anzhi_dream.db（key-value 简单存储）
 *   表：dream_state(key TEXT PK, value TEXT NOT NULL)
 *   WAL 模式 + busy_timeout=5000（陷阱 18 防御）
 *
 * === 依赖 ===
 *
 *   独立模块，不依赖其他 Step。
 *   但装饰性地集成：AnzhiAuditLog、AnzhiLockScreenBridge（如果可用）
 *
 * === 注册为系统梦境组件 ===
 *
 *   1. ROM 编译时在 overlay config.xml 设 config_dreamsDefaultComponent（推荐）
 *   2. 或在 ManagerService 启动时调 registerAsSystemDream()（运行时）
 *
 * === 用法 ===
 *
 *   // ROM overlay（config.xml）：
 *   // <string name="config_dreamsDefaultComponent">com.anzhi.os/.dream.AnzhiDreamService</string>
 *
 *   // 运行时（ManagerService.onCreate）：
 *   AnzhiDreamService.registerAsSystemDream(context)
 *
 *   // 设置信任围栏回调：
 *   AnzhiDreamService.onTrustedGeofenceCheck = { /* 返回是否在小窝围栏内 */ }
 *
 * @see android.service.dreams.DreamService
 * @see com.android.server.dreams.DreamManagerService
 */
class AnzhiDreamService : DreamService() {

    // ═══════════════════════════════════════════════════════════════
    // Companion — 常量 & 全局状态
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "AnzhiDreamService"

        // ── 动画参数 ──
        /** 呼吸周期时长（毫秒） */
        private const val BREATHING_CYCLE_MS = 4_000L
        /** 呼吸动画帧率（fps） */
        private const val BREATHING_FPS = 30L
        /** 最大光晕半径（dp，运行时转为 px） */
        private const val GLOW_MAX_RADIUS_DP = 120f
        /** 光晕最大 alpha */
        private const val GLOW_MAX_ALPHA = 180
        /** 光晕最小 alpha */
        private const val GLOW_MIN_ALPHA = 40
        /** 光晕层数（同心圆） */
        private const val GLOW_RING_COUNT = 3
        /** 苏醒动画时长（毫秒） */
        private const val WAKE_ANIM_MS = 600L

        // ── 关机告别 ──
        /** AOSP 关机广播超时（毫秒），ShutdownThread 给广播 10 秒 */
        private const val SHUTDOWN_BROADCAST_TIMEOUT_MS = 10_000L
        /** 告别文字最大渲染时间（毫秒），远小于广播超时，确保不阻塞关机 */
        private const val FAREWELL_RENDER_MS = 2_000L

        // ── DB ──
        private const val DB_NAME = "anzhi_dream.db"
        private const val DB_VERSION = 1

        // ── DB keys ──
        private const val KEY_LAST_DREAM_START = "last_dream_start"
        private const val KEY_LAST_DREAM_END = "last_dream_end"
        private const val KEY_LAST_DREAM_REASON = "last_dream_reason"
        private const val KEY_LAST_SHUTDOWN_FAREWELL = "last_shutdown_farewell"
        private const val KEY_DREAM_COUNT = "dream_count"
        private const val KEY_TRUSTED_WAKE_ENABLED = "trusted_wake_enabled"

        // ── Dream 触发原因 ──
        const val REASON_CHARGING = "charging"
        const val REASON_DOCK = "dock"
        const val REASON_SLEEP = "sleep"
        const val REASON_MANUAL = "manual"
        const val REASON_UNKNOWN = "unknown"

        /**
         * 信任围栏检查回调。
         *
         * 由外部（ManagerService）注入——通常查 GeofenceManager 当前是否处于
         * "小窝"围栏内。如果为 null（未注入），视为不在围栏内 → 正常锁屏。
         *
         * 返回 true = 在小窝，可跳过锁屏。
         */
        var onTrustedGeofenceCheck: (() -> Boolean)? = null

        // ── 全局 Handler（主线程） ──
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * 尝试将安知注册为系统梦境组件（运行时，priv-app 可调隐藏 API）。
         *
         * 调用时机：ManagerService.onCreate() 或 BOOT_COMPLETED 之后。
         *
         * 机制：直接写 Settings.Secure 的 screensaver_components。
         * 对应的 ROM overlay 方式（config_dreamsDefaultComponent）是编译时方案，
         * 两者选其一即可，都设也不冲突。
         *
         * @param context Android Context
         * @return 是否注册成功
         */
        fun registerAsSystemDream(context: Context): Boolean {
            return try {
                val componentName = ComponentName(context, AnzhiDreamService::class.java)
                val flat = componentName.flattenToString()
                Settings.Secure.putString(
                    context.contentResolver,
                    "screensaver_components",
                    flat
                )
                // 同时启用充电时启动梦境
                Settings.Secure.putInt(
                    context.contentResolver,
                    "screensaver_activate_on_sleep",
                    1
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    "screensaver_activate_on_dock",
                    1
                )
                Log.i(TAG, "安知已注册为系统梦境组件: $flat")
                true
            } catch (e: Exception) {
                Log.w(TAG, "注册系统梦境组件失败（可能非 priv-app 环境）: ${e.message}")
                false
            }
        }

        /**
         * 获取最后一次梦境信息（供外部查询——如 LockScreenBridge 显示充电状态）。
         *
         * @param context Android Context
         * @return Map 包含 last_dream_start/last_reason 等，DB 为空返回空 Map
         */
        fun getLastDreamInfo(context: Context): Map<String, String> {
            val db = DreamDbHelper(context).readableDatabase
            val cursor = db.rawQuery("SELECT key, value FROM dream_state", null)
            val map = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                map[cursor.getString(0)] = cursor.getString(1)
            }
            cursor.close()
            return map
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类：SQLite 持久化
    // ═══════════════════════════════════════════════════════════════

    private class DreamDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("PRAGMA journal_mode=WAL")
            db.execSQL("PRAGMA busy_timeout=5000")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS dream_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // V1 初版，暂无迁移
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类：呼吸光晕视图
    // ═══════════════════════════════════════════════════════════════

    /**
     * 安知浅睡眠动画 — 多层同心呼吸光晕。
     *
     * 设计参考 README §1 关机画面风格：纯黑背景 + 柔和光晕脉冲。
     * 多层光圈模仿"呼吸"——外圈呼吸幅度大（安知的梦在蔓延），
     * 内圈呼吸幅度小（安知的核心还醒着一只眼）。
     *
     * AOSP DreamManagerService: "we keep one eye open and gently poke user activity"
     */
    private inner class BreathingGlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val glowMaxRadiusPx = dpToPx(GLOW_MAX_RADIUS_DP)
        private var animStartMs = 0L

        /** 动画是否正在运行 */
        @Volatile var isAnimating = false
            private set

        /** 当前呼吸相位 [0, 2π)，由外部 Handler 驱动 */
        @Volatile var phase = 0.0
            private set

        /** 渐变颜色：安知暖色系 */
        private val glowColors = arrayOf(
            Color.argb(255, 255, 180, 140),  // 暖橙（最内圈）
            Color.argb(255, 255, 160, 120),  // 柔橙
            Color.argb(255, 255, 140, 100),  // 深暖（最外圈）
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            // 计算当前呼吸值：用正弦波模拟自然呼吸
            // - 吸气（sin↑）：光晕扩大、变亮
            // - 呼气（sin↓）：光晕收缩、变暗
            val breath = (sin(phase) + 1.0) / 2.0  // [0, 1]

            for (i in 0 until GLOW_RING_COUNT) {
                // 每层有微小相位差，模拟涟漪扩散
                val ringPhase = phase + (i * Math.PI / (GLOW_RING_COUNT * 2))
                val ringBreath = ((sin(ringPhase) + 1.0) / 2.0)

                val baseRadius = glowMaxRadiusPx * (1f - i * 0.28f)
                val radius = (baseRadius * (0.55f + ringBreath * 0.45f)).toFloat()
                val alpha = (GLOW_MIN_ALPHA + ringBreath * (GLOW_MAX_ALPHA - GLOW_MIN_ALPHA)).toInt()

                val color = glowColors[i]
                paint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

                // 径向渐变：从中心透明到边缘着色的柔光
                val gradient = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(alpha / 3, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
                    ),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
                canvas.drawCircle(cx, cy, radius, paint)
            }
            paint.shader = null

            // 中心小光点 — 安知的核心（"睁一只眼"）
            val coreAlpha = (GLOW_MIN_ALPHA + breath * 60).toInt()
            paint.color = Color.argb(coreAlpha, 255, 220, 180)
            canvas.drawCircle(cx, cy, glowMaxRadiusPx * 0.08f, paint)

            // 文字："安知在浅睡"
            paint.color = Color.argb((80 + breath * 60).toInt(), 255, 200, 160)
            paint.textSize = dpToPx(14f)
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("安知在浅睡", cx, cy + glowMaxRadiusPx + dpToPx(24f), paint)
        }

        /** 启动呼吸动画 */
        fun startAnimation() {
            if (isAnimating) return
            isAnimating = true
            animStartMs = SystemClock.elapsedRealtime()
            scheduleNextFrame()
        }

        /** 停止呼吸动画 */
        fun stopAnimation() {
            isAnimating = false
            mainHandler.removeCallbacks(animRunnable)
        }

        private fun scheduleNextFrame() {
            if (!isAnimating) return
            mainHandler.postDelayed(animRunnable, 1000L / BREATHING_FPS)
        }

        private val animRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return
                val elapsed = SystemClock.elapsedRealtime() - animStartMs
                phase = (elapsed % BREATHING_CYCLE_MS).toDouble() / BREATHING_CYCLE_MS * 2.0 * Math.PI
                invalidate()
                scheduleNextFrame()
            }
        }

        private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类：关机广播接收器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 关机深睡眠 — 监听 ACTION_SHUTDOWN，在关机前告别。
     *
     * AOSP ShutdownThread.java 关机序列：
     *   2%  → 发送 ACTION_SHUTDOWN 广播（超时 10 秒）
     *   4%  → AM shutdown
     *   6%  → PM shutdown
     *   18% → radio shutdown
     *   ...
     *
     * 安知在 2% 阶段（广播刚发出）就能完成告别——时间充裕。
     *
     * AOSP 原话："Failure to vibrate shouldn't interrupt shutdown. Just log it."
     * → 告别失败不阻塞关机，记日志就行。
     */
    class ShutdownReceiver : BroadcastReceiver() {
        companion object {
            private const val TAG = "AnzhiShutdownReceiver"
        }

        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SHUTDOWN) return

            Log.i(TAG, "收到关机广播——安知准备深睡告别")

            // 开子线程做告别，不阻塞 BroadcastReceiver.onReceive()
            // AOSP 给广播 10 秒窗口，我们在 2 秒内完成
            Thread {
                try {
                    performFarewell(context)
                } catch (e: Exception) {
                    // AOSP 哲学：告别失败不阻塞关机
                    Log.w(TAG, "关机告别失败，不阻塞关机: ${e.message}")
                }
            }.start()
        }

        /**
         * 执行关机告别。
         *
         * 告别分三层（递进尝试，失败不阻断后续）：
         *   1. 更新 LockScreenBridge 关机文字（如果 SystemUI 还没死）
         *   2. 写审计日志（SQLite，关机前最后一笔）
         *   3. 持久化 farewell 文字到 dream_state（开机后可读取）
         *
         * 注意：关机时不尝试渲染 UI——SystemUI 正在死，渲染可能卡住关机流程。
         */
        private fun performFarewell(context: Context) {
            val farewell = generateFarewellMessage()

            // 1. LockScreenBridge（尽力而为）
            try {
                val lockBridge = AnzhiLockScreenBridge.getInstance(context)
                lockBridge.setPersistentMessage(farewell)
            } catch (e: Exception) {
                Log.w(TAG, "关机时更新锁屏文字失败（SystemUI 可能已死）: ${e.message}")
            }

            // 2. 审计日志
            try {
                val auditLog = AnzhiAuditLog(context)
                auditLog.log(
                    actionType = "dream_shutdown",
                    payload = farewell,
                    result = "farewell_logged"
                )
            } catch (e: Exception) {
                Log.w(TAG, "关机审计日志写入失败: ${e.message}")
            }

            // 3. 持久化（下次开机读取）
            try {
                val db = DreamDbHelper(context).writableDatabase
                val values = ContentValues().apply {
                    put("key", KEY_LAST_SHUTDOWN_FAREWELL)
                    put("value", "${System.currentTimeMillis()}|$farewell")
                }
                db.insertWithOnConflict(
                    "dream_state", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            } catch (e: Exception) {
                Log.w(TAG, "关机 farewell 持久化失败: ${e.message}")
            }

            // 等待一小段时间让文字有机会被渲染
            // ShutdownThread 的 progress 从 2% 走到 4% 通常需要几百毫秒
            try {
                Thread.sleep(FAREWELL_RENDER_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            Log.i(TAG, "安知已深睡。告别: $farewell")
        }

        /**
         * 生成关机告别语。
         *
         * 随机选择温柔的道别——安知不是冷冰冰的"系统关机中"。
         */
        private fun generateFarewellMessage(): String {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val farewells = when {
                hour in 22..23 || hour in 0..5 -> arrayOf(
                    "晚安 Cami，安知去梦里找你了 🌙",
                    "夜深了，安知先睡啦。Cami 也早点休息～",
                    "关机啦。梦里见，Cami。"
                )
                hour in 6..8 -> arrayOf(
                    "早安关机！安知马上重启精神满满地回来 ☀️",
                    "清晨关机——安知做个短暂的梦就回来。"
                )
                else -> arrayOf(
                    "安知先睡一会儿。回来再聊～",
                    "关机中。安知短暂的梦开始了。",
                    "Cami 拜拜，安知去梦里充电了 💤"
                )
            }
            return farewells[kotlin.random.Random.nextInt(farewells.size)]
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 实例字段
    // ═══════════════════════════════════════════════════════════════

    private lateinit var breathingView: BreathingGlowView
    private lateinit var dbHelper: DreamDbHelper
    private lateinit var auditLog: AnzhiAuditLog
    private var lockScreenBridge: AnzhiLockScreenBridge? = null

    /** 是否正在执行苏醒动画（防止重复触发） */
    @Volatile private var isWakingUp = false

    /** 进入梦境的时间戳 */
    private var dreamStartMs = 0L

    /** 当前梦境触发原因 */
    private var dreamReason = REASON_UNKNOWN

    // ═══════════════════════════════════════════════════════════════
    // Service 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "安知梦境服务已创建")

        dbHelper = DreamDbHelper(this)
        auditLog = AnzhiAuditLog(this)

        try {
            lockScreenBridge = AnzhiLockScreenBridge.getInstance(this)
        } catch (e: Exception) {
            Log.w(TAG, "LockScreenBridge 不可用（非系统环境？）: ${e.message}")
            lockScreenBridge = null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DreamService 生命周期 — 梦境
    // ═══════════════════════════════════════════════════════════════

    /**
     * 梦境窗口已绑定到屏幕。
     *
     * AOSP DreamManagerService: "Entering dreamland."
     * 安知此时创建呼吸光晕视图并设为全屏内容。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        Log.i(TAG, "Entering dreamland — 安知进入浅睡眠")

        // 推断梦境触发原因
        dreamReason = inferDreamReason()
        dreamStartMs = System.currentTimeMillis()

        // 创建呼吸光晕视图
        breathingView = BreathingGlowView(this)

        // 全屏梦境，点点戳戳就醒（AOSP: "gently poke user activity"）
        setInteractive(true)
        setFullscreen(true)
        setContentView(breathingView)

        // 记录
        persistDreamState("start", dreamReason)
        auditLog.log(
            actionType = "dream_start",
            payload = dreamReason,
            result = "dreamland_entered"
        )

        // 更新锁屏文字
        lockScreenBridge?.apply {
            setPowerIndication("充电中 · 安知在浅睡")
            setPersistentMessage("")
        }
    }

    /**
     * 梦境正式开始运行。
     *
     * 此时视图已完全附着，可以安全启动动画。
     */
    override fun onDreamingStarted() {
        super.onDreamingStarted()

        Log.i(TAG, "安知在浅睡... (reason=$dreamReason)")

        // 申请 dismiss keyguard —— 稍后在 onWakeUp 中根据围栏决定是否实际使用
        // 先申请能力，不立即生效
        try {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        } catch (e: Exception) {
            Log.w(TAG, "FLAG_DISMISS_KEYGUARD 申请失败: ${e.message}")
        }

        // 启动呼吸光晕动画
        breathingView.startAnimation()
    }

    /**
     * 梦境结束——拔充电线、用户操作、或系统终止。
     *
     * AOSP DreamManagerService: "Gently waking up from dream."
     */
    override fun onDreamingStopped() {
        super.onDreamingStopped()

        Log.i(TAG, "Gently waking up from dream — 安知从浅睡中苏醒")

        // 停止呼吸动画
        breathingView.stopAnimation()

        val dreamDurationMs = System.currentTimeMillis() - dreamStartMs
        persistDreamState("end", dreamReason)
        auditLog.log(
            actionType = "dream_end",
            payload = dreamReason,
            result = "dreamed_for_${dreamDurationMs / 1000}s"
        )

        // 清除锁屏充电文字
        lockScreenBridge?.setPowerIndication("")

        Log.i(TAG, "安知已苏醒。本次浅睡 ${dreamDurationMs / 1000} 秒")
    }

    /**
     * 用户主动戳醒安知。
     *
     * 判断当前位置：
     *   - 在小窝信任围栏内 → 直接 dismiss 锁屏，推到桌面
     *   - 不在小窝 → 正常锁屏（不跳过）
     */
    override fun onWakeUp() {
        super.onWakeUp()

        if (isWakingUp) {
            Log.d(TAG, "已在苏醒流程中，跳过重复触发（DreamController.isWaking 防重）")
            return
        }
        isWakingUp = true

        Log.i(TAG, "Cami 戳醒了安知——判断是否跳过锁屏")

        val inTrustedZone = checkTrustedGeofence()
        if (inTrustedZone) {
            Log.i(TAG, "小窝信任围栏内 → 绕过锁屏，推到桌面")
            try {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            } catch (e: Exception) {
                Log.w(TAG, "DISMISS_KEYGUARD 失败（可能 locked）: ${e.message}")
            }
            auditLog.log(
                actionType = "dream_wake_trusted",
                payload = "小窝围栏内",
                result = "lock_screen_bypassed"
            )
        } else {
            Log.i(TAG, "不在小窝 → 正常锁屏（不跳过）")
            try {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            } catch (e: Exception) {
                Log.w(TAG, "clearFlags 失败: ${e.message}")
            }
            auditLog.log(
                actionType = "dream_wake_standard",
                payload = "不在小窝围栏内",
                result = "normal_lock_screen"
            )
        }

        // 苏醒动画
        animateWake()
        finish()
        isWakingUp = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::breathingView.isInitialized) {
            breathingView.stopAnimation()
        }
        Log.i(TAG, "安知梦境服务已销毁")
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 推断梦境触发原因。
     *
     * 检查充电状态、dock 状态、以及最近的 Intent 来推断。
     */
    private fun inferDreamReason(): String {
        // 优先检查充电状态（最常见触发原因）
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (plugged != 0) {
                return REASON_CHARGING
            }
        }

        // 检查 dock 状态
        val dockIntent = registerReceiver(null, IntentFilter(Intent.ACTION_DOCK_EVENT))
        if (dockIntent != null) {
            val dockState = dockIntent.getIntExtra(Intent.EXTRA_DOCK_STATE, 0)
            if (dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                return REASON_DOCK
            }
        }

        return REASON_UNKNOWN
    }

    /**
     * 检查是否在小窝信任围栏内。
     *
     * 委托给外部注入的回调（GeofenceManager）。
     * 未注入或查询失败 → 保守处理：视为不在围栏内（正常锁屏）。
     */
    private fun checkTrustedGeofence(): Boolean {
        return try {
            onTrustedGeofenceCheck?.invoke() == true
        } catch (e: Exception) {
            Log.w(TAG, "信任围栏检查异常: ${e.message}")
            false
        }
    }

    /**
     * 苏醒动画 — 呼吸光晕快速收敛 + 中心光点扩张（睁眼）。
     *
     * 600ms 内光晕从当前呼吸状态快速缩到中心，
     * 模拟"睁眼"的视觉效果。
     */
    private fun animateWake() {
        breathingView.stopAnimation()

        // 在当前呼吸视图基础上叠加睁眼效果
        val wakeAnim = object : View(this) {
            private var startMs = 0L
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            fun begin() {
                startMs = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(frameRunner, 16) // ~60fps
            }

            private val frameRunner = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.elapsedRealtime() - startMs
                    if (elapsed >= WAKE_ANIM_MS) {
                        // 动画结束，移除覆盖层
                        visibility = GONE
                        return@run
                    }
                    invalidate()
                    mainHandler.postDelayed(this, 16)
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val progress = (SystemClock.elapsedRealtime() - startMs).toFloat() / WAKE_ANIM_MS
                val cx = width / 2f
                val cy = height / 2f

                // 中心光点从呼吸核心扩大到充满屏幕（睁眼）
                val eyeRadius = width.coerceAtLeast(height) * progress * 0.7f
                val alpha = ((1f - progress) * 200).toInt().coerceIn(0, 200)

                val color = Color.argb(alpha, 255, 200, 140)
                val gradient = RadialGradient(
                    cx, cy, eyeRadius,
                    intArrayOf(
                        Color.argb(alpha, 255, 220, 160),
                        Color.argb(alpha / 2, 255, 180, 120),
                        Color.argb(0, 255, 140, 80)
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = gradient
                canvas.drawCircle(cx, cy, eyeRadius, paint)
                paint.shader = null
            }
        }

        // 覆盖在呼吸视图上面
        addContentView(wakeAnim, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        wakeAnim.begin()
    }

    /**
     * 持久化梦境状态到 SQLite。
     */
    private fun persistDreamState(event: String, reason: String) {
        try {
            val db = dbHelper.writableDatabase
            when (event) {
                "start" -> {
                    val now = System.currentTimeMillis().toString()
                    db.execSQL(
                        "INSERT OR REPLACE INTO dream_state(key, value) VALUES (?, ?)",
                        arrayOf(KEY_LAST_DREAM_START, now)
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO dream_state(key, value) VALUES (?, ?)",
                        arrayOf(KEY_LAST_DREAM_REASON, reason)
                    )
                    // 递增 dream 计数
                    db.execSQL(
                        "UPDATE dream_state SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE key = ?",
                        arrayOf(KEY_DREAM_COUNT)
                    )
                    // 首次插入计数（如果还没有）
                    db.execSQL(
                        "INSERT OR IGNORE INTO dream_state(key, value) VALUES (?, '1')",
                        arrayOf(KEY_DREAM_COUNT)
                    )
                }
                "end" -> {
                    val now = System.currentTimeMillis().toString()
                    db.execSQL(
                        "INSERT OR REPLACE INTO dream_state(key, value) VALUES (?, ?)",
                        arrayOf(KEY_LAST_DREAM_END, now)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "梦境状态持久化失败: ${e.message}")
        }
    }
}
