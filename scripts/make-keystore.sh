#!/usr/bin/env bash
# Makes the key release builds are signed with: omalauncher-release.jks and keystore.properties at the repo root
# (both gitignored). Needs a JDK for keytool. Keep both files: an update only installs over an app signed with the
# same key, so losing the key means uninstalling the app (and its data) to update it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JKS="$ROOT/omalauncher-release.jks"
PROPS="$ROOT/keystore.properties"
if [ -e "$JKS" ] || [ -e "$PROPS" ]; then
  echo "omalauncher-release.jks or keystore.properties already exists; not overwriting" >&2
  exit 1
fi

# od stops after 18 bytes by itself: no pipe for pipefail to see closing early (tr | head dies of SIGPIPE).
PASS="$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')"
keytool -genkeypair -v -keystore "$JKS" -storetype PKCS12 -alias omalauncher \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Omalauncher, O=Omalauncher, C=ID"

cat >"$PROPS" <<PROPS
storeFile=omalauncher-release.jks
storePassword=$PASS
keyAlias=omalauncher
keyPassword=$PASS
PROPS
chmod 600 "$JKS" "$PROPS"
echo "wrote $JKS and $PROPS"
