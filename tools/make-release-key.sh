#!/usr/bin/env bash
#
# Generate the release signing key for pm3-droid.
#
# The signing key IS the app's identity on Android. Once a user installs an
# APK signed with it, every later update must be signed with the same key --
# there is no way to migrate except uninstall and reinstall, losing app data.
# So: generate this once, back it up somewhere you will still have in ten
# years, and never commit it.
#
# Outputs (all gitignored):
#   secrets/release.keystore        the key itself
#   secrets/release.properties      alias + passwords, for local release builds
#   secrets/release.keystore.base64 the blob for the KEYSTORE_B64 CI secret
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="$REPO/secrets"

ALIAS=${ALIAS:-release}
# 10000 days (~27 years). Google Play requires a key valid past 2033; a key
# that expires mid-life strands the app, and re-keying is not possible.
VALIDITY=${VALIDITY:-10000}
DNAME=${DNAME:-"CN=pm3-droid, OU=pm3-droid, O=pm3-droid, C=US"}

KEYSTORE="$OUT/release.keystore"

if [ -e "$KEYSTORE" ]; then
    echo "Refusing to overwrite $KEYSTORE" >&2
    echo "Overwriting it would orphan every install signed with the old key." >&2
    exit 1
fi

mkdir -p "$OUT"
chmod 700 "$OUT"

# Generated rather than prompted so the password never lands in shell history
# or a terminal transcript.
if [ -n "${KEYSTORE_PASSWORD:-}" ]; then
    PASSWORD="$KEYSTORE_PASSWORD"
else
    # Read a bounded chunk first. Piping an endless /dev/urandom into `head`
    # makes head close the pipe, which SIGPIPEs the producer and trips
    # `set -o pipefail` -- the script exits 141 before writing anything.
    raw="$(head -c 256 /dev/urandom | base64 | LC_ALL=C tr -dc 'A-Za-z0-9')"
    PASSWORD="${raw:0:40}"
fi

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity "$VALIDITY" \
    -dname "$DNAME" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    >/dev/null

# PKCS12 uses one password for store and key; keep both keys in the file so
# the Gradle properties map 1:1 onto the CI secrets.
umask 077
cat > "$OUT/release.properties" <<EOF
storeFile=$KEYSTORE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
EOF

base64 -w0 < "$KEYSTORE" > "$OUT/release.keystore.base64"
chmod 600 "$KEYSTORE" "$OUT/release.properties" "$OUT/release.keystore.base64"

echo "Created $KEYSTORE"
echo
echo "Certificate fingerprint (this is public -- it is what users verify):"
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" \
    | grep -E "SHA256:|Valid from" | sed 's/^/  /'
echo
cat <<'EOF'
Next steps:

  1. Back up secrets/release.keystore somewhere durable and offline.
     Losing it means you can never update an installed app again.

  2. Add four repository secrets on GitHub
     (Settings -> Secrets and variables -> Actions):

       KEYSTORE_B64        contents of secrets/release.keystore.base64
       KEYSTORE_PASSWORD   storePassword from secrets/release.properties
       KEY_ALIAS           keyAlias     from secrets/release.properties
       KEY_PASSWORD        keyPassword  from secrets/release.properties

     Or, with the gh CLI, from the repo root:

       gh secret set KEYSTORE_B64      < secrets/release.keystore.base64
       gh secret set KEYSTORE_PASSWORD --body "$(sed -n 's/^storePassword=//p' secrets/release.properties)"
       gh secret set KEY_ALIAS         --body "$(sed -n 's/^keyAlias=//p'      secrets/release.properties)"
       gh secret set KEY_PASSWORD      --body "$(sed -n 's/^keyPassword=//p'   secrets/release.properties)"

  3. Local release builds pick secrets/release.properties up automatically.
EOF
