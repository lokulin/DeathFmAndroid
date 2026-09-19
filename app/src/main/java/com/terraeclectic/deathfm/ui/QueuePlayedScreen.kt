package com.terraeclectic.deathfm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.terraeclectic.deathfm.R
import com.terraeclectic.deathfm.queueplayed.QueueEntry
import com.terraeclectic.deathfm.ui.theme.DeathFmTheme

/**
 * Read-only Queue/Played browser - phone/tablet only, deliberately not
 * surfaced to Android Auto (see [com.terraeclectic.deathfm.playback.PlaybackService]'s
 * doc and [QueueEntry]'s doc for why: it's a fixed DJ rotation, not a
 * user-controllable playlist, and Auto's browse tree is built around
 * "pick something to play" rather than static browsing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuePlayedScreen(
    isLoading: Boolean,
    errorMessage: String?,
    queue: List<QueueEntry>,
    played: List<QueueEntry>,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue & Played") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Queue") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Played") })
            }

            val entries = if (selectedTab == 0) queue else played
            when {
                isLoading && entries.isEmpty() -> CenteredMessage { CircularProgressIndicator() }
                errorMessage != null && entries.isEmpty() -> CenteredMessage {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                entries.isEmpty() -> CenteredMessage {
                    Text(
                        text = "Nothing to show yet.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.rank }) { entry -> QueueEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(name = "Populated")
@Composable
private fun QueuePlayedScreenPopulatedPreview() {
    val sampleEntries = listOf(
        QueueEntry(rank = 1, thumbnailUrl = null, artist = "Sample Artist One", albumOrTrack = "Sample Track One"),
        QueueEntry(rank = 2, thumbnailUrl = null, artist = "Sample Artist Two", albumOrTrack = "Sample Track Two"),
        QueueEntry(rank = 3, thumbnailUrl = null, artist = "Sample Artist Three", albumOrTrack = "Sample Track Three"),
    )
    DeathFmTheme {
        QueuePlayedScreen(
            isLoading = false,
            errorMessage = null,
            queue = sampleEntries,
            played = sampleEntries,
            onRefresh = {},
            onBack = {},
        )
    }
}

@Preview(name = "Empty")
@Composable
private fun QueuePlayedScreenEmptyPreview() {
    DeathFmTheme {
        QueuePlayedScreen(
            isLoading = false,
            errorMessage = null,
            queue = emptyList(),
            played = emptyList(),
            onRefresh = {},
            onBack = {},
        )
    }
}

@Preview(name = "Error")
@Composable
private fun QueuePlayedScreenErrorPreview() {
    DeathFmTheme {
        QueuePlayedScreen(
            isLoading = false,
            errorMessage = "Couldn't load the queue.",
            queue = emptyList(),
            played = emptyList(),
            onRefresh = {},
            onBack = {},
        )
    }
}

@Composable
private fun QueueEntryRow(entry: QueueEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.thumbnailUrl,
            fallback = painterResource(R.drawable.album_art_placeholder),
            error = painterResource(R.drawable.album_art_placeholder),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(text = entry.artist, style = MaterialTheme.typography.bodyLarge)
            if (entry.albumOrTrack.isNotBlank()) {
                Text(
                    text = entry.albumOrTrack,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
    }
}
