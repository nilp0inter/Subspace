package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.dependency.ConfigurationFieldDeclaration
import dev.nilp0inter.subspace.dependency.InstalledProviderId
import dev.nilp0inter.subspace.dependency.PackageCapability
import dev.nilp0inter.subspace.dependency.PackageConfigurationDeclaration
import dev.nilp0inter.subspace.dependency.PackageConfigurationLimits
import dev.nilp0inter.subspace.dependency.UiChoice
import dev.nilp0inter.subspace.dependency.UiControl
import dev.nilp0inter.subspace.dependency.UiFieldDeclaration
import dev.nilp0inter.subspace.dependency.ValidatedPackageRevision
import dev.nilp0inter.subspace.lua.actor.ActorPolicy
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelConfigurationMigrationStep
import dev.nilp0inter.subspace.model.ChannelConfigurationProvider
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.InstalledProviderBinding
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ChannelPresentationMetadata
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashSet

internal object LuaPackageMaterializer {
    internal fun materialize(
        revision: ValidatedPackageRevision,
        bridge: LuaKernelBridge,
        actorPolicy: ActorPolicy = ActorPolicy.startingEvidence(),
        validationBounds: ValidationBounds = ValidationBounds.DEFAULT,
        logSink: PluginLogSink = NoOpPluginLogSink
    ): InstalledProviderBinding {
        val implementationId = InstalledProviderId.derive(revision.manifest.repositoryId)
        val presentation = ChannelPresentationMetadata(
            label = revision.manifest.presentation.label,
            summary = revision.manifest.presentation.summary,
            unavailableMessage = "Lua package is unavailable or failed to initialize."
        )
        val declaration = revision.manifest.configuration
        val configurationProvider = CompiledConfigurationProvider(
            implementationId = implementationId,
            declaration = declaration,
        )
        val fields = compileFields(declaration)
        val capabilities = compileCapabilities(revision.manifest.capabilities)
        val provider = LuaChannelImplementationProvider.create(
            implementationId = implementationId,
            presentation = presentation,
            programImage = revision.programImage,
            fingerprint = revision.fingerprint,
            actorFactory = { context, capabilities, kernelBridge, policy ->
                ActorRuntimeFactory.createForGeneration(context, capabilities, kernelBridge, policy)
            },
            bridge = bridge,
            actorPolicy = actorPolicy,
            validationBounds = validationBounds,
            logSink = logSink,
            configurationProvider = configurationProvider,
            configurationFields = fields,
            requiredCapabilities = capabilities,
        )
        return InstalledProviderBinding(
            repositoryId = revision.manifest.repositoryId,
            expectedDigest = revision.digest,
            provider = provider
        )
    }

    private fun compileFields(declaration: PackageConfigurationDeclaration): List<ChannelConfigurationField> {
        return declaration.ui.fields.map { uiField ->
            when (uiField.control) {
                UiControl.TEXT -> ChannelConfigurationField.TextField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                )
                UiControl.TOGGLE -> ChannelConfigurationField.BooleanField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                )
                UiControl.NUMBER -> {
                    val dataField = declaration.data.fields.find { it.id == uiField.field }
                    val integerField = dataField as? ConfigurationFieldDeclaration.IntegerField
                    ChannelConfigurationField.NumberField(
                        id = uiField.field,
                        label = uiField.label,
                        help = uiField.help,
                        required = true,
                        minimum = integerField?.minimum,
                        maximum = integerField?.maximum,
                    )
                }
                UiControl.CHOICE -> ChannelConfigurationField.ChoiceField(
                    id = uiField.field,
                    label = uiField.label,
                    help = uiField.help,
                    required = true,
                    choices = uiField.choices?.map { choice ->
                        ChannelConfigurationField.ChoiceField.Choice(
                            id = choice.value,
                            label = choice.label,
                        )
                    } ?: emptyList(),
                )
            }
        }
    }

    private fun compileCapabilities(declaredCapabilities: Set<String>): Set<ChannelCapability> {
        // Deterministic public→internal semantic mapping. Public manifest IDs
        // (PackageCapability) compile to existing internal ChannelCapability
        // requirements only; no CapabilityKey names or implementation classes
        // appear in the compiled set. Declaration order is preserved and the
        // result is immutable. Unknown IDs are rejected fail-closed — the
        // manifest parser already validates against PackageCapability.ALL, but
        // this function is independently deterministic.
        val result = LinkedHashSet<ChannelCapability>()
        for (cap in declaredCapabilities) {
            when (cap) {
                PackageCapability.AUDIO_TRANSCRIPTION -> result.add(ChannelCapability.Transcription)
                PackageCapability.AUDIO_SYNTHESIS -> result.add(ChannelCapability.Synthesis)
                PackageCapability.AUDIO_PLAYBACK -> {
                    // audio.playback requires both the audio-operation mechanism
                    // (PCM playback creation) and deferred-playback eligibility
                    // (scheduled/leased delivery). Order matches design D5.
                    result.add(ChannelCapability.AudioOperation)
                    result.add(ChannelCapability.DeferredAudioPlayback)
                }
                else -> throw IllegalArgumentException(
                    "Unknown package capability ID: $cap. " +
                        "Expected one of ${PackageCapability.ALL}.",
                )
            }
        }
        return Collections.unmodifiableSet(result)
    }
}

