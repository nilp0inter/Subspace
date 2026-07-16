use std::sync::{Arc, Barrier};
use std::thread;

use subspace_lua_proof::{
    Generation, OperationId, Outcome, OutcomeKind, StateEngine, Topology, BINDING_VERSION,
    LUA_VERSION,
};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;
const STRESS_CYCLES: usize = 24;

fn each_topology(mut test: impl FnMut(Topology)) {
    test(Topology::JvmOwned);
}

fn engine(topology: Topology) -> StateEngine {
    StateEngine::new(topology, MEMORY_LIMIT, HOOK_INTERVAL, INSTRUCTION_BUDGET)
        .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()))
}

fn assert_kind(outcome: &Outcome, expected: OutcomeKind) {
    assert_eq!(outcome.kind(), expected, "outcome: {:?}", outcome.to_json());
}

fn string_field(outcome: &Outcome, field: &str) -> String {
    outcome
        .to_json()
        .get(field)
        .and_then(|value| value.as_str())
        .unwrap_or_else(|| panic!("missing string field {field}: {:?}", outcome.to_json()))
        .to_owned()
}

fn i64_field(outcome: &Outcome, field: &str) -> i64 {
    outcome
        .to_json()
        .get(field)
        .and_then(|value| value.as_i64())
        .unwrap_or_else(|| panic!("missing integer field {field}: {:?}", outcome.to_json()))
}

fn u64_field(outcome: &Outcome, field: &str) -> u64 {
    outcome
        .to_json()
        .get(field)
        .and_then(|value| value.as_u64())
        .unwrap_or_else(|| panic!("missing unsigned field {field}: {:?}", outcome.to_json()))
}

fn elapsed_nanos(outcome: &Outcome) -> u64 {
    u64_field(outcome, "elapsedNanos")
}

fn load_and_start(engine: &StateEngine, source: &str, entrypoint: &str) -> Outcome {
    let generation = engine.handle().generation;
    let loaded = engine.load(generation, source, entrypoint);
    assert_kind(&loaded, OutcomeKind::Completed);
    engine.start(generation)
}

fn yielded_operation(engine: &StateEngine, source: &str) -> (Generation, OperationId) {
    let generation = engine.handle().generation;
    let loaded = engine.load(generation, source, "main");
    assert_kind(&loaded, OutcomeKind::Completed);
    let started = engine.start(generation);
    assert_kind(&started, OutcomeKind::Yielded);
    (generation, i64_field(&started, "operationId"))
}

const YIELDING_COUNTER: &str = r#"
hits = 0
function main()
  local success, value = subspace.yield_operation("external-work")
  hits = hits + 1
  if success then return "success:" .. value end
  return "failure:" .. value
end
function observe() return tostring(hits) end
"#;

