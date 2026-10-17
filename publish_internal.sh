#!/bin/bash
# Exit immediately if any command exits with a non-zero status
set -e

# Define color codes for premium terminal output aesthetics
COLOR_INFO="\033[1;36m"    # Cyan
COLOR_SUCCESS="\033[1;32m" # Green
COLOR_WARNING="\033[1;33m" # Yellow
COLOR_ERROR="\033[1;31m"   # Red
COLOR_RESET="\033[0m"      # Reset

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="com.madeinhk.cantonesevoicekeyboard"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle.kts"

echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"
echo -e "${COLOR_INFO}        Cantonese Voice Keyboard Google Play Auto-Publisher      ${COLOR_RESET}"
echo -e "${COLOR_INFO}================================================================${COLOR_RESET}"

# 1. Fetch remote tracks status via gplay
echo -e "${COLOR_INFO}[Step 1/4] Checking Google Play remote release status...${COLOR_RESET}"
if ! command -v gplay &> /dev/null; then
    echo -e "${COLOR_ERROR}Error: gplay CLI is not installed or not in PATH.${COLOR_RESET}"
    exit 1
fi

STATUS_JSON=$(gplay status --package "$PACKAGE" 2>/dev/null || echo "")

if [ -z "$STATUS_JSON" ]; then
    echo -e "${COLOR_WARNING}Warning: Could not fetch play status. Proceeding with current local version code.${COLOR_RESET}"
else
    # Parse max remote version code using python
    REMOTE_VERSION=$(python3 -c "
import json, sys
try:
    data = json.loads(sys.argv[1])
    version_codes = []
    for track in data.get('tracks', {}).get('tracks', []):
        releases = track.get('releases') or []
        for r in releases:
            version_codes.extend(r.get('version_codes') or [])
    print(max(version_codes) if version_codes else 0)
except Exception as e:
    print(0)
" "$STATUS_JSON")
    
    echo -e "${COLOR_SUCCESS}Highest remote version code found on Play Store: $REMOTE_VERSION${COLOR_RESET}"
    
    # 2. Get local version code from build.gradle.kts
    LOCAL_VERSION=$(grep -o 'versionCode = [0-9]\+' "$GRADLE_FILE" | grep -o '[0-9]\+')
    echo -e "${COLOR_SUCCESS}Current local version code: $LOCAL_VERSION${COLOR_RESET}"
    
    if [ "$LOCAL_VERSION" -le "$REMOTE_VERSION" ]; then
        NEW_VERSION=$((REMOTE_VERSION + 1))
        echo -e "${COLOR_WARNING}Local version code ($LOCAL_VERSION) is <= remote ($REMOTE_VERSION). Auto-incrementing to $NEW_VERSION...${COLOR_RESET}"
        
        # Update build.gradle.kts using python
        python3 -c "
with open('$GRADLE_FILE', 'r') as f:
    content = f.read()
import re
new_content = re.sub(r'versionCode = \d+', 'versionCode = $NEW_VERSION', content)
with open('$GRADLE_FILE', 'w') as f:
    f.write(new_content)
"
        echo -e "${COLOR_SUCCESS}Updated app/build.gradle.kts with versionCode = $NEW_VERSION${COLOR_RESET}"
    else
        echo -e "${COLOR_SUCCESS}Local version code ($LOCAL_VERSION) is higher than remote ($REMOTE_VERSION). No increment needed.${COLOR_RESET}"
    fi
fi

# 3. Build release AAB
echo -e "${COLOR_INFO}[Step 2/4] Building signed release App Bundle (.aab)...${COLOR_RESET}"
INTERNAL_TYPING_TRIAL="${INTERNAL_TYPING_TRIAL:-true}" ./deploy.sh --release

AAB_PATH="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
if [ ! -f "$AAB_PATH" ]; then
    echo -e "${COLOR_ERROR}Error: Expected bundle not found at $AAB_PATH${COLOR_RESET}"
    exit 1
fi

# 4. Upload to Google Play Store internal track
echo -e "${COLOR_INFO}[Step 3/4] Uploading AAB to Google Play Store internal track...${COLOR_RESET}"
gplay publish track \
    --package "$PACKAGE" \
    --track internal \
    --bundle "$AAB_PATH"

echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}    Successfully built and published version code $(grep -o 'versionCode = [0-9]\+' "$GRADLE_FILE" | grep -o '[0-9]\+') to internal track!${COLOR_RESET}"
echo -e "${COLOR_SUCCESS}================================================================${COLOR_RESET}"
