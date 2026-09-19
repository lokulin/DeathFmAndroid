package com.terraeclectic.deathfm.playback

import android.util.Log
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

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private lateinit var nowPlayingRepository: NowPlayingRepository
    private lateinit var scrobbler: LastFmScrobbler

    private val rootItem = browsableFolder(ROOT_ID, "Death.FM Network")

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            addListener(playerListener)
        }

        mediaSession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
            .build()

        val app = application as DeathFmApp
        nowPlayingRepository = NowPlayingRepository(Stations.DEATH_FM)

        scrobbler = LastFmScrobbler(app.settings, nowPlayingRepository.nowPlaying)
        scrobbler.start(serviceScope)

        serviceScope.launch {
            nowPlayingRepository.nowPlaying.collect { metadata -> onNowPlayingChanged(metadata) }
        }
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession = mediaSession

    override fun onDestroy() {
        nowPlayingRepository.stop()
        scrobbler.stop()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            scrobbler.setPlaying(isPlaying)
            // Only poll death.fm's now-playing endpoint while actually
            // streaming - no reason to spend even the small amount of data/
            // battery that takes while stopped and nobody's listening.
            if (isPlaying) nowPlayingRepository.start(serviceScope) else nowPlayingRepository.stop()
        }
    }

    /** Pushes fresh track/artist/artwork into the currently-playing MediaItem so lock-screen, notification, and Auto all pick it up - the stream itself never changes, only its metadata does. */
    private fun onNowPlayingChanged(metadata: NowPlayingMetadata) {
        val current = player.currentMediaItem ?: return
        val updated = current.buildUpon()
            .setMediaMetadata(buildMetadata(metadata))
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
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
            })
            .build()

    private fun stationMediaItem(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.streamUrl)
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
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val ROOT_ID = "root"
        const val EXTRA_LENGTH_MS = "com.terraeclectic.deathfm.LENGTH_MS"
        const val EXTRA_ELAPSED_AT_FETCH_MS = "com.terraeclectic.deathfm.ELAPSED_AT_FETCH_MS"
        const val EXTRA_FETCHED_AT_DEVICE_MS = "com.terraeclectic.deathfm.FETCHED_AT_DEVICE_MS"
    }
}
