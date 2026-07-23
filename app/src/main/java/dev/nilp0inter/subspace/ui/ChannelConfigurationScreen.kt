package dev.nilp0inter.subspace.ui

import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceRequest
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolver
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import org.json.JSONObject

/**
 * Native, host-owned editor for one descriptor configuration. Providers describe values only;
 * this component owns every Compose control, navigation action, and directory picker request.
 */
/** A host-produced directory selection addressed by one configuration owner and field ID. */
data class DirectorySelection(val ownerId: String, val fieldId: String, val path: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelConfigurationScreen(
    title: String,
    configurationOwnerId: String,
    descriptor: ChannelImplementationDescriptor,
    initialPayload: OpaqueJsonObject,
    submitLabel: String,
    onSubmit: (OpaqueJsonObject) -> String?,
    choiceResolver: DynamicConfigurationChoiceResolver = DynamicConfigurationChoiceResolver {
        DynamicConfigurationChoiceResolution.Unavailable(
            dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY,
        )
    },
    directorySelection: DirectorySelection?,
    onPickDirectory: (String, String) -> Unit,
    mountEntries: List<MountEditorEntry> = emptyList(),
    onPickMount: (MountSelectionRequest) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialValues = remember(descriptor, initialPayload) {
        val payload = initialPayload.toJsonObject()
        descriptor.configurationFields.mapNotNull { field ->
            if (payload.has(field.id)) field.id to initialFieldValue(field, payload) else null
        }.toMap()
    }
    val values = remember(descriptor, initialPayload) {
        mutableStateMapOf<String, String?>().apply { putAll(initialValues) }
    }
    var submissionError by remember(descriptor, initialPayload) { mutableStateOf<String?>(null) }
    LaunchedEffect(directorySelection, configurationOwnerId, descriptor.implementationId) {
        directorySelection?.takeIf { it.ownerId == configurationOwnerId }?.let { selection ->
            if (descriptor.configurationFields.any { it.id == selection.fieldId }) {
                values[selection.fieldId] = selection.path
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (descriptor.configurationFields.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = "No configuration required.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                descriptor.configurationFields.forEach { field ->
                    if (field.isVisible(values)) {
                        ChannelConfigurationFieldEditor(
                            field = field,
                            configurationOwnerId = configurationOwnerId,
                            value = values[field.id],
                            dependencyValue = (field as? ChannelConfigurationField.DynamicChoiceField)
                                ?.dependsOnFieldId
                                ?.let(values::get),
                            choiceResolver = choiceResolver,
                            onValueChange = {
                                values[field.id] = it
                                submissionError = null
                            },
                            onPickDirectory = onPickDirectory,
                        )
                    }
                }
            }

            if (mountEntries.isNotEmpty()) {
                mountEntries.forEach { entry ->
                    ResourceMountEditorRow(
                        entry = entry,
                        onPickMount = {
                            onPickMount(
                                MountSelectionRequest(
                                    ownerInstanceId = configurationOwnerId,
                                    implementationId = descriptor.implementationId,
                                    declarationId = entry.declaration.declarationId,
                                ),
                            )
                        },
                    )
                }
            }

            submissionError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    val payload = payloadWithFieldValues(initialPayload, descriptor.configurationFields, values)
                    submissionError = onSubmit(payload)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(submitLabel)
            }
        }
    }
}

@Composable
private fun ChannelConfigurationFieldEditor(
    field: ChannelConfigurationField,
    value: String?,
    onValueChange: (String?) -> Unit,
    dependencyValue: String?,
    choiceResolver: DynamicConfigurationChoiceResolver,
    configurationOwnerId: String,
    onPickDirectory: (String, String) -> Unit,
) {
    when (field) {
        is ChannelConfigurationField.BooleanField -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = value == "true",
                onCheckedChange = { onValueChange(it.toString()) },
            )
        }

        is ChannelConfigurationField.TextField -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onValueChange,
            label = { Text(requiredLabel(field)) },
            minLines = if (field.multiline) 3 else 1,
            modifier = Modifier.fillMaxWidth(),
        )

        is ChannelConfigurationField.NumberField -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onValueChange,
            label = { Text(requiredLabel(field)) },
            supportingText = {
                field.minimum?.let { minimum ->
                    Text("Minimum: $minimum" + field.maximum?.let { ", maximum: $it" }.orEmpty())
                } ?: field.maximum?.let { maximum -> Text("Maximum: $maximum") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        is ChannelConfigurationField.ChoiceField -> ChoiceEditor(field, value, onValueChange)

        is ChannelConfigurationField.DynamicChoiceField -> DynamicChoiceEditor(
            field = field,
            value = value,
            dependencyValue = dependencyValue,
            choiceResolver = choiceResolver,
            onValueChange = onValueChange,
        )

        is ChannelConfigurationField.DirectoryField -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
            Text(
                value ?: "No directory selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onPickDirectory(configurationOwnerId, field.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (value == null) "Select directory" else "Change directory")
            }
        }
    }
}

