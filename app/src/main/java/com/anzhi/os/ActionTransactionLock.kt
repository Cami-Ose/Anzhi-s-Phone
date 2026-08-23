package com.anzhi.os

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 全局动作事务锁 —— 防幽灵按键（陷阱 9）。
 *
 * 任何改变 UI 状态的操作（input tap/swipe/type、am start、input keyevent）
 * 必须经过此锁获取全局事务许可。不存在"快速旁路"或"紧急直通"（Invariant 2）。
 *
 * 设计：
 *   新 UI Session 启动 → InputInjector 置为 BUSY
 *   后续指令（纯指令或异步 DeepSeek 结果）→ FIFO 队列排队
 *   上一轮未完成异步任务可被 CANCEL
 *   队列为空时 → 自动回到 IDLE
 *
 * Step 7 定义接口和队列结构。
 * Step 9 接入 InputInjector 实际执行。
 *
 * 用法（Step 9 接入后）：
 *   ActionTransactionLock.submit(ActionTask(
 *       canCancelPrevious = true,
 *       description = "免打扰张三",
 *       action = { inputInjector.executeSteps(steps) }
 *   ))
 */
object ActionTransactionLock {
    private const val TAG = "ActionTransactionLock"

    // ── 状态枚举 ──

    /** 锁状态 */
    enum class State {
        /** 空闲——无任务在执行，队列为空 */
        IDLE,
        /** 忙碌——正在执行任务，新请求进 FIFO 队列 */
        BUSY
    }

    /** 取消原因 */
    enum class CancelReason {
        /** 被新任务打断（Cami 发了新指令） */
        PREEMPTED,
        /** 超时（单任务执行超 120 秒） */
        TIMEOUT,
        /** 执行异常 */
        ERROR,
        /** 主动取消 */
        MANUAL
    }

    // ── 数据类 ──

    /**
     * 一个动作事务。
     *
     * @param id           唯一标识（自动生成）
     * @param canCancelPrevious  新任务到达时是否可打断当前正在执行的任务
     * @param description  人类可读描述（用于日志和审计）
     * @param createdAt    创建时间戳
     * @param action       要执行的挂起函数（Step 9 接入 InputInjector）
     */
    data class ActionTask(
        val id: String = UUID.randomUUID().toString(),
        val canCancelPrevious: Boolean = true,
        val description: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val action: (suspend () -> Unit)? = null
    )

