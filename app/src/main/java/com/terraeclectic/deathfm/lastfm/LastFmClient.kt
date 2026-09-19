package com.terraeclectic.deathfm.lastfm

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Thin wrapper over Last.fm's Audioscrobbler REST API (auth.getToken /
 * auth.getSession / track.updateNowPlaying / track.scrobble) - the same
 * plain REST+JSON surface DeathFmTray's LastFmScrobbler.cs talks to, no
 * native interop needed here either. Every write call must be signed with
 * an md5 "api_sig" per Last.fm's spec: sort params by key, concatenate
 * key+value pairs, append the shared secret, md5 it.
 */
class LastFmClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /** Step 1 of the desktop-app auth flow: get a token to show the user an authorization page for. */
    fun getToken(): String {
        val params = mapOf("method" to "auth.getToken", "api_key" to apiKey)
        val json = get(params)
        return json.getString("token")
    }

    /** The URL to open in a browser so the user can approve [token] for this app. */
    fun buildAuthUrl(token: String): String =
        "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    /** Step 2, called after the user confirms they approved the token in the browser. */
    fun getSession(token: String): String {
        val params = mapOf("method" to "auth.getSession", "api_key" to apiKey, "token" to token)
        val json = get(params, signed = true)
        return json.getJSONObject("session").getString("key")
    }

    fun updateNowPlaying(sessionKey: String, artist: String, track: String, album: String?) {
        val params = buildMap {
            put("method", "track.updateNowPlaying")
            put("api_key", apiKey)
            put("sk", sessionKey)
            put("artist", artist)
            put("track", track)
            if (!album.isNullOrBlank()) put("album", album)
        }
        post(params)
    }

    fun scrobble(sessionKey: String, artist: String, track: String, album: String?, timestampSeconds: Long) {
        val params = buildMap {
            put("method", "track.scrobble")
            put("api_key", apiKey)
            put("sk", sessionKey)
            put("artist", artist)
            put("track", track)
            put("timestamp", timestampSeconds.toString())
            if (!album.isNullOrBlank()) put("album", album)
        }
        post(params)
    }

    private fun sign(params: Map<String, String>): String {
        val base = params.toSortedMap().entries.joinToString(separator = "") { (k, v) -> "$k$v" } + apiSecret
        val digest = MessageDigest.getInstance("MD5").digest(base.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun get(params: Map<String, String>, signed: Boolean = false): JSONObject {
        val full = if (signed) params + ("api_sig" to sign(params)) else params
        val query = (full + ("format" to "json")).entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val request = Request.Builder().url("$API_ROOT?$query").build()
        return execute(request)
    }

    private fun post(params: Map<String, String>): JSONObject {
        val signature = sign(params)
        val bodyBuilder = FormBody.Builder()
        (params + ("api_sig" to signature) + ("format" to "json")).forEach { (k, v) -> bodyBuilder.add(k, v) }
        val request = Request.Builder().url(API_ROOT).post(bodyBuilder.build()).build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            if (json.has("error")) {
                throw LastFmException(json.optInt("error"), json.optString("message"))
            }
            return json
        }
    }

    companion object {
        private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
    }
}

class LastFmException(val code: Int, message: String) : Exception("Last.fm error $code: $message")
