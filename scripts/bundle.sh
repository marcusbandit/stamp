#!/usr/bin/env bash
# Build the Play Store bundle, signed with the upload key.
# The key and its passwords live in ~/.config/stamp/, never in the repo. Make them once with
# scripts/setup-upload-key.sh. Output: app/build/outputs/bundle/release/app-release.aab
set -euo pipefail
cd "$(dirname "$0")/.."
PROPS="${STAMP_UPLOAD_PROPS:-${XDG_CONFIG_HOME:-$HOME/.config}/stamp/upload.properties}"
if [[ ! -f "$PROPS" ]]; then
  echo "bundle.sh: no upload key at $PROPS. Make one with scripts/setup-upload-key.sh" >&2
  exit 1
fi
scripts/build.sh :app:bundleRelease "-Pstamp.uploadKey=$PROPS" "$@"

# Check the bundle is signed by the upload key, not the debug key or nothing.
JAVA_BIN="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin"
aab=app/build/outputs/bundle/release/app-release.aab
prop() { sed -n "s/^$1=//p" "$PROPS"; }
export STAMP_KEY_PASS
STAMP_KEY_PASS=$(prop storePassword)
want=$("$JAVA_BIN/keytool" -list -v -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
  -storepass:env STAMP_KEY_PASS | sed -n 's/^\s*SHA256: //p')
got=$("$JAVA_BIN/keytool" -printcert -jarfile "$aab" | sed -n 's/^\s*SHA256: //p' | head -1)
if [[ -z "$want" || "$want" != "$got" ]]; then
  echo "bundle.sh: $aab is not signed by the upload key (got: ${got:-no signature})." >&2
  exit 1
fi
echo "$aab"
