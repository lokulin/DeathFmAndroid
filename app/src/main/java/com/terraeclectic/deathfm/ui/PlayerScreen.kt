package com.terraeclectic.deathfm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.terraeclectic.deathfm.R
import com.terraeclectic.deathfm.playback.Station
import com.terraeclectic.deathfm.playback.Stations
import com.terraeclectic.deathfm.ui.theme.DeathFmTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Player UI, laid out differently depending on orientation: portrait gets
 * full-width square artwork up top (with a faded reflection bleeding into
 * the metadata area below it, iTunes Cover Flow-style) and everything else
 * stacked underneath; landscape (rotation is no longer locked - a tablet in
 * landscape looked odd stretched into the portrait layout) gets a side by
 * side split, artwork on the left and now-playing details on the right,
 * since there's usually not enough height in landscape for a full square
 * plus a stack of text and controls below it. Settings is a small,
 * deliberately quiet icon in the top-right corner in both, rather than
 * competing with the transport control for visual weight.
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
    onOpenQueuePlayed: () -> Unit,
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                LandscapePlayerLayout(
                    trackTitle = trackTitle,
                    trackArtist = trackArtist,
                    trackAlbum = trackAlbum,
                    coverUrl = coverUrl,
                    trackLengthMs = trackLengthMs,
                    trackElapsedAtFetchMs = trackElapsedAtFetchMs,
                    trackFetchedAtDeviceMs = trackFetchedAtDeviceMs,
                    isPlaying = isPlaying,
                    onPlayPause = onPlayPause,
                )
            } else {
                PortraitPlayerLayout(
                    trackTitle = trackTitle,
                    trackArtist = trackArtist,
                    trackAlbum = trackAlbum,
                    coverUrl = coverUrl,
                    trackLengthMs = trackLengthMs,
                    trackElapsedAtFetchMs = trackElapsedAtFetchMs,
                    trackFetchedAtDeviceMs = trackFetchedAtDeviceMs,
                    isPlaying = isPlaying,
                    onPlayPause = onPlayPause,
                )
            }

            // Queue/Played: same quiet-icon treatment as Settings, mirrored
            // to the top-left corner. Phone/tablet only, deliberately not
            // surfaced to Android Auto - see QueuePlayedScreen's doc.
            IconButton(
                onClick = onOpenQueuePlayed,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue & Played",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }

            // Settings: a small, quiet icon rather than a same-sized button
            // next to Play/Stop - a large touch target doesn't require an
            // equally large visible control, and this one isn't the primary
            // action on the screen. Shared between both orientations.
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

@Composable
private fun PortraitPlayerLayout(
    trackTitle: String,
    trackArtist: String,
    trackAlbum: String,
    coverUrl: String?,
    trackLengthMs: Long,
    trackElapsedAtFetchMs: Long,
    trackFetchedAtDeviceMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
) {
    var artworkBottomPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
            // Reflection: a full, un-squashed copy of the artwork, flipped
            // and positioned to start exactly where the real artwork's own
            // bottom edge actually is on screen (artworkBottomPx, captured
            // below via onGloballyPositioned - not just the artwork's own
            // height, which would miss the statusBarsPadding pushing it
            // down and made the reflection start too early by that much).
            // Drawn as the first child here so everything else (the Column
            // with the real artwork and metadata) paints on top of it, same
            // z-order as classic Cover Flow.
            //
            // Deliberately NOT sized/clipped down to a short "reflection
            // height" box - nesting a full-size square inside anything with
            // its own fixed, shorter height kept getting squashed by that
            // ancestor's constraint (tried aspectRatio, then
            // BoxWithConstraints, then requiredHeight - all still got
            // clamped somewhere in the chain). Positioning it here, as a
            // sibling of the Column rather than nested inside the
            // height-constrained metadata box, means the only ancestor
            // constraint it ever sees is "the whole screen," so
            // aspectRatio(1f) sizes it correctly without a fight. The
            // "short reflection" look is achieved entirely by the gradient
            // below fading to the background color within the first ~45%
            // of its height - the rest of the (identical, just unseen)
            // copy is harmless, since it's already fully background-colored
            // by then regardless of what's technically drawn there.
            if (artworkBottomPx > 0) {
                val artworkBottomDp = with(density) { artworkBottomPx.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .offset(y = artworkBottomDp),
                ) {
                    AsyncImage(
                        model = coverUrl,
                        fallback = painterResource(R.drawable.album_art_placeholder),
                        error = painterResource(R.drawable.album_art_placeholder),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleY = -1f }
                            .alpha(0.35f),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    // Front-loaded into the first ~45% of the
                                    // full height, rather than spread evenly
                                    // across it - reaches solid background
                                    // color well before the bottom, giving
                                    // the visual impression of a short
                                    // reflection without fading so fast it's
                                    // barely visible at all.
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        0.45f to MaterialTheme.colorScheme.background,
                                        1f to MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                }
            }

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
                        .aspectRatio(1f)
                        // Captures this Image's actual on-screen BOTTOM edge
                        // position (not just its own height!) - the
                        // reflection above is offset from y=0 of the shared
                        // parent Box, so it needs the artwork's position
                        // INCLUDING the statusBarsPadding above it, not just
                        // the square's own height, or it starts too early by
                        // exactly the status bar's height (duplicating a
                        // sliver of the real artwork into the reflection).
                        .onGloballyPositioned { coordinates ->
                            artworkBottomPx = (coordinates.positionInRoot().y + coordinates.size.height).roundToInt()
                        },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
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
    }
}

