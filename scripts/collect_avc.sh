#!/bin/bash
# ============================================================================
# collect_avc.sh — 安知手机 SELinux AVC 拒绝日志收集
#
# 用途：
#   收集所有与安知相关的 SELinux AVC denied 日志，用于补充 .te 规则。
#   SELinux 在 Permissive 模式下不阻止操作但会记录 denied 日志。
#   开发阶段持续运行此脚本，每新增一个模块就同步补齐 .te 规则。
#
# 用法：
#   chmod +x collect_avc.sh
#   ./collect_avc.sh              # 实时输出到终端
#   ./collect_avc.sh -o avc.log   # 输出到文件
#   ./collect_avc.sh -c           # 统计当前有多少条 avc denied
#   ./collect_avc.sh -p           # 生成 .te 规则建议
#
# 依赖：
#   - adb 在 PATH 中
#   - 手机已连接（adb devices）且已授权 USB 调试
#   - SELinux 为 Permissive 模式（开发阶段）
#
# BUILD.md 参考：Step 2 — SELinux 地基
# ============================================================================

set -euo pipefail

OUTPUT_FILE=""
COUNT_ONLY=false
PARSE_ONLY=false

usage() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  -o FILE    将日志写入 FILE（默认输出到终端）"
    echo "  -c         只统计当前 avc denied 数量"
    echo "  -p         解析日志并生成 .te 规则建议"
    echo "  -h         显示此帮助"
    echo ""
    echo "示例:"
    echo "  $0                    # 实时看 avc 日志"
    echo "  $0 -o avc_$(date +%Y%m%d).log &  # 后台收集"
    echo "  $0 -c                 # 看有多少条 denied"
    echo "  $0 -p | tee rules.te  # 生成规则建议"
    exit 0
}

# 解析参数
while getopts "o:cph" opt; do
    case $opt in
        o) OUTPUT_FILE="$OPTARG" ;;
        c) COUNT_ONLY=true ;;
        p) PARSE_ONLY=true ;;
        h) usage ;;
        *) usage ;;
    esac
done

# 检查 adb
if ! command -v adb &> /dev/null; then
    echo "❌ 找不到 adb 命令。请安装 Android SDK Platform Tools 并加入 PATH。"
    exit 1
fi

# 检查设备连接
DEVICES=$(adb devices 2>/dev/null | tail -n +2 | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 未检测到已连接的 Android 设备。请："
    echo "   1. USB 连接手机"
    echo "   2. 开启 USB 调试"
    echo "   3. 运行 adb devices 确认设备已授权"
    exit 1
fi

echo "✅ 设备已连接，开始收集安知 SELinux AVC 日志…"
echo "   过滤条件: avc + anzhi"
echo "   按 Ctrl+C 停止"
echo ""

# ── 模式：只统计 ──
if $COUNT_ONLY; then
    COUNT=$(adb logcat -d -s auditd 2>/dev/null | grep -i "avc.*anzhi\|anzhi.*avc" | wc -l)
    echo "当前缓冲区中安知相关 AVC denied 数量: $COUNT"
    exit 0
fi

# ── 模式：解析生成 .te 建议 ──
if $PARSE_ONLY; then
    echo "# 安知手机 SELinux 规则建议"
    echo "# 生成时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "# 数据来源: adb logcat -d | grep avc"
    echo ""
    echo "# 用法：将需要的规则追加到 sepolicy/anzhi_service.te"
    echo ""

    adb logcat -d -s auditd 2>/dev/null | grep -i "avc" | grep -i "anzhi\|com\.anzhi" | while read -r line; do
        # 提取 scontext / tcontext / tclass / permissive
        SCONTEXT=$(echo "$line" | grep -oP 'scontext=u:r:\K[^:]+')
        TCONTEXT=$(echo "$line" | grep -oP 'tcontext=u:object_r:\K[^:]+')
        TCLASS=$(echo "$line" | grep -oP 'tclass=\K[^ ]+')
        PERM=$(echo "$line" | grep -oP '\{ \K[^}]+')

        if [ -n "$SCONTEXT" ] && [ -n "$TCONTEXT" ] && [ -n "$TCLASS" ]; then
            echo "# $(echo "$line" | head -c 120)"
            echo "allow $SCONTEXT $TCONTEXT:$TCLASS { $PERM };"
            echo ""
        fi
    done
    exit 0
fi

# ── 模式：实时收集 ──
FILTER="avc.*anzhi|anzhi.*avc|avc.*com\.anzhi"

if [ -n "$OUTPUT_FILE" ]; then
    echo "输出文件: $OUTPUT_FILE"
    echo "安知手机 SELinux AVC 日志 — 开始于 $(date '+%Y-%m-%d %H:%M:%S')" > "$OUTPUT_FILE"
    echo "过滤: $FILTER" >> "$OUTPUT_FILE"
    echo "---" >> "$OUTPUT_FILE"
    adb logcat -s auditd 2>/dev/null | grep -iE "$FILTER" >> "$OUTPUT_FILE"
else
    adb logcat -s auditd 2>/dev/null | grep -iE "$FILTER"
fi
