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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terraeclectic.deathfm.settings.SettingsStore

/**
 * Last.fm credentials editor - the Android equivalent of DeathFmTray's
 * SettingsForm.cs, minus Discord (no viable RPC surface on mobile - see
 * README discussion).
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
    onConnectClicked: (apiKey: String, apiSecret: String) -> Unit,
    onApprovedClicked: () -> Unit,
    onCancelConnect: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onBack: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(settings.lastFmApiKey.orEmpty()) }
    var apiSecret by remember { mutableStateOf(settings.lastFmApiSecret.orEmpty()) }

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

            val fieldsEditable = connectionState is LastFmConnectionState.Disconnected ||
                connectionState is LastFmConnectionState.Failed
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    settings.lastFmApiKey = it
                },
                label = { Text("API key") },
                enabled = fieldsEditable,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiSecret,
                onValueChange = {
                    apiSecret = it
                    settings.lastFmApiSecret = it
                },
                label = { Text("Shared secret") },
                enabled = fieldsEditable,
                modifier = Modifier.fillMaxWidth(),
            )

            when (connectionState) {
                is LastFmConnectionState.Disconnected -> {
                    Button(
                        onClick = { onConnectClicked(apiKey, apiSecret) },
                        enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    ) { Text("Connect…") }
                }

                is LastFmConnectionState.Failed -> {
                    Text(
                        text = connectionState.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { onConnectClicked(apiKey, apiSecret) },
                        enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    ) { Text("Try again") }
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
