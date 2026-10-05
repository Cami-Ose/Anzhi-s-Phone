#!/bin/bash
# =============================================================================
# Anzhi's Phone — ROM 全编译脚本（Step 14）
# =============================================================================
#
# 用法：
#   chmod +x scripts/build_rom.sh
#   ./scripts/build_rom.sh              # 全编译 ROM
#   ./scripts/build_rom.sh --clean      # 全量清理后全编译
#   ./scripts/build_rom.sh --anzhi-only # 仅编译 AnzhiOS APK
#   ./scripts/build_rom.sh --verify     # 仅验证编译环境
#
# 前提条件（BUILD.md §六）：
#   1. WSL2 Ubuntu 26.04，源码在 /home/<user>/lineageos/
#   2. 已执行 repo init + repo sync
#   3. 已配置 local_manifests/anzhi.xml
#   4. 已复制安知源码到 AOSP 树
#
# 内存要求：
#   - 工具链验证（make adb）：≥ 8GB
#   - 完整 mka bacon       ：≥ 16GB
#   - 本机 7.6GB 仅能编译单个模块；完整 ROM 需 VPS/云服务器
# =============================================================================

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────
LINEAGE_ROOT="${LINEAGE_ROOT:-$HOME/lineageos}"
if [ -z "${ANZHI_SRC:-}" ]; then
    # 目录名里的撇号无法写进 ${VAR:-默认值}（bash 会当成引号起始），改用通配解析
    ANZHI_SRC=$(ls -d /mnt/*/Anzhi*Phone 2>/dev/null | head -1 || true)
fi
: "${ANZHI_SRC:?未找到源码目录，请 export ANZHI_SRC=/path/to/repo}"
BUILD_LOG="$LINEAGE_ROOT/build_anzhi.log"
JOBS="${JOBS:-}"                             # 留空，由 verify_environment 自动检测

# ── 颜色 ────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[安知手机]${NC} $*"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $*"; }
error() { echo -e "${RED}[错误]${NC} $*"; exit 1; }

# ── 步骤 0：检查环境 ────────────────────────────────────
verify_environment() {
    info "Step 0: 检查编译环境..."

    # 检查代码目录
    if [ ! -d "$LINEAGE_ROOT" ]; then
        error "LineageOS 源码目录不存在: $LINEAGE_ROOT"
    fi

    if [ ! -f "$LINEAGE_ROOT/build/envsetup.sh" ]; then
        error "未找到 build/envsetup.sh——请先 repo init + repo sync"
    fi

    # 检查工具链
    if ! command -v make &>/dev/null; then
        error "make 未安装。请运行：sudo apt install -y build-essential"
    fi

    # ── CPU 核数 & 内存检查 ──
    CPU_CORES=$(nproc 2>/dev/null || echo 4)
    TOTAL_MEM=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0)
    TOTAL_MEM_GB=$(( TOTAL_MEM / 1024 / 1024 ))

    if [ "$TOTAL_MEM_GB" -lt 8 ]; then
        warn "内存 < 8GB（约 ${TOTAL_MEM_GB}GB）。完整编译需 ≥16GB。"
        warn "将使用 -j2 编译，且只编译 AnzhiOS 模块。"
        JOBS=2
    elif [ "$TOTAL_MEM_GB" -lt 16 ]; then
        warn "内存 < 16GB（约 ${TOTAL_MEM_GB}GB）。完整 mka bacon 可能 OOM。"
        warn "建议仅编译 AnzhiOS 模块或在 VPS 上跑完整编译。"
        JOBS=${JOBS:-$CPU_CORES}
    else
        # 内存充足时自动设定 JOBS = CPU 核数 × 1.5（AOSP 编译推荐值）
        JOBS=${JOBS:-$(( CPU_CORES * 3 / 2 ))}
        info "自动设定 JOBS=$JOBS（CPU=${CPU_CORES}核，RAM=${TOTAL_MEM_GB}GB）"
    fi

    # ── ccache 检测 ──
    if command -v ccache &>/dev/null; then
        export USE_CCACHE=1
        export CCACHE_DIR="${CCACHE_DIR:-$LINEAGE_ROOT/.ccache}"
        local cache_max="${CCACHE_SIZE:-20G}"
        ccache -M "$cache_max" 2>/dev/null || true
        info "ccache 已启用（${CCACHE_DIR}，上限 ${cache_max}）"
    else
        info "ccache 未安装——建议安装以加速后续增量编译：sudo apt install ccache"
    fi

    info "编译环境检查完成 ✓"
}

# ── 步骤 1：部署安知源码到 AOSP 树 ──────────────────────
deploy_source() {
    info "Step 1: 部署安知源码到 AOSP 树..."

    # 检查 Windows 源码是否可达
    if [ ! -d "$ANZHI_SRC/app" ]; then
        warn "安知源码目录不可达: $ANZHI_SRC/app"
        warn "请确保 WSL 中 /mnt/c/ 已挂载"
        warn "跳过自动部署，假设已手动部署"
        return
    fi

    # 复制 App 源码
    APP_TARGET="$LINEAGE_ROOT/packages/apps/AnzhiOS"
    mkdir -p "$APP_TARGET"
    info "  复制 app/ → packages/apps/AnzhiOS/"
    cp -r "$ANZHI_SRC/app/"* "$APP_TARGET/" 2>/dev/null || warn "复制 app/ 失败（可能已存在）"

    # 复制 SELinux 策略
    SEPOLICY_DIR="$LINEAGE_ROOT/vendor/anzhi/sepolicy"
    mkdir -p "$SEPOLICY_DIR"
    info "  复制 sepolicy/* → vendor/anzhi/sepolicy/"
    cp "$ANZHI_SRC/sepolicy/anzhi_service.te" "$SEPOLICY_DIR/" 2>/dev/null || warn "复制 sepolicy/anzhi_service.te 失败"
    cp "$ANZHI_SRC/sepolicy/property_contexts" "$SEPOLICY_DIR/" 2>/dev/null || warn "复制 sepolicy/property_contexts 失败"
    cp "$ANZHI_SRC/sepolicy/seapp_contexts" "$SEPOLICY_DIR/" 2>/dev/null || warn "复制 sepolicy/seapp_contexts 失败"

    # 复制 priv-app 特权权限 allowlist（源文件在 vendor/anzhi/ 下，随源码一起进树）
    PERM_DIR="$LINEAGE_ROOT/vendor/anzhi/privapp-permissions"
    mkdir -p "$PERM_DIR"
    info "  复制 vendor/anzhi/privapp-permissions/ → vendor/anzhi/privapp-permissions/"
    cp "$ANZHI_SRC/vendor/anzhi/privapp-permissions/"*.xml "$PERM_DIR/" 2>/dev/null || warn "复制 privapp-permissions 失败"

    # 复制 vendor/Android.mk
    info "  复制 vendor/Android.mk"
    cp "$ANZHI_SRC/vendor/Android.mk" "$LINEAGE_ROOT/vendor/anzhi/" 2>/dev/null || warn "复制 Android.mk 失败"

    # 复制 AnzhiCoreService 系统服务模块
    ANZHICORE_TARGET="$LINEAGE_ROOT/vendor/anzhi/anzhicore"
    mkdir -p "$ANZHICORE_TARGET"
    info "  复制 anzhicore/ → vendor/anzhi/anzhicore/"
    cp -r "$ANZHI_SRC/vendor/anzhi/anzhicore/"* "$ANZHICORE_TARGET/" 2>/dev/null || warn "复制 anzhicore/ 失败"

    # 复制 SELinux service_contexts（AnzhiCoreService 服务注册）
    info "  复制 sepolicy/service_contexts → vendor/anzhi/sepolicy/"
    cp "$ANZHI_SRC/sepolicy/service_contexts" "$SEPOLICY_DIR/" 2>/dev/null || warn "复制 service_contexts 失败"

    # ── 产品配置：生效路径是 device/anzhi/bluejay/（lineage_bluejay.mk 末尾 inherit 它）
    #    device/google/bluejay/ 下没有上游 device.mk，把配置放那儿永远不会被读到 ──
    ANZHI_DEVICE_DIR="$LINEAGE_ROOT/device/anzhi/bluejay"
    mkdir -p "$ANZHI_DEVICE_DIR"
    info "  复制 device/bluejay/device.mk → device/anzhi/bluejay/device.mk"
    cp "$ANZHI_SRC/device/bluejay/device.mk" "$ANZHI_DEVICE_DIR/device.mk" || warn "复制 device.mk 失败"

    # VPS 地址与接口令牌**不进 ROM**（2026-10-03 Cami 定的）：令牌一旦落到
    # /system/build.prop 就是任何 app `getprop` 可读的明文，而且她换服务器就得重编一次。
    # 改由应用内设置界面填写、存 app 私有 SharedPreferences；device.mk 里只保留
    # persist.anzhi.api_url 作为出厂默认地址（非机密，可被界面覆盖）。

    OVERLAY_DIR="$ANZHI_DEVICE_DIR/overlay/frameworks/base/core/res/res/values"
    mkdir -p "$OVERLAY_DIR"
    info "  复制 overlay config.xml → device/anzhi/bluejay/overlay/（DEVICE_PACKAGE_OVERLAYS 生效）"
    cp "$ANZHI_SRC/device/bluejay/overlay/frameworks/base/core/res/res/values/config.xml" \
       "$OVERLAY_DIR/" || warn "复制 overlay config.xml 失败"

    # ── BoardConfig：真实入口在 device/google/bluejay/bluejay/BoardConfig.mk（gs101 分层）──
    DEVICE_DIR="$LINEAGE_ROOT/device/google/bluejay"
    BOARD_CFG="$DEVICE_DIR/bluejay/BoardConfig.mk"
    if [ ! -f "$BOARD_CFG" ]; then
        error "找不到 BoardConfig：$BOARD_CFG"
    fi

    info "  复制 device/bluejay/BoardConfig.mk → device/google/bluejay/BoardConfig_anzhi.mk"
    cp "$ANZHI_SRC/device/bluejay/BoardConfig.mk" "$DEVICE_DIR/BoardConfig_anzhi.mk" 2>/dev/null || warn "复制 BoardConfig 失败"

    # 幂等注册 include（只写一次）
    if ! grep -q "include device/google/bluejay/BoardConfig_anzhi.mk" "$BOARD_CFG"; then
        {
            echo ""
            echo "# Anzhi's Phone"
            echo "include device/google/bluejay/BoardConfig_anzhi.mk"
        } >> "$BOARD_CFG"
        info "  ✓ BoardConfig_anzhi.mk 已注册到 bluejay/BoardConfig.mk"
    else
        info "  - BoardConfig_anzhi.mk 已注册，跳过"
    fi

    # 幂等注册 inherit-product（只写一次）
    PRODUCT_CFG="$DEVICE_DIR/lineage_bluejay.mk"
    if ! grep -q "inherit-product, device/anzhi/bluejay/device.mk" "$PRODUCT_CFG"; then
        {
            echo ""
            echo "# Anzhi's Phone"
            echo '$(call inherit-product, device/anzhi/bluejay/device.mk)'
        } >> "$PRODUCT_CFG"
        info "  ✓ device/anzhi/bluejay/device.mk 已注册到 lineage_bluejay.mk"
    else
        info "  - device/anzhi/bluejay/device.mk 已注册，跳过"
    fi

    # ── 清走历次脚本写歪的孤儿文件（移出去，不删）──
    STALE_DIR="$LINEAGE_ROOT/.anzhi-stale/$(date +%Y%m%d-%H%M%S)"
    for stale in "$DEVICE_DIR/device.mk" "$DEVICE_DIR/device_anzhi.mk" "$DEVICE_DIR/test_include.txt" "$DEVICE_DIR/aosp_bluejax.mk" "$DEVICE_DIR/BoardConfig.mk"; do
        [ -e "$stale" ] || continue
        mkdir -p "$STALE_DIR"
        mv "$stale" "$STALE_DIR/"
        warn "  移走无关文件：$stale → $STALE_DIR"
    done

    # device/bluejay/sepolicy/file_contexts 暂不接入：它会把 /data/data/com.anzhi.os
    # 改标成 anzhi_data_file，没有实机验证前可能造成安知起不来。
    # 待 ROM 能开机、确认 anzhi_service.te 里的类型定义齐备后再连入 vendor/anzhi/sepolicy。

    # 复制 local_manifest
    MANIFEST_DIR="$LINEAGE_ROOT/.repo/local_manifests"
    mkdir -p "$MANIFEST_DIR"
    info "  复制 local_manifests/anzhi.xml → .repo/local_manifests/"
    cp "$ANZHI_SRC/local_manifests/anzhi.xml" "$MANIFEST_DIR/" 2>/dev/null || warn "复制 manifest 失败"

    # 复制构建脚本
    mkdir -p "$LINEAGE_ROOT/scripts"
    cp "$ANZHI_SRC/scripts/build_rom.sh" "$LINEAGE_ROOT/scripts/" 2>/dev/null || true
    cp "$ANZHI_SRC/scripts/collect_avc.sh" "$LINEAGE_ROOT/scripts/" 2>/dev/null || true

    info "源码部署完成 ✓"
}

# ── 步骤 2：SELinux 模式设置 ────────────────────────────
configure_selinux() {
    local mode="${1:-enforcing}"

    info "Step 2: SELinux 模式设置 → $mode"

    BOARD_CFG="$LINEAGE_ROOT/device/google/bluejay/bluejay/BoardConfig.mk"

    if [ ! -f "$BOARD_CFG" ]; then
        error "找不到 BoardConfig：$BOARD_CFG"
    fi

    if [ "$mode" == "permissive" ]; then
        # 开发阶段：Permissive
        if ! grep -q "androidboot.selinux=permissive" "$BOARD_CFG" 2>/dev/null; then
            echo "" >> "$BOARD_CFG"
            echo "# Anzhi's Phone — 开发阶段 Permissive 模式" >> "$BOARD_CFG"
            echo "BOARD_KERNEL_CMDLINE += androidboot.selinux=permissive" >> "$BOARD_CFG"
            warn "SELinux 已设为 Permissive——操作不被阻止但记录 avc 日志"
        fi
    else
        # 生产阶段：Enforcing
        if grep -q "androidboot.selinux=permissive" "$BOARD_CFG" 2>/dev/null; then
            sed -i '/androidboot.selinux=permissive/d' "$BOARD_CFG"
            info "移除 Permissive 标志 → 编译后默认 Enforcing ✓"
        else
            info "已是 Enforcing 模式 ✓"
        fi
    fi
}

# ── 步骤 3：编译 ────────────────────────────────────────
build_rom() {
    # 变量名必须带前缀：breakfast/junch 在同一个 shell 里跑，会给普通
    # （非 local 作用域友好的）名字如 target 赋值，把我们的入参覆盖掉
    local anzhi_target="${1:-bacon}"
    local anzhi_clean="${2:-no}"

    info "Step 3: 开始编译（target=$anzhi_target, jobs=$JOBS）..."
    cd "$LINEAGE_ROOT"

    # envsetup.sh 引用未定义的 TOP，breakfast 也有无害的非零返回，
    # 顶部那套 set -eu 会让脚本在这里当场退出
    set +eu
    source build/envsetup.sh
    breakfast bluejay
    set -eu

    # 可选 clean
    if [ "$anzhi_clean" = "yes" ]; then
        info "执行 make installclean（清理输出产物，保留中间编译缓存）..."
        make installclean 2>&1 | tee -a "$BUILD_LOG" || true
    fi

    # 记录开始时间
    BUILD_START=$(date +%s)

    # 编译
    case "$anzhi_target" in
        bacon)
            info "全编译 ROM（mka bacon）——预计 4-6 小时（取决于机器）"
            mka bacon -j"$JOBS" 2>&1 | tee -a "$BUILD_LOG"
            ;;
        anzhi)
            info "仅编译 AnzhiOS + AnzhiCore"
            mka AnzhiOS -j"$JOBS" 2>&1 | tee -a "$BUILD_LOG"
            mka AnzhiCore -j"$JOBS" 2>&1 | tee -a "$BUILD_LOG"
            ;;
        *)
            error "未知编译目标: $anzhi_target"
            ;;
    esac

    # 耗时统计
    BUILD_END=$(date +%s)
    BUILD_MIN=$(( (BUILD_END - BUILD_START) / 60 ))
    BUILD_SEC=$(( (BUILD_END - BUILD_START) % 60 ))
    info "编译耗时：${BUILD_MIN}分${BUILD_SEC}秒"

    # 检查结果
    if [ "$anzhi_target" == "bacon" ]; then
        local zip_file=$(find "$LINEAGE_ROOT/out/target/product/bluejay" -maxdepth 1 -name "*.zip" 2>/dev/null | head -1)
        if [ -n "$zip_file" ]; then
            info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            info "编译成功！ROM 包："
            info "  $zip_file"
            info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        else
            error "编译失败——未找到输出 zip。查看日志: $BUILD_LOG"
        fi
    else
        local anzios_apk=$(find "$LINEAGE_ROOT/out/target/product/bluejay" -path "*/AnzhiOS.apk" 2>/dev/null | head -1)
        local anzhicore_apk=$(find "$LINEAGE_ROOT/out/target/product/bluejay" -path "*/AnzhiCore.apk" 2>/dev/null | head -1)
        local all_ok=true
        if [ -n "$anzios_apk" ]; then
            info "AnzhiOS APK 编译成功：$anzios_apk"
        else
            error "AnzhiOS 编译失败。查看日志: $BUILD_LOG"
            all_ok=false
        fi
        if [ -n "$anzhicore_apk" ]; then
            info "AnzhiCore APK 编译成功：$anzhicore_apk"
        else
            error "AnzhiCore 编译失败。查看日志: $BUILD_LOG"
            all_ok=false
        fi
        if [ "$all_ok" = false ]; then
            exit 1
        fi
    fi
}