/**
 * Side-by-side split for landscape: artwork on the left sized to 80% of the
 * available height (rather than the full height, or a full-width square
 * like portrait) so there's still room for a short reflection strip below
 * it, same iTunes Cover Flow-style effect as portrait just scaled down to
 * fit - now-playing details and controls in a column on the right.
 */
@Composable
private fun LandscapePlayerLayout(
    trackTitle: String,
    trackArtist: String,
    trackAlbum: String,
    coverUrl: String?,
    trackLengthMs: Long,
    trackElapsedAtFetchMs: Long,
    trackFetchedAtDeviceMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
) {
    var artworkBottomPx by remember { mutableIntStateOf(0) }
    var artworkSizePx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Same width as the status bar's own height - purely for symmetry, so
    // the artwork/reflection aren't wedged flush against the left edge in
    // landscape the way they visually aren't against the top edge either.
    val edgePaddingDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }

    // No statusBarsPadding/navigationBarsPadding on this outer Box itself -
    // that's applied to the Row below instead. positionInRoot() (used to
    // compute artworkBottomPx) reports an absolute screen coordinate, which
    // already accounts for the Row's own inset padding shifting it down -
    // putting the same padding on THIS Box too would shift its origin down
    // an extra time, so offset(y = artworkBottomDp) below would double-count
    // the status bar height as a gap above the reflection.
    Box(modifier = Modifier.fillMaxSize()) {
        // Reflection - same technique as portrait's (see its comments for
        // why requiredHeight/BoxWithConstraints/aspectRatio alone all failed
        // here): a full, un-squashed copy of the artwork sized to its own
        // measured dimensions, flipped, positioned via offset to start
        // exactly at the artwork's real bottom edge, faded to the
        // background color well before reaching the bottom of the
        // available ~20% of height left for it.
        if (artworkBottomPx > 0 && artworkSizePx > 0) {
            val artworkBottomDp = with(density) { artworkBottomPx.toDp() }
            val artworkSizeDp = with(density) { artworkSizePx.toDp() }
            Box(
                modifier = Modifier
                    .size(artworkSizeDp)
                    .offset(x = edgePaddingDp, y = artworkBottomDp),
            ) {
                AsyncImage(
                    model = coverUrl,
                    fallback = painterResource(R.drawable.album_art_placeholder),
                    error = painterResource(R.drawable.album_art_placeholder),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleY = -1f }
                        .alpha(0.35f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.45f to MaterialTheme.colorScheme.background,
                                    1f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = edgePaddingDp),
        ) {
            AsyncImage(
                model = coverUrl,
                fallback = painterResource(R.drawable.album_art_placeholder),
                error = painterResource(R.drawable.album_art_placeholder),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { coordinates ->
                        artworkBottomPx = (coordinates.positionInRoot().y + coordinates.size.height).roundToInt()
                        artworkSizePx = coordinates.size.height
                    },
            )

            Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp),
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
            Spacer(modifier = Modifier.height(24.dp))
            PlaybackProgress(
                lengthMs = trackLengthMs,
                elapsedAtFetchMs = trackElapsedAtFetchMs,
                fetchedAtDeviceMs = trackFetchedAtDeviceMs,
                isPlaying = isPlaying,
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularButton(
                icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                onClick = onPlayPause,
                containerColor = MaterialTheme.colorScheme.primary,
                iconTint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
    }
}

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

@Preview(name = "Portrait", widthDp = 360, heightDp = 720)
@Composable
private fun PlayerScreenPortraitPreview() {
    DeathFmTheme {
        PlayerScreen(
            station = Stations.DEATH_FM,
            trackTitle = "Sample Track Title",
            trackArtist = "Sample Artist",
            trackAlbum = "Sample Album",
            coverUrl = null,
            trackLengthMs = 240_000L,
            trackElapsedAtFetchMs = 90_000L,
            trackFetchedAtDeviceMs = System.currentTimeMillis(),
            isPlaying = true,
            onPlayPause = {},
            onOpenSettings = {},
            onOpenQueuePlayed = {},
        )
    }
}

@Preview(name = "Landscape", widthDp = 720, heightDp = 360)
@Composable
private fun PlayerScreenLandscapePreview() {
    DeathFmTheme {
        PlayerScreen(
            station = Stations.DEATH_FM,
            trackTitle = "Sample Track Title",
            trackArtist = "Sample Artist",
            trackAlbum = "Sample Album",
            coverUrl = null,
            trackLengthMs = 240_000L,
            trackElapsedAtFetchMs = 90_000L,
            trackFetchedAtDeviceMs = System.currentTimeMillis(),
            isPlaying = true,
            onPlayPause = {},
            onOpenSettings = {},
            onOpenQueuePlayed = {},
        )
    }
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
