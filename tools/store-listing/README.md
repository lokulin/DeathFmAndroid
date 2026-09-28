# store-listing/

Scripts used to prepare a Play Store submission: signed release builds,
store screenshots, and the foreground-service permission demo video. Written
for DeathFmAndroid but package-parameterized so they're reusable for other
apps (SomaMetalTray, etc.) - only `capture-screenshots.sh` is fully app-
agnostic; the recording script takes the package/activity as arguments.

Requires: `adb` (on `PATH`, or at the default Android Studio SDK location),
`python3` with Pillow (`pip install pillow`), and (only to inspect/verify
recordings, not to run the scripts) `ffmpeg`/`ffprobe`.

## Files

- `adb-helpers.sh` - shared functions (device wake/unlock, screencap,
  rotation, playback control). Sourced by the other scripts, not run
  directly.
- `pad_to_store_ratio.py` - pads a screenshot to a Play Store-safe aspect
  ratio (see "Screenshot aspect ratio" below).
- `capture-screenshots.sh <output-dir>` - interactive: press Enter to
  capture+pad+save the current screen, repeat for each shot you want.
- `record-foreground-service-demo.sh <package> [activity] [output] [seconds]`
  - records the backgrounding demo video described below.

## 1. Signed release build

Covered in the main [DEVELOPING.md](../../DEVELOPING.md) - upload keystore
lives outside the repo, credentials go in `local.properties`
(`DEATHFM_KEYSTORE_PATH`/`_PASSWORD`, `DEATHFM_KEY_ALIAS`, `DEATHFM_KEY_PASSWORD`),
wired into `app/build.gradle.kts`'s `signingConfigs`. `./gradlew bundleRelease`
produces the `.aab` for Play Console; `./gradlew assembleRelease` produces an
installable `.apk` for testing on a device (adb can't install an `.aab`
directly - use `assembleRelease`, or `bundletool` if you need to test the
exact bundle).

Installing a release-signed build over a debug-signed one already on the
device fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` - `adb uninstall
<package>` first (this clears the app's local data/login state).

`compileSdk`/`targetSdk` need bumping whenever Play Console starts flagging
the current one ("must target at least API level N") - bump both together,
and bump `versionCode`/`versionName` too since Play won't accept a re-upload
of a version code it already rejected/flagged.

## 2. Screenshots

Play Console (as of writing) requires 2-8 phone screenshots: PNG/JPEG, up to
8 MB each, each side between 320-3840 px, "16:9 or 9:16" aspect ratio.

### Screenshot aspect ratio

The real enforced rule is closer to "max side no more than 2x the min side"
(2:1) rather than exactly 16:9/9:16. Modern phones are commonly 20:9 or
19.5:9 (e.g. Pixel 7 is 1080x2400 = 2.22:1), which **fails** that check -
screenshots taken straight off the device need correcting before upload.

**Pad, don't crop.** A naive center-crop to 9:16 cuts off real UI - on a
typical player screen the bottom third (playback controls) or the very top
(status bar) ends up outside the crop, and on a list screen it can crop into
a header. `pad_to_store_ratio.py` instead adds letterbox/pillarbox bars
using the screenshot's own background color, so no content is ever lost;
`capture-screenshots.sh` calls it automatically.

### Capturing

```
./tools/store-listing/capture-screenshots.sh /path/to/output-dir
```

Wakes/unlocks the device, then waits for Enter between shots so you can
navigate the app by hand (or drive it with `adb shell input tap/swipe` /
`keyevent KEYCODE_MEDIA_PLAY` etc. in another terminal - more reliable than
tapping on-screen coordinates, which shift between layouts and orientations).

For a landscape shot (e.g. a tablet side-by-side layout), force the device
into landscape first rather than relying on the accelerometer mid-script:

```bash
source tools/store-listing/adb-helpers.sh
rotate_landscape   # ... capture ... then:
restore_rotation
```

## 3. Foreground-service permission demo video

Declaring a restricted foreground-service permission (e.g.
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) in Play Console's permissions
declaration form asks for a short video demonstrating the actual use case.

```
./tools/store-listing/record-foreground-service-demo.sh com.terraeclectic.deathfm
```

This shows: app playing in the foreground -> backgrounded -> notification
shade expanded (persistent media notification with working controls, proving
the foreground service is still alive) -> shade collapsed -> app brought
back to the foreground, still playing (position advanced naturally, not
reset).

**Before running:**
- Get the app into a playing state first - the script pauses to let you
  confirm via `dumpsys media_session` rather than assuming.
- Manually open and clear the notification shade of anything unrelated (Play
  Protect warnings, other apps' notifications) so the recorded shade only
  shows this app's media notification - the script doesn't do this for you
  since what's "unrelated" is a judgment call.

**Why it backgrounds via Settings, not Home:** pressing Home shows the real
launcher - actual wallpaper/photos, widgets, other apps' icons. None of that
belongs in a video handed to Google for review. Opening system Settings
proves the app isn't in the foreground just as well, with nothing personal
on screen.

**No audio is captured** - `adb shell screenrecord` is video-only, there's
no flag for it. Getting audio would need `scrcpy` (records via Android's
`AudioPlaybackCapture` API, Android 11+) or the device's own built-in Quick
Settings screen recorder (has a "record device audio" toggle, but is driven
through its UI rather than adb, so harder to script). Google's own
requirement is about proving the *visual* behavior (notification/lock-screen
controls persisting, position advancing) - audio isn't necessary for that.

**Play Console wants a link, not a file.** The permissions declaration form
takes a video URL (typically an unlisted YouTube upload), not a direct file
upload - upload the resulting `.mp4` to YouTube as unlisted first.

### Git Bash / MSYS path gotcha

Any `adb shell <cmd> /sdcard/...` invoked from Git Bash silently gets its
`/sdcard/...` argument rewritten into a Windows path (e.g. `C:/Program
Files/Git/sdcard/...`) before adb ever sees it, breaking the command with a
confusing, path-unrelated-looking error. `adb-helpers.sh` sets
`MSYS_NO_PATHCONV=1` to prevent this - if you're invoking adb with a device
path directly rather than through these scripts, set that yourself first.
