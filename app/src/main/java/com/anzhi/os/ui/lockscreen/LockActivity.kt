package com.anzhi.os.ui.lockscreen

import androidx.activity.ComponentActivity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import kotlinx.coroutines.delay
import androidx.compose.runtime.*
import com.anzhi.os.AnzhiLockScreenBridge
import com.anzhi.os.chat.AnzhiChatActivity
import com.anzhi.os.dashboard.AnzhiDashboardActivity
import com.anzhi.os.ui.theme.AnzhiTheme

/**
 * 锁屏 Activity — 全屏覆盖，显示安知定制锁屏。
 *
 * 启动方式：由 DreamService、WakeLock 或 Keyguard 消失时触发。
 * 解锁后 → 启动 AnzhiDashboardActivity 并 finish 自身。
 *
 * 注意：此 Activity 是安知框架层锁屏，叠加在系统锁屏之上。
 * 实际的 Keyguard 锁定/解锁由 SystemUI 管理，安知锁屏只负责
 * 显示像素时间 + 粒子动画 + 安知消息。
 */
class LockActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockActivity"

        fun launch(context: Context) {
            val intent = Intent(context, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    // ── 桥接 ──

    private lateinit var lockScreenBridge: AnzhiLockScreenBridge

    // 电池状态
    private var batteryPercent by mutableStateOf(50)
    private var batteryCharging by mutableStateOf(false)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100 / scale)
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
        }
    }

    // ── 生命周期 ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "安知锁屏启动")

        // 亮屏第一眼就是这一屏：窗口盖在系统锁屏之上显示
        setShowWhenLocked(true)

        // 全屏覆盖（覆盖系统锁屏/梦境的显示区域）
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // 桥接
        lockScreenBridge = AnzhiLockScreenBridge.getInstance(this)

        // 注册电池广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Compose 内容
        setContent {
            AnzhiTheme {
                LockScreenContent(
                    lockScreenBridge = lockScreenBridge,
                    batteryPercent = batteryPercent,
                    batteryCharging = batteryCharging,
                    onUnlock = { unlock() }
                )
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 解锁 ──

    private fun unlock() {
        Log.i(TAG, "解锁 → 启动仪表盘")
        lockScreenBridge.clearPersistentMessage()
        // 系统锁屏还在底下，先收掉，免得仪表盘被它盖住
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .requestDismissKeyguard(this, null)
        AnzhiDashboardActivity.launch(this)
        finish()
    }
}

/**
 * 锁屏 Compose 内容 — 从桥接器读取实时状态。
 */
@Composable
private fun LockScreenContent(
    lockScreenBridge: AnzhiLockScreenBridge,
    batteryPercent: Int,
    batteryCharging: Boolean,
    onUnlock: () -> Unit
) {
    // 每 2 秒刷新锁屏文字状态
    var persistentMsg by remember { mutableStateOf(lockScreenBridge.getPersistentMessage()) }
    var transientMsg by remember { mutableStateOf(lockScreenBridge.getTransientIfActive()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000L)
            persistentMsg = lockScreenBridge.getPersistentMessage()
            transientMsg = lockScreenBridge.getTransientIfActive()
        }
    }

    LockScreen(
        persistentMessage = persistentMsg,
        transientMessage = transientMsg,
        batteryPercent = batteryPercent,
        batteryCharging = batteryCharging,
        onUnlock = onUnlock
    )
}
