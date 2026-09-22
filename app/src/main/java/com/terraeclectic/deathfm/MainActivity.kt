package com.terraeclectic.deathfm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.terraeclectic.deathfm.lastfm.LastFmClient
import com.terraeclectic.deathfm.playback.PlaybackService
import com.terraeclectic.deathfm.playback.PlaybackService.Companion.EXTRA_ASIN
import com.terraeclectic.deathfm.playback.PlaybackService.Companion.EXTRA_ELAPSED_AT_FETCH_MS
import com.terraeclectic.deathfm.playback.PlaybackService.Companion.EXTRA_FETCHED_AT_DEVICE_MS
import com.terraeclectic.deathfm.playback.PlaybackService.Companion.EXTRA_LENGTH_MS
import com.terraeclectic.deathfm.playback.Stations
import com.terraeclectic.deathfm.queueplayed.QueueEntry
import com.terraeclectic.deathfm.queueplayed.QueuePlayedRepository
import com.terraeclectic.deathfm.ui.LastFmConnectionState
import com.terraeclectic.deathfm.ui.PlayerScreen
import com.terraeclectic.deathfm.ui.QueuePlayedScreen
import com.terraeclectic.deathfm.ui.SettingsScreen
import com.terraeclectic.deathfm.ui.theme.DeathFmTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class AppScreen { PLAYER, SETTINGS, QUEUE_PLAYED }

/**
 * Hosts a [MediaController] connected to [PlaybackService] and three Compose
 * screens (player / settings / queue+played) flipped between with plain
 * local state - this is a skeleton, not attempting a nav-graph for three
 * screens.
 *
 * FragmentActivity rather than plain ComponentActivity - PlayerScreen's Cast
 * button (MediaRouteButton) shows its device picker as a DialogFragment and
 * throws ("must be a subclass of FragmentActivity") without this, confirmed
 * by an actual crash on a real device tap.
 */
class MainActivity : FragmentActivity() {

    private var controller: MediaController? = null
    private val queuePlayedRepository = QueuePlayedRepository(Stations.DEATH_FM)

