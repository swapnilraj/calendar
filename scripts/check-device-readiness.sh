#!/usr/bin/env bash
# Read-only transport readiness. Never queries accounts, events, permissions or device properties.
set -u

# Exit: 0 one authorized transport; 1 adb absent; 2 no device; 3 unauthorized/offline;
#       4 multiple authorized transports; 5 adb failed/timed out.
adb_bin=$(command -v adb || true)
if [[ -z "$adb_bin" && -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    adb_bin="$ANDROID_HOME/platform-tools/adb"
fi
if [[ -z "$adb_bin" && -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
    adb_bin="$ANDROID_SDK_ROOT/platform-tools/adb"
fi
if [[ -z "$adb_bin" ]]; then
    echo 'NOT_READY: adb is unavailable (exit 1). No device or account checks performed.'
    exit 1
fi
if command -v timeout >/dev/null 2>&1; then
    devices=$(timeout 15 "$adb_bin" devices 2>/dev/null) || {
        echo 'NOT_READY: adb failed or timed out (exit 5); raw output suppressed.'
        exit 5
    }
else
    devices=$("$adb_bin" devices 2>/dev/null) || {
        echo 'NOT_READY: adb failed (exit 5); raw output suppressed.'
        exit 5
    }
fi
# Retain only transport states, never print serials or raw command output.
authorized=$(printf '%s\n' "$devices" | awk '$2 == "device" {n++} END {print n+0}')
unavailable=$(printf '%s\n' "$devices" | awk 'NF >= 2 && $1 != "List" && $2 != "device" {n++} END {print n+0}')
if (( authorized > 1 )); then
    echo 'NOT_READY: multiple authorized transports (exit 4). Select one outside this script.'
    exit 4
fi
if (( unavailable > 0 )); then
    echo 'NOT_READY: unavailable/unauthorized transport present (exit 3). Resolve it before testing.'
    exit 3
fi
if (( authorized == 0 )); then
    echo 'NOT_READY: no connected device (exit 2). Device acceptance remains NOT_RUN.'
    exit 2
fi
echo 'READY_FOR_MANUAL_TESTING: one authorized adb transport (exit 0).'
echo 'This does not verify a physical phone, app install, accounts, calendar access, sync, writes or alerts.'
