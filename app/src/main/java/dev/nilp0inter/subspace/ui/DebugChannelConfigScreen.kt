package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.SttStatus
import dev.nilp0inter.subspace.model.SttTtsStatus
import dev.nilp0inter.subspace.model.TTS_LANGS
import dev.nilp0inter.subspace.model.TTS_VOICE_STYLES
import dev.nilp0inter.subspace.model.displayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugChannelConfigScreen(
    channel: DebugChannel,
    monitorState: MonitorState,
    actions: PttUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Channel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable Debug Channel", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Mutually exclusive with Captain's Log",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = channel.enabled,
                        onCheckedChange = actions::setDebugChannelEnabled
                    )
                }
            }

            Text("Diagnostic Mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

            Column(Modifier.selectableGroup()) {
                DebugModeOption(
                    mode = DebugMode.ECHO,
                    selectedMode = channel.mode,
                    label = "Echo",
                    description = "Records audio on PTT and plays it back immediately",
                    onSelect = { actions.setDebugChannelMode(DebugMode.ECHO) }
                )
                DebugModeOption(
                    mode = DebugMode.STT,
                    selectedMode = channel.mode,
                    label = "Speech-to-Text",
                    description = "Transcribes speech to text using Parakeet v3",
                    onSelect = { actions.setDebugChannelMode(DebugMode.STT) }
                )
                DebugModeOption(
                    mode = DebugMode.TTS,
                    selectedMode = channel.mode,
                    label = "Text-to-Speech",
                    description = "Synthesizes text to speech using Supertonic 3",
                    onSelect = { actions.setDebugChannelMode(DebugMode.TTS) }
                )
                DebugModeOption(
                    mode = DebugMode.STT_TTS,
                    selectedMode = channel.mode,
                    label = "Round-trip (STT↔TTS)",
                    description = "Transcribes speech and reads it back",
                    onSelect = { actions.setDebugChannelMode(DebugMode.STT_TTS) }
                )
            }

            when (channel.mode) {
                DebugMode.STT -> SttPanel(monitorState)
                DebugMode.TTS -> TtsPanel(monitorState, actions)
                DebugMode.STT_TTS -> SttTtsPanel(monitorState, actions)
                DebugMode.ECHO -> { /* No extra panel */ }
            }
        }
    }
}

@Composable
private fun DebugModeOption(
    mode: DebugMode,
    selectedMode: DebugMode,
    label: String,
    description: String,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = (mode == selectedMode),
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = (mode == selectedMode),
            onClick = null 
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SttPanel(state: MonitorState) {
    val statusText = state.sttStatus.displayText()
    val transcript = state.sttTranscript
    val boxText = when (state.sttStatus) {
        is SttStatus.Transcribed -> transcript.ifBlank { statusText }
        SttStatus.Idle -> if (transcript.isBlank()) "No transcript yet" else transcript
        else -> statusText
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

@Composable
private fun TtsPanel(state: MonitorState, actions: PttUiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        ) {
            Text("Synthesize")
        }

        val statusText = state.ttsStatus.displayText()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

@Composable
private fun SttTtsPanel(state: MonitorState, actions: PttUiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val boxText = when (state.sttTtsStatus) {
            is SttTtsStatus.Transcript -> state.sttTtsTranscript.ifBlank { state.sttTtsStatus.displayText() }
            SttTtsStatus.Idle -> if (state.sttTtsTranscript.isBlank()) "No transcript yet" else state.sttTtsTranscript
            else -> state.sttTtsStatus.displayText()
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = boxText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParameterDropdown(
                label = "Voice",
                selected = state.sttTtsVoiceStyle,
                options = TTS_VOICE_STYLES,
                onSelect = actions::setSttTtsVoiceStyle,
                modifier = Modifier.weight(1f),
            )
            ParameterDropdown(
                label = "Language",
                selected = state.sttTtsLang,
                options = TTS_LANGS,
                onSelect = actions::setSttTtsLang,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Steps", style = MaterialTheme.typography.bodyMedium)
            val stepOptions = listOf(1, 2, 4, 8, 16, 32)
            stepOptions.forEach { steps ->
                TimingButton(
                    selected = state.sttTtsTotalSteps == steps,
                    label = steps.toString(),
                    onClick = { actions.setSttTtsTotalSteps(steps) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", style = MaterialTheme.typography.bodyMedium)
            val speedOptions = listOf(0.8f, 1.0f, 1.05f, 1.2f, 1.5f)
            speedOptions.forEach { speed ->
                TimingButton(
                    selected = state.sttTtsSpeed == speed,
                    label = "%.2f".format(speed),
                    onClick = { actions.setSttTtsSpeed(speed) },
                    modifier = Modifier.weight(1f),
                )
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
