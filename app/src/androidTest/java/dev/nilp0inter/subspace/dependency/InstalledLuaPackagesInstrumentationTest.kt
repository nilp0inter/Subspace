package dev.nilp0inter.subspace.dependency

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.lua.API_VERSION
import dev.nilp0inter.subspace.lua.LUA_VERSION
import dev.nilp0inter.subspace.lua.LuaCallbackHandle
import dev.nilp0inter.subspace.lua.LuaCoroutineId
import dev.nilp0inter.subspace.lua.LuaKernelBridge
import dev.nilp0inter.subspace.lua.LuaKernelConfig
import dev.nilp0inter.subspace.lua.LuaKernelOutcome
import dev.nilp0inter.subspace.lua.LuaNativeKernel
import dev.nilp0inter.subspace.lua.LuaNativeKernelBridge
import dev.nilp0inter.subspace.lua.LuaOperationHandle
import dev.nilp0inter.subspace.lua.LuaSpawnAdmission
import dev.nilp0inter.subspace.lua.LuaStateHandle
import dev.nilp0inter.subspace.lua.LuaValue
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.DebugProviderConfiguration
import dev.nilp0inter.subspace.model.DebugProviderConfigurationCodec
import dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.InstalledProvidersPublicationResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistry
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistryShutdownResult
import dev.nilp0inter.subspace.service.DebugBuiltInProvider
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only installed-package evidence. These tests use deterministic, source-only ZIP bytes
 * with Unix central-directory metadata and route them through the production repository,
 * materializer, provider registry, runtime registry, actor factory, and JNI bridge.
 *
 * Run later on B02PTT-FF01 with:
 * `adb shell am instrument -w -e class dev.nilp0inter.subspace.dependency.InstalledLuaPackagesInstrumentationTest \
 * dev.nilp0inter.subspace.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class InstalledLuaPackagesInstrumentationTest {
    @Test
    fun exactV1PackageCreatesPackagedJniStateAndReachesTimerReadiness() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { fixture ->
                val definition = installedDefinition("installed-package-device-channel", INSTALLED_ID)
                fixture.catalogue(definition)

                ActorRuntimeFactory.resetForTest()
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(fixture.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )

                assertTrue("installed provider must resolve after committed publication", fixture.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertTrue("production actor factory must construct the installed provider generation", ActorRuntimeFactory.isCreateAttempted)
                assertTrue("installed provider construction must load the packaged JNI library", LuaNativeKernel.isLoaded)
                assertEquals("one JNI state must be created for the enabled installed definition", 1, fixture.bridge.createdStateIds.size)
                val v1State = fixture.bridge.createdStateIds.single()
                assertTrue(
                    "the bounded Lua timer must make the v1 package input-ready",
                    awaitLuaInputAcceptance(fixture.runtimeRegistry, definition.id, fixture.bridge, v1State, awaitTimerResume = true),
                )
            }
        }
    }

    @Test
    fun updateRemovalAndRestartCloseOldGenerationBeforeSuccessorAndKeepBuiltInAvailable() = runBlocking {
        withPrivateStore { root ->
            val definition = installedDefinition("installed-package-update-channel", INSTALLED_ID)
            val first = ProductionFixture(root)
            try {
                first.catalogue(definition)
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val v1State = first.bridge.createdStateIds.single()
                assertTrue(
                    "v1 must become input-ready before its replacement",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v1State, awaitTimerResume = true),
                )
                val v1Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertEquals(
                    MutationResult.Updated(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v2Archive()), sourceRecord(SOURCE_REPOSITORY, "102", "202"))),
                )
                val v2State = first.bridge.createdStateIds.last()
                val v2Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertNotEquals("a changed exact digest must allocate a successor Lua generation", v1Generation, v2Generation)
                assertNotEquals("a changed exact digest must allocate a successor JNI state", v1State, v2State)
                assertTrue("the old JNI state must close before successor startup", first.bridge.closedBeforeStartup(v1State, v2State))
                assertTrue("v2 must become ready through the successor JNI state", awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v2State, awaitTimerResume = false))
                assertFalse(
                    "a late v1 timer must not mutate v2 readiness after v1 closes",
                    inputRefusedAfterTimerWindow(first.runtimeRegistry, definition.id),
                )

                val committed = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("the committed active revision must be the v2 digest", digestOf(v2Archive()), committed.active.digest.value)
                assertEquals("the committed rollback revision must remain v1", digestOf(v1Archive()), committed.rollback?.digest?.value)
            } finally {
                first.close()
            }

            ProductionFixture(root).useSuspending { restarted ->
                restarted.catalogue(definition)
                assertEquals(Unit, success(restarted.repository.loadAndPublish()))
                assertTrue("restart must materialize the committed v2 provider", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertTrue("restart must allocate a fresh v2 JNI state", restarted.bridge.createdStateIds.size == 1)
                assertTrue("fresh v2 state must reach readiness without retained v1 globals", awaitLuaInputAcceptance(restarted.runtimeRegistry, definition.id, restarted.bridge, restarted.bridge.createdStateIds.single(), awaitTimerResume = false))

                assertEquals(MutationResult.Removed(INSTALLED_ID), success(restarted.repository.remove(SOURCE_REPOSITORY)))
                assertTrue("removed installed provider must no longer resolve", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Missing)
                assertTrue("removal must close the active JNI state", restarted.bridge.closedStateIds.isNotEmpty())
                assertTrue("built-in provider resolution must survive installed removal", restarted.providers.resolve(BuiltInChannelImplementationIds.DEBUG) is ChannelProviderResolution.Available)
            }
        }
    }

    @Test
    fun rollbackRemovalReinstallAndRestartUseFreshCommittedPackageGenerations() = runBlocking {
        withPrivateStore { root ->
            val definition = installedDefinition("installed-package-lifecycle-channel", INSTALLED_ID)
            val first = ProductionFixture(root)
            try {
                first.catalogue(definition)
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val v1State = first.bridge.createdStateIds.single()
                assertTrue(
                    "v1 must become ready before update",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v1State, awaitTimerResume = true, stage = "initial v1 before update"),
                )
                val v1Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration

                assertEquals(
                    MutationResult.Updated(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v2Archive()), sourceRecord(SOURCE_REPOSITORY, "102", "202"))),
                )
                val v2State = first.bridge.createdStateIds.last()
                val v2Generation = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                assertTrue(
                    "v2 must become ready before explicit rollback",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, v2State, awaitTimerResume = false, stage = "v2 before explicit rollback"),
                )
                assertNotEquals("update must replace the v1 Lua state", v1State, v2State)
                assertNotEquals("update must replace the v1 runtime generation", v1Generation, v2Generation)

                assertEquals(MutationResult.RolledBack(INSTALLED_ID), success(first.repository.rollback(SOURCE_REPOSITORY)))
                val rollbackState = first.bridge.createdStateIds.last()
                val rollbackGeneration = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                val rolledBack = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("rollback must commit the retained v1 digest as active", digestOf(v1Archive()), rolledBack.active.digest.value)
                assertEquals("rollback must retain v2 as the next explicit rollback revision", digestOf(v2Archive()), rolledBack.rollback?.digest?.value)
                assertNotEquals("rollback must allocate a fresh JNI state", v2State, rollbackState)
                assertNotEquals("rollback must allocate a fresh runtime generation", v2Generation, rollbackGeneration)
                assertTrue("v2 must close before the rolled-back v1 successor starts", first.bridge.closedBeforeStartup(v2State, rollbackState))
                assertTrue(
                    "rolled-back v1 must reach readiness from its fresh volatile state",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, rollbackState, awaitTimerResume = true, stage = "rolled-back v1 before removal"),
                )

                assertEquals(MutationResult.Removed(INSTALLED_ID), success(first.repository.remove(SOURCE_REPOSITORY)))
                assertTrue("removed provider must resolve as missing", first.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Missing)
                assertTrue("removed definition must retain its ordered unavailable projection", first.runtimeRegistry.preparation(definition.id) is ChannelPreparationAvailability.Unavailable)
                assertEquals(listOf(definition.id), first.runtimeRegistry.getAllRuntimeSnapshots().map { it.id })
                assertTrue("removal must reject input through the preserved unavailable definition", first.runtimeRegistry.prepareInput(definition.id) is ChannelInputAcceptance.Unavailable)
                assertTrue("removal must close the rolled-back JNI state", rollbackState in first.bridge.closedStateIds)

                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(first.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                val reinstalledState = first.bridge.createdStateIds.last()
                val reinstalledGeneration = requireNotNull(first.runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
                assertNotEquals("reinstall must never revive the removed JNI state", rollbackState, reinstalledState)
                assertNotEquals("reinstall must never revive the removed runtime generation", rollbackGeneration, reinstalledGeneration)
                assertTrue("removed generation must close before reinstalled v1 starts", first.bridge.closedBeforeStartup(rollbackState, reinstalledState))
                assertTrue(
                    "reinstalled v1 must become ready with no retained Lua globals",
                    awaitLuaInputAcceptance(first.runtimeRegistry, definition.id, first.bridge, reinstalledState, awaitTimerResume = true, stage = "reinstalled v1 before restart"),
                )
                val reinstalled = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("reinstall must persist the exact v1 digest", digestOf(v1Archive()), reinstalled.active.digest.value)
                assertEquals("first install after removal must not infer a rollback revision", null, reinstalled.rollback)
            } finally {
                first.close()
            }

            ProductionFixture(root).useSuspending { restarted ->
                restarted.catalogue(definition)
                assertEquals(Unit, success(restarted.repository.loadAndPublish()))
                assertTrue("restart must rematerialize the committed installed provider", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                assertEquals("restart must create exactly one new JNI state for the enabled definition", 1, restarted.bridge.createdStateIds.size)
                val restartedState = restarted.bridge.createdStateIds.single()
                assertTrue(
                    "restart must reach v1 readiness without retaining prior-process Lua globals",
                    awaitLuaInputAcceptance(restarted.runtimeRegistry, definition.id, restarted.bridge, restartedState, awaitTimerResume = true, stage = "restarted v1"),
                )
                val recovered = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(SOURCE_REPOSITORY)
                assertEquals("restart must retain the committed v1 digest", digestOf(v1Archive()), recovered.active.digest.value)
                assertEquals("restart must not manufacture a rollback revision", null, recovered.rollback)
            }
        }
    }

    @Test
    fun corruptInstalledPackageStaysUnavailableWhileSiblingAndBuiltInRemainOperational() = runBlocking {
        withPrivateStore { root ->
            ProductionFixture(root).useSuspending { staging ->
                assertEquals(
                    MutationResult.Installed(INSTALLED_ID),
                    success(staging.repository.installOrUpdate(ByteArrayInputStream(v1Archive()), sourceRecord(SOURCE_REPOSITORY, "101", "201"))),
                )
                assertEquals(
                    MutationResult.Installed(CORRUPT_ID),
                    success(staging.repository.installOrUpdate(ByteArrayInputStream(corruptCandidateArchive()), sourceRecord(CORRUPT_REPOSITORY, "301", "401"))),
                )
            }

            val corruptRevision = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(CORRUPT_REPOSITORY).active
            val corruptContent = File(File(root, "content/sha256"), corruptRevision.digest.value)
            assertTrue("the exact committed corrupt-candidate archive must exist before corruption", corruptContent.isFile)
            assertTrue("the committed immutable archive must be removable to simulate post-commit corruption", corruptContent.delete())

            ProductionFixture(root).useSuspending { restarted ->
                val validDefinition = installedDefinition("installed-package-valid-sibling", INSTALLED_ID)
                val corruptDefinition = installedDefinition("installed-package-corrupt-sibling", CORRUPT_ID)
                val builtInDefinition = debugDefinition()
                restarted.catalogue(validDefinition, corruptDefinition, builtInDefinition)

                assertEquals(Unit, success(restarted.repository.loadAndPublish()))

                assertTrue("valid installed sibling must remain resolvable", restarted.providers.resolve(INSTALLED_ID) is ChannelProviderResolution.Available)
                val corruptResolution = restarted.providers.resolve(CORRUPT_ID) as? ChannelProviderResolution.Unavailable
                    ?: throw AssertionError("corrupt installed package must project typed provider unavailability")
                assertEquals(ChannelProviderError.PackageUnavailableCategory.INTEGRITY, corruptResolution.error.category)
                assertEquals(ChannelProviderError.PackageUnavailableDetail.CORRUPTED_ARCHIVE, corruptResolution.error.detail)
                assertTrue("built-in provider must remain resolvable beside corrupt installed content", restarted.providers.resolve(BuiltInChannelImplementationIds.DEBUG) is ChannelProviderResolution.Available)
                assertEquals(setOf(CORRUPT_ID), restarted.publishedFailures.get().keys)
                assertEquals(PackageFailure.IntegrityDetail.CORRUPTED_ARCHIVE, (restarted.publishedFailures.get().getValue(CORRUPT_ID) as PackageFailure.Integrity).detail)
                assertTrue("only the valid sibling may allocate a Lua JNI state", restarted.bridge.createdStateIds.size == 1)
                assertTrue("the corrupt definition must project unavailable without a Lua state", restarted.runtimeRegistry.preparation(corruptDefinition.id) is ChannelPreparationAvailability.Unavailable)
                assertTrue("the corrupt definition must reject input", restarted.runtimeRegistry.prepareInput(corruptDefinition.id) is ChannelInputAcceptance.Unavailable)
                val validState = restarted.bridge.createdStateIds.single()
                assertTrue("the valid installed sibling must reach readiness", awaitLuaInputAcceptance(restarted.runtimeRegistry, validDefinition.id, restarted.bridge, validState, awaitTimerResume = true))
                assertTrue("the built-in debug runtime must remain operational", acceptInput(restarted.runtimeRegistry, builtInDefinition.id))
            }
        }
    }

    private suspend fun awaitLuaInputAcceptance(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        bridge: TracingNativeBridge,
        stateId: Long,
        awaitTimerResume: Boolean,
        stage: String = definitionId,
    ): Boolean {
        val readinessBaseline = bridge.readinessCallbackCount(stateId)
        if (awaitTimerResume && !bridge.awaitSuccessfulTimerResume(stateId)) {
            throw readinessFailure(registry, definitionId, stage, "the v1 timer resume for state $stateId")
        }
        registry.refreshReadiness()
        if (!bridge.awaitReadinessCallbackAfter(stateId, readinessBaseline)) {
            throw readinessFailure(registry, definitionId, stage, "a handle_readiness callback for state $stateId")
        }
        return acceptInput(registry, definitionId, stage)
    }

    private suspend fun acceptInput(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        stage: String = definitionId,
    ): Boolean = when (val acceptance = registry.prepareInput(definitionId)) {
        is ChannelInputAcceptance.Accepted -> {
            acceptance.target.onInputCancelled("instrumentation readiness probe complete")
            (acceptance.target as? dev.nilp0inter.subspace.service.CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
            true
        }
        else -> throw readinessFailure(registry, definitionId, stage, "input acceptance; lastAcceptance=$acceptance")
    }

    private fun readinessFailure(
        registry: ChannelRuntimeRegistry,
        definitionId: String,
        stage: String,
        waitingFor: String,
    ): AssertionError = AssertionError(
        "input acceptance failed at lifecycle stage '$stage' while waiting for $waitingFor; " +
            "preparation=${registry.preparation(definitionId)}; snapshot=${registry.getRuntimeSnapshot(definitionId)}; " +
            "generation=${registry.capabilityScopeIdentity(definitionId)}",
    )

    /** Returns true only if the successor becomes unavailable after the retired v1 timer deadline. */
    private suspend fun inputRefusedAfterTimerWindow(registry: ChannelRuntimeRegistry, definitionId: String): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + STALE_TIMER_WINDOW_MILLIS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            registry.refreshReadiness()
            when (val acceptance = registry.prepareInput(definitionId)) {
                is ChannelInputAcceptance.Accepted -> {
                    acceptance.target.onInputCancelled("instrumentation stale-timer probe complete")
                    (acceptance.target as? dev.nilp0inter.subspace.service.CommittedTargetLeaseOwner)?.releaseCommittedTargetLease()
                }
                else -> return true
            }
            delay(POLL_MILLIS)
        }
        return false
    }

    private fun installedDefinition(id: String, implementationId: ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = id,
        name = "Installed package $id",
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(JSONObject()),
    )

    private fun debugDefinition(): ChannelDefinition = ChannelDefinition(
        id = "installed-package-debug-built-in",
        name = "Installed package debug built-in",
        implementationId = BuiltInChannelImplementationIds.DEBUG,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = DebugProviderConfigurationCodec.encode(DebugProviderConfiguration(DebugMode.ECHO)),
    )

    private fun sourceRecord(
        repository: GitHubRepositoryIdentity,
        releaseId: String,
        assetId: String,
    ): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repository,
        coordinates = GitHubRepositoryCoordinates("fixture-owner", "fixture-repository"),
        release = GitHubReleaseIdentity(releaseId, "fixture-release", false),
        asset = GitHubAssetIdentity(assetId, "fixture-package.zip"),
        ownerId = "9000001",
    )

    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("expected package outcome success; category=${outcome.error.category}")
    }

    private suspend fun withPrivateStore(block: suspend (File) -> Unit) {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "installed-lua-instrumentation-${UUID.randomUUID()}",
        )
        check(root.mkdirs()) { "could not create private instrumentation store" }
        try {
            block(root)
        } finally {
            check(root.deleteRecursively() || !root.exists()) { "could not remove private instrumentation store" }
        }
    }

    private class ProductionFixture(root: File) {
        private val catalogue = AtomicReference(ChannelCatalogueSnapshot(emptyList(), ""))
        val bridge = TracingNativeBridge()
        val providers = ChannelImplementationProviderRegistry().also { registry ->
            assertEquals(ChannelProviderRegistrationResult.Registered, registry.register(DebugBuiltInProvider()))
        }
        private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val boundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.create(workerCount = 1, queueCapacity = 16, threadNamePrefix = "installed-lua-device"),
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = WAIT_MILLIS,
                inputReleasedTimeoutMillis = WAIT_MILLIS,
                closeTimeoutMillis = WAIT_MILLIS,
            ),
        )
        val runtimeRegistry = ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = NoCapabilities,
            invocationBoundary = boundary,
            runtimeScope = runtimeScope,
            closeScope = runtimeScope,
            shutdownAwaitMillis = WAIT_MILLIS,
        )
        val publishedFailures = AtomicReference<Map<ChannelImplementationId, PackageFailure>>(emptyMap())
        val repository = InstalledPackageRepository(
            store = InstalledPackageStore(root),
            bridge = bridge,
            publisher = { materialized ->
                publishedFailures.set(materialized.failures)
                val unavailable = materialized.failures.mapValues { (id, failure) -> failure.toPackageUnavailable(id) }
                when (providers.publishInstalledProviders(materialized.bindings, unavailable)) {
                    is InstalledProvidersPublicationResult.Success -> {
                        runtimeRegistry.reconcile(catalogue.get())
                        PackageOutcome.Success(Unit)
                    }
                    is InstalledProvidersPublicationResult.Rejected -> PackageOutcome.Failure(
                        PackageFailure.Loading(PackageFailure.LoadingDetail.PUBLICATION_REJECTED),
                    )
                }
            },
            dispatcher = Dispatchers.IO,
        )

        fun catalogue(vararg definitions: ChannelDefinition) {
            catalogue.set(ChannelCatalogueSnapshot(definitions.toList(), definitions.firstOrNull()?.id.orEmpty()))
        }

        suspend fun close() {
            repository.closeAndAwait(WAIT_MILLIS)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, runtimeRegistry.shutdownAndAwait())
            boundary.close()
            runtimeScope.cancel()
        }

        suspend fun useSuspending(block: suspend (ProductionFixture) -> Unit) {
            try {
                block(this)
            } finally {
                close()
            }
        }
    }

    /** Records only opaque JNI state lifecycle ordering while delegating every operation to production JNI. */
    private class TracingNativeBridge(private val delegate: LuaKernelBridge = LuaNativeKernelBridge()) : LuaKernelBridge {
        private sealed interface Event {
            val stateId: Long
            data class Created(override val stateId: Long) : Event
            data class Startup(override val stateId: Long) : Event
            data class Resume(override val stateId: Long) : Event
            data class Readiness(override val stateId: Long) : Event
            data class Closed(override val stateId: Long) : Event
        }

        private val events = CopyOnWriteArrayList<Event>()
        val createdStateIds: List<Long>
            get() = events.filterIsInstance<Event.Created>().map(Event.Created::stateId)
        val closedStateIds: List<Long>
            get() = events.filterIsInstance<Event.Closed>().map(Event.Closed::stateId)

        fun closedBeforeStartup(predecessor: Long, successor: Long): Boolean {
            val closed = events.indexOfFirst { it is Event.Closed && it.stateId == predecessor }
            val started = events.indexOfFirst { it is Event.Startup && it.stateId == successor }
            return closed >= 0 && started >= 0 && closed < started
        }

        fun readinessCallbackCount(stateId: Long): Int = events.count { it is Event.Readiness && it.stateId == stateId }

        suspend fun awaitSuccessfulTimerResume(stateId: Long): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
            while (events.none { it is Event.Resume && it.stateId == stateId }) delay(POLL_MILLIS)
            true
        } == true

        suspend fun awaitReadinessCallbackAfter(stateId: Long, baseline: Int): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
            while (readinessCallbackCount(stateId) <= baseline) delay(POLL_MILLIS)
            true
        } == true

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = delegate.create(config).also { outcome ->
            (outcome as? LuaKernelOutcome.Created)?.let { events += Event.Created(it.stateId) }
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome =
            delegate.load(handle, source, entrypoint)

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = delegate.start(handle)

        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome =
            delegate.resume(operation, success, value, spawnAdmission).also {
                if (success) events += Event.Resume(operation.stateHandle.stateId.value)
            }

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = delegate.cancel(operation)

        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = delegate.interrupt(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = delegate.snapshot(handle)

        override fun close(handle: LuaStateHandle): LuaKernelOutcome = delegate.close(handle).also {
            events += Event.Closed(handle.stateId.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            delegate.loadProgramImage(handle, entryPoint, sourceMap)

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.invokeStartupCallback(handle, callbackHandle, spawnAdmission).also {
            events += Event.Startup(handle.stateId.value)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.invokeCallback(handle, callbackHandle, arguments, spawnAdmission).also { outcome ->
            if (callbackHandle.name == "handle_readiness" && outcome is LuaKernelOutcome.Completed && outcome.stateId == handle.stateId.value) {
                events += Event.Readiness(outcome.stateId)
            }
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = delegate.startCoroutine(handle, coroutineId, spawnAdmission)
    }

    private object NoCapabilities : ChannelCapabilityHost {
        override suspend fun availability(identity: CapabilityScopeIdentity, key: CapabilityKey<*>): CapabilityAvailability =
            CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private fun v1Archive(): ByteArray = V1_ARCHIVE.copyOf()
    private fun v2Archive(): ByteArray = V2_ARCHIVE.copyOf()
    private fun corruptCandidateArchive(): ByteArray = CORRUPT_CANDIDATE_ARCHIVE.copyOf()

    private fun digestOf(bytes: ByteArray): String = buildString(64) {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

    private companion object {
        val SOURCE_REPOSITORY = GitHubRepositoryIdentity("9001")
        val CORRUPT_REPOSITORY = GitHubRepositoryIdentity("9002")
        val INSTALLED_ID: ChannelImplementationId = InstalledProviderId.derive(SOURCE_REPOSITORY)
        val CORRUPT_ID: ChannelImplementationId = InstalledProviderId.derive(CORRUPT_REPOSITORY)
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val STALE_TIMER_WINDOW_MILLIS = 650L
        const val UNIX_HOST_OS = 3
        const val LOCAL_FILE_HEADER = 0x04034b50L
        const val CENTRAL_DIRECTORY_HEADER = 0x02014b50L
        const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
        const val REGULAR_FILE_0644 = 0b1000000110100100
        const val DIRECTORY_0755 = 0b0100000111101101
        const val HEX_DIGITS = "0123456789abcdef"

        private val V1_ARCHIVE = packageArchive(
            repositoryId = SOURCE_REPOSITORY.value,
            version = "1.0.0",
            source = """
                local runtime = require("subspace.runtime")
                local timer_completed = false
                return {
                  startup = function()
                    if volatile_boot_marker ~= nil then return { error = { code = "E_RETAINED_VOLATILE_STATE" } } end
                    volatile_boot_marker = "v1"
                    local admitted = runtime.spawn(function()
                      local completed = runtime.sleep(0.05)
                      timer_completed = completed == true
                    end)
                    if admitted ~= true then return { error = { code = "E_TIMER" } } end
                  end,
                  handle_readiness = function() return { ready = timer_completed } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private val V2_ARCHIVE = packageArchive(
            repositoryId = SOURCE_REPOSITORY.value,
            version = "2.0.0",
            source = """
                return {
                  startup = function() end,
                  handle_readiness = function() return { ready = volatile_boot_count == nil } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private val CORRUPT_CANDIDATE_ARCHIVE = packageArchive(
            repositoryId = CORRUPT_REPOSITORY.value,
            version = "1.0.0",
            source = """
                return {
                  startup = function() end,
                  handle_readiness = function() return { ready = true } end,
                  handle_input = function() return { ok = true } end,
                }
            """.trimIndent(),
        )

        private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
        private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)

        /** Creates byte-identical stored ZIPs with Unix regular-file/directory central metadata. */
        private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
            val output = ByteArrayOutputStream()
            val centralEntries = ArrayList<CentralFixtureEntry>(entries.size)
            entries.forEach { entry ->
                val name = entry.name.toByteArray(UTF_8)
                val crc = CRC32().apply { update(entry.bytes) }.value
                val offset = output.size().toLong()
                output.u32(LOCAL_FILE_HEADER)
                output.u16(20)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u32(crc)
                output.u32(entry.bytes.size.toLong())
                output.u32(entry.bytes.size.toLong())
                output.u16(name.size)
                output.u16(0)
                output.write(name)
                output.write(entry.bytes)
                centralEntries += CentralFixtureEntry(entry, name, crc, offset)
            }
            val centralOffset = output.size().toLong()
            centralEntries.forEach { central ->
                output.u32(CENTRAL_DIRECTORY_HEADER)
                output.u16((UNIX_HOST_OS shl 8) or 20)
                output.u16(20)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u32(central.crc)
                output.u32(central.entry.bytes.size.toLong())
                output.u32(central.entry.bytes.size.toLong())
                output.u16(central.name.size)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u16(0)
                output.u32(central.entry.unixMode.toLong() shl 16)
                output.u32(central.localOffset)
                output.write(central.name)
            }
            val centralSize = output.size().toLong() - centralOffset
            output.u32(END_OF_CENTRAL_DIRECTORY)
            output.u16(0)
            output.u16(0)
            output.u16(entries.size)
            output.u16(entries.size)
            output.u32(centralSize)
            output.u32(centralOffset)
            output.u16(0)
            return output.toByteArray()
        }

        private fun ByteArrayOutputStream.u16(value: Int) {
            write(value and 0xff)
            write((value ushr 8) and 0xff)
        }

        private fun ByteArrayOutputStream.u32(value: Long) {
            write((value and 0xff).toInt())
            write(((value ushr 8) and 0xff).toInt())
            write(((value ushr 16) and 0xff).toInt())
            write(((value ushr 24) and 0xff).toInt())
        }
        private fun packageArchive(repositoryId: String, version: String, source: String): ByteArray {
            val manifest = """{"manifestVersion":1,"repositoryId":"$repositoryId","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Device package","summary":"Immutable device fixture"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"}}"""
            return strictUnixStoredZip(
                listOf(
                    ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), REGULAR_FILE_0644),
                    ZipFixtureEntry("lua/", ByteArray(0), DIRECTORY_0755),
                    ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), REGULAR_FILE_0644),
                ),
            )
        }
    }
}
