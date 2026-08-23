package com.anzhi.os

import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.os.Process
import android.os.SystemProperties
import android.os.UserHandle
import android.service.notification.Adjustment
import android.service.notification.NotificationAssistantService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.anzhi.os.llm.DeepSeekClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 安知通知全权接管 —— Android Notification Assistant（系统级正式角色）。
 *
 * BUILD.md Step 10 — 通知管理：
 *   - 不靠 NotificationListenerService。安知注册为 Android Notification Assistant，
 *     拥有通知全权控制（改重要度、排序、隐藏敏感内容、加操作按钮、预设回复）。
 *   - 三级过滤：硬规则（¥0）→ 缓存（¥0）→ DeepSeek（¥，结果存缓存）。
 *   - important → 仪表盘卡片 + 安知主动说。normal → 通知栏保留。spam → 设 IMPORTANCE_NONE。
 *
 * Notification Assistant 官方 API 能力（BUILD.md §五 Step 10）：
 *   | API key                | 安知用法 |
 *   | key_importance         | 改任意通知重要度。拼多多促销 → IMPORTANCE_NONE（吞掉） |
 *   | key_ranking_score      | 调整排序。安知仪表盘卡片排最上面 |
 *   | key_sensitive_content  | 隐藏锁屏敏感内容。银行验证码/私密消息不外泄 |
 *   | key_contextual_actions | 给通知加操作按钮。物流通知 → "帮我看" |
 *   | key_text_replies       | 预设智能回复。微信消息 → 安知帮回 |
 *
 * 判断流程（README.md §三）：
 *   硬规则（¥0，电话/短信→important，拼多多促销/淘宝广告→spam）
 *   → 缓存（¥0，App+类型永久有效，例"拼多多：物流通知"→normal）
 *   → DeepSeek（¥，结果存缓存）。
 *
 * 依赖：Step 4（DeepSeek API key，用于三级过滤中最贵的那一层）。
 *
 * ⚠️ Notification Assistant 权限必须在 ROM 编译时预埋（见 default-permissions XML）。
 *    即使 AndroidManifest 声明了 BIND_NOTIFICATION_ASSISTANT_SERVICE，系统仍静默拒绝——
 *    必须用户去"特殊应用权限"手动点选。Step 14 编译时通过 default-permissions 强制写入。
 */
class AnzhiNotificationAssistant : NotificationAssistantService() {

    companion object {
        private const val TAG = "AnzhiNotificationAst"

        /** 硬规则命中的来源标记 */
        private const val SOURCE_HARD_RULE = "hard_rule"
        /** 缓存命中的来源标记 */
        private const val SOURCE_CACHE = "cache"
        /** DeepSeek 分类的来源标记 */
        private const val SOURCE_DEEPSEEK = "deepseek"

        var instance: AnzhiNotificationAssistant? = null
            private set

        // ── 重要度常量 ──
        const val IMPORTANCE_IMPORTANT = "important"
        const val IMPORTANCE_NORMAL = "normal"
        const val IMPORTANCE_SPAM = "spam"
    }

    // ── 核心组件 ──

    private lateinit var cacheDb: NotificationCacheDb
    private lateinit var auditLog: AnzhiAuditLog
    private var deepSeekClient: DeepSeekClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ═══════════════════════════════════════════
    // 硬规则（¥0，无 API 成本）
    // ═══════════════════════════════════════════

    /**
     * 包名 → 重要度 硬映射。
     *
     * 规则：
     *   - 电话/短信/闹钟/日历 → important（Cami 需要看到）
     *   - 拼多多/淘宝/京东促销类 → spam（垃圾推广）
     *   - 其余包 → null（不在硬规则内，继续查缓存或 DeepSeek）
     *
     * 硬规则不区分通知类型——整包一视同仁。精细分类交给缓存和 DeepSeek。
     */
    private val hardRulePackages: Map<String, String> = mapOf(
        // ── important：Cami 需要看到 ──
        "com.android.dialer"             to IMPORTANCE_IMPORTANT,
        "com.google.android.dialer"      to IMPORTANCE_IMPORTANT,
        "com.android.incallui"           to IMPORTANCE_IMPORTANT,
        "com.android.mms"                to IMPORTANCE_IMPORTANT,
        "com.google.android.apps.messaging" to IMPORTANCE_IMPORTANT,
        "com.google.android.calendar"    to IMPORTANCE_IMPORTANT,
        "com.android.deskclock"          to IMPORTANCE_IMPORTANT,
        "com.google.android.deskclock"   to IMPORTANCE_IMPORTANT,
        "com.google.android.gm"          to IMPORTANCE_IMPORTANT,

        // ── spam：垃圾推广，直接吞掉 ──
        "com.xunmeng.pinduoduo"          to IMPORTANCE_SPAM,   // 拼多多
        "com.taobao.taobao"              to IMPORTANCE_SPAM,   // 淘宝
        "com.jingdong.app.mall"          to IMPORTANCE_SPAM,   // 京东
    )

