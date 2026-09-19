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
| `playback/PlaybackService.kt` | `MediaLibraryService` owning the `ExoPlayer` + `MediaSession` pair - drives lock-screen/notification controls, the app's own UI, and Android Auto's browse+playback UI, all from one session. |
| `playback/Station.kt` | Station metadata (stream URL, now-playing endpoint). Currently just `Death.FM` - a list of one on purpose, so adding the other four death.fm network stations later is a data change, not a rework. |
| `nowplaying/NowPlayingRepository.kt` | Polls death.fm's now-playing JSON endpoint and exposes it as a `StateFlow`. |
| `nowplaying/NowPlayingMetadata.kt` | Parsed now-playing snapshot (track/artist/album/real length/start time/cover art). |
| `lastfm/LastFmClient.kt` | Signed REST calls against Last.fm's Audioscrobbler API (auth, now-playing, scrobble). |
| `lastfm/LastFmScrobbler.kt` | Feeds now-playing changes to Last.fm while actually playing; scrobble timing follows Last.fm's real "half the track or 4 minutes" rule. |
| `settings/SettingsStore.kt` | Last.fm API key/secret/session, in a private `SharedPreferences` file. |
| `ui/PlayerScreen.kt`, `ui/SettingsScreen.kt`, `ui/theme/Theme.kt` | Compose screens and the station-red dark theme matching death.fm's own branding. |
| `res/xml/automotive_app_desc.xml` | Declares this as an Android Auto media app (paired with the `MediaBrowserService` intent-filter in the manifest). |

## Status

This is a skeleton, not a finished app:

- Only the `dfm` station is wired up; the other four (`1980s.fm`, `adagio.fm`,
  `entranced.fm`, `streamingsoundtracks.com`) share the same API shape and
  are a small addition to `Stations.all` when wanted.
- No Discord Rich Presence - unlike the desktop app, there's no local RPC
  pipe to talk to on Android, so it's out of scope here entirely.
- Last.fm's "Connect..." flow (`MainActivity.connectLastFm`) opens the
  browser auth page but doesn't yet wait for a real "I've approved it"
  confirmation before calling `auth.getSession` - currently just a fixed
  delay as a placeholder.
- No real launcher icon yet - `ic_launcher_foreground.xml` is a placeholder
  red monogram.

## Backing APIs (reverse-engineered from the player page, not documented anywhere)

- Stream: `https://death.fm/live` (AAC+, no auth). Each of the other four
  stations serves the same path on its own domain.
- Now playing: `https://death.fm/soap/FM24sevenJSON.php?action=GetCurrentlyPlaying`
  returns JSON with `Track`, `Artist`, `Album`, `Length` (ms), `PlayStart`
  (UTC, `yyyy-MM-ddTHH:mm:ss`), `CoverLink`, `ListenerCount`. This is the
  same endpoint the player page's own `updateTrackData()` polls every 30s.

## Prerequisites

- **Android Studio** (Ladybird/Koala or newer) with an SDK matching
  `compileSdk 35` installed.
- Opening the project for the first time will generate the Gradle wrapper
  scripts and `local.properties` automatically - neither is committed here.

## Building & running

Open the folder in Android Studio and hit Run, or from the command line
once the wrapper exists:

```powershell
./gradlew assembleDebug
```

## Last.fm scrobbling

Same setup as the desktop app - register a free API application at
[last.fm/api/account/create](https://www.last.fm/api/account/create) (any
name, blank callback URL) for an API key and shared secret, then paste them
into the in-app Settings screen and tap Connect. Each person needs their own
key rather than one baked into the source, since this repo is public.
