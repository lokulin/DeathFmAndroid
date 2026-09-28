#!/bin/bash
# Shared adb helpers for the store-listing scripts in this directory.
# Source this file rather than running it directly:
#   source "$(dirname "$0")/adb-helpers.sh"

# Git Bash/MSYS rewrites args that look like absolute Unix paths (e.g.
# /sdcard/foo.mp4) into Windows paths (e.g. C:/Program Files/Git/sdcard/foo.mp4)
# before they ever reach adb.exe. That silently breaks any adb shell command
# taking a device-side path (screenrecord, pull's source, rm, etc.) - it fails
# with a confusing error ("Must specify output file") that looks unrelated to
# path handling. This must be set before such calls; harmless on other shells.
export MSYS_NO_PATHCONV=1

find_adb() {
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    local default_win="/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    if [ -x "$default_win" ]; then
        echo "$default_win"
        return
    fi
    echo "adb not found on PATH and not at the default Android Studio SDK location ($default_win)." >&2
    echo "Pass its path explicitly or add it to PATH." >&2
    exit 1
}

ADB="$(find_adb)"

require_single_device() {
    local n
    n=$("$ADB" devices | tail -n +2 | grep -c device$)
    if [ "$n" -eq 0 ]; then
        echo "No adb device attached. Connect one (USB or wireless debugging) and retry." >&2
        exit 1
    fi
    if [ "$n" -gt 1 ]; then
        echo "More than one adb device attached - set ANDROID_SERIAL or use 'adb -s <serial>'." >&2
        "$ADB" devices
        exit 1
    fi
}

# Wakes the screen and dismisses a swipe-only lockscreen (no PIN/pattern).
# Does NOT attempt to enter a PIN/pattern/biometric - if the device has one
# set, unlock it manually before running these scripts.
wake_and_unlock() {
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    sleep 1
    "$ADB" shell input swipe 540 2000 540 800 200
    sleep 1
}

# Usage: screencap_to <local-path>
screencap_to() {
    "$ADB" exec-out screencap -p > "$1"
}

# Forces the device into landscape (1) or back to portrait/auto (0).
# Needed because portrait screenshots/recordings are the default, but a
# landscape store screenshot needs the device actually rotated - relying on
# the accelerometer mid-script is unreliable.
rotate_landscape() {
    "$ADB" shell settings put system accelerometer_rotation 0
    "$ADB" shell settings put system user_rotation 1
    sleep 1
}

restore_rotation() {
    "$ADB" shell settings put system accelerometer_rotation 1
    "$ADB" shell settings put system user_rotation 0
}

# Resumes/pauses playback via the media session directly (KEYCODE_MEDIA_PLAY),
# rather than tapping a play/pause button on screen - screen coordinates
# shift between layouts/devices/orientations and are the least reliable way
# to drive playback. Confirm the app is actually playing afterwards with
# print_playback_state.
media_play() {
    "$ADB" shell input keyevent KEYCODE_MEDIA_PLAY
}

print_playback_state() {
    "$ADB" shell dumpsys media_session | grep "state=PlaybackState"
}
