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
PACKAGE="com.madeinhk.cantonesevoicekeyboard"
QWEN_ASR_DIR="${QWEN_ASR_DIR:-$PROJECT_DIR/../QwenASR}"
QWEN_ASR_TARGET_DIR="${QWEN_ASR_TARGET_DIR:-/private/tmp/qwen-asr-android-release-target}"
HANDWRITING_TARGET_DIR="${HANDWRITING_TARGET_DIR:-/private/tmp/handwriting-android-release-target}"

# Where the converted model lives on the build machine. scripts/reshard_model.py
# combines and splits its model*.safetensors tensors into two ≤480 MiB shards.
SOURCE_MODEL_DIR="/Volumes/SSD/qwen-asr-v20-0.6b-kleidiai-i8mm-full-encoder-packed"
# Two asset pack module asset dirs. Shard 1 + tokenizer files go in pack A,
# shard 2 in pack B. Files placed here are delivered to the device by Play
# Asset Delivery (fast-follow), and locally by bundletool's --local-testing.
PACK_A_ASSETS="$PROJECT_DIR/model_pack_a/src/main/assets/qwen-asr-m4-lmhead-int8"
PACK_B_ASSETS="$PROJECT_DIR/model_pack_b/src/main/assets/qwen-asr-m4-lmhead-int8"
RESHARD_SCRIPT="$PROJECT_DIR/scripts/reshard_model.py"
HANDWRITING_SOURCE_DIR="/Volumes/SSD/stust-handwriting-full/artifacts/sdt-traditional-recognition-full-lr1e-3"
HANDWRITING_ASSETS="$PROJECT_DIR/app/src/main/assets/handwriting"

# Pinned bundletool; auto-downloaded into .tools/ on first use.
BUNDLETOOL_VERSION="1.18.1"
TOOLS_DIR="$PROJECT_DIR/.tools"
BUNDLETOOL_JAR="$TOOLS_DIR/bundletool-all-${BUNDLETOOL_VERSION}.jar"

# --release builds a Play-uploadable AAB (signed if keystore.properties is set)
# and skips the on-device install. Default is the on-device debug flow.
RELEASE=false
for arg in "$@"; do
    if [ "$arg" == "--release" ] || [ "$arg" == "-r" ]; then
        RELEASE=true
    fi
done

if [ "$RELEASE" = "true" ]; then
    AAB_PATH="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
    APKS_PATH="$PROJECT_DIR/build/app-release.apks"
else
    AAB_PATH="$PROJECT_DIR/app/build/outputs/bundle/debug/app-debug.aab"
    APKS_PATH="$PROJECT_DIR/build/app-debug.apks"
fi

# ---------------------------------------------------------------------------
# 1. Locate ADB and verify device connection
# ---------------------------------------------------------------------------
if [ "$RELEASE" = "true" ]; then
    echo -e "${COLOR_INFO}[Step 1/7] Release mode: skipping device check (AAB is for Play upload).${COLOR_RESET}"
    DEVICE_ID=""
    ADB=""
else
    echo -e "${COLOR_INFO}[Step 1/7] Locating ADB and verifying device connection...${COLOR_RESET}"
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
fi

# ---------------------------------------------------------------------------
# 2. Compile JNI Library
# ---------------------------------------------------------------------------
SO_DEST="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libqwen_asr.so"
HWR_SO_DEST="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libstust_handwriting_inference.so"

echo -e "${COLOR_INFO}[Step 2/7] Compiling native libraries...${COLOR_RESET}"
if [ ! -d "$QWEN_ASR_DIR" ]; then
    echo -e "${COLOR_ERROR}Error: QwenASR workspace directory not found at '$QWEN_ASR_DIR'.${COLOR_RESET}"
    exit 1
fi

pushd "$QWEN_ASR_DIR" > /dev/null

echo -e "${COLOR_INFO}Building Rust crate 'qwen-asr' with native DOTPROD features and OpenBLAS...${COLOR_RESET}"

CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$QWEN_ASR_DIR/android-aarch64-clang" \
CC_aarch64_linux_android="$QWEN_ASR_DIR/android-aarch64-clang" \
AR_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar \
RANLIB_aarch64_linux_android=/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ranlib \
RUSTFLAGS="-C target-feature=+dotprod" \
PATH="/Users/tony/.rustup/toolchains/stable-aarch64-apple-darwin/bin:/Users/tony/Library/Android/sdk/ndk/30.0.14904198/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH" \
OPENBLAS_ANDROID_LIB_DIR=/private/tmp/OpenBLAS-0.3.34 \
OPENBLAS_ANDROID_STATIC=1 \
KLEIDIAI_LIB_DIR=/private/tmp/qwen-kleidiai-review/build-android-qwen \
CARGO_TARGET_DIR="$QWEN_ASR_TARGET_DIR" \
cargo build --release --target aarch64-linux-android --features "blas android kleidiai" -p qwen-asr

popd > /dev/null

echo -e "${COLOR_INFO}Copying compiled .so to keyboard project...${COLOR_RESET}"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
cp "$QWEN_ASR_TARGET_DIR/aarch64-linux-android/release/libqwen_asr.so" "$SO_DEST"
echo -e "${COLOR_SUCCESS}Native library successfully compiled and placed in jniLibs!${COLOR_RESET}"

echo -e "${COLOR_INFO}Compiling the Rust handwriting inference library...${COLOR_RESET}"
pushd "$PROJECT_DIR/../handwriting-recognizer/rust" > /dev/null
CARGO_TARGET_DIR="$HANDWRITING_TARGET_DIR" cargo ndk -t arm64-v8a build --release --features android
popd > /dev/null
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
cp "$HANDWRITING_TARGET_DIR/aarch64-linux-android/release/libstust_handwriting_inference.so" "$HWR_SO_DEST"
echo -e "${COLOR_SUCCESS}Handwriting native library successfully compiled and placed in jniLibs!${COLOR_RESET}"

# ---------------------------------------------------------------------------
# 3. Stage the ASR model into the Play Asset Delivery pack
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 3/7] Staging Qwen ASR model shards into both asset packs...${COLOR_RESET}"
SHARD_A="$PACK_A_ASSETS/model-00001-of-00002.safetensors"
SHARD_B="$PACK_B_ASSETS/model-00002-of-00002.safetensors"
if [ -d "$SOURCE_MODEL_DIR" ]; then
    if ! command -v python3 &> /dev/null; then
        echo -e "${COLOR_ERROR}Error: python3 required to reshard the model.${COLOR_RESET}"
        exit 1
    fi
    echo -e "${COLOR_INFO}Running reshard_model.py from $SOURCE_MODEL_DIR...${COLOR_RESET}"
    python3 "$RESHARD_SCRIPT" \
        --source "$SOURCE_MODEL_DIR" \
        --pack-a "$PACK_A_ASSETS" \
        --pack-b "$PACK_B_ASSETS"
    echo -e "${COLOR_SUCCESS}Shards staged into both asset packs.${COLOR_RESET}"
elif [ -f "$SHARD_A" ] && [ -f "$SHARD_B" ]; then
    echo -e "${COLOR_SUCCESS}Shards already staged. Skipping reshard.${COLOR_RESET}"
else
    echo -e "${COLOR_ERROR}Error: Shards not staged and source '$SOURCE_MODEL_DIR' not found.${COLOR_RESET}"
    echo -e "${COLOR_ERROR}Either place shards under '$PACK_A_ASSETS' and '$PACK_B_ASSETS',${COLOR_RESET}"
    echo -e "${COLOR_ERROR}or place the source model at '$SOURCE_MODEL_DIR' so the script can shard it.${COLOR_RESET}"
    exit 1
fi

# ---------------------------------------------------------------------------
# 4. Stage the handwriting model bundle into app assets
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 4/7] Staging handwriting model and labels...${COLOR_RESET}"
if [ ! -f "$HANDWRITING_SOURCE_DIR/model.safetensors" ] || [ ! -f "$HANDWRITING_SOURCE_DIR/labels.txt" ]; then
    echo -e "${COLOR_ERROR}Error: handwriting model artifacts are missing from '$HANDWRITING_SOURCE_DIR'.${COLOR_RESET}"
    exit 1
fi
mkdir -p "$HANDWRITING_ASSETS"
cp "$HANDWRITING_SOURCE_DIR/model.safetensors" "$HANDWRITING_ASSETS/model.safetensors"
cp "$HANDWRITING_SOURCE_DIR/labels.txt" "$HANDWRITING_ASSETS/labels.txt"
echo -e "${COLOR_SUCCESS}Handwriting model assets staged.${COLOR_RESET}"

