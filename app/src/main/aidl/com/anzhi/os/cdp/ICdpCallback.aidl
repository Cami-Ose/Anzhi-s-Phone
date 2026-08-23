// ICdpCallback.aidl — CDP WebView 事件回调
//
// 从 :webview 进程回调到主进程。通知 CAPTCHA / Google 登出 / 页面崩溃等事件。

package com.anzhi.os.cdp;

oneway interface ICdpCallback {

    /** CAPTCHA 或 Cloudflare 验证页面被检测到。 */
    void onCaptchaDetected(String windowType, String pageTitle);

    /** Google 登出被检测到（需要 Cami 重新登录）。 */
    void onGoogleSignedOut(String windowType);

    /** WebView 进程崩溃（Android 杀掉了 :webview 进程）。 */
    void onWebViewCrashed(String windowType);

    /** 页面加载失败（网络错误 / 超时）。 */
    void onPageLoadFailed(String windowType, String errorMessage);
}
