#!/bin/bash
# ============================================================
# NUI Page Latency Test (page1 app-list -> page0 desktop)
#
# Definition: Time from tapping the "Apps" button to switch back
# to desktop, until the right edge of the music panel reaches
# the screen's right edge (ViewPager2 onPageScrolled offset=0).
#
# Prerequisite: AMap Auto is running and showing the floating
# map (matches real driving scenario).
#
# Usage:
#   ./test/page_latency.sh                 10 iterations, 127.0.0.1:5555
#   ./test/page_latency.sh 20              20 iterations
#   ./test/page_latency.sh 10 <serial>     specify device serial
#
# Output:
#   test/page_latency_result.txt   per-iteration cost + min/max/avg
# ============================================================
set -u

COUNT="${1:-10}"
SERIAL="${2:-127.0.0.1:5555}"
ADB="adb -s $SERIAL"
NUI="com.nui.launcher.debug"
AMAP="com.autonavi.amapauto"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/page_latency_result.txt"
DUMP="/sdcard/nui_window.xml"

echo "=== NUI Page Latency Test ==="
echo "Device   : $SERIAL"
echo "Count    : $COUNT"
echo "NUI pkg  : $NUI"
echo

# 1. Device connectivity
echo "[1/5] Check device connection..."
$ADB get-state >/dev/null 2>&1 || { echo "ERROR: device $SERIAL not connected"; exit 1; }
echo "      connected"

# 2. Ensure AMap is running (floating map depends on AMap overlay)
echo "[2/5] Check / start AMap Auto..."
if ! $ADB shell pidof "$AMAP" >/dev/null 2>&1; then
    echo "      AMap not running, starting..."
    $ADB shell am start -n "$AMAP/.MainMapActivity" >/dev/null 2>&1 || \
    $ADB shell monkey -p "$AMAP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 6
fi
$ADB shell pidof "$AMAP" >/dev/null 2>&1 && echo "      AMap running" || echo "      WARN: AMap still not running, result may not reflect real scenario"

