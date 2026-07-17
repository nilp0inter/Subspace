use std::sync::{Arc, Barrier};
use std::thread;

use subspace_lua_actor::{
    Generation, OperationId, Outcome, OutcomeKind, StateEngine,
    BINDING_VERSION, LUA_VERSION,
};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;
const STRESS_CYCLES: usize = 24;

fn engine() -> StateEngine {
    StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, INSTRUCTION_BUDGET)
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
    let left = engine();
    let right = engine();
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
}

#[test]
fn states_keep_module_caches_and_suspended_coroutines_independent() {
    let left = engine();
    let right = engine();
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
}

#[test]
fn snapshots_publish_identity_versions_and_memory_invariants() {
    let state = engine();
    let handle = state.handle();
    let snapshot = state.snapshot(handle.generation);
    assert_kind(&snapshot, OutcomeKind::Completed);
    assert_eq!(i64_field(&snapshot, "stateId"), handle.state_id);
    assert_eq!(i64_field(&snapshot, "generation"), handle.generation);
    assert_eq!(string_field(&snapshot, "topology"), "jvm_owned");
    assert_eq!(string_field(&snapshot, "luaVersion"), LUA_VERSION);
    assert_eq!(string_field(&snapshot, "bindingVersion"), BINDING_VERSION);
    assert!(
        u64_field(&snapshot, "currentBytes") <= u64_field(&snapshot, "peakBytes"),
        "live Lua bytes cannot exceed sampled peak"
    );
    let elapsed = snapshot.to_json().get("elapsedNanos").and_then(|value| value.as_u64());
    assert!(elapsed.is_some(), "snapshot omitted elapsedNanos evidence: {:?}", snapshot.to_json());
    assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
}

#[test]
fn malformed_source_and_invalid_entrypoint_are_normalized_and_closable() {
    for (source, entrypoint, expected) in [
        ("function main( return 1 end", "main", OutcomeKind::SyntaxFailure),
        ("\u{1b}Lua\0binary-chunk", "main", OutcomeKind::SyntaxFailure),
        ("not_an_entrypoint = 3", "not_an_entrypoint", OutcomeKind::ValidationFailure),
        ("function other() return 'ok' end", "missing", OutcomeKind::ValidationFailure),
    ] {
        let state = engine();
        let handle = state.handle();
        let outcome = state.load(handle.generation, source, entrypoint);
        assert_kind(&outcome, expected);
        assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
    }
}

#[test]
fn binary_and_dynamic_loader_inputs_reject_before_lua_effects_and_leave_state_usable() {
    let cases = [
        (
            "binary bytecode",
            "\u{1b}Lua\0binary-chunk",
            OutcomeKind::SyntaxFailure,
        ),
        (
            "package.loadlib",
            r#"
            effects = 0
            function main()
              local forbidden = package.loadlib
              effects = effects + 1
              return forbidden("/tmp/libuntrusted.so", "entry")
            end
            "#,
            OutcomeKind::RuntimeFailure,
        ),
        (
            "C module searcher",
            r#"
            effects = 0
            function main()
              local forbidden = package.searchers[3]
              effects = effects + 1
              return forbidden("untrusted")
            end
            "#,
            OutcomeKind::RuntimeFailure,
        ),
        (
            "shared-library require",
            r#"
            effects = 0
            function main()
              require("untrusted_shared_library")
              effects = effects + 1
              return "should-not-run"
            end
            "#,
            OutcomeKind::RuntimeFailure,
        ),
    ];

    for (name, source, expected) in cases {
        let state = engine();
        let handle = state.handle();
        let loaded = state.load(handle.generation, source, "main");
        if expected == OutcomeKind::SyntaxFailure {
            assert_kind(&loaded, expected);
        } else {
            assert_kind(&loaded, OutcomeKind::Completed);
            assert_kind(&state.start(handle.generation), expected);
        }

        assert_kind(
            &state.load(
                handle.generation,
                "function observe() return tostring(effects or 0) end",
                "observe",
            ),
            OutcomeKind::Completed,
        );
        let observed = state.start(handle.generation);
        assert_kind(&observed, OutcomeKind::Completed);
        assert_eq!(
            string_field(&observed, "value"),
            "0",
            "{name} reached Lua code after the rejected dynamic-loading boundary",
        );

        assert_kind(
            &state.load(
                handle.generation,
                "function healthy() return 'still-usable' end",
                "healthy",
            ),
            OutcomeKind::Completed,
        );
        let healthy = state.start(handle.generation);
        assert_kind(&healthy, OutcomeKind::Completed);
        assert_eq!(string_field(&healthy, "value"), "still-usable");
        assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
    }
}

