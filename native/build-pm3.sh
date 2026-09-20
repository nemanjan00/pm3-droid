#!/usr/bin/env bash
#
# Cross-compile the Proxmark3 client for Android and stage it into the app.
#
# The client is shipped inside the APK's native library directory (as
# libproxmark3.so) because since API 29 Android refuses to exec binaries from
# an app's data dir -- nativeLibraryDir is the only supported location.
#
# Usage: ./build-pm3.sh [abi ...]        (default: arm64-v8a armeabi-v7a)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

PM3_SRC=${PM3_SRC:-/work/project/proxmark3}
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/27.2.12479018}}
API=${ANDROID_API:-26}
OUT=${OUT:-$HERE/out}
JNILIBS="$REPO/app/src/main/jniLibs"
ASSETS="$REPO/app/src/main/assets"

ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a)

[ -d "$PM3_SRC" ] || { echo "PM3_SRC not found: $PM3_SRC" >&2; exit 1; }
[ -d "$NDK" ]     || { echo "NDK not found: $NDK" >&2; exit 1; }

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

apply_patches() {
    for p in "$HERE"/patches/*.patch; do
        [ -e "$p" ] || continue
        if git -C "$PM3_SRC" apply --check "$p" 2>/dev/null; then
            git -C "$PM3_SRC" apply "$p"
            echo "  applied $(basename "$p")"
        fi
    done
}

build_abi() {
    local abi=$1
    local build="$OUT/build-$abi"

    mkdir -p "$build"
    echo "==> configuring $abi"
    # SKIPPTHREAD: bionic folds pthread into libc, there is no libpthread.so.
    # SKIPBT:      client-side BlueZ; on Android the app owns the transports.
    # SKIPQT/GD/PYTHON/READLINE: no such libs in the NDK sysroot.
    # EMBED_*:     build bzip2/lz4 from source for the target.
    cmake -S "$PM3_SRC" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_FIRMWARE=OFF \
        -DSKIPQT=1 -DSKIPPYTHON=1 -DSKIPBT=1 -DSKIPGD=1 \
        -DSKIPREADLINE=1 -DSKIPPTHREAD=1 \
        -DSKIPJANSSONSYSTEM=1 -DSKIPWHEREAMISYSTEM=1 \
        -DEMBED_BZIP2=ON -DEMBED_LZ4=ON \
        > "$build/configure.log" 2>&1 || { tail -30 "$build/configure.log"; exit 1; }

    echo "==> building $abi"
    # bzip2/lz4 are ExternalProjects; ninja has no rule tying them to the link.
    cmake --build "$build" --target bzip2 lz4 -j"$(nproc)" >/dev/null
    cmake --build "$build" -j"$(nproc)"

    mkdir -p "$JNILIBS/$abi"
    cp "$build/client/proxmark3" "$JNILIBS/$abi/libproxmark3.so"
    "$STRIP" --strip-unneeded "$JNILIBS/$abi/libproxmark3.so"
    echo "==> $abi -> $JNILIBS/$abi/libproxmark3.so ($(du -h "$JNILIBS/$abi/libproxmark3.so" | cut -f1))"
}

stage_resources() {
    # The client locates dictionaries/scripts relative to its own executable,
    # which on Android is nativeLibraryDir (read-only). The app instead unpacks
    # this archive into its data dir and points the client at it via PM3_HOME.
    local tmp
    tmp=$(mktemp -d)
    mkdir -p "$ASSETS"
    for d in dictionaries luascripts lualibs cmdscripts pyscripts resources; do
        [ -d "$PM3_SRC/client/$d" ] && cp -r "$PM3_SRC/client/$d" "$tmp/"
    done
    ( cd "$tmp" && zip -qr "$ASSETS/pm3-resources.zip" . )
    rm -rf "$tmp"
    echo "==> resources -> $ASSETS/pm3-resources.zip ($(du -h "$ASSETS/pm3-resources.zip" | cut -f1))"
}

echo "==> patching $PM3_SRC"
apply_patches
for abi in "${ABIS[@]}"; do build_abi "$abi"; done
stage_resources
