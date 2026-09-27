package com.terraeclectic.deathfm.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-user preferences, mirroring what DeathFmTray's SettingsStore.cs keeps
 * in %AppData%\DeathFmTray\settings.json - here backed by a private
 * SharedPreferences file instead. Last.fm's API key/secret are baked into
 * the app itself (see LastFmCredentials) rather than stored here - this
 * only holds the per-user Last.fm session, populated by the Connect flow.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("deathfm_settings", Context.MODE_PRIVATE)

    var lastFmSessionKey: String?
        get() = prefs.getString(KEY_LASTFM_SESSION_KEY, null)
        set(value) = prefs.edit().putString(KEY_LASTFM_SESSION_KEY, value).apply()

    val isLastFmConnected: Boolean
        get() = !lastFmSessionKey.isNullOrBlank()

    fun clearLastFmSession() {
        prefs.edit().remove(KEY_LASTFM_SESSION_KEY).apply()
    }

    companion object {
        private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
    }
}
