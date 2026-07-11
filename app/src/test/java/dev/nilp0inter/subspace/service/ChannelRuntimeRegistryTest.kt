package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import dev.nilp0inter.subspace.model.DebugConfig
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.JournalConfig
import dev.nilp0inter.subspace.model.KeyboardConfig
import dev.nilp0inter.subspace.model.TestFourthConfig
import io.sleepwalker.core.keymap.HostProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRuntimeRegistryTest {

    @Test
    fun registryReconciliationCreatesPreservesAndRetiresRuntimes() {
        val fJournal = FakeRuntimeFactory()
        val fDebug = FakeRuntimeFactory()
        val fKeyboard = FakeRuntimeFactory()
        val fFourth = FakeRuntimeFactory() // 2.8: test-only fourth runtime kind
        
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal,
            ChannelKind.DEBUG to fDebug,
            ChannelKind.KEYBOARD to fKeyboard
        ))

        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val d2 = ChannelDefinition("c2", "D1", ChannelKind.DEBUG, true, 1, DebugConfig(DebugMode.ECHO))
        
        // Reconcile initial
        val s1 = ChannelCatalogueSnapshot(listOf(d1, d2), "c1")
        registry.reconcile(s1)

        val r1 = registry.getRuntimeSnapshot("c1")
        assertNotNull(r1)
        assertEquals("J1", r1?.name)
        assertEquals(ChannelKind.JOURNAL, r1?.kind)

        val r2 = registry.getRuntimeSnapshot("c2")
        assertNotNull(r2)
        assertEquals("D1", r2?.name)
        
        // Reorder preservation
        val s2 = ChannelCatalogueSnapshot(listOf(d2, d1), "c1")
        registry.reconcile(s2)
        // Verify same instances are preserved
        val r1_post = registry.getRuntimeSnapshot("c1")
        assertEquals(r1, r1_post)

        // Config update replacement and retirement
        val d1_new = d1.copy(config = JournalConfig("/new/path", true, false))
        val s3 = ChannelCatalogueSnapshot(listOf(d2, d1_new), "c1")
        
        val oldRuntime = fJournal.instances.first()
        assertFalse(oldRuntime.isClosed)

        registry.reconcile(s3)
        
        // Verify new runtime created and old is retired (since it has 0 leases, it closes immediately)
        assertTrue(oldRuntime.isClosed)
        val newRuntime = fJournal.instances.last()
        assertTrue(newRuntime !== oldRuntime)

        // Removal retirement
        val s4 = ChannelCatalogueSnapshot(listOf(d2), "c2")
        registry.reconcile(s4)
        assertTrue(newRuntime.isClosed)
        assertNull(registry.getRuntimeSnapshot("c1"))
    }
    @Test
    fun runtimeSnapshotsFollowCatalogueOrderAfterReorder() {
        val fJournal = FakeRuntimeFactory()
        val fDebug = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal,
            ChannelKind.DEBUG to fDebug
        ))

        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val d2 = ChannelDefinition("c2", "D1", ChannelKind.DEBUG, true, 1, DebugConfig(DebugMode.ECHO))

        registry.reconcile(ChannelCatalogueSnapshot(listOf(d1, d2), "c1"))
        val rt1 = registry.getRuntime("c1")
        val rt2 = registry.getRuntime("c2")
        assertNotNull(rt1)
        assertNotNull(rt2)
        assertEquals(listOf("c1", "c2"), registry.getAllRuntimeSnapshots().map { it.id })

        // Pure reorder: definitions flip, configs unchanged.
        registry.reconcile(ChannelCatalogueSnapshot(listOf(d2, d1), "c1"))

        // Exposed snapshots must follow the catalogue order, not map insertion order.
        assertEquals(listOf("c2", "c1"), registry.getAllRuntimeSnapshots().map { it.id })
        // Existing runtime objects are preserved across a pure reorder.
        assertTrue(rt1 === registry.getRuntime("c1"))
        assertTrue(rt2 === registry.getRuntime("c2"))
    }

    @Test
    fun committedLeasesDeclineCloseUntilReleased() = runTest {
        val fJournal = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal
        ))

        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val s1 = ChannelCatalogueSnapshot(listOf(d1), "c1")
        registry.reconcile(s1)

        val rt = fJournal.instances.last()
        val originalTarget = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {}
            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None
            override fun onInputCancelled(reason: String) {}
            override fun onInputFailed(reason: String) {}
        }
        rt.nextAcceptance = ChannelInputAcceptance.Accepted(originalTarget)

        // Prepare input -> lease count = 1
        val prep = registry.prepareInput("c1")
        assertTrue(prep is ChannelInputAcceptance.Accepted)
        val wrappedTarget = (prep as ChannelInputAcceptance.Accepted).target

        // Update config -> old is retired but has active leases -> should NOT close immediately
        val d1_new = d1.copy(config = JournalConfig("/new/path", true, false))
        registry.reconcile(s1.copy(definitions = listOf(d1_new)))

        assertFalse(rt.isClosed) // Preserved because of lease!

        // Release lease -> old retired runtime should close exactly once
        wrappedTarget.onInputCancelled("Done")
        assertTrue(rt.isClosed)
        assertEquals(1, rt.closeCount.get())
    }

    @Test
    fun registryShutdownClearsAllRuntimesAndCancelsSession() = runTest {
        val fJournal = FakeRuntimeFactory()
        var cancelRequested = false
        val registry = ChannelRuntimeRegistry(
            mapOf(ChannelKind.JOURNAL to fJournal),
            onPttSessionCancelRequested = { cancelRequested = true }
        )

        val d1 = ChannelDefinition("c1", "J1", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(d1), "c1"))

        val rt = fJournal.instances.last()

        registry.shutdown()

        assertTrue(rt.isClosed)
        assertTrue(cancelRequested)
        
        // Subsequent prepare input is refused
        val prep = registry.prepareInput("c1")
        assertTrue(prep is ChannelInputAcceptance.Unavailable)
    }

    @Test
    fun testOnlyFourthRuntimeKindIntegration() = runTest {
        val fFourth = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.TEST_FOURTH to fFourth
        ))

        val d4 = ChannelDefinition(
            id = "c4",
            name = "Fourth Channel",
            kind = ChannelKind.TEST_FOURTH,
            enabled = true,
            configSchemaVersion = 1,
            config = TestFourthConfig("some-test-data")
        )

        val snapshot = ChannelCatalogueSnapshot(listOf(d4), "c4")
        registry.reconcile(snapshot)

        // Verify it exists in snapshots and projects correctly
        val r = registry.getRuntimeSnapshot("c4")
        assertNotNull(r)
        assertEquals("Fourth Channel", r?.name)
        assertEquals(ChannelKind.TEST_FOURTH, r?.kind)

        // Set next input acceptance on the runtime instance
        val rt = fFourth.instances.last()
        val target = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {}
            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None
            override fun onInputCancelled(reason: String) {}
            override fun onInputFailed(reason: String) {}
        }
        rt.nextAcceptance = ChannelInputAcceptance.Accepted(target)

        // Verify we can prepare input (routes correctly)
        val prep = registry.prepareInput("c4")
        assertTrue(prep is ChannelInputAcceptance.Accepted)
    }

    @Test
    fun refusedPreparationReleasesRetiredRuntimeAfterSuspension() = runTest {
        val factory = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(ChannelKind.JOURNAL to factory))
        val definition = ChannelDefinition("c1", "Journal", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), "c1"))
        val runtime = factory.instances.single()
        runtime.nextAcceptance = ChannelInputAcceptance.Refused("Sleepwalker connection failed")
        runtime.preparationGate = CompletableDeferred()
        runtime.preparationStarted = CompletableDeferred()

        val preparation = async { registry.prepareInput("c1") }
        runCurrent()
        assertTrue(runtime.preparationStarted?.isCompleted == true)

        registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(definition.copy(config = JournalConfig("/replaced", true, true))),
                "c1",
            ),
        )
        assertFalse(runtime.isClosed)

        runtime.preparationGate?.complete(Unit)
        assertEquals(ChannelInputAcceptance.Refused("Sleepwalker connection failed"), preparation.await())
        assertTrue(runtime.isClosed)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun acceptedPreparationForRetiredRuntimeIsCancelledAndRejected() = runTest {
        val factory = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(ChannelKind.JOURNAL to factory))
        val definition = ChannelDefinition("c1", "Journal", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(definition), "c1"))
        val runtime = factory.instances.single()
        val targetEvents = mutableListOf<String>()
        runtime.nextAcceptance = ChannelInputAcceptance.Accepted(object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) = Unit
            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None
            override fun onInputCancelled(reason: String) {
                targetEvents += "cancelled:$reason"
            }
            override fun onInputFailed(reason: String) = Unit
        })
        runtime.preparationGate = CompletableDeferred()
        runtime.preparationStarted = CompletableDeferred()

        val preparation = async { registry.prepareInput("c1") }
        runCurrent()
        assertTrue(runtime.preparationStarted?.isCompleted == true)

        registry.reconcile(
            ChannelCatalogueSnapshot(
                listOf(definition.copy(config = JournalConfig("/replaced", true, true))),
                "c1",
            ),
        )
        assertFalse(runtime.isClosed)

        runtime.preparationGate?.complete(Unit)
        assertEquals(
            ChannelInputAcceptance.Unavailable("Channel c1 is unavailable"),
            preparation.await(),
        )
        assertEquals(listOf("cancelled:Channel c1 changed during preparation"), targetEvents)
        assertTrue(runtime.isClosed)
        assertEquals(1, runtime.closeCount.get())
    }

    private class FakeRuntime(
        override val definition: ChannelDefinition
    ) : ChannelRuntime {
        override val id: String = definition.id
        
        private val _snapshot = MutableStateFlow(
            ChannelRuntimeSnapshot(
                id = definition.id,
                name = definition.name,
                kind = definition.kind,
                enabled = definition.enabled,
                isReady = true,
                executionStatus = ChannelExecutionStatus.IDLE
            )
        )
        override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

        var isClosed = false
        val closeCount = AtomicInteger(0)
        var nextAcceptance: ChannelInputAcceptance = ChannelInputAcceptance.Refused("No input configured")
        var preparationGate: CompletableDeferred<Unit>? = null
        var preparationStarted: CompletableDeferred<Unit>? = null

        override fun updateDefinition(definition: ChannelDefinition) {
            _snapshot.value = _snapshot.value.copy(
                name = definition.name,
                enabled = definition.enabled
            )
        }

        override suspend fun prepareInput(): ChannelInputAcceptance {
            preparationStarted?.complete(Unit)
            preparationGate?.await()
            return nextAcceptance
        }

        override fun close() {
            isClosed = true
            closeCount.incrementAndGet()
        }
    }

    private class FakeRuntimeFactory : ChannelRuntimeFactory {
        val instances = mutableListOf<FakeRuntime>()

        override fun create(definition: ChannelDefinition): ChannelRuntime {
            val rt = FakeRuntime(definition)
            instances.add(rt)
            return rt
        }
    }
}
