@echo off
setlocal
chcp 65001 >nul
rem ============================================================
rem  Cruise traffic-light test: A <-> B round trip
rem  Usage:  cruise_light_test.bat [legSeconds] [speedKmh]
rem  Default: 20s per leg, 10km/h
rem  Stop with Ctrl-C, then run: gps_sim.bat stop
rem ============================================================
set "GPS=%~dp0gps_sim.bat"
set "LAT=22.278211"
set "LON_A=113.542516"
set "LON_B=113.543056"

set "LEG=%~1"
if "%LEG%"=="" set "LEG=20"
set "SPD=%~2"
if "%SPD%"=="" set "SPD=10"
set /a WAIT=%LEG%+1

echo == Round trip ==
echo   A(%LAT%,%LON_A%) ^<^-^> B(%LAT%,%LON_B%)  %LEG%s per leg, %SPD%km/h
echo   Ctrl-C to stop, then run "gps_sim.bat stop"
echo.

:loop
echo [Leg] A -^> B
call "%GPS%" move %LAT% %LON_A% 90 %SPD%
ping -n %WAIT% 127.0.0.1 >nul
echo [Leg] B -^> A
call "%GPS%" move %LAT% %LON_B% 270 %SPD%
ping -n %WAIT% 127.0.0.1 >nul
goto loop