# 3. Go back to NUI desktop and ensure we are on desktop (page 1), not app list.
#    NUI may restore the last page (app list), so tap dockApps twice:
#    tap1 -> app list, tap2 -> desktop. This guarantees a known starting state.
echo "[3/5] Go to NUI desktop..."
$ADB shell am start -n "$NUI/com.nui.launcher.MainActivity" >/dev/null 2>&1
sleep 3
# Locate button early so we can normalize the starting page
BTN_X=""
BTN_Y=""
$ADB shell uiautomator dump "$DUMP" >/dev/null 2>&1
XML="$($ADB shell cat "$DUMP" 2>/dev/null)"
NODE="$(echo "$XML" | grep -o 'resource-id="[^"]*dockApps"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"')"
if [ -n "$NODE" ]; then
    COORDS="$(echo "$NODE" | grep -o '\[[0-9]*,[0-9]*\]')"
    X1="$(echo "$COORDS" | sed -n '1p' | grep -o '[0-9]*' | head -1)"
    Y1="$(echo "$COORDS" | sed -n '1p' | grep -o '[0-9]*' | tail -1)"
    X2="$(echo "$COORDS" | sed -n '2p' | grep -o '[0-9]*' | head -1)"
    Y2="$(echo "$COORDS" | sed -n '2p' | grep -o '[0-9]*' | tail -1)"
    BTN_X=$(( (X1 + X2) / 2 ))
    BTN_Y=$(( (Y1 + Y2) / 2 ))
fi
if [ -n "$BTN_X" ]; then
    # Two taps to guarantee we land on desktop regardless of starting page
    $ADB shell input tap "$BTN_X" "$BTN_Y" >/dev/null 2>&1
    sleep 1.2
    $ADB shell input tap "$BTN_X" "$BTN_Y" >/dev/null 2>&1
    sleep 1.2
fi

# 4. Locate the "Apps" button via uiautomator dump (match English resource-id)
echo "[4/5] Locate Apps button..."
BTN_X=""
BTN_Y=""
for attempt in 1 2 3; do
    $ADB shell uiautomator dump "$DUMP" >/dev/null 2>&1
    XML="$($ADB shell cat "$DUMP" 2>/dev/null)"
    # Match node whose resource-id contains dockApps; extract bounds="[x1,y1][x2,y2]"
    NODE="$(echo "$XML" | grep -o 'resource-id="[^"]*dockApps"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"')"
    if [ -z "$NODE" ]; then
        NODE="$(echo "$XML" | grep 'dockApps' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)"
    fi
    if [ -n "$NODE" ]; then
        COORDS="$(echo "$NODE" | grep -o '\[[0-9]*,[0-9]*\]')"
        X1="$(echo "$COORDS" | sed -n '1p' | grep -o '[0-9]*' | head -1)"
        Y1="$(echo "$COORDS" | sed -n '1p' | grep -o '[0-9]*' | tail -1)"
        X2="$(echo "$COORDS" | sed -n '2p' | grep -o '[0-9]*' | head -1)"
        Y2="$(echo "$COORDS" | sed -n '2p' | grep -o '[0-9]*' | tail -1)"
        if [ -n "$X1" ] && [ -n "$Y2" ]; then
            BTN_X=$(( (X1 + X2) / 2 ))
            BTN_Y=$(( (Y1 + Y2) / 2 ))
            break
        fi
    fi
    sleep 1
done

if [ -z "$BTN_X" ]; then
    echo "      WARN: uiautomator did not find Apps button, fallback to bottom-left fixed coords"
    SIZE="$($ADB shell wm size | grep -o '[0-9]*x[0-9]*' | head -1)"
    SW="${SIZE%x*}"
    SH="${SIZE#*x}"
    BTN_X=48
    BTN_Y=$(( SH - 40 ))
fi
echo "      Button center: ($BTN_X, $BTN_Y)"

# 5. Measurement loop
echo "[5/5] Start measurement ($COUNT iterations)..."
echo
$ADB shell logcat -c >/dev/null 2>&1

> "$OUT"
echo "NUI Page Latency Test (page1->page0)" >> "$OUT"
echo "Count: $COUNT  Device: $SERIAL  Time: $(date '+%Y-%m-%d %H:%M:%S')" >> "$OUT"
echo "----------------------------------------" >> "$OUT"

COSTS=()
MISSED=0
for i in $(seq 1 "$COUNT"); do
    # First tap: go to app list page
    $ADB shell input tap "$BTN_X" "$BTN_Y" >/dev/null 2>&1
    sleep 1.2
    # Clear logcat before the measured transition
    $ADB shell logcat -c >/dev/null 2>&1
    # Second tap: app list -> desktop (the transition under test)
    $ADB shell input tap "$BTN_X" "$BTN_Y" >/dev/null 2>&1
    # Wait for the latency log (animation + IDLE fallback, up to 3s)
    COST=""
    for _ in $(seq 1 15); do
        sleep 0.2
        LINE="$($ADB shell logcat -d -s NUI.PageLatency:I 2>/dev/null | grep 'page1->page0 cost=' | tail -1)"
        if [ -n "$LINE" ]; then
            COST="$(echo "$LINE" | grep -o 'cost=[0-9]*' | grep -o '[0-9]*')"
            break
        fi
    done
    if [ -n "$COST" ]; then
        echo "  [$i] ${COST} ms"
        echo "[$i] ${COST} ms" >> "$OUT"
        COSTS+=("$COST")
    else
        echo "  [$i] no log captured (skipped)"
        echo "[$i] MISS" >> "$OUT"
        MISSED=$((MISSED + 1))
    fi
    sleep 0.5
done

# Statistics
echo
echo "----------------------------------------"
echo "Summary:"
TOTAL=0
MIN=""
MAX=""
VALID=${#COSTS[@]}
for c in "${COSTS[@]+"${COSTS[@]}"}"; do
    TOTAL=$((TOTAL + c))
    if [ -z "$MIN" ] || [ "$c" -lt "$MIN" ]; then MIN=$c; fi
    if [ -z "$MAX" ] || [ "$c" -gt "$MAX" ]; then MAX=$c; fi
done
if [ "$VALID" -gt 0 ]; then
    AVG=$(( TOTAL / VALID ))
    echo "  valid  : $VALID / $COUNT  (missed: $MISSED)"
    echo "  min    : ${MIN} ms"
    echo "  max    : ${MAX} ms"
    echo "  avg    : ${AVG} ms"
    echo "----------------------------------------" >> "$OUT"
    echo "valid: $VALID/$COUNT  missed: $MISSED" >> "$OUT"
    echo "min=${MIN}ms  max=${MAX}ms  avg=${AVG}ms" >> "$OUT"
else
    echo "  no valid data"
    echo "no valid data" >> "$OUT"
fi

echo
echo "Result saved to: $OUT"
