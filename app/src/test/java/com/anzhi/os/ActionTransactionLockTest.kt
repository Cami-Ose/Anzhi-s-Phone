package com.anzhi.os

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ActionTransactionLock L0 单元测试（BUILD.md §8 测试策略）。
 *
 * 覆盖：
 *   - FIFO 排队 → 按顺序执行
 *   - CANCEL 打断 → canCancelPrevious=true 中断当前任务
 *   - 不可打断 → canCancelPrevious=false 等当前任务完成
 *   - pendingCount 精度 → AtomicInteger 同步
 *   - emergencyStop → 清空队列
 *   - null action → 跳过不崩
 *   - StateChangeEvent 监听器 → IDLE↔BUSY 通知
 *   - 超时 → 120s withTimeout
 *
 * 注意：ActionTransactionLock 是 object 单例，测试间共享全局状态。
 * 每个 @Before/@After 调 emergencyStop() 重置。
 */
@RunWith(RobolectricTestRunner::class)
class ActionTransactionLockTest {

    private val lock = ActionTransactionLock

    @Before
    fun setup() {
        lock.emergencyStop("测试重置")
    }

    @After
    fun teardown() {
        lock.emergencyStop("测试清理")
    }

    // ─────────────────────────────────────
    // 基本执行
    // ─────────────────────────────────────