    // ═══════════════════════════════════════════
    // Service 生命周期
    // ═══════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        instance = this
        cacheDb = NotificationCacheDb(this)
        auditLog = AnzhiAuditLog(this)

        // 延迟初始化 DeepSeekClient（需要 API Key 配置）
        // priv-app 可从系统属性读取（BUILD.md §四）
        val apiKey = getDeepSeekApiKey()
        if (apiKey != null) {
            try {
                deepSeekClient = DeepSeekClient(apiKey)
                Log.i(TAG, "DeepSeek 客户端就绪，通知分类可用")
            } catch (e: Exception) {
                Log.w(TAG, "DeepSeek 客户端初始化失败: ${e.message}")
            }
        } else {
            Log.w(TAG, "DeepSeek API Key 未配置，仅硬规则+缓存生效")
        }

        Log.i(TAG, "🔔 安知通知全权接管已开启（Notification Assistant）")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        Log.i(TAG, "安知通知全权接管已关闭")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════
    // 通知拦截入口
    // ═══════════════════════════════════════════

    /**
     * 通知入队时回调（API 33+，Android 13+）。
     *
     * 这是 NotificationAssistantService 的核心钩子——在通知**还没显示**之前拦截并调整。
     * 同步路径（硬规则 + 缓存命中）在这里直接 adjust，通知到达用户眼前时已被处理。
     *
     * 异步路径（缓存未命中 → DeepSeek 分类）在此处放过，后台分类完成后再 adjust。
     */
    override fun onNotificationEnqueued(sbn: StatusBarNotification): Adjustment? {
        try {
            val (importance, source) = classifySync(sbn, "enqueued")

            if (importance != null) {
                return buildAdjustment(sbn, importance, source)
            }
            // importance == null → 缓存未命中，走异步 DeepSeek 路径
            return null
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationEnqueued 异常: ${e.message}", e)
            return null
        }
    }

    /**
     * 通知已显示时回调（兜底）。
     *
     * 两种情况触发此路径：
     *   1. 某些系统通知绕过 onNotificationEnqueued 直接 posted
     *   2. 缓存未命中 → DeepSeek 异步分类完成 → 此处触发 adjust
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val (importance, source) = classifySync(sbn, "posted")

            if (importance != null) {
                // 已在 enqueued 阶段处理过的不重复处理
                applyManualAdjustment(sbn, importance, source)
            } else {
                // 缓存未命中 → 启动异步 DeepSeek 分类
                launchDeepSeekClassification(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted 异常: ${e.message}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 当前无需处理。若有需要（如统计被划掉的通知），可在此记录。
    }

    override fun onNotificationSnoozedUntilContext(
        sbn: StatusBarNotification,
        snoozeCriterionId: String
    ) {
        // 通知被延迟暂时不处理
    }

    // ═══════════════════════════════════════════
    // 三级过滤核心
    // ═══════════════════════════════════════════

    /**
     * 同步分类：硬规则 → 缓存。
     *
     * @return Pair(importance_level_or_null, source_tag)。
     *         importance 为 null 表示缓存未命中，需走 DeepSeek。
     */
    private fun classifySync(sbn: StatusBarNotification, phase: String): Pair<String?, String> {
        val pkg = sbn.packageName
        val typeHint = extractTypeHint(sbn)

        // ── 第 1 层：硬规则（¥0）──
        val hardResult = hardRulePackages[pkg]
        if (hardResult != null) {
            Log.d(TAG, "[$phase] 硬规则命中: $pkg → $hardResult")
            auditLog.log(
                actionType = "notification_hard_rule",
                payload = "$pkg|$typeHint",
                result = hardResult
            )
            return Pair(hardResult, SOURCE_HARD_RULE)
        }

        // ── 第 2 层：缓存（¥0）──
        val cached = cacheDb.lookup(pkg, typeHint)
        if (cached != null) {
            Log.d(TAG, "[$phase] 缓存命中: $pkg/$typeHint → $cached")
            return Pair(cached, SOURCE_CACHE)
        }

        // ── 缓存未命中 → 交给 DeepSeek（¥）──
        Log.d(TAG, "[$phase] 缓存未命中: $pkg/$typeHint → 待 DeepSeek 分类")
        return Pair(null, "")
    }

