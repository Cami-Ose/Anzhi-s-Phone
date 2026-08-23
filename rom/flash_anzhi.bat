@echo off
REM ============================================================================
REM 安知手机 刷机脚本 · v2.0
REM 用法：
REM   flash_anzhi.bat         正常刷机（保留数据）
REM   flash_anzhi.bat -w      刷机 + 清数据（首次刷机建议）
REM
REM 前提：手机已进入 bootloader（fastboot）模式
REM 流程：boot/dtbo/vendor_boot → fastbootd → system/vendor/product/system_ext/vbmeta
REM ============================================================================

setlocal enabledelayedexpansion

set "IMG_DIR=%~dp0"
set "WIPE_MODE="

REM ── 解析参数 ──
if /i "%1"=="-w" set "WIPE_MODE=-w"
if /i "%1"=="/w" set "WIPE_MODE=-w"

echo ========================================
echo  安知手机 Flash Script v2.0
echo  目标设备：Pixel 6a (bluejay)
echo ========================================
echo.

REM ── Step 1: 检查设备 ──
echo [1/7] 检查设备连接...
fastboot devices 2>nul | findstr /r /c:"fastboot" >nul
if %errorlevel% neq 0 (
    echo 错误：未检测到 fastboot 设备！
    echo 请确保手机已进入 bootloader 模式并连接电脑。
    pause
    exit /b 1
)
echo   设备已连接 ✓
echo.

REM ── Step 2: 刷 boot（bootloader fastboot）──
echo [2/7] 刷 boot.img（安知内核）...
fastboot flash boot "%IMG_DIR%boot.img"
if %errorlevel% neq 0 (
    echo 错误：boot.img 刷写失败！
    pause
    exit /b 1
)
echo   boot.img ✓
echo.

REM ── Step 3: 刷 dtbo + vendor_boot（bootloader fastboot）──
echo [3/7] 刷 dtbo.img + vendor_boot.img...
fastboot flash dtbo "%IMG_DIR%official\dtbo.img"
if %errorlevel% neq 0 (
    echo 警告：dtbo.img 刷写失败（可能文件不存在）
) else (
    echo   dtbo.img ✓
)

fastboot flash vendor_boot "%IMG_DIR%official\vendor_boot.img"
if %errorlevel% neq 0 (
    echo 警告：vendor_boot.img 刷写失败（可能文件不存在）
) else (
    echo   vendor_boot.img ✓
)
echo.

REM ── Step 4: 进入 fastbootd（动态分区模式）──
echo [4/7] 进入 fastbootd（动态分区模式）...
fastboot reboot fastboot
if %errorlevel% neq 0 (
    echo 错误：无法进入 fastbootd 模式！
    pause
    exit /b 1
)

REM 等待设备在 fastbootd 下重新就绪
fastboot wait-for-device
echo   fastbootd 模式 ✓
echo.

REM ── Step 5: 刷动态分区（fastbootd）──
echo [5/7] 刷系统分区（fastbootd）...

if exist "%IMG_DIR%system.img" (
    echo   刷 system.img...
    fastboot flash system "%IMG_DIR%system.img"
    if !errorlevel! neq 0 (echo 警告：system.img 刷写失败) else (echo   system.img ✓)
) else (
    echo   跳过 system.img（不存在）
)

if exist "%IMG_DIR%vendor.img" (
    echo   刷 vendor.img...
    fastboot flash vendor "%IMG_DIR%vendor.img"
    if !errorlevel! neq 0 (echo 警告：vendor.img 刷写失败) else (echo   vendor.img ✓)
) else (
    echo   跳过 vendor.img（不存在）
)

if exist "%IMG_DIR%product.img" (
    echo   刷 product.img...
    fastboot flash product "%IMG_DIR%product.img"
    if !errorlevel! neq 0 (echo 警告：product.img 刷写失败) else (echo   product.img ✓)
) else (
    echo   跳过 product.img（不存在）
)

if exist "%IMG_DIR%system_ext.img" (
    echo   刷 system_ext.img...
    fastboot flash system_ext "%IMG_DIR%system_ext.img"
    if !errorlevel! neq 0 (echo 警告：system_ext.img 刷写失败) else (echo   system_ext.img ✓)
) else (
    echo   跳过 system_ext.img（不存在）
)

if exist "%IMG_DIR%vbmeta.img" (
    echo   刷 vbmeta.img（禁用 AVB 防验证失败）...
    fastboot flash vbmeta "%IMG_DIR%vbmeta.img"
    if !errorlevel! neq 0 (echo 警告：vbmeta.img 刷写失败) else (echo   vbmeta.img ✓)
) else (
    echo   跳过 vbmeta.img（不存在）
)

echo   系统分区刷写完成 ✓
echo.

REM ── Step 6: 清数据（可选）──
if defined WIPE_MODE (
    echo [6/7] 清空用户数据（-w 模式）...
    fastboot -w
    if !errorlevel! neq 0 (
        echo 警告：数据清除失败
    ) else (
        echo   用户数据已清除 ✓
    )
) else (
    echo [6/7] 跳过清数据（保留用户数据）
    echo   提示：首次刷机建议加 -w 参数清除数据防兼容问题
)
echo.

REM ── Step 7: 重启 ──
echo [7/7] 刷机完成！重启设备...
fastboot reboot
if %errorlevel% neq 0 (
    echo 错误：重启指令发送失败！请手动重启。
    pause
    exit /b 1
)

echo.
echo ========================================
echo  刷机流程全部完成！
echo  设备正在重启，首次开机可能需要 5-10 分钟
echo ========================================
echo.
echo  验证命令：
echo    adb shell ls -l /system_ext/priv-app/AnzhiOS/
echo    adb shell getenforce
echo    adb shell cmd netpolicy list restrict-background-whitelist ^| findstr anzhi
echo.

pause
