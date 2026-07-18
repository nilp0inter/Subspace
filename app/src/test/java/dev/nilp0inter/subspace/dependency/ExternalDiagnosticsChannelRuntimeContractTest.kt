package dev.nilp0inter.subspace.dependency

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.lua.API_VERSION
import dev.nilp0inter.subspace.lua.LUA_VERSION
import dev.nilp0inter.subspace.lua.LuaCallbackHandle
import dev.nilp0inter.subspace.lua.LuaCoroutineId
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import dev.nilp0inter.subspace.lua.LuaKernelConfig
import dev.nilp0inter.subspace.lua.LuaKernelOutcome
import dev.nilp0inter.subspace.lua.LuaOperationHandle
import dev.nilp0inter.subspace.lua.LuaSpawnAdmission
import dev.nilp0inter.subspace.lua.LuaStateHandle
import dev.nilp0inter.subspace.lua.LuaValue
import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.InstalledProvidersPublicationResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.service.CommittedTargetLeaseOwner
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistry
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistryShutdownResult
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime contracts for the byte-pinned externally released diagnostics package.
 *
 * Native Lua execution is unavailable to JVM unit tests. [RecordingDiagnosticsKernelBridge] is
 * therefore limited to the native kernel boundary: it retains per-state ownership, exposes the
 * generic callback protocol, and records only values crossing that boundary. The package itself
 * is always installed through validation, materialization, publication, and the runtime registry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalDiagnosticsChannelRuntimeContractTest {
    @Test
    fun `released diagnostics artifact enters the provider registry only through generic installed publication`() = runTest {
        withTemporaryDirectory { root ->
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)

            assertTrue(
                "Before package publication, the external repository identity must not resolve to a provider.",
                providers.resolve(implementationId) is ChannelProviderResolution.Missing,
            )
            assertFalse(
                "The external package identity must not be promoted to a built-in descriptor.",
                BuiltInChannelDescriptors.all.any { it.implementationId == implementationId },
            )

            val repository = repository(root, RecordingDiagnosticsKernelBridge(), providers)
            assertEquals(
                MutationResult.Installed(implementationId),
                success(repository.installOrUpdate(ByteArrayInputStream(releasedArtifact()), sourceRecord())),
            )

            assertTrue(
                "The ordinary installed-package publisher must make the released fixture available.",
                providers.resolve(implementationId) is ChannelProviderResolution.Available,
            )
        }
    }

    @Test
    fun `released diagnostics instances have independent states and both become ready`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingDiagnosticsKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, bridge, providers)
            val registry = runtimeRegistry(providers)
            val left = definition("diagnostics-left", implementationId)
            val right = definition("diagnostics-right", implementationId)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
            runCurrent()

            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(left.id)?.preparation)
            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(right.id)?.preparation)
            assertEquals(2, bridge.createdStateIds.size)
            assertNotEquals(
                "Each configured channel instance must have its own opaque Lua state.",
                bridge.createdStateIds[0],
                bridge.createdStateIds[1],
            )

            registry.reconcile(ChannelCatalogueSnapshot(listOf(right), right.id))
            runCurrent()
            assertTrue("Retiring one instance must close its state.", bridge.closedStateIds.contains(bridge.createdStateIds[0]))
            assertFalse("A sibling instance must remain live after its peer is retired.", bridge.closedStateIds.contains(bridge.createdStateIds[1]))
            val survivingTarget = (registry.prepareInput(right.id) as? ChannelInputAcceptance.Accepted)?.target
                ?: throw AssertionError("The surviving ready instance must accept PTT")
            survivingTarget.onInputCancelled("test complete")
            (survivingTarget as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `released diagnostics rejects excess startup work without publishing it after readiness`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingDiagnosticsKernelBridge(startupSpawnCount = 65)
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, bridge, providers)
            val registry = runtimeRegistry(providers)
            val channel = definition("diagnostics-startup-admission", implementationId)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            runCurrent()

            assertEquals(ChannelPreparationAvailability.Available, registry.getRuntimeSnapshot(channel.id)?.preparation)
            assertEquals(65, bridge.startupAdmissionResults.size)
            assertTrue(
                "The bounded generation context must reject at least one excess startup task.",
                bridge.startupAdmissionResults.any { it == 2 },
            )
            assertFalse(
                "A task rejected during startup admission must never enter the native coroutine boundary.",
                bridge.startedCoroutines.contains(65L),
            )
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `released diagnostics readiness heartbeat includes unselected live channels`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingDiagnosticsKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, bridge, providers)
            val registry = runtimeRegistry(providers)
            val selected = definition("diagnostics-selected", implementationId)
            val unselected = definition("diagnostics-unselected", implementationId)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(selected, unselected), selected.id))
            runCurrent()
            val selectedState = bridge.createdStateIds[0]
            val unselectedState = bridge.createdStateIds[1]
            val selectedInitial = bridge.readinessCalls(selectedState)
            val unselectedInitial = bridge.readinessCalls(unselectedState)

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(selectedInitial + 1, bridge.readinessCalls(selectedState))
            assertEquals(
                "Periodic readiness is generation-owned, not restricted to the active channel selection.",
                unselectedInitial + 1,
                bridge.readinessCalls(unselectedState),
            )
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `released diagnostics PTT callback receives capture metadata without audio payload`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingDiagnosticsKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, bridge, providers)
            val registry = runtimeRegistry(providers)
            val channel = definition("diagnostics-ptt", implementationId)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            runCurrent()
            val target = (registry.prepareInput(channel.id) as? ChannelInputAcceptance.Accepted)?.target
                ?: throw AssertionError("Ready diagnostics package must accept PTT")
            target.onInputStarted(FakeInputSession(sampleRate = 16_000))
            assertEquals(ChannelInputResult.None, target.onInputReleased(RecordedPcm(ShortArray(8_000), 16_000)))

            val event = bridge.inputEvents.single() as LuaValue.Map
            (target as? CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            assertEquals(setOf("event", "session", "metadata"), event.pairs.keys)
            assertEquals(LuaValue.StringValue("capture"), event.pairs["event"])
            val metadata = event.pairs["metadata"] as? LuaValue.Map
                ?: throw AssertionError("PTT callback must receive a metadata object")
            assertEquals(setOf("duration_ms", "sample_rate", "channels"), metadata.pairs.keys)
            assertEquals(LuaValue.Number(500.0), metadata.pairs["duration_ms"])
            assertEquals(LuaValue.Number(16_000.0), metadata.pairs["sample_rate"])
            assertEquals(LuaValue.Number(1.0), metadata.pairs["channels"])
            assertFalse("PCM content must never cross the Lua callback boundary.", "payload" in event.pairs)
            assertFalse("Raw audio samples must never cross the Lua callback boundary.", "samples" in event.pairs)
            assertFalse("Audio payload bytes must never cross the Lua callback boundary.", "audio" in event.pairs)
            assertFalse("Transcription content must never cross the Lua callback boundary.", "transcript" in event.pairs)
            assertFalse("Capture-device identity must never cross the Lua callback boundary.", "device" in event.pairs)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `released diagnostics dispatches SOS through the generic runtime callback`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingDiagnosticsKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, bridge, providers)
            val registry = runtimeRegistry(providers)
            val channel = definition("diagnostics-sos", implementationId)

            registry.reconcile(ChannelCatalogueSnapshot(listOf(channel), channel.id))
            runCurrent()

            assertEquals(ChannelPreparationAvailability.Available, registry.dispatchSos(channel.id))
            assertEquals(
                listOf(LuaValue.Map(mapOf("event" to LuaValue.StringValue("sos")))),
                bridge.sosEvents,
            )
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
        }
    }

    @Test
    fun `released diagnostics replacement and process restart rebuild fresh runtime state`() = runTest {
        withTemporaryDirectory { root ->
            val firstBridge = RecordingDiagnosticsKernelBridge()
            val firstProviders = ChannelImplementationProviderRegistry()
            val implementationId = installReleasedArtifact(root, firstBridge, firstProviders)
            val firstRegistry = runtimeRegistry(firstProviders)
            val original = definition("diagnostics-restart", implementationId, "Original")

            firstRegistry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
            runCurrent()
            val originalState = firstBridge.createdStateIds.single()
            val replacement = original.copy(name = "Replacement")
            firstRegistry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
            runCurrent()
            val replacementState = firstBridge.createdStateIds.last()

            assertNotEquals("Replacing a definition must construct a new Lua state.", originalState, replacementState)
            assertTrue("Replacement must close the predecessor state.", firstBridge.closedStateIds.contains(originalState))
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(firstRegistry))

            val restartBridge = RecordingDiagnosticsKernelBridge()
            val restartProviders = ChannelImplementationProviderRegistry()
            val restartedRepository = repository(root, restartBridge, restartProviders)
            assertEquals(Unit, success(restartedRepository.loadAndPublish()))
            val restartedRegistry = runtimeRegistry(restartProviders)
            restartedRegistry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
            runCurrent()

            assertEquals(ChannelPreparationAvailability.Available, restartedRegistry.getRuntimeSnapshot(replacement.id)?.preparation)
            assertEquals(1, restartBridge.createdStateIds.size)
            assertTrue(
                "A restarted process must have no inherited input callback history from the prior runtime.",
                restartBridge.inputEvents.isEmpty(),
            )
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(restartedRegistry))
        }
    }

    private suspend fun TestScope.installReleasedArtifact(
        root: File,
        bridge: RecordingDiagnosticsKernelBridge,
        providers: ChannelImplementationProviderRegistry,
    ): ChannelImplementationId {
        val repository = repository(root, bridge, providers)
        val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)
        assertEquals(
            MutationResult.Installed(implementationId),
            success(repository.installOrUpdate(ByteArrayInputStream(releasedArtifact()), sourceRecord())),
        )
        return implementationId
    }

    private fun TestScope.runtimeRegistry(providers: ChannelImplementationProviderRegistry): ChannelRuntimeRegistry =
        ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = AvailableButUnimplementedCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 16,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            ),
            runtimeScope = backgroundScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 2_000,
        )

    private fun TestScope.repository(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
    ): InstalledPackageRepository = InstalledPackageRepository(
        store = InstalledPackageStore(root),
        bridge = bridge,
        publisher = { materialized ->
            val unavailable = materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) }
            when (providers.publishInstalledProviders(materialized.bindings, unavailable)) {
                is InstalledProvidersPublicationResult.Success -> PackageOutcome.Success(Unit)
                is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                    PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                )
            }
        },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private suspend fun TestScope.shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult {
        val result = async { registry.shutdownAndAwait() }
        runCurrent()
        return result.await()
    }

    private fun definition(
        id: String,
        implementationId: ChannelImplementationId,
        name: String = id,
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = name,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
    )

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("nilp0inter", "diagnostics-channel"),
        release = GitHubReleaseIdentity("1", "v1.0.0", false),
        asset = GitHubAssetIdentity("1", "subspace-channel.zip"),
        ownerId = "9000001",
    )

    private fun releasedArtifact(): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
        "Missing pinned diagnostics release fixture $RESOURCE_PATH"
    }.use { stream ->
        stream.readBytes().also { bytes ->
            assertEquals("The runtime fixture must remain the exact reviewed release artifact.", ARTIFACT_SHA256, sha256(bytes))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected package operation success, got ${outcome.error}")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-diagnostics-runtime-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class FakeInputSession(override val sampleRate: Int) : ChannelAudioInputSession {
        override val frames = emptyFlow<ShortArray>()
    }

    /**
     * Native-boundary fake: each [create] allocates an independent state and callback outcomes are
     * deterministic. It does not inspect or parse Lua source; [loadProgramImage] merely accepts
     * the immutable image delivered by the generic materializer.
     */
    private class RecordingDiagnosticsKernelBridge(
        private val startupSpawnCount: Int = 0,
    ) : LuaKernelBridge {
        private data class State(val id: Long, var closed: Boolean = false)

        private var nextStateId = 1L
        private val states = mutableMapOf<Long, State>()
        private val readinessByState = mutableMapOf<Long, Int>()

        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()
        val inputEvents = mutableListOf<LuaValue>()
        val sosEvents = mutableListOf<LuaValue>()
        val startupAdmissionResults = mutableListOf<Int>()
        val startedCoroutines = mutableListOf<Long>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextStateId++
            states[id] = State(id)
            createdStateIds += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "diagnostics-test")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(operation.stateHandle)

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = completed(operation.stateHandle)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            handle.stateId.value,
            handle.generation.value,
            0,
            0,
            0,
            0,
            null,
            LUA_VERSION,
            API_VERSION,
            "diagnostics-test",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            val state = state(handle)
            if (!state.closed) {
                state.closed = true
                closedStateIds += state.id
            }
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = completed(handle, JSONArray(CALLBACKS).toString())

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val admitted = mutableListOf<Long>()
            repeat(startupSpawnCount) { index ->
                val coroutineId = index + 1L
                val result = spawnAdmission.admitTask(coroutineId)
                startupAdmissionResults += result
                if (result == 0) admitted += coroutineId
            }
            return completed(handle, spawnedCoroutines = admitted)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = when (callbackHandle.name) {
            "handle_readiness" -> {
                readinessByState[handle.stateId.value] = readinessCalls(handle.stateId.value) + 1
                completed(handle, "{\"ready\":true}")
            }
            "handle_input" -> {
                inputEvents += arguments
                completed(handle, "{\"ok\":true}")
            }
            "handle_sos" -> {
                sosEvents += arguments
                completed(handle)
            }
            else -> completed(handle)
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            startedCoroutines += coroutineId.value
            return completed(handle)
        }

        fun readinessCalls(stateId: Long): Int = readinessByState[stateId] ?: 0

        private fun state(handle: LuaStateHandle): State = states[handle.stateId.value]
            ?: error("Unknown diagnostics test state ${handle.stateId.value}")

        private fun completed(
            handle: LuaStateHandle,
            value: String? = null,
            spawnedCoroutines: List<Long>? = null,
        ): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
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
            topology = "diagnostics-test",
            spawnedCoroutines = spawnedCoroutines,
        )

        private companion object {
            val CALLBACKS = listOf("startup", "handle_lifecycle", "handle_readiness", "handle_input", "handle_sos")
        }
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
        const val RESOURCE_PATH = "diagnostics-channel/subspace-channel.zip"
        const val REPOSITORY_ID = "1305223892"
        const val ARTIFACT_SHA256 = "a1609ba59e3bac16dbcdf03532f9774848aaf18ec46137e6bda7cecc012c6b87"
    }
}
