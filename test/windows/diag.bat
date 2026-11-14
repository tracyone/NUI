@echo off
setlocal
chcp 65001 >nul
rem ============================================================
rem  Diagnostics for NUI GPS mock on Windows
rem  Run: diag.bat    Result: diag.txt in the same folder
rem ============================================================
set "SERIAL="
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do if not defined SERIAL if "%%b"=="device" set "SERIAL=%%a"
set "ADB=adb"
if defined SERIAL set "ADB=adb -s %SERIAL%"
set "PKG=com.nui.gpsmock"

(
echo ========== 1. devices ==========
adb devices
echo.
echo ========== 2. android version / abi ==========
%ADB% shell getprop ro.build.version.sdk
%ADB% shell getprop ro.build.version.release
%ADB% shell getprop ro.product.cpu.abi
echo.
echo ========== 3. package installed? ==========
%ADB% shell pm list packages ^| findstr %PKG%
echo.
echo ========== 4. mock permission ==========
%ADB% shell appops get %PKG% android:mock_location
%ADB% shell settings get secure mock_location
echo.
echo ========== 5. service process ==========
%ADB% shell "ps ^| grep gpsmock"
echo.
echo ========== 6. last known locations ==========
%ADB% shell dumpsys location ^| findstr /i "last location"
echo.
echo ========== 7. app log (NUI.GpsMock) ==========
%ADB% logcat -d -s NUI.GpsMock:V *:S
echo.
echo ========== 8. anything about gpsmock ==========
%ADB% logcat -d ^| findstr /i "gpsmock"
) > "%~dp0diag.txt" 2>&1

type "%~dp0diag.txt"
echo.
echo Saved to diag.txt
endlocal
