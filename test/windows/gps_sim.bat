@echo off
setlocal
chcp 65001 >nul
rem ============================================================
rem  NUI GPS mock control (Windows) - compatible with old Android
rem  Usage:
rem    gps_sim.bat install
rem    gps_sim.bat auth
rem    gps_sim.bat set  <lat> <lon> [heading] [speed]
rem    gps_sim.bat move <lat> <lon> <heading> <speed>
rem    gps_sim.bat stop
rem    gps_sim.bat navi <lat> <lon> [heading] [speed]
rem    gps_sim.bat status
rem ============================================================

rem ---- auto pick the first device in "device" state ----
rem To force a device: set "SERIAL=emulator-5554"
set "SERIAL="
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do if not defined SERIAL if "%%b"=="device" set "SERIAL=%%a"

set "ADB=adb"
if defined SERIAL set "ADB=adb -s %SERIAL%"
if not defined SERIAL goto adberr

for /f %%i in ('%ADB% shell getprop ro.build.version.sdk') do set "SDK=%%i"
if not defined SDK set "SDK=0"

set "PKG=com.nui.gpsmock"
set "SVC=com.nui.gpsmock/.MockGpsService"
set "AMAP=com.autonavi.amapauto"

if "%~1"=="" goto usage
if /i "%~1"=="install" goto install
if /i "%~1"=="auth" goto auth
if /i "%~1"=="set" goto setp
if /i "%~1"=="move" goto move
if /i "%~1"=="stop" goto stop
if /i "%~1"=="navi" goto navi
if /i "%~1"=="status" goto status
goto usage

:install
echo Device: %SERIAL%  SDK: %SDK%
echo Installing gpsmock ...
%ADB% install -r "%~dp0gpsmock-debug.apk"
if errorlevel 1 goto adberr
call :doauth
echo Installed package version ^(should be versionCode=2^):
%ADB% shell dumpsys package %PKG% ^| findstr /c:"versionCode="
echo Done.
goto end

:auth
call :doauth
goto end

:doauth
echo Granting mock location permission ...
if %SDK% GEQ 23 goto auth_new
echo   old Android (%SDK%): use settings secure mock_location=1
%ADB% shell settings put secure mock_location 1
goto auth_done
:auth_new
%ADB% shell appops set %PKG% android:mock_location allow
%ADB% shell settings put secure mock_location %PKG%
:auth_done
echo   mock_location is now:
%ADB% shell settings get secure mock_location
exit /b 0

:setp
set "LAT=%~2"
if "%LAT%"=="" set "LAT=22.278211"
set "LON=%~3"
if "%LON%"=="" set "LON=113.542516"
set "HDG=%~4"
if "%HDG%"=="" set "HDG=0"
set "SPD=%~5"
if "%SPD%"=="" set "SPD=0"
%ADB% shell am startservice -n %SVC% -a %PKG%.SET --es lat %LAT% --es lon %LON% --ef latf %LAT% --ef lonf %LON% --ef heading %HDG% --ef speed %SPD%
if errorlevel 1 (
  echo [WARN] startservice failed, retry with start-foreground-service ...
  %ADB% shell am start-foreground-service -n %SVC% -a %PKG%.SET --es lat %LAT% --es lon %LON% --ef latf %LAT% --ef lonf %LON% --ef heading %HDG% --ef speed %SPD%
)
echo SET %LAT%,%LON% heading=%HDG% speed=%SPD%
goto end

:move
set "LAT=%~2"
if "%LAT%"=="" set "LAT=22.278211"
set "LON=%~3"
if "%LON%"=="" set "LON=113.542516"
set "HDG=%~4"
if "%HDG%"=="" set "HDG=90"
set "SPD=%~5"
if "%SPD%"=="" set "SPD=40"
%ADB% shell am startservice -n %SVC% -a %PKG%.MOVE --es lat %LAT% --es lon %LON% --ef latf %LAT% --ef lonf %LON% --ef heading %HDG% --ef speed %SPD%
if errorlevel 1 (
  echo [WARN] startservice failed, retry with start-foreground-service ...
  %ADB% shell am start-foreground-service -n %SVC% -a %PKG%.MOVE --es lat %LAT% --es lon %LON% --ef latf %LAT% --ef lonf %LON% --ef heading %HDG% --ef speed %SPD%
)
echo MOVE %LAT%,%LON% heading=%HDG% speed=%SPD%km/h
echo If the map still does not move, run diag.bat and send diag.txt.
goto end

:stop
%ADB% shell am startservice -n %SVC% -a %PKG%.STOP
if errorlevel 1 (
  %ADB% shell am start-foreground-service -n %SVC% -a %PKG%.STOP
)
echo STOP
goto end

:navi
set "LAT=%~2"
if "%LAT%"=="" set "LAT=22.278211"
set "LON=%~3"
if "%LON%"=="" set "LON=113.542516"
set "HDG=%~4"
if "%HDG%"=="" set "HDG=90"
set "SPD=%~5"
if "%SPD%"=="" set "SPD=40"
%ADB% shell am force-stop %AMAP%
%ADB% shell am start -n %AMAP%/.MainMapActivity
ping -n 4 127.0.0.1 >nul
%ADB% shell am start -a android.intent.action.VIEW -d "androidauto://route?sourceApplication=NUI&dlat=%LAT%&dlon=%LON%&dname=MockTarget&dev=0&m=0" -p %AMAP%
ping -n 3 127.0.0.1 >nul
call "%~f0" move %LAT% %LON% %HDG% %SPD%
echo NAVI planned. Press "start navigation" in Amap if needed.
goto end

:status
echo package version ^(should be versionCode=2^):
%ADB% shell dumpsys package %PKG% ^| findstr /c:"versionCode="
%ADB% shell dumpsys location | findstr /i "last location"
if errorlevel 1 echo [WARN] no location injected yet.
goto end

:usage
echo Usage:
echo   gps_sim.bat install
echo   gps_sim.bat auth
echo   gps_sim.bat set  ^<lat^> ^<lon^> [heading] [speed]
echo   gps_sim.bat move ^<lat^> ^<lon^> ^<heading^> ^<speed^>
echo   gps_sim.bat stop
echo   gps_sim.bat navi ^<lat^> ^<lon^> [heading] [speed]
echo   gps_sim.bat status
goto end

:adberr
echo [ERROR] adb command failed. Check:
echo   - adb is in PATH (run: adb devices)
echo   - device/emulator is connected
echo   - if several devices, set SERIAL at the top of this file

:end
endlocal
