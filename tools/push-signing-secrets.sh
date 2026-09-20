#!/usr/bin/env bash
#
# Upload the release signing key to GitHub Actions secrets.
#
# Run this from a machine where `gh` is authenticated. It reads
# secrets/release.properties and secrets/release.keystore.base64, produced by
# tools/make-release-key.sh.
#
# Nothing is echoed: passwords go to gh over stdin, never onto the command
# line, where they would land in shell history and in the process table.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="$REPO/secrets"

command -v gh >/dev/null || { echo "gh is not installed: https://cli.github.com" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated. Run: gh auth login" >&2; exit 1; }

[ -f "$OUT/release.properties" ] || { echo "Missing $OUT/release.properties -- run tools/make-release-key.sh" >&2; exit 1; }
[ -f "$OUT/release.keystore.base64" ] || { echo "Missing $OUT/release.keystore.base64" >&2; exit 1; }

prop() { sed -n "s/^$1=//p" "$OUT/release.properties"; }

gh secret set KEYSTORE_B64      < "$OUT/release.keystore.base64"
printf '%s' "$(prop storePassword)" | gh secret set KEYSTORE_PASSWORD
printf '%s' "$(prop keyAlias)"      | gh secret set KEY_ALIAS
printf '%s' "$(prop keyPassword)"   | gh secret set KEY_PASSWORD

echo
echo "Set: KEYSTORE_B64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD"
gh secret list
