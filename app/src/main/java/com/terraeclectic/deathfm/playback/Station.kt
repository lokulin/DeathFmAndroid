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
)

object Stations {
    val DEATH_FM = Station(
        id = "dfm",
        displayName = "Death.FM",
        streamUrl = "https://death.fm/live",
        nowPlayingUrl = "https://death.fm/soap/FM24sevenJSON.php?action=GetCurrentlyPlaying",
    )

    val all = listOf(DEATH_FM)

    fun byId(id: String): Station = all.first { it.id == id }
}
