# =============================================================================
# Anzhi's Phone — 刷机脚本（A/B + dynamic partitions 版）
#
# 替代 <local-path> 7 月混编产物，会 bootloop）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\flash_rom.ps1              # 交互确认
#   powershell -ExecutionPolicy Bypass -File scripts\flash_rom.ps1 -Yes         # 免确认（仍会检查解锁状态）
#   ... -DisableVerity                                                          # 额外关掉 AVB 校验（改过 vendor 时才用）
#
# 前提：
#   1. 手机已 `fastboot flashing unlock`（脚本会检查，未解锁直接停手）
#   2. 产物齐备：boot/vendor_boot/dtbo/vbmeta.img + super 内的逻辑分区 img
#      只有 OTA zip 没有 img 时，脚本会打印 recovery sideload 命令，不代跑
#   3. VPS 接口令牌：本脚本不碰（属性是任何 app 可读的明文）。刷完由她在应用设置界面填写。
# =============================================================================

[CmdletBinding()]
param(
    [string]$RomDir = '<forensics-root>',
    [string]$ApiUrl = 'https://chat.example.com',
    [switch]$Yes,
    [switch]$DisableVerity
)

$ErrorActionPreference = 'Stop'

function Step($m)  { Write-Host "[安知] $m" -ForegroundColor Green }
function Note($m)  { Write-Host "[提示] $m" -ForegroundColor Yellow }
function Die($m)   { Write-Host "[停止] $m" -ForegroundColor Red; exit 1 }

