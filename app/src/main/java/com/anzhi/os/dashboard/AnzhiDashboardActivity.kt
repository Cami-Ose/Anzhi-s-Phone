package com.anzhi.os.dashboard

import androidx.activity.ComponentActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.anzhi.os.AnzhiAuditLog
import com.anzhi.os.AnzhiLockScreenBridge
import com.anzhi.os.AnzhiDrawerProvider
import com.anzhi.os.ui.components.TempCardData
import com.anzhi.os.ui.dashboard.*
import com.anzhi.os.ui.theme.AnzhiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Calendar

/**
 * 安知仪表盘 — 主界面 Activity（Compose 版）。
 *
 * 原 WebView 版 → 纯 Kotlin Compose 迁移。
 * 保持所有公开 API 不变：
 *   - onAnzhiSpeak()
 *   - addTempCard()
 *   - setGreetingOverride()
 *   - setWeatherData()
 *   - TempCard data class
 *
 * 数据流：
 *   系统回调 → MutableStateFlow<DashboardState> → Compose collectAsState() 自动重组
 *
 * 手势：
 *   下滑 → finish() 回到原生桌面
 *   上滑 → 控制面板
 *   左/右滑 → 抽屉/聊天（由 Compose 层处理）
 *   长按电源键 → 唤起聊天
 */
class AnzhiDashboardActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AnzhiDashboard"

        /** 启动仪表盘 Activity */
        fun launch(context: Context) {
            val intent = Intent(context, AnzhiDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    // ── 核心组件 ──

    private lateinit var lockScreenBridge: AnzhiLockScreenBridge
    private lateinit var drawerProvider: AnzhiDrawerProvider
    private lateinit var auditLog: AnzhiAuditLog
    private lateinit var deviceId: String

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── 统一状态 ──

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    // 手势检测：下滑 → 返回桌面
    private lateinit var gestureDetector: GestureDetector

    // ── 数据 ──

    /** 安知覆盖的问候文字（null = 用系统默认问候） */
    @Volatile
    private var anzhiGreetingOverride: String? = null

    /** 临时卡片列表 */
    private val tempCards = mutableListOf<TempCardData>()

    /** 仪表盘布局顺序（组件 ID 列表，按显示顺序） */
    private var layoutOrder: List<String> = listOf("greeting", "weather", "messages", "calendar", "todos", "tempcard")

    // ── 电池 ──

    private var batteryPercent = 50
    private var batteryCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100 / scale)
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
            updateSystemState()
        }
    }

    // ─────────────────────────────────────
    // Activity 生命周期
    // ─────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "安知仪表盘启动（Compose）")

        // 全屏 + 半透明状态栏
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 初始化基础设施
        initInfrastructure()

        // 初始化手势检测
        initGestureDetector()

        // 注册电池广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // ── Compose 内容 ──
        setContent {
            AnzhiTheme {
                DashboardScreen(
                    state = _state.collectAsState().value,
                    onTogglePanel = this::onTogglePanel,
                    onDismissTempCard = this::onDismissTempCard,
                    onLaunchApp = this::onLaunchApp,
                    onBrightnessChange = this::onBrightnessChange,
                    onVolumeChange = this::onVolumeChange,
                    onToggleWifi = this::onToggleWifi,
                    onToggleDnd = this::onToggleDnd
                )
            }
        }

        // 启动定时刷新
        startPeriodicRefresh()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardData()
    }

    override fun onDestroy() {
        Log.i(TAG, "仪表盘销毁")
        scope.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 触摸事件 ──

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureDetector.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            Log.i(TAG, "电源键长按 → 唤起聊天")
            openChat()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // ─────────────────────────────────────
    // 初始化
    // ─────────────────────────────────────

    private fun initInfrastructure() {
        deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""

        lockScreenBridge = AnzhiLockScreenBridge.getInstance(this)
        drawerProvider = AnzhiDrawerProvider(this)
        auditLog = AnzhiAuditLog(this)

        layoutOrder = loadLayoutPreference()
        auditLog.log("dashboard_start", "仪表盘启动（Compose）")

        // 初始推送
        refreshDashboardData()
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    val dy = e2.y - e1.y
                    val dx = e2.x - e1.x
                    if (dy > 200 && Math.abs(dy) > Math.abs(dx) * 1.5) {
                        Log.i(TAG, "下滑手势 → 返回桌面")
                        finish()
                        return true
                    }
                    // 上滑 → 控制面板
                    if (dy < -200 && Math.abs(dy) > Math.abs(dx) * 1.5) {
                        Log.i(TAG, "上滑手势 → 控制面板")
                        onTogglePanel(ActivePanel.CONTROL)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startPeriodicRefresh() {
        scope.launch {
            while (isActive) {
                delay(60_000L)
                refreshDashboardData()
            }
        }
    }

    // ═══════════════════════════════════════
    // 状态更新
    // ═══════════════════════════════════════

    /** 刷新全部仪表盘数据 → 更新 StateFlow */
    private fun refreshDashboardData() {
        val now = Calendar.getInstance()
        val time = String.format("%02d:%02d",
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE))
        val days = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val dayOfWeek = days[now.get(Calendar.DAY_OF_WEEK) - 1]
        val date = "${now.get(Calendar.YEAR)}年${now.get(Calendar.MONTH) + 1}月${now.get(Calendar.DAY_OF_MONTH)}日"

        _state.update { s ->
            s.copy(
                time = time,
                dayOfWeek = dayOfWeek,
                date = date,
                greetingOverride = anzhiGreetingOverride,
                batteryPercent = batteryPercent,
                batteryCharging = batteryCharging,
                tempCards = synchronized(tempCards) { tempCards.toList() },
                widgetOrder = layoutOrder
            )
        }
    }

    /** 更新系统状态（电池、WiFi 等不依赖数据刷新的字段） */
    private fun updateSystemState() {
        _state.update { s ->
            s.copy(
                batteryPercent = batteryPercent,
                batteryCharging = batteryCharging
            )
        }
    }

    // ═══════════════════════════════════════
    // 面板切换
    // ═══════════════════════════════════════

    private fun onTogglePanel(panel: ActivePanel) {
        _state.update { s ->
            s.copy(
                activePanel = if (s.activePanel == panel) ActivePanel.NONE else panel
            )
        }
    }

    // ═══════════════════════════════════════
    // 公开 API：外部推送（供 AnzhiManagerService 回调）
    // ═══════════════════════════════════════

    /**
     * 安知 speak action → 仪表盘消息卡片 + 锁屏文字。
     */
    fun onAnzhiSpeak(text: String, isEmergency: Boolean) {
        Log.i(TAG, "安知说话: ${text.take(100)}")

        // 1. 仪表盘消息卡片
        addTempCard(
            title = if (isEmergency) "安知（紧急）" else "安知",
            text = text,
            priority = if (isEmergency) "emergency" else "normal",
            autoDismiss = true
        )

        // 2. 锁屏持久文字
        lockScreenBridge.setPersistentMessage(text)

        // 3. 锁屏瞬态提示
        lockScreenBridge.showTransient(text)
    }

    /**
     * 添加临时卡片——突发事件/安知 speak/一次性提醒。
     */
    fun addTempCard(
        title: String,
        text: String,
        priority: String = "normal",
        autoDismiss: Boolean = true
    ) {
        val card = TempCardData(
            id = "card_${System.currentTimeMillis()}_${(1000..9999).random()}",
            title = title,
            text = text,
            priority = priority,
            autoDismiss = autoDismiss
        )

        synchronized(tempCards) {
            if (tempCards.size >= 10) {
                tempCards.removeAt(0)
            }
            tempCards.add(card)
        }

        // 更新状态
        _state.update { s ->
            s.copy(
                tempCards = synchronized(tempCards) { tempCards.toList() },
                tempCard = card  // 设为当前显示
            )
        }
    }

    /**
     * 安知覆写问候文字。
     */
    fun setGreetingOverride(text: String?) {
        anzhiGreetingOverride = text
        _state.update { s -> s.copy(greetingOverride = text) }
    }

    /** 设置天气数据 */
    fun setWeatherData(
        condition: String, temp: Int,
        high: Int, low: Int, icon: String
    ) {
        _state.update { s ->
            s.copy(
                weather = WeatherData(
                    condition = condition,
                    temp = temp,
                    high = high,
                    low = low,
                    icon = icon,
                    available = true
                )
            )
        }
    }

    /** 设置消息列表 */
    fun setMessages(messages: List<MessageItem>) {
        _state.update { s -> s.copy(messages = messages) }
    }

    /** 设置日程列表 */
    fun setCalendar(events: List<CalendarEvent>) {
        _state.update { s -> s.copy(calendar = events) }
    }

    /** 设置待办列表 */
    fun setTodos(todos: List<TodoItem>) {
        _state.update { s -> s.copy(todos = todos) }
    }

    /** 设置 WiFi 状态 */
    fun setWifiOn(on: Boolean) {
        _state.update { s -> s.copy(wifiOn = on) }
    }

    /** 设置 DND 状态 */
    fun setDndOn(on: Boolean) {
        _state.update { s -> s.copy(dndOn = on) }
    }

    /** 设置位置文字 */
    fun setLocation(text: String) {
        _state.update { s -> s.copy(location = text) }
    }

    // ═══════════════════════════════════════
    // 内部回调（Compose → Activity）
    // ═══════════════════════════════════════

    private fun onDismissTempCard() {
        synchronized(tempCards) {
            if (tempCards.isNotEmpty()) {
                tempCards.removeAt(tempCards.size - 1)  // 移除最新的
            }
        }
        _state.update { s ->
            s.copy(
                tempCards = synchronized(tempCards) { tempCards.toList() },
                tempCard = synchronized(tempCards) { tempCards.lastOrNull() }
            )
        }
    }

    private fun onLaunchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                Log.w(TAG, "无法打开 App: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开 App 异常: $packageName — ${e.message}")
        }
    }

    private fun onBrightnessChange(value: Int) {
        _state.update { s -> s.copy(brightness = value.coerceIn(0, 100)) }
        // TODO: 实际调节系统亮度（需 WRITE_SETTINGS 权限）
        try {
            val lp = window.attributes
            lp.screenBrightness = value / 100f
            window.attributes = lp
        } catch (_: Exception) {}
    }

    private fun onVolumeChange(value: Int) {
        _state.update { s -> s.copy(volume = value.coerceIn(0, 100)) }
        // TODO: 实际调节音量（通过 AudioManager）
    }

    private fun onToggleWifi(on: Boolean) {
        _state.update { s -> s.copy(wifiOn = on) }
        // TODO: 实际切换 WiFi（需 WIFI_CHANGE_STATE 权限）
    }

    private fun onToggleDnd(on: Boolean) {
        _state.update { s -> s.copy(dndOn = on) }
        // TODO: 实际切换勿扰模式（需 NotificationManager.setInterruptionFilter）
    }

    // ═══════════════════════════════════════
    // 布局偏好
    // ═══════════════════════════════════════

    private fun saveLayoutPreference(order: List<String>) {
        layoutOrder = order
        val timePeriod = getCurrentTimePeriod()
        val prefs = getSharedPreferences("anzhi_dashboard_prefs", MODE_PRIVATE)
        prefs.edit().putString("layout_order_$timePeriod",
            order.joinToString(",")).apply()
        Log.d(TAG, "布局偏好已保存: period=$timePeriod order=$order")
    }

    private fun loadLayoutPreference(): List<String> {
        val timePeriod = getCurrentTimePeriod()
        val prefs = getSharedPreferences("anzhi_dashboard_prefs", MODE_PRIVATE)
        val saved = prefs.getString("layout_order_$timePeriod", null)
        return if (saved != null) {
            saved.split(",").filter { it.isNotBlank() }
        } else {
            listOf("greeting", "weather", "messages", "calendar", "todos", "tempcard")
        }
    }

    private fun getCurrentTimePeriod(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 6..11  -> "morning"
            hour in 12..17 -> "afternoon"
            hour in 18..23 -> "evening"
            else           -> "night"
        }
    }

    // ═══════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════

    private fun openChat() {
        try {
            val intent = Intent().apply {
                setClassName(packageName,
                    "com.anzhi.os.chat.AnzhiChatActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法启动聊天: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    // 数据类（保持向后兼容）
    // ═══════════════════════════════════════

    /** 临时卡片（保持与旧版兼容） */
    data class TempCard(
        val id: String,
        val title: String,
        val text: String,
        val priority: String,
        val createdAt: Long,
        val autoDismiss: Boolean
    )
}
