package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.dependency.ArtifactDigest
import dev.nilp0inter.subspace.dependency.GitHubAssetIdentity
import dev.nilp0inter.subspace.dependency.GitHubReleaseIdentity
import dev.nilp0inter.subspace.dependency.GitHubRepositoryCoordinates
import dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity
import dev.nilp0inter.subspace.dependency.InstalledProviderId
import dev.nilp0inter.subspace.dependency.PackageManifest
import dev.nilp0inter.subspace.dependency.PackagePresentation
import dev.nilp0inter.subspace.dependency.PackageSourceRecord
import dev.nilp0inter.subspace.dependency.RuntimeRequirements
import dev.nilp0inter.subspace.dependency.ValidatedPackageRevision
import dev.nilp0inter.subspace.lua.API_VERSION
import dev.nilp0inter.subspace.lua.ImmutableProgramImage
import dev.nilp0inter.subspace.lua.LUA_VERSION
import dev.nilp0inter.subspace.lua.LuaCallbackHandle
import dev.nilp0inter.subspace.lua.LuaCoroutineId
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import dev.nilp0inter.subspace.lua.LuaKernelConfig
import dev.nilp0inter.subspace.lua.LuaKernelOutcome
import dev.nilp0inter.subspace.lua.LuaNativeKernel
import dev.nilp0inter.subspace.lua.LuaOperationHandle
import dev.nilp0inter.subspace.lua.LuaPackageMaterializer
import dev.nilp0inter.subspace.lua.LuaSpawnAdmission
import dev.nilp0inter.subspace.lua.LuaStateHandle
import dev.nilp0inter.subspace.lua.LuaValue
import dev.nilp0inter.subspace.lua.LuaProgramRequirements
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistry
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistryShutdownResult
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledProviderRegistryContractTest {
    @Before
    fun resetLuaConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
        LuaNativeKernel.resetForTest()
    }

    @After
    fun clearLuaConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
        LuaNativeKernel.resetForTest()
    }

    @Test
    fun `materialized package provider uses numeric repository identity empty v1 configuration and immutable image without constructing Lua`() {
        val mutableSources = linkedMapOf("plugin" to PROGRAM_SOURCE)
        val image = image(mutableSources)
        mutableSources["plugin"] = "return { startup = function() error('mutated source') end }"

        assertEquals(PROGRAM_SOURCE, image.sourceMap.getValue("plugin"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (image.sourceMap as MutableMap<String, String>)["other"] = "return {}"
        }

        val bridge = RecordingLuaKernelBridge()
        val binding = materializedBinding(repositoryId = "918273645", digestCharacter = 'a', image = image, bridge = bridge)
        val provider = binding.provider
        val implementationId = InstalledProviderId.derive(GitHubRepositoryIdentity("918273645"))

        assertEquals("github-repository:918273645", provider.descriptor.implementationId.value)
        assertEquals(implementationId, provider.descriptor.implementationId)
        assertEquals(implementationId, provider.descriptor.configuration.implementationId)
        assertEquals(ProviderRevisionFingerprint("a".repeat(64)), provider.fingerprint)
        assertTrue(provider.descriptor.configurationFields.isEmpty())
        assertTrue(provider.descriptor.requiredCapabilities.isEmpty())

        val emptyConfiguration = provider.descriptor.configuration.validate(
            schemaVersion = 1,
            payload = emptyPayload(),
        )
        assertTrue(emptyConfiguration is ProviderConfigurationResult.Success)
        val nonEmptyFailure = provider.descriptor.configuration.validate(
            schemaVersion = 1,
            payload = OpaqueJsonObject.parse("""{"ignored":true}""").getOrThrow(),
        ) as? ProviderConfigurationResult.Failure
            ?: throw AssertionError("Nonempty v1 configuration must be rejected")
        assertEquals(
            ChannelProviderError.InvalidConfiguration(
                implementationId,
                1,
                "Lua provider configuration must be empty",
            ),
            nonEmptyFailure.error,
        )

        val registry = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            registry.publishInstalledProviders(mapOf(implementationId to binding)),
        )
        assertSame(provider, (registry.resolve(implementationId) as ChannelProviderResolution.Available).provider)
        assertTrue("materialization and registration must not call the injected Lua bridge", bridge.createdStateIds.isEmpty())
        assertFalse("materialization and registration must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
        assertFalse("materialization and registration must not load a native Lua state", LuaNativeKernel.isLoadAttempted)
    }

    @Test
    fun `two catalogue instances sharing one installed provider construct separate Lua states`() = runTest {
        val bridge = RecordingLuaKernelBridge()
        val binding = materializedBinding(repositoryId = "101", digestCharacter = 'b', bridge = bridge)
        val implementationId = binding.provider.descriptor.implementationId
        val providers = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            providers.publishInstalledProviders(mapOf(implementationId to binding)),
        )
        val runtimeRegistry = runtimeRegistry(providers)
        val left = definition("left-instance", implementationId)
        val right = definition("right-instance", implementationId)

        try {
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
            runCurrent()

            assertEquals(2, bridge.createdStateIds.size)
            assertNotEquals(
                "separate instance IDs must receive distinct state ownership",
                bridge.createdStateIds[0],
                bridge.createdStateIds[1],
            )
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
        }
        assertEquals(bridge.createdStateIds.toSet(), bridge.closedStateIds.toSet())
    }

    @Test
    fun `invalid installed snapshots return typed reasons and preserve the complete predecessor`() {
        val cases = listOf(
            InvalidSnapshotCase("repository-derived key mismatch", InstalledProvidersRejectionReason.InvalidId::class.java) { valid ->
                mapOf(ChannelImplementationId("github-repository:999") to valid)
            },
            InvalidSnapshotCase("descriptor ID mismatch", InstalledProvidersRejectionReason.AgreementMismatch::class.java) { valid ->
                val id = InstalledProviderId.derive(GitHubRepositoryIdentity("202"))
                mapOf(
                    id to InstalledProviderBinding(
                        repositoryId = GitHubRepositoryIdentity("202"),
                        expectedDigest = ArtifactDigest("c".repeat(64)),
                        provider = InstalledTestProvider(
                            implementationId = InstalledProviderId.derive(GitHubRepositoryIdentity("203")),
                            fingerprint = ProviderRevisionFingerprint("c".repeat(64)),
                        ),
                    ),
                )
            },
            InvalidSnapshotCase("fingerprint mismatch", InstalledProvidersRejectionReason.AgreementMismatch::class.java) { _ ->
                val id = InstalledProviderId.derive(GitHubRepositoryIdentity("204"))
                mapOf(
                    id to InstalledProviderBinding(
                        repositoryId = GitHubRepositoryIdentity("204"),
                        expectedDigest = ArtifactDigest("d".repeat(64)),
                        provider = InstalledTestProvider(id, ProviderRevisionFingerprint("e".repeat(64))),
                    ),
                )
            },
            InvalidSnapshotCase("reserved built-in namespace", InstalledProvidersRejectionReason.ReservedCollision::class.java) { valid ->
                mapOf(BuiltInChannelImplementationIds.JOURNAL to valid)
            },
        )

        cases.forEach { case ->
            val predecessor = binding("200", 'b')
            val predecessorId = predecessor.provider.descriptor.implementationId
            val registry = ChannelImplementationProviderRegistry()
            assertEquals(
                "${case.name}: predecessor publication",
                InstalledProvidersPublicationResult.Success(1L),
                registry.publishInstalledProviders(mapOf(predecessorId to predecessor)),
            )

            val result = registry.publishInstalledProviders(case.candidate(binding("201", 'c')))
            val rejection = result as? InstalledProvidersPublicationResult.Rejected
                ?: throw AssertionError("${case.name}: expected rejection, got $result")

            assertTrue("${case.name}: expected ${case.reason.simpleName}, got ${rejection.error}", case.reason.isInstance(rejection.error))
            assertEquals("${case.name}: rejected candidates cannot advance publication", 1L, registry.snapshotRevision)
            assertSame(
                "${case.name}: predecessor provider must remain resolvable",
                predecessor.provider,
                (registry.resolve(predecessorId) as ChannelProviderResolution.Available).provider,
            )
            assertEquals(
                "${case.name}: no candidate descriptor may partially publish",
                listOf(predecessorId),
                registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
            )
        }

        val registry = ChannelImplementationProviderRegistry()
        val available = binding("205", 'a')
        val id = available.provider.descriptor.implementationId
        assertEquals(InstalledProvidersPublicationResult.Success(1L), registry.publishInstalledProviders(mapOf(id to available)))
        val duplicateAcrossCompleteSnapshot = registry.publishInstalledProviders(
            candidate = mapOf(id to available),
            unavailable = mapOf(
                id to ChannelProviderError.PackageUnavailable(
                    id,
                    ChannelProviderError.PackageUnavailableCategory.INTEGRITY,
                    ChannelProviderError.PackageUnavailableDetail.DIGEST_MISMATCH,
                ),
            ),
        )
        assertEquals(
            InstalledProvidersRejectionReason.DuplicateValue(id),
            (duplicateAcrossCompleteSnapshot as? InstalledProvidersPublicationResult.Rejected)?.error,
        )
        assertEquals(1L, registry.snapshotRevision)
        assertSame(available.provider, (registry.resolve(id) as ChannelProviderResolution.Available).provider)
    }

    @Test
    fun `complete installed snapshots replace predecessors in one monotonic publication`() {
        val registry = ChannelImplementationProviderRegistry()
        val firstLeft = binding("301", 'a')
        val firstRight = binding("302", 'b')
        val first = mapOf(
            firstLeft.provider.descriptor.implementationId to firstLeft,
            firstRight.provider.descriptor.implementationId to firstRight,
        )
        val secondLeft = binding("303", 'c')
        val secondRight = binding("304", 'd')
        val second = mapOf(
            secondLeft.provider.descriptor.implementationId to secondLeft,
            secondRight.provider.descriptor.implementationId to secondRight,
        )

        assertEquals(InstalledProvidersPublicationResult.Success(1L), registry.publishInstalledProviders(first))
        assertEquals(InstalledProvidersPublicationResult.Success(2L), registry.publishInstalledProviders(second))

        assertEquals(2L, registry.snapshotRevision)
        assertEquals(
            listOf(
                secondLeft.provider.descriptor.implementationId,
                secondRight.provider.descriptor.implementationId,
            ),
            registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
        )
        assertTrue(registry.resolve(firstLeft.provider.descriptor.implementationId) is ChannelProviderResolution.Missing)
        assertTrue(registry.resolve(firstRight.provider.descriptor.implementationId) is ChannelProviderResolution.Missing)
        assertSame(
            secondLeft.provider,
            (registry.resolve(secondLeft.provider.descriptor.implementationId) as ChannelProviderResolution.Available).provider,
        )
        assertSame(
            secondRight.provider,
            (registry.resolve(secondRight.provider.descriptor.implementationId) as ChannelProviderResolution.Available).provider,
        )
    }

    private fun materializedBinding(
        repositoryId: String,
        digestCharacter: Char,
        image: ImmutableProgramImage = image(linkedMapOf("plugin" to PROGRAM_SOURCE)),
        bridge: LuaKernelBridge,
    ): InstalledProviderBinding {
        val identity = GitHubRepositoryIdentity(repositoryId)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Installed test", "Installed provider registry contract"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        return LuaPackageMaterializer.materialize(revision, bridge)
    }

    private fun binding(repositoryId: String, digestCharacter: Char): InstalledProviderBinding {
        val identity = GitHubRepositoryIdentity(repositoryId)
        val implementationId = InstalledProviderId.derive(identity)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        return InstalledProviderBinding(
            repositoryId = identity,
            expectedDigest = digest,
            provider = InstalledTestProvider(implementationId, ProviderRevisionFingerprint.fromDigest(digest)),
        )
    }

    private fun image(sources: Map<String, String>): ImmutableProgramImage = when (
        val created = ImmutableProgramImage.create(
            entryPoint = "plugin",
            sourceMap = sources,
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )
    ) {
        is dev.nilp0inter.subspace.lua.ProgramImageCreationResult.Success -> created.image
        is dev.nilp0inter.subspace.lua.ProgramImageCreationResult.Failure -> throw AssertionError(created.error.message)
    }

    private fun sourceRecord(repositoryId: GitHubRepositoryIdentity): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repositoryId,
        coordinates = GitHubRepositoryCoordinates("registry-owner", "registry-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "registry-package.zip"),
        ownerId = "9000001",
    )

    private fun emptyPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

    private fun definition(id: String, implementationId: ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = id,
        name = id,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = emptyPayload(),
    )

    private fun TestScope.runtimeRegistry(providers: ChannelImplementationProviderRegistry): ChannelRuntimeRegistry =
        ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = AvailableButUnimplementedCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 8,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            ),
            runtimeScope = backgroundScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 2_000,
        )

    private suspend fun TestScope.shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult {
        val result = async { registry.shutdownAndAwait() }
        runCurrent()
        return result.await()
    }

    private data class InvalidSnapshotCase(
        val name: String,
        val reason: Class<out InstalledProvidersRejectionReason>,
        val candidate: (InstalledProviderBinding) -> Map<ChannelImplementationId, InstalledProviderBinding>,
    )

    private class InstalledTestProvider(
        implementationId: ChannelImplementationId,
        override val fingerprint: ProviderRevisionFingerprint,
    ) : ChannelImplementationProvider {
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Installed test", "Installed test provider", "Unavailable"),
            configuration = EmptyConfiguration(implementationId),
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult =
            ChannelRuntimeConstructionResult.Failure(
                ChannelProviderError.RuntimeConstructionFailed(descriptor.implementationId, "Not used by registry contract tests"),
            )
    }

    private class EmptyConfiguration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            if (schemaVersion == 1 && payload.toJsonObject().length() == 0) {
                ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))
            } else {
                ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, "Expected an empty v1 object"),
                )
            }

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
            ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
        )
    }

    private class RecordingLuaKernelBridge : LuaKernelBridge {
        private val closedStates = mutableSetOf<Long>()
        private val nextState = AtomicInteger(1)
        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextState.getAndIncrement().toLong()
            createdStateIds += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "registry-contract")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(operation.stateHandle)
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = completed(operation.stateHandle)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            handle.stateId.value, handle.generation.value, 0, 0, 0, 0, null,
            LUA_VERSION, API_VERSION, "registry-contract",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            if (closedStates.add(handle.stateId.value)) closedStateIds += handle.stateId.value
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            completed(handle, "[\"startup\",\"handle_readiness\"]")

        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle)

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle, if (callbackHandle.name == "handle_readiness") "{\"ready\":true}" else null)

        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle)

        private fun completed(handle: LuaStateHandle, value: String? = null): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            coroutineId = null,
            value = value,
            elapsedNanos = null,
            currentBytes = null,
            peakBytes = null,
            deniedAllocations = null,
            bridgeBytes = null,
            luaVersion = LUA_VERSION,
            bindingVersion = API_VERSION,
            topology = "registry-contract",
            spawnedCoroutines = null,
        )
    }

    private object AvailableButUnimplementedCapabilities : ChannelCapabilityHost {
        override suspend fun availability(
            identity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.UNSUPPORTED,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private companion object {
        const val PROGRAM_SOURCE = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
    }
}
