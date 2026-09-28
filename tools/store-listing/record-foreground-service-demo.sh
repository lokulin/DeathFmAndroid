#!/bin/bash
# Records a short screen capture demonstrating a media-playback foreground
# service surviving backgrounding, for Play Console's permission declaration
# form (e.g. FOREGROUND_SERVICE_MEDIA_PLAYBACK) - it asks for a video showing
# the permission's actual use.
#
# Sequence: app plays in the foreground -> app is backgrounded via a neutral
# system screen (Settings, NOT the home launcher - see below) -> notification
# shade is expanded to show the persistent media notification with working
# transport controls -> shade collapses -> app is brought back to the
# foreground to show playback never stopped.
#
# Deliberately backgrounds via Settings rather than pressing Home: the home
# screen shows the device owner's actual wallpaper/photos and widgets (e.g. a
# personal photo, a messaging streak widget), which has no business being in
# a video handed to Google for review. Settings is functionally identical for
# proving the app is not in the foreground, without that exposure.
#
# Before running: clear/dismiss any unrelated notifications (Play Protect
# warnings, other apps' alerts, etc.) so the notification-shade shot only
# shows this app's own media notification - open the shade once by hand and
# dismiss anything irrelevant if needed.
#
# Also make sure the app is actually playing before starting (media_play
# below only toggles - if it's already playing this will pause it instead).
# Check with print_playback_state.
#
# Usage:
#   ./record-foreground-service-demo.sh <package> [main-activity] [output-file] [duration-seconds]
#
# Example:
#   ./record-foreground-service-demo.sh com.terraeclectic.deathfm .MainActivity fg_service_demo.mp4 30
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/adb-helpers.sh"

PACKAGE="${1:?Usage: record-foreground-service-demo.sh <package> [main-activity] [output-file] [duration-seconds]}"
ACTIVITY="${2:-.MainActivity}"
OUT_FILE="${3:-fg_service_demo.mp4}"
DURATION="${4:-30}"

require_single_device

echo "Make sure $PACKAGE is open and actively playing before continuing."
print_playback_state
read -r -p "Press Enter once confirmed playing (Ctrl+C to abort)... " _

DEVICE_PATH="/sdcard/fg_service_demo_capture.mp4"

"$ADB" shell screenrecord --bit-rate 6000000 --time-limit "$DURATION" "$DEVICE_PATH" &
RECORD_PID=$!

sleep 3   # a few seconds on the player screen, actively playing

"$ADB" shell am start -a android.settings.SETTINGS
sleep 3   # app is backgrounded but should keep playing

"$ADB" shell cmd statusbar expand-notifications
sleep 4   # persistent media notification with transport controls, backgrounded

"$ADB" shell cmd statusbar collapse
sleep 1

"$ADB" shell am start -n "$PACKAGE/$ACTIVITY"
sleep 6   # back in the app - playback should show it never stopped

wait $RECORD_PID
echo "Recording done, pulling..."
"$ADB" pull "$DEVICE_PATH" "$OUT_FILE"
"$ADB" shell rm "$DEVICE_PATH"
echo "Saved $OUT_FILE"
