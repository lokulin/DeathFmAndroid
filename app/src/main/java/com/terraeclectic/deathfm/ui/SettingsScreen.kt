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
 * README discussion). "Connect" opens Last.fm's browser auth page; finishing
 * the flow (auth.getSession) is wired up by whoever hosts this screen, since
 * it needs a LastFmClient + coroutine scope, not just UI state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    isConnected: Boolean,
    onConnectClicked: (apiKey: String, apiSecret: String) -> Unit,
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
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    settings.lastFmApiKey = it
                },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiSecret,
                onValueChange = {
                    apiSecret = it
                    settings.lastFmApiSecret = it
                },
                label = { Text("Shared secret") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isConnected) {
                Button(onClick = onDisconnectClicked) { Text("Disconnect") }
            } else {
                Button(
                    onClick = { onConnectClicked(apiKey, apiSecret) },
                    enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                ) { Text("Connect…") }
            }
        }
    }
}
