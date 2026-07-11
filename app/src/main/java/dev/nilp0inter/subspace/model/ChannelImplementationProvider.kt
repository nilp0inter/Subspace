package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.service.ChannelRuntime
import org.json.JSONObject

/** Typed failures returned by provider registration, configuration, and construction. */
sealed interface ChannelProviderError {
    val implementationId: ChannelImplementationId
    val message: String

    data class DuplicateRegistration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is already registered"
    }

    data class MissingProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelProviderError {
        override val message = "Implementation provider $implementationId is not registered"
    }

    data class UnsupportedSchemaVersion(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val currentSchemaVersion: Int,
    ) : ChannelProviderError {
        override val message =
            "Provider $implementationId does not support schema $schemaVersion (current $currentSchemaVersion)"
    }

    data class InvalidConfiguration(
        override val implementationId: ChannelImplementationId,
        val schemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Invalid configuration for $implementationId schema $schemaVersion: $detail"
    }

    data class MigrationFailed(
        override val implementationId: ChannelImplementationId,
        val fromSchemaVersion: Int,
        val detail: String,
    ) : ChannelProviderError {
        override val message =
            "Could not migrate $implementationId configuration from schema $fromSchemaVersion: $detail"
    }

    data class RuntimeConstructionFailed(
        override val implementationId: ChannelImplementationId,
        val detail: String,
    ) : ChannelProviderError {
        override val message = "Could not construct runtime for $implementationId: $detail"
    }
}

sealed interface ProviderConfigurationResult {
    data class Success(val configuration: ValidatedChannelConfiguration) : ProviderConfigurationResult
    data class Failure(val error: ChannelProviderError) : ProviderConfigurationResult
}

data class ValidatedChannelConfiguration(
    val implementationId: ChannelImplementationId,
    val schemaVersion: Int,
    val payload: OpaqueJsonObject,
)

/** A provider-controlled, one-step schema migration result. */
sealed interface ChannelConfigurationMigrationStep {
    data class Success(val payload: OpaqueJsonObject) : ChannelConfigurationMigrationStep
    data class Failure(val error: ChannelProviderError) : ChannelConfigurationMigrationStep
}

/**
 * Provider-owned deterministic configuration boundary. It accepts and returns opaque objects
 * so host catalogue persistence cannot discard fields added by a newer provider.
 */
interface ChannelConfigurationProvider {
    val implementationId: ChannelImplementationId
    val currentSchemaVersion: Int

    fun defaultPayload(): OpaqueJsonObject

    fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult

    /** Migrates exactly [fromSchemaVersion] to its next integer version. */
    fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep

    fun migrateAndValidate(
        schemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ProviderConfigurationResult {
        if (schemaVersion > currentSchemaVersion || schemaVersion < 1) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId,
                    schemaVersion,
                    currentSchemaVersion,
                ),
            )
        }
        var version = schemaVersion
        var migratedPayload = payload
        while (version < currentSchemaVersion) {
            when (val step = migrateStep(version, migratedPayload)) {
                is ChannelConfigurationMigrationStep.Success -> {
                    migratedPayload = step.payload
                    version += 1
                }
                is ChannelConfigurationMigrationStep.Failure -> return ProviderConfigurationResult.Failure(step.error)
            }
        }
        return validate(version, migratedPayload)
    }
}

data class ChannelPresentationMetadata(
    val label: String,
    val summary: String,
    val unavailableMessage: String,
)

sealed interface ChannelConfigurationField {
    val id: String
    val label: String
    val required: Boolean

    data class BooleanField(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
    ) : ChannelConfigurationField

    data class TextField(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
        val multiline: Boolean = false,
    ) : ChannelConfigurationField

    data class ChoiceField(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
        val choices: List<Choice>,
    ) : ChannelConfigurationField {
        data class Choice(val id: String, val label: String)
    }

    data class NumberField(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
        val minimum: Long? = null,
        val maximum: Long? = null,
    ) : ChannelConfigurationField

    /** Host UI owns directory acquisition; providers receive only the resulting string value. */
    data class DirectoryField(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
    ) : ChannelConfigurationField
}

data class ChannelPreparationTraits(
    val supportsRecoverablePreparation: Boolean,
)