    // No hook to intercept the Cast icon's own click (CastButtonFactory wires
    // it straight to opening the device picker), so this is requested
    // up front on launch instead of lazily on tap - confirmed live that
    // without NEARBY_WIFI_DEVICES granted, the picker opens fine but silently
    // finds nothing, indistinguishable from "no Chromecast on the network"
    // unless you go looking for it in adb's appops dump.
    private val requestNearbyWifiDevices = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNearbyWifiDevices.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        setContent {
            DeathFmTheme {
                var screen by remember { mutableStateOf(AppScreen.PLAYER) }
                var isPlaying by remember { mutableStateOf(false) }
                var trackTitle by remember { mutableStateOf(Stations.DEATH_FM.displayName) }
                var trackArtist by remember { mutableStateOf("") }
                var trackAlbum by remember { mutableStateOf("") }
                var coverUrl by remember { mutableStateOf<String?>(null) }
                var trackLengthMs by remember { mutableStateOf(0L) }
                var trackElapsedAtFetchMs by remember { mutableStateOf(0L) }
                var trackFetchedAtDeviceMs by remember { mutableStateOf(0L) }
                var trackAsin by remember { mutableStateOf<String?>(null) }
                var queueEntries by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
                var playedEntries by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }
                var queuePlayedLoading by remember { mutableStateOf(false) }
                var queuePlayedError by remember { mutableStateOf<String?>(null) }
                var lastFmState by remember {
                    mutableStateOf<LastFmConnectionState>(
                        if ((application as DeathFmApp).settings.isLastFmConnected) {
                            LastFmConnectionState.Connected
                        } else {
                            LastFmConnectionState.Disconnected
                        },
                    )
                }
                // Set once getToken() succeeds, needed again when the user
                // confirms they approved it in the browser - Last.fm's
                // auth.getSession call takes the same token, not a fresh one.
                var pendingAuthToken by remember { mutableStateOf<String?>(null) }

                DisposableEffect(Unit) {
                    val sessionToken = SessionToken(this@MainActivity, android.content.ComponentName(this@MainActivity, PlaybackService::class.java))
                    val future = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()
                    future.addListener({
                        val mediaController = future.get()
                        controller = mediaController
                        if (mediaController.mediaItemCount == 0) {
                            mediaController.setMediaItem(MediaItem.fromUri(Stations.DEATH_FM.streamUrl).buildUpon().setMediaId(Stations.DEATH_FM.id).build())
                            mediaController.prepare()
                        }
                        fun syncFromPlayer(player: Player) {
                            isPlaying = player.isPlaying
                            val mediaMetadata = player.mediaMetadata
                            trackTitle = mediaMetadata.title?.toString() ?: Stations.DEATH_FM.displayName
                            trackArtist = mediaMetadata.artist?.toString() ?: ""
                            trackAlbum = mediaMetadata.albumTitle?.toString() ?: ""
                            coverUrl = mediaMetadata.artworkUri?.toString()
                            trackLengthMs = mediaMetadata.extras?.getLong(EXTRA_LENGTH_MS) ?: 0L
                            trackElapsedAtFetchMs = mediaMetadata.extras?.getLong(EXTRA_ELAPSED_AT_FETCH_MS) ?: 0L
                            trackFetchedAtDeviceMs = mediaMetadata.extras?.getLong(EXTRA_FETCHED_AT_DEVICE_MS) ?: 0L
                            trackAsin = mediaMetadata.extras?.getString(EXTRA_ASIN)
                        }
                        syncFromPlayer(mediaController)
                        Log.d(TAG, "Controller connected, registering listener")
                        mediaController.addListener(object : Player.Listener {
                            // The individual onIsPlayingChanged/onMediaMetadataChanged
                            // callbacks proved unreliable on a remote MediaController
                            // (confirmed via Logcat: they fired once on connect, then
                            // never again despite the service's player state actually
                            // changing) - onEvents is guaranteed to fire for every
                            // state-change batch, so just resync everything from the
                            // live player each time instead of trusting a specific
                            // per-property callback to fire.
                            override fun onEvents(player: Player, events: Player.Events) {
                                Log.d(TAG, "onEvents: $events, isPlaying=${player.isPlaying}")
                                syncFromPlayer(player)
                            }
                        })
                        // MediaController must be built AND used entirely on its
                        // owning thread (here, main) - MoreExecutors.directExecutor()
                        // would run this callback on whatever thread the connection
                        // happened to complete on (often a binder thread), silently
                        // breaking listener registration without any error.
                    }, ContextCompat.getMainExecutor(this@MainActivity))

                    onDispose {
                        controller?.release()
                        controller = null
                    }
                }

                when (screen) {
                    AppScreen.SETTINGS -> {
                        val settings = (application as DeathFmApp).settings
                        SettingsScreen(
                            settings = settings,
                            connectionState = lastFmState,
                            onConnectClicked = { apiKey, apiSecret ->
                                startLastFmConnect(
                                    apiKey = apiKey,
                                    apiSecret = apiSecret,
                                    onToken = { token -> pendingAuthToken = token },
                                    onStateChange = { state -> lastFmState = state },
                                )
                            },
                            onApprovedClicked = {
                                val token = pendingAuthToken
                                if (token != null) {
                                    confirmLastFmApproval(
                                        apiKey = settings.lastFmApiKey.orEmpty(),
                                        apiSecret = settings.lastFmApiSecret.orEmpty(),
                                        token = token,
                                        onStateChange = { state -> lastFmState = state },
                                    )
                                }
                            },
                            onCancelConnect = {
                                pendingAuthToken = null
                                lastFmState = LastFmConnectionState.Disconnected
                            },
                            onDisconnectClicked = {
                                settings.clearLastFmSession()
                                lastFmState = LastFmConnectionState.Disconnected
                            },
                            onBack = { screen = AppScreen.PLAYER },
                        )
                    }

                    AppScreen.QUEUE_PLAYED -> {
                        QueuePlayedScreen(
                            isLoading = queuePlayedLoading,
                            errorMessage = queuePlayedError,
                            queue = queueEntries,
                            played = playedEntries,
                            onRefresh = {
                                fetchQueuePlayed(
                                    asin = trackAsin,
                                    onLoadingChange = { loading -> queuePlayedLoading = loading },
                                    onResult = { queue, played -> queueEntries = queue; playedEntries = played },
                                    onError = { message -> queuePlayedError = message },
                                )
                            },
                            onBack = { screen = AppScreen.PLAYER },
                        )
                    }

                    AppScreen.PLAYER -> {
                        PlayerScreen(
                            station = Stations.DEATH_FM,
                            trackTitle = trackTitle,
                            trackArtist = trackArtist,
                            trackAlbum = trackAlbum,
                            coverUrl = coverUrl,
                            trackLengthMs = trackLengthMs,
                            trackElapsedAtFetchMs = trackElapsedAtFetchMs,
                            trackFetchedAtDeviceMs = trackFetchedAtDeviceMs,
                            isPlaying = isPlaying,
                            onPlayPause = {
                                controller?.let { c ->
                                    Log.d(TAG, "onPlayPause tapped, c.isPlaying=${c.isPlaying}")
                                    if (c.isPlaying) c.stop() else c.play()
                                }
                            },
                            onOpenSettings = { screen = AppScreen.SETTINGS },
                            onOpenQueuePlayed = {
                                screen = AppScreen.QUEUE_PLAYED
                                fetchQueuePlayed(
                                    asin = trackAsin,
                                    onLoadingChange = { loading -> queuePlayedLoading = loading },
                                    onResult = { queue, played -> queueEntries = queue; playedEntries = played },
                                    onError = { message -> queuePlayedError = message },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Step 1 of Last.fm's desktop-app auth flow: get a token and send the
     * user to approve it in a browser. Unlike an earlier version of this,
     * it does NOT assume the user has approved anything yet - it just opens
     * the browser and reports [LastFmConnectionState.AwaitingApproval];
     * [confirmLastFmApproval] (step 2) only runs once the user comes back
     * and explicitly confirms it, via the Settings screen's "I've approved
     * it" button.
     */
    private fun startLastFmConnect(
        apiKey: String,
        apiSecret: String,
        onToken: (String) -> Unit,
        onStateChange: (LastFmConnectionState) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = LastFmClient(apiKey, apiSecret)
                val token = client.getToken()
                val authUrl = client.buildAuthUrl(token)
                launch(Dispatchers.Main) {
                    onToken(token)
                    onStateChange(LastFmConnectionState.AwaitingApproval)
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm getToken failed", e)
                launch(Dispatchers.Main) {
                    onStateChange(LastFmConnectionState.Failed("Couldn't reach Last.fm - check your API key/secret and try again."))
                }
            }
        }
    }

    /**
     * Step 2: called once the user taps "I've approved it". If they didn't
     * actually approve it in the browser first, auth.getSession fails with
     * a normal Last.fm API error, which surfaces as
     * [LastFmConnectionState.Failed] - its "Try again" button restarts from
     * step 1 rather than retrying this same token, since Last.fm tokens are
     * single-use/time-limited and a fresh one is safer than assuming this
     * one is still good.
     */
    private fun confirmLastFmApproval(
        apiKey: String,
        apiSecret: String,
        token: String,
        onStateChange: (LastFmConnectionState) -> Unit,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = LastFmClient(apiKey, apiSecret)
                val sessionKey = client.getSession(token)
                (application as DeathFmApp).settings.lastFmSessionKey = sessionKey
                launch(Dispatchers.Main) { onStateChange(LastFmConnectionState.Connected) }
            } catch (e: Exception) {
                Log.w(TAG, "Last.fm getSession failed", e)
                launch(Dispatchers.Main) {
                    onStateChange(LastFmConnectionState.Failed("Last.fm hasn't confirmed the approval yet - make sure you approved access in the browser tab, then try again."))
                }
            }
        }
    }

    /**
     * Fetches the Queue/Played lists for whatever track is currently
     * playing. Deliberately not tied to the playback lifecycle like
     * [com.terraeclectic.deathfm.nowplaying.NowPlayingRepository] - this is
     * supplementary info, only fetched while the Queue/Played screen is
     * actually open (on first opening it, and again on manual refresh).
     */
    private fun fetchQueuePlayed(
        asin: String?,
        onLoadingChange: (Boolean) -> Unit,
        onResult: (queue: List<QueueEntry>, played: List<QueueEntry>) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (asin == null) {
            onError("No track playing yet - try again once something's on.")
            return
        }
        onLoadingChange(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = queuePlayedRepository.fetch(asin)
                launch(Dispatchers.Main) {
                    onLoadingChange(false)
                    onResult(snapshot.queue, snapshot.played)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Queue/Played fetch failed", e)
                launch(Dispatchers.Main) {
                    onLoadingChange(false)
                    onError("Couldn't load queue/history - check your connection and try again.")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
