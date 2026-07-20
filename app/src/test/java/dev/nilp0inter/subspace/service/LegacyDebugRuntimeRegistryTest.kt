package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.channel.JournalBuiltInProvider
import dev.nilp0inter.subspace.channel.KeyboardBuiltInProvider
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.lua.LuaNativeKernel
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderResolution
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime registry behaviour tests for the post-removal state:
 * - A persisted active builtin:debug definition cannot prepare/execute PTT through
 *   the ordinary ChannelRuntimeRegistry path (not merely resolver Missing).
 * - An unrelated available definition remains operable alongside it.
 * - Ordinary built-in startup registry has exact IDs Journal/Keyboard/OpenAI
 *   and zero Lua state when no packages are installed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyDebugRuntimeRegistryTest {

    @Test
    fun `builtin debug definition projects unavailable through runtime registry and cannot prepare input`() = runTest {
        val providerRegistry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val journalDef = ChannelDefinition(
            id = "captains-log",
            name = "Journal",
            implementationId = BuiltInChannelImplementationIds.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse(
                """{"baseDirectory":"/records","saveVoice":true,"saveText":true}""",
            ).getOrThrow(),
        )
        val debugDef = ChannelDefinition(
            id = "debug-channel",
            name = "Debug Channel",
            implementationId = ChannelImplementationId("builtin:debug"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("""{"mode":"ECHO"}""").getOrThrow(),
        )

        val registry = runtimeRegistry(providerRegistry, this)
        registry.reconcile(
            ChannelCatalogueSnapshot(
                definitions = listOf(journalDef, debugDef),
                activeChannelId = "debug-channel",
            ),
        )
        runCurrent()

        // The debug definition must project Unavailable through the runtime registry
        val debugSnapshot = registry.getRuntimeSnapshot("debug-channel")
        assertTrue("Debug snapshot must exist", debugSnapshot != null)
        val debugPreparation = debugSnapshot!!.preparation
        assertTrue("Debug preparation must be Unavailable, got $debugPreparation",
            debugPreparation is ChannelPreparationAvailability.Unavailable)
        val reason = (debugPreparation as ChannelPreparationAvailability.Unavailable).reason
        assertTrue("Debug unavailability reason must be Provider/Missing, got $reason",
            reason is ChannelPreparationReason.Provider)

        // prepareInput on the debug channel must return Unavailable
        val acceptance = registry.prepareInput("debug-channel")
        assertTrue("prepareInput for builtin:debug must return Unavailable, got $acceptance",
            acceptance is ChannelInputAcceptance.Unavailable)

        registry.shutdownAndAwait()
    }

    @Test
    fun `available journal definition remains operable alongside unavailable debug definition`() = runTest {
        val providerRegistry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val journalDef = ChannelDefinition(
            id = "captains-log",
            name = "Journal",
            implementationId = BuiltInChannelImplementationIds.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse(
                """{"baseDirectory":"/records","saveVoice":true,"saveText":true}""",
            ).getOrThrow(),
        )
        val debugDef = ChannelDefinition(
            id = "debug-channel",
            name = "Debug Channel",
            implementationId = ChannelImplementationId("builtin:debug"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("""{"mode":"STT"}""").getOrThrow(),
        )

        val registry = runtimeRegistry(providerRegistry, this)
        registry.reconcile(
            ChannelCatalogueSnapshot(
                definitions = listOf(journalDef, debugDef),
                activeChannelId = "captains-log",
            ),
        )
        runCurrent()

        // Journal must be Available (operable)
        val journalSnapshot = registry.getRuntimeSnapshot("captains-log")
        assertTrue("Journal snapshot must exist", journalSnapshot != null)
        assertEquals(
            "Journal preparation must be Available",
            ChannelPreparationAvailability.Available,
            journalSnapshot!!.preparation,
        )

        // Debug must be Unavailable
        val debugSnapshot = registry.getRuntimeSnapshot("debug-channel")
        assertTrue("Debug snapshot must exist", debugSnapshot != null)
        assertTrue(
            "Debug preparation must be Unavailable",
            debugSnapshot!!.preparation is ChannelPreparationAvailability.Unavailable,
        )

        // Journal prepareInput must not be blocked by the unavailable debug definition
        val journalAcceptance = registry.prepareInput("captains-log")
        assertTrue(
            "Journal prepareInput must not be Unavailable due to debug sibling, got $journalAcceptance",
            journalAcceptance !is ChannelInputAcceptance.Unavailable,
        )

        registry.shutdownAndAwait()
    }

    @Test
    fun `builtin debug active definition dispatchSos returns unavailable`() = runTest {
        val providerRegistry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val journalDef = ChannelDefinition(
            id = "captains-log",
            name = "Journal",
            implementationId = BuiltInChannelImplementationIds.JOURNAL,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse(
                """{"baseDirectory":"/records","saveVoice":true,"saveText":true}""",
            ).getOrThrow(),
        )
        val debugDef = ChannelDefinition(
            id = "debug-channel",
            name = "Debug Channel",
            implementationId = ChannelImplementationId("builtin:debug"),
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.parse("""{"mode":"TTS"}""").getOrThrow(),
        )

        val registry = runtimeRegistry(providerRegistry, this)
        registry.reconcile(
            ChannelCatalogueSnapshot(
                definitions = listOf(journalDef, debugDef),
                activeChannelId = "debug-channel",
            ),
        )
        runCurrent()

        val sosResult = registry.dispatchSos("debug-channel")
        assertTrue(
            "dispatchSos for builtin:debug must return Unavailable, got $sosResult",
            sosResult is ChannelPreparationAvailability.Unavailable,
        )

        registry.shutdownAndAwait()
    }

    // ──────────────────────────────────────────────────────────────
    //  Ordinary built-in startup registry: exact IDs and zero Lua state
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `ordinary built-in startup registry has exact IDs journal keyboard and openai-agent`() {
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val ids = registry.descriptors().map { it.implementationId.value }
        assertEquals(
            listOf("builtin:journal", "builtin:keyboard"),
            ids,
        )
        // builtin:debug must not be present
        assertFalse("builtin:debug must not appear in the startup registry",
            ids.contains("builtin:debug"))
    }

    @Test
    fun `ordinary built-in startup registry resolves builtin debug as missing`() {
        val registry = ChannelImplementationProviderRegistry().apply {
            register(JournalBuiltInProvider())
            register(KeyboardBuiltInProvider())
        }

        val resolution = registry.resolve(ChannelImplementationId("builtin:debug"))
        assertTrue(
            "builtin:debug must resolve as Missing, got $resolution",
            resolution is ChannelProviderResolution.Missing,
        )
    }

    @Test
    fun `ordinary built-in startup registry does not create Lua state when no packages installed`() = runTest {
        LuaNativeKernel.resetForTest()
        ActorRuntimeFactory.resetForTest()
        try {
            val providerRegistry = ChannelImplementationProviderRegistry().apply {
                register(JournalBuiltInProvider())
                register(KeyboardBuiltInProvider())
            }

            val journalDef = ChannelDefinition(
                id = "captains-log",
                name = "Journal",
                implementationId = BuiltInChannelImplementationIds.JOURNAL,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse(
                    """{"baseDirectory":"/records","saveVoice":true,"saveText":true}""",
                ).getOrThrow(),
            )

            val registry = runtimeRegistry(providerRegistry, this)
            registry.reconcile(
                ChannelCatalogueSnapshot(listOf(journalDef), journalDef.id),
            )
            runCurrent()

            assertFalse("Lua native kernel must not be loaded", LuaNativeKernel.isLoadAttempted)
            assertFalse("Actor runtime must not be created", ActorRuntimeFactory.isCreateAttempted)

            registry.shutdownAndAwait()
        } finally {
            ActorRuntimeFactory.resetForTest()
            LuaNativeKernel.resetForTest()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun runtimeRegistry(
        providers: ChannelImplementationProviderRegistry,
        scope: kotlinx.coroutines.test.TestScope,
    ): ChannelRuntimeRegistry {
        val worker = RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(scope.testScheduler))
        val invocationBoundary = RuntimeInvocationBoundary(
            worker,
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = 1_000,
                inputReleasedTimeoutMillis = 1_000,
                closeTimeoutMillis = 1_000,
            ),
        )
        return ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = AvailableCapabilities,
            invocationBoundary = invocationBoundary,
            runtimeScope = scope.backgroundScope,
            closeScope = scope.backgroundScope,
            shutdownAwaitMillis = 1_000,
        )
    }

    /** Capability host that reports all capabilities as Available for availability checks. */
    private object AvailableCapabilities : ChannelCapabilityHost {
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