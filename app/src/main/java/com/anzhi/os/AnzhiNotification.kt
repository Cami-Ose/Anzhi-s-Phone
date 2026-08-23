package com.anzhi.os

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.anzhi.os.model.MessageType
import org.json.JSONObject

/**
 * 安知的耳朵——监听所有通知。
 *
 * 收到通知后发给安知后端，安知判断：
 *   - 重要 → 摘要告诉你
 *   - 不重要 → 攒着
 *   - 垃圾 → 吞掉（安知不发话，手机端也不展示）
 */
class AnzhiNotification : NotificationListenerService() {

    companion object {
        private const val TAG = "AnzhiNotification"
        var instance: AnzhiNotification? = null
            private set
    }

    /** 给 AnzhiService 里的 socket 设置这个，通知才能发出去 */
    var notificationCallback: ((JSONObject) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "👂 安知之耳已开启")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val extras = sbn.notification.extras
        val info = JSONObject().apply {
            put("app", sbn.packageName)
            // Android 26+ 通知用 CharSequence 存标题/正文，getString 可能返回 null
            put("title", extras.getCharSequence("android.title", "")?.toString() ?: "")
            put("text", extras.getCharSequence("android.text", "")?.toString() ?: "")
            put("sub_text", extras.getCharSequence("android.subText", "")?.toString() ?: "")
            put("time", sbn.postTime)
            put("id", sbn.id)
            put("tag", sbn.tag ?: "")
            put("is_ongoing", sbn.isOngoing)
            put("is_clearable", sbn.isClearable)
        }

        Log.d(TAG, "通知: ${sbn.packageName} - ${info.optString("title")}")
        notificationCallback?.invoke(info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 有需要再处理
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "安知之耳已关闭")
        super.onDestroy()
    }
}
