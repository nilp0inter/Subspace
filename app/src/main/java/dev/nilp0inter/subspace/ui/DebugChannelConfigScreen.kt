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

