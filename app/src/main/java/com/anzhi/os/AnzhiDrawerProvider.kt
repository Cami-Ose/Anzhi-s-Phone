package com.anzhi.os

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 安知智能抽屉 — App 分类与时间分组数据提供者。
 *
 * BUILD.md Step 12 + README §一（App 智能抽屉）：
 *   按时间段自动分组（白天 / 娱乐）。
 *   初版时间分组，V2 安知根据使用习惯建议。
 *
 * 分组规则：
 *   - 白天（6:00–18:00）：工具 / 社交 / 工作 / 学习类优先
 *   - 夜间（18:00–6:00）：娱乐 / 视频 / 游戏 / 阅读类优先
 *   - 「常用」组：最近使用过的 App（不受时间段影响，始终在前面）
 *
 * 分类依据：
 *   通过 PackageManager 获取 App 的 category 和包名关键词匹配。
 *   不需要 UsageStats 权限——仅用 PackageManager 查询已安装 App。
 *
 * 用法：
 *   val drawerProvider = AnzhiDrawerProvider(context)
 *   val json = drawerProvider.exportDrawerJson()
 *   // → JSON 供 dashboard.html 的 JS 解析渲染
 *
 * @param context Android Context
 */
class AnzhiDrawerProvider(private val context: Context) {

    companion object {
        private const val TAG = "AnzhiDrawerProvider"

        // ── 分类 key，对应时间段显示组 ──
        const val GROUP_FAVORITES = "favorites"   // 常用
        const val GROUP_TOOLS = "tools"           // 工具
        const val GROUP_SOCIAL = "social"         // 社交
        const val GROUP_WORK = "work"             // 工作/学习
        const val GROUP_ENTERTAINMENT = "entertainment"  // 娱乐
        const val GROUP_MEDIA = "media"           // 视频/音乐
        const val GROUP_GAMES = "games"           // 游戏
        const val GROUP_OTHER = "other"           // 其他

        // ── 包名关键词 → 分类映射 ──
        private val CATEGORY_RULES = listOf(
            // 工具
            CategoryRule(GROUP_TOOLS, listOf(
                "calculator", "calendar", "clock", "file", "settings",
                "camera", "torch", "flashlight", "note", "notes", "keep",
                "scan", "pdf", "printer", "vpn", "clean", "manager",
                "browser", "chrome", "firefox", "edge", "opera"
            )),
            // 社交
            CategoryRule(GROUP_SOCIAL, listOf(
                "wechat", "tencent.mm", "messenger", "whatsapp", "telegram",
                "signal", "discord", "slack", "line", "kakao", "viber",
                "qq", "weibo", "twitter", "xiaohongshu", "zhihu", "douban",
                "tieba", "facebook", "instagram", "snapchat", "reddit"
            )),
            // 工作/学习
            CategoryRule(GROUP_WORK, listOf(
                "mail", "gmail", "outlook", "docs", "sheets", "slides",
                "drive", "dropbox", "onedrive", "notion", "evernote",
                "todo", "task", "calendar", "meet", "zoom", "teams",
                "dictionary", "translate", "duolingo", "course", "learn",
                "wps", "office", "excel", "word", "ppt"
            )),
            // 视频/音乐
            CategoryRule(GROUP_MEDIA, listOf(
                "youtube", "bilibili", "tiktok", "douyin", "kuaishou",
                "netflix", "spotify", "music", "video", "player",
                "podcast", "radio", "tv", "movie", "anime", "iqiyi",
                "youku", "tencent.video", "qqmusic", "netease"
            )),
            // 游戏
            CategoryRule(GROUP_GAMES, listOf(
                "game", "genshin", "honkai", "pubg", "arena", "puzzle",
                "chess", "card", "moba", "rpg", "gaming", "arcade",
                "pokemon", "minecraft", "roblox", "candy", "clash"
            )),
            // 娱乐（阅读/购物/生活）
            CategoryRule(GROUP_ENTERTAINMENT, listOf(
                "shop", "taobao", "jingdong", "pinduoduo", "amazon",
                "alibaba", "ebay", "walmart", "food", "delivery",
                "meituan", "eleme", "didi", "uber", "travel", "hotel",
                "book", "read", "novel", "comic", "manga", "kindle",
                "news", "sport", "fitness", "health", "weather"
            ))
        )
    }