    /**
     * 锁状态变更事件（用于 UI 回调，如状态栏眼睛图标）。
     * Step 12 仪表盘可订阅此事件流。
     */
    data class StateChangeEvent(
        val from: State,
        val to: State,
        val activeTaskId: String?,
        val activeTaskDesc: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── 内部状态 ──

    /** 当前锁状态 */
    private val _state = AtomicReference(State.IDLE)
    val currentState: State get() = _state.get()

    /** 当前正在执行的任务 ID（null = 无） */
    private val _activeTaskId = AtomicReference<String?>(null)
    val activeTaskId: String? get() = _activeTaskId.get()

    /** 当前正在执行的任务描述 */
    private val _activeTaskDesc = AtomicReference<String?>(null)
    val activeTaskDesc: String? get() = _activeTaskDesc.get()

    /** 当前任务的 Job 引用（用于 cancel） */
    private var currentJob: Job? = null

    /** FIFO 任务队列 */
    private val queue = Channel<ActionTask>(Channel.UNLIMITED)

    /** 队列中待执行的任务数（含正在执行的） */
    private val pendingCounter = AtomicInteger(0)

    /** 协程 scope */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 处理器是否已启动（确保 processQueue 只启动一次） */
    @Volatile
    private var processorStarted = false

    /** 状态变更监听器 */
    private val listeners = mutableListOf<(StateChangeEvent) -> Unit>()

    // ── 公开接口 ──

    /** 是否空闲（可安全执行新操作） */
    fun isIdle(): Boolean = _state.get() == State.IDLE

    /** 是否忙碌 */
    fun isBusy(): Boolean = _state.get() == State.BUSY

    /**
     * 提交一个动作事务。
     *
     * - 如果当前无任务 → 立即执行
     * - 如果当前有任务 → 入 FIFO 队列
     * - 如果 [task.canCancelPrevious] = true → 打断当前任务再入队
     *
     * Cami 新指令通常 canCancelPrevious=true，系统自动任务通常 false。
     */
    fun submit(task: ActionTask) {
        Log.d(TAG, "提交任务: ${task.id.take(8)}... \"${task.description}\"" +
                " (可打断=${task.canCancelPrevious})")

        if (isBusy() && task.canCancelPrevious) {
            Log.i(TAG, "打断当前任务: ${_activeTaskDesc.get()} → 新任务: ${task.description}")
            cancelCurrent(CancelReason.PREEMPTED)
        }

        // 入队
        queue.trySend(task)
        pendingCounter.incrementAndGet()

        // 确保处理器在跑（首次调用时启动，后续调用为 no-op）
        startProcessor()
    }

    /**
     * 强行取消当前正在执行的任务。
     * @param reason 取消原因
     */
    fun cancelCurrent(reason: CancelReason = CancelReason.MANUAL) {
        val taskId = _activeTaskId.get() ?: return
        val taskDesc = _activeTaskDesc.get()

        Log.w(TAG, "取消任务: ${taskId.take(8)}... \"$taskDesc\" 原因=$reason")

        // 只 cancel Job，不直接改状态。
        // processQueue 的 finally 块会统一处理状态过渡，
        // 避免 cancelCurrent → IDLE 和 processQueue → BUSY 之间
        // 给 UI 监听器造成一瞬间的 IDLE 闪烁。
        currentJob?.cancel()
    }

    /**
     * 注册状态变更监听器（用于 UI 状态栏等）。
     */
    fun addStateListener(listener: (StateChangeEvent) -> Unit) {
        listeners.add(listener)
    }

    /** 移除监听器 */
    fun removeStateListener(listener: (StateChangeEvent) -> Unit) {
        listeners.remove(listener)
    }

    /** 获取当前队列中待执行的任务数（含正在执行的） */
    fun pendingCount(): Int = pendingCounter.get()

    /** 清空队列 + 取消当前任务（紧急停止，调试用） */
    fun emergencyStop(reason: String = "紧急停止") {
        Log.w(TAG, "⚠️ 紧急停止: $reason")
        cancelCurrent(CancelReason.MANUAL)

        // 清空队列里所有待处理任务
        var drained = 0
        while (true) {
            val task = queue.tryReceive()
            if (task.isSuccess) {
                Log.d(TAG, "丢弃排队任务: ${task.getOrNull()?.description}")
                drained++
            } else break
        }

        pendingCounter.addAndGet(-drained)
        setBusy(null, null)
    }

    // ─────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────

    /** 确保 processor 已启动（幂等） */
    @Synchronized
    private fun startProcessor() {
        if (processorStarted) return
        processorStarted = true
        scope.launch { processQueue() }
        Log.d(TAG, "队列处理器已启动")
    }

    /**
     * 队列处理循环。
     *
     * 使用 `for (task in queue)` 迭代 Channel：
     *   - Channel 有数据时立即取到
     *   - Channel 为空时挂起等待
     *   - 无竞态：不存在"检查为空 → 新任务到达 → 错误退出"的窗口
     */
    private suspend fun processQueue() {
        for (task in queue) {
            val prevState = _state.get()

            Log.d(TAG, "开始执行: ${task.id.take(8)}... \"${task.description}\"")
            setBusy(task.id, task.description)

            notifyListeners(StateChangeEvent(
                from = prevState,
                to = State.BUSY,
                activeTaskId = task.id,
                activeTaskDesc = task.description
            ))

            try {
                if (task.action == null) {
                    Log.w(TAG, "任务 action 为空: ${task.description}")
                    // 跳过后继续处理下一个任务
                    pendingCounter.decrementAndGet()
                    setBusy(null, null)
                    continue
                }

                // 带超时的执行（120 秒）
                val job = scope.launch {
                    task.action.invoke()
                }
                currentJob = job

                withTimeout(120_000) {
                    job.join()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "任务被取消: ${task.description}")
                // 不抛——取消是正常流程
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "任务超时: ${task.description}")
                currentJob?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "任务异常: ${task.description} — ${e.message}")
            } finally {
                currentJob = null
            }

            // 任务结束 → 过渡回 IDLE（下轮循环取到新任务时再切 BUSY）
            pendingCounter.decrementAndGet()
            setBusy(null, null)

            notifyListeners(StateChangeEvent(
                from = State.BUSY,
                to = State.IDLE,
                activeTaskId = null,
                activeTaskDesc = null
            ))
        }

        // Channel 已关闭才会走到这里（正常不应发生）
        Log.w(TAG, "队列 Channel 已关闭，处理器退出")
    }

    private fun setBusy(taskId: String?, taskDesc: String?) {
        _activeTaskId.set(taskId)
        _activeTaskDesc.set(taskDesc)
        _state.set(if (taskId != null) State.BUSY else State.IDLE)
    }

    private fun notifyListeners(event: StateChangeEvent) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                Log.w(TAG, "状态监听器异常: ${e.message}")
            }
        }
    }
}
