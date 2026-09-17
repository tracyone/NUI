#!/usr/bin/env bash
# ============================================================
# NUI 一键编译脚本（macOS / Linux）
#
# 用法:
#   ./build.sh                            编译全部架构（arm32/arm64 × debug/release，共 4 个 APK）
#   ./build.sh arm64                      仅编译 arm64（debug + release）
#   ./build.sh arm32 release              仅编译 arm32 release
#   ./build.sh arm64 debug -o ~/Desktop   编译 arm64 debug，并把 APK 复制到 ~/Desktop
#
# 参数:
#   ARCH    all | arm32 | arm64      默认 all
#   TYPE    debug | release          默认同时编译两种
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

参数:
  ARCH    all | arm32 | arm64      默认 all
  TYPE    debug | release          默认同时编译两种
  -o DIR  将产物 APK 复制到指定目录（目录不存在会自动创建）
  -h      显示本帮助

产物: app/build/outputs/apk/<arch>/<type>/app-<arch>-<type>.apk
EOF
    exit 0
}

# 首字母大写（macOS bash 3.2 不支持 ${var^}，用 awk 兼容）
capitalize() { echo "$1" | awk '{print toupper(substr($0,1,1)) tolower(substr($0,2))}'; }

# ---------- 解析参数 ----------
POS_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage ;;
        -o) OUT_DIR="${2:?错误: -o 后需要目录路径}"; shift 2 ;;
        -o=*) OUT_DIR="${1#-o=}"; shift ;;
        all|arm32|arm64) ARCH="$1"; shift ;;
        debug|release) TYPE="$1"; shift ;;
        *) echo "错误: 未知参数 '$1'（可用 ./build.sh -h 查看用法）" >&2; exit 1 ;;
    esac
done

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
