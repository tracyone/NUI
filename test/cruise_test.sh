#!/bin/bash
# ============================================================
# NUI Cruise-Mode Test Script
# Drive a GPS route through red-light / speed-camera / normal
# segments while AmapAuto is in cruise mode, and report what
# NUI / Navi-Link receive on each segment.
#
# Usage:
#   ./cruise_test.sh run [--route NAME]   run the test route (default: route1)
#   ./cruise_test.sh scan <lat> <lon> <heading> <speed> <sec>
#                                          scan for speed cameras by driving
#                                          toward <lat,lon> for <sec> seconds
#   ./cruise_test.sh shot <file>           capture a screenshot (default /tmp/cruise_shot.png)
#   ./cruise_test.sh route                 print all route segments
#   ./cruise_test.sh status                show current GPS fix
#   ./cruise_test.sh help                  this help
#
# Route segment format:  <lat> <lon> <heading> <speed> <sec> <tag>
#   tag = redlight | speedcam | plain
#
# NOTE (emulator): BlueStacks AmapAuto rarely emits camera/red-light
# data under GPS mock. On the real car head unit the same route will
# trigger real 60073 (red light) and camera broadcasts.
# ============================================================
set -e

ADB="adb -s 127.0.0.1:5555"
GPS="$(cd "$(dirname "$0")" && pwd)/gps_sim.sh"
AMAP="com.autonavi.amapauto"
SHOT_DIR="$(cd "$(dirname "$0")/.." && pwd)/test/shots"

# Default route: Zhongshan Sunwen East Rd eastbound.
# Segments are driven sequentially; script prints the current segment
# tag and watches AmapAuto broadcasts (camera / speed / state).
ROUTE1=(
    "22.5155 113.3935 90 40 12 plain"
    "22.5163 113.3970 90 35 12 plain"
    "22.5170 113.4005 90 35 15 redlight"
    "22.5177 113.4040 90 40 15 redlight"
    "22.5182 113.4070 90 40 12 plain"
)

usage() { sed -n '7,24p' "$0" | sed 's/^# \{0,1\}//'; }

# --- one-shot broadcast snapshot (latest NUI/Navi-Link extras) ---
snapshot() {
    adb -s 127.0.0.1:5555 logcat -d -t 600 2>/dev/null \
        | grep -E "Key: (CUR_SPEED|LIMITED_SPEED|CAMERA_DIST|CAMERA_SPEED|CAMERA_TYPE|ICON|TYPE|CUR_ROAD_NAME|TRAFFIC_LIGHT_NUM) " \
        | tail -12 | sed 's/.*Key: //'
}

# --- make sure a broadcast logger (Navi-Link) is running ---
ensure_logger() {
    if ! adb -s 127.0.0.1:5555 shell "ps -A 2>/dev/null | grep -q navi.link"; then
        echo "[CruiseTest] starting Navi-Link as broadcast logger ..."
        adb -s 127.0.0.1:5555 shell am start -n com.navi.link/.activity.RouterActivity >/dev/null 2>&1
        sleep 4
    fi
}

shot() {
    local out="${1:-$SHOT_DIR/cruise_shot.png}"
    mkdir -p "$(dirname "$out")"
    adb -s 127.0.0.1:5555 exec-out screencap -p > "$out"
    echo "shot: $out"
}

# --- drive one segment, watch broadcasts, snapshot camera data ---
drive_segment() {
    local lat="$1" lon="$2" heading="$3" speed="$4" sec="$5" tag="$6"
    echo ">>> [$tag] move -> $lat,$lon heading=$heading speed=${speed}km/h ${sec}s"
    "$GPS" move "$lat" "$lon" "$heading" "$speed" >/dev/null
    local end=$(( $(date +%s) + sec ))
    local n=0
    while [ "$(date +%s)" -lt "$end" ]; do
        n=$((n+1))
        local cam
        cam=$(adb -s 127.0.0.1:5555 logcat -d -t 60 2>/dev/null \
              | grep -E "Key: (CAMERA_DIST|CAMERA_SPEED|CAMERA_TYPE) " \
              | grep -v "Value: -1" | tail -2 | sed 's/.*Key: //' | tr '\n' ' ')
        [ -n "$cam" ] && echo "    [cam] $cam"
        sleep 3
    done
    shot "$SHOT_DIR/seg_${tag}_$(date +%H%M%S).png" >/dev/null
}

run_route() {
    local name="${1:-route1}"
    mkdir -p "$SHOT_DIR"
    echo "[CruiseTest] clearing logcat, starting cruise ..."
    ensure_logger
    adb -s 127.0.0.1:5555 logcat -c
    adb -s 127.0.0.1:5555 shell am force-stop "$AMAP"
    sleep 1
    "$GPS" set 22.5155 113.3935 >/dev/null
    adb -s 127.0.0.1:5555 shell am start -n "$AMAP/com.autonavi.amapauto.MainMapActivity" >/dev/null 2>&1
    echo "[CruiseTest] AmapAuto starting, waiting for GPS fix (12s) ..."
    sleep 12
    "$GPS" move 22.516 113.396 90 30 >/dev/null
    sleep 5
    echo "[CruiseTest] cruise entered, driving route '$name' ..."
    case "$name" in
        route1) for s in "${ROUTE1[@]}"; do drive_segment $s; done ;;
        *) echo "unknown route: $name"; exit 1 ;;
    esac
    "$GPS" stop >/dev/null
    echo "[CruiseTest] done. shots saved under test/shots/. GPS stopped."
    echo "[CruiseTest] last broadcast snapshot:"
    snapshot
}

scan() {
    local lat="${1:-22.510}" lon="${2:-113.400}" heading="${3:-90}" speed="${4:-40}" sec="${5:-60}"
    echo "[CruiseTest] scanning for speed cameras toward $lat,$lon (${sec}s) ..."
    adb -s 127.0.0.1:5555 logcat -c
    "$GPS" move "$lat" "$lon" "$heading" "$speed" >/dev/null
    local end=$(( $(date +%s) + sec ))
    while [ "$(date +%s)" -lt "$end" ]; do
        local cam
        cam=$(adb -s 127.0.0.1:5555 logcat -d -t 80 2>/dev/null \
              | grep -E "Key: (CAMERA_DIST|CAMERA_SPEED|CAMERA_TYPE) " \
              | grep -v "Value: -1" | tail -3 | sed 's/.*Key: //' | tr '\n' ' ')
        echo "[scan $(date +%H:%M:%S)] $cam"
        sleep 4
    done
    "$GPS" stop >/dev/null
}

route() {
    echo "route1 (Sunwen East Rd eastbound):"
    local i=0
    for s in "${ROUTE1[@]}"; do
        i=$((i+1)); echo "  $i. $s"
    done
}

status() { "$GPS" status; }

cmd="${1:-help}"
case "$cmd" in
    run)  run_route "${2:-route1}" ;;
    scan) scan "$2" "$3" "$4" "$5" "$6" ;;
    shot) shot "$2" ;;
    route) route ;;
    status) status ;;
    *) usage ;;
esac
