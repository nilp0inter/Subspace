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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Non-secret host projection for one global connection profile. */
data class OpenAiProfileUiItem(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val credentialConfigured: Boolean,
    val modelState: OpenAiProfileModelUiState = OpenAiProfileModelUiState.NotLoaded,
)

/** Explicit rendering state of model discovery without transport or SDK values. */
sealed interface OpenAiProfileModelUiState {
    data object NotLoaded : OpenAiProfileModelUiState
    data object Loading : OpenAiProfileModelUiState
    data class Available(val modelCount: Int) : OpenAiProfileModelUiState
    data class Unavailable(val error: OpenAiProfileUiError) : OpenAiProfileModelUiState
}

/** Host-normalized, actionable profile operation errors. */
sealed interface OpenAiProfileUiError {
    val message: String
    data object HostUnavailable : OpenAiProfileUiError { override val message = "Profile service is unavailable." }

    data object InvalidName : OpenAiProfileUiError { override val message = "Enter a profile name." }
    data object InvalidBaseUrl : OpenAiProfileUiError { override val message = "Enter a valid base URL." }
    data object InvalidCredential : OpenAiProfileUiError { override val message = "Enter a bearer token." }
    data object CredentialUnavailable : OpenAiProfileUiError { override val message = "Secure credential storage is unavailable." }
    data object ProfileUnavailable : OpenAiProfileUiError { override val message = "This profile is no longer available." }
    data object AuthenticationFailed : OpenAiProfileUiError { override val message = "Authentication failed. Replace the bearer token and try again." }
    data object ConnectionFailed : OpenAiProfileUiError { override val message = "Could not reach this endpoint." }
    data object TimedOut : OpenAiProfileUiError { override val message = "The request timed out. Try again." }
    data object DiscoveryFailed : OpenAiProfileUiError { override val message = "Could not load models from this endpoint." }
    data object StorageFailed : OpenAiProfileUiError { override val message = "Could not save the profile." }
}

sealed interface OpenAiProfileUiMutationResult {
    data object Success : OpenAiProfileUiMutationResult
    data class Failure(val error: OpenAiProfileUiError) : OpenAiProfileUiMutationResult
}

data class OpenAiProfileEditRequest(
    val id: String?,
    val displayName: String,
    val baseUrl: String,
    /** Transient submission-only value; callers must consume it without retaining it. */
    val replacementBearerToken: CharSequence?,
)

/**
 * Phone surface for global connection profiles. It is deliberately driven entirely by non-secret
 * host projections and mutation callbacks; no repository, credential store, or SDK reaches Compose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenAiProfileManagementScreen(
    profiles: List<OpenAiProfileUiItem>,
    onSubmit: (OpenAiProfileEditRequest) -> OpenAiProfileUiMutationResult,
    onTest: (String) -> Unit,
    onRefreshModels: (String) -> Unit,
    onDelete: (String) -> OpenAiProfileUiMutationResult,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorTargetId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val editorTarget = profiles.firstOrNull { it.id == editorTargetId }

    if (creating || editorTarget != null) {
        OpenAiProfileEditor(
            existing = editorTarget,
            onSubmit = { request ->
                when (val result = onSubmit(request)) {
                    OpenAiProfileUiMutationResult.Success -> {
                        creating = false
                        editorTargetId = null
                        null
                    }
                    is OpenAiProfileUiMutationResult.Failure -> result.error.message
                }
            },
            onBack = {
                creating = false
                editorTargetId = null
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("OpenAI connection profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Bearer tokens are stored securely and are never shown here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add connection profile")
            }
            if (profiles.isEmpty()) {
                Text("No connection profiles configured.", style = MaterialTheme.typography.bodyMedium)
            }
            profiles.forEach { profile ->
                OpenAiProfileCard(
                    profile = profile,
                    onEdit = { editorTargetId = profile.id },
                    onTest = { onTest(profile.id) },
                    onRefreshModels = { onRefreshModels(profile.id) },
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun OpenAiProfileCard(
    profile: OpenAiProfileUiItem,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onRefreshModels: () -> Unit,
    onDelete: (String) -> OpenAiProfileUiMutationResult,
) {
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
            Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (profile.credentialConfigured) "Bearer token configured" else "Bearer token unavailable",
                color = if (profile.credentialConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(profile.modelState.label(), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onTest) { Text("Test") }
                OutlinedButton(onClick = onRefreshModels) { Text("Refresh models") }
            }
            OutlinedButton(
                onClick = {
                    error = when (val result = onDelete(profile.id)) {
                        OpenAiProfileUiMutationResult.Success -> null
                        is OpenAiProfileUiMutationResult.Failure -> result.error.message
                    }
                },
            ) { Text("Delete") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenAiProfileEditor(
    existing: OpenAiProfileUiItem?,
    onSubmit: (OpenAiProfileEditRequest) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayName by remember(existing?.id) { mutableStateOf(existing?.displayName.orEmpty()) }
    var baseUrl by remember(existing?.id) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    // Never save this value or place it in a route. Clear it immediately after every submission.
    var bearerToken by remember(existing?.id) { mutableStateOf("") }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add connection profile" else "Edit connection profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it; error = null },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; error = null },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it; error = null },
                label = { Text(if (existing == null) "Bearer token" else "Replacement bearer token (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (existing != null) {
                Text("Leave the bearer token empty to retain the configured credential.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val submittedToken = bearerToken.takeIf { it.isNotBlank() }
                    error = onSubmit(
                        OpenAiProfileEditRequest(
                            id = existing?.id,
                            displayName = displayName,
                            baseUrl = baseUrl,
                            replacementBearerToken = submittedToken,
                        ),
                    )
                    bearerToken = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existing == null) "Create profile" else "Save profile") }
        }
    }
}

private fun OpenAiProfileModelUiState.label(): String = when (this) {
    OpenAiProfileModelUiState.NotLoaded -> "Models have not been loaded."
    OpenAiProfileModelUiState.Loading -> "Loading models…"
    is OpenAiProfileModelUiState.Available -> "$modelCount model${if (modelCount == 1) "" else "s"} available"
    is OpenAiProfileModelUiState.Unavailable -> error.message
}
