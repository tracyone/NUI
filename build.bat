@echo off
REM NUI 一键编译脚本（Windows）
REM 用法：
REM   build.bat              : 编译所有架构（arm32/arm64 x debug/release）
REM   build.bat arm64        : 只编译 arm64（debug+release）
REM   build.bat arm32 debug  : 只编译 arm32 debug
cd /d "%~dp0"

if "%1"=="" (
    call gradlew.bat assembleAll --console=plain
    goto :done
)

if /i "%1"=="arm32" (
    set "TASK=assembleArm32"
) else if /i "%1"=="arm64" (
    set "TASK=assembleArm64"
) else (
    echo 用法: build.bat [arm32^|arm64] [debug^|release]
    exit /b 1
)

if /i "%2"=="debug" (
    call gradlew.bat :app:%TASK%Debug --console=plain
) else if /i "%2"=="release" (
    call gradlew.bat :app:%TASK%Release --console=plain
) else (
    call gradlew.bat :app:%TASK%Debug :app:%TASK%Release --console=plain
)

:done
echo.
echo 构建完成，产物位于 app\build\outputs\apk\
dir /s /b app\build\outputs\apk\*.apk
