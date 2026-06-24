package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.CaptainsLogChannel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptainsLogConfigScreen(
    channel: CaptainsLogChannel,
    actions: PttUiActions,
    onBack: () -> Unit,
) {
    val configured = !channel.baseDirectory.isNullOrBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Storage Directory",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = channel.baseDirectory ?: "Select a base directory before activation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = actions::requestManageExternalStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant all-files access")
                }
                OutlinedButton(onClick = actions::pickCaptainsLogDirectory, modifier = Modifier.fillMaxWidth()) {
                    Text(if (configured) "Change directory" else "Select directory")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    }
}
