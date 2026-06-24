package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.CaptainsLogChannel
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.TARGET_DEVICE_NAME

@Composable
fun MainDashboardScreen(
    connected: Boolean,
    captainsLog: CaptainsLogChannel,
    debugChannel: DebugChannel,
    actions: PttUiActions,
    onConnectionClick: () -> Unit,
    onDebugChannelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "SUBSPACE",
            subtitle = "Field operations console",
        )

        ConnectionIndicator(
            connected = connected,
            onClick = onConnectionClick,
        )

        ChannelPanel(captainsLog, debugChannel, actions, onDebugChannelClick)
    }
}

@Composable
private fun ConnectionIndicator(
    connected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val label = if (connected) "CONNECTED" else "NOT CONNECTED"
    val guidance = if (connected) "Tap for field monitor" else "Tap for link setup"

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "DEVICE LINK",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = TARGET_DEVICE_NAME,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                StatusPill(label = label, accent = accent)
            }

            Text(
                text = label,
                style = MaterialTheme.typography.displaySmall,
                color = accent,
            )
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelPanel(
    captainsLog: CaptainsLogChannel,
    debugChannel: DebugChannel,
    actions: PttUiActions,
    onDebugChannelClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "CHANNELS",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        CaptainsLogCard(captainsLog, actions)
        DebugChannelCard(debugChannel, onDebugChannelClick)

        previewChannels.forEach { channel ->
            ChannelCard(channel)
        }
    }
}

@Composable
private fun CaptainsLogCard(
    channel: CaptainsLogChannel,
    actions: PttUiActions,
) {
    val configured = !channel.baseDirectory.isNullOrBlank()
    val active = channel.enabled
    val accent = when {
        active -> MaterialTheme.colorScheme.primary
        configured -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("CH-01 / LOCAL LOG", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                StatusPill(
                    label = when {
                        active -> "ACTIVE"
                        configured -> "READY"
                        else -> "CONFIGURE"
                    },
                    accent = accent,
                )
            }

            Text(
                text = channel.baseDirectory ?: "Select a base directory before activation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Save voice", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = channel.saveVoice,
                    onCheckedChange = { enabled ->
                        if (enabled || channel.saveText) actions.setCaptainsLogSaveVoice(enabled)
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Save in log file", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = channel.saveText,
                    onCheckedChange = { enabled ->
                        if (enabled || channel.saveVoice) actions.setCaptainsLogSaveText(enabled)
                    },
                )
            }

            OutlinedButton(onClick = actions::requestManageExternalStorage, modifier = Modifier.fillMaxWidth()) {
                Text("Grant all-files access")
            }
            OutlinedButton(onClick = actions::pickCaptainsLogDirectory, modifier = Modifier.fillMaxWidth()) {
                Text(if (configured) "Change directory" else "Select directory")
            }
            Button(
                onClick = { actions.setCaptainsLogEnabled(!channel.enabled) },
                modifier = Modifier.fillMaxWidth(),
                enabled = configured,
            ) {
                Text(if (channel.enabled) "Deactivate Captain's Log" else "Activate Captain's Log")
            }
        }
    }
}

@Composable
private fun DebugChannelCard(
    channel: DebugChannel,
    onClick: () -> Unit,
) {
    val active = channel.enabled
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("CH-03 / TEST", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                StatusPill(
                    label = if (active) "ACTIVE" else "READY",
                    accent = accent,
                )
            }

            Text(
                text = "Mode: ${channel.mode.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelCard(channel: MockChannel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(label = "MOCK", accent = MaterialTheme.colorScheme.outline)
            }
            Text(
                text = channel.route,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = channel.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Preview only. No audio, network, command, or storage action is wired.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class MockChannel(
    val name: String,
    val route: String,
    val description: String,
)

private val previewChannels = listOf(
    MockChannel(
        name = "Command Uplink",
        route = "CH-02 / REMOTE",
        description = "Placeholder for a future network-backed channel.",
    )
)
