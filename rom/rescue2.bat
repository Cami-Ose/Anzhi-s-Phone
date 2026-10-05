chcp 437 >nul
set FB=D:\adb\platform-tools\fastboot.exe
set ADB=D:\adb\platform-tools\adb.exe
set STOCK=C:\Anzhi's Phone\rom\stock

:loop
%FB% wait-for-device
%FB% boot "%STOCK%\boot.img"
%ADB% wait-for-device
%ADB% reboot fastboot
%FB% wait-for-device
timeout /t 3 /nobreak >nul
%FB% flash system "%STOCK%\system.img"
if errorlevel 1 goto loop
echo SYSTEM_DONE
%FB% flash vendor "%STOCK%\vendor.img"
%FB% flash product "%STOCK%\product.img"
%FB% reboot
echo ALL_DONE
pause
