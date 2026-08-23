# =============================================================================
# Anzhi's Phone — BoardConfig.mk 覆盖配置
# =============================================================================
#
# 合并方式：在 device/google/bluejay/BoardConfig.mk 末尾添加：
#   include device/anzhi/bluejay/BoardConfig.mk
#
# BUILD.md 参考：Step 14（SELinux Enforcing + 完整 ROM 编译）
# =============================================================================

# ───────────────────────────────────────────────────────────
# Kernel 预编译模块路径（避免每次从 GitHub 同步）
# device/google/bluejay-kernels/6.1/ 已有全部 .ko 和 boot.img
# ───────────────────────────────────────────────────────────

# TARGET_KERNEL_DIR 在 gs101/BoardConfig-common.mk 中被引用（KERNEL_MODULE_DIR）
# 该文件在 BoardConfig_anzhi.mk 之前被 include，用 := 求值。
# 所以这里除了设 TARGET_KERNEL_DIR，还需要重新计算下游变量。
TARGET_KERNEL_DIR := device/google/bluejay-kernels/6.1
KERNEL_MODULE_DIR := $(TARGET_KERNEL_DIR)
BOARD_VENDOR_KERNEL_MODULES := $(wildcard $(KERNEL_MODULE_DIR)/*.ko)
BOARD_VENDOR_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_MODULE_DIR)/vendor_dlkm.modules.load))

# ───────────────────────────────────────────────────────────
# SELinux：Enforcing 模式（Step 14 收紧）
#
# 开发阶段：取消注释 androidboot.selinux=permissive
# 正式阶段：注释掉或删除此行 → 系统默认 Enforcing
# ───────────────────────────────────────────────────────────

# ⚠️ 开发阶段用 Permissive（收集 AVC 日志）
#    正式编译时注释掉下面这行即可切换为 Enforcing
# BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive

# ───────────────────────────────────────────────────────────
# SELinux 策略目录
# 把 anzhi_service.te 所在目录加入编译路径
# ───────────────────────────────────────────────────────────

BOARD_SEPOLICY_DIRS += vendor/anzhi/sepolicy

# 若将 anzhi_service.te 放在 device 目录下：
# BOARD_SEPOLICY_DIRS += device/google/bluejay/sepolicy

# ───────────────────────────────────────────────────────────
# 编译线程数（适配本机内存）
# Pixel 6a 源码编译建议 -j4 避免 OOM（本机 7.6GB RAM）
# 完整 mka bacon 需 ≥16GB 内存
# ───────────────────────────────────────────────────────────

# 若本机内存 < 16GB，单独编译模块：
#   mka AnzhiOS   （仅编译安知 APK）
#   完整 mka bacon 在 VPS/云服务器上跑