#[test]
fn string_nonstrings_and_nested_lua_errors_are_normalized_and_recoverable() {
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
        let state = engine();
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
}

#[test]
fn protected_ordinary_lua_errors_leave_peer_state_usable() {
    // This observes isolation for ordinary protected Lua errors only; it makes no containment
    // claim for Rust panics, native corruption, process OOM, or process death.
    let failing = engine();
    let peer = engine();
    let failing_handle = failing.handle();
    let peer_handle = peer.handle();

    assert_kind(
        &failing.load(
            failing_handle.generation,
            "function main() error('ordinary-protected-error') end",
            "main",
        ),
        OutcomeKind::Completed,
    );
    let failed = failing.start(failing_handle.generation);
    assert_kind(&failed, OutcomeKind::RuntimeFailure);
    assert!(
        string_field(&failed, "diagnostic").contains("ordinary-protected-error"),
        "ordinary Lua error lost its diagnostic: {:?}",
        failed.to_json()
    );

    let peer_result = load_and_start(
        &peer,
        "peer_counter = (peer_counter or 0) + 1; function main() return tostring(peer_counter) end",
        "main",
    );
    assert_kind(&peer_result, OutcomeKind::Completed);
    assert_eq!(
        string_field(&peer_result, "value"),
        "1",
        "a protected error in one state changed a peer state's execution",
    );

    assert_kind(&failing.close(failing_handle.generation), OutcomeKind::Closed);
    assert_kind(&peer.close(peer_handle.generation), OutcomeKind::Closed);
}

#[test]
fn foreign_unknown_stale_and_closed_handles_cannot_resume_lua() {
    let owner = engine();
    let other = engine();
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
}

#[test]
fn success_failure_and_cancel_resumption_have_exactly_once_effects() {
    let success_state = engine();
    let (generation, operation) = yielded_operation(&success_state, YIELDING_COUNTER);
    let success = success_state.resume(generation, operation, true, "payload");
    assert_kind(&success, OutcomeKind::Completed);
    assert_eq!(string_field(&success, "value"), "success:payload");
    assert_kind(&success_state.close(success_state.handle().generation), OutcomeKind::Closed);

    let failure_state = engine();
    let (generation, operation) = yielded_operation(&failure_state, YIELDING_COUNTER);
    let failure = failure_state.resume(generation, operation, false, "denied");
    assert_kind(&failure, OutcomeKind::Completed);
    assert_eq!(string_field(&failure, "value"), "failure:denied");
    assert_kind(&failure_state.close(failure_state.handle().generation), OutcomeKind::Closed);

    let cancelled_state = engine();
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
}

#[test]
fn cancellation_and_close_races_admit_one_terminal_result() {
    let state = Arc::new(engine());
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

    let raced = Arc::new(engine());
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
}

#[test]
fn repeated_lifecycle_cycles_leave_old_handles_unusable() {
    for cycle in 0..STRESS_CYCLES {
        let state = engine();
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
}

#[test]
fn pure_lua_interruptions_are_state_local_and_compute_still_completes() {
    for workload in [
        "function main() while true do end end",
        "local function recurse(n) return recurse(n + 1) end function main() return recurse(0) end",
        r#"
        local proxy = setmetatable({}, { __index = function() return 1 end })
        function main() while true do local value = proxy.missing end end
        "#,
    ] {
        let interrupted = StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, 1_000)
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

        let unaffected = engine();
        let healthy = load_and_start(&unaffected, "function main() return 'unaffected' end", "main");
        assert_kind(&healthy, OutcomeKind::Completed);
        assert_eq!(string_field(&healthy, "value"), "unaffected");
        assert_kind(&unaffected.close(unaffected.handle().generation), OutcomeKind::Closed);
    }

    let compute = StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, 100_000)
        .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()));
    let computed = load_and_start(
        &compute,
        "function main() local sum = 0 for i = 1, 500 do sum = sum + i end return sum end",
        "main",
    );
    assert_kind(&computed, OutcomeKind::Completed);
    assert_eq!(string_field(&computed, "value"), "125250");
    assert_kind(&compute.close(compute.handle().generation), OutcomeKind::Closed);
}

