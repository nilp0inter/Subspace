package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.resolveLocalAudioRoute
import dev.nilp0inter.subspace.channel.KeyboardBuiltInProvider
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisitionPolicy
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.Transcription
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.model.ChannelCatalogueCodec
import dev.nilp0inter.subspace.model.ChannelCatalogueDecodeResult
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
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ProviderConfigurationResult
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.KeyboardProviderConfiguration
import dev.nilp0inter.subspace.model.KeyboardProviderConfigurationCodec
import dev.nilp0inter.subspace.lua.LuaNativeKernel
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceIntegrationTest {
    @Test
    fun persistedV1AndV2DefinitionsResolveGenericallyAndPreserveUnavailableOrder() = runTest {
        val provider = CompositionProvider(BuiltInChannelImplementationIds.JOURNAL)
        val registry = compositionRegistry(provider, RecordingCapabilityHost())

        val v1 = decoded(
            """
            {"version":1,"activeChannelId":"second","definitions":[
              {"id":"first","name":"First","kind":"JOURNAL","enabled":true,"configSchemaVersion":1,
               "config":{"stage":1,"profile":"first","future":{"keep":true}}},
              {"id":"second","name":"Second","kind":"JOURNAL","enabled":true,"configSchemaVersion":1,
               "config":{"stage":1,"profile":"second","future":{"keep":false}}}
            ]}
            """.trimIndent(),
        )
        registry.reconcile(v1.snapshot)
        advanceUntilIdle()

        assertEquals(1, v1.sourceDocumentVersion)
        assertEquals(listOf("first", "second"), registry.runtimeSnapshots.value.entries.map { it.id })
        assertEquals(PttDispatchDecision.Dispatch("second"), decidePttDispatch(registry.runtimeSnapshots.value))
        assertEquals(listOf("first", "second"), provider.constructions.map { it.instanceId })
        assertEquals(listOf("first", "second"), provider.constructions.map { it.profile })
        assertEquals(listOf("first", "second"), provider.constructions.map { it.scopeIdentity.channelInstanceId })
        assertTrue(provider.constructions.first().payload.toJsonObject().getJSONObject("future").getBoolean("keep"))

        val v2 = decoded(
            """
            {"version":2,"activeChannelId":"missing","definitions":[
              {"id":"missing","name":"Offline extension","implementationId":"external:absent","enabled":true,
               "configSchemaVersion":7,"config":{"preserve":"unchanged"}},
              {"id":"second","name":"Second","implementationId":"builtin:journal","enabled":true,
               "configSchemaVersion":2,"config":{"stage":2,"profile":"second","future":{"keep":false}}}
            ]}
            """.trimIndent(),
        )
        registry.reconcile(v2.snapshot)
        advanceUntilIdle()

        assertEquals(2, v2.sourceDocumentVersion)
        assertEquals(listOf("missing", "second"), registry.runtimeSnapshots.value.entries.map { it.id })
        assertEquals(PttDispatchDecision.ErrorBeep("missing"), decidePttDispatch(registry.runtimeSnapshots.value))
        assertTrue(registry.prepareInput("missing") is ChannelInputAcceptance.Unavailable)
        assertEquals("unchanged", v2.snapshot.definitions.first().configPayload.toJsonObject().getString("preserve"))

        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
    }

    @Test
    fun unavailableCapabilityRefusesBeforeCaptureOrReadyBeep() = runTest {
        val provider = CompositionProvider(ChannelImplementationId("test:needs-text"))
        val capabilityHost = RecordingCapabilityHost(unavailableInstances = setOf("needs-text"))
        val registry = compositionRegistry(provider, capabilityHost)
        val definition = ChannelDefinition(
            id = "needs-text",
            name = "Needs text output",
            implementationId = provider.descriptor.implementationId,
            enabled = true,
            configSchemaVersion = 2,
            configPayload = opaque("""{"stage":2,"profile":"blocked"}"""),
        )
        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        advanceUntilIdle()

        val output = RecordingOutput()
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(4, 2))
        val manager = PttAudioSessionManager(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            channelRouter = registry,
            resolvePttAudioRoute = { resolveLocalAudioRoute(output, source) },
        )

        assertTrue(manager.start(dev.nilp0inter.subspace.model.PttSource.Phone, definition.id, dev.nilp0inter.subspace.model.InputMode.OnAPinch))
        advanceUntilIdle()

        assertEquals(listOf("needs-text"), capabilityHost.acquisitions.map { it.channelInstanceId })
        assertEquals(0, source.openCount)
        assertEquals(0, output.readyBeeps)
        assertEquals(1, output.errorBeeps)
        assertFalse(manager.isActive)
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, registry.shutdownAndAwait())
    }

    @Test
    fun kotlinCataloguePttReconciliationAndRestartRemainLuaDormant() = runTest {
        LuaNativeKernel.resetForTest()
        ActorRuntimeFactory.resetForTest()
        try {
            val first = ChannelDefinition(
                id = "first",
                name = "First keyboard",
                implementationId = BuiltInChannelImplementationIds.KEYBOARD,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration("linux:us")),
            )
            val second = ChannelDefinition(
                id = "second",
                name = "Second keyboard",
                implementationId = BuiltInChannelImplementationIds.KEYBOARD,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration("windows:us")),
            )
            val catalogue = ChannelCatalogueSnapshot(listOf(first, second), first.id)
            val firstHost = RecordingCapabilityHost(transcript = "first transcript")
            val firstRegistry = compositionRegistry(KeyboardBuiltInProvider(), firstHost)

            firstRegistry.reconcile(catalogue)
            advanceUntilIdle()
            firstRegistry.refreshReadiness()
            advanceUntilIdle()
            assertEquals(listOf(first.id, second.id), firstRegistry.runtimeSnapshots.value.entries.map { it.id })

            val firstSource = CaptureServiceFakes.singleShotSource(shortArrayOf(4, -2))
            val firstOutput = RecordingOutput()
            val firstManager = PttAudioSessionManager(
                scope = this,
                captureService = CaptureServiceFakes.newService(this),
                channelRouter = firstRegistry,
                resolvePttAudioRoute = { resolveLocalAudioRoute(firstOutput, firstSource) },
            )
            assertTrue(firstManager.start(dev.nilp0inter.subspace.model.PttSource.Phone, first.id, dev.nilp0inter.subspace.model.InputMode.OnAPinch))
            runCurrent()
            assertTrue(firstManager.release(dev.nilp0inter.subspace.model.PttSource.Phone))
            advanceUntilIdle()

            assertEquals(listOf("first:first transcript "), firstHost.deliveredTexts)
            assertEquals(1, firstSource.openCount)
            assertEquals(0, firstOutput.errorBeeps)

            firstRegistry.reconcile(catalogue.copy(activeChannelId = second.id))
            firstRegistry.refreshReadiness()
            advanceUntilIdle()
            assertEquals(second.id, firstRegistry.runtimeSnapshots.value.activeChannelId)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, firstRegistry.shutdownAndAwait())

            val restartedHost = RecordingCapabilityHost(transcript = "second transcript")
            val restartedRegistry = compositionRegistry(KeyboardBuiltInProvider(), restartedHost)
            restartedRegistry.reconcile(catalogue.copy(activeChannelId = second.id))
            restartedRegistry.refreshReadiness()
            advanceUntilIdle()

            val restartedSource = CaptureServiceFakes.singleShotSource(shortArrayOf(7, -3))
            val restartedOutput = RecordingOutput()
            val restartedManager = PttAudioSessionManager(
                scope = this,
                captureService = CaptureServiceFakes.newService(this),
                channelRouter = restartedRegistry,
                resolvePttAudioRoute = { resolveLocalAudioRoute(restartedOutput, restartedSource) },
            )
            assertTrue(restartedManager.start(dev.nilp0inter.subspace.model.PttSource.Phone, second.id, dev.nilp0inter.subspace.model.InputMode.OnAPinch))
            runCurrent()
            assertTrue(restartedManager.release(dev.nilp0inter.subspace.model.PttSource.Phone))
            advanceUntilIdle()

            assertEquals(listOf("second:second transcript "), restartedHost.deliveredTexts)
            assertEquals(1, restartedSource.openCount)
            assertEquals(0, restartedOutput.errorBeeps)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, restartedRegistry.shutdownAndAwait())
            assertFalse(ActorRuntimeFactory.isCreateAttempted)
            assertFalse(LuaNativeKernel.isLoadAttempted)
        } finally {
            ActorRuntimeFactory.resetForTest()
            LuaNativeKernel.resetForTest()
        }
    }
}

