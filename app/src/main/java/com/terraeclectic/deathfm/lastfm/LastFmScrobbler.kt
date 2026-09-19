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
    private var client: LastFmClient? = null
    private var job: Job? = null
    private var lastScrobbledKey: String? = null
    private var lastNowPlayingKey: String? = null
    private var isPlaying: Boolean = false

    fun start(scope: CoroutineScope) {
        refreshClient()
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

    /** Call after Settings changes the API key/secret/session, so a new client picks them up. */
    fun refreshClient() {
        val apiKey = settings.lastFmApiKey
        val apiSecret = settings.lastFmApiSecret
        client = if (!apiKey.isNullOrBlank() && !apiSecret.isNullOrBlank()) {
            LastFmClient(apiKey, apiSecret)
        } else {
            null
        }
    }

    private fun onMetadata(metadata: NowPlayingMetadata) {
        val sessionKey = settings.lastFmSessionKey
        val lastFm = client
        if (!isPlaying || sessionKey.isNullOrBlank() || lastFm == null) return
        if (metadata.trackKey == lastScrobbledKey) return

        try {
            if (metadata.trackKey != lastNowPlayingKey) {
                lastFm.updateNowPlaying(sessionKey, metadata.artist, metadata.track, metadata.album.ifBlank { null })
                lastNowPlayingKey = metadata.trackKey
            }

            val lengthSeconds = metadata.lengthMs / 1000
            if (lengthSeconds <= 30) return // Last.fm: never scrobble tracks <=30s.

            val scrobbleThresholdSeconds = min(lengthSeconds / 2, 4 * 60)
            val elapsedSeconds = (System.currentTimeMillis() / 1000) - (metadata.playStartUtc / 1000)
            if (elapsedSeconds >= scrobbleThresholdSeconds) {
                lastFm.scrobble(
                    sessionKey,
                    metadata.artist,
                    metadata.track,
                    metadata.album.ifBlank { null },
                    metadata.playStartUtc / 1000,
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
