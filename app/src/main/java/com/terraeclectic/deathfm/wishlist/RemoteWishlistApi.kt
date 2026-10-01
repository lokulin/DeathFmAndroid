package com.terraeclectic.deathfm.wishlist

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Sends wishlist changes to a private wishlist server (`POST`/`DELETE
 * /wishlist`, authenticated with its Cloudflare Access service token).
 *
 * Only ever constructed in builds whose `local.properties` supplies those
 * credentials (see `BuildConfig.WISHLIST_ENABLED`) - the public release
 * builds have none, so the feature simply isn't there.
 */
class RemoteWishlistApi(
    private val baseUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val http: OkHttpClient = OkHttpClient(),
) : WishlistSender {

    override suspend fun send(op: PendingOp): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
                .put("artist", op.entry.artist)
                .put("title", op.entry.title)
                .put("album", op.entry.album)
                .put("source", op.entry.source)
                .put("device", Build.MODEL)
                .put("likedAt", op.at)
            op.entry.coverUrl?.let { json.put("coverUrl", it) }
            val body = json.toString().toRequestBody(JSON)
            val builder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/wishlist")
                .header("CF-Access-Client-Id", clientId)
                .header("CF-Access-Client-Secret", clientSecret)
            val request = if (op.liked) builder.post(body).build() else builder.delete(body).build()
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    // The server looked at it and said no (bad data): retrying the same request
                    // forever would just wedge the queue. Auth problems and throttling aren't
                    // the entry's fault, so those are kept for a later retry.
                    response.code in 400..499 && response.code !in RETRY_LATER -> {
                        Log.w(TAG, "Wishlist request rejected (${response.code}) for ${op.entry.key}, dropping it")
                        true
                    }
                    else -> {
                        Log.w(TAG, "Wishlist request failed (${response.code}), will retry")
                        false
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Wishlist request failed (offline?), will retry", e)
            false
        }
    }

    private companion object {
        const val TAG = "RemoteWishlist"
        val JSON = "application/json".toMediaType()
        val RETRY_LATER = setOf(401, 403, 408, 429)
    }
}

/** The liked set + pending queue in a private SharedPreferences file. */
class SharedPrefsWishlistStorage(context: Context) : WishlistStorage {
    private val prefs = context.getSharedPreferences("deathfm_wishlist", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val KEY = "state"
    }
}
