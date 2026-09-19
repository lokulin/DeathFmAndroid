package com.terraeclectic.deathfm.nowplaying

import android.util.Log
import com.terraeclectic.deathfm.playback.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Polls a station's now-playing JSON endpoint (the same one the death.fm
 * player page itself calls every 30s) and exposes the result as a
 * [StateFlow]. [PlaybackService][com.terraeclectic.deathfm.playback.PlaybackService]
 * feeds this into the MediaSession's metadata; the Compose UI and the
 * Last.fm scrobbler both just observe [nowPlaying].
 */
class NowPlayingRepository(
    private val station: Station,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val _nowPlaying = MutableStateFlow(NowPlayingMetadata.EMPTY)
    val nowPlaying: StateFlow<NowPlayingMetadata> = _nowPlaying.asStateFlow()

    private var pollJob: Job? = null

    // "2026-09-19T02:12:32" - naive local-looking timestamp the API actually
    // returns in UTC (confirmed against the API's own SystemTime field).
    private val playStartFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun start(scope: CoroutineScope) {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        try {
            val url = "${station.nowPlayingUrl}&_t=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                _nowPlaying.value = parse(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Now-playing poll failed", e)
        }
    }

    private fun parse(json: String): NowPlayingMetadata {
        val obj = JSONObject(json)
        val playStartUtc = try {
            playStartFormat.parse(obj.optString("PlayStart"))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
        return NowPlayingMetadata(
            track = obj.optString("Track", "Death.FM").ifBlank { "Death.FM" },
            artist = obj.optString("Artist", "Death.FM").ifBlank { "Death.FM" },
            album = obj.optString("Album", ""),
            lengthMs = obj.optString("Length", "0").toLongOrNull() ?: 0L,
            playStartUtc = playStartUtc,
            coverUrl = obj.optString("CoverLink").ifBlank { null },
            listenerCount = obj.optString("ListenerCount", "0").toIntOrNull() ?: 0,
        )
    }

    companion object {
        private const val TAG = "NowPlayingRepository"
        private const val POLL_INTERVAL_MS = 15_000L
    }
}
