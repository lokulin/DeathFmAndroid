package com.terraeclectic.deathfm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.terraeclectic.deathfm.settings.SettingsStore
import com.terraeclectic.deathfm.ui.theme.DeathFmTheme

/**
 * Last.fm connection screen - the Android equivalent of DeathFmTray's
 * SettingsForm.cs, minus Discord (no viable RPC surface on mobile - see
 * README discussion). The app's own Last.fm API key/secret are baked in
 * (see LastFmCredentials) rather than entered here, so this screen only
 * needs to drive the per-user Connect/Disconnect flow.
 *
 * The Connect flow mirrors Last.fm's real desktop-app auth steps rather than
 * faking it: [LastFmConnectionState.AwaitingApproval] means a browser tab is
 * open for the user to approve the app in, and this screen just waits for
 * them to come back and confirm - `auth.getSession` (the network step that
 * actually needs a LastFmClient + coroutine scope) is wired up by whoever
 * hosts this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    connectionState: LastFmConnectionState,
    onConnectClicked: () -> Unit,
    onApprovedClicked: () -> Unit,
    onCancelConnect: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Last.fm scrobbling")

            when (connectionState) {
                is LastFmConnectionState.Disconnected -> {
                    Button(onClick = onConnectClicked) { Text("Connect…") }
                }

                is LastFmConnectionState.Failed -> {
                    Text(
                        text = connectionState.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onConnectClicked) { Text("Try again") }
                }

                is LastFmConnectionState.AwaitingApproval -> {
                    Text("Approve access to your Last.fm account in the browser tab that just opened, then come back and tap below.")
                    Button(onClick = onApprovedClicked) { Text("I've approved it") }
                    OutlinedButton(onClick = onCancelConnect) { Text("Cancel") }
                }

                is LastFmConnectionState.Connected -> {
                    Text("Connected to Last.fm.")
                    Button(onClick = onDisconnectClicked) { Text("Disconnect") }
                }
            }
        }
    }
}

@Preview(name = "Disconnected")
@Composable
private fun SettingsScreenDisconnectedPreview() {
    DeathFmTheme {
        SettingsScreen(
            settings = SettingsStore(LocalContext.current),
            connectionState = LastFmConnectionState.Disconnected,
            onConnectClicked = {},
            onApprovedClicked = {},
            onCancelConnect = {},
            onDisconnectClicked = {},
            onBack = {},
        )
    }
}

@Preview(name = "Awaiting approval")
@Composable
private fun SettingsScreenAwaitingApprovalPreview() {
    DeathFmTheme {
        SettingsScreen(
            settings = SettingsStore(LocalContext.current),
            connectionState = LastFmConnectionState.AwaitingApproval,
            onConnectClicked = {},
            onApprovedClicked = {},
            onCancelConnect = {},
            onDisconnectClicked = {},
            onBack = {},
        )
    }
}

@Preview(name = "Connected")
@Composable
private fun SettingsScreenConnectedPreview() {
    DeathFmTheme {
        SettingsScreen(
            settings = SettingsStore(LocalContext.current),
            connectionState = LastFmConnectionState.Connected,
            onConnectClicked = {},
            onApprovedClicked = {},
            onCancelConnect = {},
            onDisconnectClicked = {},
            onBack = {},
        )
    }
}

@Preview(name = "Failed")
@Composable
private fun SettingsScreenFailedPreview() {
    DeathFmTheme {
        SettingsScreen(
            settings = SettingsStore(LocalContext.current),
            connectionState = LastFmConnectionState.Failed("Couldn't connect to Last.fm."),
            onConnectClicked = {},
            onApprovedClicked = {},
            onCancelConnect = {},
            onDisconnectClicked = {},
            onBack = {},
        )
    }
}
