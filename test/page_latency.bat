@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  NUI Page Latency Test (page1 app-list -> page0 desktop)
rem  Definition: Time from tapping the "Apps" button to switch
rem  back to desktop, until the right edge of the music panel
rem  reaches the screen's right edge (ViewPager2 onPageScrolled
rem  offset=0).
rem  Prerequisite: AMap Auto is running and showing the floating
rem  map.
rem
rem  Usage:
rem    test\page_latency.bat                 10 iterations, 127.0.0.1:5555
rem    test\page_latency.bat 20              20 iterations
rem    test\page_latency.bat 10 <serial>     specify device serial
rem
rem  Output:
rem    test\page_latency_result.txt
rem ============================================================

set COUNT=%1
if "%COUNT%"=="" set COUNT=10
set SERIAL=%2
if "%SERIAL%"=="" set SERIAL=127.0.0.1:5555

set ADB=adb -s %SERIAL%
set NUI=com.nui.launcher.debug
set AMAP=com.autonavi.amapauto
set DIR=%~dp0
set OUT=%DIR%page_latency_result.txt

echo === NUI Page Latency Test ===
echo Device   : %SERIAL%
echo Count    : %COUNT%
echo NUI pkg  : %NUI%
echo.

rem 1. Device connectivity
echo [1/6] Check device connection...
%ADB% get-state >nul 2>&1
if errorlevel 1 (
    echo ERROR: device %SERIAL% not connected
    pause
    exit /b 1
)
echo       connected
echo.

rem 2. Ensure AMap is running
echo [2/6] Check / start AMap Auto...
%ADB% shell pidof %AMAP% >nul 2>&1
if errorlevel 1 (
    echo       AMap not running, starting...
    %ADB% shell am start -n %AMAP%/.MainMapActivity >nul 2>&1
    if errorlevel 1 %ADB% shell monkey -p %AMAP% -c android.intent.category.LAUNCHER 1 >nul 2>&1
    timeout /t 6 /nobreak >nul
)
%ADB% shell pidof %AMAP% >nul 2>&1
if errorlevel 1 (
    echo       WARN: AMap still not running, result may not reflect real scenario
) else (
    echo       AMap running
)
echo.

rem 3. Go back to NUI desktop
echo [3/6] Go to NUI desktop...
%ADB% shell am start -n %NUI%/com.nui.launcher.MainActivity >nul 2>&1
timeout /t 3 /nobreak >nul
echo.

rem 4. Locate the "Apps" button.
rem    The dockApps button is deterministic: 96dp-wide dock on the left,
rem    button 80dp x 48dp at the bottom. Compute pixel coords from screen
rem    size + density (more robust than parsing uiautomator XML in batch).
echo [4/6] Locate Apps button...
set BTN_X=
set BTN_Y=
set SH=
set DENS=
rem wm size  -> "Physical size: 1280x720"  (Override size may also appear; last wins)
for /f "tokens=2 delims=x" %%a in ('%ADB% shell wm size ^| findstr "size:"') do set "SH=%%a"
rem wm density -> "Physical density: 240"  (Override density may also appear; last wins)
for /f "tokens=3" %%a in ('%ADB% shell wm density ^| findstr "density:"') do set "DENS=%%a"
rem Strip any trailing carriage return from adb output
if defined SH for /f "tokens=*" %%a in ("!SH!") do set "SH=%%a"
if defined DENS for /f "tokens=*" %%a in ("!DENS!") do set "DENS=%%a"
rem Defaults if parsing failed
if not defined SH set SH=720
if not defined DENS set DENS=160
rem Button center: x = 43dp, y = screenHeight - 32dp (see layout for exact dp)
set /a BTN_X=43*DENS/160
set /a BTN_Y=SH-32*DENS/160
echo       Screen: width=UNKNOWN height=!SH! density=!DENS!
echo       Button center: (!BTN_X!, !BTN_Y!)
echo.

rem 4b. Ensure we start on desktop (page 1), not app list.
rem     NUI may restore the last page, so tap dockApps twice to guarantee
rem     a known starting state (tap1 -> app list, tap2 -> desktop).
%ADB% shell input tap !BTN_X! !BTN_Y! >nul 2>&1
timeout /t 1 /nobreak >nul
%ADB% shell input tap !BTN_X! !BTN_Y! >nul 2>&1
timeout /t 1 /nobreak >nul

