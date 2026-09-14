#!/bin/bash
# ============================================================
# NUI 一键模拟巡航（BlueStacks）
# 稳定复现巡航模式：重启GPS注入(让高德接受mock定位) → 拉起高德
# → 轮询等 NUI 进入巡航 → 注入测速数据(限速/摄像头) → 截图
#
# Usage:
#   ./cruise_start.sh                    默认巡航(速度65 孙文东路)
#   ./cruise_start.sh <lat> <lon> <hdg> <speed>
#
# 产物:
#   test/shots/cruise_now.png           巡航卡截图（速度+限速圈+摄像头距离）
#   test/shots/cruise_now_big.png       放大版
# ============================================================
set -e

ADB="adb -s 127.0.0.1:5555"
AMAP="com.autonavi.amapauto"
NUI="com.nui.launcher.debug"
DIR="$(cd "$(dirname "$0")" && pwd)"
GPS="$DIR/gps_sim.sh"
SHOT="$DIR/shots"
mkdir -p "$SHOT"

LAT="${1:-22.5155}"
LON="${2:-113.3935}"
HDG="${3:-90}"
SPD="${4:-65}"
SPEED_VAL="${5:-68}"      # 注入速度（模拟超速，>限速变红）
CAM_DIST="${6:-300}"      # 注入摄像头距离
CAM_SPD="${7:-60}"        # 注入限速

echo "== 1/5 重启 GPS 注入（高德对长期 mock 会反作弊显示'卫星定位中'，重启后重新接受） =="
"$GPS" stop 2>&1 | tail -1
sleep 2
"$GPS" move "$LAT" "$LON" "$HDG" "$SPD" 2>&1 | tail -1

echo "== 2/5 拉起高德地图主界面（保持前台） =="
"$ADB" shell am start -n "$AMAP/.MainMapActivity" >/dev/null 2>&1 || true
sleep 5

echo "== 3/5 等 NUI 进入巡航（轮询 logcat，最多 60s） =="
ENTERED=""
for i in $(seq 1 30); do
    if "$ADB" shell logcat -d -s NUI.NavInfo 2>/dev/null | grep -q "巡航进入: ICON=0"; then
        ENTERED="yes"
        echo "  巡航已进入（第 ${i} 次检查）"
        break
    fi
    # 高德可能被桌面顶掉，每 2 轮重拉一次
    if [ $((i % 2)) -eq 0 ]; then
        "$ADB" shell am start -n "$AMAP/.MainMapActivity" >/dev/null 2>&1 || true
    fi
    sleep 2
done
if [ -z "$ENTERED" ]; then
    echo "!! 60s 内未进入巡航。检查: 高德是否前台 / gpsmock 是否授权 / logcat -s NUI.NavInfo"
    exit 1
fi

echo "== 4/5 注入测速数据（连续 6 次，覆盖高德真实广播） =="
for i in 1 2 3 4 5 6; do
    "$ADB" shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND \
        --ei KEY_TYPE 10001 \
        --ei CUR_SPEED "$SPEED_VAL" \
        --ei CAMERA_DIST "$CAM_DIST" \
        --ei CAMERA_SPEED "$CAM_SPD" \
        --ei CAMERA_TYPE 0 \
        --ei ICON 0 >/dev/null 2>&1
    sleep 0.3
done

echo "== 5/5 截图 =="
sleep 1
"$ADB" exec-out screencap -p > "$SHOT/cruise_now.png"
# 巡航卡位于右上角（x≈1450-1950, y≈180-480），放大 2 倍方便手机看
sips -c 300 500 --cropOffset 180 1450 "$SHOT/cruise_now.png" --out "$SHOT/cruise_crop.png" >/dev/null 2>&1
sips -z 600 1000 "$SHOT/cruise_crop.png" --out "$SHOT/cruise_now_big.png" >/dev/null 2>&1
rm -f "$SHOT/cruise_crop.png"

echo ""
echo "完成: 巡航卡截图 $SHOT/cruise_now.png (放大版 cruise_now_big.png)"
echo "注意: 截图里限速/距离是注入数据(模拟器高德不发真实测速)；速度是高德真实值(会覆盖注入)"
