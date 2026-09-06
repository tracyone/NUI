@echo off
setlocal

rem ===========================================================
rem  NUI Launcher (Car) - Deploy & Logcat Capture
rem  Pattern based on soda_deploy.bat
rem
rem  Usage:
rem    nui_deploy.bat              -> arm32 + debug (32-bit, default)
rem    nui_deploy.bat arm64        -> arm64 + debug
rem    nui_deploy.bat release      -> arm32 + release (car, com.nui.launcher)
rem    nui_deploy.bat arm64 release-> arm64 + release
rem
rem  NOTE: uninstall/clear ALWAYS targets the explicit PKG_NAME of the
rem  selected build (debug = com.nui.launcher.debug, release = com.nui.launcher).
rem  It will NEVER touch other apps.
rem ===========================================================

rem --- config ---
set ADB_TARGET=-s 127.0.0.1:5555
set FLAVOR=arm32
set BUILD=debug
if /I "%1"=="arm32" set FLAVOR=arm32
if /I "%1"=="arm64" set FLAVOR=arm64
if /I "%1"=="release" set BUILD=release
if /I "%2"=="arm32" set FLAVOR=arm32
if /I "%2"=="arm64" set FLAVOR=arm64
if /I "%2"=="release" set BUILD=release
if /I "%1"=="debug" set BUILD=debug
if /I "%2"=="debug" set BUILD=debug

if /I "%BUILD%"=="release" (
    set PKG_NAME=com.nui.launcher
) else (
    set PKG_NAME=com.nui.launcher.debug
)
set MAIN_ACTIVITY=%PKG_NAME%/com.nui.launcher.MainActivity
set LOG_FILE=/mnt/sdcard/nui_launcher.log
set APK_SRC=app\build\outputs\apk\%FLAVOR%\%BUILD%\app-%FLAVOR%-%BUILD%.apk
set APK_DST=%TEMP%\nui_launcher_debug.apk
rem --------------

echo =====================================================
echo   NUI Launcher (Car) - Deploy + Logcat
echo   Flavor     : %FLAVOR%  (arm32 = 32-bit, arm64 = 64-bit)
echo   Build      : %BUILD%  (debug / release)
echo   Package    : %PKG_NAME%
echo   Activity   : %MAIN_ACTIVITY%
echo   APK source : %APK_SRC%
echo   ADB target : %ADB_TARGET%
echo   Log file   : %LOG_FILE%
echo =====================================================
echo.

echo [1/8] Wipe previous data + uninstall %PKG_NAME% ...
rem  Triple wipe to guarantee clean data:
rem   1) force-stop to kill any running process
rem   2) pm clear to force-clear SharedPreferences/databases
rem   3) uninstall (even if it fails, data is already cleared)
rem  Use EXACT match (/x) so com.nui.launcher never matches com.nui.launcher.debug
adb %ADB_TARGET% shell "pm list packages" | findstr /x /c:"package:%PKG_NAME%" >nul
if errorlevel 1 (
    echo       [INFO] %PKG_NAME% not installed on device - skip wipe.
) else (
    echo       [WARN] Removing existing package: %PKG_NAME%
    rem  First pm clear (guarantees data wiped even if uninstall later fails)
    adb %ADB_TARGET% shell pm clear %PKG_NAME% >nul 2>&1
    rem  Then uninstall
    adb %ADB_TARGET% uninstall %PKG_NAME%
    if errorlevel 1 (
        echo       [WARN] uninstall returned error, but pm clear already wiped data.
    )
)
echo.

echo [2/8] Copy APK to local temp:
echo         %APK_SRC%
echo       -^> %APK_DST% ...
copy /Y "%~dp0%APK_SRC%" "%APK_DST%"
if errorlevel 1 (
    echo [ERROR] Copy failed. Check APK exists: %~dp0%APK_SRC%
    pause
    exit /b 1
)
echo.

echo [3/8] Install APK to device ...
adb %ADB_TARGET% install -r "%APK_DST%"
if errorlevel 1 (
    echo [ERROR] Install failed. See error output above.
    pause
    exit /b 1
)
echo Install done.
rem  FORCE pm clear after install — double insurance to guarantee default config
rem  This wipes any leftover data regardless of whether uninstall succeeded earlier
adb %ADB_TARGET% shell pm clear %PKG_NAME% >nul 2>&1
echo       [OK] Data force-cleared after install (fresh install guaranteed).
echo.

echo [4/8] Clear logcat buffer + remove old log file ...
adb %ADB_TARGET% shell "logcat -c; rm -f %LOG_FILE%"
echo.

echo [5/8] Launch MainActivity: %MAIN_ACTIVITY% ...
adb %ADB_TARGET% shell am start -n %MAIN_ACTIVITY%
echo.

echo [6/8] Wait 10s for app init (first-run privacy dialog may show) ...
timeout /t 10 /nobreak >nul
echo.

echo [7/8] Capture logcat to device %LOG_FILE% (auto stop after 60s)...
echo   - Format : threadtime
echo   - Wait 60 seconds, then auto stop and pull to local
echo.
adb %ADB_TARGET% shell "timeout 60 logcat -v threadtime > %LOG_FILE%"
echo.
echo       capture done.
echo.

echo [8/8] Pull log to local current path: %~dp0
adb %ADB_TARGET% pull %LOG_FILE% "%~dp0nui_launcher.log"
if errorlevel 1 (
    echo [ERROR] Pull failed. See output above.
    pause
    exit /b 1
)
echo       Pulled: %~dp0nui_launcher.log
echo.

echo =====================================================
echo  Done. Log pulled to: %~dp0nui_launcher.log
echo  Installed: %PKG_NAME%  (%FLAVOR% / %BUILD%)
echo =====================================================
pause