internal fun TestScope.compositionRegistry(
    provider: ChannelImplementationProvider,
    capabilityHost: RecordingCapabilityHost,
    onPttSessionCancelRequested: suspend () -> Unit = {},
): ChannelRuntimeRegistry {
    val providers = ChannelImplementationProviderRegistry()
    check(providers.register(provider) is dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult.Registered)
    val workers = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler))
    return ChannelRuntimeRegistry(
        providers = providers,
        capabilityHost = capabilityHost,
        invocationBoundary = RuntimeInvocationBoundary(workers),
        runtimeScope = this,
        closeScope = this,
        onPttSessionCancelRequested = onPttSessionCancelRequested,
    )
}

internal fun decoded(json: String) = (ChannelCatalogueCodec.decode(json) as? ChannelCatalogueDecodeResult.Success)
    ?.document ?: throw AssertionError("Expected valid persisted catalogue")

internal fun opaque(json: String): OpaqueJsonObject = OpaqueJsonObject.parse(json).getOrThrow()

/** Test-only provider exercising only the public generic provider and capability contracts. */
internal class CompositionProvider(
    implementationId: ChannelImplementationId,
) : ChannelImplementationProvider {
    private val configuration = CompositionConfiguration(implementationId)
    val constructions = mutableListOf<Construction>()
    val runtimes = mutableListOf<CompositionRuntime>()

    override val descriptor = ChannelImplementationDescriptor(
        implementationId = implementationId,
        presentation = ChannelPresentationMetadata("Composition", "Composition test provider", "Provider unavailable"),
        configuration = configuration,
        configurationFields = listOf(ChannelConfigurationField.TextField("profile", "Profile")),
        requiredCapabilities = setOf(ChannelCapability.TextOutput),
        preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = true),
    )

    override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult {
        val profile = request.configuration.payload.toJsonObject().getString("profile")
        constructions += Construction(request.definition.id, profile, request.capabilities.identity, request.configuration.payload)
        val runtime = CompositionRuntime(request.definition, request.capabilities, profile)
        runtimes += runtime
        return ChannelRuntimeConstructionResult.Success(runtime)
    }

    data class Construction(
        val instanceId: String,
        val profile: String,
        val scopeIdentity: CapabilityScopeIdentity,
        val payload: OpaqueJsonObject,
    )
}

