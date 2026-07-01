package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.WebhookChannel
import dev.nilp0inter.subspace.model.WebhookHeader
import dev.nilp0inter.subspace.model.WebhookStatus
import dev.nilp0inter.subspace.model.WebhookVerb
import dev.nilp0inter.subspace.model.displayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhookChannelConfigScreen(
    channel: WebhookChannel,
    status: WebhookStatus,
    actions: PttUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, if (channel.isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(status.displayText(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (channel.isReady) "Ready to transcribe and invoke." else "URL and body placeholder are required before PTT dispatch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = channel.url,
                onValueChange = actions::setWebhookUrl,
                label = { Text("Webhook URL") },
                placeholder = { Text("https://example.test/hook") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP Verb", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    WebhookVerb.entries.forEach { verb ->
                        OutlinedButton(
                            onClick = { actions.setWebhookVerb(verb) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(verb.name)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = webhookHeadersText(channel.headers),
                onValueChange = { text -> actions.setWebhookHeaders(parseWebhookHeadersText(text)) },
                label = { Text("Headers") },
                placeholder = { Text("Authorization: Bearer token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 6,
            )

            OutlinedTextField(
                value = channel.bodyTemplate,
                onValueChange = actions::setWebhookBodyTemplate,
                label = { Text("Body template") },
                supportingText = { Text("Use {{message}} where the transcript should be inserted.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 4,
                maxLines = 8,
            )
        }
    }
}

fun webhookHeadersText(headers: List<WebhookHeader>): String =
    headers.joinToString("\n") { header -> "${header.name}: ${header.value}" }

fun parseWebhookHeadersText(text: String): List<WebhookHeader> =
    text.lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val separator = line.indexOf(':')
            if (separator < 0) {
                WebhookHeader(line.trim(), "")
            } else {
                WebhookHeader(
                    name = line.substring(0, separator).trim(),
                    value = line.substring(separator + 1).trim(),
                )
            }
        }
        .toList()
