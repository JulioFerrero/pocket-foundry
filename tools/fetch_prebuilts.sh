#!/usr/bin/env bash
set -e

# ///////////////
# Use "-PSKIP_CXX=true" to skip C++ build.
# ///////////////

# --- RELEASE VERSION (change manually when needed) ---
DEFAULT_VERSION="0.6.3"
VERSION="${1:-$DEFAULT_VERSION}"
REPO="JulioFerrero/pocket-foundry"

APK_NAME="pocket-foundry-${VERSION}.apk"
URL="https://github.com/${REPO}/releases/download/v${VERSION}/${APK_NAME}"

# Move to the project root regardless of where the script is run from
cd "$(dirname "$0")/.."

echo "=== Pocket Foundry: Prebuilt binaries setup ==="
echo "Target version: v${VERSION}"
echo "URL: $URL"
echo ""

# Check dependencies
command -v curl >/dev/null 2>&1 || { echo "❌ Error: curl is not installed."; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "❌ Error: unzip is not installed."; exit 1; }

# Prepare target directories
JNI_DIR="app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="app/src/main/assets"
TMP_DIR="build/tmp/prebuilts"

mkdir -p "$JNI_DIR"
mkdir -p "$ASSETS_DIR"
mkdir -p "$TMP_DIR"

TMP_APK="${TMP_DIR}/${APK_NAME}"

# 1. Download
echo "[1/3] Downloading the official APK..."
if ! curl -L --fail --progress-bar "$URL" -o "$TMP_APK"; then
    echo "❌ Download failed! Check if release v${VERSION} exists at:"
    echo "   https://github.com/${REPO}/releases/tag/v${VERSION}"
    rm -rf "$TMP_DIR"
    exit 1
fi

# 2. Extract native libraries
echo "[2/3] Extracting .so libraries to $JNI_DIR..."
unzip -j -q -o "$TMP_APK" "lib/arm64-v8a/*.so" -d "$JNI_DIR"

# 3. Extract runtime
echo "[3/3] Extracting runtime to $ASSETS_DIR..."
unzip -j -q -o "$TMP_APK" "assets/runtime.*" -d "$ASSETS_DIR"

# Clean up temporary files
rm -rf "$TMP_DIR"

echo ""
echo "✅ Done! Files successfully installed:"
ls -lh "$JNI_DIR"
ls -lh "$ASSETS_DIR"/runtime.*
echo ""
echo "You can now run: ./gradlew installDebug"