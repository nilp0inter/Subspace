package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.service.RequiredPermissions

/**
 * Setup surface shown only when bootstrap has identified a concrete
 * user-resolvable prerequisite: missing runtime permissions or model assets
 * that require explicit download or repair.
 *
 * After the final setup action, the system automatically returns to loading.
 * There is no manual "Enter Subspace" acknowledgement.
 */
@Composable
fun InitialSetupScreen(
    missingPermissions: List<String>,
    invalidModelSets: List<String>,
    error: String?,
    onGrantPermissions: () -> Unit,
    onStartModelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionsDone = missingPermissions.isEmpty()

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
            done = permissionsDone,
            onRequest = onGrantPermissions,
        )

        ModelDownloadStep(
            done = invalidModelSets.isEmpty(),
            error = error,
            permissionsDone = permissionsDone,
            onStart = onStartModelDownload,
        )
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

            if (done) {
                Text("✓ Models verified", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Model download or repair required.", style = MaterialTheme.typography.bodyMedium)
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (!done) {
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