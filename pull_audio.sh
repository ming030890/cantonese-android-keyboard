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
echo -e "${COLOR_INFO}             Cantonese Voice Keyboard Audio Extractor           ${COLOR_RESET}"
echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"

# 1. Locate ADB
ADB="/Users/tony/Library/Android/sdk/platform-tools/adb"
if [ ! -f "$ADB" ]; then
    if command -v adb &> /dev/null; then
        ADB="adb"
    else
        echo -e "${COLOR_ERROR}Error: ADB not found. Make sure Android SDK is installed.${COLOR_RESET}"
        exit 1
    fi
fi

# 2. Verify device connection
DEVICE_LIST=$("$ADB" devices | grep -v "List of devices" | grep "device" || true)
if [ -z "$DEVICE_LIST" ]; then
    echo -e "${COLOR_ERROR}Error: No Android device or emulator is connected via ADB.${COLOR_RESET}"
    exit 1
fi
DEVICE_ID=$(echo "$DEVICE_LIST" | awk '{print $1}' | head -n 1)
echo -e "${COLOR_SUCCESS}Target Device detected: $DEVICE_ID${COLOR_RESET}"

# 3. Define paths
REMOTE_AUDIO_DIR="/sdcard/Android/data/com.example.cantonesevoicekeyboard/files/saved_audio"
LOCAL_OUT_DIR="pulled_audio"

# 4. Check remote directory and list wav files
echo -e "${COLOR_INFO}Scanning device for post-VAD audio segments...${COLOR_RESET}"
REMOTE_FILES=$("$ADB" shell "ls $REMOTE_AUDIO_DIR/*.wav 2>/dev/null" || true)

if [ -z "$REMOTE_FILES" ]; then
    echo -e "${COLOR_WARNING}No saved audio files found on device at: $REMOTE_AUDIO_DIR${COLOR_RESET}"
    exit 0
fi

# Count files
FILE_COUNT=$(echo "$REMOTE_FILES" | wc -l | tr -d ' ')
echo -e "${COLOR_SUCCESS}Found $FILE_COUNT post-VAD audio segment(s) on device.${COLOR_RESET}"

# Create local output directory
mkdir -p "$LOCAL_OUT_DIR"

# 5. Pull files
echo -e "${COLOR_INFO}Extracting files to local directory './$LOCAL_OUT_DIR/'...${COLOR_RESET}"
"$ADB" pull "$REMOTE_AUDIO_DIR/." "$LOCAL_OUT_DIR/"

echo -e "${COLOR_SUCCESS}Successfully extracted all files!${COLOR_RESET}"
ls -lh "$LOCAL_OUT_DIR"

# 6. Check for clear argument
CLEAR_REMOTE=false
for arg in "$@"; do
    if [ "$arg" == "--clear" ] || [ "$arg" == "-c" ]; then
        CLEAR_REMOTE=true
    fi
done

if [ "$CLEAR_REMOTE" = "true" ]; then
    echo -e "${COLOR_WARNING}Clearing pulled audio files from device directory...${COLOR_RESET}"
    "$ADB" shell "rm $REMOTE_AUDIO_DIR/*.wav"
    echo -e "${COLOR_SUCCESS}Device directory successfully cleared!${COLOR_RESET}"
fi

echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"
