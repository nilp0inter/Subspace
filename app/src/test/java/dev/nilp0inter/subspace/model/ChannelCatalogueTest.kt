package dev.nilp0inter.subspace.model

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelCatalogueTest {
    @Test
    fun implementationIdRejectsBlankAndNonNamespacedReferences() {
        listOf("", "   ", "journal", ":journal", "builtin:", "Builtin:journal").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ChannelImplementationId(value)
            }
        }
    }

    @Test
    fun v2CodecRoundTripPreservesOpaqueUnknownFieldsAndDetachedPayloadViews() {
        val payload = opaque(
            """{"providerSetting":{"nested":[1,{"future":"kept"}]},"unrecognisedFlag":true}""",
        )
        val snapshot = ChannelCatalogueSnapshot(
            definitions = listOf(
                definition(
                    id = "external-1",
                    name = "External provider",
                    implementationId = ChannelImplementationId("external:provider"),
                    enabled = false,
                    payload = payload,
                ),
            ),
            activeChannelId = "external-1",
        )

        val escapedView = payload.toJsonObject()
        escapedView.getJSONObject("providerSetting").put("future", "mutated")
        escapedView.remove("unrecognisedFlag")

        val decoded = assertDecodeSuccess(ChannelCatalogueCodec.decode(ChannelCatalogueCodec.toJson(snapshot)))
        val restored = decoded.snapshot.definitions.single()

        assertEquals(2, decoded.sourceDocumentVersion)
        assertEquals(snapshot.activeChannelId, decoded.snapshot.activeChannelId)
        assertEquals(snapshot.definitions.single().implementationId, restored.implementationId)
        assertFalse(restored.enabled)
        assertTrue(restored.configPayload.toJsonObject().getBoolean("unrecognisedFlag"))
        assertEquals(
            "kept",
            restored.configPayload.toJsonObject()
                .getJSONObject("providerSetting")
                .getJSONArray("nested")
                .getJSONObject(1)
                .getString("future"),
        )
    }

    @Test
    fun sameProviderInstancesRemainIndependentWhenOneIsUpdatedByInstanceId() {
        val implementationId = ChannelImplementationId("test:repeatable")
        val first = definition(
            id = "repeatable-1",
            name = "First",
            implementationId = implementationId,
            payload = opaque("""{"setting":"first","unknown":1}"""),
        )
        val second = definition(
            id = "repeatable-2",
            name = "Second",
            implementationId = implementationId,
            payload = opaque("""{"setting":"second","unknown":2}"""),
        )
        val snapshot = ChannelCatalogueSnapshot(listOf(first, second), activeChannelId = first.id)

        val result = snapshot.updateChannel(second.id) {
            it.copy(configPayload = opaque("""{"setting":"updated","unknown":2}"""))
        }
        val updated = assertMutationSuccess(result)

        assertEquals(listOf(first.id, second.id), updated.definitions.map(ChannelDefinition::id))
        assertEquals(first.id, updated.activeChannelId)
        assertEquals(first, updated.definitions.first())
        assertEquals("updated", updated.definitions[1].configPayload.toJsonObject().getString("setting"))
        assertEquals(2, updated.definitions[1].configPayload.toJsonObject().getInt("unknown"))
    }

    @Test
    fun migratorLeavesMissingProviderDefinitionByteForByteUnchanged() {
        val missing = definition(
            id = "retained",
            name = "Unavailable provider",
            implementationId = ChannelImplementationId("removed:provider"),
            enabled = false,
            schemaVersion = 7,
            payload = opaque("""{"future":{"field":"value"}}"""),
        )
        val snapshot = ChannelCatalogueSnapshot(listOf(missing), missing.id)

        val result = ChannelCatalogueProviderMigrator.migrate(
            snapshot,
            BuiltInChannelDescriptors.configurationResolver,
        )

        val migration = assertProviderMigrationSuccess(result)
        assertFalse(migration.changed)
        assertEquals(snapshot, migration.snapshot)
        assertEquals(
            "value",
            migration.snapshot.definitions.single().configPayload.toJsonObject()
                .getJSONObject("future")
                .getString("field"),
        )
    }

    @Test
    fun v1MigrationBacksUpOriginalAndPreservesBuiltInDefinitions() = withTemporaryCatalogue { file ->
        val v1 = """
            {
              "version": 1,
              "activeChannelId": "legacy-debug",
              "definitions": [
                {
                  "id": "legacy-journal",
                  "name": "Field journal",
                  "kind": "JOURNAL",
                  "enabled": false,
                  "configSchemaVersion": 1,
                  "config": {
                    "baseDirectory": "/records",
                    "saveVoice": false,
                    "saveText": true,
                    "futureJournal": "preserve"
                  }
                },
                {
                  "id": "legacy-debug",
                  "name": "Speech debug",
                  "kind": "DEBUG",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {
                    "mode": "STT",
                    "futureDebug": 42
                  }
                },
                {
                  "id": "legacy-keyboard",
                  "name": "Desk keyboard",
                  "kind": "KEYBOARD",
                  "enabled": true,
                  "configSchemaVersion": 1,
                  "config": {
                    "hostProfile": "macos:us",
                    "futureKeyboard": { "layout": "dvorak" }
                  }
                }
              ]
            }
        """.trimIndent()
        file.writeText(v1)

        val repository = ChannelRepository(
            FakeSharedPreferences(),
            file,
            BuiltInChannelDescriptors.configurationResolver,
        )
        val snapshot = repository.catalogueState.value
        val journal = snapshot.definitions[0]
        val debug = snapshot.definitions[1]
        val keyboard = snapshot.definitions[2]
        val backup = File(file.parentFile, "${file.name}.v1.bak")

        assertEquals(listOf("legacy-journal", "legacy-debug", "legacy-keyboard"), snapshot.definitions.map(ChannelDefinition::id))
        assertEquals("legacy-debug", snapshot.activeChannelId)
        assertEquals("Field journal", journal.name)
        assertFalse(journal.enabled)
        assertEquals(BuiltInChannelImplementationIds.JOURNAL, journal.implementationId)
        assertEquals("/records", journal.configPayload.toJsonObject().getString("baseDirectory"))
        assertFalse(journal.configPayload.toJsonObject().getBoolean("saveVoice"))
        assertTrue(journal.configPayload.toJsonObject().getBoolean("saveText"))
        assertEquals("preserve", journal.configPayload.toJsonObject().getString("futureJournal"))
        assertEquals("Speech debug", debug.name)
        assertEquals(BuiltInChannelImplementationIds.DEBUG, debug.implementationId)
        assertEquals("STT", debug.configPayload.toJsonObject().getString("mode"))
        assertEquals(42, debug.configPayload.toJsonObject().getInt("futureDebug"))
        assertEquals("Desk keyboard", keyboard.name)
        assertEquals(BuiltInChannelImplementationIds.KEYBOARD, keyboard.implementationId)
        assertEquals("macos:us", keyboard.configPayload.toJsonObject().getString("hostProfile"))
        assertEquals(
            "dvorak",
            keyboard.configPayload.toJsonObject().getJSONObject("futureKeyboard").getString("layout"),
        )
        assertEquals(v1, backup.readText())
        assertEquals(2, JSONObject(file.readText()).getInt("version"))
    }

    @Test
    fun existingV2CatalogueIsSoleSourceAndDoesNotMergeLegacyPreferences() = withTemporaryCatalogue { file ->
        val persisted = ChannelCatalogueSnapshot(
            definitions = listOf(
                definition(
                    id = "persisted-external",
                    name = "Persisted unavailable provider",
                    implementationId = ChannelImplementationId("external:persisted"),
                    enabled = false,
                    schemaVersion = 4,
                    payload = opaque("""{"preserved":"payload"}"""),
                ),
            ),
            activeChannelId = "persisted-external",
        )
        val v2 = ChannelCatalogueCodec.toJson(persisted)
        file.writeText(v2)
        val preferences = FakeSharedPreferences().apply {
            edit()
                .putString("active_channel_id", "legacy-debug")
                .putString("journal_base_directory", "/legacy-must-not-merge")
                .putString("debug_channel_mode", "STT")
                .commit()
        }

        val repository = ChannelRepository(
            preferences,
            file,
            BuiltInChannelDescriptors.configurationResolver,
        )

        assertEquals(persisted, repository.catalogueState.value)
        assertEquals(v2, file.readText())
    }

    @Test
    fun providerMigrationFailureLeavesPersistedV2DocumentUnchanged() = withTemporaryCatalogue { file ->
        val provider = FailingMigrationProvider(ChannelImplementationId("test:migration"))
        val original = ChannelCatalogueSnapshot(
            definitions = listOf(
                definition(
                    id = "would-migrate",
                    name = "Would migrate",
                    implementationId = provider.descriptor.implementationId,
                    schemaVersion = 1,
                    payload = opaque("""{"step":1,"required":"ok","unknown":"keep"}"""),
                ),
                definition(
                    id = "invalid-after-first",
                    name = "Invalid",
                    implementationId = provider.descriptor.implementationId,
                    schemaVersion = 2,
                    payload = opaque("""{"step":2,"required":"invalid","unknown":"keep"}"""),
                ),
            ),
            activeChannelId = "would-migrate",
        )
        val v2 = ChannelCatalogueCodec.toJson(original)
        file.writeText(v2)

        val exception = assertThrows(ChannelRepositoryLoadException::class.java) {
            ChannelRepository(FakeSharedPreferences(), file, resolverFor(provider))
        }

        val error = exception.error as ChannelRepositoryError.ProviderMigration
        assertEquals("invalid-after-first", error.definitionId)
        assertTrue(error.error is ChannelProviderError.InvalidConfiguration)
        assertEquals(v2, file.readText())
    }

    @Test
    fun authoritativeCustomProviderResolverMigratesLoadedAndMutatedDefinitions() = withTemporaryCatalogue { file ->
        val provider = MigratingCustomProvider(ChannelImplementationId("test:authoritative"))
        val registry = ChannelImplementationProviderRegistry()
        assertEquals(ChannelProviderRegistrationResult.Registered, registry.register(provider))
        val loaded = definition(
            id = "loaded",
            name = "Loaded custom channel",
            implementationId = provider.descriptor.implementationId,
            schemaVersion = 1,
            payload = opaque("""{"stage":1,"token":"loaded","extension":"retain"}"""),
        )
        file.writeText(ChannelCatalogueCodec.toJson(ChannelCatalogueSnapshot(listOf(loaded), loaded.id)))

        val repository = ChannelRepository(FakeSharedPreferences(), file, registry)
        val added = definition(
            id = "added",
            name = "Added custom channel",
            implementationId = provider.descriptor.implementationId,
            schemaVersion = 1,
            payload = opaque("""{"stage":1,"token":"added"}"""),
        )

        assertEquals(ChannelRepositoryMutationResult.Success, repository.addChannel(added))
        assertEquals(
            ChannelRepositoryMutationResult.Success,
            repository.updateChannel("loaded") {
                it.copy(
                    configSchemaVersion = 1,
                    configPayload = opaque("""{"stage":1,"token":"updated","extension":"retain"}"""),
                )
            },
        )

        val persisted = assertDecodeSuccess(ChannelCatalogueCodec.decode(file.readText())).snapshot
        assertEquals(listOf("loaded", "added"), persisted.definitions.map(ChannelDefinition::id))
        assertEquals(listOf(2, 2), persisted.definitions.map(ChannelDefinition::configSchemaVersion))
        assertEquals(
            listOf("updated", "added"),
            persisted.definitions.map { it.configPayload.toJsonObject().getString("token") },
        )
        assertEquals("retain", persisted.definitions.first().configPayload.toJsonObject().getString("extension"))
    }

    @Test
    fun storageFailureDoesNotReplaceDirectoryTargetOrLeaveTemporaryDocument() = withTemporaryCatalogue { file ->
        file.mkdirs()
        val snapshot = ChannelCatalogueSnapshot(
            listOf(
                definition(
                    id = "stored",
                    name = "Stored",
                    implementationId = ChannelImplementationId("test:storage"),
                    payload = opaque("""{"value":"valid"}"""),
                ),
            ),
            activeChannelId = "stored",
        )

        val result = ChannelCatalogueFileStore(file).save(snapshot)

        assertTrue(result is ChannelCatalogueFileStoreResult.Failure)
        assertEquals("save catalogue", (result as ChannelCatalogueFileStoreResult.Failure).operation)
        assertTrue(file.isDirectory)
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    private fun definition(
        id: String,
        name: String,
        implementationId: ChannelImplementationId,
        enabled: Boolean = true,
        schemaVersion: Int = 1,
        payload: OpaqueJsonObject,
    ) = ChannelDefinition(id, name, implementationId, enabled, schemaVersion, payload)

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private fun assertDecodeSuccess(result: ChannelCatalogueDecodeResult): DecodedChannelCatalogue =
        (result as? ChannelCatalogueDecodeResult.Success)?.document
            ?: throw AssertionError("Expected catalogue decoding success, got $result")

    private fun assertMutationSuccess(result: ChannelCatalogueMutationResult): ChannelCatalogueSnapshot =
        (result as? ChannelCatalogueMutationResult.Success)?.snapshot
            ?: throw AssertionError("Expected catalogue mutation success, got $result")

    private fun assertProviderMigrationSuccess(
        result: ChannelCatalogueProviderMigrationResult,
    ): ChannelCatalogueProviderMigrationResult.Success =
        result as? ChannelCatalogueProviderMigrationResult.Success
            ?: throw AssertionError("Expected provider migration success, got $result")

    private fun resolverFor(provider: ChannelImplementationProvider): ChannelImplementationDescriptorResolver =
        object : ChannelImplementationDescriptorResolver {
            override fun resolveDescriptor(implementationId: ChannelImplementationId): ChannelDescriptorResolution =
                if (implementationId == provider.descriptor.implementationId) {
                    ChannelDescriptorResolution.Available(provider.descriptor)
                } else {
                    ChannelDescriptorResolution.Missing(ChannelProviderError.MissingProvider(implementationId))
                }
        }

    private inline fun withTemporaryCatalogue(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("channel-catalogue-test").toFile()
        try {
            block(File(directory, "catalogue.json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FailingMigrationProvider(
        implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        private val configuration = object : ChannelConfigurationProvider {
            override val implementationId = implementationId
            override val currentSchemaVersion = 2

            override fun defaultPayload(): OpaqueJsonObject =
                OpaqueJsonObject.parse("""{"step":2,"required":"ok"}""").getOrThrow()

            override fun validate(
                schemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ProviderConfigurationResult {
                if (schemaVersion != currentSchemaVersion) {
                    return ProviderConfigurationResult.Failure(
                        ChannelProviderError.UnsupportedSchemaVersion(
                            implementationId,
                            schemaVersion,
                            currentSchemaVersion,
                        ),
                    )
                }
                return if (payload.toJsonObject().optString("required") == "ok") {
                    ProviderConfigurationResult.Success(
                        ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
                    )
                } else {
                    ProviderConfigurationResult.Failure(
                        ChannelProviderError.InvalidConfiguration(
                            implementationId,
                            schemaVersion,
                            "required must be ok",
                        ),
                    )
                }
            }

            override fun migrateStep(
                fromSchemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ChannelConfigurationMigrationStep = when (fromSchemaVersion) {
                1 -> ChannelConfigurationMigrationStep.Success(
                    OpaqueJsonObject.fromJsonObject(payload.toJsonObject().put("step", 2)),
                )
                else -> ChannelConfigurationMigrationStep.Failure(
                    ChannelProviderError.UnsupportedSchemaVersion(
                        implementationId,
                        fromSchemaVersion,
                        currentSchemaVersion,
                    ),
                )
            }
        }

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Test", "TEST", "Unavailable"),
            configuration = configuration,
            configurationFields = listOf(ChannelConfigurationField.TextField("required", "Required")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside catalogue migration coverage",
            ),
        )
    }

    private class MigratingCustomProvider(
        implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        private val configuration = object : ChannelConfigurationProvider {
            override val implementationId = implementationId
            override val currentSchemaVersion = 2

            override fun defaultPayload(): OpaqueJsonObject =
                OpaqueJsonObject.parse("""{"stage":2,"token":"required"}""").getOrThrow()

            override fun validate(
                schemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ProviderConfigurationResult {
                val objectValue = payload.toJsonObject()
                return if (
                    schemaVersion == currentSchemaVersion &&
                    objectValue.optInt("stage") == currentSchemaVersion &&
                    objectValue.optString("token").isNotBlank()
                ) {
                    ProviderConfigurationResult.Success(
                        ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
                    )
                } else {
                    ProviderConfigurationResult.Failure(
                        ChannelProviderError.InvalidConfiguration(
                            implementationId,
                            schemaVersion,
                            "stage must be current and token must be present",
                        ),
                    )
                }
            }

            override fun migrateStep(
                fromSchemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ChannelConfigurationMigrationStep = when (fromSchemaVersion) {
                1 -> ChannelConfigurationMigrationStep.Success(
                    OpaqueJsonObject.fromJsonObject(payload.toJsonObject().put("stage", 2)),
                )
                else -> ChannelConfigurationMigrationStep.Failure(
                    ChannelProviderError.UnsupportedSchemaVersion(
                        implementationId,
                        fromSchemaVersion,
                        currentSchemaVersion,
                    ),
                )
            }
        }

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Custom", "TEST", "Unavailable"),
            configuration = configuration,
            configurationFields = listOf(ChannelConfigurationField.TextField("token", "Token")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside catalogue migration coverage",
            ),
        )
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