#[test]
fn interrupting_a_suspended_operation_suppresses_its_later_lua_and_native_effects() {
    let interrupted = engine();
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

    let survivor = engine();
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
}

#[test]
fn yielding_host_boundary_returns_before_external_completion() {
    let state = engine();
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
}

#[test]
fn bounded_native_host_completes_and_blocking_candidate_rejects_before_work() {
    let state = engine();
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
}

#[test]
fn close_reports_terminal_lua_bytes_without_folding_in_bridge_bytes() {
    let state = engine();
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
}

// Binding constraint: mlua provides deterministic denial at VM construction and through a
// per-state memory limit, not a phase-selective allocator callback. Bootstrap, GC, hook
// installation, and bridge/JSON allocation therefore have no supported deterministic injection
// point and are intentionally not represented as recoverable Lua-allocation phases here.
#[test]
fn allocator_denial_is_normalized_at_every_deterministic_mlua_phase() {
    let creation = match StateEngine::new(1_024, HOOK_INTERVAL, INSTRUCTION_BUDGET) {
        Ok(_) => panic!("a 1 KiB Lua allocator limit must deny VM construction"),
        Err(outcome) => outcome,
    };
    assert_kind(&creation, OutcomeKind::MemoryFailure);

    let constrained = StateEngine::new(256 * 1024, HOOK_INTERVAL, INSTRUCTION_BUDGET)
        .unwrap_or_else(|outcome| panic!("constrained creation failed: {:?}", outcome.to_json()));
    let constrained_handle = constrained.handle();
    let peer = engine();
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

    let coroutine = engine();
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

    let table_growth = engine();
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

    let error_formatting = engine();
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
}

#[test]
fn concurrent_callers_serialize_without_double_execution() {
    let state = Arc::new(engine());
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
}

// ---------------------------------------------------------------------------
// Registry-level close/tombstone/cross-state concurrency
// ---------------------------------------------------------------------------

#[test]
#[cfg(feature = "registry-test")]
fn stale_close_does_not_tombstone_live_engine() {
    use subspace_lua_actor::registry_test;
 
    // A close with a stale generation must NOT tombstone the live engine.
    let state = engine();
    let handle = state.handle();
    registry_test::register(state.clone());

    let stale_gen: Generation = handle.generation + 1;
    let outcome = registry_test::close(handle.state_id, stale_gen);
    assert_kind(&outcome, OutcomeKind::Stale);

    // The live engine must be untouched.
    assert_eq!(
        registry_test::entry(handle.state_id),
        registry_test::Entry::Live { generation: handle.generation }
    );

    // The same engine is still usable through the registry.
    let snapshot = registry_test::dispatch(handle.state_id, handle.generation, |e| {
        e.snapshot(handle.generation)
    });
    assert_kind(&snapshot, OutcomeKind::Completed);

    // A rightful close must still succeed and create a tombstone.
    let closed = registry_test::close(handle.state_id, handle.generation);
    assert_kind(&closed, OutcomeKind::Closed);
    assert_eq!(
        registry_test::entry(handle.state_id),
        registry_test::Entry::Tombstone { closed_generation: handle.generation }
    );
    // A stale close on a tombstone always returns Closed (idempotent).
    let late_stale = registry_test::close(handle.state_id, stale_gen);
    assert_kind(&late_stale, OutcomeKind::Closed);

    registry_test::remove(handle.state_id);
 }

