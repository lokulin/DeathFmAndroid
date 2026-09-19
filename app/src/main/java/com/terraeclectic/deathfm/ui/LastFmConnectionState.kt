package com.terraeclectic.deathfm.ui

/** Drives SettingsScreen's Last.fm section - mirrors the real steps of Last.fm's desktop-app auth flow. */
sealed interface LastFmConnectionState {
    data object Disconnected : LastFmConnectionState
    data object AwaitingApproval : LastFmConnectionState
    data object Connected : LastFmConnectionState
    data class Failed(val message: String) : LastFmConnectionState
}
