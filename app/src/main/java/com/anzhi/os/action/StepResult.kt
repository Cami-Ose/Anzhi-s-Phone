package com.anzhi.os.action

/**
 * 单步执行结果。
 *
 * 从 OpenCyvis StepResult 搬运。
 */
data class StepResult(
    val step: Int,
    val actionType: String,
    val thought: String,
    val success: Boolean,
    val detail: String,
    val durationMs: Long,
    val completed: Boolean,
    val debugInfo: String? = null,
    val suggestedRoutineName: String? = null,
    val suggestedRoutineIcon: String? = null
)
