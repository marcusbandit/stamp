#!/usr/bin/env bash
# Make the Play upload key once per build machine, outside the repo:
#   ~/.config/stamp/upload.jks          the keystore
#   ~/.config/stamp/upload.properties   its path, alias and password, mode 600
# Play App Signing holds the real app signing key. If this upload key is lost, ask for an
# upload key reset in Play Console; nothing is lost for installed users.
set -euo pipefail
umask 077
DIR="${XDG_CONFIG_HOME:-$HOME/.config}/stamp"
JKS="$DIR/upload.jks"
PROPS="$DIR/upload.properties"
KEYTOOL="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}/bin/keytool"
if [[ -f "$JKS" && -f "$PROPS" ]]; then
  echo "setup-upload-key.sh: $JKS already exists, leaving it alone." >&2
  exit 0
fi
if [[ -e "$JKS" ]]; then
  echo "setup-upload-key.sh: $JKS exists but $PROPS does not, so its password is unknown." >&2
  echo "Move $JKS aside and run this again." >&2
  exit 1
fi
mkdir -p "$DIR" && chmod 700 "$DIR"
# The password goes to keytool through the environment, never on its command line.
export STAMP_KEY_PASS
STAMP_KEY_PASS=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
# Write the properties first so an interrupted run never leaves a keystore nobody can open.
cat > "$PROPS" <<PROPS
storeFile=$JKS
storePassword=$STAMP_KEY_PASS
keyAlias=upload
keyPassword=$STAMP_KEY_PASS
PROPS
if ! "$KEYTOOL" -genkeypair -keystore "$JKS" -storetype PKCS12 -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env STAMP_KEY_PASS -keypass:env STAMP_KEY_PASS -dname "CN=Stamp upload key"; then
  rm -f "$PROPS" "$JKS"
  exit 1
fi
echo "Upload key made at $JKS. Back up $DIR somewhere private."
"$KEYTOOL" -list -v -keystore "$JKS" -storepass:env STAMP_KEY_PASS | grep -E "SHA256:"
