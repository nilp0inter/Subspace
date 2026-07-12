package dev.nilp0inter.subspace.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelImplementationProviderTest {
    @Test
    fun registryKeepsOriginalProviderAndRegistrationOrderAfterDuplicateRejection() {
        val registry = ChannelImplementationProviderRegistry()
        val first = TestProvider(ChannelImplementationId("test:first"))
        val second = TestProvider(ChannelImplementationId("test:second"))
        val duplicate = TestProvider(first.descriptor.implementationId)

        assertEquals(ChannelProviderRegistrationResult.Registered, registry.register(first))
        assertEquals(ChannelProviderRegistrationResult.Registered, registry.register(second))
        val rejected = registry.register(duplicate)

        assertTrue(rejected is ChannelProviderRegistrationResult.Rejected)
        assertTrue((rejected as ChannelProviderRegistrationResult.Rejected).error is ChannelProviderError.DuplicateRegistration)
        assertEquals(
            listOf(first.descriptor.implementationId, second.descriptor.implementationId),
            registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
        )
        val resolution = registry.resolve(first.descriptor.implementationId)
        assertSame(first, (resolution as ChannelProviderResolution.Available).provider)
    }

    @Test
    fun registryReportsMissingProviderWithoutChoosingAnotherDescriptor() {
        val registry = ChannelImplementationProviderRegistry()
        val registered = TestProvider(ChannelImplementationId("test:registered"))
        val missingId = ChannelImplementationId("test:missing")
        registry.register(registered)

        val resolution = registry.resolve(missingId)

        assertTrue(resolution is ChannelProviderResolution.Missing)
        assertEquals(
            missingId,
            (resolution as ChannelProviderResolution.Missing).error.implementationId,
        )
    }

    @Test
    fun testOnlyProviderMigratesConfigurationOneSchemaStepAtATimeWithoutDroppingUnknownFields() {
        val provider = TestProvider(ChannelImplementationId("test:extensible"))
        val payload = opaque("""{"stage":1,"required":"ok","future":{"retained":true}}""")

        val result = provider.configuration.migrateAndValidate(schemaVersion = 1, payload = payload)

        val configuration = assertConfigurationSuccess(result)
        assertEquals(listOf(1, 2), provider.configuration.migratedFrom)
        assertEquals(provider.configuration.currentSchemaVersion, configuration.schemaVersion)
        assertEquals(3, configuration.payload.toJsonObject().getInt("stage"))
        assertTrue(
            configuration.payload.toJsonObject()
                .getJSONObject("future")
                .getBoolean("retained"),
        )
    }

    @Test
    fun descriptorSchemaValidationRejectsInvalidCurrentVersionPayloadWithTypedProviderError() {
        val provider = TestProvider(ChannelImplementationId("test:validation"))

        val result = provider.descriptor.configuration.migrateAndValidate(
            schemaVersion = provider.descriptor.configuration.currentSchemaVersion,
            payload = opaque("""{"stage":3,"required":"invalid"}"""),
        )

        val failure = result as? ProviderConfigurationResult.Failure
            ?: throw AssertionError("Expected invalid configuration to be rejected, got $result")
        val error = failure.error as? ChannelProviderError.InvalidConfiguration
            ?: throw AssertionError("Expected invalid configuration error, got ${failure.error}")
        assertEquals(provider.descriptor.implementationId, error.implementationId)
        assertEquals(provider.descriptor.configuration.currentSchemaVersion, error.schemaVersion)
    }

    @Test
    fun builtInProvidersRejectInvalidAndUnsupportedConfigurationSchemas() {
        data class Case(
            val name: String,
            val configuration: ChannelConfigurationProvider,
            val schemaVersion: Int,
            val payload: OpaqueJsonObject,
            val expectedError: Class<out ChannelProviderError>,
        )

        val cases = listOf(
            Case(
                name = "journal requires at least one output",
                configuration = BuiltInChannelDescriptors.journal.configuration,
                schemaVersion = 1,
                payload = opaque("""{"baseDirectory":null,"saveVoice":false,"saveText":false}"""),
                expectedError = ChannelProviderError.InvalidConfiguration::class.java,
            ),
            Case(
                name = "debug mode must be a declared string",
                configuration = BuiltInChannelDescriptors.debug.configuration,
                schemaVersion = 1,
                payload = opaque("""{"mode":99}"""),
                expectedError = ChannelProviderError.InvalidConfiguration::class.java,
            ),
            Case(
                name = "keyboard profile must include host and layout",
                configuration = BuiltInChannelDescriptors.keyboard.configuration,
                schemaVersion = 1,
                payload = opaque("""{"hostProfile":"linux"}"""),
                expectedError = ChannelProviderError.InvalidConfiguration::class.java,
            ),
            Case(
                name = "current journal provider refuses newer schema",
                configuration = BuiltInChannelDescriptors.journal.configuration,
                schemaVersion = 2,
                payload = opaque("""{"baseDirectory":"/records","saveVoice":false,"saveText":true}"""),
                expectedError = ChannelProviderError.UnsupportedSchemaVersion::class.java,
            ),
        )

        cases.forEach { case ->
            val result = case.configuration.migrateAndValidate(case.schemaVersion, case.payload)
            val failure = result as? ProviderConfigurationResult.Failure
                ?: throw AssertionError("${case.name}: expected rejection, got $result")
            assertTrue("${case.name}: unexpected ${failure.error}", case.expectedError.isInstance(failure.error))
            assertEquals(case.configuration.implementationId, failure.error.implementationId)
        }
    }

    @Test
    fun genericMigratorAcceptsTestOnlyConformingProviderWithoutCoreBranch() {
        val provider = TestProvider(ChannelImplementationId("test:additional"))
        val registry = ChannelImplementationProviderRegistry()
        registry.register(provider)
        val original = ChannelCatalogueSnapshot(
            definitions = listOf(
                ChannelDefinition(
                    id = "additional-instance",
                    name = "Additional instance",
                    implementationId = provider.descriptor.implementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = opaque("""{"stage":1,"required":"ok","extension":"value"}"""),
                ),
            ),
            activeChannelId = "additional-instance",
        )

        val result = ChannelCatalogueProviderMigrator.migrate(original, registry)

        val migration = result as? ChannelCatalogueProviderMigrationResult.Success
            ?: throw AssertionError("Expected generic provider migration success, got $result")
        val migrated = migration.snapshot.definitions.single()
        assertTrue(migration.changed)
        assertEquals("additional-instance", migrated.id)
        assertEquals("Additional instance", migrated.name)
        assertEquals(provider.descriptor.implementationId, migrated.implementationId)
        assertEquals(original.activeChannelId, migration.snapshot.activeChannelId)
        assertEquals(3, migrated.configSchemaVersion)
        assertEquals("value", migrated.configPayload.toJsonObject().getString("extension"))
    }

    @Test
    fun OpenAiModelChoiceFieldsRequireAnExistingNonSelfProfileDependency() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelConfigurationField.DynamicChoiceField(
                id = "model",
                label = "Model",
                source = DynamicConfigurationChoiceSource.OPENAI_MODELS,
            )
        }

        val implementationId = ChannelImplementationId("test:dynamic-choices")
        val modelField = ChannelConfigurationField.DynamicChoiceField(
            id = "model",
            label = "Model",
            source = DynamicConfigurationChoiceSource.OPENAI_MODELS,
            dependsOnFieldId = "profile",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChannelImplementationDescriptor(
                implementationId = implementationId,
                presentation = ChannelPresentationMetadata("Dynamic", "TEST", "Unavailable"),
                configuration = TestConfigurationProvider(implementationId),
                configurationFields = listOf(modelField),
                requiredCapabilities = emptySet(),
                preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
            )
        }
    }

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private fun assertConfigurationSuccess(result: ProviderConfigurationResult): ValidatedChannelConfiguration =
        (result as? ProviderConfigurationResult.Success)?.configuration
            ?: throw AssertionError("Expected validated configuration, got $result")

    /** A provider outside production built-ins that uses only the public provider contract. */
    private class TestProvider(
        implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        val configuration = TestConfigurationProvider(implementationId)

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Test provider", "TEST", "Test provider unavailable"),
            configuration = configuration,
            configurationFields = listOf(
                ChannelConfigurationField.TextField("required", "Required value"),
            ),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult = ChannelRuntimeConstructionResult.Failure(
            ChannelProviderError.RuntimeConstructionFailed(
                descriptor.implementationId,
                "Runtime construction is outside provider configuration coverage",
            ),
        )
    }

    private class TestConfigurationProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion = 3
        val migratedFrom = mutableListOf<Int>()

        override fun defaultPayload(): OpaqueJsonObject =
            OpaqueJsonObject.parse("""{"stage":3,"required":"ok"}""").getOrThrow()

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
            val objectValue = payload.toJsonObject()
            return if (objectValue.optInt("stage") == currentSchemaVersion &&
                objectValue.optString("required") == "ok"
            ) {
                ProviderConfigurationResult.Success(
                    ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
                )
            } else {
                ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(
                        implementationId,
                        schemaVersion,
                        "stage must be current and required must be ok",
                    ),
                )
            }
        }

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep {
            migratedFrom += fromSchemaVersion
            return when (fromSchemaVersion) {
                1, 2 -> ChannelConfigurationMigrationStep.Success(
                    OpaqueJsonObject.fromJsonObject(
                        JSONObject(payload.toJsonString()).put("stage", fromSchemaVersion + 1),
                    ),
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
    }
}
