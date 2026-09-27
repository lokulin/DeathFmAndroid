package com.terraeclectic.deathfm.lastfm

import android.util.Log
import com.terraeclectic.deathfm.nowplaying.NowPlayingMetadata
import com.terraeclectic.deathfm.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Feeds now-playing changes to Last.fm while the stream is actually playing,
 * same split of responsibility as DeathFmTray's LastFmScrobbler.cs (fed by
 * play state, not just now-playing changes alone, so buffering blips don't
 * masquerade as new tracks and silent tracks never get scrobbled).
 *
 * Unlike the desktop app, [NowPlayingMetadata.lengthMs] is real here (from
 * the JSON API, not DOM-scraped), so the scrobble threshold can follow
 * Last.fm's actual published rule - half the track length or 4 minutes,
 * whichever is shorter, only for tracks over 30 seconds - instead of the
 * desktop app's fixed 30-second wall-clock proxy.
 */
class LastFmScrobbler(
    private val settings: SettingsStore,
    private val nowPlaying: StateFlow<NowPlayingMetadata>,
) {
    private var job: Job? = null
    private var lastScrobbledKey: String? = null
    private var lastNowPlayingKey: String? = null
    private var isPlaying: Boolean = false

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            nowPlaying.collect { metadata -> onMetadata(metadata) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (!playing) lastNowPlayingKey = null
    }

    private fun onMetadata(metadata: NowPlayingMetadata) {
        val sessionKey = settings.lastFmSessionKey
        if (!isPlaying || sessionKey.isNullOrBlank()) return
        if (metadata.trackKey == lastScrobbledKey) return

        // Built fresh each time rather than cached at start() - cheap (no
        // network call), and keeps this in step with onMetadata's other
        // per-call reads rather than assuming anything about client lifetime.
        val lastFm = LastFmClient(LastFmCredentials.API_KEY, LastFmCredentials.API_SECRET)

        try {
            if (metadata.trackKey != lastNowPlayingKey) {
                lastFm.updateNowPlaying(sessionKey, metadata.artist, metadata.track, metadata.album.ifBlank { null })
                lastNowPlayingKey = metadata.trackKey
            }

            val lengthSeconds = metadata.lengthMs / 1000
            if (lengthSeconds <= 30) return // Last.fm: never scrobble tracks <=30s.

            val scrobbleThresholdSeconds = min(lengthSeconds / 2, 4 * 60)
            // Not metadata.fetchedAtDeviceMs's raw value - the station's own
            // clock isn't a trustworthy absolute timestamp (see
            // NowPlayingMetadata's doc), so elapsed is anchored to this
            // device's own clock via elapsedAtFetchMs instead, same as the UI.
            val nowMs = System.currentTimeMillis()
            val elapsedSeconds = (metadata.elapsedAtFetchMs + (nowMs - metadata.fetchedAtDeviceMs)) / 1000
            if (elapsedSeconds >= scrobbleThresholdSeconds) {
                val startedAtSeconds = (nowMs / 1000) - elapsedSeconds
                lastFm.scrobble(
                    sessionKey,
                    metadata.artist,
                    metadata.track,
                    metadata.album.ifBlank { null },
                    startedAtSeconds,
                )
                lastScrobbledKey = metadata.trackKey
            }
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm update failed", e)
        }
    }

    companion object {
        private const val TAG = "LastFmScrobbler"
    }
}
