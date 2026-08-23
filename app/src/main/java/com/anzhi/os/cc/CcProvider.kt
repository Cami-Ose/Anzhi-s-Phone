package com.anzhi.os.cc

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════
// 共享数据类型
// ═══════════════════════════════════════════

/** 模型返回的 tool_call */
data class CcToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

/** CC agent loop 执行结果 */
data class CcLoopResult(
    val success: Boolean,
    val rounds: Int,
    val filesProduced: List<CcFileOutput>,
    val finalMessage: String?,
    val errorReason: String?
)

/** CC 产出的文件摘要 */
data class CcFileOutput(
    val path: String,
    val content: String?
)

/** CC agent loop 进度回调。AnzhiManagerService 实现此接口以跟踪 CC 运行状态。 */
interface CcProgressCallback {
    fun onRoundStart(round: Int)
    fun onToolCall(tool: CcToolCall)
    fun onToolResult(toolName: String, success: Boolean, summary: String)
    fun onLoopEnd(result: CcLoopResult)
}

// ═══════════════════════════════════════════
// CcProvider 接口
// ═══════════════════════════════════════════

/**
 * CC (Code Creation) 供应者。
 *
 * 安知决定自己写代码时，由某个 CcProvider 执行 agent loop：
 *   模型写代码/跑命令 → CcExecutor 执行 → 结果喂回模型 → 循环直到 finish
 *
 * 当前实现：
 *   - GeminiCcProvider  — Gemini 3.5 Flash function calling，前端代码强
 *   - DeepSeekCcProvider — DeepSeek tool calling，分析/分类便宜
 */
interface CcProvider {
    /** 供应者名称（"Gemini 3.5 Flash" / "DeepSeek"） */
    val name: String

    /** 当前是否可用（未被熔断） */
    val available: Boolean

    /**
     * 执行 CC agent loop。
     *
     * @param systemPrompt 系统提示词（安知的规划指令，说明要做什么）
     * @param userTask 安知说的「我要写个东西」——具体任务描述
     * @param workspaceDir CC 工作目录
     * @param callback 进度回调，用于状态栏眼睛图标切换等
     * @return CcLoopResult — 成功/失败、轮次、产出文件、最终消息
     */
    suspend fun executeCcLoop(
        systemPrompt: String,
        userTask: String,
        workspaceDir: String,
        callback: CcProgressCallback? = null
    ): CcLoopResult
}

// ═══════════════════════════════════════════
// CcExecutor — 共享执行逻辑
// ═══════════════════════════════════════════

/**
 * CC 执行器。
 *
 * 和模型无关——不论用 DeepSeek 还是 Gemini，write_file / read_file / run_command
 * 的实现完全一样。每个 CcProvider 调用此执行器处理 tool_call。
 */
class CcExecutor(private val workspaceDir: String) {

    companion object {
        private const val TAG = "CcExecutor"

        // ── 命令超时 ──
        private const val CC_COMMAND_TIMEOUT_MS = 60_000L

        // ── run_command 禁止的 Android 系统命令前缀 ──
        // CC 边界仅限代码/编译/脚本/文件操作
        private val CC_BLOCKED_COMMANDS = setOf(
            "input", "am", "pm", "cmd", "settings", "svc",
            "dumpsys", "service", "content", "monkey", "uiautomator",
            "adb", "fastboot", "reboot", "setprop", "mount",
            "wm", "ime", "telecom", "phone", "media", "audio",
            "dpm", "bmgr", "bu", "appops", "device_config"
        )
    }

