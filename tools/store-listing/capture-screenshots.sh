#!/bin/bash
# Interactive helper for capturing Play Store phone screenshots from a
# connected device: wakes/unlocks it, then on each Enter press grabs a
# screenshot, pads it to a store-safe aspect ratio (see pad_to_store_ratio.py),
# and saves it into the output directory - so you only have to navigate the
# app by hand between captures, not fight adb or image dimensions.
#
# Usage:
#   ./capture-screenshots.sh <output-dir>
#
# Workflow:
#   1. Run this script.
#   2. Navigate the device to the screen you want (manually, or via
#      `adb shell input tap/swipe/keyevent` in another terminal).
#   3. Press Enter in this terminal to capture it - you'll be prompted for a
#      short name (e.g. "now-playing") and an orientation.
#   4. Repeat for each screen (2-8 screenshots for a Play Store listing).
#   5. Ctrl+C or enter a blank name to stop.
#
# Portrait is the common case; only say landscape for a genuinely rotated
# screen (see rotate_landscape/restore_rotation in adb-helpers.sh if you need
# to force the device into landscape first - the device's own accelerometer
# rotation is unreliable to depend on mid-script).
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/adb-helpers.sh"

OUT_DIR="${1:?Usage: capture-screenshots.sh <output-dir>}"
mkdir -p "$OUT_DIR"

require_single_device
wake_and_unlock

echo "Ready. Navigate the device, then press Enter here to capture (blank name to stop)."

n=1
while true; do
    read -r -p "Screenshot name (blank to stop): " name
    [ -z "$name" ] && break
    read -r -p "Orientation [portrait/landscape] (default portrait): " orientation
    orientation="${orientation:-portrait}"

    raw="$OUT_DIR/${n}-${name}-raw.png"
    final="$OUT_DIR/${n}-${name}.png"
    screencap_to "$raw"
    python "$SCRIPT_DIR/pad_to_store_ratio.py" "$raw" "$final" --orientation "$orientation"
    rm "$raw"
    echo "Saved $final"
    n=$((n + 1))
done

echo "Done. $((n - 1)) screenshot(s) in $OUT_DIR"
