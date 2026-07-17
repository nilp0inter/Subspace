package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.GenerationExecutionContextImpl
import dev.nilp0inter.subspace.model.GenerationAdmission
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import dev.nilp0inter.subspace.service.ChannelActivationResult
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for the provider-neutral adapter seam. The retained bridge
 * is scripted only at the native boundary: all assertions exercise adapter
 * callbacks, snapshots, input targets, and close behavior through public
 * [dev.nilp0inter.subspace.service.ChannelRuntime] operations.
 */
class LuaAdapterRuntimeTest {

    @Test
    fun `activation invokes startup before ready lifecycle callback and publishes ready`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertNull("startup must cross the bridge with zero Lua arguments.", bridge.callbackCalls[0].arguments)

            assertEquals(
                listOf("startup", "handle_lifecycle"),
                bridge.callbackCalls.map { it.name },
            )
            assertEquals(
                LuaValue.Map(mapOf("event" to LuaValue.StringValue("ready"))),
                bridge.callbackCalls[1].arguments,
            )
            assertEquals(ChannelPreparationAvailability.Available, harness.runtime.snapshot.value.preparation)
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `prepare input uses only an explicit cached readiness projection`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val callbacksAfterRefresh = bridge.callbackCalls.size

            val accepted = harness.runtime.prepareInput()

