#!/usr/bin/env bash
# ============================================================
# NUI 一键编译脚本（macOS / Linux）
#
# 用法:
#   ./build.sh                            编译全部架构（arm32/arm64 × debug/release，共 4 个 APK）
#   ./build.sh arm64                      仅编译 arm64（debug + release）
#   ./build.sh arm32 release              仅编译 arm32 release
#   ./build.sh arm64 debug -o ~/Desktop   编译 arm64 debug，并把 APK 复制到 ~/Desktop
#   ./build.sh install                    自动识别 adb 设备架构，编译并安装对应的 release 包
#   ./build.sh install -s 127.0.0.1:5555  指定设备安装（多设备时必须指定）
#
# 参数:
#   install 安装目标（编译 release 并 adb install -r）
#   ARCH    all | arm32 | arm64      默认 all（install 时自动识别）
#   TYPE    debug | release          默认同时编译两种（install 时固定 release）
#   -s      adb 设备序列号（仅 install 用）
#   -o DIR  将产物 APK 复制到指定目录（目录不存在会自动创建）
#   -h      显示本帮助
#
# 产物: app/build/outputs/apk/<arch>/<type>/app-<arch>-<type>.apk
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

ARCH="all"
TYPE=""
OUT_DIR=""
APK_BASE="app/build/outputs/apk"

usage() {
    cat <<'EOF'
NUI 一键编译脚本（macOS / Linux）

用法:
  ./build.sh                            编译全部架构（arm32/arm64 × debug/release，共 4 个 APK）
  ./build.sh arm64                      仅编译 arm64（debug + release）
  ./build.sh arm32 release              仅编译 arm32 release
  ./build.sh arm64 debug -o ~/Desktop   编译 arm64 debug，并把 APK 复制到 ~/Desktop
  ./build.sh install                    自动识别 adb 设备架构，编译并安装对应 release 包
  ./build.sh install -s 127.0.0.1:5555  指定设备安装

参数:
  install 安装目标（强制 release）
  ARCH    all | arm32 | arm64      默认 all（install 时按设备 ABI 自动选择）
  TYPE    debug | release          默认同时编译两种（install 时固定 release）
  -s      adb 设备序列号（仅 install 用；多设备时必填）
  -o DIR  将产物 APK 复制到指定目录（目录不存在会自动创建）
  -h      显示本帮助

产物: app/build/outputs/apk/<arch>/<type>/app-<arch>-<type>.apk
EOF
    exit 0
}

# 首字母大写（macOS bash 3.2 不支持 ${var^}，用 awk 兼容）
capitalize() { echo "$1" | awk '{print toupper(substr($0,1,1)) tolower(substr($0,2))}'; }

# ---------- adb 辅助（install 目标用） ----------
adb_available() { command -v adb >/dev/null 2>&1; }

# 选出唯一在线设备；-s 显式指定时直接返回
pick_device() {
    if [ -n "$ADB_SERIAL" ]; then
        echo "$ADB_SERIAL"
        return 0
    fi
    local devs count
    devs=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    count=$(printf '%s\n' "$devs" | grep -c . || true)
    if [ "$count" -eq 0 ]; then
        echo "错误: 未检测到在线 adb 设备（请先启动模拟器或连接车机）" >&2
        return 1
    fi
    if [ "$count" -gt 1 ]; then
        echo "错误: 检测到多个设备，请用 -s <serial> 指定：" >&2
        printf '%s\n' "$devs" | sed 's/^/    /' >&2
        return 1
    fi
    echo "$devs"
}

# 按设备 ABI 选包：arm64-v8a → arm64，其余（armeabi-v7a/armeabi/x86）→ arm32
detect_arch() {
    local serial="$1" abi abilist
    abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
    abilist=$(adb -s "$serial" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r')
    case "$abi $abilist" in
        *arm64*) echo arm64 ;;
        *) echo arm32 ;;
    esac
}

# ---------- 解析参数 ----------
MODE="build"
ADB_SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        -o) OUT_DIR="${2:?错误: -o 后需要目录路径}"; shift 2 ;;
        -o=*) OUT_DIR="${1#-o=}"; shift ;;
        -s) ADB_SERIAL="${2:?错误: -s 后需要设备序列号}"; shift 2 ;;
        -s=*) ADB_SERIAL="${1#-s=}"; shift ;;
        install|--install|-i) MODE="install"; shift ;;
        all|arm32|arm64) ARCH="$1"; shift ;;
        debug|release) TYPE="$1"; shift ;;
        *) echo "错误: 未知参数 '$1'（可用 ./build.sh -h 查看用法）" >&2; exit 1 ;;
    esac