#[test]
#[cfg(feature = "registry-test")]
fn unknown_close_does_not_create_or_mutate_registry_entry() {
    use subspace_lua_actor::registry_test;

    // A close with an unknown state_id must not create any registry entry.
    let unknown_id = 99999;
    let outcome = registry_test::close(unknown_id, 1);
    assert_kind(&outcome, OutcomeKind::InvalidOwnership);
    assert_eq!(registry_test::entry(unknown_id), registry_test::Entry::Missing);

    // A totally separate state must still register and dispatch fine (the
    // unknown id does not "block" or poison the registry).
    let fresh = engine();
    let fresh_handle = fresh.handle();
    registry_test::register(fresh.clone());
    let load = registry_test::dispatch(fresh_handle.state_id, fresh_handle.generation, |e| {
        e.load(fresh_handle.generation, "function main() return 'fresh' end", "main")
    });
    assert_kind(&load, OutcomeKind::Completed);
    let start = registry_test::dispatch(fresh_handle.state_id, fresh_handle.generation, |e| {
        e.start(fresh_handle.generation)
    });
    assert_kind(&start, OutcomeKind::Completed);
    assert_eq!(string_field(&start, "value"), "fresh");

    registry_test::remove(fresh_handle.state_id);
}
 
 #[test]
 #[cfg(feature = "registry-test")]
 fn cross_state_dispatch_not_serialized_by_global_registry() {
     use subspace_lua_actor::registry_test;
 
     // Two states executing concurrently through the registry. The split-lock
     // guarantee: the global registry mutex is never held across Lua, so an
     // unrelated state's dispatch is NOT serialized behind a long-running one.
     //
     // This test proves the split-lock deterministically: the long state enters
     // the dispatch closure (where the registry lock would be held under old
     // code) while main thread probes registry_lock_available_for_test(). With
     // the split-lock fix, the lock is free inside the closure, returning true.
     // Old global-lock code holds it across the closure, returning false.
     let long_state = StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, 5_000)
         .unwrap_or_else(|outcome| panic!("long state creation failed: {:?}", outcome.to_json()));
     let quick_state = engine();
 
     let long_handle = long_state.handle();
     let quick_handle = quick_state.handle();
     registry_test::register(long_state.clone());
     registry_test::register(quick_state.clone());
 
     // Pre-load both states.
     assert_kind(
         &long_state.load(long_handle.generation, "function main() while true do end end", "main"),
         OutcomeKind::Completed,
     );
     assert_kind(
         &quick_state.load(
             quick_handle.generation,
             "function main() return 'quick' end",
             "main",
         ),
         OutcomeKind::Completed,
     );
 
     let about_to_run = Arc::new(Barrier::new(2));
     let long_proceed = Arc::new(Barrier::new(2));
     let long_sid = long_handle.state_id;
     let long_gen = long_handle.generation;
     let about_to_run_clone = Arc::clone(&about_to_run);
     let long_proceed_clone = Arc::clone(&long_proceed);
     let long = thread::spawn(move || {
         let outcome = registry_test::dispatch(long_sid, long_gen, |e| {
             // Inside closure: split-lock releases registry before calling f;
             // old global-lock holds it across f.
             about_to_run_clone.wait();
             long_proceed_clone.wait();
             e.start(long_gen)
         });
         outcome
     });
 
     // Wait for long to enter the dispatch closure.
     about_to_run.wait();
 
     // Deterministic proof: registry lock is NOT held inside the dispatch closure.
     // With split-lock: released before f → try_lock succeeds.
     // With old global-lock: held across f → try_lock fails.
     assert!(
         registry_test::registry_lock_available_for_test(),
         "registry lock held inside dispatch closure — global-lock regression"
     );
 
     // Release long to start Lua execution.
     long_proceed.wait();
 
     // Dispatch quick: completes independently because registry is not serialized.
     let quick_outcome = registry_test::dispatch(
         quick_handle.state_id,
         quick_handle.generation,
         |e| e.start(quick_handle.generation),
     );
     assert_kind(&quick_outcome, OutcomeKind::Completed);
     assert_eq!(string_field(&quick_outcome, "value"), "quick");
 
     let long_outcome = long.join().expect("long-state thread panicked");
     assert_kind(&long_outcome, OutcomeKind::Interrupted);
 
     registry_test::remove(long_handle.state_id);
     registry_test::remove(quick_handle.state_id);
 }

