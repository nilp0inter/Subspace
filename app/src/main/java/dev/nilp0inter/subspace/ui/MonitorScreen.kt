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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.ButtonStates
import dev.nilp0inter.subspace.model.ClickButtonState
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TTS_LANGS
import dev.nilp0inter.subspace.model.TTS_VOICE_STYLES
import dev.nilp0inter.subspace.model.TtsStatus
import dev.nilp0inter.subspace.model.TwoStateButton
import dev.nilp0inter.subspace.model.displayText

@Composable
fun MonitorScreen(
    state: MonitorState,
    actions: PttUiActions,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        state.buttons.ptt == TwoStateButton.Pressed -> MaterialTheme.colorScheme.primary
        state.echoStatus == EchoStatus.Playback -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "FIELD MONITOR",
            subtitle = "${state.hardwareMode.name.uppercase()} mode · ${state.echoStatus.displayText()}",
        )

        PttStatusCard(state, accent)
        ButtonTable(state.buttons)
        EchoControls(state, actions)
        SttControls(state, actions)
        TtsControls(state, actions)
        SttTtsControls(state, actions)
        AudioStatus(state)

        OutlinedButton(onClick = actions::disconnectSerial, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect serial")
        }
    }
}

@Composable
private fun PttStatusCard(state: MonitorState, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("PTT", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = state.buttons.ptt.name.uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = accent,
            )
            Text("Hardware mode: ${state.hardwareMode.name}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ButtonTable(buttons: ButtonStates) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("BUTTON STATE", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            MonitorRow("PTT", buttons.ptt.name.lowercase())
            MonitorRow("SOS", buttons.sos.name.toDisplayToken())
            MonitorRow("Group", buttons.group.name.lowercase())
            MonitorRow("Volume Up", buttons.volumeUp.displayText())
            MonitorRow("Volume Down", buttons.volumeDown.displayText())
        }
    }
}

@Composable
private fun EchoControls(state: MonitorState, actions: PttUiActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column {
                    Text("ECHO TEST", style = MaterialTheme.typography.titleLarge)
                    Text("Runs only on future PTT presses", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = state.echoEnabled, onCheckedChange = actions::setEchoEnabled)
            }

        }
    }
}

@Composable
private fun TimingButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SttControls(state: MonitorState, actions: PttUiActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column {
                    Text("STT TEST", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Parakeet v3 on-device · ${state.sttModelStatus.displayText()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = state.sttEnabled, onCheckedChange = actions::setSttEnabled)
            }

            val statusText = state.sttStatus.displayText()
            val transcript = state.sttTranscript
            val boxText = when (state.sttStatus) {
                is SttStatus.Transcribed -> transcript.ifBlank { statusText }
                SttStatus.Idle -> if (transcript.isBlank()) "No transcript yet" else transcript
                else -> statusText
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = boxText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TtsControls(state: MonitorState, actions: PttUiActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column {
                    Text("TTS TEST", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Supertonic 3 on-device · ${state.ttsModelStatus.displayText()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = state.ttsEnabled, onCheckedChange = actions::setTtsEnabled)
            }

            OutlinedTextField(
                value = state.ttsText,
                onValueChange = actions::setTtsText,
                label = { Text("Text to synthesize") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParameterDropdown(
                    label = "Voice",
                    selected = state.ttsVoiceStyle,
                    options = TTS_VOICE_STYLES,
                    onSelect = actions::setTtsVoiceStyle,
                    modifier = Modifier.weight(1f),
                )
                ParameterDropdown(
                    label = "Language",
                    selected = state.ttsLang,
                    options = TTS_LANGS,
                    onSelect = actions::setTtsLang,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Steps", style = MaterialTheme.typography.bodyMedium)
                val stepOptions = listOf(1, 2, 4, 8, 16, 32)
                stepOptions.forEach { steps ->
                    TimingButton(
                        selected = state.ttsTotalSteps == steps,
                        label = steps.toString(),
                        onClick = { actions.setTtsTotalSteps(steps) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", style = MaterialTheme.typography.bodyMedium)
                val speedOptions = listOf(0.8f, 1.0f, 1.05f, 1.2f, 1.5f)
                speedOptions.forEach { speed ->
                    TimingButton(
                        selected = state.ttsSpeed == speed,
                        label = "%.2f".format(speed),
                        onClick = { actions.setTtsSpeed(speed) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Button(
                onClick = actions::requestTtsSynthesis,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.ttsEnabled,
            ) {
                Text("Synthesize")
            }

            val statusText = state.ttsStatus.displayText()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SttTtsControls(state: MonitorState, actions: PttUiActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Column {
                    Text("STT↔TTS TEST", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Hold PTT to record, release to transcribe + synthesize",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = state.sttTtsEnabled, onCheckedChange = actions::setSttTtsEnabled)
            }

            val boxText = when (state.sttTtsStatus) {
                is SttTtsStatus.Transcript -> state.sttTtsTranscript.ifBlank { state.sttTtsStatus.displayText() }
                SttTtsStatus.Idle -> if (state.sttTtsTranscript.isBlank()) "No transcript yet" else state.sttTtsTranscript
                else -> state.sttTtsStatus.displayText()
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = boxText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ParameterDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$label: $selected")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioStatus(state: MonitorState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("AUDIO ROUTE", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            MonitorRow("SCO", state.scoState.displayText())
            MonitorRow("Echo", state.echoStatus.displayText())
            MonitorRow("TTS", state.ttsStatus.displayText())
            MonitorRow("STT↔TTS", state.sttTtsStatus.displayText())
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}

private fun String.toDisplayToken(): String = when (this) {
    "LONGPRESSED" -> "long-pressed"
    else -> lowercase()
}

private fun ClickButtonState.displayText(): String = when (this) {
    ClickButtonState.Idle -> "idle"
    ClickButtonState.Clicked -> "clicked"
}