data class ChannelImplementationDescriptor(
    val implementationId: ChannelImplementationId,
    val presentation: ChannelPresentationMetadata,
    val configuration: ChannelConfigurationProvider,
    val configurationFields: List<ChannelConfigurationField>,
    val requiredCapabilities: Set<ChannelCapability>,
    val preparationTraits: ChannelPreparationTraits,
) {
    init {
        require(configuration.implementationId == implementationId) {
            "Configuration provider ID must match descriptor ID"
        }
        require(configuration.currentSchemaVersion > 0) {
            "Configuration provider version must be positive"
        }
        require(configurationFields.map { it.id }.all { it.isNotBlank() }) {
            "Configuration field IDs must not be blank"
        }
        require(configurationFields.map { it.id }.distinct().size == configurationFields.size) {
            "Configuration field IDs must be unique"
        }
    }
}

data class ChannelRuntimeConstructionRequest(
    val definition: ChannelDefinition,
    val configuration: ValidatedChannelConfiguration,
    val capabilities: ChannelCapabilityScope,
) {
    init {
        require(definition.implementationId == configuration.implementationId) {
            "Validated configuration provider must match its definition"
        }
        require(definition.configSchemaVersion == configuration.schemaVersion) {
            "Runtime construction requires the definition's current validated schema"
        }
        require(definition.configPayload == configuration.payload) {
            "Runtime construction requires the definition's validated payload"
        }
    }
}

sealed interface ChannelRuntimeConstructionResult {
    data class Success(val runtime: ChannelRuntime) : ChannelRuntimeConstructionResult
    data class Failure(val error: ChannelProviderError) : ChannelRuntimeConstructionResult
}

interface ChannelImplementationProvider {
    val descriptor: ChannelImplementationDescriptor

    suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult
}

sealed interface ChannelProviderRegistrationResult {
    data object Registered : ChannelProviderRegistrationResult
    data class Rejected(val error: ChannelProviderError) : ChannelProviderRegistrationResult
}

sealed interface ChannelProviderResolution {
    data class Available(val provider: ChannelImplementationProvider) : ChannelProviderResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelProviderResolution
}

sealed interface ChannelDescriptorResolution {
    data class Available(val descriptor: ChannelImplementationDescriptor) : ChannelDescriptorResolution
    data class Missing(val error: ChannelProviderError.MissingProvider) : ChannelDescriptorResolution
}

interface ChannelImplementationDescriptorResolver {
    fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution
}

/** Deterministic insertion-ordered registry; a duplicate never replaces the original provider. */
class ChannelImplementationProviderRegistry : ChannelImplementationDescriptorResolver {
    private val providers = LinkedHashMap<ChannelImplementationId, ChannelImplementationProvider>()

    fun register(provider: ChannelImplementationProvider): ChannelProviderRegistrationResult {
        val id = provider.descriptor.implementationId
        if (providers.containsKey(id)) {
            return ChannelProviderRegistrationResult.Rejected(ChannelProviderError.DuplicateRegistration(id))
        }
        providers[id] = provider
        return ChannelProviderRegistrationResult.Registered
    }

    fun resolve(implementationId: ChannelImplementationId): ChannelProviderResolution =
        providers[implementationId]?.let(ChannelProviderResolution::Available)
            ?: ChannelProviderResolution.Missing(ChannelProviderError.MissingProvider(implementationId))

    fun descriptors(): List<ChannelImplementationDescriptor> = providers.values.map { it.descriptor }

    override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
        providers[implementationId]?.descriptor?.let(ChannelDescriptorResolution::Available)
            ?: ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
}

object BuiltInChannelImplementationIds {
    val JOURNAL = ChannelImplementationId("builtin:journal")
    val DEBUG = ChannelImplementationId("builtin:debug")
    val KEYBOARD = ChannelImplementationId("builtin:keyboard")
}

interface ChannelConfigurationCodec<T> {
    fun decode(payload: OpaqueJsonObject): Result<T>
    fun encode(value: T): OpaqueJsonObject
}

data class JournalProviderConfiguration(
    val baseDirectory: String?,
    val saveVoice: Boolean,
    val saveText: Boolean,
)

