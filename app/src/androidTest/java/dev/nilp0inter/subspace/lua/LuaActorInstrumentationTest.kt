package dev.nilp0inter.subspace.lua

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.lua.actor.ActorConstructResult
import dev.nilp0inter.subspace.lua.actor.ActorEventEnvelope
import dev.nilp0inter.subspace.lua.actor.ActorEventId
import dev.nilp0inter.subspace.lua.actor.ActorEventIdentity
import dev.nilp0inter.subspace.lua.actor.ActorFailureClassification
import dev.nilp0inter.subspace.lua.actor.ActorFailureResult
import dev.nilp0inter.subspace.lua.actor.ActorGateCommit
import dev.nilp0inter.subspace.lua.actor.ActorGateResult
import dev.nilp0inter.subspace.lua.actor.ActorGenerationGate
import dev.nilp0inter.subspace.lua.actor.ActorKernelConfig
import dev.nilp0inter.subspace.lua.actor.ActorLoadResult
import dev.nilp0inter.subspace.lua.actor.ActorMailboxResult
import dev.nilp0inter.subspace.lua.actor.ActorPolicy
import dev.nilp0inter.subspace.lua.actor.ActorRuntime
import dev.nilp0inter.subspace.lua.actor.ActorStartupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only conformance and evidence workload for the promoted internal Lua actor runtime.
 *
 * Run on a physical arm64 device for the evaluated APK/native build:
 *
 * `adb shell am instrument -w -e class dev.nilp0inter.subspace.lua.LuaActorInstrumentationTest \
 *     dev.nilp0inter.subspace.test/androidx.test.runner.AndroidJUnitRunner`
 *
 * Every invocation emits exactly one `LUA_ACTOR_EVIDENCE` JSON record through the
 * instrumentation status channel. Correctness assertions are deliberately separate from
 * timing and allocation observations: observed values are evidence, never public limits.
 */
