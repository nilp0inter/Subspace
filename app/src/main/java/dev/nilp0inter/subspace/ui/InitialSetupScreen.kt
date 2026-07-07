package dev.nilp0inter.subspace.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.DownloadProgress
import dev.nilp0inter.subspace.model.SetupState
import dev.nilp0inter.subspace.service.RequiredPermissions

/**
 * Mandatory onboarding gate shown before the dashboard on first launch and on
 * any later launch where permissions are missing or model hashes fail.
 *
 * Step 1 requests all runtime permissions in one batch; step 2 downloads +
 * SHA-256-verifies the Parakeet and Supertonic model sets; the "Enter Subspace"
 * button is gated on both steps completing.
 */
@Composable
fun InitialSetupScreen(
    state: SetupState,
    onGrantPermissions: () -> Unit,
    onStartModelDownload: () -> Unit,
    onEnterSubspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> onGrantPermissions() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TerminalHeader(
            title = "SUBSPACE SETUP",
            subtitle = "Grant permissions and download speech models to continue.",
        )

        PermissionsStep(
            done = state.permissionsDone,
            onRequest = { permissionLauncher.launch(RequiredPermissions.runtimePermissions()) },
        )

        ModelDownloadStep(
            done = state.modelsDone,
            downloading = state.downloading,
            parakeetProgress = state.parakeetProgress,
            supertonicProgress = state.supertonicProgress,
            error = state.error,
            permissionsDone = state.permissionsDone,
            onStart = onStartModelDownload,
        )

        Button(
            onClick = onEnterSubspace,
            enabled = state.done,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enter Subspace")
        }
    }
}

@Composable
private fun PermissionsStep(done: Boolean, onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 1 — PERMISSIONS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (done) "All runtime permissions granted." else "Bluetooth, microphone, and notification permissions are required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (!done) {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant permissions")
                }
            } else {
                Text("✓ Granted", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModelDownloadStep(
    done: Boolean,
    downloading: Boolean,
    parakeetProgress: DownloadProgress,
    supertonicProgress: DownloadProgress,
    error: String?,
    permissionsDone: Boolean,
    onStart: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("STEP 2 — SPEECH MODELS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Download the Parakeet (STT) and Supertonic (TTS) models (~950 MB). A network connection is required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))

            ModelRow("Parakeet STT", parakeetProgress, done)
            Spacer(Modifier.height(8.dp))
            ModelRow("Supertonic TTS", supertonicProgress, done)

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (!done && !downloading) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    enabled = permissionsDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download models")
                }
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, progress: DownloadProgress, setDone: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (setDone) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else if (progress.totalBytes > 0) {
            val pct = (progress.bytesRead.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
            Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        } else if (progress.fileCount > 0) {
            Text("file ${progress.fileIndex + 1}/${progress.fileCount}", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!setDone && progress.totalBytes > 0) {
        val pct = (progress.bytesRead.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
