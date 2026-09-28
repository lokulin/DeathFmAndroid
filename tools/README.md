# tools/

## store-listing/

Scripts for preparing a Play Store submission: signed release builds, store
screenshots (padded to a Play-safe aspect ratio without cropping), and the
foreground-service permission demo video Play Console's permissions
declaration form asks for. See [store-listing/README.md](store-listing/README.md).

## expand-logcat-buffer.sh

Bumps the Pixel 7's logcat ring buffers from Android's default 256 KiB
(`main`, `system`, `crash`, `kernel`) up to 16 MiB each.

### Why

The default 256 KiB `system` buffer - which holds `ActivityManager`,
`DisplayManager`, `MediaSessionStack`, etc. - fills and wraps in about
**10 minutes of active Android Auto use**, because AA's virtual-display
setup/teardown logging is very chatty. In practice this meant a ~10 minute
drive at the end of the day silently evicted every log line from an earlier
drive that morning, making it impossible to `adb logcat -d` after the fact
and see what happened during an earlier Android Auto session (e.g. the
buffering/stream-restart/crash-on-network-switch bug).

16 MiB per buffer (64 MiB total) comfortably covers 10+ hours of continuous
active-driving-level logging, which is enough to span a full day of normal
use. It's trivial next to phone storage/RAM.

### Usage

```
./tools/expand-logcat-buffer.sh [size] [device-serial]
```

- `size` - defaults to `16M`. Accepts adb's usual suffixes (e.g. `4M`, `32M`).
- `device-serial` - only needed if more than one adb device is attached
  (the script will list them and ask you to pick if so).

Requires `adb` on `PATH`, or the Android SDK installed at the default
Android Studio location.

### Important limitation: does not survive reboot

This device (Pixel 7) is not rooted, and setting the buffer size
permanently requires the `persist.logd.size` system property, which needs
root (`adb shell setprop persist.logd.size ...` fails with "Failed to set
property" otherwise).

Instead, this script uses `adb logcat -b <buffer> -G <size>`, which resizes
the buffers live via logd without root - but that resize is **not
persistent**. It silently resets to the 256 KiB default on the phone's next
reboot (OS update, battery drain, manual restart, etc.), with no warning.

**After any phone reboot, re-run this script** to restore the larger
buffers. There's no way around this short of rooting the device.

### Checking current buffer sizes

```
adb logcat -g
```

Shows each buffer's configured size and how much is currently consumed.