@RunWith(AndroidJUnit4::class)
class LuaActorInstrumentationTest {
    @Test
    fun actorRuntimeConformanceEmitsDeviceEvidence() {
        val evidence = EvidenceCollector(runContext())
        val failures = mutableListOf<String>()
        val workload = ActorWorkload(evidence, failures)

        try {
            workload.run()
        } finally {
            workload.closeLeakedStates()
            val record = evidence.toJson()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString(EVIDENCE_BUNDLE_KEY, record) },
            )
            Log.i(EVIDENCE_TAG, "emitted $EVIDENCE_BUNDLE_KEY (${record.length} bytes)")
        }

        assertTrue(
            "Lua actor correctness gates failed:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    private fun runContext(): JSONObject {
        val arguments = InstrumentationRegistry.getArguments()
        val applicationInfo = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        val buildType = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "debuggable"
        } else {
            "release-equivalent"
        }
        return JSONObject()
            .put("runId", arguments.getString("luaActorRunId") ?: UUID.randomUUID().toString())
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("buildType", buildType)
            .put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            .put("nativeLibrary", "subspace_lua_actor")
            .put("nativeBuild", "cargo-ndk release; minApi=31; packagedAbi=arm64-v8a")
            .put("threadCountAtStart", Thread.activeCount())
    }

    private class ActorWorkload(
        private val evidence: EvidenceCollector,
        private val failures: MutableList<String>,
    ) {
        private val bridge: LuaKernelBridge = LuaNativeKernelBridge()
        private val createdHandles = LinkedHashSet<LuaStateHandle>()
        private val kernelConfig = LuaKernelConfig(
            memoryLimitBytes = 2L * 1024L * 1024L,
            hookInterval = 37,
            instructionBudget = 250_000L,
        )

        fun run() {
            startupReadinessAndBoundedMailbox()
            yieldedSlicesReleaseNativeEntryAndResumeIndependently()
            instructionInterruptionAndAllocatorDenialAreContained()
            localAndFatalFailuresHaveDifferentContainmentScopes()
            staleForeignClosedAndRacingTerminalsProduceOneEffect()
            actorStateIsolationAndReplacementSuppressLateCompletion()
            binaryAndNativeModuleRequestsDoNotPoisonBenignEvents()
            shutdownStopsAdmissionAndRejectsLateCompletion()
        }

        /** Startup publishes readiness before ordinary admission; a full mailbox rejects without a retained waiter. */
        private fun startupReadinessAndBoundedMailbox() = probe("startup_readiness_and_bounded_mailbox") { scope ->
            val actor = scope.newActor("startup", mailboxCapacity = 2)
            try {
                scope.expect(actor.construct() is ActorConstructResult.Success) {
                    "actor construction must create an independent native state"
                }
                scope.expect(actor.loadSource(returningEntrypoint("ready"), "entry") is ActorLoadResult.Loaded) {
                    "valid startup source must stage the actor"
                }
                scope.expect(runBlocking { actor.startup() } is ActorStartupResult.Ready) {
                    "protected startup must publish actor readiness"
                }
                scope.expect(actor.isReady) { "actor must be ready after a successful protected startup" }

                val first = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                val second = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                val overflow = actor.admitEvent(scope.readinessEnvelope(actor.identity))
                scope.recordActorAdmission("mailbox_first", first)
                scope.recordActorAdmission("mailbox_second", second)
                scope.recordActorAdmission("mailbox_overflow", overflow)
                scope.expect(first is ActorMailboxResult.Admitted && second is ActorMailboxResult.Admitted) {
                    "two events must occupy the injected two-slot mailbox"
                }
                scope.expect(overflow is ActorMailboxResult.Busy && actor.mailboxDepth == 2) {
                    "overflow must return Busy and must not grow or retain an unbounded waiter queue"
                }
            } finally {
                scope.closeActor(actor)
            }
        }

        /** Yielded coroutines release native entry; independent suspended operations resume exactly once. */
        private fun yieldedSlicesReleaseNativeEntryAndResumeIndependently() = probe("yield_slot_release_and_multiple_suspensions") { scope ->
            val first = scope.create(kernelConfig)
            val second = scope.create(kernelConfig)
            scope.expectLoaded(first, yieldingEntrypoint("first", "return value"))
            scope.expectLoaded(second, yieldingEntrypoint("second", "return value"))

            val firstOperation = scope.expectYielded(scope.call("start_first") { bridge.start(first) }, "first")
            val secondOperation = scope.expectYielded(scope.call("start_second_after_first_yield") { bridge.start(second) }, "second")
            scope.expectCompletedValue(
                scope.call("resume_second") { bridge.resume(secondOperation, success = true, value = "second-complete") },
                "second-complete",
            )
            scope.expectCompletedValue(
                scope.call("resume_first") { bridge.resume(firstOperation, success = true, value = "first-complete") },
                "first-complete",
            )
            scope.close(first)
            scope.close(second)
        }

        /** Explicit policy inputs interrupt pure Lua and deny allocation while a peer remains usable. */
        private fun instructionInterruptionAndAllocatorDenialAreContained() = probe("instruction_and_allocator_containment") { scope ->
            val interrupted = scope.create(
                LuaKernelConfig(memoryLimitBytes = 2L * 1024L * 1024L, hookInterval = 13, instructionBudget = 10_000L),
            )
            scope.expectLoaded(interrupted, "function entry() while true do end end")
            val interruption = scope.call("start_infinite_loop") { bridge.start(interrupted) }
            scope.expect(interruption is LuaKernelOutcome.Interrupted) {
                "pure-Lua infinite loop must normalize as Interrupted, got ${interruption.describe()}"
            }
            scope.close(interrupted)

            val constrained = scope.create(
                LuaKernelConfig(memoryLimitBytes = 128L * 1024L, hookInterval = 31, instructionBudget = 250_000L),
            )
            scope.expectLoaded(
                constrained,
                "function entry() local t = {}; for i = 1, 100000 do t[i] = {i, i * 2} end; return #t end",
            )
            val denial = scope.call("start_allocation_growth") { bridge.start(constrained) }
            val memoryFailure = denial as? LuaKernelOutcome.MemoryFailure
            scope.expect(memoryFailure != null && (memoryFailure.deniedAllocations ?: 0L) > 0L) {
                "explicit allocator limit must report MemoryFailure with a denied allocation, got ${denial.describe()}"
            }
            val allocation = scope.snapshot(constrained, "after_denial")
            scope.expect(allocation.deniedAllocations > 0L && allocation.peakBytes >= allocation.currentBytes) {
                "allocator observation must preserve denial and peak/current accounting after denial"
            }
            scope.close(constrained)

            val survivor = scope.create(kernelConfig)
            scope.expectLoaded(survivor, returningEntrypoint("survives-policy-failure"))
            scope.expectCompletedValue(scope.call("start_policy_survivor") { bridge.start(survivor) }, "survives-policy-failure")
            scope.close(survivor)
        }

        /** Ordinary protected errors remain local; fatal classification latches only its owning actor. */
        private fun localAndFatalFailuresHaveDifferentContainmentScopes() = probe("local_vs_fatal_containment") { scope ->
            val failing = scope.newActor("failure-a", mailboxCapacity = 1)
            val peer = scope.newActor("failure-b", mailboxCapacity = 1)
            try {
                scope.startActor(failing, "failure-a")
                scope.startActor(peer, "failure-b")

                val local = failing.classifyFailure(ActorFailureClassification.OrdinaryEvent("ordinary protected error"))
                scope.recordActorOutcome("ordinary_error", local)
                scope.expect(local is ActorFailureResult.LocalContained && failing.isReady && peer.isReady) {
                    "ordinary protected error must remain local and keep both actor generations usable"
                }

                val fatal = failing.classifyFailure(ActorFailureClassification.Instruction("instruction budget exhausted"))
                scope.recordActorOutcome("instruction_fatal", fatal)
                scope.expect(fatal is ActorFailureResult.FatalLatched && failing.isFailed && !failing.isReady) {
                    "instruction failure must latch only the affected actor unavailable"
                }
                scope.expect(peer.admitEvent(scope.readinessEnvelope(peer.identity)) is ActorMailboxResult.Admitted) {
                    "fatal failure in one actor must not stop an unrelated actor admission"
                }
            } finally {
                scope.closeActor(failing)
                scope.closeActor(peer)
            }
        }

        /** Stale, foreign, closed, and terminal-race requests cannot produce a second Lua effect. */
        private fun staleForeignClosedAndRacingTerminalsProduceOneEffect() = probe("terminal_identity_and_exactly_once_races") { scope ->
            val owner = scope.create(kernelConfig)
            val foreignState = scope.create(kernelConfig)
            scope.expectLoaded(owner, "hits = 0; function entry() local _, value = subspace.yield_operation('race'); hits = hits + 1; return value end")
            scope.expectLoaded(foreignState, returningEntrypoint("foreign-survivor"))
            val operation = scope.expectYielded(scope.call("start_owner") { bridge.start(owner) }, "race")

            val foreign = operation.copy(stateHandle = foreignState)
            val foreignOutcome = scope.call("resume_foreign") { bridge.resume(foreign, true, "forbidden") }
            scope.expect(foreignOutcome is LuaKernelOutcome.InvalidOwnership) {
                "foreign terminal request must return InvalidOwnership, got ${foreignOutcome.describe()}"
            }
            val stale = operation.copy(
                stateHandle = operation.stateHandle.copy(
                    generation = LuaStateGeneration(operation.stateHandle.generation.value + 1L),
                ),
            )
            val staleOutcome = scope.call("resume_stale") { bridge.resume(stale, true, "forbidden") }
            scope.expect(staleOutcome is LuaKernelOutcome.Stale) {
                "stale terminal request must return Stale, got ${staleOutcome.describe()}"
            }

            val race = race(
                Callable { bridge.resume(operation, success = true, value = "winner") },
                Callable { bridge.cancel(operation) },
            )
            scope.recordRace("completion_vs_cancellation", race)
            val terminalKinds = race.mapNotNull { it.terminalKind() }.toSet()
            scope.expect(terminalKinds.size == 1 && terminalKinds.single() in setOf("completed", "cancelled")) {
                "terminal race must expose exactly one accepted terminal kind, got ${race.joinToString { it.describe() }}"
            }
            scope.expectLoaded(owner, "function observe() return tostring(hits) end", entrypoint = "observe")
            scope.expectCompletedValue(
                scope.call("observe_terminal_effect") { bridge.start(owner) },
                if (terminalKinds.single() == "completed") "1" else "0",
            )
            scope.close(owner)
            val closed = scope.call("resume_after_close") { bridge.resume(operation, true, "late") }
            scope.expect(closed is LuaKernelOutcome.Closed || closed is LuaKernelOutcome.Stale) {
                "closed operation completion must be rejected without Lua entry, got ${closed.describe()}"
            }
            scope.expectCompletedValue(scope.call("start_foreign_survivor") { bridge.start(foreignState) }, "foreign-survivor")
            scope.close(foreignState)
        }

        /** Equal names stay isolated; G's late completion cannot reach fresh replacement H. */
        private fun actorStateIsolationAndReplacementSuppressLateCompletion() = probe("state_isolation_and_replacement_late_suppression") { scope ->
            val predecessor = scope.create(kernelConfig)
            val successor = scope.create(kernelConfig)
            scope.expectLoaded(predecessor, "shared = 'generation-g'; function entry() local _, value = subspace.yield_operation('g'); return shared .. ':' .. value end")
            scope.expectLoaded(successor, "shared = 'generation-h'; function entry() return shared end")
            val oldOperation = scope.expectYielded(scope.call("start_generation_g") { bridge.start(predecessor) }, "g")
            scope.close(predecessor)

            val late = scope.call("late_completion_generation_g") { bridge.resume(oldOperation, true, "must-not-reach-h") }
            scope.expect(late is LuaKernelOutcome.Closed || late is LuaKernelOutcome.Stale) {
                "replacement must suppress G late completion, got ${late.describe()}"
            }
            scope.expectCompletedValue(scope.call("start_generation_h") { bridge.start(successor) }, "generation-h")
            scope.close(successor)
        }

        /** Executed binary/native-loader attempts fail before effect execution and a benign event still succeeds. */
        private fun binaryAndNativeModuleRequestsDoNotPoisonBenignEvents() = probe("binary_and_native_module_rejection") { scope ->
            val state = scope.create(kernelConfig)
            val binary = "\u001bLua\u0000\u0019\u0093\r\n\u001a\n"
            val binaryOutcome = scope.call("load_binary") { bridge.load(state, binary, "entry") }
            scope.expect(binaryOutcome is LuaKernelOutcome.SyntaxFailure) {
                "binary bytecode must be rejected before native execution, got ${binaryOutcome.describe()}"
            }

            scope.expectLoaded(
                state,
                "effects = 0; function entry() local forbidden = package.loadlib; effects = effects + 1; return forbidden('/tmp/untrusted.so', 'entry') end",
            )
            val moduleOutcome = scope.call("execute_package_loadlib") { bridge.start(state) }
            scope.expect(moduleOutcome is LuaKernelOutcome.RuntimeFailure) {
                "native module loading must fail through the executed Lua boundary, got ${moduleOutcome.describe()}"
            }
            scope.expectLoaded(state, returningEntrypoint("benign-after-module-rejection"))
            scope.expectCompletedValue(
                scope.call("start_benign_after_rejection") { bridge.start(state) },
                "benign-after-module-rejection",
            )
            scope.close(state)
        }

        /** Shutdown closes once, stops admission, and suppresses a late completion without a timing threshold. */
        private fun shutdownStopsAdmissionAndRejectsLateCompletion() = probe("bounded_shutdown") { scope ->
            val actor = scope.newActor("shutdown", mailboxCapacity = 1)
            try {
                scope.startActor(actor, "shutdown-ready")
                scope.expect(actor.admitEvent(scope.readinessEnvelope(actor.identity)) is ActorMailboxResult.Admitted) {
                    "live actor must admit an event before shutdown"
                }
                val started = System.nanoTime()
                val closed = runBlocking { actor.close() }
                evidence.recordActorClose(System.nanoTime() - started)
                scope.expect(closed.toString().contains("Closed") && actor.isClosed) {
                    "shutdown must close the actor and publish its terminal state"
                }
                scope.expect(actor.admitEvent(scope.readinessEnvelope(actor.identity)) is ActorMailboxResult.Closed) {
                    "shutdown must stop future mailbox admission"
                }
            } finally {
                scope.closeActor(actor)
            }
        }

        private fun probe(id: String, body: (ProbeScope) -> Unit) {
            val scope = ProbeScope(id, bridge, evidence, createdHandles)
            val started = System.nanoTime()
            try {
                body(scope)
                evidence.recordProbe(id, passed = true, elapsedNanos = System.nanoTime() - started, failure = null)
            } catch (failure: Throwable) {
                val message = "$id: ${failure.message ?: failure::class.java.name}"
                failures += message
                evidence.recordProbe(id, passed = false, elapsedNanos = System.nanoTime() - started, failure = message)
            }
        }

        fun closeLeakedStates() {
            createdHandles.toList().forEach { handle ->
                val started = System.nanoTime()
                evidence.recordOperation(
                    probe = "cleanup",
                    operation = "close_leaked_state",
                    outcome = bridge.close(handle),
                    elapsedNanos = System.nanoTime() - started,
                    parameters = emptyMap(),
                )
                createdHandles.remove(handle)
            }
        }
    }

    private class ProbeScope(
        private val probe: String,
        private val bridge: LuaKernelBridge,
        private val evidence: EvidenceCollector,
        private val createdHandles: MutableSet<LuaStateHandle>,
    ) {
        fun create(config: LuaKernelConfig): LuaStateHandle {
            val outcome = call(
                "create",
                mapOf(
                    "memoryLimitBytes" to config.memoryLimitBytes.toString(),
                    "hookInterval" to config.hookInterval.toString(),
                    "instructionBudget" to config.instructionBudget.toString(),
                ),
            ) { bridge.create(config) }
            val created = outcome as? LuaKernelOutcome.Created
                ?: throw AssertionError("state creation must succeed, got ${outcome.describe()}")
            evidence.recordNativeProvenance(created.luaVersion, created.bindingVersion, created.topology)
            return LuaStateHandle(LuaStateId(created.stateId), LuaStateGeneration(created.generation)).also(createdHandles::add)
        }

        fun expectLoaded(handle: LuaStateHandle, source: String, entrypoint: String = "entry") {
            val outcome = call("load", mapOf("entrypoint" to entrypoint)) { bridge.load(handle, source, entrypoint) }
            expect(outcome is LuaKernelOutcome.Completed) {
                "valid text source must load, got ${outcome.describe()}"
            }
        }

        fun expectYielded(outcome: LuaKernelOutcome, label: String): LuaOperationHandle {
            val yielded = outcome as? LuaKernelOutcome.Yielded
                ?: throw AssertionError("entrypoint must yield '$label', got ${outcome.describe()}")
            expect(yielded.value == label) {
                "yield label must survive the actor kernel boundary: expected $label, got ${yielded.value}"
            }
            return yielded.operationHandle()
        }

        fun expectCompletedValue(outcome: LuaKernelOutcome, expected: String) {
            val completed = outcome as? LuaKernelOutcome.Completed
                ?: throw AssertionError("entrypoint must complete '$expected', got ${outcome.describe()}")
            expect(completed.value == expected) {
                "completed value must be '$expected', got '${completed.value}'"
            }
        }

        fun snapshot(handle: LuaStateHandle, phase: String): LuaKernelOutcome.Snapshot {
            val outcome = call("snapshot", mapOf("phase" to phase)) { bridge.snapshot(handle) }
            val snapshot = outcome as? LuaKernelOutcome.Snapshot
                ?: throw AssertionError("snapshot '$phase' must provide allocator evidence, got ${outcome.describe()}")
            expect(snapshot.currentBytes >= 0L && snapshot.peakBytes >= snapshot.currentBytes) {
                "snapshot '$phase' must preserve non-negative current bytes and peak >= current"
            }
            return snapshot
        }

        fun close(handle: LuaStateHandle) {
            val outcome = call("close") { bridge.close(handle) }
            expect(outcome is LuaKernelOutcome.Closed) {
                "close must return Closed, got ${outcome.describe()}"
            }
            createdHandles.remove(handle)
        }

        fun newActor(instanceId: String, mailboxCapacity: Int): ActorRuntime {
            val policy = ActorPolicy(
                luaKernelConfig = ActorKernelConfig(
                    memoryLimitBytes = 2L * 1024L * 1024L,
                    hookInterval = 41,
                    instructionBudget = 250_000L,
                ),
                mailboxCapacity = mailboxCapacity,
                activeSliceBudgetMillis = 2_000L,
                operationWaitDeadlineMillis = 3_000L,
                callbackTimeoutMillis = 2_500L,
                closeTimeoutMillis = 1_000L,
                maxConcurrentTasks = 2,
                perTaskDeadlineMillis = 3_000L,
            )
            evidence.recordActorPolicy(probe, policy)
            return ActorRuntime(
                scope = CapabilityScopeIdentity(instanceId, RuntimeGeneration(1L)),
                bridge = bridge,
                gate = TestGate(),
                policy = policy,
                capabilityScope = null,
                parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }

        fun startActor(actor: ActorRuntime, expectedValue: String) {
            expect(actor.construct() is ActorConstructResult.Success) { "actor must construct before startup" }
            expect(actor.loadSource(returningEntrypoint(expectedValue), "entry") is ActorLoadResult.Loaded) {
                "actor source must stage before startup"
            }
            expect(runBlocking { actor.startup() } is ActorStartupResult.Ready) {
                "actor startup must publish readiness"
            }
        }

        fun closeActor(actor: ActorRuntime) {
            runBlocking { actor.close() }
        }

        fun readinessEnvelope(scope: CapabilityScopeIdentity): ActorEventEnvelope = ActorEventEnvelope.Readiness(
            ActorEventIdentity(scope, ActorEventId.next()),
        )

        fun recordActorAdmission(operation: String, result: ActorMailboxResult) {
            evidence.recordActorOutcome("$operation=${result::class.simpleName}")
        }

        fun recordActorOutcome(operation: String, result: Any) {
            evidence.recordActorOutcome("$operation=${result::class.simpleName}")
        }

        fun recordRace(name: String, outcomes: List<LuaKernelOutcome>) {
            evidence.recordRace(name, outcomes.map { it.toJson() })
            outcomes.forEachIndexed { index, outcome ->
                evidence.recordOperation(probe, "$name[$index]", outcome, null, mapOf("threadCount" to Thread.activeCount().toString()))
            }
        }

        fun call(
            operation: String,
            parameters: Map<String, String> = emptyMap(),
            invoke: () -> LuaKernelOutcome,
        ): LuaKernelOutcome {
            val started = System.nanoTime()
            val outcome = invoke()
            evidence.recordOperation(probe, operation, outcome, System.nanoTime() - started, parameters)
            return outcome
        }

        fun expect(condition: Boolean, lazyMessage: () -> String) {
            if (!condition) throw AssertionError(lazyMessage())
        }
    }

    private class EvidenceCollector(
        private val context: JSONObject,
    ) {
        private val probes = JSONArray()
        private val operations = JSONArray()
        private val races = JSONArray()
        private val actorPolicies = JSONArray()
        private val actorOutcomes = JSONArray()
        private val outcomeCounts = linkedMapOf<String, Int>()
        private val nativeProvenance = linkedMapOf<String, String>()

        fun recordNativeProvenance(luaVersion: String, bindingVersion: String, topology: String) {
            nativeProvenance["luaVersion"] = luaVersion
            nativeProvenance["bindingVersion"] = bindingVersion
            nativeProvenance["kernelTopology"] = topology
        }

        fun recordProbe(id: String, passed: Boolean, elapsedNanos: Long, failure: String?) {
            probes.put(
                JSONObject()
                    .put("id", id)
                    .put("passed", passed)
                    .put("elapsedNanos", elapsedNanos)
                    .put("failure", failure),
            )
        }

        fun recordOperation(
            probe: String,
            operation: String,
            outcome: LuaKernelOutcome,
            elapsedNanos: Long?,
            parameters: Map<String, String>,
        ) {
            val kind = outcome.kind()
            outcomeCounts[kind] = (outcomeCounts[kind] ?: 0) + 1
            operations.put(
                JSONObject()
                    .put("probe", probe)
                    .put("operation", operation)
                    .put("outcome", outcome.toJson())
                    .put("elapsedNanos", elapsedNanos)
                    .put("parameters", JSONObject(parameters))
                    .put("threadCount", Thread.activeCount()),
            )
        }

        fun recordRace(name: String, outcomes: List<JSONObject>) {
            races.put(JSONObject().put("name", name).put("outcomes", JSONArray(outcomes)))
        }

        fun recordActorPolicy(probe: String, policy: ActorPolicy) {
            actorPolicies.put(
                JSONObject()
                    .put("probe", probe)
                    .put("memoryLimitBytes", policy.luaKernelConfig.memoryLimitBytes)
                    .put("hookInterval", policy.luaKernelConfig.hookInterval)
                    .put("instructionBudget", policy.luaKernelConfig.instructionBudget)
                    .put("mailboxCapacity", policy.mailboxCapacity)
                    .put("activeSliceBudgetMillis", policy.activeSliceBudgetMillis)
                    .put("operationWaitDeadlineMillis", policy.operationWaitDeadlineMillis)
                    .put("callbackTimeoutMillis", policy.callbackTimeoutMillis)
                    .put("closeTimeoutMillis", policy.closeTimeoutMillis),
            )
        }

        fun recordActorOutcome(value: String) {
            actorOutcomes.put(value)
        }

        fun recordActorClose(elapsedNanos: Long) {
            actorOutcomes.put(JSONObject().put("operation", "close").put("elapsedNanos", elapsedNanos))
        }

        fun toJson(): String = JSONObject()
            .put("schema", "subspace.lua.actor.v1")
            .put("context", context.put("threadCountAtEnd", Thread.activeCount()))
            .put("nativeProvenance", JSONObject(nativeProvenance))
            .put("injectedPolicies", actorPolicies)
            .put("probes", probes)
            .put("operations", operations)
            .put("raceOutcomes", races)
            .put("actorOutcomes", actorOutcomes)
            .put("outcomeCounts", JSONObject(outcomeCounts))
            .toString()
    }

    private class TestGate : ActorGenerationGate {
        private val live = AtomicBoolean(true)

        override fun isLive(): Boolean = live.get()

        override fun <T> commitIfLive(action: () -> T): ActorGateCommit<T> =
            if (live.get()) ActorGateCommit.Success(action()) else ActorGateCommit.Closed

        override suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T> =
            if (live.get()) ActorGateResult.Success(action()) else ActorGateResult.Closed

        override fun isAdmissionStopped(): Boolean = !live.get()
    }

    private companion object {
        const val EVIDENCE_TAG = "LuaActorInstrumentation"
        const val EVIDENCE_BUNDLE_KEY = "LUA_ACTOR_EVIDENCE"
        const val RACE_TIMEOUT_SECONDS = 10L

        fun returningEntrypoint(value: String): String = "function entry() return '$value' end"

        fun yieldingEntrypoint(label: String, body: String): String =
            "function entry() local _, value = subspace.yield_operation('$label'); $body end"

        fun race(
            first: Callable<LuaKernelOutcome>,
            second: Callable<LuaKernelOutcome>,
        ): List<LuaKernelOutcome> {
            val ready = CountDownLatch(2)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                fun submit(call: Callable<LuaKernelOutcome>) = executor.submit(Callable {
                    ready.countDown()
                    check(release.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "race caller did not receive release signal"
                    }
                    call.call()
                })
                val firstFuture = submit(first)
                val secondFuture = submit(second)
                check(ready.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "race callers did not become ready" }
                release.countDown()
                return listOf(
                    firstFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun LuaKernelOutcome.kind(): String = when (this) {
            is LuaKernelOutcome.Created -> "created"
            is LuaKernelOutcome.Completed -> "completed"
            is LuaKernelOutcome.Yielded -> "yielded"
            is LuaKernelOutcome.SyntaxFailure -> "syntax_failure"
            is LuaKernelOutcome.ValidationFailure -> "validation_failure"
            is LuaKernelOutcome.RuntimeFailure -> "runtime_failure"
            is LuaKernelOutcome.MemoryFailure -> "memory_failure"
            is LuaKernelOutcome.Interrupted -> "interrupted"
            is LuaKernelOutcome.Cancelled -> "cancelled"
            is LuaKernelOutcome.InvalidOwnership -> "invalid_ownership"
            is LuaKernelOutcome.Stale -> "stale"
            is LuaKernelOutcome.Snapshot -> "snapshot"
            is LuaKernelOutcome.Closed -> "closed"
        }

        fun LuaKernelOutcome.terminalKind(): String? = when (this) {
            is LuaKernelOutcome.Completed -> "completed"
            is LuaKernelOutcome.Cancelled -> "cancelled"
            else -> null
        }

        fun LuaKernelOutcome.describe(): String = when (this) {
            is LuaKernelOutcome.Created -> "created(state=$stateId, generation=$generation)"
            is LuaKernelOutcome.Completed -> "completed(value=$value)"
            is LuaKernelOutcome.Yielded -> "yielded(label=$value, operationId=$operationId)"
            is LuaKernelOutcome.SyntaxFailure -> "syntax_failure($diagnostic)"
            is LuaKernelOutcome.ValidationFailure -> "validation_failure($diagnostic)"
            is LuaKernelOutcome.RuntimeFailure -> "runtime_failure($diagnostic)"
            is LuaKernelOutcome.MemoryFailure -> "memory_failure($diagnostic, denied=$deniedAllocations)"
            is LuaKernelOutcome.Interrupted -> "interrupted($diagnostic)"
            is LuaKernelOutcome.Cancelled -> "cancelled(operationId=$operationId)"
            is LuaKernelOutcome.InvalidOwnership -> "invalid_ownership($diagnostic)"
            is LuaKernelOutcome.Stale -> "stale($diagnostic)"
            is LuaKernelOutcome.Snapshot -> "snapshot(current=$currentBytes, peak=$peakBytes, denied=$deniedAllocations)"
            is LuaKernelOutcome.Closed -> "closed"
        }

        fun LuaKernelOutcome.toJson(): JSONObject = JSONObject()
            .put("kind", kind())
            .put("stateId", stateId)
            .put("generation", generation)
            .apply {
                when (this@toJson) {
                    is LuaKernelOutcome.Created -> put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaKernelOutcome.Completed -> put("coroutineId", coroutineId).put("value", value).put("nativeElapsedNanos", elapsedNanos).put("currentBytes", currentBytes).put("peakBytes", peakBytes).put("deniedAllocations", deniedAllocations).put("bridgeBytes", bridgeBytes)
                    is LuaKernelOutcome.Yielded -> put("coroutineId", coroutineId).put("operationId", operationId).put("value", value)
                    is LuaKernelOutcome.SyntaxFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.ValidationFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.RuntimeFailure -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.MemoryFailure -> put("diagnostic", diagnostic).put("currentBytes", currentBytes).put("peakBytes", peakBytes).put("deniedAllocations", deniedAllocations).put("bridgeBytes", bridgeBytes)
                    is LuaKernelOutcome.Interrupted -> put("diagnostic", diagnostic).put("nativeElapsedNanos", elapsedNanos)
                    is LuaKernelOutcome.Cancelled -> put("operationId", operationId)
                    is LuaKernelOutcome.InvalidOwnership -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.Stale -> put("diagnostic", diagnostic)
                    is LuaKernelOutcome.Snapshot -> put("currentBytes", currentBytes).put("peakBytes", peakBytes).put("deniedAllocations", deniedAllocations).put("bridgeBytes", bridgeBytes).put("nativeElapsedNanos", elapsedNanos).put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaKernelOutcome.Closed -> Unit
                }
            }

        fun LuaKernelOutcome.Yielded.operationHandle(): LuaOperationHandle = LuaOperationHandle(
            stateHandle = LuaStateHandle(LuaStateId(stateId), LuaStateGeneration(generation)),
            coroutineId = LuaCoroutineId(coroutineId),
            operationId = LuaOperationId(operationId),
        )
    }
}