object JournalProviderConfigurationCodec : ChannelConfigurationCodec<JournalProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<JournalProviderConfiguration> = runCatching {
        val objectValue = payload.toJsonObject()
        val baseDirectory = objectValue.opt("baseDirectory").let { value ->
            when (value) {
                null, JSONObject.NULL -> null
                is String -> value
                else -> error("baseDirectory must be a string or null")
            }
        }
        val saveVoice = objectValue.requireBoolean("saveVoice")
        val saveText = objectValue.requireBoolean("saveText")
        JournalProviderConfiguration(baseDirectory, saveVoice, saveText)
    }

    override fun encode(value: JournalProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().apply {
            put("baseDirectory", value.baseDirectory ?: JSONObject.NULL)
            put("saveVoice", value.saveVoice)
            put("saveText", value.saveText)
        })
}

data class DebugProviderConfiguration(val mode: DebugMode)

object DebugProviderConfigurationCodec : ChannelConfigurationCodec<DebugProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<DebugProviderConfiguration> = runCatching {
        DebugProviderConfiguration(DebugMode.valueOf(payload.toJsonObject().requireString("mode")))
    }

    override fun encode(value: DebugProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().put("mode", value.mode.name))
}

data class KeyboardProviderConfiguration(val hostProfileKey: String)

object KeyboardProviderConfigurationCodec : ChannelConfigurationCodec<KeyboardProviderConfiguration> {
    override fun decode(payload: OpaqueJsonObject): Result<KeyboardProviderConfiguration> = runCatching {
        val key = payload.toJsonObject().requireString("hostProfile")
        require(key.split(":").let { it.size >= 2 && it.none(String::isBlank) }) {
            "hostProfile must use hostOs:layout[:variant]"
        }
        KeyboardProviderConfiguration(key)
    }

    override fun encode(value: KeyboardProviderConfiguration): OpaqueJsonObject =
        OpaqueJsonObject.fromJsonObject(JSONObject().put("hostProfile", value.hostProfileKey))
}

private abstract class VersionOneConfigurationProvider<T>(
    final override val implementationId: ChannelImplementationId,
    private val codec: ChannelConfigurationCodec<T>,
    private val additionalValidation: (T) -> String? = { null },
) : ChannelConfigurationProvider {
    final override val currentSchemaVersion = 1

    abstract override fun defaultPayload(): OpaqueJsonObject

    final override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult {
        if (schemaVersion != currentSchemaVersion) {
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(implementationId, schemaVersion, currentSchemaVersion),
            )
        }
        val decoded = codec.decode(payload).getOrElse { error ->
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(
                    implementationId,
                    schemaVersion,
                    error.message ?: "Malformed configuration payload",
                ),
            )
        }
        additionalValidation(decoded)?.let { detail ->
            return ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, detail),
            )
        }
        return ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    final override fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
        ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
    )
}

private object JournalConfigurationProvider : VersionOneConfigurationProvider<JournalProviderConfiguration>(
    BuiltInChannelImplementationIds.JOURNAL,
    JournalProviderConfigurationCodec,
    additionalValidation = { configuration ->
        if (configuration.saveVoice || configuration.saveText) null else "at least one output must be enabled"
    },
) {
    override fun defaultPayload(): OpaqueJsonObject =
        JournalProviderConfigurationCodec.encode(JournalProviderConfiguration(null, saveVoice = true, saveText = true))
}

private object DebugConfigurationProvider : VersionOneConfigurationProvider<DebugProviderConfiguration>(
    BuiltInChannelImplementationIds.DEBUG,
    DebugProviderConfigurationCodec,
) {
    override fun defaultPayload(): OpaqueJsonObject =
        DebugProviderConfigurationCodec.encode(DebugProviderConfiguration(DebugMode.ECHO))
}

private object KeyboardConfigurationProvider : VersionOneConfigurationProvider<KeyboardProviderConfiguration>(
    BuiltInChannelImplementationIds.KEYBOARD,
    KeyboardProviderConfigurationCodec,
) {
    override fun defaultPayload(): OpaqueJsonObject =
        KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration("linux:us"))
}

