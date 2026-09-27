# Death.FM Android

An Android app for the [Death.FM](https://death.fm) internet radio stream.

## About

Death.FM Android is a sibling to the [DeathFmTray](../DeathFmTray) Windows
tray app. Instead of embedding the web player, it talks to the same stream
and now-playing API the web page itself uses, natively - built on Media3 for
native playback with lock-screen/notification media controls, Android Auto
support, and Chromecast (casting to a custom [DeathFmCastReceiver](https://github.com/lokulin/DeathFmCastReceiver)
rather than the default shared receiver).

## Installation

There's no Play Store listing yet (coming soon), so for now:

1. Download the latest APK from the project's
   [GitHub Releases](https://github.com/lokulin/DeathFmAndroid/releases) page
   on your Android device.
2. If prompted, allow your browser to install unknown apps (Android will ask
   the first time you open a downloaded APK).
3. Open the downloaded APK and install it.
4. Launch the app. Optionally, open Settings and tap Connect to link your
   Last.fm account for scrobbling - no account registration needed, just
   Last.fm's normal browser-approval flow.

## Developing

Requires Android Studio and a JDK 21 (Gradle can auto-provision the JDK).
Quick start from the command line:

```powershell
./gradlew assembleDebug
```

See [DEVELOPING.md](DEVELOPING.md) for the full architecture breakdown,
file-by-file notes, backing API reference, and release process.

## Known bugs

- No watchdog yet for the "stream gets stuck buffering forever" issue the
  desktop app's `NowPlayingService.cs` works around - ExoPlayer's own
  retry/backoff handles ordinary transient errors, but not necessarily a
  soft stall where the connection stays open without data flowing.
- The Settings screen only covers Last.fm credentials - no equivalent yet of
  the desktop app's "start with system"/notification behavior toggles.
- No Discord Rich Presence - unlike the desktop app, there's no local RPC
  pipe to talk to on Android, so it's out of scope here entirely.

## Planned features

- Wiring up the other four death.fm network stations (`1980s.fm`,
  `adagio.fm`, `entranced.fm`, `streamingsoundtracks.com`) - they share the
  same API shape, so it's mostly a data addition to `Stations.all`.
- A Play Store listing.

## License

MIT - see [LICENSE](LICENSE).
