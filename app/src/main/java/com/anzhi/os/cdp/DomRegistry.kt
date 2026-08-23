package com.anzhi.os.cdp

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * DOM 选择器注册表——集中管理所有 Gemini 页面 CSS 选择器。
 *
 * BUILD.md Step 3 — DomRegistry：
 *   - 所有 Gemini 页面的 CSS 选择器抽离到 assets/dom_selectors.json
 *   - 禁止在 CDP 逻辑代码里硬编码 document.querySelector
 *   - Google 前端 A/B Test 改 DOM → 只需更新 JSON，不动 Kotlin
 *   - 支持从手机本地文件热加载
 *
 * 用法：
 *   val registry = DomRegistry.load(context)
 *   val inputSelector = registry.getSelector("gemini.google.com", "inputArea")
 *   // → "div.ql-editor, [contenteditable=\"true\"], rich-textarea div[contenteditable]"
 */
class DomRegistry private constructor(
    private val selectors: JSONObject
) {
    companion object {
        private const val TAG = "DomRegistry"
        private const val ASSET_PATH = "dom_selectors.json"

        /**
         * 从 assets 加载选择器配置。
         * 如果 assets 中没有，返回空注册表（使用硬编码兜底）。
         */
        fun load(context: Context): DomRegistry {
            return try {
                val json = context.assets.open(ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
                DomRegistry(JSONObject(json))
            } catch (e: Exception) {
                Log.w(TAG, "无法加载 dom_selectors.json，使用空注册表: ${e.message}")
                DomRegistry(JSONObject())
            }
        }

        /**
         * 从本地文件热加载选择器配置（用于远程热更新）。
         */
        fun loadFromFile(filePath: String): DomRegistry {
            return try {
                val json = java.io.File(filePath).readText()
                DomRegistry(JSONObject(json))
            } catch (e: Exception) {
                Log.w(TAG, "无法从文件加载选择器: $filePath → ${e.message}")
                DomRegistry(JSONObject())
            }
        }
    }

    /**
     * 获取某个站点某个元素的选择器字符串。
     *
     * @param site 站点 key，如 "gemini.google.com"
     * @param element 元素 key，如 "inputArea"
     * @return CSS 选择器（优先 selector，回退 fallback），未找到返回 null
     */
    fun getSelector(site: String, element: String): String? {
        val siteObj = selectors.optJSONObject(site) ?: return null
        val elementObj = siteObj.optJSONObject(element) ?: return null
        return elementObj.optString("selector", null)
            ?: elementObj.optString("fallback", null)
    }

    /**
     * 获取选择器及其 fallback 的完整信息。
     * 用于构造 JS 代码：先试 selector，失败再试 fallback。
     */
    fun getSelectorPair(site: String, element: String): Pair<String?, String?> {
        val siteObj = selectors.optJSONObject(site) ?: return null to null
        val elementObj = siteObj.optJSONObject(element) ?: return null to null
        val primary = elementObj.optString("selector", "").ifBlank { null }
        val fallback = elementObj.optString("fallback", "").ifBlank { null }
        return primary to fallback
    }

    /**
     * 获取某个站点下所有已注册的元素名列表。
     */
    fun getElementNames(site: String): List<String> {
        val siteObj = selectors.optJSONObject(site) ?: return emptyList()
        return siteObj.keys().asSequence().toList()
    }

    /**
     * 列出所有已加载的站点 key。
     */
    fun getSites(): List<String> {
        val sites = mutableListOf<String>()
        val keys = selectors.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // 过滤掉元数据 key（以 _ 开头）和非站点 key
            if (!key.startsWith("_") && selectors.optJSONObject(key) != null) {
                sites.add(key)
            }
        }
        // 也加入 captcha_patterns 等非站点配置
        if (selectors.has("captcha_patterns")) sites.add("captcha_patterns")
        return sites
    }

    /**
     * 获取 CAPTCHA 检测的标题模式列表。
     */
    fun getCaptchaTitlePatterns(): List<String> {
        val captchaObj = selectors.optJSONObject("captcha_patterns") ?: return emptyList()
        val arr = captchaObj.optJSONArray("title_patterns") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * 获取 CAPTCHA 检测的 URL 模式列表。
     */
    fun getCaptchaUrlPatterns(): List<String> {
        val captchaObj = selectors.optJSONObject("captcha_patterns") ?: return emptyList()
        val arr = captchaObj.optJSONArray("url_patterns") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * 生成 JS 代码：用选择器安全地查找 DOM 元素。
     *
     * 生成类似：
     *   (function() {
     *     var el = document.querySelector("primary") || document.querySelector("fallback");
     *     return el ? el.innerText : null;
     *   })()
     */
    fun buildQueryJs(
        site: String,
        element: String,
        property: String = "innerText"
    ): String {
        val (primary, fallback) = getSelectorPair(site, element)
        if (primary == null && fallback == null) {
            Log.w(TAG, "未找到选择器: site=$site element=$element")
            return "null"
        }

        val checks = buildString {
            if (primary != null) {
                append("document.querySelector('${primary.escapeJs()}')")
            }
            if (fallback != null) {
                if (primary != null) append(" || ")
                append("document.querySelector('${fallback.escapeJs()}')")
            }
        }

        return "(function(){var el=$checks;return el?el.$property:null;})()"
    }

    /**
     * 生成完整的 DOM 查询 JS（支持选择器数组，按顺序尝试）。
     */
    fun buildQueryJsMulti(
        site: String,
        elements: List<String>,
        property: String = "innerText"
    ): String {
        val selectors = elements.mapNotNull { getSelector(site, it) }
        if (selectors.isEmpty()) return "null"

        val checks = selectors.joinToString(" || ") {
            "document.querySelector('${it.escapeJs()}')"
        }
        return "(function(){var el=$checks;return el?el.$property:null;})()"
    }

    /** 转义 JS 字符串中的单引号 */
    private fun String.escapeJs(): String = this.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
}
