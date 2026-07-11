package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import dev.nilp0inter.subspace.model.JournalConfig
import dev.nilp0inter.subspace.model.DebugConfig
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.KeyboardConfig
import io.sleepwalker.core.keymap.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ServiceIntegrationTest {

    @Test
    fun catalogueToRegistryToDispatchFlow() = runTest {
        val fJournal = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal
        ))

        val d1 = ChannelDefinition("c1", "Journal", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val snapshot = ChannelCatalogueSnapshot(listOf(d1), "c1")
        registry.reconcile(snapshot)

        // Verify dispatch decider resolves correctly against active channel ID and registry snapshot
        val state = AppState(
            channels = registry.getAllRuntimeSnapshots(),
            activeChannelId = "c1"
        )
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.Dispatch("c1"), decision)
    }

    @Test
    fun committedTargetSurvivalAndRemovalDuringPtt() = runTest {
        val fJournal = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal
        ))

        val d1 = ChannelDefinition("c1", "Journal", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val snapshot = ChannelCatalogueSnapshot(listOf(d1), "c1")
        registry.reconcile(snapshot)

        val rt = fJournal.instances.first()
        val originalTarget = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {}
            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult = ChannelInputResult.None
            override fun onInputCancelled(reason: String) {}
            override fun onInputFailed(reason: String) {}
        }
        rt.nextAcceptance = ChannelInputAcceptance.Accepted(originalTarget)

        // Prepare input -> lease acquired
        val prep = registry.prepareInput("c1")
        assertTrue(prep is ChannelInputAcceptance.Accepted)
        val target = (prep as ChannelInputAcceptance.Accepted).target

        // Simulate reorder / selection change (reconcile with same configuration but different active)
        val snapshot2 = ChannelCatalogueSnapshot(listOf(d1), "c1")
        registry.reconcile(snapshot2)
        assertFalse(rt.isClosed) // Runtimes survive selection / reorder!

        // Simulate removal of definition "c1" during active PTT session
        val d2 = ChannelDefinition("c2", "Journal 2", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        val snapshot3 = ChannelCatalogueSnapshot(listOf(d2), "c2")
        registry.reconcile(snapshot3)

        // Old runtime should be retired but NOT closed since it has active leases!
        assertFalse(rt.isClosed)

        // Release target -> retired runtime closes exactly once
        target.onInputCancelled("Done")
        assertTrue(rt.isClosed)
        assertEquals(1, rt.closeCount.get())
    }

    @Test
    fun runtimeFailureIsolationAndTeardown() = runTest {
        val fJournal = FakeRuntimeFactory()
        val registry = ChannelRuntimeRegistry(mapOf(
            ChannelKind.JOURNAL to fJournal
        ))

        val d1 = ChannelDefinition("c1", "Journal", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))
        registry.reconcile(ChannelCatalogueSnapshot(listOf(d1), "c1"))

        val rt = fJournal.instances.first()
        val throwingTarget = object : ChannelInputTarget {
            override fun onInputStarted(session: ChannelAudioInputSession) {}
            override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
                throw RuntimeException("Target threw non-cancellation exception")
            }
            override fun onInputCancelled(reason: String) {}
            override fun onInputFailed(reason: String) {}
        }
        rt.nextAcceptance = ChannelInputAcceptance.Accepted(throwingTarget)

        val prep = registry.prepareInput("c1")
        assertTrue(prep is ChannelInputAcceptance.Accepted)
        val target = (prep as ChannelInputAcceptance.Accepted).target

        // Verify that even if target throws, lease gets released and no double close or leaks happen
        try {
            target.onInputReleased(RecordedPcm(shortArrayOf(1,2,3), 16_000))
        } catch (e: Exception) {
            // caught
        }

        // Registry should have 0 active leases
        // Reconcile removal
        registry.reconcile(ChannelCatalogueSnapshot(listOf(ChannelDefinition("c2", "J2", ChannelKind.JOURNAL, true, 1, JournalConfig(null, true, true))), "c2"))
        
        // Old runtime should close exactly once since leases was released!
        assertTrue(rt.isClosed)
        assertEquals(1, rt.closeCount.get())
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

        override fun updateDefinition(definition: ChannelDefinition) {
            _snapshot.value = _snapshot.value.copy(
                name = definition.name,
                enabled = definition.enabled
            )
        }

        override fun prepareInput(): ChannelInputAcceptance = nextAcceptance

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
