# Death.FM Android

An Android app for the [Death.FM](https://death.fm) internet radio stream,
sibling to the [DeathFmTray](../DeathFmTray) Windows tray app. Instead of
embedding the web player, this talks to the same stream and now-playing API
the web page itself uses, natively - which gets lock-screen/notification
media controls and Android Auto support essentially for free via Media3.

## What's here

| File | Purpose |
|---|---|
| `MainActivity.kt` | Compose UI host; connects a `MediaController` to `PlaybackService` and flips between the player and settings screens. |
| `playback/PlaybackService.kt` | `MediaLibraryService` owning the `ExoPlayer` + `MediaSession` pair - drives lock-screen/notification controls, the app's own UI, and Android Auto's browse+playback UI, all from one session. Handles audio focus and "becoming noisy" (Bluetooth/headphone disconnect) pauses, and implements playback resumption so Android Auto launches straight into the player instead of a one-item browse list. |
| `playback/Station.kt` | Station metadata (stream URL, now-playing endpoint). Currently just `Death.FM` - a list of one on purpose, so adding the other four death.fm network stations later is a data change, not a rework. |
| `nowplaying/NowPlayingRepository.kt` | Polls death.fm's now-playing JSON endpoint and exposes it as a `StateFlow`. Timed to poll again right after the current track is expected to end (using the real `Length`/elapsed data), rather than a flat 30s cadence, which falls back to as the ceiling. |
| `nowplaying/NowPlayingMetadata.kt` | Parsed now-playing snapshot (track/artist/album/real length/start time/cover art). |
| `lastfm/LastFmClient.kt` | Signed REST calls against Last.fm's Audioscrobbler API (auth, now-playing, scrobble). |
| `lastfm/LastFmScrobbler.kt` | Feeds now-playing changes to Last.fm while actually playing; scrobble timing follows Last.fm's real "half the track or 4 minutes" rule. |
| `settings/SettingsStore.kt` | Last.fm API key/secret/session, in a private `SharedPreferences` file. |
| `ui/PlayerScreen.kt` | The player screen, laid out differently by orientation: portrait stacks full-width square artwork (with an iTunes Cover Flow-style reflection bleeding into the metadata area behind the text) above title/album/artist/progress/controls; landscape splits side by side (artwork at 80% height with its own reflection strip on the left, details/controls on the right) - rotation isn't locked, so a tablet gets a real landscape layout rather than a stretched portrait one. Both show a live progress bar + elapsed/total readout and a small Settings icon in the top-right corner. |
| `ui/SettingsScreen.kt`, `ui/LastFmConnectionState.kt`, `ui/theme/Theme.kt` | The Last.fm settings screen (rendering each step of the real auth flow - Disconnected/AwaitingApproval/Connected/Failed), and the station-red dark theme matching death.fm's own branding. |
| `res/drawable/ic_launcher_foreground.png`, `res/drawable/album_art_placeholder.png` | The real app icon/artwork-placeholder art (a headphone-wearing skull), used both as the launcher icon (inset for adaptive-icon safe zones) and as the artwork shown before any track art has loaded. |
| `res/xml/automotive_app_desc.xml` | Declares this as an Android Auto media app (paired with the `MediaBrowserService` intent-filter in the manifest). |

## Status

Tagged `v0.1.0` (2026-09-19). Core playback, now-playing metadata, the
player UI, Last.fm scrobbling, and Android Auto have all been tested end to
end on real hardware (a Pixel 7, a Lenovo tablet for the landscape layout,
and an actual car head unit for Android Auto) - not just reasoned about.
Still a skeleton in scope, though - not a finished app:

- Only the `dfm` station is wired up; the other four (`1980s.fm`, `adagio.fm`,
  `entranced.fm`, `streamingsoundtracks.com`) share the same API shape and
  are a small addition to `Stations.all` when wanted.
- Android Auto has been verified against a real head unit: it launches
  straight into the player (via playback resumption, skipping the one-item
  browse list), and audio focus / "becoming noisy" handling means switching
  to/from other media apps and disconnecting Bluetooth behave correctly.
- No Discord Rich Presence - unlike the desktop app, there's no local RPC
  pipe to talk to on Android, so it's out of scope here entirely.
- Last.fm's Connect flow is fully wired (real two-step auth: opens the
  browser, then waits for the user to explicitly confirm they approved it
  before calling `auth.getSession` - see `LastFmConnectionState`) and has
  been tested against a real Last.fm account.
