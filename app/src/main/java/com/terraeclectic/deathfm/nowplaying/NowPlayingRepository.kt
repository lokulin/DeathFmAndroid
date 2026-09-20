package com.terraeclectic.deathfm.nowplaying

import android.text.Html
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

    // "2026-09-19T02:12:32" - naive-looking timestamp format used by both
    // "PlayStart" and "SystemTime". Despite the field name, this is NOT
    // reliably UTC - checked live against this device's real UTC clock, the
    // station's own clock was a flat 4 hours off (looks like it's actually
    // running on US Eastern time and mislabeling its timestamps). Only ever
    // used to compute the delta between the two fields (see parse()), never
    // as an absolute instant. DateTimeFormatter (unlike SimpleDateFormat) is
    // immutable and thread-safe - pollOnce() runs on Dispatchers.IO, a
    // multi-threaded pool, so a shared SimpleDateFormat here would risk its
    // internal Calendar state getting corrupted by concurrent use.
    private val stationTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun start(scope: CoroutineScope) {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                val metadata = pollOnce()
                delay(nextPollDelayMs(metadata))
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
    private suspend fun pollOnce(): NowPlayingMetadata? {
        return try {
            withContext(Dispatchers.IO) {
                val url = "${station.nowPlayingUrl}&_t=${System.currentTimeMillis()}"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val metadata = parse(body)
                    _nowPlaying.value = metadata
                    metadata
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Now-playing poll failed", e)
            null
        }
    }

    // Rather than always waiting the full POLL_INTERVAL_MS, times the next
    // poll to land just after the current track is expected to end (using
    // the real Length/elapsed data we already have) - otherwise the elapsed
    // readout could sit visibly past a track's length for up to 30s before
    // the next scheduled poll happens to notice the track actually changed.
    // Still capped at POLL_INTERVAL_MS as the normal/fallback cadence (a
    // long remaining time doesn't mean waiting that whole time - regular
    // polling continues in case something else changes), and floored at
    // MIN_POLL_DELAY_MS so a just-started or bogus-length track can't cause
    // a tight poll loop.
    private fun nextPollDelayMs(metadata: NowPlayingMetadata?): Long {
        if (metadata == null || metadata.lengthMs <= 0L) return POLL_INTERVAL_MS
        val remainingMs = metadata.lengthMs - metadata.elapsedAtFetchMs
        return (remainingMs + END_OF_TRACK_BUFFER_MS).coerceIn(MIN_POLL_DELAY_MS, POLL_INTERVAL_MS)
    }

    private fun parse(json: String): NowPlayingMetadata {
        val obj = JSONObject(json)

        // Parsed under the same (possibly-wrong) zone assumption for both
        // fields, so whatever the station's clock's real offset is, it
        // cancels out in this subtraction - see stationTimeFormat's doc.
        val playStart = parseStationTime(obj.optString("PlayStart"))
        val systemTime = parseStationTime(obj.optString("SystemTime"))
        val elapsedAtFetchMs = if (playStart != null && systemTime != null) {
            (systemTime - playStart).coerceAtLeast(0L)
        } else {
            0L
        }

        return NowPlayingMetadata(
            track = decodeEntities(obj.optString("Track", "Death.FM")).ifBlank { "Death.FM" },
            artist = decodeEntities(obj.optString("Artist", "Death.FM")).ifBlank { "Death.FM" },
            album = decodeEntities(obj.optString("Album", "")),
            lengthMs = obj.optString("Length", "0").toLongOrNull() ?: 0L,
            elapsedAtFetchMs = elapsedAtFetchMs,
            fetchedAtDeviceMs = System.currentTimeMillis(),
            spinId = playStart ?: 0L,
            coverUrl = obj.optString("CoverLink").ifBlank { null },
            listenerCount = obj.optString("ListenerCount", "0").toIntOrNull() ?: 0,
            asin = extractAsin(obj.optString("SiteLink")),
        )
    }

    private fun parseStationTime(raw: String): Long? = try {
        LocalDateTime.parse(raw, stationTimeFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    // The death.fm player page's own JS calls a decodeEntities() helper on
    // this same field before display - the API returns HTML-entity-encoded
    // text (e.g. "All&#039;inizio" for "All'inizio") rather than plain text.
    private fun decodeEntities(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

    // "SiteLink": "https://death.fm/modules.php?name=Album&asin=B00004L8BJ"
    private fun extractAsin(siteLink: String): String? =
        Regex("[?&]asin=([A-Za-z0-9]+)").find(siteLink)?.groupValues?.get(1)

    companion object {
        private const val TAG = "NowPlayingRepository"
        // Matches the death.fm player page's own updateTrackData() polling
        // cadence - no reason to hit the endpoint any harder than the
        // official web player itself does. Also the ceiling for the
        // end-of-track-timed polling in nextPollDelayMs.
        private const val POLL_INTERVAL_MS = 30_000L

        // Grace period after a track's expected end before polling again.
        // This isn't just "the station's database lags a beat" - our
        // elapsed-time math is anchored to the station's own broadcast
        // clock (see NowPlayingMetadata's doc), but the audio actually
        // reaching the listener is delayed by however much ExoPlayer has
        // buffered ahead, which varies with network conditions (cellular,
        // Bluetooth to a car head unit, etc.) and can easily be several
        // seconds. Too short a buffer here means the new track's artwork
        // shows up before the previous track has actually finished
        // playing audibly - confirmed happening in real testing over
        // Android Auto. There's no way to know the exact buffer depth, so
        // this is a tuning knob, not a precise fix - biased toward "a bit
        // late" rather than "too early," since a late update is far less
        // jarring than art changing mid-song.
        private const val END_OF_TRACK_BUFFER_MS = 8_000L

        // Floor on the computed delay, so a track reported with an already-
        // elapsed or bogus (e.g. zero/negative remaining) length can't turn
        // into a tight poll loop.
        private const val MIN_POLL_DELAY_MS = 3_000L
    }
}
