package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.dependency.ArtifactDigest
import dev.nilp0inter.subspace.dependency.ConfigurationDataDeclaration
import dev.nilp0inter.subspace.dependency.ConfigurationFieldDeclaration
import dev.nilp0inter.subspace.dependency.ConfigurationUiDeclaration
import dev.nilp0inter.subspace.dependency.DynamicChoiceSource
import dev.nilp0inter.subspace.dependency.GitHubAssetIdentity
import dev.nilp0inter.subspace.dependency.GitHubReleaseIdentity
import dev.nilp0inter.subspace.dependency.GitHubRepositoryCoordinates
import dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity
import dev.nilp0inter.subspace.dependency.PackageCapability
import dev.nilp0inter.subspace.dependency.PackageConfigurationDeclaration
import dev.nilp0inter.subspace.dependency.PackageManifest
import dev.nilp0inter.subspace.dependency.PackagePresentation
import dev.nilp0inter.subspace.dependency.PackageResourcesDeclaration
import dev.nilp0inter.subspace.dependency.PackageSourceRecord
import dev.nilp0inter.subspace.dependency.RuntimeRequirements
import dev.nilp0inter.subspace.dependency.UiChoice
import dev.nilp0inter.subspace.dependency.UiControl
import dev.nilp0inter.subspace.dependency.UiFieldDeclaration
import dev.nilp0inter.subspace.dependency.ValidatedPackageRevision
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceId
import dev.nilp0inter.subspace.model.InstalledProviderBinding
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.model.ProviderRevisionFingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused contracts for [LuaPackageMaterializer] (tasks 3.4 / 10.1 / 10.4).
 *
 * Materialization is declaration-only: it compiles validated public capability IDs to existing
 * semantic host capabilities and validated `dynamic-choice` declarations to generic
 * [ChannelConfigurationField.DynamicChoiceField] metadata, without resolving a choice source,
 * executing Lua, creating an actor, or loading the native kernel. Tests are package-identity
 * independent: behavior is asserted against arbitrary numeric repository identities.
 */
class LuaPackageMaterializerTest {

    @Before
    fun resetSideEffectFlags() {
        ActorRuntimeFactory.resetForTest()
        LuaNativeKernel.resetForTest()
    }