# ── 1. 找 adb / fastboot ────────────────────────────────
function Resolve-Tool([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($dir in @('D:\adb\platform-tools', 'D:\adb', "$env:LOCALAPPDATA\Android\Sdk\platform-tools")) {
        $p = Join-Path $dir "$name.exe"
        if (Test-Path $p) { return $p }
    }
    return $null
}

$fastboot = Resolve-Tool 'fastboot'
$adb = Resolve-Tool 'adb'
if (-not $fastboot) { Die '找不到 fastboot.exe，请装 platform-tools 或加进 PATH' }
if (-not $adb) { Die '找不到 adb.exe，请装 platform-tools 或加进 PATH' }
Step "工具：$fastboot / $adb"

# ── 2. 清点产物 ─────────────────────────────────────────
if (-not (Test-Path $RomDir)) { Die "产物目录不存在：$RomDir" }

$logical = @('system', 'system_ext', 'product', 'odm', 'vendor', 'vendor_dlkm', 'system_dlkm') |
    Where-Object { Test-Path (Join-Path $RomDir "$_.img") }
$slotA = @('boot', 'vendor_boot', 'dtbo', 'vbmeta') |
    Where-Object { Test-Path (Join-Path $RomDir "$_.img") }
$otaZip = Get-ChildItem -Path $RomDir -Filter 'lineage-*.zip' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($logical.Count -eq 0 -and $slotA.Count -eq 0) {
    Die "$RomDir 里没有可刷的 img。先跑 scripts\build_rom.sh --full，再把 out/target/product/bluejay 下产物同步过来"
}
Step ("逻辑分区 img：" + ($(if ($logical) { $logical -join ', ' } else { '（无）' })))
Step ("boot 侧 img：" + ($slotA -join ', '))
if ($otaZip) { Step "OTA zip：$($otaZip.Name)（$([math]::Round($otaZip.Length/1MB)) MB）" }

# ── 3. 设备与解锁检查 ───────────────────────────────────
$devLines = & $fastboot devices 2>&1 | Where-Object { "$_".Trim() }
if (-not $devLines) {
    Die 'fastboot 模式下没看到设备。手机关机 → 按住 音量下 + 电源 进 bootloader，再重跑本脚本'
}
Step "fastboot 设备：$($devLines -join ' | ')"

$unlocked = & $fastboot getvar unlocked 2>&1 | Out-String
if ($unlocked -notmatch 'unlocked:\s*yes') {
    Die "bootloader 未解锁（getvar unlocked 返回：$($unlocked.Trim())）。解锁会清空整机数据，我不会代你做，请自行执行 fastboot flashing unlock"
}
Step 'bootloader 已解锁 ✓'

# ── 4. 确认 ────────────────────────────────────────────
Write-Host ''
Write-Host '将执行：' -ForegroundColor Cyan
if ($logical) { Write-Host ("  fastbootd 刷入：" + ($logical -join ', ')) }
if ($slotA) { Write-Host ("  bootloader 刷入：" + ($slotA -join ', ')) }
Write-Host '  然后重启进系统，兜底注入 persist.vendor.anzhi.api_url（令牌不注入，由应用界面填）'
Write-Host ''
if (-not $Yes) {
    $ans = Read-Host '确认开始刷机？整机数据会被覆盖，输入 YES 继续'
    if ($ans -cne 'YES') { Die '已取消，未触碰设备' }
}

# ── 5. fastbootd 刷逻辑分区 ─────────────────────────────
if ($logical) {
    Step '重启进 fastbootd（音量键可手动选 "Recovery/Fastboot"）...'
    & $fastboot reboot fastboot 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    $inFastd = & $fastboot devices 2>&1 | Where-Object { "$_".Trim() }
    if (-not $inFastd) { Die 'fastbootd 里没看到设备。请手动开机到 fastbootd（bootloader → 音量键选 Fastboot）后重跑' }
    foreach ($p in $logical) {
        Step "fastboot flash $p"
        & $fastboot flash $p (Join-Path $RomDir "$p.img")
        if ($LASTEXITCODE -ne 0) { Die "刷 $p 失败（exit=$LASTEXITCODE）——停手，不清引导，可安全重试" }
    }
}

# ── 6. bootloader 刷 boot 侧 ────────────────────────────
Step '回 bootloader 刷 boot 侧分区...'
& $fastboot reboot bootloader 2>&1 | Out-Null
Start-Sleep -Seconds 8
if (-not (& $fastboot devices 2>&1 | Where-Object { "$_".Trim() })) {
    Die 'bootloader 里没看到设备，请手动回到 bootloader 后重跑第 6 步'
}
foreach ($p in $slotA) {
    $args = @('flash', $p, (Join-Path $RomDir "$p.img"))
    if ($p -eq 'vbmeta' -and $DisableVerity) { $args = @('--disable-verity', '--disable-verification') + $args }
    Step ('fastboot ' + ($args -join ' '))
    & $fastboot @args
    if ($LASTEXITCODE -ne 0) { Die "刷 $p 失败（exit=$LASTEXITCODE）" }
}

Step 'fastboot reboot'
& $fastboot reboot 2>&1 | Out-Null

# ── 7. 进系统后注入 VPS 配置 ────────────────────────────
Step '等设备安装开机（最长 4 分钟）...'
& $adb wait-for-device 2>&1 | Out-Null
$booted = $false
for ($i = 0; $i -lt 24; $i++) {
    Start-Sleep -Seconds 10
    if ("$(& $adb shell getprop sys.boot_completed 2>&1)".Trim() -eq '1') { $booted = true; break }
}
if (-not $booted) { Die '4 分钟内没等到 boot_completed=1——先按 §十 救砖清单排查，别急着重刷' }
Step '系统已起来 ✓'

& $adb shell setprop persist.vendor.anzhi.api_url $ApiUrl
$back = (& $adb shell getprop persist.vendor.anzhi.api_url 2>&1 | Out-String).Trim()
if ($back -eq $ApiUrl) { Step "persist.vendor.anzhi.api_url = $ApiUrl ✓ 已生效" }
else { Note "persist.vendor.anzhi.api_url 回读为「$back」——setprop 没落地（多半 SELinux 拦 shell 写 vendor 属性）。设备树已把出厂默认写进 vendor/build.prop，界面填写优先，此项不影响使用" }

# VPS 接口令牌不由本脚本处理（2026-10-03 Cami 定）：不读凭据文件、不 setprop。
# 属性是任何 app `getprop` 都能读的明文，令牌只该存在应用私有的 SharedPreferences 里，
# 由她在应用设置界面填一次。

# ── 8. 权限管道自检 ─────────────────────────────────────
Step '自检：安知组件与权限管道'
$checks = [ordered]@{
    'AnzhiOS 装在 system_ext' = 'ls /system_ext/priv-app/AnzhiOS/AnzhiOS.apk'
    'privapp allowlist 在位' = 'ls /system_ext/etc/permissions/privapp-permissions-com.anzhi.os.xml'
    '安知进程已起' = 'pidof com.anzhi.os'
    '无障碍自启已写入' = 'settings get secure enabled_accessibility_services'
    '无障碍总闸' = 'settings get secure accessibility_enabled'
    '通知助理默认值' = 'dumpsys notification_manager | grep -i anzhi | head -3'
}
foreach ($k in $checks.Keys) {
    $out = (& $adb shell $checks[$k] 2>&1 | Out-String).Trim()
    if ($out -and $out -notmatch 'No such file|not found|^$') { Write-Host "  ✓ $k → $out" }
    else { Write-Host "  ✗ $k → 空/缺失" -ForegroundColor Yellow }
}

# 令牌必须是空的：这轮之前 build_rom.sh 会把它烤进 /system/build.prop（世界可读）。
# 现在改由她在应用设置界面填，属性只留作开发期 adb 兜底。
$baked = (& $adb shell getprop persist.vendor.anzhi.token 2>&1 | Out-String).Trim()
if ($baked) {
    Note 'persist.vendor.anzhi.token 在机上非空 —— 这轮镜像仍带着烤进去的令牌。'
    Note '  删法：改 device.mk 去掉该属性后重编；令牌正文不落任何文件、不打印。'
    Note '  应用侧已是「界面值优先」，填过一次即以界面为准。'
} else {
    Step 'persist.vendor.anzhi.token 为空 ✓ 令牌没被烤进镜像'
}

Write-Host ''
Step '刷机流程结束。VPS 连不通时先跑：adb logcat -s AnzhiChatActivity VpsBrainProvider AnzhiBootReceiver'
