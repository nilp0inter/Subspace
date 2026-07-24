package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityFailureReason
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityPreparerRegistry
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.KeyboardOutputAdapter
import dev.nilp0inter.subspace.channel.capability.KeyboardOutputSubmission
import dev.nilp0inter.subspace.channel.capability.OutputExecutionOwner
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.dependency.DynamicChoiceSource
import dev.nilp0inter.subspace.dependency.GitHubAssetIdentity
import dev.nilp0inter.subspace.dependency.GitHubReleaseIdentity
import dev.nilp0inter.subspace.dependency.GitHubRepositoryCoordinates
import dev.nilp0inter.subspace.dependency.GitHubRepositoryIdentity
import dev.nilp0inter.subspace.dependency.InstalledPackageRepository
import dev.nilp0inter.subspace.dependency.InstalledPackageStore
import dev.nilp0inter.subspace.dependency.InstalledProviderId
import dev.nilp0inter.subspace.dependency.MutationResult
import dev.nilp0inter.subspace.dependency.PackageFailure
import dev.nilp0inter.subspace.dependency.PackageOutcome
import dev.nilp0inter.subspace.dependency.PackageSourceRecord
import dev.nilp0inter.subspace.dependency.toPackageUnavailable
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolver
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceResolution
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceSourceId
import dev.nilp0inter.subspace.model.DynamicConfigurationChoiceUnavailableReason
import dev.nilp0inter.subspace.model.InstalledProvidersPublicationResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistry
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistryShutdownResult
import dev.nilp0inter.subspace.service.CommittedTargetLeaseOwner
import dev.nilp0inter.subspace.service.KeyboardOutputChoiceHierarchy
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import io.sleepwalker.core.keymap.HostProfile
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side runtime contracts for the byte-pinned external Keyboard package
 * (task 12.3-12.5, 12.9, 12.11 and the 11.13 fixture pin).
 *
 * Every test starts from the exact published v1.0.1 release artifact and
 * traverses the ordinary production path: candidate archive bytes -> validator
 * -> immutable store -> materializer -> provider registry -> catalogue ->
 * runtime registry -> actor seam -> fake host keyboard capability. The v1.0.1
 * package declares the three-stage detached hierarchy (host_os -> host_layout
 * -> host_profile): every generation carries all three detached scalars, the
 * host readiness resolver projects all three references against the generic
 * dynamic-choice registry, and only host_profile drives keyboard output. The
 * package is never installed through a Keyboard-specific provider, a
 * repository/name branch, or an automatic instance.
 *
 * Scope: this file covers host-owned wiring only. The native Lua kernel is
 * unavailable to JVM unit tests, so [KeyboardWiringBridge] is scripted strictly
 * at the native kernel boundary — exactly the seam used by the existing
 * `ExternalDebugChannelRuntimeContractTest` /
 * `ExternalDiagnosticsChannelRuntimeContractTest` — to model state ownership,
 * readiness/preparation wiring, and lifecycle. It does NOT stand in for the
 * package's Lua policy: trailing-space policy, exactly-one-Enter SOS, delivered
 * acknowledgement, no-replay, and content privacy (12.6-12.8) live in
 * `lua/plugin.lua` and are covered by the package-local Lua tests plus the
 * native-bridge package tests, not here.
 *
 * The source-record release/asset IDs are the published v1.0.1 GitHub IDs
 * (release tag commit 4499f8ef7e5f06952bd10cdd5478619c1c78b24d).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalKeyboardChannelRuntimeContractTest {

    @Test
    fun `exact keyboard fixture pins size and SHA-256 and installs through the generic path with no Lua state`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            val providers = ChannelImplementationProviderRegistry()
            val adapterBuilds = AtomicInteger()
            val id = install(
                root,
                bridge,
                providers,
                CapabilityPreparerRegistry.default(),
                keyboardOutputAdapterFactory = { identity, owner ->
                    RecordingKeyboardAdapter(identity, owner).also { adapterBuilds.incrementAndGet() }
                },
            )

            assertEquals(InstalledProviderId.derive(GitHubRepositoryIdentity(REPOSITORY_ID)), id)
            assertTrue(
                "The exact fixture must resolve to an available provider.",
                providers.resolve(id) is ChannelProviderResolution.Available,
            )
            assertEquals(
                "Validation, storage, materialization, and publication must create no Lua state.",
                0,
                bridge.createCalls.get(),
            )
            assertEquals(
                "Installation must build no keyboard-output adapter (no output effect).",
                0,
                adapterBuilds.get(),
            )
        }
    }

    @Test
    fun `installed provider exposes keyboard and transcription eligibility with a dynamic host profile and no Lua state`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            val providers = ChannelImplementationProviderRegistry()
            val id = install(root, bridge, providers, CapabilityPreparerRegistry.default(), ::RecordingKeyboardAdapter)

            val provider = (providers.resolve(id) as ChannelProviderResolution.Available).provider
            val descriptor = provider.descriptor
            assertTrue(
                "keyboard.output must map to the TextOutput host capability.",
                ChannelCapability.TextOutput in descriptor.requiredCapabilities,
            )
            assertTrue(
                "audio.transcription must map to the Transcription host capability.",
                ChannelCapability.Transcription in descriptor.requiredCapabilities,
            )
            assertTrue(
                "keyboard.output is preparable, so the descriptor must advertise recoverable preparation.",
                descriptor.preparationTraits.supportsRecoverablePreparation,
            )
            val dynamic = descriptor.configurationFields
                .filterIsInstance<ChannelConfigurationField.DynamicChoiceField>()
            assertEquals(
                "The v1.0.1 package declares the three-stage dynamic hierarchy in order.",
                listOf("host_os", "host_layout", "host_profile"),
                dynamic.map { it.id },
            )
            assertEquals(
                listOf(
                    "keyboard-output-platforms",
                    "keyboard-output-layouts",
                    "keyboard-output-profiles",
                ),
                dynamic.map { it.source.value },
            )
            assertEquals(
                listOf(null, "host_os", "host_layout"),
                dynamic.map { it.dependsOnFieldId },
            )
            assertEquals("The package declares no mounts.", 0, descriptor.resourceDeclarations.mounts.size)
            assertEquals(
                "Provider inspection must create no Lua state.",
                0,
                bridge.createCalls.get(),
            )
        }
    }

    @Test
    fun `available keyboard output accepts input without preparation`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            val providers = ChannelImplementationProviderRegistry()
            val host = KeyboardCapabilityHost(CapabilityAvailability.Available, RecordingTextOutput())
            val id = install(root, bridge, providers, CapabilityPreparerRegistry.default(), ::RecordingKeyboardAdapter)
            val registry = runtimeRegistry(providers, host)
            val definition = definition("kb-available", id)
            try {
                registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                advanceUntilIdle()
                val acceptance = registry.prepareInput(definition.id)
                assertTrue(
                    "Available keyboard output must accept input: $acceptance",
                    acceptance is ChannelInputAcceptance.Accepted,
                )
                releaseAccepted(acceptance)
                assertAllReferencesAvailable(bridge.readinessContexts)
                assertEquals(
                    "Available output must project keyboard.output as available to readiness.",
                    "available",
                    readinessCapability(bridge.readinessContexts.first()),
                )
                assertEquals(
                    "Already-available output must not start a preparation.",
                    0,
                    host.prepareCalls.get(),
                )
            } finally {
                assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
            }
        }
    }

    @Test
    fun `recoverable keyboard output performs exactly one preparation before acceptance`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            bridge.readinessResponses.addLast(
                """{"ready":false,"status":"keyboard preparing","prepare":["keyboard.output"]}""",
            )
            bridge.readinessResponses.addLast("""{"ready":true,"status":"ready"}""")
            val providers = ChannelImplementationProviderRegistry()
            val host = KeyboardCapabilityHost(CapabilityAvailability.Recoverable, RecordingTextOutput())
            val id = install(root, bridge, providers, CapabilityPreparerRegistry.default(), ::RecordingKeyboardAdapter)
            val registry = runtimeRegistry(providers, host)
            val definition = definition("kb-recoverable", id)
            try {
                registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                advanceUntilIdle()
                val acceptance = registry.prepareInput(definition.id)
                assertTrue(
                    "Recoverable output must accept after one bounded preparation: $acceptance",
                    acceptance is ChannelInputAcceptance.Accepted,
                )
                releaseAccepted(acceptance)
                assertAllReferencesAvailable(bridge.readinessContexts)
                assertEquals(
                    "Recoverable output must project keyboard.output as recoverable to readiness.",
                    "recoverable",
                    readinessCapability(bridge.readinessContexts.first()),
                )
                assertEquals(
                    "Exactly one host preparation must run before acceptance.",
                    1,
                    host.prepareCalls.get(),
                )
            } finally {
                assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
            }
        }
    }

    @Test
    fun `failed preparation admits no target capture transcription or output`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            bridge.readinessResponses.addLast(
                """{"ready":false,"status":"keyboard preparing","prepare":["keyboard.output"]}""",
            )
            val textOutput = RecordingTextOutput()
            val providers = ChannelImplementationProviderRegistry()
            val host = KeyboardCapabilityHost(CapabilityAvailability.Recoverable, textOutput)
            host.prepareResult =
                HostedCapabilityAcquisition.Failed(CapabilityFailureReason.PREPARATION_FAILED)
            val id = install(root, bridge, providers, CapabilityPreparerRegistry.default(), ::RecordingKeyboardAdapter)
            val registry = runtimeRegistry(providers, host)
            val definition = definition("kb-failed", id)
            try {
                registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
                advanceUntilIdle()
                val acceptance = registry.prepareInput(definition.id)
                assertTrue(
                    "A failed preparation must refuse the input: $acceptance",
                    acceptance is ChannelInputAcceptance.Refused,
                )
                assertEquals("One preparation attempt must occur.", 1, host.prepareCalls.get())
                assertEquals(
                    "A refused input must never reach the package input callback (no capture/transcription).",
                    0,
                    bridge.inputCalls.get(),
                )
                assertEquals("No keyboard text output may occur.", 0, textOutput.textRequests.size)
                assertEquals("No keyboard key output may occur.", 0, textOutput.keyRequests.size)
                assertAllReferencesAvailable(bridge.readinessContexts)
            } finally {
                assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
            }
        }
    }

    @Test
    fun `two external instances retain independent profiles generations and lifecycle cleanup`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = KeyboardWiringBridge()
            val providers = ChannelImplementationProviderRegistry()
            val host = KeyboardCapabilityHost(CapabilityAvailability.Available, RecordingTextOutput())
            val id = install(root, bridge, providers, CapabilityPreparerRegistry.default(), ::RecordingKeyboardAdapter)
            val registry = runtimeRegistry(providers, host)
            val left = definition("kb-left", id)
            val right = definition(
                "kb-right",
                id,
                hostOs = "macos",
                hostLayout = "macos:us",
                hostProfile = "macos:us",
            )
            try {
                registry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
                advanceUntilIdle()
                assertEquals("Each instance must own an independent Lua state.", 2, bridge.createdStates.size)
                assertNotEquals(bridge.createdStates[0], bridge.createdStates[1])
                assertEquals(
                    "Each generation must receive exactly its own detached hierarchy triple.",
                    setOf(
                        listOf("linux", "linux:us", "linux:us"),
                        listOf("macos", "macos:us", "macos:us"),
                    ),
                    bridge.startupConfigs.map { config ->
                        listOf(
                            configuredScalar(config, "host_os"),
                            configuredScalar(config, "host_layout"),
                            configuredScalar(config, "host_profile"),
                        )
                    }.toSet(),
                )
                val leftAcceptance = registry.prepareInput(left.id)
                val rightAcceptance = registry.prepareInput(right.id)
                assertTrue(leftAcceptance is ChannelInputAcceptance.Accepted)
                assertTrue(rightAcceptance is ChannelInputAcceptance.Accepted)
                releaseAccepted(leftAcceptance)
                releaseAccepted(rightAcceptance)
                assertAllReferencesAvailable(bridge.readinessContexts)

                registry.reconcile(
                    ChannelCatalogueSnapshot(listOf(left, right.copy(enabled = false)), left.id),
                )
                advanceUntilIdle()
                assertEquals(
                    "Disabling one instance must close exactly its own state.",
                    1,
                    bridge.closedStates.size,
                )
                val siblingAcceptance = registry.prepareInput(left.id)
                assertTrue(
                    "The enabled sibling must keep accepting input.",
                    siblingAcceptance is ChannelInputAcceptance.Accepted,
                )
                releaseAccepted(siblingAcceptance)
            } finally {
                assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(registry))
            }
        }
    }

    private suspend fun releaseAccepted(acceptance: ChannelInputAcceptance) {
        val target = (acceptance as ChannelInputAcceptance.Accepted).target
        target.onInputCancelled("test complete")
        (target as CommittedTargetLeaseOwner).releaseCommittedTargetLease()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun TestScope.install(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
        preparerRegistry: CapabilityPreparerRegistry,
        keyboardOutputAdapterFactory: (CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter,
        dynamicChoiceResolver: DynamicConfigurationChoiceResolver = keyboardChoiceResolver(),
    ): ChannelImplementationId {
        val repository = repository(
            root,
            bridge,
            providers,
            preparerRegistry,
            keyboardOutputAdapterFactory,
            dynamicChoiceResolver,
        )
        val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)
        assertEquals(
            MutationResult.Installed(implementationId),
            success(repository.installOrUpdate(ByteArrayInputStream(releasedArtifact()), sourceRecord())),
        )
        return implementationId
    }

    private fun TestScope.repository(
        root: File,
        bridge: LuaKernelBridge,
        providers: ChannelImplementationProviderRegistry,
        preparerRegistry: CapabilityPreparerRegistry,
        keyboardOutputAdapterFactory: (CapabilityScopeIdentity, OutputExecutionOwner) -> KeyboardOutputAdapter,
        dynamicChoiceResolver: DynamicConfigurationChoiceResolver,
    ): InstalledPackageRepository = InstalledPackageRepository(
        store = InstalledPackageStore(
            root,
            NoOpPluginLogSink,
            null,
            preparerRegistry,
            dynamicChoiceResolver,
            keyboardOutputAdapterFactory,
        ),
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

    private fun TestScope.runtimeRegistry(
        providers: ChannelImplementationProviderRegistry,
        host: ChannelCapabilityHost,
    ): ChannelRuntimeRegistry = ChannelRuntimeRegistry(
        providers = providers,
        capabilityHost = host,
        invocationBoundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
            RuntimeInvocationPolicy(16, 1_000, 1_000, 1_000),
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

    private fun definition(
        id: String,
        implementationId: ChannelImplementationId,
        hostOs: String = "linux",
        hostLayout: String = "linux:us",
        hostProfile: String = "linux:us",
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = id,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(
            JSONObject()
                .put("host_os", hostOs)
                .put("host_layout", hostLayout)
                .put("host_profile", hostProfile),
        ),
    )

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity(REPOSITORY_ID),
        coordinates = GitHubRepositoryCoordinates("nilp0inter", "keyboard-channel"),
        release = GitHubReleaseIdentity(RELEASE_ID, "v$PACKAGE_VERSION", false),
        asset = GitHubAssetIdentity(ASSET_ID, "subspace-channel.zip"),
        ownerId = OFFICIAL_OWNER_ID,
    )

    /**
     * Host dynamic-choice resolution mirroring the production registry's source
     * keying: the three public keyboard sources resolve through one bounded
     * keymap hierarchy (platforms with no dependency -> layouts depending on
     * host_os -> profiles depending on host_layout). Resolution is inline
     * rather than deadline-wrapped so it stays deterministic on the test
     * dispatcher; deadline and publication validation are covered by the
     * source-registry tests. No Keyboard-package special case: the runtime sees
     * only detached available/unavailable reference states.
     */
    private fun keyboardChoiceResolver(): DynamicConfigurationChoiceResolver {
        val hierarchy = KeyboardOutputChoiceHierarchy(
            listOf(HostProfile("linux", "us"), HostProfile("macos", "us")),
        )
        return DynamicConfigurationChoiceResolver { request ->
            when (request.source.value) {
                DynamicChoiceSource.KEYBOARD_OUTPUT_PLATFORMS -> hierarchy.resolvePlatforms()
                DynamicChoiceSource.KEYBOARD_OUTPUT_LAYOUTS -> hierarchy.resolveLayouts(request)
                DynamicChoiceSource.KEYBOARD_OUTPUT_PROFILES -> hierarchy.resolveProfiles(request)
                else -> DynamicConfigurationChoiceResolution.Unavailable(
                    DynamicConfigurationChoiceUnavailableReason.SOURCE_UNAVAILABLE,
                )
            }
        }
    }

    private fun releasedArtifact(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_PATH)) {
            "Missing pinned keyboard release fixture $RESOURCE_PATH"
        }.use { stream ->
            stream.readBytes().also { bytes ->
                assertEquals("The runtime fixture must remain the exact reviewed release artifact (size).",
                    ARTIFACT_SIZE.toLong(), bytes.size.toLong())
                assertEquals("The runtime fixture must remain the exact reviewed release artifact (SHA-256).",
                    ARTIFACT_SHA256, sha256(bytes))
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected package operation success, got ${outcome.error}")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val root = createTempDirectory("external-keyboard-runtime-").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun configuredScalar(config: LuaValue, field: String): String? =
        (((config as? LuaValue.Map)?.pairs?.get("values") as? LuaValue.Map)
            ?.pairs?.get(field) as? LuaValue.StringValue)?.value

    private fun readinessReference(context: LuaValue, field: String): String? =
        (((context as? LuaValue.Map)?.pairs?.get("references") as? LuaValue.Map)
            ?.pairs?.get(field) as? LuaValue.StringValue)?.value

    private fun readinessCapability(context: LuaValue): String? =
        (((context as? LuaValue.Map)?.pairs?.get("capabilities") as? LuaValue.Map)
            ?.pairs?.get("keyboard.output") as? LuaValue.StringValue)?.value

    private fun assertAllReferencesAvailable(contexts: List<LuaValue>) {
        assertTrue(
            "Every readiness refresh must project all three detached hierarchy references as available.",
            contexts.isNotEmpty() && contexts.all { context ->
                readinessReference(context, "host_os") == "available" &&
                    readinessReference(context, "host_layout") == "available" &&
                    readinessReference(context, "host_profile") == "available"
            },
        )
    }

    /**
     * Native-boundary fake. Models per-state ownership and the readiness/preparation
     * wiring only; it does not interpret the package Lua source. [loadProgramImage]
     * merely accepts the immutable image delivered by the generic materializer.
     */
    private class KeyboardWiringBridge : LuaKernelBridge {
        private var nextStateId = 1L
        private val claims = mutableMapOf<Long, HostOperationClaim>()
        val createCalls = AtomicInteger()
        val createdStates = mutableListOf<Long>()
        val closedStates = mutableListOf<Long>()
        val startupConfigs = mutableListOf<LuaValue>()
        val inputCalls = AtomicInteger()
        val sosCalls = AtomicInteger()
        val readinessResponses = ArrayDeque<String>()
        val readinessContexts = mutableListOf<LuaValue>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            createCalls.incrementAndGet()
            val id = nextStateId++
            createdStates += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "keyboard-wiring")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
            completed(handle)

        override fun setResourceContext(handle: LuaStateHandle, resourceContextJson: String): LuaKernelOutcome =
            completed(handle)

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
            "keyboard-wiring",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closedStates += handle.stateId.value
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>,
        ): LuaKernelOutcome = completed(
            handle,
            JSONArray(listOf("startup", "handle_readiness", "handle_input", "handle_sos")).toString(),
        )

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            startupConfigs += config
            return completed(handle)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = when (callbackHandle.name) {
            "handle_readiness" -> {
                readinessContexts += arguments
                completed(
                    handle,
                    readinessResponses.removeFirstOrNull() ?: """{"ready":true,"status":"ready"}""",
                )
            }
            else -> completed(handle)
        }

        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            inputCalls.incrementAndGet()
            return completed(handle, """{"ok":true}""")
        }

        override fun invokeSosCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            sosCalls.incrementAndGet()
            return completed(handle, """{"ok":true}""")
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle)

        override fun claimHostOperation(handle: LuaStateHandle, requestId: Long): HostOperationClaim =
            claims[requestId] ?: HostOperationClaim.Rejected("E_STALE")

        private fun completed(handle: LuaStateHandle, value: String? = null): LuaKernelOutcome.Completed =
            LuaKernelOutcome.Completed(
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
                topology = "keyboard-wiring",
            )
    }

    /**
     * Fake host keyboard capability. Records text/key submissions and counts host
     * preparation calls so wiring tests can assert exactly-once preparation and the
     * absence of output effects.
     */
    private class KeyboardCapabilityHost(
        private val textOutputAvailability: CapabilityAvailability,
        private val textOutput: TextOutputCapability,
    ) : ChannelCapabilityHost {
        val prepareCalls = AtomicInteger()
        var prepareResult: HostedCapabilityAcquisition<TextOutputCapability> =
            HostedCapabilityAcquisition.Available(textOutput) { }

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = when (key) {
            CapabilityKey.TextOutput -> textOutputAvailability
            CapabilityKey.Transcription -> CapabilityAvailability.Available
            else -> CapabilityAvailability.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.TextOutput -> (when (textOutputAvailability) {
                CapabilityAvailability.Available -> HostedCapabilityAcquisition.Available(textOutput) { }
                CapabilityAvailability.Recoverable ->
                    HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
                is CapabilityAvailability.Unavailable ->
                    HostedCapabilityAcquisition.Unavailable(textOutputAvailability.reason)
            }) as HostedCapabilityAcquisition<T>
            CapabilityKey.Transcription ->
                HostedCapabilityAcquisition.Available(NoOpTranscription()) { } as HostedCapabilityAcquisition<T>
            else ->
                HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
                    as HostedCapabilityAcquisition<T>
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> {
            if (key == CapabilityKey.TextOutput) {
                prepareCalls.incrementAndGet()
                return prepareResult as HostedCapabilityAcquisition<T>
            }
            return acquire(identity, key)
        }
    }

    private class NoOpTranscription : dev.nilp0inter.subspace.channel.capability.TranscriptionCapability {
        override suspend fun transcribe(
            recording: dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording,
        ) = dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult.Success(
            dev.nilp0inter.subspace.channel.capability.Transcription("transcript"),
        )
    }

    private class RecordingTextOutput : TextOutputCapability {
        val textRequests = mutableListOf<TextOutputRequest>()
        val keyRequests = mutableListOf<TextKeyRequest>()
        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            textRequests += request
            return TextDeliveryOutcome.Delivered("op-1")
        }
        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome {
            keyRequests += request
            return TextDeliveryOutcome.Delivered("op-1")
        }
    }

    private class RecordingKeyboardAdapter(
        override val identity: CapabilityScopeIdentity,
        val owner: OutputExecutionOwner,
    ) : KeyboardOutputAdapter {
        val textRequests = mutableListOf<TextOutputRequest>()
        val keyRequests = mutableListOf<TextKeyRequest>()
        override suspend fun sendText(request: TextOutputRequest): KeyboardOutputSubmission {
            textRequests += request
            return KeyboardOutputSubmission.Completed(TextDeliveryOutcome.Delivered("op-1"))
        }
        override suspend fun sendKey(request: TextKeyRequest): KeyboardOutputSubmission {
            keyRequests += request
            return KeyboardOutputSubmission.Completed(TextDeliveryOutcome.Delivered("op-1"))
        }
    }

    private companion object {
        const val RESOURCE_PATH = "keyboard-channel/subspace-channel.zip"
        const val REPOSITORY_ID = "1310281239"
        const val PACKAGE_VERSION = "1.0.1"
        // Published v1.0.1 provenance (release tag commit
        // 4499f8ef7e5f06952bd10cdd5478619c1c78b24d).
        const val RELEASE_ID = "358955848"
        const val ASSET_ID = "487638331"
        const val OFFICIAL_OWNER_ID = "1224006"
        const val ARTIFACT_SIZE = 14236
        const val ARTIFACT_SHA256 = "aa1861438c90149a287dc2e300ab9238683905f90dffb4fec75d8f0d8cc6fdd1"
    }
}
