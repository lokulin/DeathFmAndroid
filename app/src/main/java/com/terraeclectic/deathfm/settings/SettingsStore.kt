package com.terraeclectic.deathfm.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-user preferences, mirroring what DeathFmTray's SettingsStore.cs keeps
 * in %AppData%\DeathFmTray\settings.json - here backed by a private
 * SharedPreferences file instead. Last.fm needs its own API key/secret per
 * person since this repo is public (same reasoning as the desktop app).
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("deathfm_settings", Context.MODE_PRIVATE)

    var lastFmApiKey: String?
        get() = prefs.getString(KEY_LASTFM_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_LASTFM_API_KEY, value).apply()

    var lastFmApiSecret: String?
        get() = prefs.getString(KEY_LASTFM_API_SECRET, null)
        set(value) = prefs.edit().putString(KEY_LASTFM_API_SECRET, value).apply()

    var lastFmSessionKey: String?
        get() = prefs.getString(KEY_LASTFM_SESSION_KEY, null)
        set(value) = prefs.edit().putString(KEY_LASTFM_SESSION_KEY, value).apply()

    val isLastFmConfigured: Boolean
        get() = !lastFmApiKey.isNullOrBlank() && !lastFmApiSecret.isNullOrBlank()

    val isLastFmConnected: Boolean
        get() = isLastFmConfigured && !lastFmSessionKey.isNullOrBlank()

    fun clearLastFmSession() {
        prefs.edit().remove(KEY_LASTFM_SESSION_KEY).apply()
    }

    companion object {
        private const val KEY_LASTFM_API_KEY = "lastfm_api_key"
        private const val KEY_LASTFM_API_SECRET = "lastfm_api_secret"
        private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    }
}