#[test]
fn source_only_lua_cannot_use_base_load_for_constructed_bytecode() {
    // The base library's `load` function is replaced with a text-only wrapper
    // that rejects binary chunks. `dofile` and `loadfile` are removed.
    for (source, expected_prefix) in [
        (
            // Construct a binary-signature chunk via string and try to load it.
            r#"
            function main()
              local chunk = string.char(27) .. "nonsense"
              local fn, err = load(chunk)
              if fn then
                fn()
                return "executed-binary"
              end
              return "rejected:" .. tostring(err)
            end
            "#,
            "rejected:",
        ),
        (
            // dofile is nil — pcall returns error, not boolean.
            r#"
            function main()
              local ok, err = pcall(dofile, "anywhere")
              return "dofile-error:" .. tostring(err)
            end
            "#,
            "dofile-error:",
        ),
        (
            // loadfile is nil — pcall returns error.
            r#"
            function main()
              local ok, err = pcall(loadfile, "anywhere")
              return "loadfile-error:" .. tostring(err)
            end
            "#,
            "loadfile-error:",
        ),
    ] {
        let state = engine();
        let handle = state.handle();
        assert_kind(
            &state.load(handle.generation, source, "main"),
            OutcomeKind::Completed,
        );
        let outcome = state.start(handle.generation);

        if expected_prefix == "rejected:" {
            // The load wrapper returns nil + diagnostic; Lua returns the
            // concatenated string, which must include the binary rejection.
            assert_kind(&outcome, OutcomeKind::Completed);
            let value = string_field(&outcome, "value");
            assert!(
                value.starts_with("rejected:"),
                "binary load was not handled: {:?}",
                outcome.to_json()
            );
            assert!(
                value.contains("binary"),
                "binary rejection did not contain 'binary': {:?}",
                outcome.to_json()
            );
        } else {
            // dofile/loadfile are nil — pcall catches the error.
            assert_kind(&outcome, OutcomeKind::Completed);
            let value = string_field(&outcome, "value");
            assert!(
                value.contains("nil") || value.contains("attempt"),
                "dofile/loadfile error unexpected: {:?}",
                outcome.to_json()
            );
        }

        // State remains usable after attempting the dynamic/bytecode load.
        assert_kind(
            &state.load(
                handle.generation,
                "function healthy() return 'still-usable' end",
                "healthy",
            ),
            OutcomeKind::Completed,
        );
        let healthy = state.start(handle.generation);
        assert_kind(&healthy, OutcomeKind::Completed);
        assert_eq!(string_field(&healthy, "value"), "still-usable");

        assert_kind(&state.close(handle.generation), OutcomeKind::Closed);
    }
}

