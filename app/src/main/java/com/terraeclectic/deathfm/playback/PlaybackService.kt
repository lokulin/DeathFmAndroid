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

    /** Hands the media session's currently-playing item off to [newPlayer], stopping whichever player was active before so they don't both end up outputting audio. */
    private fun switchToPlayer(newPlayer: Player) {
        if (currentPlayer === newPlayer) return

        val mediaItem = currentPlayer.currentMediaItem
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

    // Last trackKey (title|artist|spinId - not the timestamp fields) pushed
    // to the CastPlayer. NowPlayingMetadata is a data class that includes
    // elapsedAtFetchMs/fetchedAtDeviceMs, which change on every ~30s poll
    // even when the track hasn't - so a plain equality/dedup check on the
    // whole metadata object never suppresses anything. That's fine for the
    // local player (replaceMediaItem there is cheap and keeps SMTC/Auto's
    // elapsed-time readout in sync), but confirmed live that doing the same
    // to a connected Chromecast visibly rebuffers the stream on every single
    // poll, not just on a real track change - so the cast path only pushes
    // a fresh item when the track identity actually changes.
    private var lastCastTrackKey: String? = null

    /** Pushes fresh track/artist/artwork into the currently-playing MediaItem so lock-screen, notification, Auto, and a connected Chromecast all pick it up - the stream itself never changes, only its metadata does. */
    private fun onNowPlayingChanged(metadata: NowPlayingMetadata) {
        if (currentPlayer === castPlayer) {
            if (metadata.trackKey == lastCastTrackKey) return
            lastCastTrackKey = metadata.trackKey
        }

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
            // MediaMetadata has no built-in "track length" field (duration
            // normally comes from the player/Timeline, meaningless for a live
            // stream) - stashed in extras so the UI can render a playtime
            // readout from the same real data the scrobbler uses. See
            // NowPlayingMetadata's doc for why this is elapsedAtFetchMs +
            // fetchedAtDeviceMs rather than a raw "start time" - the
            // station's own clock turned out not to be trustworthy as an
            // absolute timestamp.
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
            // Without this, nothing tells Media3/Android Auto this is live,
            // unbounded content - a plain progressive HTTP stream like ours
            // doesn't get auto-detected as "live" the way an HLS/DASH live
            // manifest would. Auto's Now Playing widget only reads the
            // official Player/MediaMetadata surface (not our own custom
            // extras, which only our app's Compose UI knows to read), so
            // without this it tried to render a normal elapsed/duration
            // timer for something with no real duration - landing on a
            // stuck "0:00" total instead of showing it as a live stream.
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.displayName)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

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
