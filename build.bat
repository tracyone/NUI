@echo off
REM ============================================================
REM NUI 一键编译脚本（Windows）
REM
REM 用法:
REM   build.bat                              : 编译全部架构（arm32/arm64 x debug/release）
REM   build.bat arm64                        : 仅编译 arm64（debug+release）
REM   build.bat arm32 release                : 仅编译 arm32 release
REM   build.bat arm64 debug -o C:\apk_out    : 编译 arm64 debug 并复制 APK 到指定目录
REM   build.bat -h                           : 帮助
REM
REM 产物: app\build\outputs\apk\<arch>\<type>\app-<arch>-<type>.apk
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ARCH=all"
set "TYPE="
set "OUT_DIR="

:parse
if "%~1"=="" goto :run
if /i "%~1"=="-h" (
    echo 用法: build.bat [all^|arm32^|arm64] [debug^|release] [-o DIR]
    exit /b 0
)
if /i "%~1"=="all" set "ARCH=all"
if /i "%~1"=="arm32" set "ARCH=arm32"
if /i "%~1"=="arm64" set "ARCH=arm64"
if /i "%~1"=="debug" set "TYPE=debug"
if /i "%~1"=="release" set "TYPE=release"
if /i "%~1"=="-o" (
    set "OUT_DIR=%~2"
    shift
)
shift
goto :parse

:run
if /i "%ARCH%"=="all" (
    set "GRADLE_TASKS=assembleAll"
) else (
    if "%TYPE%"=="" (
        set "GRADLE_TASKS=:app:assemble%ARCH%Debug :app:assemble%ARCH%Release"
    ) else (
        set "GRADLE_TASKS=:app:assemble%ARCH%%TYPE%"
    )
)

echo ==^> 开始构建: %GRADLE_TASKS%
call gradlew.bat %GRADLE_TASKS% --console=plain
if errorlevel 1 (
    echo 构建失败
    exit /b 1
)

echo.
echo ==^> 产物清单:
if "%OUT_DIR%"=="" (
    dir /s /b app\build\outputs\apk\*.apk
) else (
    mkdir "%OUT_DIR%" 2>nul
    if /i "%ARCH%"=="all" (
        set "APK_SRC=app\build\outputs\apk"
    ) else (
        set "APK_SRC=app\build\outputs\apk\%ARCH%"
    )
    for /r "!APK_SRC!" %%F in (*.apk) do (
        if "%TYPE%"=="" (
            copy /y "%%F" "%OUT_DIR%\" >nul
        ) else (
            echo %%F | findstr /i "\%TYPE%\" >nul && copy /y "%%F" "%OUT_DIR%\" >nul
        )
    )
    dir /b "%OUT_DIR%\*.apk"
    echo.
    echo ^=^> 已复制到 %OUT_DIR%
)

echo.
echo 构建完成
endlocal
