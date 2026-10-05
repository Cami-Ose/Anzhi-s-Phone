package com.anzhi.os

import android.content.Context
import android.os.SystemProperties

/**
 * VPS 接入配置（网关地址 + 接口令牌）。
 *
 * 读取优先级：她在设置界面填的值（app 私有 SharedPreferences）> 系统属性 > 空。
 * 属性那条只是开发期 `adb shell setprop` 的临时后门——/system/build.prop 世界可读，
 * 任何 app `getprop` 都能拿走，所以令牌不烤进镜像。
 */
object VpsConfig {

    private const val PREFS = "anzhi_config"
    private const val KEY_API_URL = "vps_api_url"
    private const val KEY_API_TOKEN = "vps_api_token"

    /** 实际生效值：界面值优先，属性兜底 */
    fun apiUrl(context: Context): String =
        rawUrl(context).ifBlank { propUrl() }

    fun apiToken(context: Context): String =
        rawToken(context).ifBlank { propToken() }

    /** 界面里回填用的值（不含属性兜底），以及属性当前有没有值 */
    fun rawUrl(context: Context): String = pref(context, KEY_API_URL)

    fun rawToken(context: Context): String = pref(context, KEY_API_TOKEN)

    fun propUrl(): String = SystemProperties.get("persist.vendor.anzhi.api_url", "")

    fun propToken(): String = SystemProperties.get("persist.vendor.anzhi.token", "")

    fun save(context: Context, url: String, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_URL, url.trim().trimEnd('/'))
            .putString(KEY_API_TOKEN, token.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_API_URL)
            .remove(KEY_API_TOKEN)
            .apply()
    }

    private fun pref(context: Context, key: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "").orEmpty().trim()
}
