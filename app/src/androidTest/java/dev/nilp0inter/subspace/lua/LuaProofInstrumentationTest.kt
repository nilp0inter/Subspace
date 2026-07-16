package dev.nilp0inter.subspace.lua

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only conformance and evidence workload for the internal Lua substrate.
 *
 * The test deliberately constructs [LuaProofBridge] only from this instrumentation
 * entrypoint. It is neither a provider nor part of activity, service, navigation,
 * or ordinary application startup composition. Run it by class name on a physical
 * arm64 device for each APK/native build that is being evaluated:
 *
 * `adb shell am instrument -w -e class dev.nilp0inter.subspace.lua.LuaProofInstrumentationTest \
 *     dev.nilp0inter.subspace.test/androidx.test.runner.AndroidJUnitRunner`
 *
 * Every invocation emits one `LUA_PROOF_EVIDENCE` JSON record through the instrumentation
 * status channel. Correctness gates are assertions; latency and allocation values are
 * observations, never limits.
 */
@RunWith(AndroidJUnit4::class)
class LuaProofInstrumentationTest {
    @Test
    fun proofSuiteExercisesSelectedTopologyAndEmitsDeviceEvidence() {
        val evidence = EvidenceCollector(runContext())
        val failures = mutableListOf<String>()

        try {
            TopologyWorkload(LuaBridgeTopology.JvmOwned, evidence, failures).run()
        } finally {
            // The status channel preserves a full record beyond logcat's per-line limit.
            // The log line identifies the run without duplicating or truncating its JSON evidence.
            val record = evidence.toJson()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString(EVIDENCE_BUNDLE_KEY, record) },
            )
            Log.i(EVIDENCE_TAG, "emitted $EVIDENCE_BUNDLE_KEY (${record.length} bytes)")
        }

        assertTrue(
            "Lua proof correctness gates failed:\n${failures.joinToString("\n")}",
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
            .put("runId", arguments.getString("luaProofRunId") ?: UUID.randomUUID().toString())
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("buildType", buildType)
            .put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            .put("nativeBuildSettings", "cargo-ndk release; minApi=31; packagedAbi=arm64-v8a")
            .put("threadCountAtStart", Thread.activeCount())
    }

    private class TopologyWorkload(
        private val topology: LuaBridgeTopology,
        private val evidence: EvidenceCollector,
        private val failures: MutableList<String>,
    ) {
        private val bridge = LuaProofBridgeFactory.create()
        private val createdHandles = LinkedHashSet<LuaStateHandle>()

        private val defaultConfig = DEFAULT_CONFIG.copy(topology = topology)
        fun run() {
            try {
                sourceOnlyRejectionLeavesAnotherStateUsable()
                independentStateGlobalsAndCloseEffectsStayIsolated()
                protectedRuntimeFailureDoesNotEscapeOrPoisonSurvivor()
                memoryPhaseSnapshotsCoverOneStateLifecycle()
                yieldResumeAndCancellationHaveExactlyOneObservableTerminalKind()
                staleAndForeignHandlesCannotEnterAnotherState()
                closeVersusResumeRaceInvalidatesTheOperation()
                instructionBudgetsInterruptUncooperativeLuaAndLeaveSurvivorUsable()
                allocatorDenialIsAccountedAndContained()
                sameStateConcurrentStartIsSerializedOrRejected()
                multipleStatesExecuteIndependentlyUnderConcurrentCallers()
                hookIntervalsPreserveWorkloadResultAndRecordObservedCost()
            } finally {
                closeAnyLeakedStates()
            }
        }

        /** Binary Lua must be rejected before it can enter the VM, while a separate state survives. */
        private fun sourceOnlyRejectionLeavesAnotherStateUsable() = probe("source_only_rejection") { scope ->
            val rejected = scope.create(defaultConfig)
            val binary = "\u001bLua\u0000\u0019\u0093\r\n\u001a\n"
            val rejection = scope.call("load_binary", mapOf("entrypoint" to "entry")) {
                bridge.load(rejected, binary, "entry")
            }
            scope.expect(rejection is LuaProofOutcome.SyntaxFailure) {
                "binary chunk must return SyntaxFailure, got ${rejection.describe()}"
            }
            scope.close(rejected)

            val survivor = scope.create(defaultConfig)
            scope.expectLoaded(survivor, returningEntrypoint("source-survivor"))
            val completed = scope.call("start_survivor") { bridge.start(survivor) }
            scope.expectCompletedValue(completed, "source-survivor")
            scope.close(survivor)
        }

        /** Equal global names and closing one state must not modify a suspended sibling. */
        private fun independentStateGlobalsAndCloseEffectsStayIsolated() = probe("state_isolation") { scope ->
            val left = scope.create(defaultConfig)
            val right = scope.create(defaultConfig)
            scope.expectLoaded(left, "shared = 'left'; function entry() return shared end")
            scope.expectLoaded(
                right,
                "shared = 'right'; function entry() local _, value = subspace.yield_operation('isolation'); return shared .. ':' .. value end",
            )

            val leftResult = scope.call("start_left") { bridge.start(left) }
            scope.expectCompletedValue(leftResult, "left")
            val yielded = scope.expectYielded(scope.call("start_right") { bridge.start(right) }, "isolation")

            scope.close(left)
            val resumed = scope.call("resume_right_after_left_close") {
                bridge.resume(yielded, success = true, value = "continued")
            }
            scope.expectCompletedValue(resumed, "right:continued")
            scope.close(right)
        }

        /** A protected Lua error must retain diagnostic context and leave a new state executable. */
        private fun protectedRuntimeFailureDoesNotEscapeOrPoisonSurvivor() = probe("protected_error_containment") { scope ->
            val failing = scope.create(defaultConfig)
            scope.expectLoaded(failing, "function entry() error('adversarial-runtime-error') end")
            val outcome = scope.call("start_runtime_failure") { bridge.start(failing) }
            val failure = outcome as? LuaProofOutcome.RuntimeFailure
            scope.expect(failure != null && failure.diagnostic.contains("adversarial-runtime-error")) {
                "protected Lua error must return its diagnostic, got ${outcome.describe()}"
            }
            scope.close(failing)

            val survivor = scope.create(defaultConfig)
            scope.expectLoaded(survivor, returningEntrypoint("survives-runtime-failure"))
            scope.expectCompletedValue(scope.call("start_survivor") { bridge.start(survivor) }, "survives-runtime-failure")
            scope.close(survivor)
        }

        /** Empty, loaded, yielded, terminal, and representative-allocation snapshots retain allocator invariants. */
        private fun memoryPhaseSnapshotsCoverOneStateLifecycle() = probe("memory_phase_snapshots") { scope ->
            val lifecycle = scope.create(defaultConfig)
            val empty = scope.snapshot(lifecycle, "empty")
            scope.expectLoaded(lifecycle, yieldingEntrypoint("memory-phase", "return value"))
            val sourceLoaded = scope.snapshot(lifecycle, "source_loaded")
            val operation = scope.expectYielded(scope.call("start_memory_phase_yield") { bridge.start(lifecycle) }, "memory-phase")
            val coroutineLoaded = scope.snapshot(lifecycle, "coroutine_yielded")
            scope.expectCompletedValue(
                scope.call("resume_memory_phase") { bridge.resume(operation, success = true, value = "terminal") },
                "terminal",
            )
            val terminal = scope.snapshot(lifecycle, "terminal")
            scope.expect(sourceLoaded.peakBytes >= empty.peakBytes) {
                "loading source must not lower the sampled allocation peak"
            }
            scope.expect(coroutineLoaded.peakBytes >= sourceLoaded.peakBytes) {
                "creating a yielded coroutine must not lower the sampled allocation peak"
            }
            scope.expect(terminal.peakBytes >= coroutineLoaded.peakBytes) {
                "terminal snapshot must retain the sampled allocation peak"
            }
            scope.close(lifecycle)

            val allocating = scope.create(defaultConfig)
            scope.expectLoaded(
                allocating,
                "function entry() local values = {}; for i = 1, 10000 do values[i] = i end; return #values end",
            )
            scope.expectCompletedValue(scope.call("start_representative_allocation") { bridge.start(allocating) }, "10000")
            val allocation = scope.snapshot(allocating, "representative_allocation")
            scope.expect(allocation.peakBytes > 0L) {
                "representative allocation must report a positive allocator peak"
            }
            scope.close(allocating)
        }

        /** A yielded operation resumes once; cancellation/resume racing never exposes mixed terminal kinds. */
        private fun yieldResumeAndCancellationHaveExactlyOneObservableTerminalKind() = probe("yield_resume_cancel_race") { scope ->
            val normal = scope.create(defaultConfig)
            scope.expectLoaded(
                normal,
                "hits = 0; function entry() local _, value = subspace.yield_operation('fetch'); hits = hits + 1; return value end",
            )
            val operation = scope.expectYielded(scope.call("start_yield") { bridge.start(normal) }, "fetch")
            val resumed = scope.call("resume_success") { bridge.resume(operation, success = true, value = "result-42") }
            scope.expectCompletedValue(resumed, "result-42")
            val duplicate = scope.call("resume_duplicate") { bridge.resume(operation, success = true, value = "must-not-run") }
            scope.expectCompletedValue(duplicate, "result-42")
            scope.expectLoaded(normal, "function observe() return tostring(hits) end", entrypoint = "observe")
            scope.expectCompletedValue(scope.call("observe_duplicate_effect") { bridge.start(normal) }, "1")
            scope.close(normal)
            val racing = scope.create(defaultConfig)
            scope.expectLoaded(
                racing,
                "hits = 0; function entry() local _, value = subspace.yield_operation('race'); hits = hits + 1; return value end",
            )
            val racingOperation = scope.expectYielded(scope.call("start_race_yield") { bridge.start(racing) }, "race")
            val results = race(
                Callable { bridge.resume(racingOperation, success = true, value = "race-value") },
                Callable { bridge.cancel(racingOperation) },
            )
            scope.recordExternal("resume_cancel_race", results)
            val terminalKinds = results.mapNotNull { it.terminalKind() }.toSet()
            scope.expect(terminalKinds.size == 1 && terminalKinds.single() in setOf("completed", "cancelled")) {
                "resume/cancel race must echo one admitted terminal kind, got ${results.joinToString { it.describe() }}"
            }
            val late = scope.call("resume_after_race") { bridge.resume(racingOperation, success = true, value = "late") }
            scope.expect(late.terminalKind() == terminalKinds.single()) {
                "late completion must echo the admitted terminal result without re-entering Lua, got ${late.describe()}"
            }
            scope.expectLoaded(racing, "function observe() return tostring(hits) end", entrypoint = "observe")
            val expectedHits = if (terminalKinds.single() == "completed") "1" else "0"
            scope.expectCompletedValue(scope.call("observe_race_effect") { bridge.start(racing) }, expectedHits)
            scope.close(racing)
        }

        /** A foreign owner and a stale generation must be rejected while the target state remains executable. */
        private fun staleAndForeignHandlesCannotEnterAnotherState() = probe("adversarial_stale_handles") { scope ->
            val owner = scope.create(defaultConfig)
            val unrelated = scope.create(defaultConfig)
            scope.expectLoaded(owner, yieldingEntrypoint("owned", "return value"))
            scope.expectLoaded(unrelated, returningEntrypoint("foreign-state-survives"))
            val operation = scope.expectYielded(scope.call("start_owned_yield") { bridge.start(owner) }, "owned")

            val foreign = operation.copy(stateHandle = unrelated)
            val foreignOutcome = scope.call("resume_foreign_operation") {
                bridge.resume(foreign, success = true, value = "forbidden")
            }
            scope.expect(foreignOutcome is LuaProofOutcome.InvalidOwnership) {
                "foreign operation must return InvalidOwnership, got ${foreignOutcome.describe()}"
            }

            val stale = operation.copy(
                stateHandle = operation.stateHandle.copy(
                    generation = LuaStateGeneration(operation.stateHandle.generation.value + 1L),
                ),
            )
            val staleOutcome = scope.call("resume_stale_generation") {
                bridge.resume(stale, success = true, value = "forbidden")
            }
            scope.expect(staleOutcome is LuaProofOutcome.Stale) {
                "stale generation must return Stale, got ${staleOutcome.describe()}"
            }

            scope.expectCompletedValue(scope.call("start_unrelated") { bridge.start(unrelated) }, "foreign-state-survives")
            scope.close(owner)
            scope.close(unrelated)
        }

        /** Closing a yielded state wins or follows resumption safely; post-close completion can never execute. */
        private fun closeVersusResumeRaceInvalidatesTheOperation() = probe("close_resume_race") { scope ->
            val state = scope.create(defaultConfig)
            scope.expectLoaded(state, yieldingEntrypoint("close-race", "return value"))
            val operation = scope.expectYielded(scope.call("start_close_race_yield") { bridge.start(state) }, "close-race")
            val pair = race(
                Callable { bridge.resume(operation, success = true, value = "race-value") },
                Callable { bridge.close(state) },
            )
            scope.recordExternal("close_resume_race", pair)
            val resumed = pair[0]
            val closed = pair[1]
            scope.expect(closed is LuaProofOutcome.Closed) {
                "close must return Closed regardless of race winner, got ${closed.describe()}"
            }
            scope.expect(resumed is LuaProofOutcome.Completed || resumed is LuaProofOutcome.Stale || resumed is LuaProofOutcome.Closed) {
                "resume racing close must complete before close or be rejected after close, got ${resumed.describe()}"
            }
            val late = scope.call("resume_after_close") { bridge.resume(operation, success = true, value = "late") }
            scope.expect(late is LuaProofOutcome.Closed || late is LuaProofOutcome.Stale) {
                "post-close completion must be Closed or Stale, got ${late.describe()}"
            }
            scope.close(state)
        }

        /** Pure-Lua infinite loops must be interrupted at each observed hook interval, without harming another state. */
        private fun instructionBudgetsInterruptUncooperativeLuaAndLeaveSurvivorUsable() = probe("interruption_containment") { scope ->
            HOOK_INTERVALS.forEach { interval ->
                val interrupted = scope.create(defaultConfig.copy(hookInterval = interval, instructionBudget = 10_000L))
                scope.expectLoaded(interrupted, "function entry() while true do end end")
                val outcome = scope.call("start_infinite_loop", mapOf("hookInterval" to interval.toString())) {
                    bridge.start(interrupted)
                }
                scope.expect(outcome is LuaProofOutcome.Interrupted) {
                    "infinite Lua loop must be Interrupted at interval=$interval, got ${outcome.describe()}"
                }
                scope.close(interrupted)
            }

            val survivor = scope.create(defaultConfig)
            scope.expectLoaded(survivor, returningEntrypoint("survives-interrupt"))
            scope.expectCompletedValue(scope.call("start_interrupt_survivor") { bridge.start(survivor) }, "survives-interrupt")
            scope.close(survivor)
        }

        /** Allocator denial must surface as memory failure with a denied allocation and leave a separate state usable. */
        private fun allocatorDenialIsAccountedAndContained() = probe("allocator_denial_containment") { scope ->
            val constrained = scope.create(
                defaultConfig.copy(memoryLimitBytes = ALLOCATION_DENIAL_LIMIT_BYTES),
            )
            scope.expectLoaded(
                constrained,
                "function entry() local t = {}; for i = 1, 100000 do t[i] = {i, i * 2} end; return #t end",
            )
            val denial = scope.call("start_allocation_growth", mapOf("limitBytes" to ALLOCATION_DENIAL_LIMIT_BYTES.toString())) {
                bridge.start(constrained)
            }
            val memoryFailure = denial as? LuaProofOutcome.MemoryFailure
            scope.expect(memoryFailure != null && (memoryFailure.deniedAllocations ?: 0L) > 0L) {
                "allocator growth must report MemoryFailure with denied allocations, got ${denial.describe()}"
            }
            val snapshot = scope.snapshot(constrained, "after_allocator_denial")
            scope.expect(snapshot.deniedAllocations > 0L) {
                "allocator snapshot must retain denial count after denial, got ${snapshot.deniedAllocations}"
            }
            scope.close(constrained)

            val survivor = scope.create(defaultConfig)
            scope.expectLoaded(survivor, returningEntrypoint("survives-memory-denial"))
            scope.expectCompletedValue(scope.call("start_memory_survivor") { bridge.start(survivor) }, "survives-memory-denial")
            scope.close(survivor)
        }

        /** Concurrent callers for one loaded state have one admitted start and one visible lifecycle rejection. */
        private fun sameStateConcurrentStartIsSerializedOrRejected() = probe("same_state_concurrent_start") { scope ->
            val state = scope.create(defaultConfig)
            scope.expectLoaded(state, yieldingEntrypoint("single-entry", "return value"))
            val results = race(
                Callable { bridge.start(state) },
                Callable { bridge.start(state) },
            )
            scope.recordExternal("concurrent_start", results)
            val yielded = results.filterIsInstance<LuaProofOutcome.Yielded>()
            val rejected = results.filterIsInstance<LuaProofOutcome.ValidationFailure>()
            scope.expect(yielded.size == 1 && rejected.size == 1) {
                "one state must admit one concurrent start and reject the other, got ${results.joinToString { it.describe() }}"
            }
            val cancelled = scope.call("cancel_admitted_start") { bridge.cancel(yielded.single().operationHandle()) }
            scope.expect(cancelled is LuaProofOutcome.Cancelled) {
                "admitted yielded operation must cancel, got ${cancelled.describe()}"
            }
            scope.close(state)
        }

        /** Several states may execute concurrently, but each retains its own result and allocator snapshot. */
        private fun multipleStatesExecuteIndependentlyUnderConcurrentCallers() = probe("multi_state_concurrency_and_snapshots") { scope ->
            val states = (1..MULTI_STATE_COUNT).map { index ->
                scope.create(defaultConfig).also { state ->
                    scope.expectLoaded(state, returningEntrypoint("state-$index"))
                }
            }
            val executor = Executors.newFixedThreadPool(MULTI_STATE_COUNT)
            try {
                val futures = states.map { state -> executor.submit(Callable { bridge.start(state) }) }
                val outcomes = futures.map { it.get(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                outcomes.forEachIndexed { index, outcome ->
                    scope.recordExternal("start_independent_state_$index", listOf(outcome))
                    scope.expectCompletedValue(outcome, "state-${index + 1}")
                    scope.snapshot(states[index], "terminal_state_$index")
                }
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            states.forEach(scope::close)
        }

        /** Different hook intervals must preserve finite-workload semantics while recording their observed latency. */
        private fun hookIntervalsPreserveWorkloadResultAndRecordObservedCost() = probe("hook_interval_overhead") { scope ->
            HOOK_INTERVALS.forEach { interval ->
                val state = scope.create(defaultConfig.copy(hookInterval = interval, instructionBudget = 1_000_000L))
                scope.expectLoaded(
                    state,
                    "function entry() local sum = 0; for i = 1, 10000 do sum = sum + i end; return tostring(sum) end",
                )
                val completed = scope.call("start_representative_compute", mapOf("hookInterval" to interval.toString())) {
                    bridge.start(state)
                }
                scope.expectCompletedValue(completed, "50005000")
                scope.snapshot(state, "representative_compute_$interval")
                scope.close(state)
            }
        }

        private fun probe(id: String, body: (ProbeScope) -> Unit) {
            val scope = ProbeScope(id, topology, bridge, evidence, createdHandles)
            val started = System.nanoTime()
            try {
                body(scope)
                evidence.recordProbe(id, topology, passed = true, elapsedNanos = System.nanoTime() - started, failure = null)
            } catch (failure: Throwable) {
                val message = "$topology/$id: ${failure.message ?: failure::class.java.name}"
                failures += message
                evidence.recordProbe(id, topology, passed = false, elapsedNanos = System.nanoTime() - started, failure = message)
            }
        }

        private fun closeAnyLeakedStates() {
            createdHandles.toList().forEach { handle ->
                val started = System.nanoTime()
                val outcome = bridge.close(handle)
                evidence.recordOperation(
                    topology = topology,
                    probe = "cleanup",
                    operation = "close_leaked_state",
                    outcome = outcome,
                    elapsedNanos = System.nanoTime() - started,
                    parameters = emptyMap(),
                )
                createdHandles.remove(handle)
            }
        }
    }

    private class ProbeScope(
        private val probe: String,
        private val topology: LuaBridgeTopology,
        private val bridge: LuaProofBridge,
        private val evidence: EvidenceCollector,
        private val createdHandles: MutableSet<LuaStateHandle>,
    ) {
        fun create(config: LuaProofConfig): LuaStateHandle {
            val outcome = call(
                "create",
                mapOf(
                    "memoryLimitBytes" to config.memoryLimitBytes.toString(),
                    "hookInterval" to config.hookInterval.toString(),
                    "instructionBudget" to config.instructionBudget.toString(),
                ),
            ) { bridge.create(config) }
            val created = outcome as? LuaProofOutcome.Created
                ?: throw AssertionError("state creation must succeed, got ${outcome.describe()}")
            expect(created.topology == topology.wireValue) {
                "created state topology must echo ${topology.wireValue}, got ${created.topology}"
            }
            evidence.recordProvenance(created.luaVersion, created.bindingVersion)
            return LuaStateHandle(LuaStateId(created.stateId), LuaStateGeneration(created.generation)).also(createdHandles::add)
        }

        fun expectLoaded(handle: LuaStateHandle, source: String, entrypoint: String = "entry") {
            val outcome = call("load", mapOf("entrypoint" to entrypoint)) { bridge.load(handle, source, entrypoint) }
            expect(outcome is LuaProofOutcome.Completed) {
                "valid text source must load, got ${outcome.describe()}"
            }
        }

        fun expectYielded(outcome: LuaProofOutcome, label: String): LuaOperationHandle {
            val yielded = outcome as? LuaProofOutcome.Yielded
                ?: throw AssertionError("entrypoint must yield '$label', got ${outcome.describe()}")
            expect(yielded.value == label) {
                "yield label must survive bridge crossing: expected $label, got ${yielded.value}"
            }
            return yielded.operationHandle()
        }

        fun expectCompletedValue(outcome: LuaProofOutcome, expected: String) {
            val completed = outcome as? LuaProofOutcome.Completed
                ?: throw AssertionError("entrypoint must complete '$expected', got ${outcome.describe()}")
            expect(completed.value == expected) {
                "completed value must be '$expected', got '${completed.value}'"
            }
        }

        fun snapshot(handle: LuaStateHandle, phase: String): LuaProofOutcome.Snapshot {
            val outcome = call("snapshot", mapOf("phase" to phase)) { bridge.snapshot(handle) }
            val snapshot = outcome as? LuaProofOutcome.Snapshot
                ?: throw AssertionError("snapshot '$phase' must provide allocator evidence, got ${outcome.describe()}")
            expect(snapshot.currentBytes >= 0L && snapshot.peakBytes >= snapshot.currentBytes) {
                "snapshot '$phase' must preserve non-negative current bytes and peak >= current"
            }
            expect(snapshot.deniedAllocations >= 0L && snapshot.bridgeBytes >= 0L) {
                "snapshot '$phase' must separately report non-negative allocator and bridge accounting"
            }
            return snapshot
        }

        fun close(handle: LuaStateHandle) {
            val outcome = call("close") { bridge.close(handle) }
            expect(outcome is LuaProofOutcome.Closed) {
                "close must be idempotent and return Closed, got ${outcome.describe()}"
            }
            createdHandles.remove(handle)
        }

        fun call(
            operation: String,
            parameters: Map<String, String> = emptyMap(),
            invoke: () -> LuaProofOutcome,
        ): LuaProofOutcome {
            val started = System.nanoTime()
            val outcome = invoke()
            evidence.recordOperation(
                topology = topology,
                probe = probe,
                operation = operation,
                outcome = outcome,
                elapsedNanos = System.nanoTime() - started,
                parameters = parameters,
            )
            return outcome
        }

        fun recordExternal(operation: String, outcomes: List<LuaProofOutcome>) {
            outcomes.forEachIndexed { index, outcome ->
                evidence.recordOperation(
                    topology = topology,
                    probe = probe,
                    operation = "$operation[$index]",
                    outcome = outcome,
                    elapsedNanos = null,
                    parameters = mapOf("threadCount" to Thread.activeCount().toString()),
                )
            }
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
        private val outcomeCounts = linkedMapOf<String, Int>()
        private val provenance = linkedMapOf<String, String>()

        fun recordProvenance(luaVersion: String, bindingVersion: String) {
            provenance["luaVersion"] = luaVersion
            provenance["bindingVersion"] = bindingVersion
        }

        fun recordProbe(
            id: String,
            topology: LuaBridgeTopology,
            passed: Boolean,
            elapsedNanos: Long,
            failure: String?,
        ) {
            probes.put(
                JSONObject()
                    .put("id", id)
                    .put("topology", topology.wireValue)
                    .put("passed", passed)
                    .put("elapsedNanos", elapsedNanos)
                    .put("failure", failure),
            )
        }

        fun recordOperation(
            topology: LuaBridgeTopology,
            probe: String,
            operation: String,
            outcome: LuaProofOutcome,
            elapsedNanos: Long?,
            parameters: Map<String, String>,
        ) {
            val kind = outcome.kind()
            outcomeCounts[kind] = (outcomeCounts[kind] ?: 0) + 1
            operations.put(
                JSONObject()
                    .put("topology", topology.wireValue)
                    .put("probe", probe)
                    .put("operation", operation)
                    .put("outcome", outcome.toJson())
                    .put("elapsedNanos", elapsedNanos)
                    .put("parameters", JSONObject(parameters))
                    .put("threadCount", Thread.activeCount()),
            )
        }

        fun toJson(): String = JSONObject()
            .put("schema", "subspace.lua.proof.v1")
            .put("context", context.put("threadCountAtEnd", Thread.activeCount()))
            .put("provenance", JSONObject(provenance))
            .put("probes", probes)
            .put("operations", operations)
            .put("outcomeCounts", JSONObject(outcomeCounts))
            .toString()
    }

    private companion object {
        const val EVIDENCE_TAG = "LuaProofInstrumentation"
        const val EVIDENCE_BUNDLE_KEY = "LUA_PROOF_EVIDENCE"
        const val EXECUTOR_TIMEOUT_SECONDS = 10L
        const val MULTI_STATE_COUNT = 4
        const val ALLOCATION_DENIAL_LIMIT_BYTES = 512L * 1024L
        val HOOK_INTERVALS = listOf(10, 1_000)
        val DEFAULT_CONFIG = LuaProofConfig(
            topology = LuaBridgeTopology.JvmOwned,
            memoryLimitBytes = 8L * 1024L * 1024L,
            hookInterval = 100,
            instructionBudget = 1_000_000L,
        )

        fun returningEntrypoint(value: String): String = "function entry() return '$value' end"

        fun yieldingEntrypoint(label: String, body: String): String =
            "function entry() local _, value = subspace.yield_operation('$label'); $body end"

        fun race(
            first: Callable<LuaProofOutcome>,
            second: Callable<LuaProofOutcome>,
        ): List<LuaProofOutcome> {
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val firstFuture = executor.submit(Callable {
                    ready.countDown()
                    check(start.await(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "first race caller did not receive start signal" }
                    first.call()
                })
                val secondFuture = executor.submit(Callable {
                    ready.countDown()
                    check(start.await(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "second race caller did not receive start signal" }
                    second.call()
                })
                check(ready.await(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "race callers did not become ready" }
                start.countDown()
                return listOf(
                    firstFuture.get(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    secondFuture.get(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } finally {
                start.countDown()
                executor.shutdownNow()
                executor.awaitTermination(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun LuaProofOutcome.kind(): String = when (this) {
            is LuaProofOutcome.Created -> "created"
            is LuaProofOutcome.Completed -> "completed"
            is LuaProofOutcome.Yielded -> "yielded"
            is LuaProofOutcome.SyntaxFailure -> "syntax_failure"
            is LuaProofOutcome.ValidationFailure -> "validation_failure"
            is LuaProofOutcome.RuntimeFailure -> "runtime_failure"
            is LuaProofOutcome.MemoryFailure -> "memory_failure"
            is LuaProofOutcome.Interrupted -> "interrupted"
            is LuaProofOutcome.Cancelled -> "cancelled"
            is LuaProofOutcome.InvalidOwnership -> "invalid_ownership"
            is LuaProofOutcome.Stale -> "stale"
            is LuaProofOutcome.Snapshot -> "snapshot"
            is LuaProofOutcome.Closed -> "closed"
        }

        fun LuaProofOutcome.terminalKind(): String? = when (this) {
            is LuaProofOutcome.Completed -> "completed"
            is LuaProofOutcome.Cancelled -> "cancelled"
            else -> null
        }

        fun LuaProofOutcome.describe(): String = when (this) {
            is LuaProofOutcome.Completed -> "completed(value=$value)"
            is LuaProofOutcome.Yielded -> "yielded(label=$value, operationId=$operationId)"
            is LuaProofOutcome.SyntaxFailure -> "syntax_failure($diagnostic)"
            is LuaProofOutcome.ValidationFailure -> "validation_failure($diagnostic)"
            is LuaProofOutcome.RuntimeFailure -> "runtime_failure($diagnostic)"
            is LuaProofOutcome.MemoryFailure -> "memory_failure($diagnostic, denied=$deniedAllocations)"
            is LuaProofOutcome.Interrupted -> "interrupted($diagnostic)"
            is LuaProofOutcome.Cancelled -> "cancelled(operationId=$operationId)"
            is LuaProofOutcome.InvalidOwnership -> "invalid_ownership($diagnostic)"
            is LuaProofOutcome.Stale -> "stale($diagnostic)"
            is LuaProofOutcome.Snapshot -> "snapshot(current=$currentBytes, peak=$peakBytes, denied=$deniedAllocations)"
            is LuaProofOutcome.Closed -> "closed"
            is LuaProofOutcome.Created -> "created(topology=$topology)"
        }

        fun LuaProofOutcome.toJson(): JSONObject = JSONObject()
            .put("kind", kind())
            .put("stateId", stateId)
            .put("generation", generation)
            .apply {
                when (this@toJson) {
                    is LuaProofOutcome.Created -> put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaProofOutcome.Completed -> put("coroutineId", coroutineId).put("value", value).put("nativeElapsedNanos", elapsedNanos)
                    is LuaProofOutcome.Yielded -> put("coroutineId", coroutineId).put("operationId", operationId).put("value", value)
                    is LuaProofOutcome.SyntaxFailure -> put("diagnostic", diagnostic)
                    is LuaProofOutcome.ValidationFailure -> put("diagnostic", diagnostic)
                    is LuaProofOutcome.RuntimeFailure -> put("diagnostic", diagnostic)
                    is LuaProofOutcome.MemoryFailure -> put("diagnostic", diagnostic).put("currentBytes", currentBytes).put("peakBytes", peakBytes).put("deniedAllocations", deniedAllocations).put("bridgeBytes", bridgeBytes)
                    is LuaProofOutcome.Interrupted -> put("diagnostic", diagnostic).put("nativeElapsedNanos", elapsedNanos)
                    is LuaProofOutcome.Cancelled -> put("operationId", operationId)
                    is LuaProofOutcome.InvalidOwnership -> put("diagnostic", diagnostic)
                    is LuaProofOutcome.Stale -> put("diagnostic", diagnostic)
                    is LuaProofOutcome.Snapshot -> put("currentBytes", currentBytes).put("peakBytes", peakBytes).put("deniedAllocations", deniedAllocations).put("bridgeBytes", bridgeBytes).put("nativeElapsedNanos", elapsedNanos).put("luaVersion", luaVersion).put("bindingVersion", bindingVersion).put("topology", topology)
                    is LuaProofOutcome.Closed -> Unit
                }
            }

        fun LuaProofOutcome.Yielded.operationHandle(): LuaOperationHandle = LuaOperationHandle(
            stateHandle = LuaStateHandle(LuaStateId(stateId), LuaStateGeneration(generation)),
            coroutineId = LuaCoroutineId(coroutineId),
            operationId = LuaOperationId(operationId),
        )
    }
}