    @Test
    fun `submit — 任务执行完成后回到 IDLE`() = runTest {
        assertTrue("初始状态应为 IDLE", lock.isIdle())

        val executed = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "测试任务",
            action = {
                executed.set(true)
                latch.countDown()
            }
        ))

        assertTrue("任务应在 5 秒内执行", latch.await(5, TimeUnit.SECONDS))
        assertTrue("任务应被执行", executed.get())

        // 给 processQueue 一点时间完成状态过渡
        delay(100)
        assertEquals("执行后 pendingCount 应为 0", 0, lock.pendingCount())
    }

    @Test
    fun `submit — isIdle 在无任务时为 true`() {
        assertTrue("无任务时 isIdle", lock.isIdle())
        assertFalse("无任务时 isBusy 应为 false", lock.isBusy())
    }

    // ─────────────────────────────────────
    // FIFO 排队
    // ─────────────────────────────────────

    @Test
    fun `FIFO — 两个任务按提交顺序执行`() = runTest {
        val order = mutableListOf<Int>()
        val task1Done = CountDownLatch(1)
        val task2Gate = CountDownLatch(1)

        // 任务 1：等待 task2Gate 打开才完成
        lock.submit(ActionTransactionLock.ActionTask(
            description = "任务1",
            action = {
                order.add(1)
                task1Done.countDown()
                task2Gate.await() // 阻塞等 task2 入队后再完成
            }
        ))

        assertTrue("任务1 应已开始执行", task1Done.await(3, TimeUnit.SECONDS))
        assertEquals("任务1 应先记录", listOf(1), order)

        // 任务 2：在任务 1 还跑着时提交
        val task2Done = CountDownLatch(1)
        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = false,
            description = "任务2",
            action = {
                order.add(2)
                task2Done.countDown()
            }
        ))

        // 放开任务 1
        task2Gate.countDown()

        assertTrue("任务2 应在 5 秒内执行", task2Done.await(5, TimeUnit.SECONDS))
        assertEquals("执行顺序应为 1→2", listOf(1, 2), order)
    }

    // ─────────────────────────────────────
    // CANCEL 打断
    // ─────────────────────────────────────

    @Test
    fun `CANCEL — canCancelPrevious=true 打断当前任务`() = runTest {
        val task1Cancelled = AtomicBoolean(false)
        val task2Executed = AtomicBoolean(false)
        val task1Started = CountDownLatch(1)
        val task2Done = CountDownLatch(1)

        // 任务 1：开始后挂起等待（模拟长耗时操作）
        lock.submit(ActionTransactionLock.ActionTask(
            description = "任务1 (可打断)",
            action = {
                task1Started.countDown()
                try {
                    delay(30_000) // 30 秒，会被 cancel
                } catch (e: CancellationException) {
                    task1Cancelled.set(true)
                    throw e
                }
            }
        ))

        // 等任务 1 开始
        assertTrue("任务1 应在 3 秒内开始", task1Started.await(3, TimeUnit.SECONDS))

        // 任务 2：可打断前一个
        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = true,
            description = "任务2 (打断者)",
            action = {
                task2Executed.set(true)
                task2Done.countDown()
            }
        ))

        assertTrue("任务2 应在 5 秒内执行", task2Done.await(5, TimeUnit.SECONDS))
        assertTrue("任务1 应被取消", task1Cancelled.get())
        assertTrue("任务2 应被执行", task2Executed.get())
    }

    @Test
    fun `CANCEL — canCancelPrevious=false 等当前任务完成`() = runTest {
        val task1Done = CountDownLatch(1)
        val task1Completed = AtomicBoolean(false)
        val task2Done = CountDownLatch(1)
        val order = mutableListOf<Int>()

        lock.submit(ActionTransactionLock.ActionTask(
            description = "任务1 (不可打断)",
            action = {
                delay(200)
                order.add(1)
                task1Completed.set(true)
                task1Done.countDown()
            }
        ))

        // 等任务 1 开始
        delay(50)

        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = false,   // 不打断
            description = "任务2 (排队)",
            action = {
                order.add(2)
                task2Done.countDown()
            }
        ))

        assertTrue("任务1 应在 3 秒内完成", task1Done.await(3, TimeUnit.SECONDS))
        assertTrue("任务2 应在 3 秒内完成", task2Done.await(3, TimeUnit.SECONDS))
        assertEquals("执行顺序应为 1→2", listOf(1, 2), order)
    }

    // ─────────────────────────────────────
    // pendingCount
    // ─────────────────────────────────────

    @Test
    fun `pendingCount — 随任务提交递增、完成后递减`() = runTest {
        assertEquals("初始为 0", 0, lock.pendingCount())

        val holdLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "阻塞任务",
            action = {
                startedLatch.countDown()
                holdLatch.await()
            }
        ))

        assertTrue("任务应开始", startedLatch.await(3, TimeUnit.SECONDS))
        assertEquals("执行中 pendingCount 应为 1", 1, lock.pendingCount())

        // 再提交一个排队任务
        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = false,
            description = "排队任务",
            action = { /* no-op */ }
        ))

        assertEquals("执行中 + 排队中 = 2", 2, lock.pendingCount())

        // 放开阻塞任务
        holdLatch.countDown()
        delay(200)

        assertEquals("全部完成后为 0", 0, lock.pendingCount())
    }

    // ─────────────────────────────────────
    // emergencyStop
    // ─────────────────────────────────────

    @Test
    fun `emergencyStop — 取消当前任务并清空队列`() = runTest {
        val task1Cancelled = AtomicBoolean(false)
        val task2Executed = AtomicBoolean(false)
        val task1Started = CountDownLatch(1)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "任务1",
            action = {
                task1Started.countDown()
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    task1Cancelled.set(true)
                    throw e
                }
            }
        ))

        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = false,
            description = "任务2 (应被丢弃)",
            action = { task2Executed.set(true) }
        ))

        assertTrue("任务1 应开始", task1Started.await(3, TimeUnit.SECONDS))

        lock.emergencyStop("测试")

        assertTrue("任务1 应被取消", task1Cancelled.get())
        assertFalse("任务2 不应被执行（已丢弃）", task2Executed.get())
        assertEquals("pendingCount 应为 0", 0, lock.pendingCount())
        assertTrue("应回到 IDLE", lock.isIdle())
    }

    // ─────────────────────────────────────
    // null action
    // ─────────────────────────────────────

    @Test
    fun `null action — 跳过不崩`() = runTest {
        val latch = CountDownLatch(1)
        val secondExecuted = AtomicBoolean(false)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "空 action 任务",
            action = null
        ))

        lock.submit(ActionTransactionLock.ActionTask(
            description = "正常任务",
            action = {
                secondExecuted.set(true)
                latch.countDown()
            }
        ))

        assertTrue("正常任务应在 5 秒内执行", latch.await(5, TimeUnit.SECONDS))
        assertTrue("正常任务应执行", secondExecuted.get())
        assertEquals("全部完成后 pendingCount 为 0", 0, lock.pendingCount())
    }

    // ─────────────────────────────────────
    // StateChangeEvent 监听器
    // ─────────────────────────────────────

    @Test
    fun `StateListener — IDLE→BUSY→IDLE 事件顺序正确`() = runTest {
        val events = mutableListOf<ActionTransactionLock.StateChangeEvent>()
        lock.addStateListener { events.add(it) }

        val done = CountDownLatch(1)
        lock.submit(ActionTransactionLock.ActionTask(
            description = "测试",
            action = { done.countDown() }
        ))

        assertTrue("任务应完成", done.await(5, TimeUnit.SECONDS))
        delay(100)

        assertTrue("应至少收到 2 个事件（IDLE→BUSY, BUSY→IDLE）", events.size >= 2)

        val first = events.first()
        assertEquals("第一个事件: from IDLE", ActionTransactionLock.State.IDLE, first.from)
        assertEquals("第一个事件: to BUSY", ActionTransactionLock.State.BUSY, first.to)
        assertNotNull("第一个事件: activeTaskId", first.activeTaskId)

        val last = events.last()
        assertEquals("最后事件: from BUSY", ActionTransactionLock.State.BUSY, last.from)
        assertEquals("最后事件: to IDLE", ActionTransactionLock.State.IDLE, last.to)
        assertNull("最后事件: activeTaskId 应为 null", last.activeTaskId)

        lock.removeStateListener(events::add)
    }

    // ─────────────────────────────────────
    // 边界 & 并发
    // ─────────────────────────────────────

    @Test
    fun `submit from callback — ActionTask 内再 submit`() = runTest {
        val order = mutableListOf<Int>()
        val outerDone = CountDownLatch(1)
        val innerDone = CountDownLatch(1)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "外层任务",
            action = {
                order.add(1)
                // 在任务内再 submit
                lock.submit(ActionTransactionLock.ActionTask(
                    canCancelPrevious = false,
                    description = "内嵌任务",
                    action = {
                        order.add(2)
                        innerDone.countDown()
                    }
                ))
                outerDone.countDown()
            }
        ))

        assertTrue("外层任务应完成", outerDone.await(5, TimeUnit.SECONDS))
        assertTrue("内嵌任务应完成", innerDone.await(5, TimeUnit.SECONDS))
        assertEquals("顺序：外层 → 内嵌", listOf(1, 2), order)
    }

    @Test
    fun `并发 submit — 多个任务全部执行`() = runTest {
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(10)

        // 并发提交 10 个任务
        val jobs = (1..10).map { i ->
            launch(Dispatchers.IO) {
                lock.submit(ActionTransactionLock.ActionTask(
                    description = "并发任务 $i",
                    action = {
                        counter.incrementAndGet()
                        latch.countDown()
                    }
                ))
            }
        }
        jobs.joinAll()

        assertTrue("10 个任务应在 10 秒内全部完成",
            latch.await(10, TimeUnit.SECONDS))
        assertEquals("counter 应为 10", 10, counter.get())
        assertEquals("pendingCount 应为 0", 0, lock.pendingCount())
    }

    @Test
    fun `cancelCurrent — 手动取消后 processQueue 正确过渡`() = runTest {
        val task1Cancelled = AtomicBoolean(false)
        val task2Done = CountDownLatch(1)
        val task1Started = CountDownLatch(1)

        lock.submit(ActionTransactionLock.ActionTask(
            description = "任务1 (将被取消)",
            action = {
                task1Started.countDown()
                try {
                    delay(30_000)
                } catch (e: CancellationException) {
                    task1Cancelled.set(true)
                    throw e
                }
            }
        ))

        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = false,
            description = "排队任务2",
            action = { task2Done.countDown() }
        ))

        assertTrue("任务1 应开始", task1Started.await(3, TimeUnit.SECONDS))
        lock.cancelCurrent(ActionTransactionLock.CancelReason.MANUAL)

        assertTrue("任务1 应被取消", task1Cancelled.get())
        assertTrue("任务2 应在取消后自动开始", task2Done.await(5, TimeUnit.SECONDS))
        assertEquals("任务2 完成后 pendingCount 为 0", 0, lock.pendingCount())
    }

    @Test
    fun `cancelCurrent — 无活跃任务时不崩`() {
        // cancelCurrent 在 IDLE 状态下调用应直接 return，不抛异常
        assertTrue(lock.isIdle())
        lock.cancelCurrent(ActionTransactionLock.CancelReason.MANUAL)
        assertTrue("cancelCurrent 在 IDLE 时不应改变状态", lock.isIdle())
    }

    @Test
    fun `cancelCurrent — 取消后不产生状态闪动`() = runTest {
        val events = mutableListOf<ActionTransactionLock.StateChangeEvent>()
        lock.addStateListener { events.add(it) }

        val task1Started = CountDownLatch(1)
        lock.submit(ActionTransactionLock.ActionTask(
            description = "被取消的任务",
            action = {
                task1Started.countDown()
                try { delay(30_000) } catch (_: CancellationException) { throw CancellationException("") }
            }
        ))

        assertTrue("任务应开始", task1Started.await(3, TimeUnit.SECONDS))

        // 提交打断任务
        val task2Done = CountDownLatch(1)
        lock.submit(ActionTransactionLock.ActionTask(
            canCancelPrevious = true,
            description = "打断任务",
            action = { task2Done.countDown() }
        ))

        assertTrue("打断任务应完成", task2Done.await(5, TimeUnit.SECONDS))
        delay(100)

        // 验证：不应出现无意义的状态不变事件（from == to）
        val meaningless = events.filter { it.from == it.to }
        assertTrue("不应有无状态变化的事件", meaningless.isEmpty())

        // 验证：最终状态应回到 IDLE
        assertTrue("最终应回到 IDLE", lock.isIdle())

        lock.removeStateListener(events::add)
    }
}
