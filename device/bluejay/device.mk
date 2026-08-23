# =============================================================================
# Anzhi's Phone — device.mk 覆盖配置
# =============================================================================
#
# 合并方式：在 device/google/bluejay/device.mk 末尾添加：
#   include device/anzhi/bluejay/device.mk
#
# BUILD.md 参考：Step 10（通知管理）、Step 13（Doze 白名单）、Step 14（编译）
# =============================================================================

# ───────────────────────────────────────────────────────────
# 1. PRODUCT_PACKAGES：打入 ROM 的模块
# ───────────────────────────────────────────────────────────

# 安知手机 系统服务（priv-app，平台签名）
PRODUCT_PACKAGES += AnzhiOS

# AnzhiCoreService 系统服务（独立进程，平台签名）
# 定义在 vendor/anzhi/anzhicore/Android.bp
PRODUCT_PACKAGES += AnzhiCore

# ───────────────────────────────────────────────────────────
# 2. PRODUCT_COPY_FILES：打入 ROM 的配置文件
# ───────────────────────────────────────────────────────────

# Default Permissions（Notification Assistant + Accessibility 开机自启白名单）
# Source: default-permissions/anzhi_assistant_grant.xml in this snapshot
PRODUCT_COPY_FILES += \
    vendor/anzhi/default-permissions/anzhi_assistant_grant.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/anzhi_assistant_grant.xml

# ───────────────────────────────────────────────────────────
# 3. 系统属性覆盖
# ───────────────────────────────────────────────────────────

# 安知手机 版本
PRODUCT_PROPERTY_OVERRIDES += \
    persist.anzhi.os.version=1.0.0 \
    persist.anzhi.os.build_type=userdebug

# VPS WebSocket 地址（刷机后可通过 adb shell setprop 覆盖）
PRODUCT_PROPERTY_OVERRIDES += \
    persist.anzhi.os.vps_url=ws://localhost:8080/anzhi

# DeepSeek / Gemini API 配置（留空——由 Cami 手动设置或从配置文件读取）
# 不把 API key 编译进 ROM
# PRODUCT_PROPERTY_OVERRIDES += persist.anzhi.os.deepseek_key=
# PRODUCT_PROPERTY_OVERRIDES += persist.anzhi.os.gemini_key=

# ───────────────────────────────────────────────────────────
# 4. 特性开关
# ───────────────────────────────────────────────────────────

# 启用 Notification Assistant 预置白名单
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.notification_assistant.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.software.notification_assistant.xml
