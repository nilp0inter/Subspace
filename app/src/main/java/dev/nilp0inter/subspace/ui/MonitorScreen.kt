package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.ButtonStates
import dev.nilp0inter.subspace.model.ClickButtonState
import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.EchoTimingMode
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.SttStatus
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

            Text("Timing", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimingButton(
                    selected = state.echoTimingMode == EchoTimingMode.RecordAfterBeep,
                    label = "After beep",
                    onClick = { actions.setEchoTimingMode(EchoTimingMode.RecordAfterBeep) },
                    modifier = Modifier.weight(1f),
                )
                TimingButton(
                    selected = state.echoTimingMode == EchoTimingMode.RecordWhileBeepPlays,
                    label = "During beep",
                    onClick = { actions.setEchoTimingMode(EchoTimingMode.RecordWhileBeepPlays) },
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
