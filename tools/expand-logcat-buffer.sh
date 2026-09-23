#!/usr/bin/env bash
# Expands the connected Android device's logcat ring buffers so more history
# survives before it's overwritten. See tools/README.md for why this exists.
#
# Usage:
#   ./expand-logcat-buffer.sh [size] [device-serial]
#
#   size           Buffer size per log (default: 16M). Accepts adb's usual
#                  suffixes, e.g. 4M, 16M, 32M.
#   device-serial  adb device serial to target. If omitted and exactly one
#                  device is attached, that device is used automatically.
#                  If multiple devices are attached, you must pass one.
#
# NOT persistent across reboots (no root on this device) - see README.

set -euo pipefail

SIZE="${1:-16M}"
SERIAL="${2:-}"

find_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  local candidates=(
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"
  )
  for c in "${candidates[@]}"; do
    if [ -x "$c" ]; then
      echo "$c"
      return
    fi
  done
  echo "ERROR: could not find adb. Set ANDROID_HOME or put adb on PATH." >&2
  exit 1
}

ADB="$(find_adb)"

if [ -z "$SERIAL" ]; then
  mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "ERROR: no adb devices attached." >&2
    exit 1
  elif [ "${#DEVICES[@]}" -eq 1 ]; then
    SERIAL="${DEVICES[0]}"
  else
    echo "Multiple devices attached - pass a serial as the 2nd argument:" >&2
    for d in "${DEVICES[@]}"; do
      MODEL="$("$ADB" -s "$d" shell getprop ro.product.model | tr -d '\r')"
      echo "  $d ($MODEL)" >&2
    done
    exit 1
  fi
fi

MODEL="$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
echo "Target device: $SERIAL ($MODEL)"
echo "Resizing logcat ring buffers to $SIZE ..."

for BUF in main system crash kernel; do
  "$ADB" -s "$SERIAL" logcat -b "$BUF" -G "$SIZE"
done

echo
echo "Done. Current buffer sizes:"
"$ADB" -s "$SERIAL" logcat -g
