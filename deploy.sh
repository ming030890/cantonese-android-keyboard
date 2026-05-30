#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# Define color codes for premium terminal output aesthetics
COLOR_INFO="\033[1;36m"    # Cyan
COLOR_SUCCESS="\033[1;32m" # Green
COLOR_WARNING="\033[1;33m" # Yellow
COLOR_ERROR="\033[1;31m"   # Red
COLOR_RESET="\033[0m"      # Reset

echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"
echo -e "${COLOR_INFO}           Cantonese Voice Keyboard Deployment Automator        ${COLOR_RESET}"
echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="com.example.cantonesevoicekeyboard"

# Where the raw model lives on the build machine (staged into the asset pack).
SOURCE_MODEL_DIR="/Volumes/SSD/qwen-asr-int8dec"
# The asset pack module's assets dir. Files placed here are delivered to the
# device by Play Asset Delivery (fast-follow), and locally by bundletool's
# --local-testing mode.
ASSET_PACK_MODEL_DIR="$PROJECT_DIR/model_pack/src/main/assets/qwen-asr-int8dec"

# Pinned bundletool; auto-downloaded into .tools/ on first use.
BUNDLETOOL_VERSION="1.18.1"
TOOLS_DIR="$PROJECT_DIR/.tools"
BUNDLETOOL_JAR="$TOOLS_DIR/bundletool-all-${BUNDLETOOL_VERSION}.jar"

AAB_PATH="$PROJECT_DIR/app/build/outputs/bundle/debug/app-debug.aab"
APKS_PATH="$PROJECT_DIR/build/app-debug.apks"

# ---------------------------------------------------------------------------
# 1. Locate ADB and verify device connection
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 1/6] Locating ADB and verifying device connection...${COLOR_RESET}"
ADB="/Users/tony/Library/Android/sdk/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    if command -v adb &> /dev/null; then
        ADB="adb"
    else
        echo -e "${COLOR_ERROR}Error: ADB not found at '$ADB' or in system PATH.${COLOR_RESET}"
        exit 1
    fi
fi

DEVICE_LIST=$("$ADB" devices | grep -v "List of devices" | grep "device" || true)
if [ -z "$DEVICE_LIST" ]; then
    echo -e "${COLOR_ERROR}Error: No Android device or emulator is connected via ADB.${COLOR_RESET}"
    exit 1
fi
DEVICE_ID=$(echo "$DEVICE_LIST" | awk '{print $1}' | head -n 1)
echo -e "${COLOR_SUCCESS}Connected device detected: $DEVICE_ID${COLOR_RESET}"

# ---------------------------------------------------------------------------
# 2. Compile JNI Library (Optional, pass --compile / -c to force)
# ---------------------------------------------------------------------------
COMPILE=false
for arg in "$@"; do
    if [ "$arg" == "--compile" ] || [ "$arg" == "-c" ]; then
        COMPILE=true
    fi
done

SO_DEST="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libqwen_asr.so"

if [ "$COMPILE" = "true" ] || [ ! -f "$SO_DEST" ]; then
    echo -e "${COLOR_INFO}[Step 2/6] Compiling native libqwen_asr.so shared library...${COLOR_RESET}"
    if [ ! -d "$PROJECT_DIR/../QwenASR" ]; then
        echo -e "${COLOR_ERROR}Error: QwenASR workspace directory not found at '../QwenASR'.${COLOR_RESET}"
        exit 1
    fi

    pushd "$PROJECT_DIR/../QwenASR" > /dev/null

    echo -e "${COLOR_INFO}Building Rust crate 'qwen-asr' with native DOTPROD features and OpenBLAS...${COLOR_RESET}"

    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=/Users/tony/Documents/keyboard/QwenASR/android-aarch64-clang \
    CC_aarch64_linux_android=/Users/tony/Documents/keyboard/QwenASR/android-aarch64-clang \
    AR_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar \
    RANLIB_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ranlib \
    RUSTFLAGS="-C target-feature=+dotprod" \
    PATH="/Users/tony/.rustup/toolchains/stable-aarch64-apple-darwin/bin:/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH" \
    OPENBLAS_ANDROID_LIB_DIR=/private/tmp/OpenBLAS \
    OPENBLAS_ANDROID_STATIC=1 \
    cargo build --release --target aarch64-linux-android --features "blas android" -p qwen-asr

    popd > /dev/null

    echo -e "${COLOR_INFO}Copying compiled .so to keyboard project...${COLOR_RESET}"
    mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
    cp "$PROJECT_DIR/../QwenASR/target/aarch64-linux-android/release/libqwen_asr.so" "$SO_DEST"
    echo -e "${COLOR_SUCCESS}Native library successfully compiled and placed in jniLibs!${COLOR_RESET}"
else
    echo -e "${COLOR_SUCCESS}[Step 2/6] Using existing libqwen_asr.so in jniLibs (Pass --compile to rebuild).${COLOR_RESET}"
fi

# ---------------------------------------------------------------------------
# 3. Stage the ASR model into the Play Asset Delivery pack
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 3/6] Staging Qwen ASR model into the asset pack...${COLOR_RESET}"
if [ -d "$ASSET_PACK_MODEL_DIR" ] && [ -n "$(ls -A "$ASSET_PACK_MODEL_DIR" 2>/dev/null)" ]; then
    echo -e "${COLOR_SUCCESS}Model already staged at model_pack/src/main/assets/qwen-asr-int8dec. Skipping copy.${COLOR_RESET}"
