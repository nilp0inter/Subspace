package dev.nilp0inter.subspace.dependency

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
import dev.nilp0inter.subspace.lua.LuaPackageMaterializer
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.InstalledProvidersPublicationResult
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistry
import dev.nilp0inter.subspace.service.ChannelRuntimeRegistryShutdownResult
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import dev.nilp0inter.subspace.service.CompositionProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM smoke coverage for installed Lua packages. The ZIP writer emits the Unix-mode
 * central directory and byte-identical local headers required by the production raw ZIP parser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledLuaPackagesSmokeTest {
    @Test
    fun `9_1 static validation builds immutable program image without entering Lua`() = runTest {
        withTemporaryDirectory { root ->
        val archive = packageArchive(version = "1.0.0", sourceMarker = "first")
        val staged = File(root, "candidate.zip")
        val source = sourceRecord()

        val revision = success(
            PackageValidator.validatePackage(
                ByteArrayInputStream(archive),
                source,
                staged,
            ),
        )
        val bridge = RecordingLuaKernelBridge()
        val binding = LuaPackageMaterializer.materialize(revision, bridge)

        assertEquals("plugin", revision.programImage.entryPoint)
        assertEquals("-- first\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }", revision.programImage.sourceMap["plugin"])
        assertEquals("github-repository:123", binding.provider.descriptor.implementationId.value)
        assertEquals(revision.fingerprint, binding.provider.fingerprint)
        assertEquals(0, bridge.calls)
    }
    }

    @Test
    fun `9_2 first install commits exact content publishes one snapshot and leaves empty runtime catalogue dormant`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingLuaKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val runtimeRegistry = runtimeRegistry(providers)
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(emptyList(), ""))
            val repository = repository(root, bridge, providers)
            val archive = packageArchive(version = "1.0.0", sourceMarker = "first")
            val source = sourceRecord()

            val installed = success(repository.installOrUpdate(ByteArrayInputStream(archive), source))
            val implementationId = InstalledProviderId.derive(source.repositoryId)
            val index = success(InstalledPackageStore(root).loadIndex()).index
            val record = requireNotNull(index.providers[source.repositoryId])

            assertEquals(MutationResult.Installed(implementationId), installed)
            assertEquals(1L, providers.snapshotRevision)
            assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Available)
            assertTrue(File(root, "content/sha256/${record.active.digest.value}").readBytes().contentEquals(archive))
            assertEquals(source, record.active.sourceRecord)
            assertEquals("1.0.0", record.active.manifest.packageVersion)
            assertTrue(runtimeRegistry.runtimeSnapshots.value.entries.isEmpty())
            assertEquals(0, bridge.calls)
        }
    }

    @Test
    fun `9_3 installed definition resolves through registries reaches readiness and runs bounded callback`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingLuaKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val repository = repository(root, bridge, providers)
            val implementationId = InstalledProviderId.derive(sourceRecord().repositoryId)
            success(repository.installOrUpdate(ByteArrayInputStream(packageArchive("1.0.0", "first")), sourceRecord()))

            val runtimeRegistry = runtimeRegistry(providers)
            val definition = definition(implementationId)
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()

            assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Available)
            assertEquals(ChannelPreparationAvailability.Available, runtimeRegistry.getRuntimeSnapshot(definition.id)?.preparation)
            assertEquals(1, bridge.createdStateIds.size)
            assertTrue(bridge.callbackNames.contains("startup"))
            assertTrue(bridge.callbackNames.contains("handle_readiness"))

            val readinessBeforeRefresh = bridge.callbackNames.count { it == "handle_readiness" }
            runtimeRegistry.refreshReadiness()
            runCurrent()
            assertEquals(readinessBeforeRefresh + 1, bridge.callbackNames.count { it == "handle_readiness" })

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            assertEquals(bridge.createdStateIds.toSet(), bridge.closedStateIds.toSet())
        }
    }

    @Test
    fun `9_4 update rollback removal reinstall and restart replace fingerprints generations availability and volatile state`() = runTest {
        withTemporaryDirectory { root ->
            val bridge = RecordingLuaKernelBridge()
            val providers = ChannelImplementationProviderRegistry()
            val repository = repository(root, bridge, providers)
            val source = sourceRecord()
            val implementationId = InstalledProviderId.derive(source.repositoryId)
            val v1 = packageArchive("1.0.0", "first")
            val v2 = packageArchive("2.0.0", "second")
            val runtimeRegistry = runtimeRegistry(providers)
            val definition = definition(implementationId)

            success(repository.installOrUpdate(ByteArrayInputStream(v1), source))
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()
            val generationOne = requireNotNull(runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
            val firstState = bridge.createdStateIds.single()
            val firstFingerprint = availableFingerprint(providers, implementationId)

            val updated = success(repository.installOrUpdate(ByteArrayInputStream(v2), source))
            val afterUpdate = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(source.repositoryId)
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()
            val generationTwo = requireNotNull(runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
            val secondState = bridge.createdStateIds.last()
            val secondFingerprint = availableFingerprint(providers, implementationId)

            assertEquals(MutationResult.Updated(implementationId), updated)
            assertEquals(2, bridge.createdStateIds.size)
            assertNotEquals(firstFingerprint, secondFingerprint)
            assertNotEquals(generationOne, generationTwo)
            assertTrue(bridge.closedStateIds.contains(firstState))
            assertEquals(secondFingerprint.value, afterUpdate.active.digest.value)
            assertEquals(firstFingerprint.value, requireNotNull(afterUpdate.rollback).digest.value)

            val rolledBack = success(repository.rollback(source.repositoryId))
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()
            val generationThree = requireNotNull(runtimeRegistry.capabilityScopeIdentity(definition.id)).runtimeGeneration
            val thirdState = bridge.createdStateIds.last()
            val afterRollback = success(InstalledPackageStore(root).loadIndex()).index.providers.getValue(source.repositoryId)

            assertEquals(MutationResult.RolledBack(implementationId), rolledBack)
            assertEquals(firstFingerprint, availableFingerprint(providers, implementationId))
            assertNotEquals(generationTwo, generationThree)
            assertTrue(bridge.closedStateIds.contains(secondState))
            assertEquals(firstFingerprint.value, afterRollback.active.digest.value)
            assertEquals(secondFingerprint.value, afterRollback.rollback?.digest?.value)

            val removed = success(repository.remove(source.repositoryId))
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()

            assertEquals(MutationResult.Removed(implementationId), removed)
            assertTrue(providers.resolve(implementationId) is ChannelProviderResolution.Missing)
            assertTrue(bridge.closedStateIds.contains(thirdState))
            assertEquals(0, success(InstalledPackageStore(root).loadIndex()).index.providers.size)

            val reinstalled = success(repository.installOrUpdate(ByteArrayInputStream(v1), source))
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()
            assertEquals(MutationResult.Installed(implementationId), reinstalled)
            assertEquals(firstFingerprint, availableFingerprint(providers, implementationId))
            assertEquals(4, bridge.createdStateIds.size)

            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            assertEquals(bridge.createdStateIds.toSet(), bridge.closedStateIds.toSet())

            val restartedBridge = RecordingLuaKernelBridge()
            val restartedProviders = ChannelImplementationProviderRegistry()
            val restartedRepository = repository(root, restartedBridge, restartedProviders)
            assertEquals(Unit, success(restartedRepository.loadAndPublish()))
            assertEquals(firstFingerprint, availableFingerprint(restartedProviders, implementationId))

            val restartedRuntimeRegistry = runtimeRegistry(restartedProviders)
            restartedRuntimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()
            assertEquals(ChannelPreparationAvailability.Available, restartedRuntimeRegistry.getRuntimeSnapshot(definition.id)?.preparation)
            assertEquals(1, restartedBridge.createdStateIds.size)
            assertFalse(restartedBridge.closedStateIds.contains(restartedBridge.createdStateIds.single()))
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(restartedRuntimeRegistry))
            assertEquals(restartedBridge.createdStateIds.toSet(), restartedBridge.closedStateIds.toSet())
        }
    }

    @Test
    fun `9_5 absent and corrupt installed stores do not prevent registered built in provider construction or closure`() = runTest {
        withTemporaryDirectory { root ->
            val provider = CompositionProvider(BuiltInChannelImplementationIds.JOURNAL)
            val providers = ChannelImplementationProviderRegistry()
            assertEquals(ChannelProviderRegistrationResult.Registered, providers.register(provider))

            val emptyRepository = repository(root, RecordingLuaKernelBridge(), providers)
            assertEquals(Unit, success(emptyRepository.loadAndPublish()))
            assertTrue(providers.resolve(BuiltInChannelImplementationIds.JOURNAL) is ChannelProviderResolution.Available)

            val corruptRoot = File(root, "corrupt").apply { mkdirs() }
            File(corruptRoot, "index.json").writeText("not an index")
            File(corruptRoot, "index.backup.json").writeText("also not an index")
            val corruptRepository = repository(corruptRoot, RecordingLuaKernelBridge(), providers)
            val corruptLoad = corruptRepository.loadAndPublish()
            assertTrue(corruptLoad is PackageOutcome.Failure)
            assertTrue(providers.resolve(BuiltInChannelImplementationIds.JOURNAL) is ChannelProviderResolution.Available)

            val runtimeRegistry = runtimeRegistry(providers)
            val definition = ChannelDefinition(
                id = "built-in-survival",
                name = "Built-in survival",
                implementationId = BuiltInChannelImplementationIds.JOURNAL,
                enabled = true,
                configSchemaVersion = 2,
                configPayload = OpaqueJsonObject.parse("{\"stage\":2,\"profile\":\"smoke\"}").getOrThrow(),
            )
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(definition), definition.id))
            runCurrent()

            assertEquals(ChannelPreparationAvailability.Available, runtimeRegistry.getRuntimeSnapshot(definition.id)?.preparation)
            assertEquals(1, provider.runtimes.size)
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
            assertEquals(1, provider.runtimes.single().closeCount)
        }
    }

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

    private fun definition(implementationId: ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = "installed-channel",
        name = "Installed channel",
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
    )

    private fun sourceRecord(): PackageSourceRecord = PackageSourceRecord(
        repositoryId = GitHubRepositoryIdentity("123"),
        coordinates = GitHubRepositoryCoordinates("smoke-owner", "smoke-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "smoke-package.zip"),
    )

    private fun availableFingerprint(
        providers: ChannelImplementationProviderRegistry,
        implementationId: ChannelImplementationId,
    ) = (providers.resolve(implementationId) as? ChannelProviderResolution.Available)
        ?.provider
        ?.fingerprint
        ?: throw AssertionError("Expected an available installed provider for $implementationId")


    private fun <T> success(outcome: PackageOutcome<T>): T = when (outcome) {
        is PackageOutcome.Success -> outcome.value
        is PackageOutcome.Failure -> throw AssertionError("Expected package operation success, got ${outcome.error}")
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("installed-lua-smoke-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun packageArchive(version: String, sourceMarker: String): ByteArray {
        val source = "-- $sourceMarker\nreturn { startup = function() end, handle_readiness = function() return { ready = true } end }"
        val manifest = """{"manifestVersion":1,"repositoryId":"123","packageVersion":"$version","entryModule":"plugin","presentation":{"label":"Smoke package","summary":"Installed Lua smoke package"},"runtime":{"luaVersion":"$LUA_VERSION","apiVersion":"$API_VERSION"}}"""
        return strictUnixStoredZip(
            listOf(
                ZipFixtureEntry("manifest.json", manifest.toByteArray(UTF_8), 0b1000000110100100),
                ZipFixtureEntry("lua/", ByteArray(0), 0b0100000111101101),
                ZipFixtureEntry("lua/plugin.lua", source.toByteArray(UTF_8), 0b1000000110100100),
            ),
        )
    }

    private fun strictUnixStoredZip(entries: List<ZipFixtureEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val centralEntries = ArrayList<CentralFixtureEntry>(entries.size)
        entries.forEach { entry ->
            val name = entry.name.toByteArray(UTF_8)
            val crc = CRC32().apply { update(entry.bytes) }.value
            val offset = output.size().toLong()
            output.u32(0x04034b50)
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
            output.u32(0x02014b50)
            output.u16((3 shl 8) or 20)
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
        output.u32(0x06054b50)
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

    private data class ZipFixtureEntry(val name: String, val bytes: ByteArray, val unixMode: Int)
    private data class CentralFixtureEntry(val entry: ZipFixtureEntry, val name: ByteArray, val crc: Long, val localOffset: Long)

    private object AvailableButUnimplementedCapabilities : ChannelCapabilityHost {
        override suspend fun availability(identity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity, key: CapabilityKey<*>): CapabilityAvailability =
            CapabilityAvailability.Available

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

    private class RecordingLuaKernelBridge : LuaKernelBridge {
        private val states = mutableMapOf<Long, Boolean>()
        private var nextStateId = 1L

        var calls = 0
            private set
        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()
        val callbackNames = mutableListOf<String>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            calls += 1
            val id = nextStateId++
            states[id] = false
            createdStateIds += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "smoke")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(operation.stateHandle)
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
            "smoke",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            calls += 1
            if (states[handle.stateId.value] != true) {
                states[handle.stateId.value] = true
                closedStateIds += handle.stateId.value
            }
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome {
            calls += 1
            return completed(handle, "[\"startup\",\"handle_readiness\"]")
        }

        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome {
            calls += 1
            callbackNames += callbackHandle.name
            return completed(handle)
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            calls += 1
            callbackNames += callbackHandle.name
            return completed(handle, if (callbackHandle.name == "handle_readiness") "{\"ready\":true}" else null)
        }

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
            topology = "smoke",
            spawnedCoroutines = null,
        )
    }
}
