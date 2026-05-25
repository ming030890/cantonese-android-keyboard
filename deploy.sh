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

# 1. Locate ADB
echo -e "${COLOR_INFO}[Step 1/5] Locating ADB and verifying device connection...${COLOR_RESET}"
ADB="/Users/tony/Library/Android/sdk/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    if command -v adb &> /dev/null; then
        ADB="adb"
    else
        echo -e "${COLOR_ERROR}Error: ADB not found at '$ADB' or in system PATH.${COLOR_RESET}"
        exit 1
    fi
fi

# Verify device is connected
DEVICE_LIST=$("$ADB" devices | grep -v "List of devices" | grep "device" || true)
if [ -z "$DEVICE_LIST" ]; then
    echo -e "${COLOR_ERROR}Error: No Android device or emulator is connected via ADB.${COLOR_RESET}"
    exit 1
fi
DEVICE_ID=$(echo "$DEVICE_LIST" | awk '{print $1}' | head -n 1)
echo -e "${COLOR_SUCCESS}Connected device detected: $DEVICE_ID${COLOR_RESET}"

# 2. Compile JNI Library (Optional)
COMPILE=false
for arg in "$@"; do
    if [ "$arg" == "--compile" ] || [ "$arg" == "-c" ]; then
        COMPILE=true
    fi
done

SO_DEST="app/src/main/jniLibs/arm64-v8a/libqwen_asr.so"

if [ "$COMPILE" = "true" ] || [ ! -f "$SO_DEST" ]; then
    echo -e "${COLOR_INFO}[Step 2/5] Compiling native libqwen_asr.so shared library...${COLOR_RESET}"
    if [ ! -d "../QwenASR" ]; then
        echo -e "${COLOR_ERROR}Error: QwenASR workspace directory not found at '../QwenASR'.${COLOR_RESET}"
        exit 1
    fi
    
    # Save current directory
    CURRENT_DIR=$(pwd)
    cd ../QwenASR
    
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
    
    cd "$CURRENT_DIR"
    
    echo -e "${COLOR_INFO}Copying compiled .so to keyboard project...${COLOR_RESET}"
    mkdir -p app/src/main/jniLibs/arm64-v8a
    cp ../QwenASR/target/aarch64-linux-android/release/libqwen_asr.so "$SO_DEST"
    echo -e "${COLOR_SUCCESS}Native library successfully compiled and placed in jniLibs!${COLOR_RESET}"
else
    echo -e "${COLOR_SUCCESS}[Step 2/5] Using existing libqwen_asr.so in jniLibs (Pass --compile to rebuild).${COLOR_RESET}"
fi

# 3. Push ASR model assets
echo -e "${COLOR_INFO}[Step 3/5] Deploying/Syncing Qwen ASR model assets on device...${COLOR_RESET}"
TARGET_MODEL_DIR="/sdcard/Android/data/com.example.cantonesevoicekeyboard/files"
echo -e "${COLOR_INFO}Checking if model already exists on device...${COLOR_RESET}"

# We check if the model folder already exists and is non-empty under the app sandbox
MODEL_EXISTS_ON_DEVICE=$("$ADB" shell "ls -d $TARGET_MODEL_DIR/qwen-asr-int8dec 2>/dev/null || true")

if [ -n "$MODEL_EXISTS_ON_DEVICE" ]; then
    echo -e "${COLOR_SUCCESS}Model directory already exists in application external storage! skipping sync.${COLOR_RESET}"
else
    echo -e "${COLOR_WARNING}Model directory not found in application external storage. Initializing sync...${COLOR_RESET}"
    "$ADB" shell "mkdir -p $TARGET_MODEL_DIR"
    
    # Optimization: If the model is already in /sdcard/Download, we can copy it locally on the device (near-instant!)
    DOWNLOADS_MODEL_DIR="/sdcard/Download/qwen-asr-int8dec"
    DOWNLOADS_MODEL_EXISTS=$("$ADB" shell "ls -d $DOWNLOADS_MODEL_DIR 2>/dev/null || true")
    
    if [ -n "$DOWNLOADS_MODEL_EXISTS" ]; then
        echo -e "${COLOR_INFO}Found model folder in device Downloads. Performing fast local copy...${COLOR_RESET}"
        "$ADB" shell "cp -r $DOWNLOADS_MODEL_DIR $TARGET_MODEL_DIR/"
        echo -e "${COLOR_SUCCESS}Fast local copy completed successfully!${COLOR_RESET}"
    else
        # Fallback: Push from /Volumes/SSD
        LOCAL_MODEL_DIR="/Volumes/SSD/qwen-asr-int8dec"
        if [ -d "$LOCAL_MODEL_DIR" ]; then
            echo -e "${COLOR_INFO}Pushing model folder from local SSD ($LOCAL_MODEL_DIR) to device...${COLOR_RESET}"
            "$ADB" push "$LOCAL_MODEL_DIR" "$TARGET_MODEL_DIR/"
            echo -e "${COLOR_SUCCESS}Model successfully pushed via ADB!${COLOR_RESET}"
        else
            echo -e "${COLOR_ERROR}Error: Model files not found locally at '$LOCAL_MODEL_DIR' or on device at '$DOWNLOADS_MODEL_DIR'.${COLOR_RESET}"
            exit 1
        fi
    fi
fi

# Apply broad permissions so the app user process can read the model files
echo -e "${COLOR_INFO}Applying global read/write permissions to model directory on device...${COLOR_RESET}"
"$ADB" shell "chmod -R 777 $TARGET_MODEL_DIR/qwen-asr-int8dec"



# 4. Build and Install App APK
echo -e "${COLOR_INFO}[Step 4/5] Building and installing the debug APK...${COLOR_RESET}"
./gradlew installDebug

# 5. Launch companion walkthrough app
echo -e "${COLOR_INFO}[Step 5/5] Launching companion configuration application...${COLOR_RESET}"
"$ADB" shell am start -n com.example.cantonesevoicekeyboard/.MainActivity

echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}         Cantonese Keyboard Successfully Deployed!             ${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
