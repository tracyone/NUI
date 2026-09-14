#!/bin/bash
# ============================================================
# GPS Simulation Tool for NUI (uses gpsmock app)
# Simulate vehicle movement via mock location to trigger
# AmapAuto cruise / navigation and its real broadcasts.
#
# Usage:
#   ./gps_sim.sh install                     build+install gpsmock & grant permission
#   ./gps_sim.sh auth                        grant mock location permission
#   ./gps_sim.sh set <lat> <lon> [heading] [speed]    inject a single point
#   ./gps_sim.sh move <lat> <lon> <heading> <speed>    keep moving (heading=deg, speed=km/h)
#   ./gps_sim.sh stop                        stop moving
#   ./gps_sim.sh navi <lat> <lon> [heading] [speed]    inject+move+plan route in AmapAuto
#   ./gps_sim.sh status                      show current GPS fix
#
# Examples:
#   ./gps_sim.sh move 22.517 113.394 45 60      # drive 60km/h heading 45deg
#   ./gps_sim.sh navi 22.517 113.394 90 40      # move + ask AmapAuto to navigate
# ============================================================
set -e

ADB="adb -s 127.0.0.1:5555"
PKG="com.nui.gpsmock"
SVC="com.nui.gpsmock/.MockGpsService"
AMAP="com.autonavi.amapauto"
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
APK="$PROJ/gpsmock/build/outputs/apk/debug/gpsmock-debug.apk"

usage() {
    sed -n '5,19p' "$0" | sed 's/^# \{0,1\}//'
}

auth() {
    echo "granting mock location to $PKG ..."
    $ADB shell appops set "$PKG" android:mock_location allow
    $ADB shell settings put secure mock_location "$PKG"
    echo "done. (dev option 'Select mock location app' also works)"
}

install() {
    echo "building gpsmock ..."
    (cd "$PROJ" && ./gradlew :gpsmock:assembleDebug -q)
    echo "installing $APK ..."
    $ADB install -r "$APK"
    auth
}

set_point() {
    local lat="${1:-22.517}" lon="${2:-113.394}" heading="${3:-0}" speed="${4:-0}"
    # lat/lon 必须用 --ed(Double)，--ef(Float) 会被 getDoubleExtra 读成默认值
    $ADB shell am start-foreground-service -n "$SVC" -a "$PKG.SET" \
        --ed lat "$lat" --ed lon "$lon" --ef heading "$heading" --ef speed "$speed"
    echo "SET $lat,$lon heading=$heading speed=$speed"
}

move() {
    local lat="${1:-22.517}" lon="${2:-113.394}" heading="${3:-45}" speed="${4:-60}"
    # lat/lon 必须用 --ed(Double)，--ef(Float) 会被 getDoubleExtra 读成默认值
    $ADB shell am start-foreground-service -n "$SVC" -a "$PKG.MOVE" \
        --ed lat "$lat" --ed lon "$lon" --ef heading "$heading" --ef speed "$speed"
    echo "MOVE $lat,$lon heading=$heading speed=${speed}km/h (AmapAuto will enter cruise)"
}

stop() {
    $ADB shell am start-foreground-service -n "$SVC" -a "$PKG.STOP"
    echo "STOP"
}

navi() {
    local lat="${1:-22.517}" lon="${2:-113.394}" heading="${3:-90}" speed="${4:-40}"
    # keep AmapAuto alive in background first
    $ADB shell am force-stop "$AMAP"
    $ADB shell am start -n "$AMAP/com.autonavi.amapauto.MainMapActivity" >/dev/null 2>&1
    sleep 3
    # plan a route to a point slightly ahead so navigation really starts
    $ADB shell am start -a android.intent.action.VIEW \
        -d "androidauto://route?sourceApplication=NUI&dlat=$lat&dlon=$lon&dname=MockTarget&dev=0&m=0" \
        -p "$AMAP" >/dev/null 2>&1 || true
    sleep 2
    # now move continuously so the vehicle actually drives the route
    move "$lat" "$lon" "$heading" "$speed"
    echo "NAVI planned; moving toward $lat,$lon. Press '开始导航' in AmapAuto if needed."
}

status() {
    $ADB shell dumpsys location 2>/dev/null | grep "last location=Location\[gps" | head -1 || echo "no gps fix"
}

cmd="${1:-help}"
case "$cmd" in
    install) install ;;
    auth) auth ;;
    set) set_point "$2" "$3" "$4" "$5" ;;
    move) move "$2" "$3" "$4" "$5" ;;
    stop) stop ;;
    navi) navi "$2" "$3" "$4" "$5" ;;
    status) status ;;
    *) usage ;;
esac