# ---------------------------------------------------------------------------
# 5. Ensure bundletool is available
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 5/7] Ensuring bundletool is available...${COLOR_RESET}"
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
# 6. Build the App Bundle (.aab) including the asset pack
# ---------------------------------------------------------------------------
cd "$PROJECT_DIR"
if [ "$RELEASE" = "true" ]; then
    echo -e "${COLOR_INFO}[Step 6/7] Building RELEASE App Bundle (.aab) for Play upload...${COLOR_RESET}"
    ./gradlew :app:bundleRelease
else
    echo -e "${COLOR_INFO}[Step 6/7] Building debug App Bundle (.aab)...${COLOR_RESET}"
    ./gradlew :app:bundleDebug
fi
if [ ! -f "$AAB_PATH" ]; then
    echo -e "${COLOR_ERROR}Error: Expected bundle not found at $AAB_PATH${COLOR_RESET}"
    exit 1
fi

if [ "$RELEASE" = "true" ]; then
    echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
    echo -e "${COLOR_SUCCESS}Release AAB built:${COLOR_RESET}"
    echo -e "${COLOR_SUCCESS}  $AAB_PATH${COLOR_RESET}"
    echo -e "${COLOR_SUCCESS}Upload it to Play Console. Skipping on-device install.${COLOR_RESET}"
    echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
    exit 0
fi

# ---------------------------------------------------------------------------
# 7. Convert to APK set with local-testing asset packs and install
# ---------------------------------------------------------------------------
echo -e "${COLOR_INFO}[Step 7/7] Generating APK set and installing via bundletool (--local-testing)...${COLOR_RESET}"
mkdir -p "$(dirname "$APKS_PATH")"
rm -f "$APKS_PATH"

# --local-testing embeds the fast-follow asset packs so install-apks pushes them to
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

# Freshness check across both packs: skip the full install only when every
# pack's master split on-device matches what we just built.
NEED_FULL_INSTALL=false
for PACK in model_pack_a model_pack_b; do
    PACK_ENTRY=$(unzip -Z1 "$APKS_PATH" 2>/dev/null | grep -E "${PACK}.*\.apk$" | head -n1)
    DEVICE_PACK="$LOCAL_TESTING_DIR/${PACK}-master.apk"
    if [ -z "$PACK_ENTRY" ]; then
        NEED_FULL_INSTALL=true
        break
    fi
    LOCAL_PACK_MD5=$(unzip -p "$APKS_PATH" "$PACK_ENTRY" | md5_of)
    DEVICE_PACK_MD5=$("$ADB" -s "$DEVICE_ID" shell "md5sum '$DEVICE_PACK' 2>/dev/null" | awk '{print $1}')
    if [ -z "$DEVICE_PACK_MD5" ] || [ "$LOCAL_PACK_MD5" != "$DEVICE_PACK_MD5" ]; then
        NEED_FULL_INSTALL=true
        break
    fi
done

if [ "$NEED_FULL_INSTALL" = "true" ]; then
    echo -e "${COLOR_WARNING}Asset pack changed or missing on device — full install (pushes the model packs)...${COLOR_RESET}"
    java -jar "$BUNDLETOOL_JAR" install-apks \
        --apks="$APKS_PATH" \
        --adb="$ADB" \
        --device-id="$DEVICE_ID"
else
    echo -e "${COLOR_SUCCESS}Asset packs unchanged on device — fast install (app splits only, keeping the packs).${COLOR_RESET}"
    APP_SPLITS_DIR="$PROJECT_DIR/build/app-splits"
    rm -rf "$APP_SPLITS_DIR"
    mkdir -p "$APP_SPLITS_DIR"
    # Extract only the app splits (everything except the asset packs) and reinstall them.
    unzip -Z1 "$APKS_PATH" | grep -E '\.apk$' | grep -Ev 'model_pack_a|model_pack_b' | while read -r entry; do
        unzip -o -j -q "$APKS_PATH" "$entry" -d "$APP_SPLITS_DIR"
    done
    "$ADB" -s "$DEVICE_ID" install-multiple -r -d "$APP_SPLITS_DIR"/*.apk
fi

echo -e "${COLOR_INFO}Launching companion configuration application...${COLOR_RESET}"
"$ADB" -s "$DEVICE_ID" shell am start -n "$PACKAGE/.MainActivity"

echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}         Cantonese Keyboard Successfully Deployed!             ${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
