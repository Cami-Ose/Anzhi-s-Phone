package com.anzhi.os.cc

import android.util.Log

/**
 * DeepSeek CC 供应者。
 *
 * 适配器模式——把 DeepSeekClient 现有的 executeCcLoop 包装成 CcProvider 接口。
 * DeepSeek 的分析/分类/后端代码能力足够且便宜，适合不需要前端代码的场景。
 */
class DeepSeekCcProvider(
    private val deepSeekClient: com.anzhi.os.llm.DeepSeekClient
) : CcProvider {

    companion object {
        private const val TAG = "DeepSeekCcProvider"
    }

    override val name: String get() = "DeepSeek"

    override val available: Boolean
        get() = deepSeekClient.available

    override suspend fun executeCcLoop(
        systemPrompt: String,
        userTask: String,
        workspaceDir: String,
        callback: CcProgressCallback?
    ): CcLoopResult {
        Log.i(TAG, "DeepSeek CC 代理调用: workspace=$workspaceDir")
        return deepSeekClient.executeCcLoop(
            systemPrompt = systemPrompt,
            userTask = userTask,
            workspaceDir = workspaceDir,
            callback = callback
        )
    }
}
