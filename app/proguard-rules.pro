# ────────────────────────────────────────────────────
# Anzhi's Phone — ProGuard / R8 混淆保留规则
# ────────────────────────────────────────────────────
# 编译命令：mka AnzhiOS（Android.bp 自动应用此文件）
# ────────────────────────────────────────────────────

# ── Android 组件：Manifest 中声明的不能混淆 ──
-keep class com.anzhi.os.AnzhiManagerService { *; }
-keep class com.anzhi.os.AnzhiBootReceiver { *; }
-keep class com.anzhi.os.AnzhiAccessibility { *; }
-keep class com.anzhi.os.AnzhiNotification { *; }
-keep class com.anzhi.os.AnzhiNotificationAssistant { *; }
-keep class com.anzhi.os.dream.AnzhiDreamService { *; }
-keep class com.anzhi.os.dream.AnzhiDreamService$ShutdownReceiver { *; }
-keep class com.anzhi.os.cdp.CdpWebViewService { *; }
-keep class com.anzhi.os.cdp.CaptchaActivity { *; }
-keep class com.anzhi.os.chat.AnzhiChatActivity { *; }
-keep class com.anzhi.os.ui.lockscreen.LockActivity { *; }
-keep class com.anzhi.os.dashboard.AnzhiDashboardActivity { *; }

# ── AIDL 接口（Binder 跨进程通信，不可混淆） ──
-keep class com.anzhi.os.IAnzhiService { *; }
-keep class com.anzhi.os.IAnzhiService$Stub { *; }
-keep class com.anzhi.os.cdp.ICdpWebViewService { *; }
-keep class com.anzhi.os.cdp.ICdpWebViewService$Stub { *; }
-keep class com.anzhi.os.cdp.ICdpCallback { *; }
-keep class com.anzhi.os.cdp.ICdpCallback$Stub { *; }

# ── Kotlin Compose（Compose 编译器生成代码依赖反射） ──
-keep class androidx.compose.** { *; }

# ── 数据模型类（JSON 序列化/反序列化） ──
-keep class com.anzhi.os.model.** { *; }
-keep class com.anzhi.os.SocketMessage { *; }
-keep class com.anzhi.os.PendingRequest { *; }

# ── 审计日志/工具类 ──
-keep class com.anzhi.os.AnzhiAuditLog { *; }
-keep class com.anzhi.os.AnzhiSessionStore { *; }
