package dev.nilp0inter.subspace.model

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ChannelRepositoryError {
    val message: String

    data class Decode(val error: ChannelCatalogueDecodeError) : ChannelRepositoryError {
        override val message = error.message
    }

    data class ProviderMigration(
        val definitionId: String,
        val error: ChannelProviderError,
    ) : ChannelRepositoryError {
        override val message = "Could not migrate channel $definitionId: ${error.message}"
    }

    data class Storage(val operation: String, val cause: IOException) : ChannelRepositoryError {
        override val message = "Could not $operation: ${cause.message}"
    }

    data class Mutation(val error: ChannelCatalogueError) : ChannelRepositoryError {
        override val message = error.message
    }
}

sealed interface ChannelRepositoryMutationResult {
    data object Success : ChannelRepositoryMutationResult
    data class Failure(val error: ChannelRepositoryError) : ChannelRepositoryMutationResult
}

class ChannelRepositoryLoadException(val error: ChannelRepositoryError) : IllegalStateException(error.message)

class ChannelRepository(
    private val prefs: SharedPreferences,
    private val catalogueFile: File,
    private val descriptorResolver: ChannelImplementationDescriptorResolver,
) {
    constructor(
        context: Context,
        descriptorResolver: ChannelImplementationDescriptorResolver,
    ) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        File(context.filesDir, "channels_catalogue.json"),
        descriptorResolver,
    )

    constructor(
        prefs: SharedPreferences,
        descriptorResolver: ChannelImplementationDescriptorResolver,
    ) : this(
        prefs,
        File(System.getProperty("java.io.tmpdir"), "channels_catalogue_${System.nanoTime()}.json")
            .apply { deleteOnExit() },
        descriptorResolver,
    )

    private val fileStore = ChannelCatalogueFileStore(catalogueFile)
    private val _catalogueState: MutableStateFlow<ChannelCatalogueSnapshot>
    private val mutationLock = Any()

    val catalogueState: StateFlow<ChannelCatalogueSnapshot>
        get() = _catalogueState.asStateFlow()

    init {
        val initial = when (val loaded = fileStore.load()) {
            null -> seedFromLegacyPreferences().also(::saveOrThrow)
            is ChannelCatalogueLoadResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Decode(loaded.error),
            )
            is ChannelCatalogueLoadResult.Success -> migrateLoadedDocument(loaded.document)
        }
        _catalogueState = MutableStateFlow(initial)
    }

    private fun migrateLoadedDocument(document: DecodedChannelCatalogue): ChannelCatalogueSnapshot {
        val migration = ChannelCatalogueProviderMigrator.migrate(document.snapshot, descriptorResolver)
        val migrated = when (migration) {
            is ChannelCatalogueProviderMigrationResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.ProviderMigration(migration.definitionId, migration.error),
            )
            is ChannelCatalogueProviderMigrationResult.Success -> migration
        }
        if (document.sourceDocumentVersion == 1) {
            backupLegacyV1OrThrow()
            saveOrThrow(migrated.snapshot)
        } else if (migrated.changed) {
            saveOrThrow(migrated.snapshot)
        }
        return migrated.snapshot
    }

    private fun seedFromLegacyPreferences(): ChannelCatalogueSnapshot {
        val journalSaveVoice = prefs.getBoolean(KEY_SAVE_VOICE, true)
        val journalSaveText = prefs.getBoolean(KEY_SAVE_TEXT, true)
        val journalDefinition = ChannelDefinition(
            id = LegacyPreferenceCatalogueSeed.JOURNAL_ID,
            name = LegacyPreferenceCatalogueSeed.JOURNAL_NAME,
            implementationId = BuiltInChannelImplementationIds.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = JournalProviderConfigurationCodec.encode(
                JournalProviderConfiguration(
                    baseDirectory = prefs.getString(KEY_BASE_DIRECTORY, null),
                    saveVoice = journalSaveVoice || !journalSaveText,
                    saveText = journalSaveText,
                ),
            ),
        )
        val debugMode = prefs.getString(KEY_DEBUG_MODE, DebugMode.ECHO.name)
            ?.let { value -> runCatching { DebugMode.valueOf(value) }.getOrNull() }
            ?: DebugMode.ECHO
        val debugDefinition = ChannelDefinition(
            id = LegacyPreferenceCatalogueSeed.DEBUG_ID,
            name = LegacyPreferenceCatalogueSeed.DEBUG_NAME,
            implementationId = BuiltInChannelImplementationIds.DEBUG,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = DebugProviderConfigurationCodec.encode(DebugProviderConfiguration(debugMode)),
        )
        val hostProfileKey = prefs.getString(KEY_KEYBOARD_HOST_PROFILE, "linux:us") ?: "linux:us"
        val keyboardDefinition = ChannelDefinition(
            id = LegacyPreferenceCatalogueSeed.KEYBOARD_ID,
            name = LegacyPreferenceCatalogueSeed.KEYBOARD_NAME,
            implementationId = BuiltInChannelImplementationIds.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = KeyboardProviderConfigurationCodec.encode(
                KeyboardProviderConfiguration(hostProfileKey.takeIf(::isHostProfileKey) ?: "linux:us"),
            ),
        )
        val definitions = listOf(journalDefinition, debugDefinition, keyboardDefinition)
        val preferredActiveId = prefs.getString(KEY_ACTIVE_CHANNEL, LegacyPreferenceCatalogueSeed.JOURNAL_ID)
        return ChannelCatalogueSnapshot(
            definitions = definitions,
            activeChannelId = preferredActiveId.takeIf { id -> definitions.any { it.id == id } }
                ?: LegacyPreferenceCatalogueSeed.JOURNAL_ID,
        )
    }

    fun selectChannel(id: String): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.selectChannel(id))
    }

    fun addChannel(definition: ChannelDefinition): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        when (val migrated = migrateProviderDefinition(definition)) {
            is ChannelDefinitionMigrationResult.Failure -> ChannelRepositoryMutationResult.Failure(migrated.error)
            is ChannelDefinitionMigrationResult.Success -> commit(_catalogueState.value.addChannel(migrated.definition))
        }
    }

    fun updateChannel(
        id: String,
        transform: (ChannelDefinition) -> ChannelDefinition,
    ): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        val current = _catalogueState.value
        val currentDefinition = current.definitions.find { it.id == id }
            ?: return@synchronized ChannelRepositoryMutationResult.Failure(
                ChannelRepositoryError.Mutation(ChannelCatalogueError.UnknownChannelId(id)),
            )
        val replacement = transform(currentDefinition)
        if (replacement.id != id) return@synchronized commit(current.updateChannel(id) { replacement })
        when (val migrated = migrateProviderDefinition(replacement)) {
            is ChannelDefinitionMigrationResult.Failure -> ChannelRepositoryMutationResult.Failure(migrated.error)
            is ChannelDefinitionMigrationResult.Success -> commit(current.updateChannel(id) { migrated.definition })
        }
    }

    fun moveChannel(id: String, toIndex: Int): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.moveChannel(id, toIndex))
    }

    fun removeChannel(id: String): ChannelRepositoryMutationResult = synchronized(mutationLock) {
        commit(_catalogueState.value.removeChannel(id))
    }

    private sealed interface ChannelDefinitionMigrationResult {
        data class Success(val definition: ChannelDefinition) : ChannelDefinitionMigrationResult
        data class Failure(val error: ChannelRepositoryError) : ChannelDefinitionMigrationResult
    }

    private fun migrateProviderDefinition(definition: ChannelDefinition): ChannelDefinitionMigrationResult = when (
        val resolution = descriptorResolver.resolveDescriptor(definition.implementationId)
    ) {
        is ChannelDescriptorResolution.Missing -> ChannelDefinitionMigrationResult.Failure(
            ChannelRepositoryError.ProviderMigration(definition.id, resolution.error),
        )
        is ChannelDescriptorResolution.Available -> when (
            val result = resolution.descriptor.configuration.migrateAndValidate(
                definition.configSchemaVersion,
                definition.configPayload,
            )
        ) {
            is ProviderConfigurationResult.Failure -> ChannelDefinitionMigrationResult.Failure(
                ChannelRepositoryError.ProviderMigration(definition.id, result.error),
            )
            is ProviderConfigurationResult.Success -> ChannelDefinitionMigrationResult.Success(
                definition.copy(
                    configSchemaVersion = result.configuration.schemaVersion,
                    configPayload = result.configuration.payload,
                ),
            )
        }
    }

    private fun commit(result: ChannelCatalogueMutationResult): ChannelRepositoryMutationResult = when (result) {
        is ChannelCatalogueMutationResult.Failure ->
            ChannelRepositoryMutationResult.Failure(ChannelRepositoryError.Mutation(result.error))
        is ChannelCatalogueMutationResult.Success -> {
            when (val stored = fileStore.save(result.snapshot)) {
                ChannelCatalogueFileStoreResult.Success -> {
                    _catalogueState.value = result.snapshot
                    ChannelRepositoryMutationResult.Success
                }
                is ChannelCatalogueFileStoreResult.Failure -> ChannelRepositoryMutationResult.Failure(
                    ChannelRepositoryError.Storage(stored.operation, stored.cause),
                )
            }
        }
    }

    private fun saveOrThrow(snapshot: ChannelCatalogueSnapshot) {
        when (val stored = fileStore.save(snapshot)) {
            ChannelCatalogueFileStoreResult.Success -> Unit
            is ChannelCatalogueFileStoreResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Storage(stored.operation, stored.cause),
            )
        }
    }

    private fun backupLegacyV1OrThrow() {
        when (val backup = fileStore.backupLegacyV1()) {
            ChannelCatalogueFileStoreResult.Success -> Unit
            is ChannelCatalogueFileStoreResult.Failure -> throw ChannelRepositoryLoadException(
                ChannelRepositoryError.Storage(backup.operation, backup.cause),
            )
        }
    }

    companion object {
        /** Stable v1 preference-derived identities used only when no catalogue file exists. */
        private object LegacyPreferenceCatalogueSeed {
            const val JOURNAL_ID = "captains-log"
            const val JOURNAL_NAME = "Journal"
            const val DEBUG_ID = "debug-channel"
            const val DEBUG_NAME = "Debug Channel"
            const val KEYBOARD_ID = "keyboard-channel"
            const val KEYBOARD_NAME = "Keyboard Channel"
        }

        private const val PREFS_NAME = "channels"
        private const val KEY_BASE_DIRECTORY = "journal_base_directory"
        private const val KEY_SAVE_VOICE = "journal_save_voice"
        private const val KEY_SAVE_TEXT = "journal_save_text"
        private const val KEY_DEBUG_MODE = "debug_channel_mode"
        private const val KEY_ACTIVE_CHANNEL = "active_channel_id"
        private const val KEY_KEYBOARD_HOST_PROFILE = "keyboard_host_profile"

        private fun isHostProfileKey(key: String): Boolean =
            key.split(":").let { parts -> parts.size >= 2 && parts.none(String::isBlank) }
    }
}