            assertTrue("A ready callback plus handle_input must permit capture.", accepted is ChannelInputAcceptance.Accepted)
            assertEquals(
                "prepareInput must decide from the cached readiness projection without another Lua callback.",
                callbacksAfterRefresh,
                bridge.callbackCalls.size,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `proactive only runtime remains active but exposes neither input nor SOS callback`() = runTest {
        val bridge = RecordingBridge()
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val snapshotBeforeSos = harness.runtime.snapshot.value

            harness.runtime.refreshReadiness()
            val acceptance = harness.runtime.prepareInput()
            harness.runtime.handleSos()

            assertTrue("A plugin without handle_input must refuse capture.", acceptance is ChannelInputAcceptance.Refused)
            assertEquals(
                "Missing optional callbacks are neutral and must not enter Lua.",
                listOf("startup"),
                bridge.callbackCalls.map { it.name },
            )
            assertEquals(snapshotBeforeSos, harness.runtime.snapshot.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `input outcomes are strict and host capture terminal paths never play audio`() = runTest {
        val cases = listOf(
            InputCase("exact success", """{"ok":true}""", ChannelExecutionStatus.SUCCESS),
            InputCase(
                "exact application failure",
                """{"error":{"code":"E_CAPTURE_FAILURE","detail":"processing failed"}}""",
                ChannelExecutionStatus.FAILED,
            ),
            InputCase("ambiguous success and failure", """{"ok":true,"error":{"code":"E","detail":"d"}}""", ChannelExecutionStatus.FAILED),
            InputCase("false success", """{"ok":false}""", ChannelExecutionStatus.FAILED),
            InputCase("malformed failure detail", """{"error":{"code":"E","detail":""}}""", ChannelExecutionStatus.FAILED),
            InputCase("unrecognized table", """{"action":"confirm"}""", ChannelExecutionStatus.FAILED),
            InputCase("non table result", "true", ChannelExecutionStatus.FAILED),
        )

        for (case in cases) {
            val bridge = RecordingBridge().apply {
                enqueue("handle_readiness", completed("""{"ready":true}"""))
                enqueue("handle_input", completed(case.callbackResult))
            }
            val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
            try {
                assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
                harness.runtime.refreshReadiness()
                val target = (harness.runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
                    ?: throw AssertionError("${case.name}: expected accepted input")

                target.onInputStarted(session(sampleRate = 16_000))
                val result = target.onInputReleased(RecordedPcm(ShortArray(8_000), 16_000))

                assertEquals("${case.name}: v1 never returns playback", ChannelInputResult.None, result)
                assertEquals(case.expectedStatus, harness.runtime.snapshot.value.executionStatus)

                val inputEvent = bridge.callbackCalls.last { it.name == "handle_input" }.arguments as LuaValue.Map
                assertEquals(
                    "Only host-domain event identity, per-capture session, and metadata may cross into Lua.",
                    setOf("event", "session", "metadata"),
                    inputEvent.pairs.keys,
                )
                assertEquals(LuaValue.StringValue("capture"), inputEvent.pairs["event"])
                assertTrue(inputEvent.pairs["session"] is LuaValue.StringValue)
                val metadata = inputEvent.pairs["metadata"] as? LuaValue.Map
                    ?: throw AssertionError("${case.name}: metadata must be a map")
                assertEquals(setOf("duration_ms", "sample_rate", "channels"), metadata.pairs.keys)
                assertEquals(LuaValue.Number(500.0), metadata.pairs["duration_ms"])
                assertEquals(LuaValue.Number(16_000.0), metadata.pairs["sample_rate"])
                assertEquals(LuaValue.Number(1.0), metadata.pairs["channels"])
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun `host capture cancellation and failure change snapshots without entering handle input`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()

            val cancelled = acceptedTarget(harness.runtime)
            cancelled.onInputStarted(session(16_000))
            cancelled.onInputCancelled("host cancellation")
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)

            val failed = acceptedTarget(harness.runtime)
            failed.onInputStarted(session(16_000))
            failed.onInputFailed("microphone failed")
            assertEquals(ChannelExecutionStatus.FAILED, harness.runtime.snapshot.value.executionStatus)
            assertFalse(
                "Host-terminal capture paths must not invoke Lua input processing.",
                bridge.callbackCalls.any { it.name == "handle_input" },
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `yielded synchronous callbacks are contained by their callback specific host policy`() = runTest {
        val startupBridge = RecordingBridge().apply {
            enqueue("startup", yielded())
        }
        val startupHarness = harness(startupBridge, setOf("startup"))
        try {
            assertTrue(startupHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(ChannelExecutionStatus.FAILED, startupHarness.runtime.snapshot.value.executionStatus)
        } finally {
            startupHarness.close()
        }

        val readinessBridge = RecordingBridge().apply {
            enqueue("handle_readiness", yielded())
        }
        val readinessHarness = harness(readinessBridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, readinessHarness.runtime.activate())
            readinessHarness.runtime.refreshReadiness()
            assertTrue(
                "A yielded readiness callback must be locally contained as not-ready.",
                readinessHarness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
            assertEquals(ChannelExecutionStatus.IDLE, readinessHarness.runtime.snapshot.value.executionStatus)
        } finally {
            readinessHarness.close()
        }

        val inputBridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_input", yielded())
        }
        val inputHarness = harness(inputBridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, inputHarness.runtime.activate())
            inputHarness.runtime.refreshReadiness()
            val target = acceptedTarget(inputHarness.runtime)
            target.onInputStarted(session(16_000))
            assertEquals(ChannelInputResult.None, target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)))
            assertEquals(ChannelExecutionStatus.FAILED, inputHarness.runtime.snapshot.value.executionStatus)
        } finally {
            inputHarness.close()
        }
    }

    @Test
    fun `SOS failure is contained and repeated close suppresses every late callback`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_sos", LuaKernelOutcome.RuntimeFailure(41, 7, "sos failure"))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input", "handle_sos"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            val beforeSos = harness.runtime.snapshot.value

            harness.runtime.handleSos()

            assertEquals("SOS callback failure must not mutate the runtime snapshot.", beforeSos, harness.runtime.snapshot.value)
            assertEquals(1, bridge.callbackCalls.count { it.name == "handle_sos" })

            harness.runtime.close()
            harness.runtime.close()
            val callbacksAtClose = bridge.callbackCalls.size

            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Unavailable)
            harness.runtime.refreshReadiness()
            harness.runtime.handleSos()
            target.onInputStarted(session(16_000))
            assertEquals(ChannelInputResult.None, target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)))

            assertEquals("Late adapter operations must not re-enter Lua after close.", callbacksAtClose, bridge.callbackCalls.size)
            assertEquals(1, bridge.closeCalls.get())
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `activation close race has one startup entry and preserves the closed snapshot`() = runTest {
        val enteredStartup = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            beforeCallback = { name ->
                if (name == "startup") {
                    enteredStartup.countDown()
                    releaseStartup.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            val activation = AtomicReference<ChannelActivationResult>()
            val thread = Thread {
                activation.set(runBlocking { harness.runtime.activate() })
            }
            thread.start()
            enteredStartup.await()

            harness.runtime.close()
            releaseStartup.countDown()
            thread.join()

            assertTrue(activation.get() is ChannelActivationResult.Failed)
            assertEquals(1, bridge.callbackCalls.count { it.name == "startup" })
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `generation retirement during lifecycle callback cannot publish ready`() = runTest {
        val enteredLifecycle = CountDownLatch(1)
        val releaseLifecycle = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            beforeCallback = { name ->
                if (name == "handle_lifecycle") {
                    enteredLifecycle.countDown()
                    releaseLifecycle.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup", "handle_lifecycle"))
        try {
            val activation = AtomicReference<ChannelActivationResult>()
            val thread = Thread {
                activation.set(runBlocking { harness.runtime.activate() })
            }
            thread.start()
            enteredLifecycle.await()

            harness.retireGeneration()
            releaseLifecycle.countDown()
            thread.join()

            assertTrue(
                "Retirement after lifecycle dispatch but before the Ready claim must fail activation.",
                activation.get() is ChannelActivationResult.Failed,
            )
            assertFalse(harness.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Available)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `input close race cannot overwrite the terminal closed snapshot`() = runTest {
        val enteredInput = CountDownLatch(1)
        val releaseInput = CountDownLatch(1)
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            beforeCallback = { name ->
                if (name == "handle_input") {
                    enteredInput.countDown()
                    releaseInput.await()
                }
            }
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            val target = acceptedTarget(harness.runtime)
            target.onInputStarted(session(16_000))
            val input = Thread {
                runBlocking { target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)) }
            }
            input.start()
            enteredInput.await()

            harness.runtime.close()
            releaseInput.countDown()
            input.join()

            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                harness.runtime.snapshot.value.preparation,
            )
            assertEquals(ChannelExecutionStatus.IDLE, harness.runtime.snapshot.value.executionStatus)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `readiness without an explicit true revokes a prior cached input acceptance`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("handle_readiness", completed("""{"ready":true}"""))
            enqueue("handle_readiness", completed("{}"))
        }
        val harness = harness(bridge, setOf("startup", "handle_readiness", "handle_input"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.runtime.refreshReadiness()
            assertTrue(harness.runtime.prepareInput() is ChannelInputAcceptance.Accepted)

            harness.runtime.refreshReadiness()

            assertTrue(
                "Only an explicit ready=true projection may keep input acceptance cached.",
                harness.runtime.prepareInput() is ChannelInputAcceptance.Refused,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup spawned coroutine remains staged until generation authorization`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(91)))
        }
        val started = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 91L) started.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            assertTrue(
                "A startup-admitted coroutine must not execute before the registry authorizes staged tasks.",
                bridge.startedCoroutines.isEmpty(),
            )

            harness.authorizeStagedTasks()
            started.await()

            assertEquals(listOf(91L), bridge.startedCoroutines)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `startup spawn admission returns typed busy or closed without starting a child`() = runTest {
        val capacityBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(96)))
        }
        val capacityHarness = harness(capacityBridge, setOf("startup"))
        try {
            capacityHarness.saturateTaskCapacity()

            assertEquals(ChannelActivationResult.Ready, capacityHarness.runtime.activate())
            assertEquals(listOf(SpawnAdmission("startup", 96, 2)), capacityBridge.spawnAdmissions)
            assertTrue(capacityBridge.startedCoroutines.isEmpty())
        } finally {
            capacityHarness.close()
        }

        lateinit var closedHarness: AdapterHarness
        val closedBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(97)))
            beforeCallback = { name ->
                if (name == "startup") runBlocking { closedHarness.retireGeneration() }
            }
        }
        closedHarness = harness(closedBridge, setOf("startup"))
        try {
            assertTrue(closedHarness.runtime.activate() is ChannelActivationResult.Failed)
            assertEquals(listOf(SpawnAdmission("startup", 97, 1)), closedBridge.spawnAdmissions)
            assertTrue(closedBridge.startedCoroutines.isEmpty())
        } finally {
            closedHarness.close()
        }
    }

    @Test
    fun `timer saturation resumes the exact sleeping coroutine with E_BUSY`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(98)))
            startCoroutineOutcomes += yielded(coroutineId = 98, operationId = 18, value = "sleep:1")
        }
        val resumed = CompletableDeferred<Unit>()
        bridge.onCoroutineResumed = { resumed.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            harness.saturateTimerCapacity()
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()

            resumed.await()
            assertEquals(listOf(false to "E_BUSY"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `deadline first resumes timeout once and a late requested timer cannot resume again`() = runTest {
        val timers = ControlledTimerDelay()
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(110)))
            startCoroutineOutcomes += yielded(coroutineId = 110, operationId = 20, value = "sleep:1")
        }
        val harness = harness(bridge, setOf("startup"), timers::await)
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            timers.awaitPending()
            timers.assertPending(listOf(1_000L, 1_000L))

            timers.release(1)
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)

            timers.releaseAll()
            assertEquals(listOf(false to "E_TIMEOUT"), bridge.resumeCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `requested timer first resumes success once and close suppresses both pending timer paths`() = runTest {
        val successTimers = ControlledTimerDelay()
        val successBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(111)))
            startCoroutineOutcomes += yielded(coroutineId = 111, operationId = 21, value = "sleep:1")
        }
        val successHarness = harness(successBridge, setOf("startup"), successTimers::await)
        try {
            assertEquals(ChannelActivationResult.Ready, successHarness.runtime.activate())
            successHarness.authorizeStagedTasks()
            successTimers.awaitPending()
            successTimers.release(0)
            assertEquals(listOf(true to ""), successBridge.resumeCalls)
            successTimers.releaseAll()
            assertEquals(listOf(true to ""), successBridge.resumeCalls)
        } finally {
            successHarness.close()
        }

        val closeTimers = ControlledTimerDelay()
        val closeBridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(112)))
            startCoroutineOutcomes += yielded(coroutineId = 112, operationId = 22, value = "sleep:1")
        }
        val closeHarness = harness(closeBridge, setOf("startup"), closeTimers::await)
        try {
            assertEquals(ChannelActivationResult.Ready, closeHarness.runtime.activate())
            closeHarness.authorizeStagedTasks()
            closeTimers.awaitPending()
            closeTimers.assertPending(listOf(1_000L, 1_000L))
            closeHarness.runtime.close()
            closeTimers.releaseAll()
            assertTrue(closeBridge.resumeCalls.isEmpty())
        } finally {
            closeHarness.close()
        }
    }


    @Test
    fun `resume-slice nested spawn is admitted before success and cannot start before the resume returns`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(113)))
            startCoroutineOutcomes += yielded(coroutineId = 113, operationId = 23, value = "not-a-sleep")
            resumeOutcomes += completed(spawnedCoroutines = listOf(114))
            startCoroutineOutcomes += completed()
            beforeNativeSliceReturns = { caller, spawned ->
                if (caller == "resume") {
                    assertEquals(listOf(114L), spawned)
                    assertEquals(listOf(113L), startedCoroutines)
                }
            }
        }
        val childStarted = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 114L) childStarted.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            childStarted.await()
            assertEquals(listOf(113L, 114L), bridge.startedCoroutines)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 113, 0),
                    SpawnAdmission("resume", 114, 0),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a capacity-rejected resume-slice spawn returns E_BUSY and starts no child`() = runTest {
        lateinit var harness: AdapterHarness
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(115)))
            startCoroutineOutcomes += yielded(coroutineId = 115, operationId = 24, value = "not-a-sleep")
            resumeOutcomes += completed(spawnedCoroutines = listOf(116))
            beforeNativeSliceReturns = { caller, _ ->
                if (caller == "start:115") harness.saturateActiveTaskCapacity()
            }
        }
        harness = harness(bridge, setOf("startup"))
        val resumed = CompletableDeferred<Unit>()
        bridge.onCoroutineResumed = { resumed.complete(Unit) }
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            resumed.await()
            assertEquals(listOf(115L), bridge.startedCoroutines)
            assertEquals(listOf(false to "E_INVALID_YIELD"), bridge.resumeCalls)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 115, 0),
                    SpawnAdmission("resume", 116, 2),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }
    @Test
    fun `nested spawned coroutine IDs are admitted and run after authorization`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(99)))
            beforeNativeSliceReturns = { caller, spawned ->
                if (caller == "start:99") {
                    assertEquals(listOf(100L), spawned)
                    assertEquals(
                        "A child admitted in the parent native slice must remain gated until that slice returns.",
                        listOf(99L),
                        startedCoroutines,
                    )
                }
            }
            startCoroutineOutcomes += completed(spawnedCoroutines = listOf(100))
            startCoroutineOutcomes += completed()
        }
        val childStarted = CompletableDeferred<Unit>()
        bridge.onCoroutineStarted = { if (it == 100L) childStarted.complete(Unit) }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            childStarted.await()
            assertEquals(listOf(99L, 100L), bridge.startedCoroutines)
            assertEquals(
                listOf(
                    SpawnAdmission("startup", 99, 0),
                    SpawnAdmission("start:99", 100, 0),
                ),
                bridge.spawnAdmissions,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `native logs are bounded enriched and cleared by close while malformed records are contained`() = runTest {
        val validRecords = (0..128).map { index ->
            """{"level":"info","payload":{"index":$index}}"""
        }
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(logs = listOf("not-json") + validRecords))
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            val records = harness.runtime.logSnapshot

            assertEquals(128, records.size)
            assertEquals("lua-adapter", records.first().instanceId)
            assertEquals(7L, records.first().generation)
            assertEquals("info", records.first().level)
            assertTrue(records.all { it.timestampMillis > 0L })
            assertEquals(LuaValue.Number(1.0), records.first().payload.pairs["index"])
            assertEquals(LuaValue.Number(128.0), records.last().payload.pairs["index"])

            harness.runtime.close()
            assertTrue(harness.runtime.logSnapshot.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `authorized background sleep resumes exactly once and invalid yields are rejected locally`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(92, 93)))
            startCoroutineOutcomes += yielded(coroutineId = 92, operationId = 12, value = "sleep:0")
            startCoroutineOutcomes += yielded(coroutineId = 93, operationId = 13, value = "not-a-sleep")
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()

            assertTrue(bridge.resumeCalls.contains(true to ""))
            assertTrue(
                "A non-sleep native yield must be resumed as a local invalid-yield error rather than treated as a timer.",
                bridge.resumeCalls.contains(false to "E_INVALID_YIELD"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `close before a background timer fires suppresses its coroutine resume`() = runTest {
        val bridge = RecordingBridge().apply {
            enqueue("startup", completed(spawnedCoroutines = listOf(94)))
            startCoroutineOutcomes += yielded(coroutineId = 94, operationId = 14, value = "sleep:3600")
        }
        val harness = harness(bridge, setOf("startup"))
        try {
            assertEquals(ChannelActivationResult.Ready, harness.runtime.activate())
            harness.authorizeStagedTasks()
            harness.runtime.close()

            assertTrue(
                "Closing a generation with a pending sleep must discard it without a Lua resume.",
                bridge.resumeCalls.isEmpty(),
            )
        } finally {
            harness.close()
        }
    }

    private suspend fun acceptedTarget(runtime: LuaAdapterRuntime) =
        (runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected input acceptance")

    private fun session(sampleRate: Int): ChannelAudioInputSession = object : ChannelAudioInputSession {
        override val frames = emptyFlow<ShortArray>()
        override val sampleRate: Int = sampleRate
    }

    private suspend fun harness(
        bridge: RecordingBridge,
        callbacks: Set<String>,
        timerDelay: suspend (Long) -> Unit = { delay(it) },
    ): AdapterHarness {
        val instanceId = "lua-adapter"
        val generation = RuntimeGeneration(7)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(Dispatchers.Unconfined + parentJob)
        val workers = RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined)
        val boundary = RuntimeInvocationBoundary(workers)
        val gate = boundary.openGeneration(instanceId, generation, parentScope)
        val context = GenerationExecutionContextImpl(instanceId, gate, parentScope, timerDelay)
        val definition = ChannelDefinition(
            id = instanceId,
            name = "Lua adapter",
            implementationId = LUA_CHANNEL_IMPLEMENTATION_ID,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpaqueJsonObject.fromJsonObject(org.json.JSONObject()),
        )
        val image = (ImmutableProgramImage.create(
            entryPoint = "main",
            sourceMap = mapOf("main" to "return { startup = function() end }"),
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        ) as? ProgramImageCreationResult.Success)?.image
            ?: throw AssertionError("Test image must validate")
        val capabilities = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(instanceId, generation),
            declaredCapabilities = emptySet(),
            host = NoCapabilitiesHost,
        )
        bridge.retainedCallbacks = callbacks
        val result = LuaChannelImplementationProvider(image, bridge).constructRuntime(
            ChannelRuntimeConstructionRequest(
                definition = definition,
                configuration = ValidatedChannelConfiguration(
                    implementationId = LUA_CHANNEL_IMPLEMENTATION_ID,
                    schemaVersion = 1,
                    payload = definition.configPayload,
                ),
                capabilities = capabilities,
                generationContext = context,
            ),
        )
        val runtime = (result as? ChannelRuntimeConstructionResult.Success)?.runtime as? LuaAdapterRuntime
            ?: throw AssertionError("Expected a validated Lua adapter runtime, got $result")
        return AdapterHarness(runtime, context, boundary, parentJob)
    }

    private class ControlledTimerDelay {
        private data class Pending(
            val delayMillis: Long,
            val release: CompletableDeferred<Unit>,
        )

        private val pending = mutableListOf<Pending>()
        private val twoPending = CompletableDeferred<Unit>()

        suspend fun await(delayMillis: Long) {
            val release = CompletableDeferred<Unit>()
            pending += Pending(delayMillis, release)
            if (pending.size == 2) twoPending.complete(Unit)
            release.await()
        }

        suspend fun awaitPending() {
            twoPending.await()
        }

        fun assertPending(delays: List<Long>) {
            assertEquals(delays, pending.map(Pending::delayMillis))
        }

        fun release(index: Int) {
            pending.removeAt(index).release.complete(Unit)
        }

        fun releaseAll() {
            while (pending.isNotEmpty()) release(0)
        }
    }

    private class AdapterHarness(
        val runtime: LuaAdapterRuntime,
        private val context: GenerationExecutionContextImpl,
        private val boundary: RuntimeInvocationBoundary,
        private val parentJob: Job,
    ) {
        private var closed = false
        private val heldTasks = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun retireGeneration() {
            context.closeAndDrain()
        }

        fun saturateTaskCapacity() {
            repeat(256) {
                if (context.admitTask { } is GenerationAdmission.Rejected) return
            }
            throw AssertionError("Generation task admission did not enforce a capacity bound")
        }

        fun saturateActiveTaskCapacity() {
            repeat(256) {
                val release = CompletableDeferred<Unit>()
                when (context.admitTask { release.await() }) {
                    is GenerationAdmission.Accepted -> heldTasks += release
                    is GenerationAdmission.Rejected -> return
                }
            }
            throw AssertionError("Generation task admission did not enforce an active capacity bound")
        }

        fun saturateTimerCapacity() {
            repeat(256) {
                if (context.scheduleTimer(3_600.0) { } is GenerationAdmission.Rejected) return
            }
            throw AssertionError("Generation timer admission did not enforce a capacity bound")
        }

        fun authorizeStagedTasks() {
            context.authorizeStagedTasksAfterReady().forEach { it.start() }
        }

        suspend fun close() {
            if (closed) return
            closed = true
            runtime.close()
            context.closeAndDrain()
            boundary.close()
            parentJob.cancel()
        }
    }

    private object NoCapabilitiesHost : ChannelCapabilityHost {
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
    }

    private data class InputCase(
        val name: String,
        val callbackResult: String,
        val expectedStatus: ChannelExecutionStatus,
    )

    private data class CallbackCall(
        val name: String,
        val arguments: LuaValue?,
    )
    private data class SpawnAdmission(
        val caller: String,
        val coroutineId: Long,
        val result: Int,
    )
    private class RecordingBridge : LuaKernelBridge {
        val callbackCalls = mutableListOf<CallbackCall>()
        val closeCalls = AtomicInteger()
        val startedCoroutines = mutableListOf<Long>()
        val resumeCalls = mutableListOf<Pair<Boolean, String>>()
        val startCoroutineOutcomes = ArrayDeque<LuaKernelOutcome>()
        val spawnAdmissions = mutableListOf<SpawnAdmission>()
        val resumeOutcomes = ArrayDeque<LuaKernelOutcome>()
        var beforeNativeSliceReturns: ((String, List<Long>) -> Unit)? = null
        var onCoroutineStarted: ((Long) -> Unit)? = null
        var onCoroutineResumed: (() -> Unit)? = null
        var retainedCallbacks: Set<String> = setOf("startup")
        var beforeCallback: ((String) -> Unit)? = null
        private val scriptedCallbacks = mutableMapOf<String, ArrayDeque<LuaKernelOutcome>>()

        fun enqueue(name: String, outcome: LuaKernelOutcome) {
            scriptedCallbacks.getOrPut(name) { ArrayDeque() }.addLast(outcome)
        }

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = LuaKernelOutcome.Created(
            stateId = 41,
            generation = 7,
            luaVersion = LUA_VERSION,
            bindingVersion = "recording",
            topology = "recording",
        )

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed()
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed()
        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            resumeCalls += success to value
            onCoroutineResumed?.invoke()
            return admitSpawned("resume", resumeOutcomes.removeFirstOrNull() ?: completed(), spawnAdmission)
        }
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = LuaKernelOutcome.Cancelled(41, 7, operation.operationId.value)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Interrupted(41, 7, "interrupted", 0)
        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(41, 7, 0, 0, 0, 0, 0, LUA_VERSION, "recording", "recording")
        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            closeCalls.incrementAndGet()
            return LuaKernelOutcome.Closed(41, 7)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            completed(org.json.JSONArray(retainedCallbacks.toList()).toString())

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            callbackCalls += CallbackCall(callbackHandle.name, null)
            beforeCallback?.invoke(callbackHandle.name)
            return admitSpawned(
                "startup",
                scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            callbackCalls += CallbackCall(callbackHandle.name, arguments)
            beforeCallback?.invoke(callbackHandle.name)
            return admitSpawned(
                callbackHandle.name,
                scriptedCallbacks[callbackHandle.name]?.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }

        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            startedCoroutines += coroutineId.value
            onCoroutineStarted?.invoke(coroutineId.value)
            return admitSpawned(
                "start:${coroutineId.value}",
                startCoroutineOutcomes.removeFirstOrNull() ?: completed(),
                spawnAdmission,
            )
        }

        private fun admitSpawned(
            caller: String,
            outcome: LuaKernelOutcome,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome {
            val spawned = when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.spawnedCoroutines.orEmpty()
                is LuaKernelOutcome.Yielded -> outcome.spawnedCoroutines.orEmpty()
                else -> return outcome
            }
            val accepted = spawned.filter { coroutineId ->
                val result = spawnAdmission.admitTask(coroutineId)
                spawnAdmissions += SpawnAdmission(caller, coroutineId, result)
                result == 0
            }
            beforeNativeSliceReturns?.invoke(caller, spawned)
            if (accepted == spawned) return outcome
            return when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.copy(spawnedCoroutines = accepted)
                is LuaKernelOutcome.Yielded -> outcome.copy(spawnedCoroutines = accepted)
                else -> outcome
            }
        }
    }

    private companion object {
        fun completed(
            value: String? = null,
            spawnedCoroutines: List<Long>? = null,
            logs: List<String>? = null,
        ): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = 41,
            generation = 7,
            coroutineId = null,
            value = value,
            elapsedNanos = 0,
            currentBytes = 0,
            peakBytes = 0,
            deniedAllocations = 0,
            bridgeBytes = 0,
            luaVersion = LUA_VERSION,
            bindingVersion = "recording",
            topology = "recording",
            spawnedCoroutines = spawnedCoroutines,
            logs = logs,
        )

        fun yielded(
            coroutineId: Long = 9,
            operationId: Long = 10,
            value: String? = null,
        ): LuaKernelOutcome.Yielded = LuaKernelOutcome.Yielded(
            stateId = 41,
            generation = 7,
            coroutineId = coroutineId,
            operationId = operationId,
            value = value,
        )
    }
}