rem 5. Measurement loop
echo [5/6] Start measurement (%COUNT% iterations)...
echo.
%ADB% shell logcat -c >nul 2>&1

echo NUI Page Latency Test (page1 to page0) > "%OUT%"
echo Count: %COUNT%  Device: %SERIAL% >> "%OUT%"
echo ---------------------------------------- >> "%OUT%"

set VALID=0
set TOTAL=0
set MIN=
set MAX=
set MISSED=0

for /l %%i in (1,1,%COUNT%) do call :measure_one %%i

echo.
echo ----------------------------------------
echo Summary:
echo   valid  : %VALID% / %COUNT%  (missed: %MISSED%)
if %VALID% gtr 0 (
    set /a AVG=TOTAL/VALID
    echo   min    : !MIN! ms
    echo   max    : !MAX! ms
    echo   avg    : !AVG! ms
    echo ---------------------------------------- >> "%OUT%"
    echo valid: %VALID%/%COUNT%  missed: %MISSED% >> "%OUT%"
    echo min=!MIN!ms  max=!MAX!ms  avg=!AVG!ms >> "%OUT%"
) else (
    echo   no valid data
    echo no valid data >> "%OUT%"
)

goto :capture_log

:measure_one
set COST=
set RAW=
set TMP=
rem First tap: go to app list page
%ADB% shell input tap !BTN_X! !BTN_Y! >nul 2>&1
timeout /t 1 /nobreak >nul
rem Clear logcat before the measured transition
%ADB% shell logcat -c >nul 2>&1
rem Second tap: app list -> desktop (the transition under test)
%ADB% shell input tap !BTN_X! !BTN_Y! >nul 2>&1
rem Poll for the latency log (animation + IDLE fallback, up to ~5s).
rem NOTE: ping -n 1 -w 200 127.0.0.1 returns instantly on localhost, so use
rem timeout /t 1 for a real 1s sleep. 5 polls x 1s = 5s coverage.
for /l %%j in (1,1,5) do (
    if not defined COST (
        timeout /t 1 /nobreak >nul
        rem Search for "cost=" to avoid the ">" in "page1->page0" being
        rem misinterpreted as a redirection operator inside the pipe.
        %ADB% shell logcat -d -s NUI.PageLatency:I 2>nul | findstr "cost=" > "%DIR%_pl_tmp.txt"
        for /f "usebackq delims=" %%l in ("%DIR%_pl_tmp.txt") do set "RAW=%%l"
        if defined RAW (
            rem "... cost=424ms (offset=0 settled)" -> split on "=" -> "424ms (offset" -> split on "m" -> "424"
            for /f "tokens=2 delims==" %%a in ("!RAW!") do for /f "tokens=1 delims=m" %%b in ("%%a") do set "COST=%%b"
        )
    )
)
if defined COST (
    echo   [%1] !COST! ms
    echo [%1] !COST! ms >> "%OUT%"
    set /a VALID+=1
    set /a TOTAL+=!COST!
    if not defined MIN set MIN=!COST!
    if not defined MAX set MAX=!COST!
    if !COST! lss !MIN! set MIN=!COST!
    if !COST! gtr !MAX! set MAX=!COST!
) else (
    echo   [%1] no log captured (skipped)
    echo [%1] MISS >> "%OUT%"
    set /a MISSED+=1
)
timeout /t 1 /nobreak >nul
exit /b

:capture_log

rem 6. Capture full logcat for perf analysis (surface/frame/IPC breakdown)
echo.
echo [6/6] Capturing logcat to nui_launcher.log ...
set LOGTAGS=NUI_PERF:I NUI.PageLatency:I NUI.MapHost:I NUI.MapHost:D ActivityManager:I WindowManager:I SurfaceFlinger:I *:S
%ADB% shell logcat -d %LOGTAGS% > "%DIR%nui_launcher.log" 2>nul
rem Also dump a wider unfiltered tail (last 4000 lines) for context
%ADB% shell logcat -d -t 4000 >> "%DIR%nui_launcher.log" 2>nul
echo       saved to test\nui_launcher.log

rem Cleanup temp file
if exist "%DIR%_pl_tmp.txt" del "%DIR%_pl_tmp.txt" >nul 2>&1

echo.
echo Result saved to: %OUT%
echo Log saved to   : %DIR%nui_launcher.log
pause
