#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
ANDROID_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)
SIGNING_DIR="${ANDROID_DIR}/.signing"
KEYSTORE_FILE="${SIGNING_DIR}/wifishare-release.p12"
PROPERTIES_FILE="${ANDROID_DIR}/signing.properties"

command -v openssl >/dev/null 2>&1 || {
  printf 'error: openssl is required\n' >&2
  exit 2
}
command -v keytool >/dev/null 2>&1 || {
  printf 'error: keytool is required; set JAVA_HOME to a JDK first\n' >&2
  exit 2
}

if [ -e "$KEYSTORE_FILE" ] || [ -e "$PROPERTIES_FILE" ]; then
  printf 'error: release signing already exists; refusing to overwrite it\n' >&2
  exit 2
fi

umask 077
mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"
SIGNING_PASSWORD=$(openssl rand -hex 32)
export SIGNING_PASSWORD

keytool -genkeypair \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -storepass:env SIGNING_PASSWORD \
  -keypass:env SIGNING_PASSWORD \
  -alias wifishare-release \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=WifiShare Release,O=iawnix" \
  -noprompt

{
  printf '%s\n' 'storeFile=.signing/wifishare-release.p12'
  printf 'storePassword=%s\n' "$SIGNING_PASSWORD"
  printf '%s\n' 'keyAlias=wifishare-release'
  printf 'keyPassword=%s\n' "$SIGNING_PASSWORD"
} > "$PROPERTIES_FILE"

chmod 600 "$KEYSTORE_FILE" "$PROPERTIES_FILE"
unset SIGNING_PASSWORD

printf 'Release signing created:\n'
printf '  keystore:   %s\n' "$KEYSTORE_FILE"
printf '  properties: %s\n' "$PROPERTIES_FILE"
printf 'Back up both files securely. Losing the key prevents future app updates.\n'
