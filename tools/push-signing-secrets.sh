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
printf '%s' "$(prop keyPassword)"   | gh secret set KEY_PASSWORD

# The alias is a repository *variable*, not a secret. It is not sensitive,
# and GitHub masks every occurrence of a secret's value in the logs -- an
# alias of "release" turns every logged path into
# app/build/outputs/apk/***/app-arm64-v8a-***.apk, which makes a failing
# build genuinely hard to read.
gh variable set KEY_ALIAS --body "$(prop keyAlias)"

# Remove a KEY_ALIAS left over from an earlier run of this script, for the
# same reason -- while it exists as a secret, its value stays masked.
if gh secret list --json name --jq '.[].name' 2>/dev/null | grep -qx KEY_ALIAS; then
    gh secret delete KEY_ALIAS
    echo "Deleted the redundant KEY_ALIAS secret (now a variable)."
fi

echo
echo "Secrets:   KEYSTORE_B64 KEYSTORE_PASSWORD KEY_PASSWORD"
echo "Variables: KEY_ALIAS"
gh secret list
gh variable list
