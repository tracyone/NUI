@echo off
setlocal

rem ===========================================================
rem  NUI Launcher - Deploy, Launch, & Logcat Capture
rem   target: 32/64 bit BlueStacks or real device (127.0.0.1:5555 default)
rem   switches (top of file): UNINSTALL_FIRST, OPEN_SETTINGS, SHOW_FLOAT_MAP
rem ===========================================================

rem --- config ---
set ADB_TARGET=-s 127.0.0.1:5555
set PKG_NAME=com.nui.launcher.debug
set MAIN_ACTIVITY=%PKG_NAME%/com.nui.launcher.MainActivity
set SETTINGS_ACTIVITY=%PKG_NAME%/com.nui.launcher.settings.SettingsActivity
set APK_REL=app\build\outputs\apk\debug\app-debug.apk
set LOG_FILE=/mnt/sdcard/nui_launcher.log

rem --- switches ---
set UNINSTALL_FIRST=0
set OPEN_SETTINGS=0
set SHOW_FLOAT_MAP=1
rem --------------

set "APK_PATH=%~dp0%APK_REL%"

echo =====================================================
echo   NUI Launcher (debug) - Deploy + Launch + Logcat
echo   Package     : %PKG_NAME%
echo   APK source  : %APK_PATH%
echo   ADB target  : %ADB_TARGET%
echo   Log file    : %LOG_FILE%
echo.
echo   Switches: UNINSTALL_FIRST=%UNINSTALL_FIRST%  OPEN_SETTINGS=%OPEN_SETTINGS%  SHOW_FLOAT_MAP=%SHOW_FLOAT_MAP%
echo =====================================================
echo.

if not exist "%APK_PATH%" goto ERR_APK

echo [1/6] Install policy
if "%UNINSTALL_FIRST%"=="1" goto STEP_UNINSTALL
echo       skip uninstall, install -r only
goto STEP_INSTALL

:STEP_UNINSTALL
echo       uninstall %PKG_NAME% ...
adb %ADB_TARGET% uninstall %PKG_NAME%
echo.

:STEP_INSTALL
echo [2/6] Install APK
adb %ADB_TARGET% install -r "%APK_PATH%"
if errorlevel 1 goto ERR_INSTALL
echo       OK
echo.

echo [3/6] Grant permissions (overlay + notification listener)
adb %ADB_TARGET% shell appops set %PKG_NAME% OP_SYSTEM_ALERT_WINDOW allow >nul 2>&1
adb %ADB_TARGET% shell settings put secure enabled_notification_listeners %PKG_NAME%/com.nui.launcher.music.MusicListenerService
adb %ADB_TARGET% shell am broadcast -a android.service.notification.NotificationListenerService.REBIND -p %PKG_NAME% >nul 2>&1
echo       OK
echo.

echo [4/6] Kill old process + clear logcat
adb %ADB_TARGET% shell am force-stop %PKG_NAME%
adb %ADB_TARGET% shell rm -f %LOG_FILE%
adb %ADB_TARGET% shell logcat -c
echo       OK
echo.

echo [5/6] Launch NUI
adb %ADB_TARGET% shell monkey -p %PKG_NAME% -c android.intent.category.LAUNCHER 1
timeout /t 4 /nobreak >nul
if "%OPEN_SETTINGS%"=="1" adb %ADB_TARGET% shell am start -n %SETTINGS_ACTIVITY%
if "%SHOW_FLOAT_MAP%"=="1" adb %ADB_TARGET% shell am broadcast -a com.autonavi.plus.showmap --ei x 256 --ei y 40 --ei w 1440 --ei h 1000 >nul
echo       OK
echo.

echo [6/6] Capture logcat (threadtime, Info+) -^> %LOG_FILE%
echo       Stop  : Ctrl+C
echo       Pull  : adb %ADB_TARGET% pull %LOG_FILE% .\xx.log
echo       Tail  : adb %ADB_TARGET% shell tail -n 200 %LOG_FILE%
echo.
adb %ADB_TARGET% shell "logcat -v threadtime *:I > %LOG_FILE%"

echo.
echo =====================================================
echo   Done. Log on device: %LOG_FILE%
echo =====================================================
pause
exit /b 0

:ERR_APK
echo [ERROR] APK missing: %APK_PATH%
echo         Build the APK on mac first (gradle assembleDebug),
echo         then copy the whole NUI folder to Windows so the
echo         app\build\outputs\apk\debug\app-debug.apk path exists.
pause
exit /b 1

:ERR_INSTALL
echo [ERROR] adb install failed. See output above.
pause
exit /b 1
