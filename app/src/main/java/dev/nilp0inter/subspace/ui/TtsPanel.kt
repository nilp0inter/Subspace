package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.MonitorState
import dev.nilp0inter.subspace.model.TTS_LANGS
import dev.nilp0inter.subspace.model.TTS_VOICE_STYLES
import dev.nilp0inter.subspace.model.displayText

@Composable
internal fun TtsPanel(state: MonitorState, actions: PttUiActions) {
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