# ── 步骤 4：刷机验证清单 ────────────────────────────────
print_flash_guide() {
    info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    info "刷机步骤："
    info ""
    info "1. 解锁 bootloader（仅首次）："
    info "   adb reboot bootloader"
    info "   fastboot flashing unlock"
    info ""
    info "2. 刷入 ROM："
    info "   adb reboot bootloader"
    info "   fastboot flashall -w"
    info ""
    info "3. 开机后验证 SELinux："
    info "   adb shell getenforce"
    info "   # 期望输出：Enforcing"
    info ""
    info "4. 收集 AVC 日志（如还在调 SELinux）："
    info "   ./scripts/collect_avc.sh -o avc.log &"
    info ""
    info "5. 验证 Data Saver 豁免："
    info "   adb shell cmd netpolicy list restrict-background-whitelist | grep anzhi"
    info "   # 期望输出：com.anzhi.os"
    info ""
    info "6. 验证安知服务在跑："
    info "   adb shell dumpsys activity services com.anzhi.os"
    info ""
    info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# ── 主入口 ──────────────────────────────────────────────
main() {
    local mode="${1:---full}"

    echo ""
    info "安知手机 ROM 编译脚本（Step 14）"
    info "目标设备：Pixel 6a (bluejay)"
    info "=============================================="

    verify_environment

    case "$mode" in
            --verify)
            info "仅验证环境——跳过编译"
            print_flash_guide
            ;;

        --deploy)
            # 只部署配置文件到 AOSP 树，不编译
            deploy_source
            configure_selinux "enforcing"
            info "部署完成，未编译。接着跑 --full 或 --anzhi-only"
            ;;

        --clean)
            deploy_source
            configure_selinux "enforcing"
            build_rom "bacon" "yes"
            print_flash_guide
            ;;
        --anzhi-only)
            deploy_source
            configure_selinux "enforcing"
            build_rom "anzhi"
            print_flash_guide
            ;;

        --full)
            deploy_source
            configure_selinux "enforcing"
            build_rom "bacon"
            print_flash_guide
            ;;

        --dev)
            # 开发模式：Permissive + 仅编译 AnzhiOS
            deploy_source
            configure_selinux "permissive"
            build_rom "anzhi"
            warn "开发模式：SELinux=Permissive。用 collect_avc.sh 收集 AVC 日志。"
            ;;

        *)
            echo "用法: $0 [--full|--clean|--anzhi-only|--verify|--dev]"
            echo ""
            echo "  --full        全编译 ROM + Enforcing（默认）"
            echo "  --clean       先 installclean 再全编译"
            echo "  --anzhi-only  仅编译 AnzhiOS APK"
            echo "  --verify      仅验证环境，不编译"
            echo "  --deploy      仅把配置/源码部署进 AOSP 树，不编译"
            echo "  --dev         开发模式：Permissive + 仅编译 AnzhiOS"
            ;;
    esac
}

main "$@"
