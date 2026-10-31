#!/usr/bin/env bash
# Build the Rust JNI core (libratatosk_android.so) for every Android ABI and
# copy the shared libraries into app/src/main/jniLibs. Run after pulling
# changes in the ratatosk repo, then build the app.
#
#   scripts/update-core.sh                 # ratatosk at ../ratatosk
#   RATATOSK_DIR=/path/to/ratatosk scripts/update-core.sh
#   ABIS="arm64-v8a x86_64" scripts/update-core.sh
#
# The Cargo manifest points at ../../ratatosk/crates/core (a sibling of this
# repo). If your ratatosk checkout is elsewhere, set RATATOSK_DIR and make
# `../ratatosk` a symlink to it, or edit rust/Cargo.toml.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
rust_dir="$here/rust"

ratatosk="${RATATOSK_DIR:-$(cd "$here/.." && pwd)/ratatosk}"
if [ ! -f "$ratatosk/crates/core/Cargo.toml" ]; then
    echo "ratatosk core not found at $ratatosk (set RATATOSK_DIR)" >&2
    exit 1
fi

# NDK: explicit env first, then the newest under the SDK.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
    ndk="$(find "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
[ -n "$ndk" ] && [ -d "$ndk/toolchains/llvm" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }

case "$(uname -s)" in
    Darwin) host="darwin-x86_64" ;;
    Linux)  host="linux-x86_64" ;;
    *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host"
[ -d "$toolchain" ] || { echo "NDK toolchain not found at $toolchain" >&2; exit 1; }
export PATH="$toolchain/bin:$PATH"

api=26
abis="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

rust_target() {
    case "$1" in
        arm64-v8a)   echo aarch64-linux-android ;;
        armeabi-v7a) echo armv7-linux-androideabi ;;
        x86_64)      echo x86_64-linux-android ;;
        *) echo "unknown ABI $1" >&2; exit 1 ;;
    esac
}

clang_prefix() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android$api" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi$api" ;;
        x86_64)      echo "x86_64-linux-android$api" ;;
    esac
}

echo "core:    $ratatosk ($(git -C "$ratatosk" log -1 --format='%h %s' 2>/dev/null || echo 'no git'))"
echo "ndk:     $ndk"
echo "targets: $abis"

for abi in $abis; do
    target="$(rust_target "$abi")"
    prefix="$(clang_prefix "$abi")"
    upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"

    export "CARGO_TARGET_${upper}_LINKER=${prefix}-clang"
    export "CC_${target//-/_}=${prefix}-clang"
    export "CXX_${target//-/_}=${prefix}-clang++"
    export "AR_${target//-/_}=llvm-ar"
    export "RANLIB_${target//-/_}=llvm-ranlib"

    echo "── $abi → $target"
    cargo build --manifest-path "$rust_dir/Cargo.toml" \
        --release --target "$target"

    mkdir -p "$here/app/src/main/jniLibs/$abi"
    cp "$rust_dir/target/$target/release/libratatosk_android.so" \
        "$here/app/src/main/jniLibs/$abi/libratatosk_android.so"
done

echo "done: $(du -sh "$here/app/src/main/jniLibs" | cut -f1) in app/src/main/jniLibs"
