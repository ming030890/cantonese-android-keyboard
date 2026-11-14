#!/usr/bin/env bash
# Emulator-only smoke check of the installed, minified release's actual JNI classes.
set -euo pipefail
if [[ $# != 2 || "$1" != emulator-* ]]; then
    echo 'Usage: bash scripts/run_minified_native_smoke.sh emulator-SERIAL /path/to/app-release.apk' >&2
    exit 1
fi
DEVICE="$1"
APK="$2"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
JAVA="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SMOKE_DIR=$(mktemp -d /private/tmp/keyboard-release-smoke.XXXXXX)
REMOTE="/data/local/tmp/keyboard-release-smoke-$$"
trap 'rm -rf "$SMOKE_DIR"; "$ADB" -s "$DEVICE" shell rm -rf "$REMOTE" >/dev/null 2>&1 || true' EXIT
mkdir -p "$SMOKE_DIR/classes" "$SMOKE_DIR/dex"
"$JAVA/bin/javac" --release 11 -classpath "$SDK/platforms/android-37.0/android.jar" \
    -d "$SMOKE_DIR/classes" "$PROJECT/scripts/android/NativeReleaseSmoke.java"
JAVA_HOME="$JAVA" "$SDK/build-tools/36.0.0/d8" --min-api 32 \
    --lib "$SDK/platforms/android-37.0/android.jar" \
    --output "$SMOKE_DIR/dex" "$SMOKE_DIR/classes/NativeReleaseSmoke.class"
"$ADB" -s "$DEVICE" install -r "$APK"
"$ADB" -s "$DEVICE" shell mkdir -p "$REMOTE"
"$ADB" -s "$DEVICE" push "$SMOKE_DIR/dex/classes.dex" "$REMOTE/classes.dex"
"$ADB" -s "$DEVICE" shell "CLASSPATH=$REMOTE/classes.dex app_process /system/bin NativeReleaseSmoke com.madeinhk.cantonesevoicekeyboard"
"$ADB" -s "$DEVICE" shell am start -W -n com.madeinhk.cantonesevoicekeyboard/com.madeinhk.cantonesevoicekeyboard.MainActivity
sleep 1
APP_PID=$("$ADB" -s "$DEVICE" shell pidof com.madeinhk.cantonesevoicekeyboard | tr -d '\r')
test -n "$APP_PID"
echo "Minified app remains running (PID $APP_PID)."