private class CompositionConfiguration(
    override val implementationId: ChannelImplementationId,
) : ChannelConfigurationProvider {
    override val currentSchemaVersion: Int = 2

    override fun defaultPayload(): OpaqueJsonObject = opaque("""{"stage":2,"profile":"new"}""")

    override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult {
        val profile = payload.toJsonObject().optString("profile")
        return if (schemaVersion == currentSchemaVersion && payload.toJsonObject().optInt("stage") == currentSchemaVersion && profile.isNotBlank()) {
            ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))
        } else {
            ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, "stage and profile are required"),
            )
        }
    }

    override fun migrateStep(
        fromSchemaVersion: Int,
        payload: OpaqueJsonObject,
    ): ChannelConfigurationMigrationStep = if (fromSchemaVersion == 1) {
        ChannelConfigurationMigrationStep.Success(
            OpaqueJsonObject.fromJsonObject(payload.toJsonObject().put("stage", currentSchemaVersion)),
        )
    } else {
        ChannelConfigurationMigrationStep.Failure(
            ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
        )
    }
}

internal class CompositionRuntime(
    private val definition: ChannelDefinition,
    private val capabilities: ChannelCapabilityScope,
    val profile: String,
) : ChannelRuntime {
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = ChannelPreparationAvailability.Available,
            executionStatus = ChannelExecutionStatus.IDLE,
        ),
    )
    var releaseGate: CompletableDeferred<Unit>? = null
    private var textLease: dev.nilp0inter.subspace.channel.capability.CapabilityLease<TextOutputCapability>? = null
    val events = mutableListOf<String>()
    var closeCount = 0
        private set

    override val id: String = definition.id
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override suspend fun prepareInput(): ChannelInputAcceptance {
        val acquisition = capabilities.acquire(
            CapabilityKey.TextOutput,
            CapabilityAcquisitionPolicy.PrepareRecoverable(100),
        )
        val lease = (acquisition as? CapabilityAcquisition.Available)?.lease
            ?: return ChannelInputAcceptance.Unavailable("Text output is unavailable")
        textLease = lease
        return ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) = Unit

            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                events += "released:$profile"
                releaseGate?.await()
                return ChannelInputResult.None
            }

            override fun onInputCancelled(reason: String) {
                events += "cancelled:$profile:$reason"
            }

            override fun onInputFailed(reason: String) {
                events += "failed:$profile:$reason"
            }
        })
    }

    suspend fun sendLateText(): CapabilityOperationResult<TextDeliveryOutcome>? = textLease?.use { port ->
        CapabilityOperationResult.Success(
            port.sendText(TextOutputRequest("late", TextOutputProfile("test:profile"))),
        )
    }

    override suspend fun close() {
        closeCount += 1
        events += "closed:$profile"
    }
}

