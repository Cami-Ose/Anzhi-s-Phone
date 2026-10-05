@echo off
chcp 65001 >nul
set FB=D:\adb\platform-tools\fastboot.exe
set ADB=D:\adb\platform-tools\adb.exe
set ROM=C:\Anzhi's Phone\rom\stock

echo ===== 接力脚本：stock boot → ADB → fastbootd → flash system =====
echo 把手机放到一边，它会自动循环等设备出现
echo 不需要你操作按键
echo.

:retry_boot
cls
echo [等待 fastboot 设备...]
%FB% wait-for-device 2>nul
echo [检测到 fastboot！正在 boot stock 内核...]
%FB% boot "%ROM%\boot.img" 2>nul

echo [等待 ADB 出现 (约5-10秒)...]
%ADB% wait-for-device 2>nul
echo [ADB 已连接！进入 fastbootd...]
%ADB% reboot fastboot 2>nul

echo [等待 fastbootd 设备...]
%FB% wait-for-device 2>nul
timeout /t 2 /nobreak >nul

echo [刷入 stock system.img...]
%FB% flash system "%ROM%\system.img"
if %errorlevel% neq 0 (
    echo system 刷写失败，重试...
    timeout /t 2 /nobreak >nul
    goto retry_boot
)
echo [OK！system 刷写成功]

echo [刷入 stock vendor.img...]
%FB% flash vendor "%ROM%\vendor.img"
if %errorlevel% neq 0 (
    echo vendor 可选跳过
)

echo [刷入 stock product.img...]
%FB% flash product "%ROM%\product.img"
if %errorlevel% neq 0 (
    echo product 可选跳过
)

echo.
echo ===== 全部完成！重启 =====
%FB% reboot
pause