#[test]
#[cfg(feature = "registry-test")]
fn bounded_tombstone_history_evicts_oldest_and_allows_new_ids() {
    use subspace_lua_actor::registry_test;

    let bound = registry_test::MAX_TOMBSTONES;
    let mut state_ids = Vec::with_capacity(bound + 2);
    let mut generations = Vec::with_capacity(bound + 2);

    // Close `bound + 1` distinct states, collecting their ids.
    for _i in 0..=bound {
        let state = engine();
        let sid = state.state_id();
        let gen = state.handle().generation;
        registry_test::register(state.clone());
        let closed = registry_test::close(sid, gen);
        assert_kind(&closed, OutcomeKind::Closed);
        state_ids.push(sid);
        generations.push(gen);
    }

    // The oldest tombstone must be evicted.
    let oldest_id = state_ids[0];
    assert_eq!(
        registry_test::entry(oldest_id),
        registry_test::Entry::Missing,
        "oldest tombstone was not evicted after exceeding MAX_TOMBSTONES"
    );

    // The newest tombstone must still be present.
    let newest_id = state_ids[bound];
    let newest_gen = generations[bound];
    assert_eq!(
        registry_test::entry(newest_id),
        registry_test::Entry::Tombstone { closed_generation: newest_gen }
    );

    let final_tomb_count = registry_test::tombstone_count();
    assert!(
        final_tomb_count <= bound,
        "tombstone count {final_tomb_count} exceeds bound {bound}"
    );

    // A late dispatch on the evicted id receives invalid_ownership.
    let late = registry_test::dispatch(oldest_id, generations[0], |_| {
        panic!("dispatch must not call the closure for a missing entry")
    });
    assert_kind(&late, OutcomeKind::InvalidOwnership);

    // A recent (non-evicted) tombstone must preserve typed closed/stale semantics:
    // matching generation → closed; mismatched generation → stale.
    let recent_id = state_ids[bound];
    let recent_gen = generations[bound];
    let closed = registry_test::close(recent_id, recent_gen);
    assert_kind(&closed, OutcomeKind::Closed);
    let stale = registry_test::dispatch(recent_id, recent_gen + 1, |_| {
        panic!("dispatch must not call closure for stale generation on tombstone")
    });
    assert_kind(&stale, OutcomeKind::Stale);
    let matching = registry_test::dispatch(recent_id, recent_gen, |_| {
        panic!("dispatch must not call closure for closed state")
    });
    assert!(matches!(matching.kind(), OutcomeKind::Closed | OutcomeKind::Stale));

    // A fresh state_id (brand new engine) registers and dispatches fine,
    // confirming eviction does not block new state IDs.
    let fresh = engine();
    let fresh_id = fresh.state_id();
    let fresh_gen = fresh.handle().generation;
    registry_test::register(fresh.clone());
    let load = registry_test::dispatch(fresh_id, fresh_gen, |e| {
        e.load(fresh_gen, "function main() return 'fresh' end", "main")
    });
    assert_kind(&load, OutcomeKind::Completed);
    let start = registry_test::dispatch(fresh_id, fresh_gen, |e| {
        e.start(fresh_gen)
    });
    assert_kind(&start, OutcomeKind::Completed);
    assert_eq!(string_field(&start, "value"), "fresh");

    // Cleanup.
    for &sid in &state_ids[1..] {
        registry_test::remove(sid);
    }
    registry_test::remove(fresh_id);
}

// ---------------------------------------------------------------------------
// Linearizable interrupt/resume over a suspended operation
// ---------------------------------------------------------------------------

/// Source whose main coroutine yields twice before completing. The first
/// `yield_operation` produces `op1`; resuming `op1` makes the coroutine yield
/// again, producing a fresh `op2`; resuming `op2` completes. This is the
/// critical shape for the stale-peek→unlock→invalidate race: the old
/// `interrupt` peeked `lifecycle == Yielded` and released the lock, then a
/// concurrent `resume` of `op1` could produce a new `Yielded` state with a
/// different `current_operation` (`op2`). The old `handle_invalidate_suspended`
/// only checked `lifecycle == Yielded`, so it would invalidate `op2` — a
/// suspension the interrupt never observed — corrupting the state.
const DOUBLE_YIELDING_COUNTER: &str = r#"
hits = 0
function main()
  local success1, value1 = subspace.yield_operation("first-work")
  hits = hits + 1
  local success2, value2 = subspace.yield_operation("second-work")
  hits = hits + 1
  if success1 and success2 then return "success:" .. value1 .. ":" .. value2 end
  return "failure:" .. value1 .. ":" .. value2
end
function observe() return tostring(hits) end
"#;