elif [ -d "$SOURCE_MODEL_DIR" ]; then
    echo -e "${COLOR_INFO}Copying model from $SOURCE_MODEL_DIR into the asset pack (~1.3 GB, one-time)...${COLOR_RESET}"
    mkdir -p "$ASSET_PACK_MODEL_DIR"
    cp -R "$SOURCE_MODEL_DIR/." "$ASSET_PACK_MODEL_DIR/"
    echo -e "${COLOR_SUCCESS}Model staged into asset pack.${COLOR_RESET}"
else
    echo -e "${COLOR_ERROR}Error: Model not staged and source '$SOURCE_MODEL_DIR' not found.${COLOR_RESET}"
    echo -e "${COLOR_ERROR}Place the model under '$ASSET_PACK_MODEL_DIR' or at '$SOURCE_MODEL_DIR'.${COLOR_RESET}"
    exit 1
fi

# ---------------------------------------------------------------------------
# 4. Ensure bundletool is available
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 4/6] Ensuring bundletool is available...${COLOR_RESET}"
if ! command -v java &> /dev/null; then
    echo -e "${COLOR_ERROR}Error: 'java' not found on PATH. bundletool requires a JDK.${COLOR_RESET}"
    exit 1
fi
if [ ! -f "$BUNDLETOOL_JAR" ]; then
    echo -e "${COLOR_WARNING}bundletool ${BUNDLETOOL_VERSION} not cached. Downloading...${COLOR_RESET}"
    mkdir -p "$TOOLS_DIR"
    curl -fsSL -o "$BUNDLETOOL_JAR" \
        "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"
    echo -e "${COLOR_SUCCESS}bundletool downloaded to .tools/.${COLOR_RESET}"
else
    echo -e "${COLOR_SUCCESS}Using cached bundletool at .tools/.${COLOR_RESET}"
fi

# ---------------------------------------------------------------------------
# 5. Build the App Bundle (.aab) including the asset pack
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 5/6] Building debug App Bundle (.aab)...${COLOR_RESET}"
cd "$PROJECT_DIR"
./gradlew :app:bundleDebug
if [ ! -f "$AAB_PATH" ]; then
    echo -e "${COLOR_ERROR}Error: Expected bundle not found at $AAB_PATH${COLOR_RESET}"
    exit 1
fi

# ---------------------------------------------------------------------------
# 6. Convert to APK set with local-testing asset packs and install
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 6/6] Generating APK set and installing via bundletool (--local-testing)...${COLOR_RESET}"
mkdir -p "$(dirname "$APKS_PATH")"
rm -f "$APKS_PATH"

# --local-testing embeds the fast-follow asset pack so install-apks pushes it to
# device-local storage, where the Play Asset Delivery library resolves it exactly
# as it would after a Play install. No adb push / sideloading required.
java -jar "$BUNDLETOOL_JAR" build-apks \
    --bundle="$AAB_PATH" \
    --output="$APKS_PATH" \
    --local-testing \
    --connected-device \
    --adb="$ADB" \
    --device-id="$DEVICE_ID"

# Decide whether the ~1.4 GB asset pack actually needs re-pushing. A full
# `install-apks` re-pushes every split (including the model pack) every run, which
# is slow. Compare the freshly-built pack's checksum against the copy already on
# the device; if they match we only reinstall the small app splits and leave the
# pack in place.
md5_of() {
    # Hash from stdin, portable across macOS (md5) and Linux (md5sum).
    if command -v md5 &> /dev/null; then md5 -q; else md5sum | awk '{print $1}'; fi
}

LOCAL_TESTING_DIR="/sdcard/Android/data/$PACKAGE/files/local_testing"
DEVICE_PACK="$LOCAL_TESTING_DIR/model_pack-master.apk"
PACK_ENTRY=$(unzip -Z1 "$APKS_PATH" 2>/dev/null | grep -E 'model_pack.*\.apk$' | head -n1)

NEED_FULL_INSTALL=true
if [ -n "$PACK_ENTRY" ]; then
    LOCAL_PACK_MD5=$(unzip -p "$APKS_PATH" "$PACK_ENTRY" | md5_of)
    DEVICE_PACK_MD5=$("$ADB" -s "$DEVICE_ID" shell "md5sum '$DEVICE_PACK' 2>/dev/null" | awk '{print $1}')
    if [ -n "$DEVICE_PACK_MD5" ] && [ "$LOCAL_PACK_MD5" == "$DEVICE_PACK_MD5" ]; then
        NEED_FULL_INSTALL=false
    fi
fi

if [ "$NEED_FULL_INSTALL" = "true" ]; then
    echo -e "${COLOR_WARNING}Asset pack changed or missing on device — full install (pushes the model pack)...${COLOR_RESET}"
    java -jar "$BUNDLETOOL_JAR" install-apks \
        --apks="$APKS_PATH" \
        --adb="$ADB" \
        --device-id="$DEVICE_ID"
else
    echo -e "${COLOR_SUCCESS}Asset pack unchanged on device — fast install (app splits only, keeping the pack).${COLOR_RESET}"
    APP_SPLITS_DIR="$PROJECT_DIR/build/app-splits"
    rm -rf "$APP_SPLITS_DIR"
    mkdir -p "$APP_SPLITS_DIR"
    # Extract only the app splits (everything except the asset pack) and reinstall them.
    unzip -Z1 "$APKS_PATH" | grep -E '\.apk$' | grep -v 'model_pack' | while read -r entry; do
        unzip -o -j -q "$APKS_PATH" "$entry" -d "$APP_SPLITS_DIR"
    done
    "$ADB" -s "$DEVICE_ID" install-multiple -r -d "$APP_SPLITS_DIR"/*.apk
fi

echo -e "${COLOR_INFO}Launching companion configuration application...${COLOR_RESET}"
"$ADB" -s "$DEVICE_ID" shell am start -n "$PACKAGE/.MainActivity"

echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}         Cantonese Keyboard Successfully Deployed!             ${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
