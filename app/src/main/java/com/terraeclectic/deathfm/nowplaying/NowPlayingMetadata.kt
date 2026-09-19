package com.terraeclectic.deathfm.nowplaying

/**
 * One snapshot of death.fm's FM24sevenJSON "GetCurrentlyPlaying" response.
 *
 * Unlike the Windows app (which DOM-scrapes the player page and never learns
 * a real track length), this hits the JSON endpoint the page itself calls
 * and gets [lengthMs] + [playStartUtc] directly - enough to compute real
 * elapsed/remaining position and to scrobble against Last.fm's actual
 * "half the track or 4 minutes, whichever is shorter" rule instead of the
 * desktop app's 30-second wall-clock proxy.
 */
data class NowPlayingMetadata(
    val track: String,
    val artist: String,
    val album: String,
    val lengthMs: Long,
    val playStartUtc: Long,
    val coverUrl: String?,
    val listenerCount: Int,
) {
    /** Identity for "has the track actually changed" - PlayStart is unique per spin. */
    val trackKey: String get() = "$track|$artist|$playStartUtc"

    companion object {
        val EMPTY = NowPlayingMetadata(
            track = "Death.FM",
            artist = "Death.FM",
            album = "",
            lengthMs = 0L,
            playStartUtc = 0L,
            coverUrl = null,
            listenerCount = 0,
        )
    }
}
