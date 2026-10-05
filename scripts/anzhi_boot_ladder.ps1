# =============================================================================
# 安知手机 —— 开机定位阶梯（boot ladder）
#
# 存在的唯一理由：ROM 编出来过很多次，但从没有一次留下"手机上到底发生了什么"。
# 这个脚本每一步都无条件把 fastboot/adb/logcat/pstore 落成文件，失败也有尸检报告。
#
# 用法（PowerShell 5.1）：
#   powershell -ExecutionPolicy Bypass -File scripts\anzhi_boot_ladder.ps1 -Rung info
#   powershell -ExecutionPolicy Bypass -File scripts\anzhi_boot_ladder.ps1 -Rung base      # 工厂包打底，会清空 userdata，必须手输 YES
#   powershell -ExecutionPolicy Bypass -File scripts\anzhi_boot_ladder.ps1 -Rung ours      # 刷我们这一套
#   powershell -ExecutionPolicy Bypass -File scripts\anzhi_boot_ladder.ps1 -Rung diagnose  # 不刷，只开机+抓日志
#
# 阶梯判读：
#   base 开机、ours 不开机  → 是我们 priv-app/sepolicy/配置把 system_server 拖死，看 logcat 第一段 fatal
#   base 都不开机           → 线/口/驱动/fastboot 层面，与我们的代码无关
#   ours 开机但功能缺        → 引导链通了，回到功能台账（BUILD.md §23）逐条清
#
# 不做的事：不解锁（会清整机数据，只报状态）、不删她树里的任何文件、不 setprop 令牌。
# =============================================================================