/// Deterministically force the old `interrupt` peek→unlock→invalidate
/// interleaving over one suspended operation. The test-only post-peek hook
/// blocks interrupt after it observed `op1` and released the state mutex. The
/// main test thread then resumes `op1`, creating `op2`, before a second
/// `Barrier` release permits tagged invalidation of its stale target.
///
/// No sleeps/timeouts synchronize this test. Resume is the sole terminal
/// winner for `op1`; interrupt is the typed losing call (`Stale(op1)`), and
/// op2 must remain resumable.
#[test]
fn concurrent_interrupt_resume_is_linearizable_per_state() {
    let state = Arc::new(engine());
    let generation = state.handle().generation;
    assert_kind(
        &state.load(generation, DOUBLE_YIELDING_COUNTER, "main"),
        OutcomeKind::Completed,
    );
    let started = state.start(generation);
    assert_kind(&started, OutcomeKind::Yielded);
    let op1 = i64_field(&started, "operationId");

    // The hook runs after interrupt has atomically observed op1 and released
    // the state mutex, but before it sets the flag and dispatches tagged
    // invalidation. Both barriers have two parties: interrupt and this test.
    let observed = Arc::new(Barrier::new(2));
    let release = Arc::new(Barrier::new(2));
    let hook_observed = Arc::clone(&observed);
    let hook_release = Arc::clone(&release);
    state.set_interrupt_post_peek_hook(Some(Arc::new(move || {
        hook_observed.wait();
        hook_release.wait();
    })));

    let interrupt_state = Arc::clone(&state);
    let interrupt = thread::spawn(move || interrupt_state.interrupt(generation));

    // This returns only after interrupt has captured op1 and relinquished its
    // lock. Resume therefore wins op1 and yields distinct op2 before stale
    // invalidation may re-acquire the mutex.
    observed.wait();
    let resumed = state.resume(generation, op1, true, "first-resume");
    assert_kind(&resumed, OutcomeKind::Yielded);
    let op2 = i64_field(&resumed, "operationId");
    assert_ne!(op2, op1, "resume did not replace op1 with a fresh operation");

    release.wait();
    let interrupted = interrupt.join().expect("interrupt thread panicked");
    state.set_interrupt_post_peek_hook(None);

    // Exactly one call terminally consumed op1: resume advanced it. Interrupt
    // must report the loss as typed Stale carrying the identity it observed.
    assert_kind(&interrupted, OutcomeKind::Stale);
    assert_eq!(i64_field(&interrupted, "operationId"), op1);

    // The old lifecycle-only invalidation would see Yielded and destroy op2.
    // Tagged invalidation instead leaves it live, and clearing the stale
    // interrupt flag lets its later resume complete normally.
    let completed = state.resume(generation, op2, true, "second-resume");
    assert_kind(&completed, OutcomeKind::Completed);
    assert_eq!(
        string_field(&completed, "value"),
        "success:first-resume:second-resume"
    );
    assert_kind(
        &state.load(generation, "function observe() return tostring(hits) end", "observe"),
        OutcomeKind::Completed,
    );
    assert_eq!(string_field(&state.start(generation), "value"), "2");

    // A peer state remains independently usable after the deterministic race.
    let peer = engine();
    let peer_outcome = load_and_start(&peer, "function main() return 'peer-survives' end", "main");
    assert_kind(&peer_outcome, OutcomeKind::Completed);
    assert_eq!(string_field(&peer_outcome, "value"), "peer-survives");
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    assert_kind(&peer.close(peer.handle().generation), OutcomeKind::Closed);
}



// ---------------------------------------------------------------------------
// Plugin coroutine boundary
// ---------------------------------------------------------------------------