    // ── 内部缓存 ──

    private val packageManager = context.packageManager

    /** 已安装可启动 App 列表（不含系统 App） */
    private var installedApps: List<AppEntry>? = null

    /** 上次刷新时间 */
    private var lastRefreshTime: Long = 0L

    /** 缓存有效期 5 分钟 */
    private val cacheValidMs = 5 * 60 * 1000L

    // ═══════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════

    /**
     * 导出智能抽屉数据 JSON。
     *
     * 格式：
     * ```
     * {
     *   "time_period": "day" | "night",
     *   "groups": [
     *     { "key": "favorites", "label": "常用",
     *       "apps": [{ "packageName": "...", "name": "...", "initial": "微" }] },
     *     ...
     *   ]
     * }
     * ```
     *
     * 直接供 dashboard.html 的 JS 调用，无需二次处理。
     */
    fun exportDrawerJson(): String {
        val apps = getInstalledApps()
        val timePeriod = getCurrentTimePeriod()
        val groups = buildGroups(apps, timePeriod)

        val obj = JSONObject()
        obj.put("time_period", timePeriod)

        val groupsArr = JSONArray()
        for (group in groups) {
            val groupObj = JSONObject()
            groupObj.put("key", group.key)
            groupObj.put("label", group.label)

            val appsArr = JSONArray()
            for (app in group.apps) {
                appsArr.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("name", app.name)
                    put("initial", app.name.take(1))
                })
            }
            groupObj.put("apps", appsArr)
            groupsArr.put(groupObj)
        }
        obj.put("groups", groupsArr)

        return obj.toString()
    }

    /**
     * 强制刷新 App 列表（新 App 安装后调用）。
     */
    fun refresh() {
        installedApps = null
        lastRefreshTime = 0L
    }

    // ═══════════════════════════════════════
    // 内部：App 列表
    // ═══════════════════════════════════════

    /**
     * 获取已安装可启动 App 列表，5 分钟缓存。
     */
    private fun getInstalledApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        val cached = installedApps

        if (cached != null && (now - lastRefreshTime) < cacheValidMs) {
            return cached
        }

        val apps = mutableListOf<AppEntry>()

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    mainIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(
                        android.content.pm.PackageManager.MATCH_ALL.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(
                    mainIntent,
                    android.content.pm.PackageManager.MATCH_ALL
                )
            }

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(packageManager).toString()

                // 跳过安知手机 自身的 Activity
                if (pkg == context.packageName) continue

                // 跳过设置/系统 UI 类 App（用 flag 判断）
                val appInfo = ri.activityInfo.applicationInfo
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // 系统 App：只保留常用的（电话/短信/设置/相机/相册/浏览器）
                    if (!isEssentialSystemApp(pkg)) continue
                }

                apps.add(AppEntry(
                    packageName = pkg,
                    name = label,
                    category = classifyApp(pkg)
                ))
            }

            // 按名称排序
            apps.sortBy { it.name.lowercase() }

        } catch (e: Exception) {
            Log.e(TAG, "获取 App 列表异常: ${e.message}", e)
        }

        installedApps = apps
        lastRefreshTime = now

        Log.d(TAG, "已加载 ${apps.size} 个 App")
        return apps
    }

    /**
     * 判断系统 App 是否属于「必须保留」的。
     * 只保留用户日常真正会用的系统应用。
     */
    private fun isEssentialSystemApp(pkg: String): Boolean {
        val essential = listOf(
            "com.android.dialer",       // 电话
            "com.android.contacts",     // 联系人
            "com.android.mms",          // 短信
            "com.android.camera",       // 相机
            "com.android.gallery3d",    // 相册
            "com.android.settings",     // 设置
            "com.android.chrome",       // Chrome 浏览器
            "com.android.calendar",     // 日历
            "com.android.deskclock",    // 时钟
            "com.google.android.apps.maps",       // Google 地图
            "com.google.android.apps.photos",     // Google 相册
            "com.google.android.gm",              // Gmail
            "com.google.android.youtube",         // YouTube
            "com.google.android.apps.docs",       // Google Docs
            "com.google.android.keep"             // Google Keep
        )
        return pkg in essential
    }

    // ═══════════════════════════════════════
    // 内部：分类
    // ═══════════════════════════════════════

    /**
     * 根据包名关键词匹配分类。
     */
    private fun classifyApp(packageName: String): String {
        val lower = packageName.lowercase()
        for (rule in CATEGORY_RULES) {
            for (keyword in rule.keywords) {
                if (keyword in lower) return rule.category
            }
        }
        return GROUP_OTHER
    }

    // ═══════════════════════════════════════
    // 内部：分组
    // ═══════════════════════════════════════

    /**
     * 根据时间段构建显示分组。
     *
     * 白天（6:00-18:00）：工具 / 社交 / 工作 优先
     * 夜间（18:00-6:00）：娱乐 / 媒体 / 游戏 优先
     */
    private fun buildGroups(
        apps: List<AppEntry>,
        timePeriod: String
    ): List<GroupEntry> {
        val isDaytime = timePeriod == "day"

        // 按分类分组
        val byCategory: Map<String, List<AppEntry>> = apps.groupBy { it.category }

        val groups = mutableListOf<GroupEntry>()

        // 所有时间段都有的：其他（在前面当"全部应用"）
        val otherApps = byCategory[GROUP_OTHER] ?: emptyList()
        if (otherApps.isNotEmpty()) {
            groups.add(GroupEntry("other", "其他", otherApps))
        }

        if (isDaytime) {
            // 白天优先：工具 → 社交 → 工作
            addGroupIfNotEmpty(groups, "tools", "工具", byCategory)
            addGroupIfNotEmpty(groups, "social", "社交", byCategory)
            addGroupIfNotEmpty(groups, "work", "工作学习", byCategory)
            addGroupIfNotEmpty(groups, "media", "影音", byCategory)
            addGroupIfNotEmpty(groups, "entertainment", "娱乐生活", byCategory)
            addGroupIfNotEmpty(groups, "games", "游戏", byCategory)
        } else {
            // 夜间优先：娱乐 → 影音 → 游戏 → 社交
            addGroupIfNotEmpty(groups, "entertainment", "娱乐生活", byCategory)
            addGroupIfNotEmpty(groups, "media", "影音", byCategory)
            addGroupIfNotEmpty(groups, "games", "游戏", byCategory)
            addGroupIfNotEmpty(groups, "social", "社交", byCategory)
            addGroupIfNotEmpty(groups, "tools", "工具", byCategory)
            addGroupIfNotEmpty(groups, "work", "工作学习", byCategory)
        }

        return groups
    }

    private fun addGroupIfNotEmpty(
        groups: MutableList<GroupEntry>,
        key: String,
        label: String,
        byCategory: Map<String, List<AppEntry>>
    ) {
        val apps = byCategory[key]
        if (apps != null && apps.isNotEmpty()) {
            groups.add(GroupEntry(key, label, apps))
        }
    }

    /**
     * 获取当前时间段标识。
     * @return "day" (6:00–18:00) 或 "night" (18:00–6:00)
     */
    private fun getCurrentTimePeriod(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour in 6..17) "day" else "night"
    }

    // ═══════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════

    data class AppEntry(
        val packageName: String,
        val name: String,
        val category: String
    )

    data class GroupEntry(
        val key: String,
        val label: String,
        val apps: List<AppEntry>
    )

    data class CategoryRule(
        val category: String,
        val keywords: List<String>
    )
}
