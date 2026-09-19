package com.terraeclectic.deathfm.queueplayed

/**
 * One row from either the "Queue" (upcoming) or "Played" (recent history)
 * list on death.fm's player page - informational only. Unlike the current
 * station's own playback, there's no way to actually jump to one of these:
 * it's a fixed DJ rotation, not a user-controllable playlist, which is also
 * why this never gets surfaced to Android Auto (see PlaybackService's doc -
 * exposing this as a real, tappable "queue" there would imply control that
 * doesn't exist).
 */
data class QueueEntry(
    val rank: Int,
    val thumbnailUrl: String?,
    val artist: String,
    val albumOrTrack: String,
)
