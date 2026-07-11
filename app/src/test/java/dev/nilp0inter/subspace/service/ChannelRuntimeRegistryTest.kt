package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRuntimeRegistryTest {
    @Test
    fun unavailableDefinitionsRemainOrderedWithTheirTypedProviderFailures() = runTest {
        val incompatible = TestProvider(ChannelImplementationId("test:incompatible")).apply {
            configuration.mode = ConfigurationMode.UNSUPPORTED
        }
        val migration = TestProvider(ChannelImplementationId("test:migration")).apply {
            configuration.mode = ConfigurationMode.MIGRATION_FAILURE
        }
        val invalid = TestProvider(ChannelImplementationId("test:invalid")).apply {
            configuration.mode = ConfigurationMode.INVALID
        }
        val construction = TestProvider(ChannelImplementationId("test:construction")).apply {
            constructResult = { request ->
                ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        request.definition.implementationId,
                        "constructor rejected request",
                    ),
                )
            }
        }
        val fixture = fixture(incompatible, migration, invalid, construction)
        val missing = definition("missing", "test:missing")
        val incompatibleDefinition = definition("incompatible", "test:incompatible")
        val migrationDefinition = definition("migration", "test:migration", version = 1)
        val invalidDefinition = definition("invalid", "test:invalid")
        val constructionDefinition = definition("construction", "test:construction")

        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(
                    missing,
                    incompatibleDefinition,
                    migrationDefinition,
                    invalidDefinition,
                    constructionDefinition,
                ),
                missing.id,
            ),
        )

        val snapshots = fixture.registry.runtimeSnapshots.value.entries
        assertEquals(
            listOf("missing", "incompatible", "migration", "invalid", "construction"),
            snapshots.map(ChannelRuntimeSnapshot::id),
        )
        assertTrue(providerError(snapshots[0]) is ChannelProviderError.MissingProvider)
        assertTrue(providerError(snapshots[1]) is ChannelProviderError.UnsupportedSchemaVersion)
        assertTrue(providerError(snapshots[2]) is ChannelProviderError.MigrationFailed)
        assertTrue(providerError(snapshots[3]) is ChannelProviderError.InvalidConfiguration)
        assertTrue(providerError(snapshots[4]) is ChannelProviderError.RuntimeConstructionFailed)
        assertEquals(
            ChannelInputAcceptance.Unavailable("Implementation provider test:missing is not registered"),
            fixture.registry.prepareInput(missing.id),
        )
    }
    @Test
    fun unavailableActiveSelectionRemainsOrderedInTheAggregateProjection() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:available"))
        val fixture = fixture(provider)
        val available = definition("available", "test:available")
        val unavailable = definition("unavailable", "test:missing")
        val definitions = listOf(available, unavailable)

        fixture.registry.reconcile(ChannelCatalogueSnapshot(definitions, available.id))
        runCurrent()

        val initial = fixture.registry.runtimeSnapshots.value
        assertEquals(available.id, initial.activeChannelId)
        assertEquals(definitions.map(ChannelDefinition::id), initial.entries.map(ChannelRuntimeSnapshot::id))
        assertEquals(ChannelPreparationAvailability.Available, initial.entries[0].preparation)
        assertTrue(providerError(initial.entries[1]) is ChannelProviderError.MissingProvider)
        val unavailablePreparation = initial.entries[1].preparation

        fixture.registry.reconcile(ChannelCatalogueSnapshot(definitions, unavailable.id))

        val selectedUnavailable = fixture.registry.runtimeSnapshots.value
        assertEquals(unavailable.id, selectedUnavailable.activeChannelId)
        assertEquals(definitions.map(ChannelDefinition::id), selectedUnavailable.entries.map(ChannelRuntimeSnapshot::id))
        assertEquals(ChannelPreparationAvailability.Available, selectedUnavailable.entries[0].preparation)
        assertEquals(unavailablePreparation, selectedUnavailable.entries[1].preparation)
    }


    @Test
    fun preparationCanReenterRegistryWithoutHoldingItsStructuralLock() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:reentrant"))
        val second = TestProvider(ChannelImplementationId("test:second"))
        val fixture = fixture(provider, second)
        val firstDefinition = definition("first", "test:reentrant")
        val secondDefinition = definition("second", "test:second")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(firstDefinition, secondDefinition), firstDefinition.id))
        val observed = mutableListOf<String>()
        provider.runtimes.single().prepareResult = {
            observed += requireNotNull(fixture.registry.getRuntimeSnapshot(secondDefinition.id)).id
            ChannelInputAcceptance.Refused("not accepting")
        }

        assertEquals(ChannelInputAcceptance.Refused("not accepting"), fixture.registry.prepareInput(firstDefinition.id))
        assertEquals(listOf(secondDefinition.id), observed)
    }

    @Test
    fun slowConstructionForOneInstanceDoesNotBlockAnotherInstanceRead() = runTest {
        val slow = TestProvider(ChannelImplementationId("test:slow"))
        val fast = TestProvider(ChannelImplementationId("test:fast"))
        val fixture = fixture(slow, fast)
        val slowDefinition = definition("slow", "test:slow", payload = "{\"version\":1}")
        val fastDefinition = definition("fast", "test:fast")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(slowDefinition, fastDefinition), fastDefinition.id))
        val slowConstructionStarted = CompletableDeferred<Unit>()
        val releaseSlowConstruction = CompletableDeferred<Unit>()
        slow.constructResult = { request ->
            slowConstructionStarted.complete(Unit)
            releaseSlowConstruction.await()
            ChannelRuntimeConstructionResult.Success(TestRuntime(request.definition))
        }

        val reconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(slowDefinition.copy(configPayload = opaque("{\"version\":2}")), fastDefinition),
                    fastDefinition.id,
                ),
            )
        }
        runCurrent()
        assertTrue(slowConstructionStarted.isCompleted)
        assertEquals(fastDefinition.id, fixture.registry.getRuntimeSnapshot(fastDefinition.id)?.id)
        assertEquals(listOf("slow", "fast"), fixture.registry.getAllRuntimeSnapshots().map(ChannelRuntimeSnapshot::id))

        releaseSlowConstruction.complete(Unit)
        reconciliation.await()
    }

    @Test
    fun staleConstructionCannotPublishOverTheCurrentGeneration() = runTest {
        val first = TestProvider(ChannelImplementationId("test:first"))
        val second = TestProvider(ChannelImplementationId("test:second"))
        val fixture = fixture(first, second)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        first.constructResult = { request ->
            firstStarted.complete(Unit)
            releaseFirst.await()
            ChannelRuntimeConstructionResult.Success(TestRuntime(request.definition))
        }
        val initial = definition("channel", "test:first")
        val replacement = definition("channel", "test:second")

        val staleReconciliation = async {
            fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(initial), initial.id))
        }
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(replacement), replacement.id))
        val current = second.runtimes.single()
        releaseFirst.complete(Unit)
        staleReconciliation.await()

        assertEquals(replacement.implementationId, fixture.registry.getRuntimeSnapshot(replacement.id)?.implementationId)
        assertEquals(0, first.runtimes.size)
        assertEquals(0, current.closeCount.get())
    }

    @Test
    fun committedTargetSurvivesSelectionReorderReplacementAndRemovalUntilReleased() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val other = TestProvider(ChannelImplementationId("test:other"))
        val fixture = fixture(provider, other)
        val original = definition("original", "test:provider")
        val selectedLater = definition("later", "test:other")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original, selectedLater), original.id))
        runCurrent()
        val originalRuntime = provider.runtimes.single()
        val events = mutableListOf<String>()
        originalRuntime.onClose = { events += "closed" }
        originalRuntime.prepareResult = {
            ChannelInputAcceptance.Accepted(RecordingTarget(events))
        }
        val committed = accepted(fixture.registry.prepareInput(original.id))

        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original, selectedLater), selectedLater.id))
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(selectedLater, original), selectedLater.id))
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(selectedLater, original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                selectedLater.id,
            ),
        )
        runCurrent()
        val replacement = provider.runtimes.last()
        replacement.prepareResult = { ChannelInputAcceptance.Refused("replacement received preparation") }
        assertEquals(
            ChannelInputAcceptance.Refused("replacement received preparation"),
            fixture.registry.prepareInput(original.id),
        )
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(selectedLater), selectedLater.id))

        assertEquals(0, originalRuntime.closeCount.get())
        committed.target.onInputCancelled("catalogue changed")
        committed.lease.releaseCommittedTargetLease()

        assertEquals(listOf("cancelled:catalogue changed", "closed"), events)
        assertEquals(1, originalRuntime.closeCount.get())
        assertEquals(1, replacement.closeCount.get())
    }

    @Test
    fun committedReleaseOutlivesGenericCallbackDeadlineAndRetiredRuntimeClosesAfterLeaseRelease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(
            provider,
            callbackTimeoutMillis = 5_000,
            inputReleasedTimeoutMillis = 7_000,
        )
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val events = mutableListOf<String>()
        runtime.onClose = { events += "runtime-closed" }
        runtime.prepareResult = {
            ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
                override fun onInputStarted(session: ChannelAudioInputSession) = Unit

                override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                    events += "release-started"
                    delay(6_000)
                    events += "release-effect-returned"
                    return ChannelInputResult.None
                }

                override fun onInputCancelled(reason: String) = Unit

                override fun onInputFailed(reason: String) = Unit
            })
        }
        val committed = accepted(fixture.registry.prepareInput(original.id))

        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()
        assertEquals(0, runtime.closeCount.get())

        val release = async {
            committed.target.onInputReleased(RecordedPcm(shortArrayOf(7), 16_000))
        }
        runCurrent()
        assertEquals(listOf("release-started"), events)

        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(release.isCompleted)
        assertEquals(0, runtime.closeCount.get())

        advanceTimeBy(999)
        runCurrent()
        assertEquals(ChannelInputResult.None, release.await())
        assertEquals(listOf("release-started", "release-effect-returned"), events)
        assertEquals(0, runtime.closeCount.get())

        committed.lease.releaseCommittedTargetLease()
        runCurrent()
        assertEquals(
            listOf("release-started", "release-effect-returned", "runtime-closed"),
            events,
        )
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun staleAcceptedPreparationIsCancelledAndNeverLeasesTheRetiredGeneration() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val events = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtime.prepareResult = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ChannelInputAcceptance.Accepted(RecordingTarget(events))
        }

        val preparation = async { fixture.registry.prepareInput(original.id) }
        runCurrent()
        assertTrue(started.isCompleted)
        val reconciliation = async {
            fixture.registry.reconcile(
                ChannelCatalogueSnapshot(
                    listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                    original.id,
                ),
            )
        }
        runCurrent()
        release.complete(Unit)
        reconciliation.await()

        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeClosed.message),
            preparation.await(),
        )
        assertEquals(listOf("cancelled:Channel channel changed during preparation"), events)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun refusedPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.prepareResult = { ChannelInputAcceptance.Refused("dependency unavailable") }

        assertEquals(
            ChannelInputAcceptance.Refused("dependency unavailable"),
            fixture.registry.prepareInput(original.id),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun thrownPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.prepareResult = { throw IllegalStateException("provider bug") }

        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeFailed().message),
            fixture.registry.prepareInput(original.id),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun timedOutPreparationReleasesAReplacedRuntimeWithoutCreatingALease() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider, callbackTimeoutMillis = 10)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        val started = CompletableDeferred<Unit>()
        runtime.prepareResult = {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            ChannelInputAcceptance.Refused("unreachable")
        }

        val preparation = async { fixture.registry.prepareInput(original.id) }
        runCurrent()
        assertTrue(started.isCompleted)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(
            ChannelInputAcceptance.Unavailable(ChannelPreparationReason.RuntimeTimedOut.message),
            preparation.await(),
        )
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        runCurrent()

        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun aggregateProjectionEmitsRuntimeChangesWithoutRefreshSampling() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val definition = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        val runtime = provider.runtimes.single()
        runCurrent()

        runtime.publish(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("transcriber stopped"),
            ),
            status = ChannelExecutionStatus.FAILED,
        )
        runCurrent()

        val projected = fixture.registry.runtimeSnapshots.value.entries.single()
        assertEquals(ChannelExecutionStatus.FAILED, projected.executionStatus)
        assertEquals(
            ChannelPreparationReason.RuntimeFailed("transcriber stopped"),
            (projected.preparation as ChannelPreparationAvailability.Unavailable).reason,
        )
    }

    @Test
    fun shutdownWaitsForTerminalCallbackBeforeClosingAndRepeatedShutdownDoesNotCloseAgain() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val order = mutableListOf<String>()
        val fixture = fixture(
            provider,
            onPttSessionCancelRequested = { order += "session-cancel-requested" },
            shutdownAwaitMillis = 1_000,
        )
        val definition = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
        runCurrent()
        val runtime = provider.runtimes.single()
        runtime.onClose = { order += "closed" }
        runtime.prepareResult = { ChannelInputAcceptance.Accepted(RecordingTarget(order)) }
        val committed = accepted(fixture.registry.prepareInput(definition.id))

        val shutdown = async { fixture.registry.shutdownAndAwait() }
        runCurrent()
        assertEquals(listOf("session-cancel-requested"), order)
        assertFalse(shutdown.isCompleted)
        committed.target.onInputCancelled("service stopping")
        committed.lease.releaseCommittedTargetLease()

        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown.await())
        assertEquals(
            listOf("session-cancel-requested", "cancelled:service stopping", "closed"),
            order,
        )
        assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, fixture.registry.shutdownAndAwait())
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun lateSnapshotsFromARetiredRuntimeCannotOverwriteTheReplacementProjection() = runTest {
        val provider = TestProvider(ChannelImplementationId("test:provider"))
        val fixture = fixture(provider)
        val original = definition("channel", "test:provider")
        fixture.registry.reconcile(ChannelCatalogueSnapshot(listOf(original), original.id))
        runCurrent()
        val retired = provider.runtimes.single()
        fixture.registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(original.copy(configPayload = opaque("{\"version\":2,\"revision\":2}"))),
                original.id,
            ),
        )
        val replacement = provider.runtimes.last()
        runCurrent()

        retired.publish(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("late retired failure"),
            ),
            status = ChannelExecutionStatus.FAILED,
        )
        replacement.publish(ChannelPreparationAvailability.Available, ChannelExecutionStatus.SUCCESS)
        runCurrent()

        val projected = fixture.registry.runtimeSnapshots.value.entries.single()
        assertEquals(ChannelExecutionStatus.SUCCESS, projected.executionStatus)
        assertEquals(ChannelPreparationAvailability.Available, projected.preparation)
        assertEquals(1, retired.closeCount.get())
    }

    private fun TestScope.fixture(
        vararg providers: TestProvider,
        callbackTimeoutMillis: Long = 1_000,
        inputReleasedTimeoutMillis: Long = 1_000,
        closeTimeoutMillis: Long = 1_000,
        shutdownAwaitMillis: Long = 1_000,
        onPttSessionCancelRequested: suspend () -> Unit = {},
    ): RegistryFixture {
        val providerRegistry = ChannelImplementationProviderRegistry()
        providers.forEach { provider -> providerRegistry.register(provider) }
        val worker = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler))
        val invocationBoundary = RuntimeInvocationBoundary(
            worker,
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = callbackTimeoutMillis,
                inputReleasedTimeoutMillis = inputReleasedTimeoutMillis,
                closeTimeoutMillis = closeTimeoutMillis,
            ),
        )
        return RegistryFixture(
            registry = ChannelRuntimeRegistry(
                providers = providerRegistry,
                capabilityHost = NoCapabilities,
                invocationBoundary = invocationBoundary,
                runtimeScope = backgroundScope,
                closeScope = backgroundScope,
                onPttSessionCancelRequested = onPttSessionCancelRequested,
                shutdownAwaitMillis = shutdownAwaitMillis,
            ),
        )
    }

    private fun definition(
        id: String,
        implementationId: String,
        version: Int = 2,
        payload: String = "{\"version\":2}",
    ): ChannelDefinition = ChannelDefinition(
        id = id,
        name = "Name for $id",
        implementationId = ChannelImplementationId(implementationId),
        enabled = true,
        configSchemaVersion = version,
        configPayload = opaque(payload),
    )

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private fun accepted(acceptance: ChannelInputAcceptance): CommittedInput {
        val target = (acceptance as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected an accepted leased target, got $acceptance")
        val lease = target as? CommittedTargetLeaseOwner
            ?: throw AssertionError("Accepted target did not carry a committed lease")
        return CommittedInput(target, lease)
    }

    private fun providerError(snapshot: ChannelRuntimeSnapshot): ChannelProviderError {
        val unavailable = snapshot.preparation as? ChannelPreparationAvailability.Unavailable
            ?: throw AssertionError("Expected unavailable provider entry, got ${snapshot.preparation}")
        return (unavailable.reason as? ChannelPreparationReason.Provider)?.error
            ?: throw AssertionError("Expected provider reason, got ${unavailable.reason}")
    }

    private data class RegistryFixture(val registry: ChannelRuntimeRegistry)
    private data class CommittedInput(
        val target: ChannelInputTarget,
        val lease: CommittedTargetLeaseOwner,
    )

    private enum class ConfigurationMode {
        VALID,
        UNSUPPORTED,
        MIGRATION_FAILURE,
        INVALID,
    }

    private class TestProvider(
        implementationId: ChannelImplementationId,
    ) : ChannelImplementationProvider {
        val configuration = TestConfigurationProvider(implementationId)
        val runtimes = mutableListOf<TestRuntime>()
        var constructResult: suspend (ChannelRuntimeConstructionRequest) -> ChannelRuntimeConstructionResult = { request ->
            val runtime = TestRuntime(request.definition)
            runtimes += runtime
            ChannelRuntimeConstructionResult.Success(runtime)
        }

        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Test", "test summary", "test unavailable"),
            configuration = configuration,
            configurationFields = listOf(ChannelConfigurationField.TextField("value", "Value")),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(
            request: ChannelRuntimeConstructionRequest,
        ): ChannelRuntimeConstructionResult {
            val result = constructResult(request)
            if (result is ChannelRuntimeConstructionResult.Success && result.runtime is TestRuntime) {
                if (result.runtime !in runtimes) runtimes += result.runtime
            }
            return result
        }
    }

    private class TestConfigurationProvider(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        var mode = ConfigurationMode.VALID
        override val currentSchemaVersion: Int = 2

        override fun defaultPayload(): OpaqueJsonObject = opaque("{\"version\":2}")

        override fun validate(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = validated(schemaVersion, payload)

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Success(
            opaque("{\"version\":${fromSchemaVersion + 1}}"),
        )

        override fun migrateAndValidate(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = when (mode) {
            ConfigurationMode.UNSUPPORTED -> ProviderConfigurationResult.Failure(
                ChannelProviderError.UnsupportedSchemaVersion(implementationId, schemaVersion, currentSchemaVersion),
            )
            ConfigurationMode.MIGRATION_FAILURE -> ProviderConfigurationResult.Failure(
                ChannelProviderError.MigrationFailed(implementationId, schemaVersion, "migration rejected"),
            )
            ConfigurationMode.INVALID -> ProviderConfigurationResult.Failure(
                ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, "value rejected"),
            )
            ConfigurationMode.VALID -> {
                if (schemaVersion < currentSchemaVersion) {
                    validated(currentSchemaVersion, opaque("{\"version\":$currentSchemaVersion}"))
                } else {
                    validated(schemaVersion, payload)
                }
            }
        }

        private fun validated(
            schemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ProviderConfigurationResult = ProviderConfigurationResult.Success(
            ValidatedChannelConfiguration(implementationId, schemaVersion, payload),
        )
    }

    private class TestRuntime(
        private val definition: ChannelDefinition,
    ) : ChannelRuntime {
        override val id: String = definition.id
        private val snapshots = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = definition.id,
                name = definition.name,
                implementationId = definition.implementationId,
                enabled = definition.enabled,
                preparation = ChannelPreparationAvailability.Available,
                executionStatus = ChannelExecutionStatus.IDLE,
            ),
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = snapshots.asStateFlow()
        val closeCount = AtomicInteger(0)
        var prepareResult: suspend () -> ChannelInputAcceptance = {
            ChannelInputAcceptance.Refused("no test input configured")
        }
        var onClose: suspend () -> Unit = {}

        override suspend fun prepareInput(): ChannelInputAcceptance = prepareResult()

        override suspend fun close() {
            closeCount.incrementAndGet()
            onClose()
        }

        fun publish(
            preparation: ChannelPreparationAvailability,
            status: ChannelExecutionStatus,
        ) {
            snapshots.value = snapshots.value.copy(preparation = preparation, executionStatus = status)
        }
    }

    private class RecordingTarget(
        private val events: MutableList<String>,
    ) : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) = Unit

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None

        override fun onInputCancelled(reason: String) {
            events += "cancelled:$reason"
        }

        override fun onInputFailed(reason: String) {
            events += "failed:$reason"
        }

        override fun onInputPlaybackCompleted() {
            events += "playback-completed"
        }
    }

    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.HOST_NOT_READY,
        )
    }
}
