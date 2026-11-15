#!/usr/bin/env bash
# Build the public APK, signed with the release key, and check that it really is.
# The key lives in ~/.config/stamp/ (scripts/setup-upload-key.sh), never in the repo.
# Output: app/build/outputs/apk/release/stamp.apk, the name every GitHub release carries.
set -euo pipefail
cd "$(dirname "$0")/.."
PROPS="${STAMP_UPLOAD_PROPS:-${XDG_CONFIG_HOME:-$HOME/.config}/stamp/upload.properties}"
if [[ ! -f "$PROPS" ]]; then
  echo "apk.sh: no release key at $PROPS. Copy ~/.config/stamp/ from the machine that has it." >&2
  exit 1
fi
scripts/build.sh :app:assembleRelease "-Pstamp.uploadKey=$PROPS" "$@"

out=app/build/outputs/apk/release
JAVA_BIN="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
apksigner=$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
prop() { sed -n "s/^$1=//p" "$PROPS"; }
export STAMP_KEY_PASS
STAMP_KEY_PASS=$(prop storePassword)
want=$("$JAVA_BIN/keytool" -list -v -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
  -storepass:env STAMP_KEY_PASS | sed -n 's/^\s*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
got=$("$apksigner" verify --print-certs "$out/app-release.apk" 2>/dev/null \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
if [[ -z "$want" || "$want" != "$got" ]]; then
  echo "apk.sh: $out/app-release.apk is not signed by the release key (got: ${got:-no signature})." >&2
  exit 1
fi
cp "$out/app-release.apk" "$out/stamp.apk"
echo "$out/stamp.apk"
