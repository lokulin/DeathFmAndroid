package com.terraeclectic.deathfm.playback

import android.os.Bundle
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.terraeclectic.deathfm.DeathFmApp
import com.terraeclectic.deathfm.R
import com.terraeclectic.deathfm.lastfm.LastFmCredentials
import com.terraeclectic.deathfm.lastfm.LastFmScrobbler
import com.terraeclectic.deathfm.nowplaying.NowPlayingMetadata
import com.terraeclectic.deathfm.nowplaying.NowPlayingRepository
import com.terraeclectic.deathfm.queueplayed.QueuePlayedRepository
import com.terraeclectic.deathfm.wishlist.WishlistEntry
import com.terraeclectic.deathfm.wishlist.WishlistRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    // What the session sees in place of localPlayer, so Auto's queue can list the upcoming tracks without
    // any of it being playable - see LiveStreamGuardPlayer.
    private lateinit var guardedLocalPlayer: Player
    private lateinit var nowPlayingRepository: NowPlayingRepository
    private lateinit var queueRepository: QueuePlayedRepository
    private lateinit var scrobbler: LastFmScrobbler

    // Private-build feature (see DeathFmApp.wishlist): null in public builds.
    private var wishlist: WishlistRepository? = null
    private var latestNowPlaying: NowPlayingMetadata = NowPlayingMetadata.EMPTY

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

        guardedLocalPlayer = LiveStreamGuardPlayer(localPlayer)
        mediaSession = MediaLibrarySession.Builder(this, guardedLocalPlayer, librarySessionCallback)
            .build()

        val app = application as DeathFmApp
        nowPlayingRepository = NowPlayingRepository(Stations.DEATH_FM)
        queueRepository = QueuePlayedRepository(Stations.DEATH_FM)

        wishlist = app.wishlist
        wishlist?.let { repository ->
            // The heart in the car / notification follows the liked set (a like from the phone UI too).
            serviceScope.launch { repository.liked.collect { refreshLikeButton() } }
        }

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
    //
    // ResilientMediaItemConverter (not CastPlayer's default) - the custom
    // DeathFmCastReceiver issues its own receiver-initiated loads when
    // switching stations (a TV remote or a phone's system media widget),
    // and Media3's DefaultMediaItemConverter crashes on any queue item it
    // didn't originate itself. See that class's own doc comment for the
    // full story.
    private fun initializeCastPlayer() {
        try {
            val castContext = CastContext.getSharedInstance(this)
            castPlayer = CastPlayer(castContext, ResilientMediaItemConverter()).apply {
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
            sendLastFmCredentialsToReceiver()
        }

        override fun onCastSessionUnavailable() {
            switchToPlayer(localPlayer)
        }
    }

    /**
     * Hands the receiver everything it needs to run Last.fm scrobbling
     * itself, independently, for the rest of the cast session - see
     * LASTFM_SCROBBLING_PLAN.md (DeathFmCastReceiver repo) for the full
     * reasoning. The receiver already polls death.fm's own API and tracks
     * elapsed time correctly on its own; this is a one-time handoff, not an
     * ongoing relay - once sent, the phone's own scrobbling is suppressed
     * (see playerListener.onIsPlayingChanged) so the same play never gets
     * scrobbled twice.
     *
     * No-op if the user isn't connected to Last.fm - the receiver simply
     * never gets credentials and never attempts to scrobble, no explicit
     * "disabled" signal needed.
     */
    private fun sendLastFmCredentialsToReceiver() {
        val settings = (application as DeathFmApp).settings
        if (!settings.isLastFmConnected) return

        val message = JSONObject().apply {
            put("apiKey", LastFmCredentials.API_KEY)
            put("apiSecret", LastFmCredentials.API_SECRET)
            put("sessionKey", settings.lastFmSessionKey)
        }.toString()

        // sendMessage silently no-ops (an already-resolved failed
        // PendingResult, confirmed against the SDK's own bytecode) rather
        // than throwing if there's no connected session - still guard with
        // a null check since currentCastSession is a plain nullable getter,
        // not something the type system enforces non-null here.
        val session = CastContext.getSharedInstance(this).sessionManager.currentCastSession
        if (session == null) {
            Log.w(TAG, "sendLastFmCredentialsToReceiver: no current CastSession")
            return
        }
        session.sendMessage(LASTFM_NAMESPACE, message).setResultCallback { status ->
            if (status.isSuccess) {
                Log.d(TAG, "Sent Last.fm credentials to receiver")
            } else {
                Log.w(TAG, "Failed to send Last.fm credentials to receiver: ${status.statusCode}")
            }
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
        mediaSession.player = if (newPlayer === localPlayer) guardedLocalPlayer else newPlayer

        if (mediaItem != null) {
            newPlayer.setMediaItem(mediaItem)
            newPlayer.playWhenReady = playWhenReady
            newPlayer.prepare()
        }
        applyUpcomingQueue()
    }

    /**
     * Android Auto's "Queue" button lists the session player's playlist, so the
     * tracks death.fm says are coming up are appended after the (live, endless)
     * current item. They never play - the stream never ends to reach them, and
     * [LiveStreamGuardPlayer] ignores taps on them - they exist only to be shown.
     * Local playback only: a CastPlayer playlist edit would reload the stream on the TV.
     */
    private var upcomingItems: List<MediaItem> = emptyList()
    private var upcomingForAsin: String? = null

    private fun refreshUpcomingQueue(asin: String?) {
        if (asin.isNullOrBlank() || asin == upcomingForAsin) return
        upcomingForAsin = asin
        serviceScope.launch {
            try {
                val queue = queueRepository.fetch(asin).queue.take(MAX_UPCOMING)
                upcomingItems = queue.map { entry ->
                    MediaItem.Builder()
                        .setMediaId("queue:${entry.rank}:${entry.artist}|${entry.albumOrTrack}")
                        .setUri(Stations.DEATH_FM.streamUrl) // ExoPlayer needs one to build the item; it's never prepared
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entry.albumOrTrack)
                                .setArtist(entry.artist)
                                .setArtworkUri(entry.thumbnailUrl?.let { android.net.Uri.parse(it) })
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build(),
                        )
                        .build()
                }
                applyUpcomingQueue()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't load the upcoming queue", e)
                upcomingForAsin = null // try again on the next poll
            }
        }
    }

    // Idempotent (it's also called from onTimelineChanged, which its own edits trigger): only touches the playlist if it differs.
    private fun applyUpcomingQueue() {
        val player = localPlayer
        if (currentPlayer !== player || player.mediaItemCount == 0) return
        val start = player.currentMediaItemIndex + 1
        val have = (start until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (have == upcomingItems.map { it.mediaId }) return
        if (start < player.mediaItemCount) player.removeMediaItems(start, player.mediaItemCount)
        if (upcomingItems.isNotEmpty()) player.addMediaItems(upcomingItems)
    }

    /**
     * The local player as the MediaSession sees it: a live stream has nothing to
     * skip to, so the upcoming-queue entries (see [applyUpcomingQueue]) must not be
     * reachable - no next/previous, and a tap on a queue row (Auto's
     * skipToQueueItem) is ignored, leaving the stream playing untouched.
     */
    private class LiveStreamGuardPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = false
        override fun hasPreviousMediaItem(): Boolean = false
        override fun seekToNext() {}
        override fun seekToNextMediaItem() {}
        override fun seekToPrevious() {}
        override fun seekToPreviousMediaItem() {}
        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (mediaItemIndex == currentMediaItemIndex) super.seekToDefaultPosition(mediaItemIndex)
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == currentMediaItemIndex) super.seekTo(mediaItemIndex, positionMs)
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
            //
            // Forced false while casting, regardless of the CastPlayer's own
            // isPlaying - the receiver now runs its own independent
            // scrobbling client (see sendLastFmCredentialsToReceiver), and
            // letting the phone's scrobbler keep running too would double-
            // scrobble the same play. Local playback resumes normal
            // isPlaying-driven behaviour the moment currentPlayer switches
            // back off the CastPlayer.
            scrobbler.setPlaying(if (currentPlayer === castPlayer) false else isPlaying)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // A new play request replaces the whole playlist - put the upcoming rows back after it.
            applyUpcomingQueue()
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
        latestNowPlaying = metadata
        refreshLikeButton() // a new track starts with its own heart state, even while casting
        refreshUpcomingQueue(metadata.asin)
        if (currentPlayer === castPlayer) return

        val current = currentPlayer.currentMediaItem ?: return
        val updated = current.buildUpon()
            .setMediaMetadata(buildMetadata(metadata))
            .build()
        currentPlayer.replaceMediaItem(currentPlayer.currentMediaItemIndex, updated)
    }

    /** The track playing right now, as a wishlist entry - null before the first real track. */
    private fun currentWishlistEntry(): WishlistEntry? =
        latestNowPlaying
            .let { WishlistEntry(artist = it.artist, title = it.track, album = it.album, coverUrl = it.coverUrl) }
            .takeIf { it.isRealTrack }

    /** The single heart button Android Auto (and the notification) shows next to the transport controls. */
    private fun likeLayout(): ImmutableList<CommandButton> {
        val entry = currentWishlistEntry()
        val liked = entry != null && wishlist?.isLiked(entry) == true
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName(if (liked) "Remove from wishlist" else "Add to wishlist")
                .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                .setSessionCommand(SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY))
                .setEnabled(entry != null)
                .build(),
        )
    }

    // There's no toast in a car: the button itself has to visibly change, so the layout is re-published on every
    // track change and every like/unlike.
    private fun refreshLikeButton() {
        if (wishlist == null || !::mediaSession.isInitialized) return
        mediaSession.setCustomLayout(likeLayout())
    }

    // The metadata is snapshotted at the moment of the tap: the poll can move on to the next track while the
    // request is still in flight, and it's the track the user heard that they meant.
    private fun toggleLikeFromSession() {
        val repository = wishlist ?: return
        val entry = currentWishlistEntry() ?: return
        serviceScope.launch { repository.toggle(entry) }
    }

    private fun buildMetadata(metadata: NowPlayingMetadata): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(metadata.track)
            .setArtist(metadata.artist)
            .setAlbumTitle(metadata.album)
            // Android Auto's Now Playing template reads these display fields
            // for its title/subtitle text, not the raw title/artist above -
            // confirmed by checking VLC-Android's real PlaybackService, which
            // sets METADATA_KEY_DISPLAY_TITLE/DISPLAY_SUBTITLE explicitly in
            // car mode rather than relying on Auto to synthesize them itself.
            .setDisplayTitle(metadata.track)
            .setSubtitle(metadata.artist)
            .setArtworkUri(metadata.coverUrl?.let { android.net.Uri.parse(it) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            // Set on every metadata update, not just the initial item - see
            // stationMediaItem's doc on MEDIA_TYPE_RADIO_STATION.
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
            // Doesn't actually change Auto's display, since LiveConfiguration
            // only tunes real live formats (HLS/DASH manifests that already
            // declare themselves live) and is silently ignored for a plain
            // progressive HTTP stream like this one. Left in place since it's
            // harmless and correctly documents intent.
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

    // Tapping a liked track just starts the stream (onAddMediaItems maps any unknown id to it) - a radio can't play a specific track.
    private fun likedMediaItem(entry: WishlistEntry): MediaItem =
        MediaItem.Builder()
            .setMediaId("liked:${entry.key}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.artist)
                    .setAlbumTitle(entry.album.ifBlank { null })
                    .setArtworkUri(entry.coverUrl?.let { android.net.Uri.parse(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
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
        override fun onConnect(
            session: MediaSession,
            controller: ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (wishlist == null) return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(likeLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_TOGGLE_LIKE) {
                toggleLikeFromSession()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

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
            val liked = wishlist
            val children = when {
                parentId == ROOT_ID ->
                    Stations.all.map { stationMediaItem(it) } +
                        // Private builds only (the wishlist is null otherwise): the tracks hearted from the car/phone.
                        listOfNotNull(liked?.let { browsableFolder(LIKED_ID, "Liked") })
                parentId == LIKED_ID && liked != null -> liked.likedEntries().map { likedMediaItem(it) }
                else -> emptyList()
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
        private const val COMMAND_TOGGLE_LIKE = "com.terraeclectic.deathfm.TOGGLE_LIKE"
        private const val ROOT_ID = "root"
        private const val LIKED_ID = "browse:liked"
        private const val MAX_UPCOMING = 10
        private const val MIME_TYPE_CAST_AUDIO_AAC = "audio/aac"
        // Must match the receiver's listener namespace exactly
        // (DeathFmCastReceiver's index.html) - no Cast Console registration
        // needed for a custom namespace, just this string convention on
        // both ends (must start with "urn:x-cast:").
        private const val LASTFM_NAMESPACE = "urn:x-cast:com.terraeclectic.deathfm.lastfm"
        const val EXTRA_LENGTH_MS = "com.terraeclectic.deathfm.LENGTH_MS"
        const val EXTRA_ELAPSED_AT_FETCH_MS = "com.terraeclectic.deathfm.ELAPSED_AT_FETCH_MS"
        const val EXTRA_FETCHED_AT_DEVICE_MS = "com.terraeclectic.deathfm.FETCHED_AT_DEVICE_MS"
        const val EXTRA_ASIN = "com.terraeclectic.deathfm.ASIN"
    }
}
