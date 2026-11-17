#!/usr/bin/env bash
# Installs the Android SDK pieces this project needs (macOS or Linux) and writes local.properties.
# Safe to re-run. Usage: scripts/setup-android-sdk.sh
# Requires: JDK 21 on PATH (or JAVA_HOME), curl, unzip.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"
CMDLINE_TOOLS_VERSION="13114758" # commandlinetools "latest" build; bump if Google removes it

case "$(uname -s)" in
  Darwin) OS=mac; DEFAULT_SDK="$HOME/Library/Android/sdk" ;;
  Linux) OS=linux; DEFAULT_SDK="$HOME/Android/Sdk" ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$DEFAULT_SDK}}"
mkdir -p "$SDK"

java -version 2>&1 | grep -q 'version "21' || echo "warning: JDK 21 recommended (found: $(java -version 2>&1 | head -1))" >&2

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if ! [ -x "$SDKMANAGER" ]; then
  if command -v sdkmanager >/dev/null 2>&1; then
    SDKMANAGER="$(command -v sdkmanager)"
  else
    echo "Installing Android command-line tools into $SDK"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/commandlinetools-${OS}-${CMDLINE_TOOLS_VERSION}_latest.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

echo "sdk.dir=$SDK" > "$ROOT/local.properties"
echo "Android SDK ready at $SDK (local.properties written)"
echo "export ANDROID_HOME=\"$SDK\""