    /**
     * 执行 write_file tool。
     * 路径相对于 workspace，拒绝路径穿越。
     */
    fun executeWriteFile(path: String, content: String): Pair<Boolean, String> {
        val resolved = resolveWorkspacePath(path)
        if (resolved == null) {
            return Pair(false, "路径穿越拒绝: $path")
        }
        if (path.isBlank()) {
            return Pair(false, "path 不能为空")
        }
        return try {
            val file = File(resolved)
            file.parentFile?.mkdirs()
            file.writeText(content)
            val size = file.length()
            Log.i(TAG, "write_file: $path (${size} bytes)")
            Pair(true, "已写入 $path (${size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "write_file 失败: $path — ${e.message}", e)
            Pair(false, "写入失败: ${e.message}")
        }
    }

    /**
     * 执行 read_file tool。
     * 路径相对于 workspace，拒绝路径穿越。
     */
    fun executeReadFile(path: String): Pair<Boolean, String> {
        val resolved = resolveWorkspacePath(path)
        if (resolved == null) {
            return Pair(false, "路径穿越拒绝: $path")
        }
        if (path.isBlank()) {
            return Pair(false, "path 不能为空")
        }
        return try {
            val file = File(resolved)
            if (!file.exists()) {
                Pair(false, "文件不存在: $path")
            } else if (!file.canRead()) {
                Pair(false, "文件不可读: $path")
            } else {
                val text = file.readText()
                val limit = 50_000  // 单次最大 50KB
                if (text.length > limit) {
                    Pair(true, text.take(limit) + "\n…[截断: 全文 ${text.length} bytes]")
                } else {
                    Pair(true, text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "read_file 失败: $path — ${e.message}", e)
            Pair(false, "读取失败: ${e.message}")
        }
    }

    /**
     * 执行 run_command tool。
     * 安全检查：拒绝 Android 系统命令前缀。
     */
    fun executeRunCommand(cmd: String): Pair<Boolean, String> {
        if (cmd.isBlank()) {
            return Pair(false, "cmd 不能为空")
        }

        // 安全检查：拒绝 Android 系统命令
        val firstWord = cmd.trimStart().split(" ", "\t").firstOrNull()?.lowercase()?.trim() ?: ""
        if (firstWord in CC_BLOCKED_COMMANDS) {
            val msg = "已拒绝: '$firstWord' 是 Android 系统命令。CC run_command 仅限代码/编译/脚本/文件操作。"
            Log.w(TAG, "run_command 拦截: $cmd")
            return Pair(false, msg)
        }

        // 额外检查：禁止包含系统路径
        val dangerousPaths = listOf("/system/bin/", "/vendor/bin/", "/sbin/")
        for (dp in dangerousPaths) {
            if (cmd.contains(dp)) {
                val msg = "已拒绝: 命令中包含系统路径 '$dp'。CC 不能直接调用系统二进制。"
                Log.w(TAG, "run_command 拦截: $cmd")
                return Pair(false, msg)
            }
        }

        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", cmd),
                arrayOf(),
                File(workspaceDir)
            )

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            val finished = process.waitFor(CC_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                Pair(false, "命令超时 (${CC_COMMAND_TIMEOUT_MS / 1000}s): ${cmd.take(100)}")
            } else {
                val exitCode = process.exitValue()
                val builder = StringBuilder()
                if (stdout.isNotBlank()) builder.append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append("[stderr] ").append(stderr.trimEnd())
                }
                val output = builder.toString().let { if (it.isBlank()) "(无输出)" else it }
                val result = if (output.length > 10_000) {
                    output.take(10_000) + "\n…[截断]"
                } else output

                if (exitCode == 0) {
                    Log.i(TAG, "run_command 成功: ${cmd.take(80)}")
                    Pair(true, result)
                } else {
                    Log.w(TAG, "run_command 失败 (exit=$exitCode): ${cmd.take(80)}")
                    Pair(false, "exit=$exitCode\n$result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "run_command 异常: ${e.message}", e)
            Pair(false, "命令执行异常: ${e.message}")
        }
    }

    /** 解析工作路径，拒绝路径穿越 */
    private fun resolveWorkspacePath(path: String): String? {
        return try {
            val wsCanonical = File(workspaceDir).canonicalPath
            val resolved = File(workspaceDir, path).canonicalPath
            if (!resolved.startsWith(wsCanonical + File.separator) && resolved != wsCanonical) {
                Log.w(TAG, "路径穿越检测: workspace=$wsCanonical, requested=$path, resolved=$resolved")
                null
            } else {
                resolved
            }
        } catch (e: Exception) {
            Log.e(TAG, "路径解析失败: $path — ${e.message}")
            null
        }
    }
}
