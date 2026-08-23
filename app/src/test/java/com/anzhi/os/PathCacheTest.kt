package com.anzhi.os

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PathCache L0 单元测试（BUILD.md §8 测试策略）。
 *
 * 覆盖：
 *   - 缓存命中 / 未命中
 *   - 缓存存储 / 读取 / 失效
 *   - 失败账本计数器
 *   - 自动失效（≥3 次连续失败 → invalidatePath）
 *   - lookupSafe 失败账本守卫
 *   - 失败账本重置 / 清空
 *
 * 不依赖 Android 真机。Robolectric 在 JVM 上提供 SQLite 支持。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PathCacheTest {

    private lateinit var cache: PathCache

    // 测试常量
    private val testApp = "com.tencent.mm"
    private val testOp = "设免打扰"
    private val testKey = PathCache.buildKey(testApp, testOp)

    private val sampleSteps = listOf(
        PathCache.PathStep("tap", "聊天tab"),
        PathCache.PathStep("find_and_tap", "联系人"),
        PathCache.PathStep("tap", "设置"),
        PathCache.PathStep("tap", "免打扰")
    )

    @Before
    fun setup() {
        cache = PathCache(ApplicationProvider.getApplicationContext())
        cache.clearAll() // 确保每个测试干净起跑
    }

    @After
    fun teardown() {
        cache.clearAll()
        cache.close()
    }

    // ─────────────────────────────────────
    // 缓存命中 / 未命中
    // ─────────────────────────────────────

    @Test
    fun `缓存未命中 — lookup 返回 null`() {
        val result = cache.lookup("ui:com.unknown:不存在的操作")
        assertNull("不存在的 key 应返回 null", result)
    }

    @Test
    fun `缓存存储后命中 — lookup 返回完整步骤`() {
        cache.store(testKey, testApp, testOp, sampleSteps)

        val result = cache.lookup(testKey)
        assertNotNull("存储后应命中", result)
        assertEquals("步骤数应对齐", 4, result!!.size)
        assertEquals("第一步 type", "tap", result[0].type)
        assertEquals("第一步 hint", "聊天tab", result[0].hint)
        assertEquals("最后一步 type", "tap", result[3].type)
        assertEquals("最后一步 hint", "免打扰", result[3].hint)
    }

    @Test
    fun `命中后 hit_count 递增`() {
        cache.store(testKey, testApp, testOp, sampleSteps)

        // 命中 3 次
        repeat(3) { cache.lookup(testKey) }

        // store() 会把 fail_count 置 0，但验证 lookup 不崩即可。
        // hit_count 是内部计数器，通过 SQLite 直接校验。
        val db = cache.readableDatabase
        val cursor = db.rawQuery(
            "SELECT hit_count FROM path_cache WHERE key = ?", arrayOf(testKey)
        )
        cursor.use { c ->
            assertTrue("应有记录", c.moveToFirst())
            assertTrue("hit_count 应 ≥ 4（1 次 store + 3 次 lookup）",
                c.getInt(0) >= 4)
        }
    }

    // ─────────────────────────────────────
    // 路径失败 & 自动失效
    // ─────────────────────────────────────

    @Test
    fun `markPathFailed 递增 fail_count`() {
        cache.store(testKey, testApp, testOp, sampleSteps)

        val count1 = cache.markPathFailed(testKey)
        assertEquals("第一次失败", 1, count1)

        val count2 = cache.markPathFailed(testKey)
        assertEquals("第二次失败", 2, count2)

        assertEquals("getFailCount 应返回 2", 2, cache.getFailCount(testKey))
    }

    @Test
    fun `连续 3 次失败后自动失效 — lookup 返回 null`() {
        cache.store(testKey, testApp, testOp, sampleSteps)

        // 3 次连续失败 → 触发 FAILURE_LEDGER_THRESHOLD 自动 invalidatePath
        repeat(3) { cache.markPathFailed(testKey) }

        // 路径应已被删除
        val result = cache.lookup(testKey)
        assertNull("自动失效后 lookup 应返回 null", result)

        val failCount = cache.getFailCount(testKey)
        assertEquals("删除后 getFailCount 应返回 0", 0, failCount)
    }

    @Test
    fun `路径彻底失效 — invalidatePath 删除缓存`() {
        cache.store(testKey, testApp, testOp, sampleSteps)
        assertNotNull("存储后应命中", cache.lookup(testKey))

        cache.invalidatePath(testKey)
        assertNull("invalidatePath 后应返回 null", cache.lookup(testKey))
    }

    // ─────────────────────────────────────
    // 失败账本
    // ─────────────────────────────────────

    @Test
    fun `同一 App 连续 3 次失败 — 入失败账本`() {
        // 存两条不同路径
        val key1 = PathCache.buildKey(testApp, "设免打扰")
        val key2 = PathCache.buildKey(testApp, "回消息")
        cache.store(key1, testApp, "设免打扰", sampleSteps)
        cache.store(key2, testApp, "回消息", sampleSteps)

        assertFalse("初始不在失败账本", cache.isAppInFailureLedger(testApp))

        cache.markPathFailed(key1)
        assertFalse("1 次失败不应入账", cache.isAppInFailureLedger(testApp))

        cache.markPathFailed(key2)
        assertFalse("2 次失败不应入账", cache.isAppInFailureLedger(testApp))

        // 第 3 次失败 → 入账
        cache.markPathFailed(key1)
        assertTrue("3 次失败应入失败账本", cache.isAppInFailureLedger(testApp))

        val entries = cache.getFailureLedgerEntries()
        assertEquals("应有 1 条失败账本记录", 1, entries.size)
        assertEquals(testApp, entries[0].app)
        assertEquals(3, entries[0].consecutiveFailures)
        assertFalse("新入账不应被 acknowledged", entries[0].acknowledged)
    }

    @Test
    fun `失败后成功 — 失败账本自动重置`() {
        val key = PathCache.buildKey(testApp, "设免打扰")
        cache.store(key, testApp, "设免打扰", sampleSteps)

        // 制造 2 次失败
        repeat(2) { cache.markPathFailed(key) }
        assertEquals("2 次连续失败", 2, cache.getAppFailCount(testApp))

        // 成功一次 → 重置
        cache.resetAppFailures(testApp)
        assertEquals("重置后应为 0", 0, cache.getAppFailCount(testApp))
        assertFalse("不在失败账本", cache.isAppInFailureLedger(testApp))
    }

    @Test
    fun `store 成功后自动重置失败账本`() {
        val key = PathCache.buildKey(testApp, "设免打扰")
        cache.store(key, testApp, "设免打扰", sampleSteps)

        // 制造 2 次失败
        repeat(2) { cache.markPathFailed(key) }

        // 重新 store（路径重做成功） → 应自动 resetAppFailures
        cache.store(key, testApp, "设免打扰", sampleSteps)
        assertEquals("store 后应自动重置失败计数", 0, cache.getAppFailCount(testApp))
    }

    @Test
    fun `acknowledgeFailure 标记已告知`() {
        val key = PathCache.buildKey(testApp, "设免打扰")
        cache.store(key, testApp, "设免打扰", sampleSteps)
        repeat(3) { cache.markPathFailed(key) }

        cache.acknowledgeFailure(testApp)

        val entries = cache.getFailureLedgerEntries()
        assertEquals("仍应有记录", 1, entries.size)
        assertTrue("应标记为 acknowledged", entries[0].acknowledged)
    }

    @Test
    fun `clearFailureLedger 清空失败账本`() {
        val key = PathCache.buildKey(testApp, "设免打扰")
        cache.store(key, testApp, "设免打扰", sampleSteps)
        repeat(3) { cache.markPathFailed(key) }
        assertTrue("应在失败账本", cache.isAppInFailureLedger(testApp))

        cache.clearFailureLedger(testApp)
        assertFalse("清空后不在失败账本", cache.isAppInFailureLedger(testApp))
        assertEquals("清空后失败数为 0", 0, cache.getAppFailCount(testApp))
    }

    // ─────────────────────────────────────
    // lookupSafe — 失败账本守卫
    // ─────────────────────────────────────

    @Test
    fun `lookupSafe — App 不在失败账本时正常查找`() {
        cache.store(testKey, testApp, testOp, sampleSteps)
        val result = cache.lookupSafe(testKey, testApp)
        assertNotNull("未入账时 lookupSafe 应正常命中", result)
        assertEquals(4, result!!.size)
    }

    @Test
    fun `lookupSafe — App 在失败账本时直接返回 null`() {
        cache.store(testKey, testApp, testOp, sampleSteps)
        // 制造 3 次失败 → 入账
        repeat(3) { cache.markPathFailed(testKey) }
        assertTrue("应是入账状态", cache.isAppInFailureLedger(testApp))

        val result = cache.lookupSafe(testKey, testApp)
        assertNull("入账后 lookupSafe 应返回 null（不再盲目重试）", result)
    }

    @Test
    fun `lookupSafe — App 在失败账本、不同 key 也拦截`() {
        val key1 = PathCache.buildKey(testApp, "设免打扰")
        val key2 = PathCache.buildKey(testApp, "回消息")
        cache.store(key1, testApp, "设免打扰", sampleSteps)
        cache.store(key2, testApp, "回消息", sampleSteps)
        repeat(3) { cache.markPathFailed(key1) }

        // 同一 App 的不同操作也拦截
        val result = cache.lookupSafe(key2, testApp)
        assertNull("同一 App 下所有 key 都应被拦截", result)
    }

    // ─────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────

    @Test
    fun `buildKey 拼出正确格式`() {
        val key = PathCache.buildKey("com.tencent.mm", "设免打扰")
        assertEquals("ui:com.tencent.mm:设免打扰", key)
    }

    @Test
    fun `buildKey — 操作含特殊字符`() {
        val key = PathCache.buildKey("com.example", "打开\"设置\"页面")
        // 不应崩溃。key 中允许特殊字符（SQLite TEXT 主键）
        assertTrue(key.startsWith("ui:com.example:"))
    }

    @Test
    fun `getAppKeys 列出某 App 所有缓存 key`() {
        val key1 = PathCache.buildKey(testApp, "操作A")
        val key2 = PathCache.buildKey(testApp, "操作B")
        val keyOther = PathCache.buildKey("com.other.app", "其他操作")

        cache.store(key1, testApp, "操作A", sampleSteps)
        cache.store(key2, testApp, "操作B", sampleSteps)
        cache.store(keyOther, "com.other.app", "其他操作", sampleSteps)

        val appKeys = cache.getAppKeys(testApp)
        assertEquals("应有 2 条", 2, appKeys.size)
        assertTrue(appKeys.contains(key1))
        assertTrue(appKeys.contains(key2))
        assertFalse(appKeys.contains(keyOther))
    }

    @Test
    fun `count 返回缓存总数`() {
        assertEquals("初始为 0", 0, cache.count())

        cache.store(testKey, testApp, testOp, sampleSteps)
        assertEquals("存储 1 条后为 1", 1, cache.count())

        cache.store(PathCache.buildKey(testApp, "操作B"), testApp, "操作B", sampleSteps)
        assertEquals("存储 2 条后为 2", 2, cache.count())
    }

    @Test
    fun `clearAll 清空所有数据`() {
        cache.store(testKey, testApp, testOp, sampleSteps)
        repeat(3) { cache.markPathFailed(testKey) }
        assertTrue(cache.count() > 0 || cache.isAppInFailureLedger(testApp))

        cache.clearAll()
        assertEquals("清空后缓存数为 0", 0, cache.count())
        assertFalse("清空后不在失败账本", cache.isAppInFailureLedger(testApp))
    }

    // ─────────────────────────────────────
    // JSON 序列化
    // ─────────────────────────────────────

    @Test
    fun `PathStep toJson fromJson 往返一致性`() {
        val step = PathCache.PathStep("find_and_tap", "搜索按钮")
        val json = step.toJson()
        val restored = PathCache.PathStep.fromJson(json)

        assertEquals(step.type, restored.type)
        assertEquals(step.hint, restored.hint)
    }

    @Test
    fun `空步骤存储后取回`() {
        val emptySteps = emptyList<PathCache.PathStep>()
        cache.store(testKey, testApp, testOp, emptySteps)

        val result = cache.lookup(testKey)
        assertNotNull("空步骤列表也应命中", result)
        assertTrue("步骤列表应为空", result!!.isEmpty())
    }
}
