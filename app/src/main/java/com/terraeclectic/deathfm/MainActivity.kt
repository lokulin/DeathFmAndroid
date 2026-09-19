package com.terraeclectic.deathfm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.terraeclectic.deathfm.lastfm.LastFmClient
import com.terraeclectic.deathfm.playback.PlaybackService
import com.terraeclectic.deathfm.playback.Stations
import com.terraeclectic.deathfm.ui.PlayerScreen
import com.terraeclectic.deathfm.ui.SettingsScreen
import com.terraeclectic.deathfm.ui.theme.DeathFmTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hosts a [MediaController] connected to [PlaybackService] and two Compose
 * screens (player / settings) flipped between with plain local state - this
 * is a skeleton, not attempting a nav-graph for two screens.
 */
class MainActivity : ComponentActivity() {

    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DeathFmTheme {
                var showSettings by remember { mutableStateOf(false) }
                var isPlaying by remember { mutableStateOf(false) }
                var trackTitle by remember { mutableStateOf(Stations.DEATH_FM.displayName) }
                var trackArtist by remember { mutableStateOf("") }
                var isConnected by remember { mutableStateOf((application as DeathFmApp).settings.isLastFmConnected) }

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
                        isPlaying = mediaController.isPlaying
                        mediaController.addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(playing: Boolean) {
                                isPlaying = playing
                            }

                            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                                trackTitle = mediaMetadata.title?.toString() ?: Stations.DEATH_FM.displayName
                                trackArtist = mediaMetadata.artist?.toString() ?: ""
                            }
                        })
                    }, MoreExecutors.directExecutor())

                    onDispose {
                        controller?.release()
                        controller = null
                    }
                }

                if (showSettings) {
                    val settings = (application as DeathFmApp).settings
                    SettingsScreen(
                        settings = settings,
                        isConnected = isConnected,
                        onConnectClicked = { apiKey, apiSecret ->
                            connectLastFm(apiKey, apiSecret) { connected -> isConnected = connected }
                        },
                        onDisconnectClicked = {
                            settings.clearLastFmSession()
                            isConnected = false
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    PlayerScreen(
                        station = Stations.DEATH_FM,
                        trackTitle = trackTitle,
                        trackArtist = trackArtist,
                        isPlaying = isPlaying,
                        onPlayPause = {
                            controller?.let { c -> if (c.isPlaying) c.stop() else c.play() }
                        },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    /**
     * Last.fm's desktop-app auth flow: get a token, send the user to approve
     * it in a browser, then poll auth.getSession once they confirm - same
     * shape as DeathFmTray's Settings "Connect..." button, just without a
     * WinForms dialog to drive it.
     */
    private fun connectLastFm(apiKey: String, apiSecret: String, onResult: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = LastFmClient(apiKey, apiSecret)
                val token = client.getToken()
                val authUrl = client.buildAuthUrl(token)
                launch(Dispatchers.Main) {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)))
                }
                // TODO: this fires immediately after opening the browser, before
                // the user has actually approved anything - a skeleton stand-in
                // for a real "I've approved it" confirmation step/button.
                kotlinx.coroutines.delay(5000)
                val sessionKey = client.getSession(token)
                (application as DeathFmApp).settings.lastFmSessionKey = sessionKey
                launch(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onResult(false) }
            }
        }
    }
}
