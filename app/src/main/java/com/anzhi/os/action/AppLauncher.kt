package com.anzhi.os.action

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

/**
 * 启动应用结果。
 */
data class LaunchResult(val description: String, val packageName: String?)

/**
 * 应用启动器 —— 通过应用名启动应用。
 *
 * 从 OpenCyvis AppLauncher 搬运并简化（已移除 PrivilegeBackend 依赖）。
 * 支持：系统应用 Intent、包名别名、已安装应用标签搜索。
 */
class AppLauncher(
    private val context: Context,
    private val displayId: Int = 0
) {
    companion object {
        private const val TAG = "AppLauncher"

        val KNOWN_APP_PACKAGES: Map<String, String> = mapOf(
            "settings" to "com.android.settings", "设置" to "com.android.settings",
            "browser" to "com.android.browser", "浏览器" to "com.android.browser",
            "camera" to "com.android.camera", "相机" to "com.android.camera",
            "phone" to "com.android.dialer", "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts", "联系人" to "com.android.contacts",
            "messages" to "com.android.mms", "短信" to "com.android.mms",
        )

        val APP_INTENTS: Map<String, () -> Intent> = mapOf(
            "settings" to { Intent(Settings.ACTION_SETTINGS) },
            "设置" to { Intent(Settings.ACTION_SETTINGS) },
            "browser" to { Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")) },
            "camera" to { Intent(MediaStore.ACTION_IMAGE_CAPTURE) },
            "phone" to { Intent(Intent.ACTION_DIAL) },
            "contacts" to { Intent(Intent.ACTION_VIEW).apply { type = "vnd.android.cursor.dir/contact" } },
            "messages" to { Intent(Intent.ACTION_VIEW).apply { type = "vnd.android-dir/mms-sms" } },
        )

        /** 中文应用名 → 包名候选列表 */
        val APP_PACKAGE_ALIASES: Map<String, List<String>> = mapOf(
            "微信" to listOf("com.tencent.mm"), "wechat" to listOf("com.tencent.mm"),
            "支付宝" to listOf("com.eg.android.AlipayGphone"), "alipay" to listOf("com.eg.android.AlipayGphone"),
            "淘宝" to listOf("com.taobao.taobao"), "taobao" to listOf("com.taobao.taobao"),
            "京东" to listOf("com.jingdong.app.mall"), "jd" to listOf("com.jingdong.app.mall"),
            "美团" to listOf("com.sankuai.meituan"),
            "抖音" to listOf("com.ss.android.ugc.aweme"),
            "微博" to listOf("com.sina.weibo"),
            "百度" to listOf("com.baidu.searchbox"),
            "拼多多" to listOf("com.xunmeng.pinduoduo"),
        )

        fun launchFlagsForDisplay(displayId: Int): Int {
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (displayId != 0) flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            return flags
        }
    }

    private fun launchOptionsBundle() = if (displayId == 0) null
        else ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()

    fun launch(appName: String): LaunchResult {
        val key = appName.lowercase().trim()

        // 1. 已知 Intent
        APP_INTENTS[key]?.let { factory ->
            return try {
                val intent = factory().apply { addFlags(launchFlagsForDisplay(displayId)) }
                context.startActivity(intent, launchOptionsBundle())
                val pkg = intent.component?.packageName ?: intent.`package` ?: KNOWN_APP_PACKAGES[key]
                LaunchResult("打开 $appName", pkg)
            } catch (e: Exception) {
                Log.e(TAG, "启动 $appName 失败", e)
                LaunchResult("无法打开 $appName: ${e.message}", null)
            }
        }

        // 2. 包名别名
        for (pkg in APP_PACKAGE_ALIASES[key] ?: emptyList()) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                intent.addFlags(launchFlagsForDisplay(displayId))
                context.startActivity(intent, launchOptionsBundle())
                return LaunchResult("打开 $appName ($pkg)", pkg)
            } catch (e: Exception) { Log.e(TAG, "启动 $pkg 失败", e) }
        }

        // 3. 按应用名搜索
        return launchByPackageSearch(appName)
    }

    private fun launchByPackageSearch(appName: String): LaunchResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)

        // 精确匹配
        apps.firstOrNull { it.loadLabel(pm).toString().equals(appName, ignoreCase = true) }
            ?.let { return launchResolveInfo(it, appName, pm) }

        // 模糊匹配
        apps.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.contains(appName, ignoreCase = true) ||
                appName.contains(label, ignoreCase = true) ||
                it.activityInfo.packageName.contains(appName, ignoreCase = true)
        }?.let { return launchResolveInfo(it, appName, pm) }

        return LaunchResult("找不到应用 $appName。使用 list_apps 查看已安装应用。", null)
    }

    private fun launchResolveInfo(ri: android.content.pm.ResolveInfo, appName: String, pm: PackageManager): LaunchResult {
        val pkg = ri.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return LaunchResult("$appName 无启动入口", null)
        return try {
            intent.addFlags(launchFlagsForDisplay(displayId))
            context.startActivity(intent, launchOptionsBundle())
            LaunchResult("打开 $appName ($pkg)", pkg)
        } catch (e: Exception) {
            LaunchResult("无法打开 $appName: ${e.message}", null)
        }
    }

    fun listApps(keyword: String? = null): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.loadLabel(pm).toString() }
            .filter { keyword.isNullOrBlank() || it.contains(keyword, ignoreCase = true) }
            .distinct().sorted()
    }
}
