#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
ANDROID_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)

: "${JAVA_HOME:=/home/iaw/soft/jdk21-local/usr/lib/jvm/java-21-openjdk-amd64}"
: "${ANDROID_HOME:=/home/iaw/soft/android/sdk}"
: "${ANDROID_SDK_ROOT:=$ANDROID_HOME}"
: "${GRADLE_USER_HOME:=${ANDROID_DIR}/.gradle-local}"
: "${ANDROID_USER_HOME:=${ANDROID_DIR}/.android-user}"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT GRADLE_USER_HOME ANDROID_USER_HOME
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

if [ -f "${ANDROID_DIR}/signing.properties" ]; then
  [ "$(stat -c '%a' "${ANDROID_DIR}/signing.properties")" = "600" ] || {
    printf 'error: signing.properties must have mode 600\n' >&2
    exit 2
  }
  SIGNING_STORE=$(sed -n 's/^storeFile=//p' "${ANDROID_DIR}/signing.properties" | head -1)
else
  SIGNING_STORE=${WIFISHARE_SIGNING_STORE_FILE:-}
fi
case "$SIGNING_STORE" in
  /*) ;;
  *) SIGNING_STORE="${ANDROID_DIR}/${SIGNING_STORE}" ;;
esac
[ "$(stat -c '%a' "$SIGNING_STORE" 2>/dev/null || true)" = "600" ] || {
  printf 'error: release keystore must exist with mode 600\n' >&2
  exit 2
}

GRADLE_BIN=${GRADLE_BIN:-/home/iaw/soft/gradle/gradle-8.9/bin/gradle}
[ -x "$GRADLE_BIN" ] || GRADLE_BIN=$(command -v gradle || true)
[ -n "$GRADLE_BIN" ] || {
  printf 'error: Gradle was not found\n' >&2
  exit 2
}

BUILD_TOOLS_DIR=$(find "${ANDROID_HOME}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
AAPT="${BUILD_TOOLS_DIR}/aapt"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
[ -x "$AAPT" ] && [ -x "$APKSIGNER" ] && [ -x "$ZIPALIGN" ] || {
  printf 'error: Android build tools were not found\n' >&2
  exit 2
}

cd "$ANDROID_DIR"
"$GRADLE_BIN" --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintRelease assembleRelease

SOURCE_APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
[ -f "$SOURCE_APK" ] || {
  printf 'error: signed release APK was not produced\n' >&2
  exit 2
}

BADGING=$($AAPT dump badging "$SOURCE_APK")
printf '%s\n' "$BADGING" | grep -q "package: name='io.iaw.lanshare'" || {
  printf 'error: unexpected package name\n' >&2
  exit 2
}
if printf '%s\n' "$BADGING" | grep -q 'application-debuggable'; then
  printf 'error: release APK is debuggable\n' >&2
  exit 2
fi

SIGNATURE=$($APKSIGNER verify --verbose --print-certs "$SOURCE_APK")
printf '%s\n' "$SIGNATURE" | grep -q '^Verifies$' || {
  printf 'error: APK signature verification failed\n' >&2
  exit 2
}
if printf '%s\n' "$SIGNATURE" | grep -q 'CN=Android Debug'; then
  printf 'error: release APK uses an Android Debug certificate\n' >&2
  exit 2
fi
$ZIPALIGN -c -P 16 4 "$SOURCE_APK"

VERSION_NAME=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$VERSION_NAME" ] || {
  printf 'error: APK versionName could not be read\n' >&2
  exit 2
}
DESTINATION="${ANDROID_DIR}/WifiShare-v${VERSION_NAME}-release.apk"
cp "$SOURCE_APK" "$DESTINATION"
chmod 644 "$DESTINATION"

printf '%s\n' "$SIGNATURE"
sha256sum "$DESTINATION"
stat -c '%s bytes  %n' "$DESTINATION"