[CmdletBinding()]
param(
    [ValidateSet('info', 'base', 'ours', 'diagnose')] [string]$Rung = 'info',
    [ValidateSet('imgs', 'sideload')] [string]$Route = 'imgs',
    [string]$RomDir = '<forensics-root>',
    [string]$FactoryDir = 'E:\anzhi_phone_flash_tool\blue\bluejay-bp4a.251205.006',
    [int]$BootTimeoutSec = 420,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $RomDir "bootlogs\$stamp-$Rung"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$script:Transcript = Join-Path $runDir 'LADDER.txt'

function Log($m) {
    Write-Host $m
    [IO.File]::AppendAllText($script:Transcript, ("{0}  {1}`r`n" -f (Get-Date -Format 'HH:mm:ss'), $m), [Text.UTF8Encoding]::new($false))
}
function Step($m) { Log "[安知] $m" }
function Note($m) { Log "[提示] $m" }
function Save($name, $text) {
    $p = Join-Path $runDir $name
    [IO.File]::AppendAllText($p, "$text`r`n", [Text.UTF8Encoding]::new($false))
}
function Die($m) {
    Log "[停止] $m"
    Save 'RESULT.txt' "VERDICT=ABORTED rung=$Rung route=$Route`r`nreason=$m`r`nrunDir=$runDir"
    exit 1
}

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
if (-not $fastboot) { Die '找不到 fastboot.exe' }
if (-not $adb) { Die '找不到 adb.exe' }
Step "工具：$fastboot / $adb"
Step "阶梯=$Rung 路线=$Route 产物=$RomDir 证据目录=$runDir"

function Invoke-Native([string]$exe, [string[]]$a) {
    # fastboot/adb 把正常信息写到 stderr；EAP=Stop 下 PS5.1 会把它抛成终止错误，
    # 所以原生调用要在 Continue 里跑，退出码另外取。
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # 赋值形式捕获：& exe 2>&1 的结果若直接进管道，stderr 会被渲染成红色错误记录糊满日志
    $raw = & $exe @a 2>&1
    $script:LastRc = $LASTEXITCODE
    $ErrorActionPreference = $eap
    $flat = foreach ($o in $raw) {
        if ($o -is [System.Management.Automation.ErrorRecord]) { $o.Exception.Message } else { [string]$o }
    }
    return (($flat -join "`r`n") + "`r`n")
}
function Fb([string[]]$a) { Invoke-Native $fastboot $a }
function Ab([string[]]$a) { Invoke-Native $adb $a }
function Fbx([string[]]$a) { Invoke-Native $fastboot $a | Out-Null }

function Fastboot-There { return (Fb @('devices')) -match '[0-9A-Za-z_-]+\s+fastboot' }
function Mode {
    if (-not (Fastboot-There)) { return 'none' }
    $u = (Fb @('getvar', 'is-userspace'))
    if ($u -match 'is-userspace:\s*yes') { return 'fastbootd' }
    return 'bootloader'
}

# ── 0. 现状取证（任何 rung 都先跑，只读）──────────────────────
function Take-FastbootFacts([string]$tag) {
    if (-not (Fastboot-There)) { Log "[$tag] fastboot 里没有设备"; return }
    $all = Fb @('getvar', 'all')
    Save "fastboot-getvar-$tag.txt" $all
    if ($all -match 'no link|unable to|FAILED') {
        Note "[$tag] fastboot 能列出设备但事务失败（no link）。这是 USB 链路层，不是 ROM 问题：换数据线 / 换到主板后置 USB2.0 口（别走集线器和前面板）/ 手机重新长按 音量下+电源 回 fastboot / 设备管理器里把该设备的驱动换成 WinUSB。"
    }
    foreach ($k in @('current-slot', 'unlocked', 'product', 'variant', 'version-bootloader', 'version-baseband', 'is-userspace', 'max-download-size')) {
        if ($all -match "(?m)^$k\s*:\s*(.+?)\s*$") { Log ("[{0}] {1} = {2}" -f $tag, $k, $Matches[1]) }
    }
}

Take-FastbootFacts 'pre'
$adbList = Ab @('devices', '-l')
if ($adbList -notmatch '^\s*$') { Save 'adb-devices.txt' $adbList; Step ("adb 侧：" + ($adbList -split "`n"[0])) }

if ($Rung -eq 'info') {
    if ((Fastboot-There)) {
        Step '设备在 fastboot。本 rung 不写入任何分区。'
    } else {
        Note 'fastboot 侧无设备。若想同时看系统状态：adb reboot bootloader 或 关机后按住 音量下+电源。'
    }
    $sys = Ab @('shell', 'getprop', 'ro.build.display.id')
    if ($sys.Trim()) { Step "当前系统：$($sys.Trim())" }
    $slot = Ab @('shell', 'getprop', 'ro.boot.slot_suffix')
    if ($slot.Trim()) { Step "当前槽位：$($slot.Trim())" }
    Save 'RESULT.txt' "VERDICT=INFO_ONLY runDir=$runDir"
    Step 'info 结束，未触碰设备。'
    exit 0
}

# ── 1. 产物清点：尺寸 + 与本轮构建对齐 ────────────────────────
$needBoot = @('boot', 'vendor_boot', 'dtbo', 'vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'pvmfw')
$needLogi = @('system', 'system_ext', 'product', 'vendor', 'vendor_dlkm')
foreach ($p in ($needBoot + $needLogi)) {
    $f = Join-Path $RomDir "$p.img"
    if (-not (Test-Path $f)) { Die "缺 $p.img —— 先跑 WSL 里那条同步，别拿半套上机" }
}
$list = foreach ($p in ($needBoot + $needLogi)) {
    $f = Join-Path $RomDir "$p.img"
    "{0,14}  {1}" -f (Get-Item $f).Length, $p
}
Save 'artifact-sizes.txt' ($list -join "`r`n")
Step ("产物齐备：boot 侧 " + ($needBoot -join ',') + " / 逻辑 " + ($needLogi -join ','))

$zip = Get-ChildItem -Path $RomDir -Filter 'lineage-23.2-20261004-*-bluejay.zip' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($Route -eq 'sideload' -and -not $zip) { Die 'sideload 路线需要 20261004 的 zip，产物目录里没有' }
if ($zip) { Step "zip：$($zip.Name)（$([math]::Round($zip.Length/1MB)) MB）" }

# ── 2. 工厂打底（唯一会清 userdata 的一步）────────────────────
if ($Rung -eq 'base') {
    $bl = Get-ChildItem -Path $FactoryDir -Filter 'bootloader-*.img' | Select-Object -First 1
    $rd = Get-ChildItem -Path $FactoryDir -Filter 'radio-*.img' | Select-Object -First 1
    $imz = Get-ChildItem -Path $FactoryDir -Filter 'image-bluejay-*.zip' | Select-Object -First 1
    if (-not ($bl -and $rd -and $imz)) { Die "工厂包不齐：$FactoryDir" }
    if (-not (Fastboot-There)) { Die '打底需要设备在 bootloader（普通 fastboot，不是 fastbootd）' }
    $ul = Fb @('getvar', 'unlocked')
    if ($ul -notmatch 'unlocked:\s*yes') { Die "bootloader 未解锁（$($ul.Trim())）。解锁我不代做。" }
    $warn = 'base 会执行 fastboot -w update：格式化 userdata + 把整机回到原厂 BP4A。你手机上的数据、聊天、照片会全没（ROM 里的安知本来也要重刷）。'
    Log "[警告] $warn"
    if (-not $Yes) {
        $ans = Read-Host '确定要回到原厂打底？输入 YES'
        if ($ans -cne 'YES') { Die '已取消，未写入任何分区' }
    }
    Step "fastboot flash bootloader $($bl.Name)"
    $r = Fb @('flash', 'bootloader', $bl.FullName); Save 'base-flash.txt' $r
    if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:') { Die "刷 bootloader 失败 rc=$($script:LastRc)：$($r.Trim())" }
    Fbx @('reboot-bootloader'); Start-Sleep 6
    if (-not (Fastboot-There)) { Die '回 bootloader 后设备不见了' }
    Step "fastboot flash radio $($rd.Name)"
    $r = Fb @('flash', 'radio', $rd.FullName); Save 'base-flash.txt' $r
    if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:') { Die "刷 radio 失败 rc=$($script:LastRc)：$($r.Trim())" }
    Fbx @('reboot-bootloader'); Start-Sleep 6
    Step "fastboot -w update $($imz.Name)（这步最长，几分钟）"
    $r = Fb @('-w', 'update', $imz.FullName); Save 'base-flash.txt' $r
    if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:') { Die "打底 update 失败 rc=$($script:LastRc)：$($r.Trim())" }
    Step '打底完成，交给开机取证环节。'
}

# ── 3. 刷我们这一套 ─────────────────────────────────────────
if ($Rung -eq 'ours') {
    if ($Route -eq 'imgs') {
        # 3a. bootloader 侧：vbmeta 三片 + boot 侧
        if (-not (Fastboot-There)) { Die '设备不在 fastboot（bootloader/fastbootd 都行，脚本会自己切）' }
        if ((Mode) -eq 'fastbootd') { Fbx @('reboot', 'bootloader'); Start-Sleep 8 }
        Take-FastbootFacts 'before-ours-boot'
        foreach ($p in @('vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'boot', 'vendor_boot', 'dtbo', 'pvmfw')) {
            $r = Fb @('--disable-verity', '--disable-verification', 'flash', $p, (Join-Path $RomDir "$p.img"))
            Save "flash-$p.txt" $r
            if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:') { Die "刷 $p 失败 rc=$($script:LastRc)：$($r.Trim())" }
            Step "刷入 $p ✓"
        }
        # 3b. fastbootd 侧：5 个逻辑分区
        Step '进 fastbootd（动态分区要在用户态刷）'
        Fbx @('reboot', 'fastboot'); Start-Sleep 10
        if ((Mode) -ne 'fastbootd') { Take-FastbootFacts 'stuck' ; Die '没进 fastbootd。手动：bootloader 里音量键选 Fastboot 再重跑 -Rung ours' }
        Take-FastbootFacts 'before-ours-logical'
        foreach ($p in $needLogi) {
            $r = Fb @('flash', $p, (Join-Path $RomDir "$p.img"))
            Save "flash-$p.txt" $r
            if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:|not enough|No space') { Die "刷 $p 失败 rc=$($script:LastRc)（若是 not enough/No space，就是动态分区容量不够，日志已留）：$($r.Trim())" }
            Step "刷入 $p ✓"
        }
    } else {
        # sideload：只刷 boot 侧，逻辑分区交给 update_engine（官方 Lineage 安装路径）
        if ((Mode) -eq 'fastbootd') { Fbx @('reboot', 'bootloader'); Start-Sleep 8 }
        foreach ($p in @('vbmeta', 'vbmeta_system', 'vbmeta_vendor', 'boot', 'vendor_boot', 'dtbo')) {
            $r = Fb @('--disable-verity', '--disable-verification', 'flash', $p, (Join-Path $RomDir "$p.img"))
            Save "flash-$p.txt" $r
            if ($script:LastRc -ne 0 -or $r -match 'FAILED|error:') { Die "刷 $p 失败 rc=$($script:LastRc)：$($r.Trim())" }
            Step "刷入 $p ✓"
        }
        Fbx @('reboot', 'bootloader'); Start-Sleep 6
        $rec = (Ab @('reboot', 'recovery') 2>&1 | Out-String)
        Save 'reboot-recovery.txt' $rec
        Note '请在 recovery 里选 “Apply update from ADB”（音量键移动，电源键确认）。选好后本脚本会继续。'
        $deadline = (Get-Date).AddSeconds(180)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep 5
            $sd = Ab @('devices')
            if ($sd -notmatch '^\s*$') { break }
        }
        $sl = (Ab @('sideload', $zip.FullName) 2>&1 | Out-String)
        Save 'sideload.txt' $sl
        if ($sl -match 'Failure|error') { Die "sideload 失败，日志在 $runDir\sideload.txt" }
        Step 'sideload 完成'
    }
}

# ── 4. 开机 + 无条件抓日志 ──────────────────────────────────
if ($Rung -in @('base', 'ours')) {
    Step 'fastboot reboot，开始抓 logcat'
    Fbx @('reboot')
}
$dev = Ab @('wait-for-device') 2>&1 | Out-Null
$adbNow = Ab @('devices')
if ($adbNow -match '\sdevice\b') {
    Step ('adb 起来了：' + (($adbNow -split "`r?`n" | Where-Object { $_.Trim() }) -join ' | '))
} else {
    Note "adb 侧状态：$($adbNow.Trim())"
}

$lcFile = Join-Path $runDir 'logcat.txt'
$lc = $null
try {
    $lc = Start-Process -FilePath $adb -ArgumentList @('logcat', '-b', 'all', '-v', 'threadtime') `
        -RedirectStandardOutput $lcFile -RedirectStandardError (Join-Path $runDir 'logcat-err.txt') `
        -NoNewWindow -PassThru
    Step "logcat 进程 PID=$($lc.Id) → $lcFile"
} catch { Note "logcat 起不来：$_" }

$booted = $false; $t0 = Get-Date
$samples = @()
while (((Get-Date) - $t0).TotalSeconds -lt $BootTimeoutSec) {
    Start-Sleep 10
    $bc = (Ab @('shell', 'getprop', 'sys.boot_completed')).Trim()
    $rb = (Ab @('shell', 'getprop', 'ro.boot.bootreason')).Trim()
    $samples += "{0}  boot_completed='{1}' bootreason='{2}' dev='{3}'" -f (Get-Date -Format HH:mm:ss), $bc, $rb, ((Ab @('devices')).Trim() -replace "`r|`n", ' | ')
    if ($bc -eq '1') { $booted = true; break }
}
Save 'boot-samples.txt' ($samples -join "`r`n")

if ($lc) { Start-Sleep 3; try { $lc.Kill(); $lc.WaitForExit(5000) | Out-Null } catch { Note "logcat 进程收尾：$_" } }
Save 'dmesg.txt' (Ab @('shell', 'dmesg'))
Save 'pstore-console-ramoops.txt' (Ab @('shell', 'sh', '-c', 'ls /sys/fs/pstore/ 2>&1; for f in /sys/fs/pstore/*; do echo "== $f =="; cat "$f"; done 2>&1'))
# 截图必须走文件重定向：PS 5.1 管道会把 stdout 当文本解码，png 字节会坏
try {
    $shot = Join-Path $runDir 'screen.png'
    $sp = Start-Process -FilePath $adb -ArgumentList @('exec-out', 'screencap', '-p') `
        -RedirectStandardOutput $shot -NoNewWindow -PassThru
    if (-not $sp.WaitForExit(15000)) { try { $sp.Kill() } catch { } }
    if (-not (Test-Path $shot) -or (Get-Item $shot).Length -lt 1000) { Note '截图为空（framework 没起来时正常）' }
} catch { Note "截图失败：$_" }
Save 'prop-dump.txt' (Ab @('shell', 'getprop'))
Save 'packages.txt' (Ab @('shell', 'pm', 'list', 'packages', '-f', 'com.anzhi.os', 'com.anzhi.core'))

# ── 5. 尸检：把最可能的死因直接摘出来 ────────────────────────
$hits = @()
if (Test-Path $lcFile) {
    $pat = 'FATAL|ANR |Process .* has died|SystemServer|SystemConfig|privapp-permissions|avb|dm-verity|FAILURE|denied|init: cannot|Service ... .* did not start|bootloop'
    $hits = (Select-String -Path $lcFile -Pattern $pat -AllMatches | Select-Object -First 120 | ForEach-Object { $_.Line })
    Save 'logcat-hits.txt' ($hits -join "`r`n")
}
$logLines = 0; if (Test-Path $lcFile) { $logLines = (Get-Content $lcFile -ReadCount 1000 | Measure-Object -Line).Lines }

$verdict = if ($booted) { 'BOOTED' } elseif ($logLines -gt 0) { 'HALF-UP (adbd 活着，framework 没起来)' } else { 'NO-ADB (内核/引导层面)' }
$res = @(
    "VERDICT=$verdict"
    "rung=$Rung route=$Route booted=$booted logcat_lines=$logLines"
    "waited=$(( [int]((Get-Date) - $t0).TotalSeconds ))s boot_completed_seen='$($samples[-1])'"
    "artifacts: $runDir"
    "下一步判读：boot 成功→回功能台账；HALF-UP→看 logcat-hits.txt 第一段 FATAL/SystemConfig/denied；NO-ADB→先用 -Rung base 验原厂能不能开机。"
) -join "`r`n"
Save 'RESULT.txt' $res
Log ''
Log "======== $verdict ========"
Log "证据目录：$runDir"
foreach ($h in ($hits | Select-Object -First 12)) { Log "  $h" }
