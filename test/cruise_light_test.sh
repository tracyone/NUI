#!/bin/bash
# ============================================================
# 巡航红绿灯播报测试（往返移动版）
#
# 旧问题：一直笔直向东匀速注入，车很快开出路口，红绿灯数据中断，
#         倒计时还没降到 10 秒就再也没有回调了。
# 现在：在梅华西路同一路口附近 A<->B 往返（东 20s / 西 20s 循环），
#       车始终停在有红绿灯数据的路段，红灯会经历完整周期，
#       倒计时进入 1~10 秒窗口时即可听到播报。
#
# 用法：
#   ./cruise_light_test.sh            默认: 每段 20s, 10km/h, Ctrl-C 停止
#   ./cruise_light_test.sh 30 10      每段 30s, 10km/h
#
# 若高德刚重装（未处于巡航态）：先 ./cruise_light_test.sh 90 63 跑约 50 秒让它进入巡航，
# Ctrl-C 结束后再跑本脚本（无参数或自定义 LEG/SPD）。
# 观察红绿灯数据： adb -s 127.0.0.1:5555 logcat -s NUI.NavInfo
# ============================================================
set -e

ADB="adb -s 127.0.0.1:5555"
AMAP="com.autonavi.amapauto"
DIR="$(cd "$(dirname "$0")" && pwd)"
GPS="$DIR/gps_sim.sh"

# ---- 往返路段（梅华西路，东西向） ----
LAT="22.278211"
LON_A="113.542516"    # 西端点
LON_B="113.543056"    # 东端点（约 55m，10km/h 走 20s）

LEG="${1:-20}"        # 每段行驶秒数
SPD="${2:-10}"        # 车速 km/h

echo "== 往返巡航 =="
echo "  A($LAT,$LON_A) <-> B($LAT,$LON_B)  每段 ${LEG}s  ${SPD}km/h"
echo "  按 Ctrl-C 停止。另开终端看红绿灯数据: adb -s 127.0.0.1:5555 logcat -s NUI.NavInfo"

echo "== 拉起高德 =="
$ADB shell am start -n "$AMAP/.MainMapActivity" >/dev/null 2>&1 || true

trap 'echo; echo "停止往返，GPS 停止"; "$GPS" stop >/dev/null 2>&1 || true; exit 0' INT TERM

i=0
while true; do
    i=$((i+1))
    echo "[第 $i 趟] A -> B (向东 ${LEG}s)"
    "$GPS" move "$LAT" "$LON_A" 90 "$SPD" >/dev/null 2>&1
    sleep "$LEG"
    echo "[第 $i 趟] B -> A (向西 ${LEG}s)"
    "$GPS" move "$LAT" "$LON_B" 270 "$SPD" >/dev/null 2>&1
    sleep "$LEG"
done