@Composable
private fun ChoiceEditor(
    field: ChannelConfigurationField.ChoiceField,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    val selected = field.choices.firstOrNull { it.id == value }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected?.label ?: "Select ${field.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onValueChange(choice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun ChannelConfigurationField.isVisible(values: Map<String, String?>): Boolean = when (this) {
    is ChannelConfigurationField.DynamicChoiceField ->
        visibleWhenFieldId == null || values[visibleWhenFieldId] == visibleWhenValue
    else -> true
}

@Composable
private fun DynamicChoiceEditor(
    field: ChannelConfigurationField.DynamicChoiceField,
    value: String?,
    dependencyValue: String?,
    choiceResolver: DynamicConfigurationChoiceResolver,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    val resolution by androidx.compose.runtime.produceState<DynamicConfigurationChoiceResolution>(
        initialValue = DynamicConfigurationChoiceResolution.Loading,
        field.source,
        dependencyValue,
        choiceResolver,
    ) {
        this.value = try {
            choiceResolver.resolve(DynamicConfigurationChoiceRequest(field.source, dependencyValue))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DynamicConfigurationChoiceResolution.Unavailable(
                dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED,
            )
        }
    }
    val choices = (resolution as? DynamicConfigurationChoiceResolution.Available)?.choices.orEmpty()
    val selected = choices.firstOrNull { it.id == value }
    val selectedLabel = selected?.label ?: value?.let { "Unavailable: $it" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requiredLabel(field), style = MaterialTheme.typography.bodyLarge)
        when (val state = resolution) {
            DynamicConfigurationChoiceResolution.Loading -> Text(
                "Loading ${field.label.lowercase()}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is DynamicConfigurationChoiceResolution.Unavailable -> Text(
                dynamicChoiceUnavailableMessage(state, dependencyValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is DynamicConfigurationChoiceResolution.Available -> Unit
        }
        OutlinedButton(
            enabled = resolution is DynamicConfigurationChoiceResolution.Available,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedLabel ?: "Select ${field.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onValueChange(choice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun dynamicChoiceUnavailableMessage(
    resolution: DynamicConfigurationChoiceResolution.Unavailable,
    dependencyValue: String?,
): String = when (resolution.reason) {
    dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.DEPENDENCY_MISSING ->
        if (dependencyValue == null) "Choose the required dependency first." else "The selected dependency is unavailable."
    dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE ->
        "Choices are currently unavailable."
    dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.DISCOVERY_FAILED ->
        "Could not load choices. Refresh the source and try again."
    dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason.HOST_NOT_READY ->
        "Choices are unavailable until the host is ready."
}

private fun requiredLabel(field: ChannelConfigurationField): String =
    if (field.required) field.label else "${field.label} (optional)"

internal fun initialFieldValue(field: ChannelConfigurationField, payload: JSONObject): String? {
    val raw = payload.opt(field.id)
    if (raw == null || raw == JSONObject.NULL) return null
    return when (field) {
        is ChannelConfigurationField.BooleanField -> (raw as? Boolean)?.toString()
        is ChannelConfigurationField.TextField,
        is ChannelConfigurationField.ChoiceField,
        is ChannelConfigurationField.DynamicChoiceField,
        is ChannelConfigurationField.DirectoryField,
        -> raw as? String
        is ChannelConfigurationField.NumberField -> (raw as? Number)?.toString()
    }
}

/**
 * Only declared field IDs are changed. All unknown keys remain in the copied opaque object so
 * newer providers can survive a host edit without data loss.
 */
internal fun payloadWithFieldValues(
    initialPayload: OpaqueJsonObject,
    fields: List<ChannelConfigurationField>,
    values: Map<String, String?>,
): OpaqueJsonObject {
    val payload = initialPayload.toJsonObject()
    fields.forEach { field ->
        if (!values.containsKey(field.id)) return@forEach
        val value = values[field.id]
        when (field) {
            is ChannelConfigurationField.BooleanField -> payload.put(field.id, value == "true")
            is ChannelConfigurationField.TextField,
            is ChannelConfigurationField.ChoiceField,
            is ChannelConfigurationField.DynamicChoiceField,
            is ChannelConfigurationField.DirectoryField,
            -> payload.put(field.id, value ?: JSONObject.NULL)
            is ChannelConfigurationField.NumberField -> {
                val parsed = value?.toLongOrNull()
                payload.put(field.id, parsed ?: value ?: JSONObject.NULL)
            }
        }
    }
    return OpaqueJsonObject.fromJsonObject(payload)
}

/**
 * 2.6/2.7: Host-rendered editor row for one declared resource mount. Shows only
 * package-provided label/help and the portable status; the pick button launches
 * the generic SAF directory-tree selection keyed by owner instance, provider
 * implementation, and declaration ID. Never renders a grant blob, URI, or path.
 */
@Composable
private fun ResourceMountEditorRow(
    entry: MountEditorEntry,
    onPickMount: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (entry.declaration.required) entry.declaration.label else "${entry.declaration.label} (optional)",
            style = MaterialTheme.typography.bodyLarge,
        )
        entry.declaration.help?.let { help ->
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Status: ${entry.statusPortable}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isBlocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onPickMount,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (entry.availability) {
                    is dev.nilp0inter.subspace.resource.MountAvailability.Available -> "Change directory"
                    is dev.nilp0inter.subspace.resource.MountAvailability.Unavailable -> "Select directory"
                },
            )
        }
    }
}
