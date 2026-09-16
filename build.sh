#!/usr/bin/env bash
# NUI 一键编译脚本（macOS / Linux）
# 用法：
#   ./build.sh              # 编译所有架构（arm32/arm64 × debug/release）
#   ./build.sh arm64        # 只编译 arm64（debug+release）
#   ./build.sh arm32 debug  # 只编译 arm32 debug
set -e
cd "$(dirname "$0")"

ARCH="${1:-all}"
TYPE="${2:-}"

case "$ARCH" in
  all)
    ./gradlew assembleAll --console=plain
    ;;
  arm32|arm64)
    if [ -n "$TYPE" ]; then
      ./gradlew ":app:assemble${ARCH^}${TYPE^}" --console=plain
    else
      ./gradlew ":app:assemble${ARCH^}Debug" ":app:assemble${ARCH^}Release" --console=plain
    fi
    ;;
  *)
    echo "用法: $0 [all|arm32|arm64] [debug|release]"
    exit 1
    ;;
esac

echo ""
echo "✅ 构建完成，产物位于 app/build/outputs/apk/"
find app/build/outputs/apk -name "*.apk" -newer "$0" -o -name "*.apk" | sort | while read -r f; do
  echo "   $f ($(du -h "$f" | cut -f1))"
done