    /**
     * 异步 DeepSeek 分类（第 3 层，¥）。
     *
     * 在后台协程中调 DeepSeek API 判断通知重要度，结果写入缓存，
     * 然后补调 adjustNotification 修正通知栏中已显示的通知。
     */
    private fun launchDeepSeekClassification(sbn: StatusBarNotification) {
        val ds = deepSeekClient ?: return  // DeepSeek 不可用，跳过

        scope.launch {
            try {
                val pkg = sbn.packageName
                val typeHint = extractTypeHint(sbn)
                val title = extractNotificationText(sbn, Notification.EXTRA_TITLE)
                val text = extractNotificationText(sbn, Notification.EXTRA_TEXT)
                val subText = extractNotificationText(sbn, Notification.EXTRA_SUB_TEXT)

                Log.d(TAG, "DeepSeek 分类: $pkg | $typeHint | title=$title")

                val classification = classifyWithDeepSeek(ds, pkg, typeHint, title, text, subText)

                if (classification != null) {
                    // 写入缓存（永久有效，除非手动清）
                    cacheDb.store(pkg, typeHint, classification)

                    // 补调 adjust（通知已显示，修正之）
                    applyManualAdjustment(sbn, classification, SOURCE_DEEPSEEK)

                    auditLog.log(
                        actionType = "notification_deepseek",
                        payload = "$pkg|$typeHint|$title",
                        result = classification
                    )

                    Log.i(TAG, "DeepSeek 分类完成: $pkg/$typeHint → $classification")
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek 分类异常: ${e.message}", e)
            }
        }
    }

    /**
     * 调 DeepSeek API 做通知分类。
     *
     * 发给 DeepSeek 的 prompt：
     *   - 通知的 App、类型、标题、正文
     *   - DeepSeek 返回 JSON：{ "importance": "important" | "normal" | "spam", "reason": "…" }
     *
     * 注意：DeepSeek 只做分类，不生成对话。分类结果不直接暴露给 Cami（Invariant 7）。
     *
     * @return importance 标签，或 null（DeepSeek 不可用/解析失败）
     */
    private suspend fun classifyWithDeepSeek(
        ds: DeepSeekClient,
        pkg: String,
        typeHint: String,
        title: String,
        text: String,
        subText: String
    ): String? {
        val systemPrompt = buildString {
            append("你是一个通知过滤器。请将通知分类为 important、normal、或 spam。")
            append(" important：需要立即关注（支付/验证码/日程/消息/物流/安全警报）。")
            append(" normal：普通信息（社交动态/新闻/更新提醒/非紧急邮件）。")
            append(" spam：垃圾推广（促销广告/红包活动/直播通知/打卡提醒/签到奖励）。")
            append(" 只回复 JSON：{\"importance\": \"<分类>\", \"reason\": \"<简短原因>\"}")
        }

        val userMessage = buildString {
            appendLine("通知信息：")
            appendLine("App: $pkg")
            appendLine("类型: $typeHint")
            appendLine("标题: $title")
            appendLine("正文: $text")
            if (subText.isNotBlank()) appendLine("副标题: $subText")
        }

        val json = ds.chatJsonMode(systemPrompt, userMessage)
            ?: return null

        return try {
            val importance = json.optString("importance", "").lowercase()
            when (importance) {
                "important" -> IMPORTANCE_IMPORTANT
                "spam" -> IMPORTANCE_SPAM
                else -> IMPORTANCE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "DeepSeek 响应 JSON 解析失败: ${e.message} | raw=$json")
            null
        }
    }

    // ═══════════════════════════════════════════
    // 通知调整（adjustNotification）
    // ═══════════════════════════════════════════

    /**
     * 根据分类结果调整通知。
     *
     * important → IMPORTANCE_HIGH（通知栏置顶 + 仪表盘卡片）
     * normal    → IMPORTANCE_DEFAULT（通知栏保留）
     * spam      → IMPORTANCE_NONE（完全隐藏）
     *
     * 同时设置敏感内容标记（important 金融/验证码类通知在锁屏隐藏内容）。
     */
    /**
     * 对已显示的通知手动调整（onNotificationPosted / DeepSeek 异步回调）。
     * 通知已在栏中，只能用 adjustNotification()，不能 return Adjustment。
     */
    private fun applyManualAdjustment(sbn: StatusBarNotification, importanceLabel: String, source: String) {
        val adjustment = buildAdjustment(sbn, importanceLabel, source) ?: return
        try {
            adjustNotification(adjustment)

            if (importanceToAndroidLevel(importanceLabel) == NotificationManager.IMPORTANCE_NONE) {
                Log.i(TAG, "🗑️ 吞掉: ${sbn.packageName} — ${extractNotificationText(sbn, Notification.EXTRA_TITLE)}")
                try {
                    cancelNotification(sbn.key)
                } catch (_: SecurityException) {
                    // 部分系统版本不允许 NotificationAssistant 直接 cancel
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "adjustNotification 失败: ${e.message}", e)
        }
    }

    /** 构建 Adjustment 对象（不含 apply 操作），onNotificationEnqueued 直接 return */
    private fun buildAdjustment(sbn: StatusBarNotification, importanceLabel: String, source: String): Adjustment? {
        val importance = importanceToAndroidLevel(importanceLabel)
        val pkg = sbn.packageName

        // 前台 Service 通知不允许改重要度，跳过
        if (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
            return null
        }

        try {
            val signals = Bundle().apply {
                putInt(Adjustment.KEY_IMPORTANCE, importance)

                // 金融/验证码类 important 通知 → 锁屏隐藏敏感内容
                if (importanceLabel == IMPORTANCE_IMPORTANT && isSensitiveNotification(sbn)) {
                    putInt(Adjustment.KEY_SENSITIVE_CONTENT, 1)
                }
            }

            val explanation = "安知 · $source"

            val adjustment = Adjustment(pkg, sbn.key, signals, explanation, Process.myUserHandle())

            if (importance == NotificationManager.IMPORTANCE_NONE) {
                Log.i(TAG, "🗑️ 吞掉: $pkg — ${extractNotificationText(sbn, Notification.EXTRA_TITLE)}")
            }

            auditLog.log(
                actionType = "notification_adjust",
                payload = "$pkg → $importanceLabel",
                result = "source=$source"
            )

            return adjustment
        } catch (e: Exception) {
            Log.e(TAG, "构建 Adjustment 失败: ${e.message}", e)
            return null
        }
    }

    /** 将安知重要度标签映射为 Android NotificationManager.IMPORTANCE_* 常量 */
    private fun importanceToAndroidLevel(label: String): Int = when (label) {
        IMPORTANCE_IMPORTANT -> NotificationManager.IMPORTANCE_HIGH
        IMPORTANCE_SPAM     -> NotificationManager.IMPORTANCE_NONE
        else                -> NotificationManager.IMPORTANCE_DEFAULT
    }

    // ═══════════════════════════════════════════
    // 通知内容提取
    // ═══════════════════════════════════════════

    /** 提取通知文本字段 */
    private fun extractNotificationText(sbn: StatusBarNotification, key: String): String {
        return sbn.notification.extras.getCharSequence(key, "")?.toString() ?: ""
    }

    /**
     * 提取通知"类型提示"（type_hint），用作缓存键的一部分。
     *
     * 优先使用 Android 系统分类（Android 8+ 的 Notification.category），
     * 系统未设分类时从标题/正文关键词推断。
     *
     * 类型提示让缓存区分同一 App 的不同通知：
     *   例："拼多多：物流通知"→normal，"拼多多：促销广告"→spam
     */
    private fun extractTypeHint(sbn: StatusBarNotification): String {
        // 1. 优先用 Android 系统分类
        val category = sbn.notification.category
        if (!category.isNullOrEmpty() && category != "null") {
            return category  // "msg", "call", "promo", "email", "social", etc.
        }

        // 2. 从标题/正文关键词推断
        val title = extractNotificationText(sbn, Notification.EXTRA_TITLE)
        val text = extractNotificationText(sbn, Notification.EXTRA_TEXT)
        val combined = "$title $text"

        return when {
            // 验证码/安全类
            combined.contains("验证码") || combined.contains("校验码") ||
            combined.contains("Verification") || combined.contains("verification code") ||
            combined.contains("安全") || combined.contains("登录") -> "auth"

            // 支付/金融
            combined.contains("支付") || combined.contains("转账") || combined.contains("收款") ||
            combined.contains("付款") || combined.contains("到账") || combined.contains("余额") ||
            combined.contains("PayPal") || combined.contains("Alipay") -> "payment"

            // 物流/快递
            combined.contains("物流") || combined.contains("快递") || combined.contains("发货") ||
            combined.contains("配送") || combined.contains("签收") || combined.contains("包裹") ||
            combined.contains("订单") -> "logistics"

            // 促销/广告
            combined.contains("促销") || combined.contains("优惠") || combined.contains("折扣") ||
            combined.contains("红包") || combined.contains("满减") || combined.contains("秒杀") ||
            combined.contains("特价") || combined.contains("限时") || combined.contains("立减") ||
            combined.contains("大促") || combined.contains("双11") || combined.contains("618") ||
            combined.contains("SALE") || combined.contains("Sale") -> "promo"

            // 直播/短视频
            combined.contains("直播") || combined.contains("开播") || combined.contains("主播") -> "live"

            // 社交互动
            combined.contains("赞了") || combined.contains("评论了") || combined.contains("关注了") ||
            combined.contains("提到了你") || combined.contains("@你") || combined.contains("好友") -> "social"

            // 新闻/资讯
            combined.contains("新闻") || combined.contains("头条") || combined.contains("快讯") ||
            combined.contains("热点") || combined.contains("推送") -> "news"

            // 系统/服务
            combined.contains("更新") || combined.contains("升级") || combined.contains("备份") ||
            combined.contains("同步") || combined.contains("存储") -> "system"

            // 无特殊特征
            else -> "general"
        }
    }

    /**
     * 判断是否为敏感通知（金融/验证码/私密消息类）。
     * 这类通知在锁屏上应隐藏内容（KEY_SENSITIVE_CONTENT）。
     */
    private fun isSensitiveNotification(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification.category
        if (category == Notification.CATEGORY_MESSAGE ||
            category == Notification.CATEGORY_EMAIL) return true

        val typeHint = extractTypeHint(sbn)
        return typeHint == "auth" || typeHint == "payment"
    }

    // ─────────────────────────────────────────
    // API Key 获取
    // ─────────────────────────────────────────

    /**
     * 获取 DeepSeek API Key。
     *
     * priv-app 在自定义 ROM 中可读取系统属性（BUILD.md §四）：
     *   DEEPSEEK_API_KEY=sk-xxx
     *
     * 优先级：系统属性 → BuildConfig（未实现）→ null（降级：仅硬规则+缓存）
     */
    private fun getDeepSeekApiKey(): String? {
        // 1. 尝试读取 Android 系统属性（需 platform_apis）
        val key = SystemProperties.get("persist.anzhi.deepseek_api_key", "")
        if (key.isNotBlank()) return key

        // 2. 尝试从 SharedPreferences 读取（开发阶段手动配置）
        try {
            val prefs = getSharedPreferences("anzhi_config", Context.MODE_PRIVATE)
            val key = prefs.getString("deepseek_api_key", null)
            if (!key.isNullOrBlank()) return key
        } catch (_: Exception) {
            // 忽略
        }

        // 3. 无 API Key → DeepSeek 分类不可用，仅硬规则+缓存生效
        return null
    }

    // ─────────────────────────────────────────
    // 公开方法（供 AnzhiManagerService 查询/管理）
    // ─────────────────────────────────────────

    /**
     * 查询某个 App 的缓存分类。
     * 供仪表盘/设置界面展示通知规则。
     */
    fun getCachedRulesForPackage(pkg: String): List<Map<String, String>> {
        return cacheDb.getRulesForPackage(pkg)
    }

    /** 清除指定规则（Cami 手动覆盖通知分类后调用） */
    fun clearRule(pkg: String, typeHint: String) {
        cacheDb.deleteRule(pkg, typeHint)
    }

    /** 手动写入规则（Cami 在聊天中或卡片上调整通知分类后调用） */
    fun setRule(pkg: String, typeHint: String, importance: String) {
        cacheDb.store(pkg, typeHint, importance)
    }

}

// ═══════════════════════════════════════════════
// 通知缓存数据库（notification_cache.db）
// BUILD.md §3.4 定义的 schema
// ═══════════════════════════════════════════════

/**
 * 通知分类缓存 SQLite 数据库。
 *
 * Schema（BUILD.md §3.4）：
 *   CREATE TABLE notification_rules (
 *       id          INTEGER PRIMARY KEY AUTOINCREMENT,
 *       package     TEXT NOT NULL,
 *       type_hint   TEXT NOT NULL,
 *       importance  TEXT NOT NULL,
 *       source      TEXT DEFAULT 'deepseek',
 *       created_at  INTEGER NOT NULL
 *   );
 *   CREATE UNIQUE INDEX idx_notif_rule ON notification_rules(package, type_hint);
 *
 * 缓存永久有效。Cami 可通过聊天或卡片手动覆盖。
 * (package, type_hint) 唯一——同 App 同类型通知只存一条规则。
 */
private class NotificationCacheDb(context: Context) : SQLiteOpenHelper(
    context, "notification_cache.db", null, 1
) {
    companion object {
        private const val TAG = "NotificationCacheDb"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode=WAL")       // 陷阱 18：读写不互斥
        db.execSQL("PRAGMA busy_timeout=5000")

        db.execSQL("""
            CREATE TABLE notification_rules (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                package     TEXT NOT NULL,
                type_hint   TEXT NOT NULL,
                importance  TEXT NOT NULL,
                source      TEXT DEFAULT 'deepseek',
                created_at  INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE UNIQUE INDEX idx_notif_rule
            ON notification_rules(package, type_hint)
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 陷阱 22：用 ALTER TABLE 增量迁移，不删表重建
        if (oldVersion < 2) {
            // 预留：未来新增字段在此处 ALTER TABLE ADD COLUMN
        }
    }

    /**
     * 查缓存。
     * @return importance 标签（"important"/"normal"/"spam"），或 null（未命中）
     */
    fun lookup(pkg: String, typeHint: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT importance FROM notification_rules WHERE package=? AND type_hint=? LIMIT 1",
            arrayOf(pkg, typeHint)
        )
        val result = if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else null
        cursor.close()
        return result
    }

    /**
     * 写入缓存。已存在则更新（UPSERT）。
     */
    fun store(pkg: String, typeHint: String, importance: String, source: String = "deepseek") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("package", pkg)
            put("type_hint", typeHint)
            put("importance", importance)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "notification_rules", null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.d(TAG, "缓存写入: $pkg/$typeHint → $importance ($source)")
    }

    /** 查询某 App 的所有缓存规则 */
    fun getRulesForPackage(pkg: String): List<Map<String, String>> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT type_hint, importance, source, created_at FROM notification_rules WHERE package=? ORDER BY created_at DESC",
            arrayOf(pkg)
        )
        val rules = mutableListOf<Map<String, String>>()
        while (cursor.moveToNext()) {
            rules.add(mapOf(
                "type_hint" to cursor.getString(0),
                "importance" to cursor.getString(1),
                "source" to cursor.getString(2),
                "created_at" to cursor.getLong(3).toString()
            ))
        }
        cursor.close()
        return rules
    }

    /** 删除指定规则 */
    fun deleteRule(pkg: String, typeHint: String) {
        writableDatabase.delete(
            "notification_rules",
            "package=? AND type_hint=?",
            arrayOf(pkg, typeHint)
        )
    }
}
