package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.channel.JournalBuiltInProvider
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisitionPolicy
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailabilityResult
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.JournalDerivation
import dev.nilp0inter.subspace.channel.capability.JournalEntryHandle
import dev.nilp0inter.subspace.channel.capability.JournalEntryRequest
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.channel.capability.JournalStoredCapture
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.service.RuntimeGenerationInvocationGate
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeInvocationPhase
import dev.nilp0inter.subspace.service.RuntimeInvocationPolicy
import dev.nilp0inter.subspace.service.RuntimeInvocationOutcome
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationExecutionContextImplTest {
    @Test
    fun `same provider contexts retain instance identity and closing one leaves its sibling live`() = runTest {
        val first = context("first-instance", generation = 3)
        val second = context("second-instance", generation = 3)
        try {
            assertEquals("first-instance", first.context.instanceId)
            assertEquals("second-instance", second.context.instanceId)
            assertNotSame(first.context, second.context)
            first.context.authorizeStagedTasksAfterReady()
            second.context.authorizeStagedTasksAfterReady()

            val firstCallbacks = AtomicInteger()
            val secondCallbacks = AtomicInteger()
            accepted(first.context.scheduleTimer(100.0) { firstCallbacks.incrementAndGet() })
            accepted(second.context.scheduleTimer(0.0) { secondCallbacks.incrementAndGet() })

            first.context.closeAndDrain()
            runCurrent()

            assertEquals("retiring one instance must suppress only its own timer", 0, firstCallbacks.get())
            assertEquals("a same-provider sibling must retain its timer authority", 1, secondCallbacks.get())
            assertFalse(first.context.isActive())
            assertTrue(second.context.isActive())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `timer and task saturation are distinct from post-close rejection`() = runTest {
        val timers = context("timer-capacity")
        val tasks = context("task-capacity")
        try {
            val timerRejection = firstRejection {
                timers.context.scheduleTimer(60_000.0) { }
            }
            assertEquals(GenerationAdmissionRejection.CAPACITY_EXHAUSTED, timerRejection.reason)
            timers.context.closeAndDrain()
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(timers.context.scheduleTimer(0.0) { }).reason,
            )

            val taskRejection = firstRejection {
                tasks.context.admitTask { }
            }
            assertEquals(GenerationAdmissionRejection.CAPACITY_EXHAUSTED, taskRejection.reason)
            tasks.context.closeAndDrain()
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(tasks.context.admitTask { }).reason,
            )
        } finally {
            timers.close()
            tasks.close()
        }
    }

    @Test
    fun `staged tasks reserve capacity without execution then authorize or discard deterministically`() = runTest {
        val authorized = context("authorized")
        val discarded = context("discarded")
        try {
            val authorizedRuns = AtomicInteger()
            accepted(authorized.context.admitTask { authorizedRuns.incrementAndGet() })
            runCurrent()
            assertEquals("a staged task must not run before readiness publication", 0, authorizedRuns.get())

            val authorizedJobs = authorized.context.authorizeStagedTasksAfterReady()
            runCurrent()
            assertEquals("authorized staged work remains inert until the registry starts its returned jobs", 0, authorizedRuns.get())
            authorizedJobs.forEach { it.start() }
            runCurrent()
            assertEquals("starting the authorized jobs after readiness must make the staged task runnable", 1, authorizedRuns.get())

            val discardedRuns = AtomicInteger()
            accepted(discarded.context.admitTask { discardedRuns.incrementAndGet() })
            discarded.context.discardStagedTasks()
            val discardedJobs = discarded.context.authorizeStagedTasksAfterReady()
            assertTrue("discard must remove every staged job before authorization", discardedJobs.isEmpty())
            runCurrent()
            assertEquals("discarded staged tasks must never execute", 0, discardedRuns.get())
        } finally {
            authorized.close()
            discarded.close()
        }
    }

    @Test
    fun `active task admission does not preempt its current invocation slice`() = runTest {
        val harness = context("non-preemptive")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val taskRan = AtomicInteger()

            val outcome = harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                accepted(harness.context.admitTask { taskRan.incrementAndGet() })
                assertEquals("admitted work must not run inside the admitting invocation", 0, taskRan.get())
            }

            assertTrue("the admitting invocation must complete normally", outcome is RuntimeInvocationOutcome.Success)
            runCurrent()
            assertEquals("admitted work must run after the invocation slice", 1, taskRan.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `timers fire once and explicit disposal prevents their callback`() = runTest {
        val harness = context("timers")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val delivered = AtomicInteger()
            val deliveredHandle = accepted(harness.context.scheduleTimer(10.0) { delivered.incrementAndGet() })
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("an accepted timer must invoke its callback once", 1, delivered.get())

            deliveredHandle.dispose()
            deliveredHandle.dispose()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("a one-shot timer must not invoke again after disposal", 1, delivered.get())

            val disposed = AtomicInteger()
            val disposedHandle = accepted(harness.context.scheduleTimer(10.0) { disposed.incrementAndGet() })
            disposedHandle.dispose()
            disposedHandle.dispose()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("idempotent disposal must suppress a pending timer callback", 0, disposed.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `close drains active work and permanently suppresses stale callbacks and admissions`() = runTest {
        val harness = context("closing")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val taskStarted = CompletableDeferred<Unit>()
            val taskFinallyRan = CompletableDeferred<Unit>()
            val staleTimerCallbacks = AtomicInteger()
            accepted(harness.context.admitTask {
                taskStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    taskFinallyRan.complete(Unit)
                }
            })
            accepted(harness.context.scheduleTimer(60.0) { staleTimerCallbacks.incrementAndGet() })
            runCurrent()
            assertTrue(taskStarted.isCompleted)

            harness.context.closeAndDrain()
            harness.context.closeAndDrain()
            assertTrue("closeAndDrain must await cancellation of active task work", taskFinallyRan.isCompleted)
            assertFalse(harness.context.isActive())
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("a timer accepted before close must not enter a retired generation", 0, staleTimerCallbacks.get())
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(harness.context.scheduleTimer(0.0) { }).reason,
            )
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(harness.context.admitTask { }).reason,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `journal provider behavior is unchanged when it ignores a closed generation context`() = runTest {
        val live = context("journal-live")
        val closed = context("journal-closed")
        val payload = opaque("""{"baseDirectory":"/records","saveVoice":false,"saveText":true}""")
        try {
            closed.context.closeAndDrain()

            val provider = JournalBuiltInProvider()
            val liveResult = provider.constructRuntime(journalRequest("journal-live", payload, live.context))
            val closedResult = provider.constructRuntime(journalRequest("journal-closed", payload, closed.context))
            val liveRuntime = success(liveResult)
            val closedRuntime = success(closedResult)

            assertTrue(liveRuntime.prepareInput() is ChannelInputAcceptance.Accepted)
            assertTrue(
                "Kotlin provider construction and input admission must not depend on the unused context",
                closedRuntime.prepareInput() is ChannelInputAcceptance.Accepted,
            )

            liveRuntime.close()
            closedRuntime.close()
        } finally {
            live.close()
            closed.close()
        }
    }

    private fun TestScope.context(
        instanceId: String,
        generation: Long = 0,
    ): ContextHarness {
        val boundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = 1_000,
                inputReleasedTimeoutMillis = 1_000,
                closeTimeoutMillis = 1_000,
            ),
        )
        val gate = boundary.openGeneration(instanceId, RuntimeGeneration(generation), this)
        return ContextHarness(
            boundary = boundary,
            gate = gate,
            context = GenerationExecutionContextImpl(instanceId, gate, this),
        )
    }

    private fun journalRequest(
        instanceId: String,
        payload: OpaqueJsonObject,
        context: GenerationExecutionContext,
    ): ChannelRuntimeConstructionRequest {
        val implementationId = BuiltInChannelImplementationIds.JOURNAL
        return ChannelRuntimeConstructionRequest(
            definition = ChannelDefinition(
                id = instanceId,
                name = "Journal $instanceId",
                implementationId = implementationId,
                enabled = true,
                configSchemaVersion = 1,
                configPayload = payload,
            ),
            configuration = ValidatedChannelConfiguration(implementationId, 1, payload),
            capabilities = journalCapabilities(instanceId),
            generationContext = context,
        )
    }

    private fun journalCapabilities(instanceId: String): ChannelCapabilityScope = RevocableChannelCapabilityScope(
        identity = CapabilityScopeIdentity(instanceId, RuntimeGeneration(0)),
        declaredCapabilities = setOf(ChannelCapability.Journal),
        host = JournalCapabilityHost,
    )

    private fun firstRejection(
        admission: () -> GenerationAdmission<*>,
    ): GenerationAdmission.Rejected {
        repeat(512) {
            val result = admission()
            if (result is GenerationAdmission.Rejected) return result
        }
        throw AssertionError("Expected bounded admission to reject before the safety limit")
    }

    private fun <T> accepted(admission: GenerationAdmission<T>): T =
        (admission as? GenerationAdmission.Accepted<T>)?.value
            ?: throw AssertionError("Expected accepted admission, got $admission")

    private fun rejected(admission: GenerationAdmission<*>): GenerationAdmission.Rejected =
        admission as? GenerationAdmission.Rejected
            ?: throw AssertionError("Expected rejected admission, got $admission")

    private fun success(result: ChannelRuntimeConstructionResult) =
        (result as? ChannelRuntimeConstructionResult.Success)?.runtime
            ?: throw AssertionError("Expected Kotlin provider runtime, got $result")

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private class ContextHarness(
        private val boundary: RuntimeInvocationBoundary,
        val gate: RuntimeGenerationInvocationGate,
        val context: GenerationExecutionContextImpl,
    ) {
        suspend fun close() {
            context.closeAndDrain()
            gate.close { }
            boundary.close()
        }
    }

    private object JournalCapabilityHost : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.Journal -> HostedCapabilityAcquisition.Available(JournalPort as T) { }
            else -> error("Journal provider requested undeclared capability ${key.capability}")
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private object JournalPort : JournalStorageCapability {
        override suspend fun createEntry(request: JournalEntryRequest): Nothing =
            error("Journal input handling is outside provider construction coverage")

        override suspend fun storeCapture(
            entry: JournalEntryHandle,
            recording: OpaqueAudioRecording,
        ): Nothing = error("Journal input handling is outside provider construction coverage")

        override suspend fun derive(entry: JournalEntryHandle): Nothing =
            error("Journal input handling is outside provider construction coverage")
    }
}
