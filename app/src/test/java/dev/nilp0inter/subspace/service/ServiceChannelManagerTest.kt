package dev.nilp0inter.subspace.service

import android.content.SharedPreferences
import dev.nilp0inter.subspace.model.ChannelCatalogueCodec
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelConfigurationMigrationStep
import dev.nilp0inter.subspace.model.ChannelConfigurationProvider
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelPreparationTraits
import dev.nilp0inter.subspace.model.ChannelPresentationMetadata
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult
import dev.nilp0inter.subspace.model.ChannelRepository
import dev.nilp0inter.subspace.model.ChannelRepositoryError
import dev.nilp0inter.subspace.model.ChannelRepositoryMutationResult
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceChannelManagerTest {
    @Test
    fun `explicit create persists the requested channel payload unchanged`() = withFixture { fixture ->
        val payload = opaque("""{"mode":"explicit","future":{"kept":true}}""")

        val result = fixture.manager.createChannel(TEST_IMPLEMENTATION_ID, "Explicit", payload)

        assertEquals(ChannelRepositoryMutationResult.Success, result)
        assertEquals(
            ChannelDefinition(
                id = CREATED_ID,
                name = "Explicit",
                implementationId = TEST_IMPLEMENTATION_ID,
                enabled = true,
                configSchemaVersion = TestProvider.SCHEMA_VERSION,
                configPayload = payload,
            ),
            fixture.repository.catalogueState.value.definitions.last(),
        )
    }

    @Test
    fun `create without payload persists the provider supplied payload`() = withFixture { fixture ->
        val result = fixture.manager.createChannel(TEST_IMPLEMENTATION_ID, "Provider configured")

        assertEquals(ChannelRepositoryMutationResult.Success, result)
        assertEquals(
            fixture.providerDefaultPayload,
            fixture.repository.catalogueState.value.definitions.last().configPayload,
        )
    }

    @Test
    fun `create for an unregistered provider leaves the catalogue unchanged`() = withFixture(
        managerHasProvider = false,
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.createChannel(MISSING_IMPLEMENTATION_ID, "Unavailable", opaque("""{"mode":"new"}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals("new", failure.definitionId)
        assertEquals(MISSING_IMPLEMENTATION_ID, failure.error.implementationId)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `configuration update for an unregistered provider leaves the channel unchanged`() = withFixture(
        managerHasProvider = false,
    ) { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"mode":"changed"}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals(PRIMARY_ID, failure.definitionId)
        assertEquals(TEST_IMPLEMENTATION_ID, failure.error.implementationId)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `rejected configuration update leaves the existing definition unchanged`() = withFixture { fixture ->
        val before = fixture.repository.catalogueState.value

        val result = fixture.manager.updateChannelConfiguration(PRIMARY_ID, opaque("""{"reject":true}"""))

        val failure = assertProviderMigrationFailure(result)
        assertEquals(PRIMARY_ID, failure.definitionId)
        assertTrue(failure.error is ChannelProviderError.InvalidConfiguration)
        assertEquals(before, fixture.repository.catalogueState.value)
    }

    @Test
    fun `successful selection notifies both playback paths before recording its complete diagnostic`() {
        val events = mutableListOf<String>()
        withFixture(
            definitions = listOf(primaryDefinition(), secondaryDefinition()),
            immediateSelection = { events += "immediate:$it" },
            deferredSelection = { events += "deferred:$it" },
            log = { events += "log:$it" },
        ) { fixture ->
            assertTrue(fixture.manager.selectChannel(SECONDARY_ID))

            assertEquals(
                listOf(
                    "immediate:$SECONDARY_ID",
                    "deferred:$SECONDARY_ID",
                    "log:CHANNEL_SELECT requested=$SECONDARY_ID previous=$PRIMARY_ID selected=true active=$SECONDARY_ID",
                ),
                events,
            )
        }
    }

    @Test
    fun `failed selection does not notify either playback path`() {
        val immediateSelections = mutableListOf<String>()
        val deferredSelections = mutableListOf<String>()
        withFixture(
            immediateSelection = { immediateSelections += it },
            deferredSelection = { deferredSelections += it },
        ) { fixture ->
            assertFalse(fixture.manager.selectChannel("absent"))

            assertTrue(immediateSelections.isEmpty())
            assertTrue(deferredSelections.isEmpty())
        }
    }

    private fun withFixture(
        managerHasProvider: Boolean = true,
        definitions: List<ChannelDefinition> = listOf(primaryDefinition()),
        immediateSelection: (String) -> Unit = {},
        deferredSelection: (String) -> Unit = {},
        log: (String) -> Unit = {},
        block: (Fixture) -> Unit,
    ) {
        val provider = TestProvider(TEST_IMPLEMENTATION_ID, PROVIDER_DEFAULT_PAYLOAD)
        val repositoryRegistry = ChannelImplementationProviderRegistry().apply {
            assertEquals(ChannelProviderRegistrationResult.Registered, register(provider))
        }
        val managerRegistry = ChannelImplementationProviderRegistry().apply {
            if (managerHasProvider) {
                assertEquals(ChannelProviderRegistrationResult.Registered, register(provider))
            }
        }
        val catalogueFile = File.createTempFile("service-channel-manager", ".json")
        catalogueFile.writeText(
            ChannelCatalogueCodec.toJson(ChannelCatalogueSnapshot(definitions, PRIMARY_ID)),
        )

        try {
            val repository = ChannelRepository(InMemorySharedPreferences(), catalogueFile, repositoryRegistry)
            val manager = ServiceChannelManager(
                channelRepository = repository,
                providerRegistry = managerRegistry,
                immediateSelection = immediateSelection,
                deferredSelection = deferredSelection,
                newChannelId = { CREATED_ID },
                log = log,
            )
            block(Fixture(repository, manager, PROVIDER_DEFAULT_PAYLOAD))
        } finally {
            catalogueFile.delete()
        }
    }

    private fun assertProviderMigrationFailure(
        result: ChannelRepositoryMutationResult,
    ): ChannelRepositoryError.ProviderMigration =
        ((result as? ChannelRepositoryMutationResult.Failure)?.error as? ChannelRepositoryError.ProviderMigration)
            ?: throw AssertionError("Expected provider migration failure, got $result")

    private data class Fixture(
        val repository: ChannelRepository,
        val manager: ServiceChannelManager,
        val providerDefaultPayload: OpaqueJsonObject,
    )

    private class TestProvider(
        implementationId: ChannelImplementationId,
        private val providerDefaultPayload: OpaqueJsonObject,
    ) : ChannelImplementationProvider {
        private val configuration = object : ChannelConfigurationProvider {
            override val implementationId = implementationId
            override val currentSchemaVersion = SCHEMA_VERSION

            override fun defaultPayload(): OpaqueJsonObject = providerDefaultPayload

            override fun validate(
                schemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ProviderConfigurationResult = when {
                schemaVersion != currentSchemaVersion -> ProviderConfigurationResult.Failure(
                    ChannelProviderError.UnsupportedSchemaVersion(
                        implementationId,
                        schemaVersion,
                        currentSchemaVersion,
                    ),
                )
                payload.toJsonObject().optBoolean("reject") -> ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId,
                        schemaVersion,
                        "reject is reserved for the failure case",
                    ),
                )
                else -> ProviderConfigurationResult.Success(
                    ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
                )
            }

            override fun migrateStep(
                fromSchemaVersion: Int,
                payload: OpaqueJsonObject,
            ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(
                    implementationId,
                    fromSchemaVersion,
                    currentSchemaVersion,
                ),
            )
        }

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Test", "TEST", "Unavailable"),
            configuration = configuration,
            configurationFields = listOf(ChannelConfigurationField.TextField("mode", "Mode")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside channel management coverage",
            ),
        )

        companion object {
            const val SCHEMA_VERSION = 7
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }

    private companion object {
        val TEST_IMPLEMENTATION_ID = ChannelImplementationId("test:managed")
        val MISSING_IMPLEMENTATION_ID = ChannelImplementationId("test:missing")
        const val PRIMARY_ID = "primary"
        const val SECONDARY_ID = "secondary"
        const val CREATED_ID = "created"
        val PROVIDER_DEFAULT_PAYLOAD = opaque("""{"mode":"provider-default"}""")

        fun primaryDefinition(): ChannelDefinition = ChannelDefinition(
            id = PRIMARY_ID,
            name = "Primary",
            implementationId = TEST_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = TestProvider.SCHEMA_VERSION,
            configPayload = opaque("""{"mode":"primary"}"""),
        )

        fun secondaryDefinition(): ChannelDefinition = ChannelDefinition(
            id = SECONDARY_ID,
            name = "Secondary",
            implementationId = TEST_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = TestProvider.SCHEMA_VERSION,
            configPayload = opaque("""{"mode":"secondary"}"""),
        )

        fun opaque(encoded: String): OpaqueJsonObject = OpaqueJsonObject.parse(encoded).getOrThrow()
    }
}
