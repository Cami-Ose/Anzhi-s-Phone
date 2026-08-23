package com.anzhi.os

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 温度自适应降级阀门 —— 陷阱 17：Tensor G1 高温降频 → ANR 看门狗猎杀。
 *
 * Pixel 6a 的 Tensor G1 极易发热。夏天插充电线 + cpu_temp >46°C → 内核触发热降频 →
 * CPU 算力瞬间暴跌 → SQLite/UI 树 IO 延迟拖长 → 看门狗猎杀 AnzhiManagerService。
 *
 * 三级阀门：
 *   FULL (< 42°C)     — 全速：CC + 记忆库检索 + CDP WebView + 通知 + 心跳
 *   REDUCED (42-46°C) — 降级：暂停 CC + 记忆库向量检索，保留心跳 + 纯指令缓存 + 通知过滤
 *   MINIMAL (≥ 46°C)  — 仅心跳：暂停所有非紧急后台任务，仅心跳 + WebSocket 保活
 *
 * 充电状态 + 高温 → 优先停 CC，其次停 CDP WebView，绝不碰心跳和通知。
 *
 * 温度读数来源：
 *   1. status_update 的 cpu_temp 字段（主）
 *   2. /sys/class/thermal/thermal_zone_XX/temp 做冗余校验（备）
 *
 * 用法：
 *   val guard = ThermalGuard(scope)
 *   guard.onLevelChange { level -> when(level) { ... } }
 *   guard.start()
 *   // 定期调用 guard.updateTemp(cpuTemp) 或让 guard 自行轮询
 */
class ThermalGuard(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 30_000L  // 30 秒轮询一次 sysfs
) {
    companion object {
        private const val TAG = "ThermalGuard"

        /** 全速阈值：低于此温度为正常 */
        const val THRESHOLD_FULL_C = 42

        /** 降级阈值：达到此温度开始停重任务 */
        const val THRESHOLD_REDUCED_C = 42

        /** 最低阈值：达到此温度只保留心跳 */
        const val THRESHOLD_MINIMAL_C = 46

        /** sysfs 温度读取路径候选 */
        private val THERMAL_ZONE_CANDIDATES = listOf(
            "/sys/class/thermal/thermal_zone0/temp",   // 通常为 CPU
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
        )

        /** 温度文件名（部分设备用 type 文件标识 zone 类型） */
        private const val THERMAL_TYPE_FILE = "/sys/class/thermal/thermal_zone%d/type"
    }

    /** 温度等级 */
    enum class Level {
        /** < 42°C：全速运行 */
        FULL,
        /** 42-46°C：挂起 CC、记忆库向量检索等重任务，保留心跳 + 纯指令缓存 + 通知过滤 */
        REDUCED,
        /** ≥ 46°C：挂起所有非紧急后台任务，仅保留心跳 + WebSocket 保活 */
        MINIMAL
    }

    // ── 状态 ──

    private val _currentLevel = MutableStateFlow(Level.FULL)
    val currentLevel: StateFlow<Level> = _currentLevel.asStateFlow()

    private val _currentTemp = MutableStateFlow(0)
    val currentTemp: StateFlow<Int> = _currentTemp.asStateFlow()

    @Volatile
    private var running = false

    private var pollJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Level) -> Unit>()

    /** 上次从 status_update 收到的温度（优先使用） */
    @Volatile
    private var lastStatusUpdateTemp: Int? = null

    // ── 公开接口 ──

    /** 启动 sysfs 温度轮询（作为 status_update 的冗余备份） */
    fun start() {
        if (running) return
        running = true
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                val sysTemp = readSysfsTemp()
                if (sysTemp != null) {
                    evaluateLevel(sysTemp)
                }
                delay(pollIntervalMs)
            }
        }
        Log.i(TAG, "温度阀门已启动 (poll=${pollIntervalMs}ms)")
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        Log.i(TAG, "温度阀门已停止")
    }

    /**
     * 从 status_update 更新当前温度（优先数据源）。
     * 调用方（AnzhiManagerService）每次收到 status_update 时调用此方法。
     */
    fun updateTemp(cpuTemp: Int) {
        lastStatusUpdateTemp = cpuTemp
        _currentTemp.value = cpuTemp
        evaluateLevel(cpuTemp)
    }

    /** 注册温度等级变化回调 */
    fun onLevelChange(listener: (Level) -> Unit) {
        listeners.add(listener)
    }

    /** 移除回调 */
    fun removeLevelListener(listener: (Level) -> Unit) {
        listeners.remove(listener)
    }

    // ── 便捷查询 ──

    /** 当前是否允许 CC（双模型 code creation agent loop） */
    fun canRunCC(): Boolean = _currentLevel.value == Level.FULL

    /** 当前是否允许记忆库向量检索 */
    fun canRunMemorySearch(): Boolean = _currentLevel.value == Level.FULL

    /** 当前是否允许 CDP WebView 操作 */
    fun canRunCDP(): Boolean = _currentLevel.value != Level.MINIMAL

    /** 当前是否允许后台重任务 */
    fun canRunHeavyTask(): Boolean = _currentLevel.value == Level.FULL

    // ── 内部 ──

    private fun evaluateLevel(cpuTemp: Int) {
        val newLevel = when {
            cpuTemp >= THRESHOLD_MINIMAL_C -> Level.MINIMAL
            cpuTemp >= THRESHOLD_REDUCED_C -> Level.REDUCED
            else -> Level.FULL
        }

        val oldLevel = _currentLevel.value
        if (newLevel != oldLevel) {
            Log.w(TAG, "温度阀门切换: $oldLevel → $newLevel (cpu_temp=${cpuTemp}°C)")
            _currentLevel.value = newLevel

            // 通知所有监听器（在主线程）
            mainHandler.post {
                for (listener in listeners) {
                    try {
                        listener(newLevel)
                    } catch (e: Exception) {
                        Log.w(TAG, "温度监听器异常: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 从 sysfs 读取 CPU 温度（毫摄氏度 → 摄氏度）。
     * 作为 status_update 的冗余校验——如果 sysfs 温度比 status_update 高 5°C 以上，
     * 优先信任 sysfs（更接近硬件真实温度）。
     */
    private fun readSysfsTemp(): Int? {
        for (path in THERMAL_ZONE_CANDIDATES) {
            try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) continue
                val raw = file.readText().trim()
                val milliC = raw.toIntOrNull() ?: continue
                val celsius = milliC / 1000
                if (celsius in 10..120) {  // 合理范围
                    // 冗余校验：sysfs 和 status_update 差距 > 5°C 时用 sysfs
                    val statusTemp = lastStatusUpdateTemp
                    if (statusTemp != null && kotlin.math.abs(celsius - statusTemp) <= 5) {
                        return statusTemp  // 两者一致，用 status_update
                    }
                    return celsius
                }
            } catch (_: Exception) { continue }
        }
        return null
    }
}
