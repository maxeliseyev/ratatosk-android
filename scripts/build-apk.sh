#!/usr/bin/env bash
# Build the app APK. By default rebuilds the Rust core first, like
# scripts/testflight.sh does on iOS.
#
#   scripts/build-apk.sh              # debug APK (fastest)
#   scripts/build-apk.sh --release    # release APK
#   scripts/build-apk.sh --no-core    # keep app/src/main/jniLibs as it is
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
mode="debug"
core=1
for arg in "$@"; do
    case "$arg" in
        --release) mode="release" ;;
        --debug) mode="debug" ;;
        --no-core) core=0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

if [ "$core" = 1 ]; then
    "$here/scripts/update-core.sh"
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ "$mode" = "release" ]; then
    "$here/gradlew" -p "$here" :app:assembleRelease
    apk="$here/app/build/outputs/apk/release/app-release-unsigned.apk"
else
    "$here/gradlew" -p "$here" :app:assembleDebug
    apk="$here/app/build/outputs/apk/debug/app-debug.apk"
fi

echo "apk: $apk"
ls -lh "$apk" 2>/dev/null || true