    @Test
    fun `keyboard_output eligibility compiles to the existing semantic TextOutput capability without Lua`() {
        val binding = materialize(
            declaration = emptyDeclaration(),
            capabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )

        assertEquals(setOf(ChannelCapability.TextOutput), binding.provider.descriptor.requiredCapabilities)
        assertFalse("materialization must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
        assertFalse("materialization must not load a native Lua state", LuaNativeKernel.isLoadAttempted)
    }

    @Test
    fun `keyboard_output with transcription compiles independent semantic capabilities without implementation identities`() {
        val binding = materialize(
            declaration = emptyDeclaration(),
            capabilities = linkedSetOf(PackageCapability.AUDIO_TRANSCRIPTION, PackageCapability.KEYBOARD_OUTPUT),
        )

        assertEquals(
            setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
            binding.provider.descriptor.requiredCapabilities,
        )
    }

    @Test
    fun `dynamic-choice declaration compiles to generic DynamicChoiceField metadata without resolving the source`() {
        val binding = materialize(
            declaration = keyboardProfileDeclaration(),
            capabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )

        val fields = binding.provider.descriptor.configurationFields
        assertEquals(1, fields.size)
        val field = fields.single() as? ChannelConfigurationField.DynamicChoiceField
            ?: throw AssertionError(
                "dynamic-choice declaration must compile to a DynamicChoiceField, got ${fields.single()::class.simpleName}",
            )

        assertEquals("host_profile", field.id)
        assertEquals("Host profile", field.label)
        assertNull(field.help)
        assertTrue("dynamic-choice field is a required scalar", field.required)
        assertNull("keyboard-output-profiles is a top-level field with no dependency", field.dependsOnFieldId)
        assertNull(field.visibleWhenFieldId)
        assertNull(field.visibleWhenValue)
        // The validated public source ID is carried verbatim; no resolution, no host profile object.
        assertEquals(DynamicConfigurationChoiceSourceId.KEYBOARD_OUTPUT_PROFILES, field.source)
        assertEquals(DynamicConfigurationChoiceSourceId("keyboard-output-profiles"), field.source)
    }
    @Test
    fun `three stage dynamic-choice hierarchy projects dependsOnFieldId onto materialized fields`() {
        val binding = materialize(
            declaration = keyboardHierarchyDeclaration(),
            capabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )

        val fields = binding.provider.descriptor.configurationFields
        assertEquals(listOf("host_os", "host_layout", "host_profile"), fields.map { it.id })

        val platform = fields[0] as ChannelConfigurationField.DynamicChoiceField
        val layout = fields[1] as ChannelConfigurationField.DynamicChoiceField
        val profile = fields[2] as ChannelConfigurationField.DynamicChoiceField

        assertEquals(DynamicConfigurationChoiceSourceId("keyboard-output-platforms"), platform.source)
        assertNull("platforms is the top-level stage with no dependency", platform.dependsOnFieldId)

        assertEquals(DynamicConfigurationChoiceSourceId("keyboard-output-layouts"), layout.source)
        assertEquals("host_os", layout.dependsOnFieldId)

        assertEquals(DynamicConfigurationChoiceSourceId("keyboard-output-profiles"), profile.source)
        assertEquals("host_layout", profile.dependsOnFieldId)
    }

    @Test
    fun `dynamic-choice field preserves strict scalar configuration validation`() {
        val binding = materialize(
            declaration = keyboardProfileDeclaration(),
            capabilities = setOf(PackageCapability.KEYBOARD_OUTPUT),
        )
        val configuration = binding.provider.descriptor.configuration

        assertEquals("linux:us", configuration.defaultPayload().toJsonObject().getString("host_profile"))

        assertTrue(
            configuration.validate(
                1,
                OpaqueJsonObject.fromJsonObject(JSONObject().put("host_profile", "linux:us")),
            ) is ProviderConfigurationResult.Success,
        )
        assertTrue(
            "undeclared key must be rejected",
            configuration.validate(
                1,
                OpaqueJsonObject.fromJsonObject(JSONObject().put("host_profile", "linux:us").put("extra", "x")),
            ) is ProviderConfigurationResult.Failure,
        )
        assertTrue(
            "nested value must be rejected as a non-scalar",
            configuration.validate(
                1,
                OpaqueJsonObject.fromJsonObject(JSONObject().put("host_profile", JSONObject())),
            ) is ProviderConfigurationResult.Failure,
        )
        assertTrue(
            "missing required field must be rejected",
            configuration.validate(1, OpaqueJsonObject.fromJsonObject(JSONObject())) is ProviderConfigurationResult.Failure,
        )
    }

    @Test
    fun `materializing the full keyboard channel shape performs no bridge actor or native effect`() {
        val binding = materialize(
            declaration = keyboardProfileDeclaration(),
            capabilities = setOf(PackageCapability.AUDIO_TRANSCRIPTION, PackageCapability.KEYBOARD_OUTPUT),
            bridge = ThrowingBridge(),
        )

        assertEquals(
            setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
            binding.provider.descriptor.requiredCapabilities,
        )
        assertEquals(1, binding.provider.descriptor.configurationFields.size)
        assertFalse("materialization must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
        assertFalse("materialization must not load a native Lua state", LuaNativeKernel.isLoadAttempted)
    }

    @Test
    fun `existing capability compilation remains deterministic ordered and side-effect-free`() {
        val binding = materialize(
            declaration = emptyDeclaration(),
            capabilities = linkedSetOf(
                PackageCapability.AUDIO_TRANSCRIPTION,
                PackageCapability.AUDIO_SYNTHESIS,
                PackageCapability.AUDIO_PLAYBACK,
                PackageCapability.STORAGE_FILES,
                PackageCapability.AUDIO_FILES,
            ),
            bridge = ThrowingBridge(),
        )

        assertEquals(
            listOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
                ChannelCapability.DeferredAudioPlayback,
                ChannelCapability.StorageFiles,
                ChannelCapability.AudioFiles,
            ),
            binding.provider.descriptor.requiredCapabilities.toList(),
        )
        assertFalse("materialization must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
        assertFalse("materialization must not load a native Lua state", LuaNativeKernel.isLoadAttempted)
    }

    @Test
    fun `mixed static and dynamic controls compile to ordered generic fields`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("device_name", "B02PTT", null),
                    ConfigurationFieldDeclaration.BooleanField("verbose", false),
                    ConfigurationFieldDeclaration.IntegerField("retry_count", 3L, 0L, 10L),
                    ConfigurationFieldDeclaration.StringField("mode", "ECHO", listOf("ECHO", "TTS")),
                    ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("device_name", UiControl.TEXT, "Device name", null, null),
                    UiFieldDeclaration("verbose", UiControl.TOGGLE, "Verbose", null, null),
                    UiFieldDeclaration("retry_count", UiControl.NUMBER, "Retry count", null, null),
                    UiFieldDeclaration("mode", UiControl.CHOICE, "Mode", null, listOf(UiChoice("ECHO", "ECHO"), UiChoice("TTS", "TTS"))),
                    UiFieldDeclaration(
                        field = "host_profile",
                        control = UiControl.DYNAMIC_CHOICE,
                        label = "Host profile",
                        help = null,
                        choices = null,
                        source = DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES,
                    ),
                )
            ),
        )

        val binding = materialize(declaration = declaration, capabilities = setOf(PackageCapability.KEYBOARD_OUTPUT))
        val fields = binding.provider.descriptor.configurationFields

        assertEquals(listOf("device_name", "verbose", "retry_count", "mode", "host_profile"), fields.map { it.id })
        assertTrue(fields[0] is ChannelConfigurationField.TextField)
        assertTrue(fields[1] is ChannelConfigurationField.BooleanField)
        assertTrue(fields[2] is ChannelConfigurationField.NumberField)
        assertTrue(fields[3] is ChannelConfigurationField.ChoiceField)
        assertTrue(fields[4] is ChannelConfigurationField.DynamicChoiceField)
    }

    private fun materialize(
        declaration: PackageConfigurationDeclaration,
        capabilities: Set<String>,
        bridge: LuaKernelBridge = ThrowingBridge(),
        repositoryId: String = "100200300",
        digestCharacter: Char = 'a',
    ): InstalledProviderBinding {
        val image = image()
        val identity = GitHubRepositoryIdentity(repositoryId)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Materializer contract", "Focused materialization contract fixture"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = capabilities,
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        return LuaPackageMaterializer.materialize(revision, bridge)
    }

    private fun keyboardProfileDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(
            listOf(ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null))
        ),
        ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration(
                    field = "host_profile",
                    control = UiControl.DYNAMIC_CHOICE,
                    label = "Host profile",
                    help = null,
                    choices = null,
                    source = DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES,
                )
            )
        ),
    )
    private fun keyboardHierarchyDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(
            listOf(
                ConfigurationFieldDeclaration.StringField("host_os", "linux", null),
                ConfigurationFieldDeclaration.StringField("host_layout", "linux:us", null),
                ConfigurationFieldDeclaration.StringField("host_profile", "linux:us", null),
            )
        ),
        ConfigurationUiDeclaration(
            listOf(
                UiFieldDeclaration(
                    field = "host_os",
                    control = UiControl.DYNAMIC_CHOICE,
                    label = "Host OS",
                    help = null,
                    choices = null,
                    source = DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS,
                ),
                UiFieldDeclaration(
                    field = "host_layout",
                    control = UiControl.DYNAMIC_CHOICE,
                    label = "Host layout",
                    help = null,
                    choices = null,
                    source = DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS,
                    dependsOnFieldId = "host_os",
                ),
                UiFieldDeclaration(
                    field = "host_profile",
                    control = UiControl.DYNAMIC_CHOICE,
                    label = "Host profile",
                    help = null,
                    choices = null,
                    source = DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES,
                    dependsOnFieldId = "host_layout",
                ),
            )
        ),
    )

    private fun emptyDeclaration(): PackageConfigurationDeclaration = PackageConfigurationDeclaration(
        ConfigurationDataDeclaration(emptyList()),
        ConfigurationUiDeclaration(emptyList()),
    )

    private fun image(): ImmutableProgramImage = when (
        val created = ImmutableProgramImage.create(
            entryPoint = "plugin",
            sourceMap = mapOf("plugin" to PROGRAM_SOURCE),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )
    ) {
        is ProgramImageCreationResult.Success -> created.image
        is ProgramImageCreationResult.Failure -> throw AssertionError(created.error.message)
    }

    private fun sourceRecord(repositoryId: GitHubRepositoryIdentity): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repositoryId,
        coordinates = GitHubRepositoryCoordinates("materializer-owner", "materializer-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "materializer-package.zip"),
        ownerId = "9000001",
    )

    /**
     * Fails loudly on any kernel interaction. Materialization is declaration-only, so a passing
     * test proves it created no Lua state, loaded no module, and invoked no callback.
     */
    private class ThrowingBridge : LuaKernelBridge {
        private fun forbidden(): Nothing =
            throw AssertionError("materialization must not invoke the Lua kernel bridge")

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = forbidden()
        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = forbidden()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = forbidden()
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun close(handle: LuaStateHandle): LuaKernelOutcome = forbidden()
        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome = forbidden()
        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun invokeCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, arguments: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = forbidden()
    }

    private companion object {
        const val PROGRAM_SOURCE = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
    }
}