/** Built-in metadata is declarative; service composition supplies their runtime constructors. */
object BuiltInChannelDescriptors {
    val journal = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.JOURNAL,
        presentation = ChannelPresentationMetadata("Journal", "LOCAL LOG", "Requires valid journal configuration."),
        configuration = JournalConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.DirectoryField("baseDirectory", "Storage directory", required = false),
            ChannelConfigurationField.BooleanField("saveVoice", "Save voice recording"),
            ChannelConfigurationField.BooleanField("saveText", "Save transcript"),
        ),
        requiredCapabilities = setOf(ChannelCapability.Journal),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
    )

    val debug = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.DEBUG,
        presentation = ChannelPresentationMetadata("Debug Channel", "TEST", "Selected debug mode is unavailable."),
        configuration = DebugConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.ChoiceField(
                id = "mode",
                label = "Mode",
                choices = DebugMode.entries.map { mode ->
                    ChannelConfigurationField.ChoiceField.Choice(mode.name, mode.name)
                },
            ),
        ),
        requiredCapabilities = setOf(
            ChannelCapability.Transcription,
            ChannelCapability.Synthesis,
            ChannelCapability.AudioOperation,
        ),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
    )

    val keyboard = ChannelImplementationDescriptor(
        implementationId = BuiltInChannelImplementationIds.KEYBOARD,
        presentation = ChannelPresentationMetadata("Keyboard Channel", "BLE HID", "Requires active text-output availability."),
        configuration = KeyboardConfigurationProvider,
        configurationFields = listOf(
            ChannelConfigurationField.ChoiceField(
                id = "hostProfile",
                label = "Host profile",
                choices = listOf(
                    ChannelConfigurationField.ChoiceField.Choice("linux:us", "us [linux]"),
                ),
            ),
        ),
        requiredCapabilities = setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = true),
    )

    val all: List<ChannelImplementationDescriptor> = listOf(journal, debug, keyboard)

    val configurationResolver: ChannelImplementationDescriptorResolver = object : ChannelImplementationDescriptorResolver {
        private val descriptors = all.associateBy { it.implementationId }

        override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
            descriptors[implementationId]?.let(ChannelDescriptorResolution::Available)
                ?: ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
    }
}

sealed interface ChannelCatalogueProviderMigrationResult {
    data class Success(
        val snapshot: ChannelCatalogueSnapshot,
        val changed: Boolean,
    ) : ChannelCatalogueProviderMigrationResult

    /** The original snapshot is intentionally retained and must not be committed or published. */
    data class Failure(
        val definitionId: String,
        val error: ChannelProviderError,
    ) : ChannelCatalogueProviderMigrationResult
}

/**
 * Migrates every available provider as one in-memory transaction. Missing providers are left
 * byte-for-byte opaque at the payload boundary; any available-provider failure aborts all writes.
 */
object ChannelCatalogueProviderMigrator {
    fun migrate(
        snapshot: ChannelCatalogueSnapshot,
        resolver: ChannelImplementationDescriptorResolver,
    ): ChannelCatalogueProviderMigrationResult {
        var changed = false
        val migrated = snapshot.definitions.map { definition ->
            when (val resolution = resolver.resolveDescriptor(definition.implementationId)) {
                is ChannelDescriptorResolution.Missing -> definition
                is ChannelDescriptorResolution.Available -> when (
                    val result = resolution.descriptor.configuration.migrateAndValidate(
                        definition.configSchemaVersion,
                        definition.configPayload,
                    )
                ) {
                    is ProviderConfigurationResult.Failure -> {
                        return ChannelCatalogueProviderMigrationResult.Failure(definition.id, result.error)
                    }
                    is ProviderConfigurationResult.Success -> {
                        val configuration = result.configuration
                        if (configuration.schemaVersion != definition.configSchemaVersion ||
                            configuration.payload != definition.configPayload
                        ) {
                            changed = true
                            definition.copy(
                                configSchemaVersion = configuration.schemaVersion,
                                configPayload = configuration.payload,
                            )
                        } else {
                            definition
                        }
                    }
                }
            }
        }
        return ChannelCatalogueProviderMigrationResult.Success(
            snapshot.copy(definitions = migrated),
            changed,
        )
    }
}

private fun JSONObject.requireBoolean(name: String): Boolean =
    opt(name).takeIf { it is Boolean } as? Boolean ?: error("$name must be a boolean")

private fun JSONObject.requireString(name: String): String =
    opt(name).takeIf { it is String } as? String ?: error("$name must be a string")
