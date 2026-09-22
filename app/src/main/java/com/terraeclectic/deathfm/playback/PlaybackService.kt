package com.terraeclectic.deathfm.playback

import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.terraeclectic.deathfm.DeathFmApp
import com.terraeclectic.deathfm.lastfm.LastFmScrobbler
import com.terraeclectic.deathfm.nowplaying.NowPlayingMetadata
import com.terraeclectic.deathfm.nowplaying.NowPlayingRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer + MediaSession pair that everything else hangs off:
 * the lock-screen/notification controls, the app's own UI (via a
 * MediaController connected to this session), and Android Auto (via the
 * MediaLibrarySession browse tree below). One always-on foreground service,
 * same lifetime model as a normal music-player app.
 *
 * There's only one "station" node for now (see [Stations]) - browsing is a
 * flat list of one playable item rather than anything hierarchical, on
 * purpose, so adding the other four death.fm network stations later is just
 * more entries in [Stations.all], not a rework of this browse tree.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var localPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null

    // Whichever of localPlayer/castPlayer is currently attached to
    // mediaSession - tracked separately rather than read back off
    // mediaSession.player so switchToPlayer has the *previous* player in
    // hand (to stop it) before it hands the session the new one.
    private lateinit var currentPlayer: Player

    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var nowPlayingRepository: NowPlayingRepository
    private lateinit var scrobbler: LastFmScrobbler

    private val rootItem = browsableFolder(ROOT_ID, "Death.FM Network")

    override fun onCreate() {
        super.onCreate()

        localPlayer = ExoPlayer.Builder(this)
            // Neither of these is on by default - without them ExoPlayer
            // never requests/responds to Android's audio focus system at
            // all, which is why switching to another media app (in Android
            // Auto or otherwise) didn't pause us, switching back to us
            // didn't pause the other app, and disconnecting Bluetooth kept
            // playing out of the phone's own speaker instead of stopping.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
            }
        currentPlayer = localPlayer

        mediaSession = MediaLibrarySession.Builder(this, currentPlayer, librarySessionCallback)
            .build()

        val app = application as DeathFmApp
        nowPlayingRepository = NowPlayingRepository(Stations.DEATH_FM)

        scrobbler = LastFmScrobbler(app.settings, nowPlayingRepository.nowPlaying)
        scrobbler.start(serviceScope)

        serviceScope.launch {
            nowPlayingRepository.nowPlaying.collect { metadata -> onNowPlayingChanged(metadata) }
        }

        initializeCastPlayer()
    }

    // CastContext.getSharedInstance can fail on devices without a current-
    // enough Play Services (rare, but not impossible on the sideloaded/Auto-
    // adjacent hardware this app also targets) - casting just silently isn't
    // offered rather than crashing the service on startup.
    private fun initializeCastPlayer() {
        try {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext).apply {
                setSessionAvailabilityListener(castSessionListener)
                addListener(playerListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cast unavailable", e)
        }
    }

    private val castSessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            switchToPlayer(castPlayer ?: return)
        }

        override fun onCastSessionUnavailable() {
            switchToPlayer(localPlayer)
        }
    }

    /**
     * Hands the media session's currently-playing item off to [newPlayer],
     * stopping whichever player was active before so they don't both end up
     * outputting audio.
     *
     * Deliberately does NOT carry over the local player's real track
     * metadata when handing off to the CastPlayer - onNowPlayingChanged no
     * longer pushes updates to it afterwards (see its doc), so whatever
     * metadata this item started with is what the TV shows for the entire
     * cast session. Using the generic station branding here rather than
     * "whatever happened to be playing at the moment you tapped Cast" avoids
     * showing one real track/artist frozen in place, stale, for however long
     * the session runs.
     */
    private fun switchToPlayer(newPlayer: Player) {
        if (currentPlayer === newPlayer) return

        val mediaItem = if (newPlayer === castPlayer) {
            castBrandingMediaItem(Stations.DEATH_FM)
        } else {
            currentPlayer.currentMediaItem
        }
        val playWhenReady = currentPlayer.playWhenReady
        currentPlayer.stop()

        currentPlayer = newPlayer
        mediaSession.player = newPlayer

        if (mediaItem != null) {
            newPlayer.setMediaItem(mediaItem)
            newPlayer.playWhenReady = playWhenReady
            newPlayer.prepare()
        }
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession = mediaSession

    override fun onDestroy() {
        nowPlayingRepository.stop()
        scrobbler.stop()
        mediaSession.release()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        localPlayer.release()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            // Gates actual scrobbling/now-playing updates - deliberately
            // tied to genuine audible playback (not the more tolerant
            // updateNowPlayingPolling below), matching Last.fm's own
            // semantics of "are they actually listening right now."
            scrobbler.setPlaying(isPlaying)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                updateNowPlayingPolling(player)
            }
            // TEMP debug logging - Android Auto's Now Playing widget still
            // shows a stuck "0:00" total/time-since-started instead of a
            // live-stream display on a real head unit, despite
            // stationMediaItem's setLiveConfiguration call (which, per
            // ProgressiveMediaSource's own source, only tunes real live
            // formats like HLS/DASH and does nothing for a plain progressive
            // HTTP stream like ours). Logging the real Player-reported values
            // Auto's legacy MediaSession bridge actually reads, next time
            // this is tested in the car, rather than guessing at another fix.
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                Log.d(
                    TAG,
                    "DEBUG duration=${player.duration} isLive=${player.isCurrentMediaItemLive} " +
                        "isSeekable=${player.isCurrentMediaItemSeekable} contentDuration=${player.contentDuration}",
                )
            }
        }
    }

    /**
     * Starts/stops the now-playing poll based on whether we're genuinely
     * trying to play - deliberately NOT the same signal as isPlaying above.
     * isPlaying flips false during any transient rebuffer (STATE_BUFFERING),
     * not just a real user Stop, which would otherwise stop/restart this
     * poll on every brief network hiccup instead of only on an actual Stop -
     * the same class of bug DeathFmTray's scrobbler once had with a
     * wall-clock timer that reset on any interruption rather than only a
     * real Stop. playWhenReady stays true through a rebuffer and only turns
     * false when stop() is actually called.
     */
    private fun updateNowPlayingPolling(player: Player) {
        val shouldPoll = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        if (shouldPoll) nowPlayingRepository.start(serviceScope) else nowPlayingRepository.stop()
    }

    /**
     * Pushes fresh track/artist/artwork into the currently-playing MediaItem
     * so lock-screen, notification, and Auto all pick it up - the stream
     * itself never changes, only its metadata does.
     *
     * Deliberately a no-op while casting, by design, not merely throttled:
     * confirmed live that CastPlayer.replaceMediaItem()/replaceMediaItems()
     * is implemented as addMediaItems() + removeMediaItems() - a real queue
     * insert-then-remove, not a metadata patch - so pushing updates to it
     * visibly pauses/reloads the stream on every track change even though
     * the underlying URL never changes. RemoteMediaClient.queueUpdateItems()
     * (the real "patch metadata in place" Cast API, which CastPlayer doesn't
     * expose) could avoid that, but the actual point of casting here is to
     * hand off completely: the phone should be safe to close once a cast
     * session starts, not stay alive polling death.fm just to keep a title
     * on the TV in sync. The TV keeps whatever metadata was current at
     * hand-off (set once in switchToPlayer) for the rest of the session.
     */
    private fun onNowPlayingChanged(metadata: NowPlayingMetadata) {
        if (currentPlayer === castPlayer) return

        val current = currentPlayer.currentMediaItem ?: return
        val updated = current.buildUpon()
            .setMediaMetadata(buildMetadata(metadata))
            .build()
        currentPlayer.replaceMediaItem(currentPlayer.currentMediaItemIndex, updated)
    }

    private fun buildMetadata(metadata: NowPlayingMetadata): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(metadata.track)
            .setArtist(metadata.artist)
            .setAlbumTitle(metadata.album)
            .setArtworkUri(metadata.coverUrl?.let { android.net.Uri.parse(it) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            // See stationMediaItem's doc on MEDIA_TYPE_RADIO_STATION for why
            // this is set on every metadata update, not just the initial
            // item - Android Auto still showed a stuck "0:00" total without
            // it, confirmed live on a real head unit despite the player's own
            // duration correctly reporting unset/unknown.
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            // MediaMetadata.durationMs exists but is deliberately left unset
            // here too - death.fm's own "Length" is catalog metadata a live
            // spin can run past (see PlaybackProgress's doc), not a real
            // fixed duration to advertise to the system. The real numbers for
            // our own progress bar go in extras below instead.
            .setExtras(android.os.Bundle().apply {
                putLong(EXTRA_LENGTH_MS, metadata.lengthMs)
                putLong(EXTRA_ELAPSED_AT_FETCH_MS, metadata.elapsedAtFetchMs)
                putLong(EXTRA_FETCHED_AT_DEVICE_MS, metadata.fetchedAtDeviceMs)
                putString(EXTRA_ASIN, metadata.asin)
            })
            .build()

    private fun stationMediaItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.streamUrl)
            // The stream serves "audio/aacp" (HE-AAC/AAC+, confirmed via its
            // response headers) - ExoPlayer sniffs that fine on its own, but
            // a Chromecast receiver needs an explicit, standard contentType
            // to know how to play it; CastPlayer's DefaultMediaItemConverter
            // forwards this mimeType straight through as that contentType.
            // Deliberately the plain "audio/aac" string, not
            // MimeTypes.AUDIO_AAC ("audio/mp4a-latm") - that constant is
            // ExoPlayer's internal RFC6381 codec name, not the web MIME type
            // a Cast receiver's contentType field expects.
            .setMimeType(MIME_TYPE_CAST_AUDIO_AAC)
            // NOT a fix for Auto's stuck "0:00" duration display, despite
            // looking like one - confirmed by reading ProgressiveMediaSource's
            // own source: LiveConfiguration only tunes real live formats
            // (HLS/DASH manifests that already declare themselves live) and
            // is silently ignored for a plain progressive HTTP stream like
            // this one. Left in place since it's harmless and correctly
            // documents intent, but see MEDIA_TYPE_RADIO_STATION below (and
            // in buildMetadata) for the setting that actually addresses it -
            // Auto's legacy MediaSession bridge needs an explicit hint that
            // this is a radio station, not just an unset player duration
            // (which the player already reported correctly on its own).
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.displayName)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build()
            )
            .build()

    /**
     * The item handed to the CastPlayer on connect - see switchToPlayer's
     * doc for why this is deliberately generic branding, not whatever's
     * really playing. Kept separate from stationMediaItem's own baseline
     * metadata rather than folded into it - that one is also what the app
     * loads locally on a cold start, and showing a literal "Now Playing"
     * artist line on the phone's own now-playing screen before the first
     * real poll lands would be a bug there, not a feature.
     */
    private fun castBrandingMediaItem(station: Station): MediaItem {
        val base = stationMediaItem(station)
        return base.buildUpon()
            .setMediaMetadata(
                base.mediaMetadata.buildUpon()
                    .setArtist("Now Playing")
                    .setArtworkUri(android.net.Uri.parse(station.logoUrl))
                    .build()
            )
            .build()
    }

    private fun browsableFolder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .build()
            )
            .build()

    private val librarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val children = if (parentId == ROOT_ID) {
                Stations.all.map { stationMediaItem(it) }
            } else {
                emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(com.google.common.collect.ImmutableList.copyOf(children), params)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // Auto/the app only ever pass a mediaId (from the browse tree, or
            // the one MainActivity plays directly) - resolve it to the real
            // stream item with a URI attached before ExoPlayer sees it.
            val resolved = mediaItems.map { item ->
                val station = Stations.all.find { it.id == item.mediaId } ?: Stations.DEATH_FM
                stationMediaItem(station)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        // Called when Android Auto (or any surface asking "what should play
        // right now") launches without the user picking anything from the
        // browse tree - without this, Auto always shows the one-station
        // browse list first and makes the user tap it, even though there's
        // only ever one thing to choose. With just one station, there's
        // nothing to actually "resume" (no meaningful playback position for
        // a live stream) - this just always hands back that one station, so
        // launching the app goes straight to the player.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(stationMediaItem(Stations.DEATH_FM)),
                    /* startIndex= */ 0,
                    /* startPositionMs= */ 0L,
                ),
            )
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val ROOT_ID = "root"
        private const val MIME_TYPE_CAST_AUDIO_AAC = "audio/aac"
        const val EXTRA_LENGTH_MS = "com.terraeclectic.deathfm.LENGTH_MS"
        const val EXTRA_ELAPSED_AT_FETCH_MS = "com.terraeclectic.deathfm.ELAPSED_AT_FETCH_MS"
        const val EXTRA_FETCHED_AT_DEVICE_MS = "com.terraeclectic.deathfm.FETCHED_AT_DEVICE_MS"
        const val EXTRA_ASIN = "com.terraeclectic.deathfm.ASIN"
    }
}