#[test]
fn states_isolate_globals_accounting_and_close_effects() {
    each_topology(|topology| {
        let left = engine(topology);
        let right = engine(topology);
        let left_handle = left.handle();
        let right_handle = right.handle();

        assert_kind(
            &right.load(
                right_handle.generation,
                r#"
                side = "right"
                function main() return side end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        let right_result = right.start(right_handle.generation);
        assert_kind(&right_result, OutcomeKind::Completed);
        assert_eq!(string_field(&right_result, "value"), "right");
        let before_right = right.snapshot(right_handle.generation);
        assert_kind(&before_right, OutcomeKind::Completed);

        assert_kind(
            &left.load(
                left_handle.generation,
                r#"
                local allocated = {}
                for i = 1, 2000 do allocated[i] = string.rep("l", 32) end
                side = "left"
                function main() return side end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        let left_result = left.start(left_handle.generation);
        assert_kind(&left_result, OutcomeKind::Completed);
        assert_eq!(string_field(&left_result, "value"), "left");

        let after_right = right.snapshot(right_handle.generation);
        assert_kind(&after_right, OutcomeKind::Completed);
        assert_eq!(
            u64_field(&after_right, "currentBytes"),
            u64_field(&before_right, "currentBytes"),
            "allocations in one state changed another state's live Lua accounting"
        );
        assert_eq!(
            u64_field(&after_right, "peakBytes"),
            u64_field(&before_right, "peakBytes"),
            "allocations in one state changed another state's peak Lua accounting"
        );

        let closed = left.close(left_handle.generation);
        assert_kind(&closed, OutcomeKind::Closed);
        let rejected_left = left.snapshot(left_handle.generation);
        assert!(
            matches!(rejected_left.kind(), OutcomeKind::Stale | OutcomeKind::Closed),
            "closed state accepted a snapshot: {:?}",
            rejected_left.to_json()
        );

        let right_probe = right.load(
            right_handle.generation,
            "function surviving() return side end",
            "surviving",
        );
        assert_kind(&right_probe, OutcomeKind::Completed);
        let right_after_close = right.start(right_handle.generation);
        assert_kind(&right_after_close, OutcomeKind::Completed);
        assert_eq!(string_field(&right_after_close, "value"), "right");
        assert_kind(&right.close(right_handle.generation), OutcomeKind::Closed);
    });
}

#[test]
fn states_keep_module_caches_and_suspended_coroutines_independent() {
    each_topology(|topology| {
        let left = engine(topology);
        let right = engine(topology);
        let left_handle = left.handle();
        let right_handle = right.handle();

        assert_kind(
            &left.load(
                left_handle.generation,
                r#"
                shared = "left"
                subspace.module_put("shared-module", "left-module")
                function main()
                  local _, continuation = subspace.yield_operation("left-operation")
                  return shared .. ":" .. subspace.module_get("shared-module") .. ":" .. continuation
                end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        assert_kind(
            &right.load(
                right_handle.generation,
                r#"
                shared = "right"
                subspace.module_put("shared-module", "right-module")
                function main()
                  local _, continuation = subspace.yield_operation("right-operation")
                  return shared .. ":" .. subspace.module_get("shared-module") .. ":" .. continuation
                end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );

        let left_yielded = left.start(left_handle.generation);
        let right_yielded = right.start(right_handle.generation);
        assert_kind(&left_yielded, OutcomeKind::Yielded);
        assert_kind(&right_yielded, OutcomeKind::Yielded);
        let left_operation = i64_field(&left_yielded, "operationId");
        let right_operation = i64_field(&right_yielded, "operationId");

        assert_kind(
            &left.resume(left_handle.generation, right_operation, true, "forbidden"),
            OutcomeKind::InvalidOwnership,
        );
        assert_kind(&left.close(left_handle.generation), OutcomeKind::Closed);

        let right_resumed = right.resume(right_handle.generation, right_operation, true, "continued");
        assert_kind(&right_resumed, OutcomeKind::Completed);
        assert_eq!(
            string_field(&right_resumed, "value"),
            "right:right-module:continued",
            "closing a sibling state or submitting its coroutine operation must not change this state's module cache or coroutine"
        );
        let left_late = left.resume(left_handle.generation, left_operation, true, "late");
        assert!(
            matches!(left_late.kind(), OutcomeKind::Stale | OutcomeKind::Closed),
            "a closed coroutine accepted continuation: {:?}",
            left_late.to_json()
        );
        assert_kind(&right.close(right_handle.generation), OutcomeKind::Closed);
    });
}

#[test]
fn snapshots_publish_identity_topology_versions_and_memory_invariants() {
    each_topology(|topology| {
        let state = engine(topology);
        let handle = state.handle();
        let snapshot = state.snapshot(handle.generation);
        assert_kind(&snapshot, OutcomeKind::Completed);
        assert_eq!(i64_field(&snapshot, "stateId"), handle.state_id);
        assert_eq!(i64_field(&snapshot, "generation"), handle.generation);
        assert_eq!(string_field(&snapshot, "topology"), topology.as_str());
        assert_eq!(string_field(&snapshot, "luaVersion"), LUA_VERSION);
        assert_eq!(string_field(&snapshot, "bindingVersion"), BINDING_VERSION);
        assert!(
            u64_field(&snapshot, "currentBytes") <= u64_field(&snapshot, "peakBytes"),
            "live Lua bytes cannot exceed sampled peak"
        );
        let elapsed = snapshot.to_json().get("elapsedNanos").and_then(|value| value.as_u64());
        assert!(elapsed.is_some(), "snapshot omitted elapsedNanos evidence: {:?}", snapshot.to_json());
        assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
    });
}

#[test]
fn malformed_source_and_invalid_entrypoint_are_normalized_and_closable() {
    each_topology(|topology| {
        for (source, entrypoint, expected) in [
            ("function main( return 1 end", "main", OutcomeKind::SyntaxFailure),
            ("\u{1b}Lua\0binary-chunk", "main", OutcomeKind::SyntaxFailure),
            ("not_an_entrypoint = 3", "not_an_entrypoint", OutcomeKind::ValidationFailure),
            ("function other() return 'ok' end", "missing", OutcomeKind::ValidationFailure),
        ] {
            let state = engine(topology);
            let handle = state.handle();
            let outcome = state.load(handle.generation, source, entrypoint);
            assert_kind(&outcome, expected);
            assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
        }
    });
}

#[test]
fn string_nonstrings_and_nested_lua_errors_are_normalized_and_recoverable() {
    each_topology(|topology| {
        for source in [
            "function main() error('string-regression') end",
            "function main() error({ kind = 'structured-regression' }) end",
            r#"
            function main()
              local ok, err = pcall(function() error("nested-regression") end)
              if ok then error("protected call unexpectedly succeeded") end
              error("outer-regression:" .. err)
            end
            "#,
        ] {
            let state = engine(topology);
            let handle = state.handle();
            assert_kind(&state.load(handle.generation, source, "main"), OutcomeKind::Completed);
            let failed = state.start(handle.generation);
            assert_kind(&failed, OutcomeKind::RuntimeFailure);

            if source.contains("nested-regression") {
                assert!(
                    string_field(&failed, "diagnostic").contains("nested-regression"),
                    "nested protected error lost its diagnostic context: {:?}",
                    failed.to_json()
                );
            }

            assert_kind(
                &state.load(handle.generation, "function healthy() return 'recovered' end", "healthy"),
                OutcomeKind::Completed,
            );
            let recovered = state.start(handle.generation);
            assert_kind(&recovered, OutcomeKind::Completed);
            assert_eq!(string_field(&recovered, "value"), "recovered");
            assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
        }
    });
}

#[test]
fn foreign_unknown_stale_and_closed_handles_cannot_resume_lua() {
    each_topology(|topology| {
        let owner = engine(topology);
        let other = engine(topology);
        let (owner_generation, owner_operation) = yielded_operation(&owner, YIELDING_COUNTER);
        let (other_generation, other_operation) = yielded_operation(&other, YIELDING_COUNTER);
        assert_ne!(
            owner_operation, other_operation,
            "operation identifiers must remain opaque and unambiguous across state ownership domains"
        );

        let foreign = other.resume(other_generation, owner_operation, true, "should-not-run");
        assert_kind(&foreign, OutcomeKind::InvalidOwnership);
        let own_unknown = owner.resume(owner_generation, other_operation, true, "should-not-run");
        assert_kind(&own_unknown, OutcomeKind::InvalidOwnership);

        let other_completed = other.resume(other_generation, other_operation, true, "other");
        assert_kind(&other_completed, OutcomeKind::Completed);
        assert_eq!(string_field(&other_completed, "value"), "success:other");

        let completed = owner.resume(owner_generation, owner_operation, true, "accepted");
        assert_kind(&completed, OutcomeKind::Completed);
        assert_eq!(string_field(&completed, "value"), "success:accepted");

        let duplicate = owner.resume(owner_generation, owner_operation, true, "must-not-run");
        assert_kind(&duplicate, OutcomeKind::Completed);
        assert_kind(
            &owner.load(
                owner_generation,
                "function observe() return tostring(hits) end",
                "observe",
            ),
            OutcomeKind::Completed,
        );
        let observed = owner.start(owner_generation);
        assert_kind(&observed, OutcomeKind::Completed);
        assert_eq!(string_field(&observed, "value"), "1");

        let owner_handle = owner.handle();
        assert_kind(&owner.close(owner_handle.generation), OutcomeKind::Closed);
        let late = owner.resume(owner_generation, owner_operation, true, "late");
        assert!(
            matches!(late.kind(), OutcomeKind::Stale | OutcomeKind::Closed),
            "closed state admitted a late completion: {:?}",
            late.to_json()
        );
        assert_kind(&owner.close(owner_handle.generation), OutcomeKind::Closed);
        assert_kind(&other.close(other.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn success_failure_and_cancel_resumption_have_exactly_once_effects() {
    each_topology(|topology| {
        let success_state = engine(topology);
        let (generation, operation) = yielded_operation(&success_state, YIELDING_COUNTER);
        let success = success_state.resume(generation, operation, true, "payload");
        assert_kind(&success, OutcomeKind::Completed);
        assert_eq!(string_field(&success, "value"), "success:payload");
        assert_kind(&success_state.close(success_state.handle().generation), OutcomeKind::Closed);

        let failure_state = engine(topology);
        let (generation, operation) = yielded_operation(&failure_state, YIELDING_COUNTER);
        let failure = failure_state.resume(generation, operation, false, "denied");
        assert_kind(&failure, OutcomeKind::Completed);
        assert_eq!(string_field(&failure, "value"), "failure:denied");
        assert_kind(&failure_state.close(failure_state.handle().generation), OutcomeKind::Closed);

        let cancelled_state = engine(topology);
        let (generation, operation) = yielded_operation(&cancelled_state, YIELDING_COUNTER);
        let cancelled = cancelled_state.cancel(generation, operation);
        assert_kind(&cancelled, OutcomeKind::Cancelled);
        let duplicate_cancel = cancelled_state.cancel(generation, operation);
        assert_kind(&duplicate_cancel, OutcomeKind::Cancelled);
        assert_kind(
            &cancelled_state.load(generation, "function observe() return tostring(hits) end", "observe"),
            OutcomeKind::Completed,
        );
        let observed = cancelled_state.start(generation);
        assert_kind(&observed, OutcomeKind::Completed);
        assert_eq!(string_field(&observed, "value"), "0");
        assert_kind(&cancelled_state.close(cancelled_state.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn cancellation_and_close_races_admit_one_terminal_result() {
    each_topology(|topology| {
        let state = Arc::new(engine(topology));
        let (generation, operation) = yielded_operation(&state, YIELDING_COUNTER);
        let gate = Arc::new(Barrier::new(3));
        let completion_state = Arc::clone(&state);
        let completion_gate = Arc::clone(&gate);
        let completion = thread::spawn(move || {
            completion_gate.wait();
            completion_state.resume(generation, operation, true, "race")
        });
        let cancellation_state = Arc::clone(&state);
        let cancellation_gate = Arc::clone(&gate);
        let cancellation = thread::spawn(move || {
            cancellation_gate.wait();
            cancellation_state.cancel(generation, operation)
        });
        gate.wait();
        let completion = completion.join().expect("completion caller panicked");
        let cancellation = cancellation.join().expect("cancellation caller panicked");
        let outcomes = [completion.kind(), cancellation.kind()];
        assert!(
            outcomes.iter().all(|kind| matches!(kind, OutcomeKind::Completed | OutcomeKind::Cancelled)),
            "race returned a non-terminal outcome: {outcomes:?}"
        );
        assert!(
            outcomes.iter().all(|kind| kind == &outcomes[0]),
            "the losing terminal request did not report the accepted terminal state: {outcomes:?}"
        );

        assert_kind(
            &state.load(generation, "function observe() return tostring(hits) end", "observe"),
            OutcomeKind::Completed,
        );
        let observed = state.start(generation);
        assert_kind(&observed, OutcomeKind::Completed);
        let expected_hits = if outcomes[0] == OutcomeKind::Completed { "1" } else { "0" };
        assert_eq!(string_field(&observed, "value"), expected_hits);
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);

        let raced = Arc::new(engine(topology));
        let (generation, operation) = yielded_operation(&raced, YIELDING_COUNTER);
        let handle = raced.handle();
        let gate = Arc::new(Barrier::new(3));
        let completion_state = Arc::clone(&raced);
        let completion_gate = Arc::clone(&gate);
        let completion = thread::spawn(move || {
            completion_gate.wait();
            completion_state.resume(generation, operation, true, "late")
        });
        let close_state = Arc::clone(&raced);
        let close_gate = Arc::clone(&gate);
        let close = thread::spawn(move || {
            close_gate.wait();
            close_state.close(handle.generation)
        });
        gate.wait();
        let completion = completion.join().expect("completion caller panicked");
        let close = close.join().expect("close caller panicked");
        assert_kind(&close, OutcomeKind::Closed);
        assert!(
            matches!(completion.kind(), OutcomeKind::Completed | OutcomeKind::Closed | OutcomeKind::Stale),
            "completion raced with close unexpectedly: {:?}",
            completion.to_json()
        );
        assert_kind(&raced.snapshot(generation), OutcomeKind::Stale);
    });
}

#[test]
fn repeated_lifecycle_cycles_leave_old_handles_unusable() {
    each_topology(|topology| {
        for cycle in 0..STRESS_CYCLES {
            let state = engine(topology);
            let (generation, operation) = yielded_operation(&state, YIELDING_COUNTER);
            let terminal = if cycle % 2 == 0 {
                state.resume(generation, operation, true, "cycle")
            } else {
                state.cancel(generation, operation)
            };
            assert!(
                matches!(terminal.kind(), OutcomeKind::Completed | OutcomeKind::Cancelled),
                "cycle {cycle} failed terminal admission: {:?}",
                terminal.to_json()
            );
            let snapshot = state.snapshot(generation);
            assert_kind(&snapshot, OutcomeKind::Completed);
            assert!(
                u64_field(&snapshot, "currentBytes") <= u64_field(&snapshot, "peakBytes"),
                "cycle {cycle} corrupted accounting"
            );
            let handle = state.handle();
            assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
            assert_kind(&state.resume(generation, operation, true, "late"), OutcomeKind::Stale);
        }
    });
}

#[test]
fn pure_lua_interruptions_are_state_local_and_compute_still_completes() {
    each_topology(|topology| {
        for workload in [
            "function main() while true do end end",
            "local function recurse(n) return recurse(n + 1) end function main() return recurse(0) end",
            r#"
            local proxy = setmetatable({}, { __index = function() return 1 end })
            function main() while true do local value = proxy.missing end end
            "#,
        ] {
            let interrupted = StateEngine::new(topology, MEMORY_LIMIT, HOOK_INTERVAL, 1_000)
                .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()));
            let handle = interrupted.handle();
            assert_kind(&interrupted.load(handle.generation, workload, "main"), OutcomeKind::Completed);
            let outcome = interrupted.start(handle.generation);
            assert_kind(&outcome, OutcomeKind::Interrupted);
            assert!(
                outcome.to_json().get("elapsedNanos").and_then(|value| value.as_u64()).is_some(),
                "interruption omitted elapsed-time evidence: {:?}",
                outcome.to_json()
            );
            assert_kind(&interrupted.close(handle.generation), OutcomeKind::Closed);

            let unaffected = engine(topology);
            let healthy = load_and_start(&unaffected, "function main() return 'unaffected' end", "main");
            assert_kind(&healthy, OutcomeKind::Completed);
            assert_eq!(string_field(&healthy, "value"), "unaffected");
            assert_kind(&unaffected.close(unaffected.handle().generation), OutcomeKind::Closed);
        }

        let compute = StateEngine::new(topology, MEMORY_LIMIT, HOOK_INTERVAL, 100_000)
            .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()));
        let computed = load_and_start(
            &compute,
            "function main() local sum = 0 for i = 1, 500 do sum = sum + i end return sum end",
            "main",
        );
        assert_kind(&computed, OutcomeKind::Completed);
        assert_eq!(string_field(&computed, "value"), "125250");
        assert_kind(&compute.close(compute.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn interrupting_a_suspended_operation_suppresses_its_later_lua_and_native_effects() {
    each_topology(|topology| {
        let interrupted = engine(topology);
        let generation = interrupted.handle().generation;
        assert_kind(
            &interrupted.load(
                generation,
                r#"
                native_effects = 0
                function main()
                  local _, value = subspace.yield_operation("interrupt-me")
                  native_effects = native_effects + 1
                  return subspace.host_hash(value)
                end
                function observe() return tostring(native_effects) end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        let yielded = interrupted.start(generation);
        assert_kind(&yielded, OutcomeKind::Yielded);
        let operation = i64_field(&yielded, "operationId");
        assert_kind(&interrupted.interrupt(generation), OutcomeKind::Interrupted);

        let survivor = engine(topology);
        let survivor_outcome = load_and_start(&survivor, "function main() return 'survives-suspended-interrupt' end", "main");
        assert_kind(&survivor_outcome, OutcomeKind::Completed);
        assert_eq!(string_field(&survivor_outcome, "value"), "survives-suspended-interrupt");

        let late = interrupted.resume(generation, operation, true, "foobar");
        assert!(
            matches!(late.kind(), OutcomeKind::RuntimeFailure | OutcomeKind::InvalidOwnership),
            "an interrupted suspended operation resumed instead of reporting its terminal rejection: {:?}",
            late.to_json()
        );
        assert_kind(
            &interrupted.load(generation, "function observe() return tostring(native_effects) end", "observe"),
            OutcomeKind::Completed,
        );
        let observed = interrupted.start(generation);
        assert_kind(&observed, OutcomeKind::Completed);
        assert_eq!(
            string_field(&observed, "value"),
            "0",
            "the interrupted continuation entered Lua and produced its later effect"
        );
        assert_kind(&interrupted.close(interrupted.handle().generation), OutcomeKind::Closed);
        assert_kind(&survivor.close(survivor.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn yielding_host_boundary_returns_before_external_completion() {
    each_topology(|topology| {
        let state = engine(topology);
        let generation = state.handle().generation;
        assert_kind(&state.load(generation, YIELDING_COUNTER, "main"), OutcomeKind::Completed);
        let yielded = state.start(generation);
        assert_kind(&yielded, OutcomeKind::Yielded);
        let operation = i64_field(&yielded, "operationId");
        let yield_elapsed = elapsed_nanos(&yielded);
        let resumed = state.resume(generation, operation, true, "completed-later");
        assert_kind(&resumed, OutcomeKind::Completed);
        assert_eq!(string_field(&resumed, "value"), "success:completed-later");
        assert!(
            elapsed_nanos(&resumed) >= yield_elapsed,
            "resume telemetry preceded the yielded operation: yield={yield_elapsed}, resume={:?}",
            resumed.to_json()
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn bounded_native_host_completes_and_blocking_candidate_rejects_before_work() {
    each_topology(|topology| {
        let state = engine(topology);
        let generation = state.handle().generation;
        assert_kind(
            &state.load(
                generation,
                r#"
                function hash() return subspace.host_hash("foobar") end
                function blocking()
                  local success, value = subspace.host_call("network")
                  return tostring(success) .. ":" .. value
                end
                "#,
                "hash",
            ),
            OutcomeKind::Completed,
        );
        let hash = state.start(generation);
        assert_kind(&hash, OutcomeKind::Completed);
        assert_eq!(
            string_field(&hash, "value"),
            "bf9cf968",
            "the bounded native host must return the FNV-1a value without yielding"
        );

        assert_kind(
            &state.load(generation, "function blocking() local success, value = subspace.host_call('network'); return tostring(success) .. ':' .. value end", "blocking"),
            OutcomeKind::Completed,
        );
        let rejected = state.start(generation);
        assert_kind(&rejected, OutcomeKind::Completed);
        assert_eq!(
            string_field(&rejected, "value"),
            "false:rejected:network",
            "a potentially blocking host operation must reject before external work rather than yield or run it"
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    });
}

#[test]
fn close_reports_terminal_lua_bytes_without_folding_in_bridge_bytes() {
    each_topology(|topology| {
        let state = engine(topology);
        let handle = state.handle();
        assert_kind(
            &state.load(
                handle.generation,
                "allocated = string.rep('x', 16384); function main() return #allocated end",
                "main",
            ),
            OutcomeKind::Completed,
        );
        let completed = state.start(handle.generation);
        assert_kind(&completed, OutcomeKind::Completed);
        assert_eq!(string_field(&completed, "value"), "16384");
        let before_close = state.snapshot(handle.generation);
        assert_kind(&before_close, OutcomeKind::Completed);
        let closed = state.close(handle.generation);
        assert_kind(&closed, OutcomeKind::Closed);
        assert_eq!(
            u64_field(&closed, "currentBytes"),
            0,
            "closing must report no live Lua-owned bytes after dropping the VM"
        );
        assert_eq!(
            u64_field(&closed, "peakBytes"),
            u64_field(&before_close, "peakBytes"),
            "closing must preserve the recorded Lua allocation peak"
        );
        assert_eq!(
            u64_field(&closed, "bridgeBytes"),
            u64_field(&before_close, "bridgeBytes"),
            "closing must retain separately attributed bridge allocations"
        );
        assert!(
            u64_field(&closed, "bridgeBytes") > 0,
            "loaded bridge source must remain separately observable at terminal close"
        );
    });
}

// Binding constraint: mlua provides deterministic denial at VM construction and through a
// per-state memory limit, not a phase-selective allocator callback. Bootstrap, GC, hook
// installation, and bridge/JSON allocation therefore have no supported deterministic injection
// point and are intentionally not represented as recoverable Lua-allocation phases here.
#[test]
fn allocator_denial_is_normalized_at_every_deterministic_mlua_phase() {
    each_topology(|topology| {
        let creation = match StateEngine::new(topology, 1_024, HOOK_INTERVAL, INSTRUCTION_BUDGET) {
            Ok(_) => panic!("a 1 KiB Lua allocator limit must deny VM construction"),
            Err(outcome) => outcome,
        };
        assert_kind(&creation, OutcomeKind::MemoryFailure);

        let constrained = StateEngine::new(topology, 256 * 1024, HOOK_INTERVAL, INSTRUCTION_BUDGET)
            .unwrap_or_else(|outcome| panic!("constrained creation failed: {:?}", outcome.to_json()));
        let constrained_handle = constrained.handle();
        let peer = engine(topology);
        let peer_handle = peer.handle();
        let peer_before = peer.snapshot(peer_handle.generation);
        assert_kind(&peer_before, OutcomeKind::Completed);

        let oversized_source = format!("function main() return '{}' end", "x".repeat(1_000_000));
        let load_denied = constrained.load(constrained_handle.generation, &oversized_source, "main");
        assert_kind(&load_denied, OutcomeKind::MemoryFailure);
        assert!(
            u64_field(&load_denied, "deniedAllocations") >= 1,
            "source loading denial did not record a denied allocation: {:?}",
            load_denied.to_json()
        );
        assert_kind(&constrained.close(constrained_handle.generation), OutcomeKind::Closed);

        let coroutine = engine(topology);
        let coroutine_handle = coroutine.handle();
        assert_kind(
            &coroutine.load(coroutine_handle.generation, "function main() return 'never-created' end", "main"),
            OutcomeKind::Completed,
        );
        let coroutine_before = coroutine.snapshot(coroutine_handle.generation);
        assert_kind(&coroutine_before, OutcomeKind::Completed);
        assert_kind(
            &coroutine.lower_memory_limit(
                coroutine_handle.generation,
                1,
            ),
            OutcomeKind::Completed,
        );
        let coroutine_denied = coroutine.start(coroutine_handle.generation);
        assert_kind(&coroutine_denied, OutcomeKind::MemoryFailure);
        assert!(
            u64_field(&coroutine_denied, "deniedAllocations") >= 1,
            "coroutine creation denial did not record a denied allocation: {:?}",
            coroutine_denied.to_json()
        );
        assert_kind(&coroutine.close(coroutine_handle.generation), OutcomeKind::Closed);

        let table_growth = engine(topology);
        let table_handle = table_growth.handle();
        assert_kind(
            &table_growth.load(
                table_handle.generation,
                r#"
                function main()
                  subspace.yield_operation("grow-table")
                  local values = {}
                  for i = 1, 100000 do values[i] = i end
                  return #values
                end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        let table_yielded = table_growth.start(table_handle.generation);
        assert_kind(&table_yielded, OutcomeKind::Yielded);
        let table_before = table_growth.snapshot(table_handle.generation);
        assert_kind(&table_before, OutcomeKind::Completed);
        assert_kind(
            &table_growth.lower_memory_limit(
                table_handle.generation,
                u64_field(&table_before, "currentBytes") + 4 * 1024,
            ),
            OutcomeKind::Completed,
        );
        let table_denied = table_growth.resume(
            table_handle.generation,
            i64_field(&table_yielded, "operationId"),
            true,
            "",
        );
        assert_kind(&table_denied, OutcomeKind::MemoryFailure);
        assert!(
            u64_field(&table_denied, "deniedAllocations") >= 1,
            "table-growth denial did not record a denied allocation: {:?}",
            table_denied.to_json()
        );
        assert_kind(&table_growth.close(table_handle.generation), OutcomeKind::Closed);

        let error_formatting = engine(topology);
        let error_handle = error_formatting.handle();
        assert_kind(
            &error_formatting.load(
                error_handle.generation,
                r#"
                function main()
                  subspace.yield_operation("format-error")
                  error("formatted:" .. string.rep("x", 65536))
                end
                "#,
                "main",
            ),
            OutcomeKind::Completed,
        );
        let formatting_yielded = error_formatting.start(error_handle.generation);
        assert_kind(&formatting_yielded, OutcomeKind::Yielded);
        let formatting_before = error_formatting.snapshot(error_handle.generation);
        assert_kind(&formatting_before, OutcomeKind::Completed);
        assert_kind(
            &error_formatting.lower_memory_limit(
                error_handle.generation,
                u64_field(&formatting_before, "currentBytes") + 4 * 1024,
            ),
            OutcomeKind::Completed,
        );
        let formatting_denied = error_formatting.resume(
            error_handle.generation,
            i64_field(&formatting_yielded, "operationId"),
            true,
            "",
        );
        assert_kind(&formatting_denied, OutcomeKind::MemoryFailure);
        assert!(
            u64_field(&formatting_denied, "deniedAllocations") >= 1,
            "error-formatting denial did not record a denied allocation: {:?}",
            formatting_denied.to_json()
        );
        assert_kind(&error_formatting.close(error_handle.generation), OutcomeKind::Closed);

        let peer_after = peer.snapshot(peer_handle.generation);
        assert_kind(&peer_after, OutcomeKind::Completed);
        assert_eq!(
            u64_field(&peer_after, "currentBytes"),
            u64_field(&peer_before, "currentBytes"),
            "allocator denial changed another state's accounting"
        );
        let healthy = load_and_start(&peer, "function main() return 'peer-alive' end", "main");
        assert_kind(&healthy, OutcomeKind::Completed);
        assert_eq!(string_field(&healthy, "value"), "peer-alive");
        assert_kind(&peer.close(peer_handle.generation), OutcomeKind::Closed);
    });
}

#[test]
fn concurrent_callers_serialize_without_double_execution() {
    each_topology(|topology| {
        let state = Arc::new(engine(topology));
        let generation = state.handle().generation;
        assert_kind(
            &state.load(
                generation,
                "hits = 0 function main() hits = hits + 1 return tostring(hits) end",
                "main",
            ),
            OutcomeKind::Completed,
        );
        let gate = Arc::new(Barrier::new(3));
        let first_state = Arc::clone(&state);
        let first_gate = Arc::clone(&gate);
        let first = thread::spawn(move || {
            first_gate.wait();
            first_state.start(generation)
        });
        let second_state = Arc::clone(&state);
        let second_gate = Arc::clone(&gate);
        let second = thread::spawn(move || {
            second_gate.wait();
            second_state.start(generation)
        });
        gate.wait();
        let first = first.join().expect("first caller panicked");
        let second = second.join().expect("second caller panicked");
        let outcomes = [first, second];
        assert_eq!(
            outcomes.iter().filter(|outcome| outcome.kind() == OutcomeKind::Completed).count(),
            1,
            "concurrent callers entered one Lua state more than once: {:?}",
            outcomes.iter().map(Outcome::to_json).collect::<Vec<_>>()
        );
        assert_eq!(
            outcomes.iter().filter(|outcome| outcome.kind() == OutcomeKind::ValidationFailure).count(),
            1,
            "serialized second start was not rejected after terminal completion: {:?}",
            outcomes.iter().map(Outcome::to_json).collect::<Vec<_>>()
        );
        let completed = outcomes
            .iter()
            .find(|outcome| outcome.kind() == OutcomeKind::Completed)
            .expect("one caller must complete");
        assert_eq!(string_field(completed, "value"), "1");
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    });
}



#[test]
fn topology_parser_accepts_only_contract_candidates() {
    assert_eq!(Topology::parse("jvm_owned"), Some(Topology::JvmOwned));
    assert_eq!(Topology::parse("worker_pool"), None);
}
