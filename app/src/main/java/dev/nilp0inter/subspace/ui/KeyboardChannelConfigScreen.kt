package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.KeyboardChannel
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardStatus
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.displayText
import io.sleepwalker.core.keymap.HostProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardChannelConfigScreen(
    channel: KeyboardChannel,
    monitorState: MonitorState,
    actions: PttUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard Channel Config") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Configure Sleepwalker Keyboard",
                style = MaterialTheme.typography.titleMedium,
            )

            // Host Profile Selector
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Host Keymap Profile",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HostProfileDropdown(
                        selected = channel.hostProfile,
                        onSelect = { actions.setKeyboardHostProfile(it) },
                    )
                }
            }

            // Connection Manager
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Sleepwalker Bridge Connection",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Status: ${monitorState.keyboardConnectionState}",
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        val buttonText = when (monitorState.keyboardConnectionState) {
                            KeyboardConnectionState.Disconnected -> "Connect"
                            KeyboardConnectionState.Scanning -> "Connecting..."
                            KeyboardConnectionState.Connecting -> "Connecting..."
                            KeyboardConnectionState.Connected -> "Disconnect"
                        }
                        val isEnabled = monitorState.keyboardConnectionState == KeyboardConnectionState.Disconnected ||
                                monitorState.keyboardConnectionState == KeyboardConnectionState.Connected

                        Button(
                            onClick = {
                                if (monitorState.keyboardConnectionState == KeyboardConnectionState.Connected) {
                                    actions.disconnectKeyboardBridge()
                                } else {
                                    actions.connectKeyboardBridge()
                                }
                            },
                            enabled = isEnabled,
                            colors = if (monitorState.keyboardConnectionState == KeyboardConnectionState.Connected) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Text(buttonText)
                        }
                    }
                }
            }

            // Keyboard Status Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Session Status",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = monitorState.keyboardStatus.displayText(),
                        style = MaterialTheme.typography.headlineSmall,
                    )

                    val lastText = (monitorState.keyboardStatus as? KeyboardStatus.Done)?.text
                    if (lastText != null) {
                        Text(
                            text = "Last Typed: \"$lastText\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostProfileDropdown(
    selected: HostProfile,
    onSelect: (HostProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = HostProfile.values()
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Profile: ${selected.name}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
