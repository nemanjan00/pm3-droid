#!/usr/bin/env bash
#
# Build the firmware matrix defined in matrix.conf.
#
# Each variant produces a fullimage.elf + bootrom.elf pair, collected under
# out/<id>/ together with a manifest the app reads to populate the flash UI.
#
# The build is *not* parallel across variants: the pm3 Makefile caches the
# platform in .Makefile.options.cache at the tree root and errors out if it
# changes without a clean, so variants must be serialised.
#
# Usage: ./build-matrix.sh [id ...]      (default: every variant in matrix.conf)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PM3_SRC=${PM3_SRC:-/work/project/proxmark3}
ARM_TOOLCHAIN=${ARM_TOOLCHAIN:-/work/toolchains/arm-gnu-toolchain-13.3.rel1-x86_64-arm-none-eabi}
OUT=${OUT:-$HERE/out}
JOBS=${JOBS:-$(nproc)}

export PATH="$ARM_TOOLCHAIN/bin:$PATH"
command -v arm-none-eabi-gcc >/dev/null || {
    echo "arm-none-eabi-gcc not on PATH (looked in $ARM_TOOLCHAIN/bin)" >&2; exit 1; }

WANTED=("$@")
want() {
    [ ${#WANTED[@]} -eq 0 ] && return 0
    for w in "${WANTED[@]}"; do [ "$w" = "$1" ] && return 0; done
    return 1
}

mkdir -p "$OUT"
MANIFEST="$OUT/manifest.json"
: > "$OUT/.entries"

trim() { local v="$*"; v="${v#"${v%%[![:space:]]*}"}"; echo "${v%"${v##*[![:space:]]}"}"; }

while IFS='|' read -r id platform extras vars desc; do
    id=$(trim "$id"); platform=$(trim "$platform"); extras=$(trim "$extras")
    vars=$(trim "$vars"); desc=$(trim "$desc")
    case "$id" in ''|\#*) continue;; esac
    want "$id" || continue

    echo "=============================================================="
    echo "==> $id  ($platform${extras:+ / $extras})  -- $desc"
    echo "=============================================================="

    dest="$OUT/$id"
    mkdir -p "$dest"

    # shellcheck disable=SC2086
    (
        cd "$PM3_SRC"
        make clean >/dev/null 2>&1 || true
        make PLATFORM="$platform" PLATFORM_EXTRAS="$extras" $vars \
             -j"$JOBS" fullimage bootrom
    ) > "$dest/build.log" 2>&1 || { echo "FAILED -- see $dest/build.log"; tail -20 "$dest/build.log"; continue; }

    cp "$PM3_SRC/armsrc/obj/fullimage.elf" "$dest/fullimage.elf"
    cp "$PM3_SRC/bootrom/obj/bootrom.elf"  "$dest/bootrom.elf"

    fw_size=$(stat -c%s "$dest/fullimage.elf")
    printf '%s|%s|%s|%s|%s|%s\n' \
        "$id" "$platform" "$extras" "$desc" "$fw_size" \
        "$(sha256sum "$dest/fullimage.elf" | cut -d' ' -f1)" >> "$OUT/.entries"

    echo "==> $id ok  ($(du -h "$dest/fullimage.elf" | cut -f1) fullimage)"
done < "$HERE/matrix.conf"

# Manifest consumed by the app's flash screen.
{
    echo '{'
    echo '  "generated": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",'
    echo '  "pm3_commit": "'"$(git -C "$PM3_SRC" rev-parse --short HEAD)"'",'
    echo '  "variants": ['
    first=1
    while IFS='|' read -r id platform extras desc size sha; do
        [ $first -eq 1 ] || echo '    ,'
        first=0
        printf '    {"id":"%s","platform":"%s","extras":"%s","description":"%s","size":%s,"sha256":"%s"}\n' \
            "$id" "$platform" "$extras" "$desc" "$size" "$sha"
    done < "$OUT/.entries"
    echo '  ]'
    echo '}'
} > "$MANIFEST"
rm -f "$OUT/.entries"

echo
echo "==> manifest: $MANIFEST"
