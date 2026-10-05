# =============================================================================
# DEPRECATED 2026-10-03: ROM no longer bakes the token; nothing reads this file anymore. The token is now typed in-app (chat sidebar -> VPS settings) and stored in app-private prefs. Keep only if you want a local copy; safe to delete.
# Anzhi's Phone — 一次性取回本机令牌
#
# 为什么要有这个脚本：环境的安全策略按「动作」拦截我这侧任何读凭据的行为
# （ssh 到 VPS 抓 ANZHI_API_TOKEN、读 <user-store> 都被挡下）。
# 所以这一步由你在自己的终端执行——密钥只走「你的终端 → 本机文件 → ROM/adb」，
# 不经过聊天，也不进 git。
#
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File scripts\fetch_anzhi_token.ps1
#
# 之后 scripts\flash_rom.ps1 会自动读这个文件，在刷机后注入 persist.anzhi.token；
# scripts\build_rom.sh --deploy 会把它编进 ROM（写进 WSL 树那份 device.mk，不写仓库）。
# =============================================================================

$ErrorActionPreference = 'Stop'

$dest = Join-Path $env:USERPROFILE '<local-token-file>'
$keyName = 'ANZHI_API_TOKEN'
$remoteFile = '<vps-secrets-file>'

function Die($m) { Write-Host "[停止] $m" -ForegroundColor Red; exit 1 }

# 已有值就不覆盖，避免反复覆盖掉一份好令牌
if (Test-Path $dest) {
    $cur = Get-Content $dest | Where-Object { $_ -match "^$keyName=" } | Select-Object -First 1
    if ($cur) {
        $curVal = ($cur -split '=', 2)[1].Trim()
        Write-Host "[已存在] $dest 里已有 $keyName（长度 $($curVal.Length)）。要覆盖请先删掉该文件。" -ForegroundColor Yellow
        exit 0
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null

Write-Host "[安知] 从 VPS 取 $keyName —— 只写文件，屏幕不显示正文" -ForegroundColor Green
# 注意：PS 5.1 的 `>` 默认写成 UTF-16LE（带 FF FE BOM），WSL 侧 grep 读不到这行，
# build_rom.sh 的注入会静默失败。所以显式用 ASCII 写。
$remote = ssh -i "$env:USERPROFILE\.ssh\id_rsa" -o IdentitiesOnly=yes -o ConnectTimeout=20 vps "grep -m1 '^$keyName=' $remoteFile | tr -d '\r'"
if ($LASTEXITCODE -ne 0) { Die "ssh 取值失败（exit=$LASTEXITCODE）。检查 ~/.ssh/config 里的 vps 别名与私钥" }

$line = $remote | Where-Object { $_ -match "^$keyName=" } | Select-Object -First 1
if (-not $line) { Die "$remoteFile 里没有 $keyName= 这一行，确认密钥位置（见 <user-memory-store>" }

$val = (($line -split '=', 2)[1]).Trim()
if ($val.Length -lt 8) { Die "取到的值长度只有 $($val.Length)，疑似被截断，请手动核对文件内容" }

[System.IO.File]::WriteAllText($dest, "$keyName=$val`n", [System.Text.Encoding]::ASCII)

Write-Host "[完成] $dest 已写入 $keyName（长度 $($val.Length)，正文未显示）" -ForegroundColor Green
Write-Host '下一步：powershell -ExecutionPolicy Bypass -File scripts\flash_rom.ps1'
