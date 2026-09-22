package com.terraeclectic.deathfm.playback

/**
 * A single death.fm network station. Kept as a list of one for now (dfm
 * only) rather than a bare constant, so adding the other four
 * (1980s.fm/adagio.fm/entranced.fm/streamingsoundtracks.com - see the
 * Windows DeathFmTray README) later is a data change, not a restructure.
 */
data class Station(
    val id: String,
    val displayName: String,
    val streamUrl: String,
    val nowPlayingUrl: String,
    val logoUrl: String,
)

object Stations {
    val DEATH_FM = Station(
        id = "dfm",
        displayName = "Death.FM",
        streamUrl = "https://death.fm/live",
        nowPlayingUrl = "https://death.fm/soap/FM24sevenJSON.php?action=GetCurrentlyPlaying",
        // NOT death.fm's own logo URL - confirmed dead (404) on their end,
        // even in their own player page's onerror fallback, so not
        // something that broke on our side. Using our own already-hosted
        // copy of the same skull/headphones logo instead (same asset the
        // Cast skin at deathfm-cast.pages.dev uses), since we don't control
        // death.fm's server to get theirs fixed.
        logoUrl = "https://deathfm-cast.pages.dev/logo.png",
    )

    val all = listOf(DEATH_FM)

    fun byId(id: String): Station = all.first { it.id == id }
}
