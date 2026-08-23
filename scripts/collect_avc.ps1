# ============================================================================
# collect_avc.ps1 — 安知手机 SELinux AVC 拒绝日志收集（Windows PowerShell）
#
# 用途：
#   收集所有与安知相关的 SELinux AVC denied 日志，用于补充 .te 规则。
#   SELinux 在 Permissive 模式下不阻止操作但会记录 denied 日志。
#   开发阶段持续运行此脚本，每新增一个模块就同步补齐 .te 规则。
#
# 用法：
#   .\collect_avc.ps1                    # 实时输出到终端
#   .\collect_avc.ps1 -OutputFile avc.log  # 输出到文件
#   .\collect_avc.ps1 -CountOnly           # 统计当前有多少条 avc denied
#   .\collect_avc.ps1 -ParseOnly           # 生成 .te 规则建议
#
# 依赖：
#   - adb 在 PATH 中（Android SDK Platform Tools）
#   - 手机已连接（adb devices）
#
# BUILD.md 参考：Step 2 — SELinux 地基
# ============================================================================

param(
    [string]$OutputFile = "",
    [switch]$CountOnly,
    [switch]$ParseOnly,
    [switch]$Help
)

if ($Help) {
    Write-Host @"
用法: .\collect_avc.ps1 [选项]

选项:
  -OutputFile FILE  将日志写入 FILE（默认输出到终端）
  -CountOnly        只统计当前 avc denied 数量
  -ParseOnly        解析日志并生成 .te 规则建议
  -Help             显示此帮助

示例:
  .\collect_avc.ps1                              # 实时看 avc 日志
  Start-Job { .\collect_avc.ps1 -OutputFile avc.log }  # 后台收集
  .\collect_avc.ps1 -CountOnly                         # 看有多少条 denied
  .\collect_avc.ps1 -ParseOnly | Tee-Object rules.te   # 生成规则建议
"@
    exit 0
}

# 检查 adb
if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
    Write-Host "❌ 找不到 adb 命令。请安装 Android SDK Platform Tools 并加入 PATH。" -ForegroundColor Red
    exit 1
}

# 检查设备连接
$devices = adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
if (-not $devices) {
    Write-Host "❌ 未检测到已连接的 Android 设备。" -ForegroundColor Red
    Write-Host "   1. USB 连接手机"
    Write-Host "   2. 开启 USB 调试"
    Write-Host "   3. 运行 adb devices 确认设备已授权"
    exit 1
}

Write-Host "✅ 设备已连接，开始收集安知 SELinux AVC 日志…" -ForegroundColor Green
Write-Host "   过滤: avc + anzhi | com.anzhi"
Write-Host "   按 Ctrl+C 停止"
Write-Host ""

$filter = "avc.*anzhi|anzhi.*avc|avc.*com\.anzhi"

# ── 模式：只统计 ──
if ($CountOnly) {
    $count = (adb logcat -d -s auditd 2>$null | Select-String -Pattern $filter -AllMatches).Matches.Count
    Write-Host "当前缓冲区中安知相关 AVC denied 数量: $count"
    exit 0
}

# ── 模式：解析生成 .te 建议 ──
if ($ParseOnly) {
    Write-Host "# 安知手机 SELinux 规则建议"
    Write-Host "# 生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    Write-Host "# 数据来源: adb logcat -d | grep avc"
    Write-Host ""
    Write-Host "# 用法：将需要的规则追加到 sepolicy/anzhi_service.te"
    Write-Host ""

    $lines = adb logcat -d -s auditd 2>$null | Select-String -Pattern $filter
    foreach ($line in $lines) {
        $text = $line.Line
        # 提取 scontext / tcontext / tclass
        if ($text -match 'scontext=u:r:([^:]+)') { $scontext = $Matches[1] } else { $scontext = "" }
        if ($text -match 'tcontext=u:object_r:([^:]+)') { $tcontext = $Matches[1] } else { $tcontext = "" }
        if ($text -match 'tclass=(\S+)') { $tclass = $Matches[1] } else { $tclass = "" }
        if ($text -match '\{ ([^}]+)') { $perm = $Matches[1] } else { $perm = "" }

        if ($scontext -and $tcontext -and $tclass) {
            Write-Host "# $($text.Substring(0, [Math]::Min(120, $text.Length)))"
            Write-Host "allow $scontext $tcontext`:$tclass { $perm };"
            Write-Host ""
        }
    }
    exit 0
}

# ── 模式：实时收集 ──
if ($OutputFile) {
    Write-Host "输出文件: $OutputFile"
    "安知手机 SELinux AVC 日志 — 开始于 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Out-File -FilePath $OutputFile -Encoding utf8
    "过滤: $filter" | Out-File -FilePath $OutputFile -Append -Encoding utf8
    "---" | Out-File -FilePath $OutputFile -Append -Encoding utf8
    adb logcat -s auditd 2>$null | Select-String -Pattern $filter | ForEach-Object {
        $_.Line | Out-File -FilePath $OutputFile -Append -Encoding utf8
    }
} else {
    adb logcat -s auditd 2>$null | Select-String -Pattern $filter
}
