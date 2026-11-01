#!/usr/bin/env bash
# android-bench.sh: energy/CPU baseline for the Ratatosk Android app.
#
# Measures the app process CPU% and RSS (PSS) over a window, for two cases:
#   idle  — tunnel connected, no traffic (screen off is best)
#   load  — tunnel connected, downloading a file through it
#
# The tunnel itself must already be up: connect it in the app once (the
# VpnService consent dialog cannot be automated), then run this script.
#
# Usage:
#   scripts/android-bench.sh                     # idle, 300 s, screen off
#   scripts/android-bench.sh --idle-secs 600     # idle, 600 s
#   scripts/android-bench.sh --load http://...   # load: fetch URL through tunnel
#   scripts/android-bench.sh --apk app.apk --link 'vless://...'  # install + import first
#
# Needs adb and a connected device (USB debugging on). Output is a JSON
# object on stdout plus a couple of progress lines on stderr.

set -euo pipefail

PKG="dev.ratatosk"
DURATION=300
SAMPLE=5
MODE=idle
URL=""
APK=""
LINK=""
SCREEN_OFF=1

usage() {
    sed -n '2,15p' "$0"
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --idle-secs) DURATION="$2"; shift 2 ;;
        --load) MODE=load; URL="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --link) LINK="$2"; shift 2 ;;
        --screen-on) SCREEN_OFF=0; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

here="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ADB:-}"
if [ -z "$adb" ]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    adb="$sdk/platform-tools/adb"
fi
[ -x "$adb" ] || adb="$(command -v adb || true)"
[ -n "$adb" ] && [ -x "$adb" ] || { echo "adb not found (set ADB or install platform-tools)" >&2; exit 1; }

"$adb" get-state >/dev/null 2>&1 || { echo "no device connected" >&2; exit 1; }

if [ -n "$APK" ]; then
    echo "installing $APK" >&2
    "$adb" install -r "$APK"
fi

if [ -n "$LINK" ]; then
    echo "importing link" >&2
    "$adb" shell am start -n "$PKG/.MainActivity" --es link "$LINK" >/dev/null
fi

pid="$("$adb" shell pidof "$PKG" | tr -d '\r')"
[ -n "$pid" ] || { echo "app not running (open it and connect the tunnel)" >&2; exit 1; }
echo "pid=$pid" >&2

# Clock ticks per second for /proc/<pid>/stat (usually 100 on Android).
hz="$("$adb" shell getconf CLK_TCK 2>/dev/null | tr -d '\r')"
hz="${hz:-100}"

jiffies() {
    # utime+stime of the app process, in clock ticks.
    local stat rest
    stat="$("$adb" shell cat "/proc/$pid/stat" | tr -d '\r')"
    rest="${stat##*) }"          # everything after "pid (comm) "
    set -- $rest
    local utime="${12:-0}" stime="${13:-0}"
    echo $(( utime + stime ))
}

pss_kb() {
    "$adb" shell dumpsys meminfo "$PKG" 2>/dev/null \
        | sed -n 's/.*TOTAL[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r'
}

cpu_pct() {
    # $1 = duration in seconds; returns CPU% of the process over that window.
    local secs="$1" j0 j1 t0 t1
    j0="$(jiffies)"
    t0="$(date +%s%N)"
    sleep "$secs"
    j1="$(jiffies)"
    t1="$(date +%s%N)"
    local dj=$(( j1 - j0 ))
    local dt_ns=$(( t1 - t0 ))
    awk -v dj="$dj" -v dt="$dt_ns" -v hz="$hz" \
        'BEGIN { printf "%.4f", dj / hz / (dt / 1e9) * 100 }'
}

if [ "$SCREEN_OFF" = 1 ]; then
    echo "turning screen off for the measurement" >&2
    "$adb" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    "$adb" shell input keyevent 26 >/dev/null 2>&1 || true
fi

case "$MODE" in
    idle)
        echo "measuring idle CPU for ${DURATION}s (sampling every ${SAMPLE}s)" >&2
        ;;
    load)
        [ -n "$URL" ] || { echo "--load needs a URL" >&2; exit 1; }
        echo "measuring load CPU while fetching $URL for ${DURATION}s" >&2
        # Fetch in a loop on the device, through the tunnel, in the background.
        "$adb" shell "while :; do wget -q -O /dev/null '$URL' 2>/dev/null; done" &
        load_pid=$!
        trap 'kill $load_pid 2>/dev/null || true' EXIT
        ;;
esac

# Idle window first, so the load loop (if any) starts in a settled state.
idle_pct="$(cpu_pct "$DURATION")"
pss="$(pss_kb)"

if [ "$MODE" = load ]; then
    load_pct="$(cpu_pct "$DURATION")"
else
    load_pct="null"
fi

# Wake the screen back up.
"$adb" shell input keyevent 26 >/dev/null 2>&1 || true

echo "{"
echo "  \"package\": \"$PKG\","
echo "  \"mode\": \"$MODE\","
echo "  \"pid\": $pid,"
echo "  \"duration_secs\": $DURATION,"
echo "  \"idle_cpu_pct\": $idle_pct,"
echo "  \"load_cpu_pct\": $load_pct,"
echo "  \"pss_kb\": $pss"
echo "}"
