# =============================================================================
# Anzhi's Phone — 产品配置
#
# 生效路径：device/anzhi/bluejay/device.mk
#   由 device/google/bluejay/lineage_bluejay.mk 末尾的
#   $(call inherit-product, device/anzhi/bluejay/device.mk) 引入。
#   device/google/bluejay/ 下没有上游 device.mk，产品入口是 lineage_bluejay.mk，
#   这份配置放到别处不会被编译系统读到。
#
# BUILD.md 参考：Step 10（通知管理）、Step 14（AOSP 构建）
# =============================================================================

# ───────────────────────────────────────────────────────────
# 1. PRODUCT_PACKAGES
# ───────────────────────────────────────────────────────────

# 安知主应用（system_ext/priv-app，平台签名）
PRODUCT_PACKAGES += AnzhiOS

# AnzhiCoreService 系统服务（system_ext/priv-app，平台签名）
# 定义在 vendor/anzhi/anzhicore/Android.bp；显式列出，不靠 AnzhiOS 顺带依赖带入
PRODUCT_PACKAGES += AnzhiCore

# ───────────────────────────────────────────────────────────
# 2. PRODUCT_COPY_FILES
# ───────────────────────────────────────────────────────────

# priv-app 特权权限 allowlist。manifest 每新增一个 signature|privileged 权限，
# 这里必须同步加条目——缺条目会在开机时被 SystemConfig 判为致命错误。
PRODUCT_COPY_FILES += \
    vendor/anzhi/privapp-permissions/privapp-permissions-com.anzhi.os.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-com.anzhi.os.xml

# 声明设备支持 Notification Assistant 角色
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.notification_assistant.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/android.software.notification_assistant.xml

# ───────────────────────────────────────────────────────────
# 3. 框架资源覆盖（通知助理开机默认启用）
# ───────────────────────────────────────────────────────────

DEVICE_PACKAGE_OVERLAYS += device/anzhi/bluejay/overlay

# ───────────────────────────────────────────────────────────
# 4. 系统属性（属性名必须与 app 侧 SystemProperties.get 逐字一致）
#    persist.vendor.anzhi.api_url     ← AnzhiChatActivity.kt:210 → VpsBrainProvider
#    persist.vendor.anzhi.server_url  ← AnzhiChatActivity.kt:190（旧 WS 通道，见下方说明）
#    persist.vendor.anzhi.token       ← **不在这里**，见下面第二段
# ───────────────────────────────────────────────────────────

# VPS 大脑入口：**不预填**。Cami 2026-10-04 定的口径是「vps 地址和 token 我都自己在应用界面填」，
# 所以这条属性出厂就是空的，由设置界面写进 app 私有 SharedPreferences，读取优先级 界面值 > 属性 > 空。
# 属性这条通道保留，只用于开发期用 adb 临时补，不进镜像默认值。
# PRODUCT_PROPERTY_OVERRIDES 里如果将来要加 vendor 属性，名字必须 persist.vendor./vendor./ro.vendor. 开头。

# VPS 接口令牌**不进 ROM**（2026-10-03 Cami 定的）：
# /system/build.prop 是世界可读的，任何 app `getprop persist.vendor.anzhi.token` 就能拿走；
# 而且烤进镜像等于她每换一次服务器就要重编一次 ROM。
# 正确做法：应用设置界面填一次 → 存 app 私有 SharedPreferences（/data 沙箱内），
# 读取优先级 界面值 > 属性 > 空。属性这条保留只是为了开发期用 adb 临时补。

# persist.vendor.anzhi.server_url 故意不填：ROM 侧 AnzhiSocket 连的是 ws://…:8080/anzhi，
# 而 VPS 上 8080 只绑 127.0.0.1、且 /root/anzhi 下没有任何 WebSocket 服务
# （推送已改成 chat 域名的 /events SSE）。填了只会让安知
# 对着一个不存在的端口无限重连。手机端改走 SSE 之后再填这条。

# 模型 key 不进 ROM：DESIGN.md「API key 全部在 VPS，手机不持有任何 key」。
# 另有一处遗留待清：AnzhiNotificationAssistant.kt:525 读的是
# persist.vendor.anzhi.deepseek_api_key，与 Manager 的 persist.vendor.anzhi.deepseek_key 不同名，
# 两个都没有消费方——按上面那条不变量应整体删除，而不是补属性。

