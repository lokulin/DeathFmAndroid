package com.terraeclectic.deathfm.nowplaying

import android.util.Log
import com.terraeclectic.deathfm.playback.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    // DateTimeFormatter (unlike SimpleDateFormat) is immutable and
    // thread-safe - pollOnce() runs on Dispatchers.IO, a multi-threaded
    // pool, so a shared SimpleDateFormat here would risk its internal
    // Calendar state (timezone included) getting corrupted by concurrent use.
    private val playStartFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

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

    // PlaybackService's scope runs on Dispatchers.Main; OkHttp's synchronous
    // execute() is a blocking call, which Android hard-fails on the main
    // thread with NetworkOnMainThreadException - withContext(IO) is required
    // here, not optional.
    private suspend fun pollOnce() {
        try {
            withContext(Dispatchers.IO) {
                val url = "${station.nowPlayingUrl}&_t=${System.currentTimeMillis()}"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext
                    val body = response.body?.string() ?: return@withContext
                    _nowPlaying.value = parse(body)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Now-playing poll failed", e)
        }
    }

    private fun parse(json: String): NowPlayingMetadata {
        val obj = JSONObject(json)
        val playStartUtc = try {
            LocalDateTime.parse(obj.optString("PlayStart"), playStartFormat)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
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