/**
 * Package-specific declaration-compiled configuration provider for schema version 1.
 *
 * Produces the complete default [OpaqueJsonObject] from declared field defaults and
 * validates submitted payloads against the declaration schema. Construction performs
 * no Lua execution, module loading, actor creation, or state allocation.
 */
internal class CompiledConfigurationProvider(
    override val implementationId: ChannelImplementationId,
    private val declaration: PackageConfigurationDeclaration,
) : ChannelConfigurationProvider {

    override val currentSchemaVersion: Int = 1

    override fun defaultPayload(): OpaqueJsonObject {
        val obj = JSONObject()
        for (field in declaration.data.fields) {
            when (field) {
                is ConfigurationFieldDeclaration.StringField -> obj.put(field.id, field.default)
                is ConfigurationFieldDeclaration.BooleanField -> obj.put(field.id, field.default)
                is ConfigurationFieldDeclaration.IntegerField -> obj.put(field.id, field.default)
            }
        }
        return OpaqueJsonObject.fromJsonObject(obj)
    }

    override fun validate(
        schemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ProviderConfigurationResult {
        if (schemaVersion != currentSchemaVersion) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId, schemaVersion, currentSchemaVersion,
                ),
            )
        }

        val obj = try {
            payload.toJsonObject()
        } catch (_: Exception) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId, schemaVersion,
                    "Configuration payload is not a valid JSON object",
                ),
            )
        }

        val declaredFields = declaration.data.fields.associateBy { it.id }

        // Reject undeclared keys
        val keyIterator = obj.keys()
        while (keyIterator.hasNext()) {
            val key = keyIterator.next()
            if (key !in declaredFields) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Undeclared configuration field: $key",
                    ),
                )
            }
        }

        // Require every declared field present and validate scalar type/value
        for (field in declaration.data.fields) {
            if (!obj.has(field.id)) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Missing required field: ${field.id}",
                    ),
                )
            }

            val value = obj.get(field.id)

            // Reject null values
            if (value === JSONObject.NULL) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Field '${field.id}' must not be null",
                    ),
                )
            }

            // Reject nested objects and arrays
            if (value is JSONObject || value is org.json.JSONArray) {
                return ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId, schemaVersion,
                        "Field '${field.id}' must be a scalar, got ${value::class.simpleName}",
                    ),
                )
            }

            when (field) {
                is ConfigurationFieldDeclaration.StringField -> {
                    if (value !is String) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be a string, got ${value::class.simpleName}",
                            ),
                        )
                    }
                    if (value.toByteArray(Charsets.UTF_8).size > PackageConfigurationLimits.MAX_STRING_VALUE_BYTES) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' string value exceeds ${PackageConfigurationLimits.MAX_STRING_VALUE_BYTES} bytes",
                            ),
                        )
                    }
                    if (field.allowedValues != null && value !in field.allowedValues) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' has value '$value' not in allowed values",
                            ),
                        )
                    }
                }
                is ConfigurationFieldDeclaration.BooleanField -> {
                    if (value !is Boolean) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be a boolean, got ${value::class.simpleName}",
                            ),
                        )
                    }
                }
                is ConfigurationFieldDeclaration.IntegerField -> {
                    // Reject floating-point numbers: only Int/Long are valid integers
                    if (value !is Int && value !is Long) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' must be an integer, got ${value::class.simpleName}",
                            ),
                        )
                    }
                    val longValue = (value as Number).toLong()
                    if (field.minimum != null && longValue < field.minimum) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' value $longValue is below minimum ${field.minimum}",
                            ),
                        )
                    }
                    if (field.maximum != null && longValue > field.maximum) {
                        return ProviderConfigurationResult.Failure(
                            ChannelProviderError.InvalidConfiguration(
                                implementationId, schemaVersion,
                                "Field '${field.id}' value $longValue exceeds maximum ${field.maximum}",
                            ),
                        )
                    }
                }
            }
        }

        // Canonical 64 KiB total payload bound
        if (payload.toJsonString().toByteArray(Charsets.UTF_8).size > PackageConfigurationLimits.MAX_PAYLOAD_BYTES) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId, schemaVersion,
                    "Configuration payload exceeds ${PackageConfigurationLimits.MAX_PAYLOAD_BYTES} bytes",
                ),
            )
        }

        return ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    override fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
        ChannelProviderError.UnsupportedSchemaVersion(
            implementationId, fromSchemaVersion, currentSchemaVersion,
        ),
    )
}
