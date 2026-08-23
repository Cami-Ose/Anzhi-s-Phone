// IAnzhiService.aidl — 安知手机 系统级 Binder 接口
//
// 基于 AAOSP ILlmService 的 AIDL 设计模式，为安知提供：
//   - 对话提交（WebSocket → VPS，非本地推理）
//   - 会话历史查询
//   - 会话管理
//
// 调用方需要 android.permission.SUBMIT_ANZHI_REQUEST（signature|privileged）

package com.anzhi.os;

import android.os.Bundle;

interface IAnzhiService {
    /**
     * 提交一条消息给安知。
     * @param prompt  用户输入文本
     * @param options 可选参数（sessionId、maxToolCalls 等）
     * @return JSON 格式的响应：{"sessionId":"...","response":"...","status":"..."}
     */
    String submit(String prompt, in Bundle options);

    /**
     * 获取会话历史。
     * @param sessionId 会话 ID
     * @return JSON 数组：[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]
     */
    String getSessionHistory(String sessionId);

    /**
     * 取消当前正在执行的请求。
     */
    void cancel();

    /**
     * 结束并清理一个会话。
     * @param sessionId 会话 ID
     */
    void endSession(String sessionId);

    /**
     * 列出当前调用方的所有会话。
     * @param limit 最多返回几条
     * @return JSON 数组：[{"sessionId":"...","title":"...","createdAt":...,"messageCount":...}]
     */
    String listSessions(int limit);
}
