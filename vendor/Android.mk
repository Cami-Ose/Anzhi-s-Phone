# =============================================================================
# Anzhi's Phone — vendor 构建配方
# =============================================================================
#
# 安装路径：vendor/anzhi/Android.mk
#
# SELinux 策略 → BoardConfig_anzhi.mk 的 BOARD_SEPOLICY_DIRS 引入
# Default Permissions → device_anzhi.mk 的 PRODUCT_COPY_FILES 引入
# AnzhiCore → vendor/anzhi/anzhicore/Android.bp（Soong 自动发现）
# =============================================================================

# 此文件目前为空，但保留以兼容旧版 AOSP 的 BOARD_SEPOLICY_DIRS 机制。
# 所有安知相关的编译配置分散在 BoardConfig_anzhi.mk 和 device_anzhi.mk 中。
