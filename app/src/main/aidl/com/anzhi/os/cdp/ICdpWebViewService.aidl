// ICdpWebViewService.aidl — CDP WebView 跨进程接口
//
// 运行在 :webview 独立沙盒进程。主进程通过 bindService 获取 Binder 代理后远程调用。
// WebView 内存泄漏/崩溃 → 只杀 :webview 进程，不碰核心服务和 WebSocket 连接。
//
// 三个窗口类型（对应 BUILD.md §2.2）：
//   wake    — 提醒窗口（OS 叫醒 → 安知决定 → 推卡片）
//   chat    — 主聊天窗口（Cami 长按电源键 → 聊天）
//   diary   — 日记窗口（凌晨 2:00 → 安知写日记）

package com.anzhi.os.cdp;

import com.anzhi.os.cdp.ICdpCallback;

interface ICdpWebViewService {

    // ── 窗口生命周期 ──

    /** 加载指定类型的 Gemini 窗口。首次需 Cami 手动登录。返回当前 URL。 */
    String loadWindow(String windowType, String url);

    /** 销毁指定窗口的 WebView，释放内存。 */
    void destroyWindow(String windowType);

    /** 获取窗口当前 URL（用于持久化到 window_urls.db）。 */
    String getWindowUrl(String windowType);

    /** 检查页面是否已加载完毕且可交互。 */
    boolean isPageReady(String windowType);

    // ── CDP 核心操作 ──

    /**
     * 向 Gemini 注入上下文并发送消息，等待回复。
     *
     * @param windowType 窗口类型：wake / chat / diary
     * @param systemContext 注入的系统上下文（包裹在 SYSTEM_CONTEXT_START/END 中，不会被当作 Cami 原文）
     * @param userMessage Cami 的原文（包裹在 CAMI_MSG_START/END 中，仅此部分被提取返回）
     * @param timeoutSeconds 等待回复的超时秒数（推荐 15-60）
     * @return JSON: {"status":"ok"|"timeout"|"captcha"|"signed_out"|"error", "reply":"...", "camiText":"..."}
     */
    String injectAndSend(String windowType, String systemContext, String userMessage, int timeoutSeconds);

    // ── 紧急操作 ──

    /** 截取当前 WebView 内容为 base64 PNG（用于 CAPTCHA 通知附图）。 */
    String captureWebViewScreenshot(String windowType);

    /** 注册回调（CAPTCHA / Google 登出 / 页面崩溃等事件）。 */
    void registerCallback(ICdpCallback callback);

    /** 取消注册回调。 */
    void unregisterCallback(ICdpCallback callback);
}
