#!/bin/bash
set -e

find_adb() {
    # 1) If adb is already on PATH, use it.
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi

    # 2) Common Windows SDK locations.
    local candidates=(
        "$ANDROID_HOME/platform-tools/adb.exe"
        "$ANDROID_SDK_ROOT/platform-tools/adb.exe"
        "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
        "$USERPROFILE/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        "/c/Users/$USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    )

    local p
    for p in "${candidates[@]}"; do
        if [ -n "$p" ] && [ -x "$p" ]; then
            echo "$p"
            return 0
        fi
    done

    return 1
}

ADB_BIN="$(find_adb)" || {
    echo "ERROR: adb not found."
    echo "Install Android platform-tools or add adb to PATH."
    exit 1
}

cd app/build/outputs/apk/release
"$ADB_BIN" install app-release.apk