internal class RecordingCapabilityHost(
    private val unavailableInstances: Set<String> = emptySet(),
    private val transcript: String? = null,
) : ChannelCapabilityHost {
    val acquisitions = mutableListOf<CapabilityScopeIdentity>()
    val cleanup = mutableListOf<String>()
    val deliveredTexts = mutableListOf<String>()

    override suspend fun availability(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<*>,
    ): CapabilityAvailability = if (identity.channelInstanceId in unavailableInstances) {
        CapabilityAvailability.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
    } else {
        CapabilityAvailability.Available
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : ChannelCapabilityPort> acquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
    ): HostedCapabilityAcquisition<T> {
        acquisitions += identity
        if (identity.channelInstanceId in unavailableInstances) {
            return HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
        }
        return when (key) {
            CapabilityKey.TextOutput -> HostedCapabilityAcquisition.Available(
                object : TextOutputCapability {
                    override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
                        deliveredTexts += "${identity.channelInstanceId}:${request.text}"
                        return TextDeliveryOutcome.Delivered("delivery-${identity.channelInstanceId}")
                    }

                    override suspend fun sendKey(request: dev.nilp0inter.subspace.channel.capability.TextKeyRequest): TextDeliveryOutcome =
                        TextDeliveryOutcome.Delivered("key-${identity.channelInstanceId}")
                },
                cleanup = { termination -> cleanup += "${identity.channelInstanceId}:${identity.runtimeGeneration.value}:$termination" },
            )
            CapabilityKey.Transcription -> transcript?.let { text ->
                HostedCapabilityAcquisition.Available(
                    object : TranscriptionCapability {
                        override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> =
                            CapabilityOperationResult.Success(Transcription(text))
                    },
                    cleanup = { termination -> cleanup += "${identity.channelInstanceId}:${identity.runtimeGeneration.value}:$termination" },
                )
            } ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.HOST_NOT_READY)
            else -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        } as HostedCapabilityAcquisition<T>
    }

    override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
        identity: CapabilityScopeIdentity,
        key: CapabilityKey<T>,
        timeoutMillis: Long,
    ): HostedCapabilityAcquisition<T> = acquire(identity, key)
}
internal class RecordingOutput : PcmOutput {
    var readyBeeps = 0
        private set
    var errorBeeps = 0
        private set
    var routeReleases = 0
        private set

    override suspend fun playReadyBeep(coldStart: Boolean) {
        readyBeeps += 1
    }

    override suspend fun playErrorBeep(coldStart: Boolean) {
        errorBeeps += 1
    }

    override suspend fun play(recording: RecordedPcm) = Unit

    override suspend fun releaseRoute() {
        routeReleases += 1
    }
}
