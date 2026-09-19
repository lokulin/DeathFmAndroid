package com.terraeclectic.deathfm.nowplaying

/**
 * One snapshot of death.fm's FM24sevenJSON "GetCurrentlyPlaying" response.
 *
 * Unlike the Windows app (which DOM-scrapes the player page and never learns
 * a real track length), this hits the JSON endpoint the page itself calls
 * and gets [lengthMs] directly - enough to compute real elapsed/remaining
 * position and to scrobble against Last.fm's actual "half the track or 4
 * minutes, whichever is shorter" rule instead of the desktop app's
 * 30-second wall-clock proxy.
 *
 * [elapsedAtFetchMs]/[fetchedAtDeviceMs] deliberately avoid trusting the
 * station's own "PlayStart"/"SystemTime" fields as absolute timestamps -
 * confirmed live against this device's real UTC clock, the station's clock
 * turned out to be a flat 4 hours off (looks like it's actually running on
 * US Eastern time and mislabeling its own timestamps as UTC). The delta
 * between its own two fields is still self-consistent regardless of that
 * bug, so [elapsedAtFetchMs] captures "how far into the track, per the
 * station's own clock" and [fetchedAtDeviceMs] anchors that to this
 * device's correct clock at the moment it was fetched - elapsed time going
 * forward is `elapsedAtFetchMs + (now - fetchedAtDeviceMs)`, never the
 * station's clock directly.
 */
data class NowPlayingMetadata(
    val track: String,
    val artist: String,
    val album: String,
    val lengthMs: Long,
    val elapsedAtFetchMs: Long,
    val fetchedAtDeviceMs: Long,
    val spinId: Long,
    val coverUrl: String?,
    val listenerCount: Int,
    // The current track's Amazon ASIN, parsed out of "SiteLink" - needed to
    // call the player page's own get_db_info endpoint (queue/played history,
    // genre/year/rating), which is keyed by the currently playing track.
    val asin: String?,
) {
    /** Identity for "has the track actually changed" - spinId is unique per spin (though not a trustworthy absolute time - see class doc). */
    val trackKey: String get() = "$track|$artist|$spinId"

    companion object {
        val EMPTY = NowPlayingMetadata(
            track = "Death.FM",
            artist = "Death.FM",
            album = "",
            lengthMs = 0L,
            elapsedAtFetchMs = 0L,
            fetchedAtDeviceMs = 0L,
            spinId = 0L,
            coverUrl = null,
            listenerCount = 0,
            asin = null,
        )
    }
}