/// `StateEngine` owns the only resumable coroutine: its hook is per-thread,
/// so a plugin must not create/resume a child that could run outside the
/// instruction/interrupt budget. The test intentionally supplies an infinite
/// child body; the direct RuntimeFailure at `coroutine.create` proves it never
/// launched, instead of relying on a timing-dependent later stop.
#[test]
fn plugin_cannot_create_or_resume_an_unhooked_infinite_child_coroutine() {
    let state = engine();
    let generation = state.handle().generation;
    assert_kind(
        &state.load(
            generation,
            r#"
            function main()
              local child = coroutine.create(function()
                while true do end
              end)
              return coroutine.resume(child)
            end
            "#,
            "main",
        ),
        OutcomeKind::Completed,
    );
    let rejected = state.start(generation);
    assert_kind(&rejected, OutcomeKind::RuntimeFailure);
    let diagnostic = string_field(&rejected, "diagnostic");
    assert!(
        diagnostic.contains("nil") || diagnostic.contains("attempt"),
        "coroutine.create/resume rejection did not identify the removed API: {:?}",
        rejected.to_json()
    );

    // Rejection is isolated to the attempted child API: the same state can
    // subsequently execute a normal host-owned entry coroutine.
    assert_kind(
        &state.load(generation, "function healthy() return 'same-state-usable' end", "healthy"),
        OutcomeKind::Completed,
    );
    let healthy = state.start(generation);
    assert_kind(&healthy, OutcomeKind::Completed);
    assert_eq!(string_field(&healthy, "value"), "same-state-usable");
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

// ---------------------------------------------------------------------------
// Bounded terminal-operation retention
// ---------------------------------------------------------------------------

/// A long-lived coroutine may yield indefinitely, so its exact duplicate
/// outcomes must be bounded. The cache keeps recent terminal outcomes for
/// exactly-once retries and retains a bounded id-only tombstone for just-evicted
/// owned operations, which report typed `Stale` instead of masquerading as a
/// foreign operation.
#[test]
fn terminal_operation_outcomes_are_bounded_and_evictions_remain_typed() {
    let state = engine();
    let generation = state.handle().generation;
    let capacity = StateEngine::terminal_operation_cache_capacity();
    assert!(capacity > 0, "terminal outcome cache capacity must be nonzero");
    let operation_count = capacity + 1;
    let source = format!(
        r#"
        hits = 0
        function main()
          for i = 1, {operation_count} do
            local success, value = subspace.yield_operation("bounded-" .. i)
            hits = hits + 1
          end
          return tostring(hits)
        end
        "#
    );
    assert_kind(&state.load(generation, &source, "main"), OutcomeKind::Completed);

    let first = state.start(generation);
    assert_kind(&first, OutcomeKind::Yielded);
    let first_operation = i64_field(&first, "operationId");
    let mut operation = first_operation;
    let mut final_outcome = None;

    // Complete strictly more operations than the cache capacity in one
    // coroutine, retaining the last operation id for the exact duplicate.
    for index in 0..operation_count {
        let outcome = state.resume(generation, operation, true, "completed");
        if index + 1 == operation_count {
            assert_kind(&outcome, OutcomeKind::Completed);
            final_outcome = Some((operation, outcome));
        } else {
            assert_kind(&outcome, OutcomeKind::Yielded);
            operation = i64_field(&outcome, "operationId");
        }
    }
    let (recent_operation, final_outcome) = final_outcome.expect("last resume must complete");
    assert_eq!(
        string_field(&final_outcome, "value"),
        operation_count.to_string(),
        "all real resumes must execute exactly once"
    );

    // A recent duplicate remains an exact typed echo and must not re-enter
    // Lua: it returns the same completed payload despite a different value.
    let recent_duplicate = state.resume(generation, recent_operation, true, "must-not-reenter");
    assert_kind(&recent_duplicate, OutcomeKind::Completed);
    assert_eq!(
        string_field(&recent_duplicate, "value"),
        string_field(&final_outcome, "value"),
        "recent duplicate did not echo its exact terminal outcome"
    );

    // The first operation aged out of exact retention. It is nevertheless an
    // operation this state issued, so it gets a typed stale outcome rather
    // than `InvalidOwnership` or a second Lua entry.
    let evicted_duplicate = state.resume(generation, first_operation, true, "old-retry");
    assert_kind(&evicted_duplicate, OutcomeKind::Stale);
    assert_eq!(
        i64_field(&evicted_duplicate, "operationId"),
        first_operation,
        "evicted duplicate did not identify the operation that aged out"
    );
    assert!(
        string_field(&evicted_duplicate, "diagnostic").contains("evicted"),
        "evicted duplicate did not explain typed stale retention: {:?}",
        evicted_duplicate.to_json()
    );

    // Both the exercised state and an independent peer remain usable after
    // eviction handling.
    assert_kind(
        &state.load(generation, "function healthy() return 'state-usable' end", "healthy"),
        OutcomeKind::Completed,
    );
    let healthy = state.start(generation);
    assert_kind(&healthy, OutcomeKind::Completed);
    assert_eq!(string_field(&healthy, "value"), "state-usable");

    let peer = engine();
    let peer_outcome = load_and_start(&peer, "function main() return 'peer-usable' end", "main");
    assert_kind(&peer_outcome, OutcomeKind::Completed);
    assert_eq!(string_field(&peer_outcome, "value"), "peer-usable");

    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    assert_kind(&peer.close(peer.handle().generation), OutcomeKind::Closed);
}