done

# ---------- install 预检：识别设备与架构 ----------
DEVICE=""
if [ "$MODE" = "install" ]; then
    if ! adb_available; then
        echo "错误: 未找到 adb，请安装 Android SDK Platform-Tools 并加入 PATH" >&2
        exit 1
    fi
    DEVICE=$(pick_device) || exit 1
    # 显式指定 arm32/arm64 时尊重用户选择；默认 all 则按设备 ABI 自动挑
    if [ "$ARCH" = "all" ]; then
        ARCH=$(detect_arch "$DEVICE")
    fi
    TYPE="release"
    echo "==> 目标设备: $DEVICE    架构: $ARCH    类型: release"
fi

# ---------- 组装 Gradle 任务 ----------
ARCH_TITLE=$(capitalize "$ARCH")
TASKS=()
case "$ARCH" in
    all)
        TASKS+=("assembleAll")
        ;;
    arm32|arm64)
        if [ -n "$TYPE" ]; then
            TASKS+=(":app:assemble${ARCH_TITLE}$(capitalize "$TYPE")")
        else
            TASKS+=(":app:assemble${ARCH_TITLE}Debug" ":app:assemble${ARCH_TITLE}Release")
        fi
        ;;
esac

echo "==> 开始构建: ${TASKS[*]}"
./gradlew "${TASKS[@]}" --console=plain

# ---------- 收集产物 ----------
# macOS bash 3.2 无 mapfile，用 while 循环兼容
APKS=()
while IFS= read -r f; do
    APKS+=("$f")
done < <(find "$APK_BASE" -name "*.apk" -type f | sort)
if [ "${#APKS[@]}" -eq 0 ]; then
    echo "错误: 构建完成但未找到任何 APK" >&2
    exit 1
fi

# 按参数过滤出本次产物
MATCH=()
for f in "${APKS[@]}"; do
    case "$ARCH:$TYPE" in
        all:) MATCH+=("$f") ;;
        all:debug) [[ "$f" == *"/debug/"* ]] && MATCH+=("$f") ;;
        all:release) [[ "$f" == *"/release/"* ]] && MATCH+=("$f") ;;
        *) [[ "$f" == *"/$ARCH/"* ]] && { [ -z "$TYPE" ] || [[ "$f" == *"/$TYPE/"* ]]; } && MATCH+=("$f") ;;
    esac
done

if [ "${#MATCH[@]}" -eq 0 ]; then
    echo "错误: 未找到匹配 $ARCH/$TYPE 的产物" >&2
    exit 1
fi

# ---------- 输出产物清单（路径 / 大小 / md5）----------
echo ""
echo "==> 产物清单:"
for f in "${MATCH[@]}"; do
    size=$(du -h "$f" | cut -f1)
    if command -v md5 >/dev/null 2>&1; then
        md5=$(md5 -q "$f")
    else
        md5=$(md5sum "$f" | awk '{print $1}')
    fi
    echo "    $f"
    echo "       大小: $size    md5: $md5"

    if [ -n "$OUT_DIR" ]; then
        mkdir -p "$OUT_DIR"
        cp "$f" "$OUT_DIR/"
        echo "       -> 已复制到: $OUT_DIR/$(basename "$f")"
    fi
done

echo ""
echo "✅ 构建完成"

# ---------- install: 安装 release 包到设备 ----------
if [ "$MODE" = "install" ]; then
    APK="$APK_BASE/$ARCH/release/app-$ARCH-release.apk"
    if [ ! -f "$APK" ]; then
        APK=$(find "$APK_BASE/$ARCH/release" -name "*.apk" -type f 2>/dev/null | head -1)
    fi
    if [ -z "$APK" ] || [ ! -f "$APK" ]; then
        echo "错误: 未找到 $ARCH release APK（路径: $APK_BASE/$ARCH/release）" >&2
        exit 1
    fi
    echo ""
    echo "==> 安装到 $DEVICE: $(basename "$APK")"
    # -r 覆盖安装；-d 允许降级（方便回滚测试）
    if ! adb -s "$DEVICE" install -r -d "$APK"; then
        echo "错误: 安装失败（检查设备架构是否为 $ARCH、是否解锁/授权安装）" >&2
        exit 1
    fi
    echo "✅ 已安装到 $DEVICE"
fi
