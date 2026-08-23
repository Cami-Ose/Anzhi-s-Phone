package com.anzhi.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器。
 *
 * 接收 BOOT_COMPLETED 广播，拉起 AnzhiManagerService。
 * 这是 Android 系统中开机自启的标准方式——只有 <receiver> 能收到
 * BOOT_COMPLETED 广播，<service> 的 intent-filter 不生效。
 *
 * 对应 CODE_AUDIT.md #2 修复。
 */
class AnzhiBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AnzhiBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "收到 BOOT_COMPLETED，拉起 AnzhiManagerService")
            val serviceIntent = Intent(context, AnzhiManagerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
