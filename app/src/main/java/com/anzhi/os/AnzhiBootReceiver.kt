package com.anzhi.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.anzhi.os.ui.lockscreen.LockActivity

/**
 * 开机自启接收器。
 *
 * 接收 BOOT_COMPLETED 广播，拉起 AnzhiManagerService。
 * 这是 Android 系统中开机自启的标准方式——只有 <receiver> 能收到
 * BOOT_COMPLETED 广播，<service> 的 intent-filter 不生效。
 *
 * 首次开机还会把安知的四处系统槽位播种进 Settings，省去手动点选：
 *   1. 无障碍服务        —— Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
 *   2. 通知助理槽位      —— Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT
 *   3. 长按电源键的助理  —— Settings.Secure.ASSISTANT
 *   4. 长按电源键的行为  —— Settings.Global.POWER_BUTTON_LONG_PRESS
 *
 * 对应 CODE_AUDIT.md #2 修复。
 */
class AnzhiBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AnzhiBootReceiver"
        private const val ACCESSIBILITY_SERVICE = "com.anzhi.os/.AnzhiAccessibility"
        private const val NOTIFICATION_ASSISTANT = "com.anzhi.os/.AnzhiNotificationAssistant"
        private const val ASSISTANT_ACTIVITY = "com.anzhi.os/.chat.AnzhiChatActivity"
        private const val PREF_PROVISION = "anzhi_provision"
        private const val KEY_ACCESSIBILITY_SEEDED = "accessibility_seeded"
        private const val KEY_ASSISTANT_SEEDED = "assistant_seeded"

        // PhoneWindowManager.java:345  LONG_PRESS_POWER_ASSISTANT = 5（读 Settings.Secure.ASSISTANT）
        private const val LONG_PRESS_POWER_ASSISTANT = 5
        // PhoneWindowManager 的 power+音量上 组合键：2 = 电源菜单
        // （SettingsHelper.java:79 KEY_CHORD_POWER_VOLUME_UP_GLOBAL_ACTIONS）
        // AOSP 自己开启"长按电源=助理"时就把电源菜单挪到这条组合键上（SettingsHelper.java:568-572），
        // 我们照它做，长按电源不再是电源菜单的同时她仍有一条进得了重启/关机的路。
        private const val KEY_CHORD_GLOBAL_ACTIONS = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "收到 BOOT_COMPLETED，拉起 AnzhiManagerService")
            seedAccessibilityService(context)
            seedNotificationAssistant(context)
            seedAssistantSlot(context)
            seedPowerButtonLongPress(context)
            val serviceIntent = Intent(context, AnzhiManagerService::class.java)
            context.startForegroundService(serviceIntent)
            // 开机第一眼就是我们的锁屏（enzosphere 标识在这一屏）
            LockActivity.launch(context)
        }
    }

    /**
     * 只在首次开机播一次：之后 Cami 手动关掉无障碍，下次开机不会被我们擅自打开。
     * 通知助理那侧不用写代码，走 frameworks 资源覆盖 config_defaultAssistantAccessComponent。
     */
    private fun seedAccessibilityService(context: Context) {
        val prefs = context.getSharedPreferences(PREF_PROVISION, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACCESSIBILITY_SEEDED, false)) return
        try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (!current.contains(ACCESSIBILITY_SERVICE)) {
                val enabled = if (current.isEmpty()) {
                    ACCESSIBILITY_SERVICE
                } else {
                    "$current:$ACCESSIBILITY_SERVICE"
                }
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabled
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "已写入无障碍自启: $enabled")
            }
            prefs.edit().putBoolean(KEY_ACCESSIBILITY_SEEDED, true).apply()
        } catch (e: SecurityException) {
            Log.w(TAG, "无障碍自启写入被拒——检查 privapp allowlist 是否有 WRITE_SECURE_SETTINGS: ${e.message}")
        }
    }

    /**
     * 通知助理槽位播种。
     *
     * ROM 侧默认值（framework 资源 config_defaultAssistantAccessComponent，我们已经在
     * device/anzhi/bluejay/overlay 里覆盖了）只在**全新 /data** 上才会被采用：
     *   NotificationManagerService.java:1295 —— loadDefaultApprovedServices(USER_SYSTEM)
     *   只在 /data/system/notification_policy.xml 打开时抛 FileNotFoundException 的分支里调用；
     *   文件已存在（也就是她这种反复刷 system_ext、不清 data 的情况）就走 readPolicyXml，
     *   再按 ManagedServices.java:834 「defaultsSize == 0 才从 config 加载」——旧文件里
     *   已经有 android.ext.services，defaultsSize 非 0，config 默认值这一路整个被跳过。
     * 所以 directBootAware 修的是"新刷机/清数据后能不能自动进槽"，这条播种修的是
     * "已刷过多次的这台机器能不能进槽"。两条都要，缺一都验不通。
     *
     * 注意 ManagedServices 对助理只认一个：NotificationManagerService.java:12674
     * addApprovedList() 里 approvedArray.length > 1 时打一条
     * "More than one approved assistants" 然后只取 [0]。所以我们是**替换**
     * android.ext.services，不是排队等它让位——这也正是 README §三
     * "安知注册为 Notification Assistant（系统级正式角色）"的原意。
     */
    private fun seedNotificationAssistant(context: Context) {
        seedSecureOnce(
            context,
            "notification_assistant_seeded",
            Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT,
            NOTIFICATION_ASSISTANT,
            "通知助理"
        )
    }

    /**
     * 长按电源键 → 安知聊天（README §一 / §二 / §十一 的原始设计）里的**槽位**那半边。
     *
     * AssistUtils.java:287-295 原样读 Settings.Secure.ASSISTANT 的组件名。这个槽位会被
     * VoiceInteractionManagerService.java:2476-2508 在 ASSISTANT 角色没有持有者时擦成空串，
     * 所以真正让它长期有值的是 overlay 里的 config_defaultAssistant（角色默认持有者），
     * 这条播种只是给"已经跑过、槽位已经被擦空"的这台机补上第一次。
     */
    private fun seedAssistantSlot(context: Context) {
        seedSecureOnce(
            context,
            KEY_ASSISTANT_SEEDED,
            Settings.Secure.ASSISTANT,
            ASSISTANT_ACTIVITY,
            "助理槽位"
        )
    }

    /**
     * 长按电源键 → 安知聊天里的**手势路由**那半边（10-05 13:5x 才查出来的缺口）。
     *
     * 之前以为 overlay/框架资源 `config_longPressOnPowerBehavior` 是 5 就够了：那是
     * AOSP 基线值（framework-res.apk 里确实是 5），但 Lineage 在
     * `vendor/lineage/overlay/common/frameworks/base/core/res/res/values/config.xml:133`
     * 把它覆盖成 **1（电源菜单）**，编译进
     * `/product/overlay/framework-res__lineage_bluejay__auto_generated_rro_product.apk`
     * （android.auto_generated_rro_product__，`cmd overlay dump` 实测 mPriority=19），
     * 而我们的 overlay 落在 `/vendor/overlay/…auto_generated_rro_vendor.apk`，mPriority=2 ——
     * 同一个资源 id（0x010e00cf）两边都定义时高优先级赢，所以**我们写在 vendor 侧改不动它**。
     * 机上直接证据：`dumpsys window` → `mLongPressOnPowerBehavior=LONG_PRESS_POWER_GLOBAL_ACTIONS`。
     *
     * AOSP 给这条留的正路是 Global 覆盖（Settings.java:18988 注释
     * "Overrides internal R.integer.config_longPressOnPowerBehavior"，读取点
     * PhoneWindowManager.java:3478-3481，Global 值优先于 config）。所以播种 Global：
     * 只在键**不存在**时写，她之后在设置里自己改成别的值我们下次开机不再擅自改回来。
     */
    private fun seedPowerButtonLongPress(context: Context) {
        try {
            val resolver = context.contentResolver
            val current = Settings.Global.getInt(resolver, Settings.Global.POWER_BUTTON_LONG_PRESS, -1)
            if (current != -1) {
                Log.i(TAG, "长按电源行为已有值（$current），不覆盖")
                return
            }
            Settings.Global.putInt(
                resolver,
                Settings.Global.POWER_BUTTON_LONG_PRESS,
                LONG_PRESS_POWER_ASSISTANT
            )
            Settings.Global.putInt(
                resolver,
                Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                KEY_CHORD_GLOBAL_ACTIONS
            )
            Log.i(TAG, "已播种长按电源行为: 5=ASSISTANT，电源+音量上=2=电源菜单")
        } catch (e: SecurityException) {
            Log.w(TAG, "长按电源行为播种被拒——检查 WRITE_SECURE_SETTINGS: ${e.message}")
        }
    }

    /**
     * 播种 Settings.Secure 的通用件：只在第一次成功写入时置标记。
     * 之后她手动改掉，我们下次开机不再擅自改回来（与无障碍那条同一口径）。
     */
    private fun seedSecureOnce(
        context: Context,
        flagKey: String,
        settingName: String,
        value: String,
        label: String
    ) {
        val prefs = context.getSharedPreferences(PREF_PROVISION, Context.MODE_PRIVATE)
        if (prefs.getBoolean(flagKey, false)) return
        try {
            val current = Settings.Secure.getString(context.contentResolver, settingName)
            if (current.isNullOrEmpty()) {
                Settings.Secure.putString(context.contentResolver, settingName, value)
                Log.i(TAG, "已播种$label: $value")
            } else {
                Log.i(TAG, "$label 已有值（$current），不覆盖")
            }
            prefs.edit().putBoolean(flagKey, true).apply()
        } catch (e: SecurityException) {
            Log.w(TAG, "$label 播种被拒——检查 WRITE_SECURE_SETTINGS: ${e.message}")
        }
    }
}