- No watchdog yet for the "stream gets stuck buffering forever" issue the
  desktop app's `NowPlayingService.cs` works around - ExoPlayer's own
  retry/backoff handles ordinary transient errors, but not necessarily a
  soft stall where the connection stays open without data flowing. Not
  confirmed to actually happen on Android yet; worth watching for.
- The Settings screen only covers Last.fm credentials - no equivalent yet of
  the desktop app's "start with system"/notification behavior toggles (less
  relevant on Android, but worth revisiting).

## Backing APIs (reverse-engineered from the player page, not documented anywhere)

- Stream: `https://death.fm/live` (AAC+, no auth). Each of the other four
  stations serves the same path on its own domain.
- Now playing: `https://death.fm/soap/FM24sevenJSON.php?action=GetCurrentlyPlaying`
  returns JSON with `Track`, `Artist`, `Album`, `Length` (ms), `PlayStart`,
  `SystemTime`, `CoverLink`, `ListenerCount`. This is the same endpoint the
  player page's own `updateTrackData()` polls every 30s.
  - `Track`/`Artist`/`Album` are HTML-entity-encoded (e.g. `All&#039;inizio`)
    - the player page itself calls a `decodeEntities()` helper before
      display; `NowPlayingRepository` does the equivalent via
      `Html.fromHtml(...)`.
  - **`PlayStart`/`SystemTime` are NOT reliable absolute UTC timestamps**,
    despite looking like naive ISO datetimes and despite the station
    presenting them as such - checked live against a real UTC clock, the
    station's own clock was a flat 4 hours off (it looks like it's actually
    running on US Eastern time and mislabeling its own timestamps). Only
    the *delta* between the two fields is trustworthy. `NowPlayingRepository`
    computes `elapsedAtFetchMs = SystemTime - PlayStart` and anchors it to
    this device's own correct clock (`fetchedAtDeviceMs`) rather than ever
    treating the station's clock as absolute - see the doc comments on
    `NowPlayingMetadata` for the full reasoning. Worth re-checking
    periodically in case death.fm ever fixes their server clock.

## Prerequisites

- **Android Studio** (Ladybird/Koala or newer) with an SDK matching
  `compileSdk 35` installed.
- The Gradle wrapper (`gradlew`/`gradlew.bat`/`gradle-wrapper.jar`) is
  committed, so `./gradlew` works straight away without opening Android
  Studio first. `local.properties` (the local SDK path) is the one thing
  Studio still generates itself on first open, and isn't committed.
- A JDK 21 install - the project's `gradle-daemon-jvm.properties` has
  Gradle auto-provision one via the `foojay-resolver-convention` plugin
  already wired up in `settings.gradle.kts`, so this shouldn't need any
  manual setup either way.

## Building & running

Open the folder in Android Studio and hit Run, or from the command line:

```powershell
./gradlew assembleDebug
```

## Releasing

Pushing an annotated tag matching `v*.*.*` triggers
`.github/workflows/build-release.yml`, which builds a debug-signed APK
(there's no release keystore set up yet, since this isn't published
anywhere) and attaches it to a GitHub Release:

```powershell
git tag -a v0.2.0 -m "v0.2.0 - ..."
git push origin v0.2.0
```

`.github/workflows/build-check.yml` also builds on every push/PR to
`master`, as a lighter "does this still compile" gate independent of
tagging. Both workflows cache Gradle's dependency/build cache via
`gradle/actions/setup-gradle`.

## Last.fm scrobbling

Same setup as the desktop app - register a free API application at
[last.fm/api/account/create](https://www.last.fm/api/account/create) (any
name, blank callback URL) for an API key and shared secret, then paste them
into the in-app Settings screen and tap Connect. Each person needs their own
key rather than one baked into the source, since this repo is public.
