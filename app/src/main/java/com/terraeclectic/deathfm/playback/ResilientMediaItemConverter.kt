package com.terraeclectic.deathfm.playback

import android.net.Uri
import android.util.Log
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata

/**
 * Wraps Media3's [DefaultMediaItemConverter] to tolerate media that wasn't
 * loaded by this app's own [androidx.media3.cast.CastPlayer] - namely,
 * DeathFmCastReceiver's own receiver-initiated station switching (see its
 * SOMAFM_INTEGRATION_PLAN.md), which issues Cast loads whose `customData`
 * only ever contains `{stationId: "..."}`.
 *
 * DefaultMediaItemConverter.toMediaItem() unconditionally requires a
 * `customData.mediaItem` object with its own `uri`/`mediaId` fields - that's
 * its own private round-trip serialization of a MediaItem it originated,
 * not a documented Cast contract, and it throws (an uncaught
 * JSONException wrapped in a RuntimeException) whenever that key is
 * missing. Confirmed live: this crashed the app every time the receiver
 * switched stations, whether triggered by a TV remote or a phone's system
 * media widget - reproduced twice via `adb logcat`, both times the same
 * `FATAL EXCEPTION: ... No value for mediaItem` stack trace, landing
 * right after a station switch reached PLAYING on the receiver's own
 * debug log.
 *
 * Falls back to building a [MediaItem] directly from the [MediaQueueItem]'s
 * [com.google.android.gms.cast.MediaInfo] fields instead - contentId/
 * contentUrl/contentType/metadata are always present on any real Cast
 * media, regardless of who originated the load.
 */
@UnstableApi
class ResilientMediaItemConverter : MediaItemConverter {

    private val default = DefaultMediaItemConverter()

    // Only ever used for media this app casts itself, which always has a
    // proper Media3 MediaItem (mimeType set etc.) behind it - no need to
    // guard this direction.
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem =
        default.toMediaQueueItem(mediaItem)

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        return try {
            default.toMediaItem(mediaQueueItem)
        } catch (e: Exception) {
            Log.w(TAG, "DefaultMediaItemConverter couldn't parse this queue item (probably " +
                "loaded by something other than this app's own CastPlayer) - falling back " +
                "to MediaInfo's own fields instead of crashing", e)
            fromMediaInfo(mediaQueueItem)
        }
    }

    private fun fromMediaInfo(mediaQueueItem: MediaQueueItem): MediaItem {
        val mediaInfo = mediaQueueItem.media ?: return MediaItem.Builder().build()

        // contentUrl is the field DefaultMediaItemConverter itself populates
        // for items it originates, but DeathFmCastReceiver's loadStationUrl()
        // only ever sets contentId (to the real stream URL) - falling back to
        // that covers both cases.
        val uriString = mediaInfo.contentUrl ?: mediaInfo.contentId
        val builder = MediaItem.Builder()
            .setMediaId(mediaInfo.contentId ?: uriString)
            .setUri(Uri.parse(uriString))

        mediaInfo.contentType?.let { builder.setMimeType(it) }

        val title = mediaInfo.metadata
            ?.takeIf { it.containsKey(CastMediaMetadata.KEY_TITLE) }
            ?.getString(CastMediaMetadata.KEY_TITLE)
        if (title != null) {
            builder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "ResilientMediaConverter"
    }
}
