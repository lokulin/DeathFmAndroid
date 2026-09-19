package com.terraeclectic.deathfm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.terraeclectic.deathfm.R
import com.terraeclectic.deathfm.playback.Station
import kotlinx.coroutines.delay

/**
 * Single-screen player UI: full-width square artwork up top (with a faded
 * reflection bleeding into the metadata area below it, iTunes Cover
 * Flow-style), track details + progress centered underneath, one big
 * transport control at the bottom. Settings is a small, deliberately
 * quiet icon in the top-right corner rather than competing with the
 * transport control for visual weight.
 */
@Composable
fun PlayerScreen(
    station: Station,
    trackTitle: String,
    trackArtist: String,
    trackAlbum: String,
    coverUrl: String?,
    trackLengthMs: Long,
    trackElapsedAtFetchMs: Long,
    trackFetchedAtDeviceMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Surface (not just a Column with a background modifier) is what actually
    // propagates the theme's content color to children - without it, Text
    // composables with no explicit color fall back to a near-black default
    // regardless of colorScheme, which is why title/artist were unreadable
    // against the dark background before this.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = coverUrl,
                    // Used both when there's no coverUrl yet (model null ->
                    // fallback) and if a real art URL ever fails to load
                    // (error) - same asset as the app icon, so "no art
                    // loaded" still looks intentional rather than broken.
                    fallback = painterResource(R.drawable.album_art_placeholder),
                    error = painterResource(R.drawable.album_art_placeholder),
                    contentDescription = "Album art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        // Pushes the artwork below the status bar instead of
                        // drawing underneath it - targetSdk 35 enforces
                        // edge-to-edge by default, so without this the image
                        // extends all the way to the physical top of the screen.
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Reflection: the same artwork, flipped, dimmed, and faded
                    // to the screen's background color - drawn as this Box's
                    // first (bottommost) child so the metadata Column below
                    // renders on top of it, same as classic Cover Flow.
                    //
                    // Sized as a full square matching the real artwork above
                    // it exactly, then clipped down to just the visible
                    // reflection strip - rather than being independently
                    // cropped to ReflectionHeight directly (Coil would then
                    // pick a centered slice of the *source*, not the real
                    // artwork's own bottom edge, which looked like a
                    // reflection of the wrong, unrelated part of the cover).
                    //
                    // requiredHeight (not height/aspectRatio) is deliberate:
                    // a plain height()/aspectRatio() modifier gets clamped to
                    // the outer Box's own height(ReflectionHeight) constraint
                    // propagating down, which squished this back into a
                    // short, re-cropped rectangle instead of a true square -
                    // requiredHeight overrides that clamp so the image is
                    // genuinely full-size before clipToBounds() crops it.
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ReflectionHeight)
                            .align(Alignment.TopCenter)
                            .clipToBounds(),
                    ) {
                        AsyncImage(
                            model = coverUrl,
                            fallback = painterResource(R.drawable.album_art_placeholder),
                            error = painterResource(R.drawable.album_art_placeholder),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .requiredHeight(maxWidth)
                                .graphicsLayer { scaleY = -1f }
                                .alpha(0.25f),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ReflectionHeight)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                ),
                            ),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = trackTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        if (trackAlbum.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trackAlbum,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = trackArtist,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        PlaybackProgress(
                            lengthMs = trackLengthMs,
                            elapsedAtFetchMs = trackElapsedAtFetchMs,
                            fetchedAtDeviceMs = trackFetchedAtDeviceMs,
                            isPlaying = isPlaying,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp, top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularButton(
                        icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
                        onClick = onPlayPause,
                        containerColor = MaterialTheme.colorScheme.primary,
                        iconTint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // Settings: a small, quiet icon rather than a same-sized button
            // next to Play/Stop - a large touch target doesn't require an
            // equally large visible control, and this one isn't the primary
            // action on the screen.
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private val ReflectionHeight = 140.dp

/** Thin progress bar plus "elapsed / total" caption, ticking once a second - only meaningful while actually playing a real track. */
@Composable
private fun PlaybackProgress(lengthMs: Long, elapsedAtFetchMs: Long, fetchedAtDeviceMs: Long, isPlaying: Boolean) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val visible = isPlaying && fetchedAtDeviceMs > 0L && lengthMs > 0L
    // The caption isn't capped at lengthMs: death.fm's "Length" is catalog
    // metadata, not a hard boundary the live stream actually cuts at - a
    // track can run past it, and capping made the display look frozen once
    // that happened. The progress bar fraction below IS capped at 1f, since
    // a bar that overflows its own track doesn't mean anything visually.
    //
    // Deliberately NOT "nowMs - some start timestamp from the station" -
    // the station's own PlayStart/SystemTime fields turned out to be a flat
    // 4 hours off from real UTC (see NowPlayingRepository's doc), so elapsed
    // is instead anchored to this device's own correct clock at fetch time.
    val elapsedMs = (elapsedAtFetchMs + (nowMs - fetchedAtDeviceMs)).coerceAtLeast(0L)
    val progressFraction = if (lengthMs > 0L) (elapsedMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (visible) 0.15f else 0f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (visible) progressFraction else 0f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Always rendered (never omitted outright) so this row keeps
        // reserving its line height - otherwise title/album/artist above
        // visibly jump to re-center in the space this text used to take up,
        // every time playback starts/stops.
        Text(
            text = if (visible) "${formatDuration(elapsedMs)} / ${formatDuration(lengthMs)}" else " ",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (visible) 0.75f else 0f),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun CircularButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
