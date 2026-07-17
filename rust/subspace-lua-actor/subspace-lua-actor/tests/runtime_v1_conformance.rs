use std::sync::Arc;

use serde_json::{json, Value};
use subspace_lua_actor::{Outcome, OutcomeKind, SpawnAdmission, SpawnAdmitter, StateEngine};

const MEMORY_LIMIT: u64 = 4 * 1024 * 1024;
const HOOK_INTERVAL: u32 = 100;
const INSTRUCTION_BUDGET: u64 = 50_000;

fn engine(tasks: usize, timers: usize) -> StateEngine {
    StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, INSTRUCTION_BUDGET, tasks, timers)
        .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()))
}

fn assert_kind(outcome: &Outcome, expected: OutcomeKind) {
    assert_eq!(outcome.kind(), expected, "outcome: {:?}", outcome.to_json());
}

fn diagnostic(outcome: &Outcome) -> String {
    outcome
        .to_json()
        .get("diagnostic")
        .and_then(Value::as_str)
        .unwrap_or_else(|| panic!("outcome omitted diagnostic: {:?}", outcome.to_json()))
        .to_owned()
}

fn value(outcome: &Outcome) -> Value {
    outcome
        .to_json()
        .get("value")
        .unwrap_or_else(|| panic!("outcome omitted value: {:?}", outcome.to_json()))
        .clone()
}


fn load_image(state: &StateEngine, sources: Value, entrypoint: &str) {
    let generation = state.handle().generation;
    let source_map = serde_json::to_string(&sources).expect("fixture source map must serialize");
    assert_kind(
        &state.load_program_image(generation, &source_map, entrypoint),
        OutcomeKind::Completed,
    );
}

fn invoke(state: &StateEngine, callback: &str) -> Outcome {
    state.invoke_callback(state.handle().generation, callback, "null")
}

fn assert_invalid_value(outcome: &Outcome) {
    assert_kind(outcome, OutcomeKind::RuntimeFailure);
    assert!(
        diagnostic(outcome).contains("E_INVALID_VALUE"),
        "invalid Lua value escaped as a different error: {:?}",
        outcome.to_json()
    );
    assert!(
        outcome.to_json().get("value").is_none(),
        "normalization must reject the whole value rather than return a partial result: {:?}",
        outcome.to_json()
    );
}

#[test]
fn restricted_globals_are_absent_and_disabled_operations_leave_the_state_closable() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                return {
                  startup = function() end,
                  probe = function()
                    local dump_ok, dump_error = pcall(string.dump, function() return 1 end)
                    return {
                      io_missing = io == nil,
                      os_missing = os == nil,
                      debug_missing = debug == nil,
                      package_missing = package == nil,
                      load_missing = load == nil,
                      loadfile_missing = loadfile == nil,
                      dofile_missing = dofile == nil,
                      dump_disabled = not dump_ok,
                      dump_error = tostring(dump_error),
                    }
                  end,
                }
            "#,
        }),
        "entry",
    );

    let probe = invoke(&state, "probe");
    assert_kind(&probe, OutcomeKind::Completed);
    let result = value(&probe);
    for field in [
        "io_missing",
        "os_missing",
        "debug_missing",
        "package_missing",
        "load_missing",
        "loadfile_missing",
        "dofile_missing",
        "dump_disabled",
    ] {
        assert_eq!(
            result[field],
            json!(true),
            "the v1 sandbox exposed a forbidden standard-library entry: {result}",
        );
    }
    assert!(
        result["dump_error"]
            .as_str()
            .is_some_and(|error| error.contains("string.dump is disabled")),
        "string.dump did not fail with its stable disabled diagnostic: {result}",
    );

    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    let late = invoke(&state, "probe");
    assert!(
        matches!(late.kind(), OutcomeKind::Closed | OutcomeKind::Stale),
        "closed state re-entered Lua: {:?}",
        late.to_json()
    );
}

#[test]
fn failed_images_cannot_mutate_host_modules_and_roll_back_before_valid_reload() {
    let valid_image = json!({
        "entry": r#"
            return {
              startup = function() end,
              probe = function()
                return {
                  root_leak_absent = subspace.leaked == nil,
                  runtime_leak_absent = subspace.runtime.leaked == nil,
                  lua_version = subspace.runtime.LUA_VERSION,
                  lua_release = subspace.runtime.LUA_RELEASE,
                  api_version = subspace.runtime.API_VERSION,
                }
              end,
            }
        "#,
    });
    for (name, mutation) in [
        ("root field", "subspace.leaked = 'forbidden'"),
        ("runtime module", "subspace.runtime = {}"),
        ("runtime constant", "subspace.runtime.LUA_VERSION = 'forged'"),
        ("runtime field", "subspace.runtime.leaked = 'forbidden'"),
    ] {
        let state = engine(4, 4);
        let failed = serde_json::to_string(&json!({
            "entry": format!("{mutation}; return {{ startup = function() end }}"),
        }))
        .expect("fixture source map must serialize");
        let outcome = state.load_program_image(state.handle().generation, &failed, "entry");
        assert_kind(&outcome, OutcomeKind::RuntimeFailure);
        assert!(
            diagnostic(&outcome).contains("read-only subspace namespace"),
            "{name} mutation did not fail at the image-owned read-only boundary: {:?}",
            outcome.to_json(),
        );

        load_image(&state, valid_image.clone(), "entry");
        let probe = invoke(&state, "probe");
        assert_kind(&probe, OutcomeKind::Completed);
        assert_eq!(
            value(&probe),
            json!({
                "root_leak_absent": true,
                "runtime_leak_absent": true,
                "lua_version": "Lua 5.4",
                "lua_release": "5.4.8",
                "api_version": "subspace-lua-v1",
            }),
            "{name} failed image leaked a write into a later valid image",
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    }
}

#[test]
fn early_image_staging_allocator_denial_rolls_back_bridge_accounting_and_closes() {
    let state = engine(4, 4);
    let generation = state.handle().generation;
    let before = state.snapshot(generation);
    assert_kind(&before, OutcomeKind::Completed);
    assert_eq!(before.to_json()["bridgeBytes"], json!(0));
    assert_kind(&state.lower_memory_limit(generation, 1), OutcomeKind::Completed);

    let source_map = serde_json::to_string(&json!({
        "entry": "return { startup = function() end }",
    }))
    .expect("fixture source map must serialize");
    let denied = state.load_program_image(generation, &source_map, "entry");
    assert_kind(&denied, OutcomeKind::MemoryFailure);
    assert_eq!(
        denied.to_json()["bridgeBytes"],
        before.to_json()["bridgeBytes"],
        "bridge accounting from a failed early image stage was retained",
    );
    assert!(
        denied.to_json()["deniedAllocations"]
            .as_u64()
            .is_some_and(|count| count >= 1),
        "early image staging allocation denial was not reported: {:?}",
        denied.to_json(),
    );
    let after = state.snapshot(generation);
    assert_kind(&after, OutcomeKind::Completed);
    assert_eq!(
        after.to_json()["bridgeBytes"],
        before.to_json()["bridgeBytes"],
        "snapshot retained bridge bytes from a rolled-back image",
    );
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn legacy_unscoped_start_rejects_spawn_without_retaining_a_child_task() {
    let state = engine(4, 4);
    let generation = state.handle().generation;
    assert_kind(
        &state.load(
            generation,
            r#"
                function main()
                  local accepted, error = subspace.runtime.spawn(function() end)
                  return tostring(accepted) .. ":" .. tostring(error and error.error)
                end
            "#,
            "main",
        ),
        OutcomeKind::Completed,
    );
    let started = state.start(generation);
    assert_kind(&started, OutcomeKind::Completed);
    assert_eq!(value(&started), json!("nil:E_INVALID_CONTEXT"));
    assert!(
        started.to_json().get("spawnedCoroutines").is_none(),
        "legacy start admitted a child without explicit host admission: {:?}",
        started.to_json(),
    );
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn host_modules_have_exact_constants_and_package_local_modules_cache_real_lua_values() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                local channel = require("subspace.channel")
                local nested = require("plugin.nested")
                local nil_first = require("plugin.nil_return")
                local library_first = require("plugin.library")
                return {
                  startup = function() end,
                  probe = function()
                    local nil_second = require("plugin.nil_return")
                    local library_second = require("plugin.library")
                    return {
                      lua_version = runtime.LUA_VERSION,
                      lua_release = runtime.LUA_RELEASE,
                      api_version = runtime.API_VERSION,
                      no_version_callable = runtime.version == nil,
                      lifecycle_ready = channel.LIFECYCLE_READY,
                      capture_complete = channel.CAPTURE_COMPLETE,
                      sos_triggered = channel.SOS_TRIGGERED,
                      nested = nested.value,
                      nil_cached_as_true = nil_first == true and nil_second == true,
                      nil_load_count = nil_load_count,
                      function_identity = library_first == library_second,
                      function_value = library_first(19, 23),
                    }
                  end,
                }
            "#,
            "plugin.nested": r#"
                local helper = require("plugin.helper")
                return { value = helper.value }
            "#,
            "plugin.helper": "return { value = 'nested package-local module' }",
            "plugin.nil_return": "nil_load_count = (nil_load_count or 0) + 1",
            "plugin.library": "return function(left, right) return left + right end",
        }),
        "entry",
    );

    let probe = invoke(&state, "probe");
    assert_kind(&probe, OutcomeKind::Completed);
    assert_eq!(
        value(&probe),
        json!({
            "lua_version": "Lua 5.4",
            "lua_release": "5.4.8",
            "api_version": "subspace-lua-v1",
            "no_version_callable": true,
            "lifecycle_ready": "ready",
            "capture_complete": "capture",
            "sos_triggered": "sos",
            "nested": "nested package-local module",
            "nil_cached_as_true": true,
            "nil_load_count": 1,
            "function_identity": true,
            "function_value": 42,
        }),
        "host module identity and package-local require cache semantics changed",
    );
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn module_caches_are_isolated_between_independent_state_generations() {
    let image = json!({
        "entry": r#"
            local mutable = require("plugin.mutable")
            return {
              startup = function() end,
              increment = function()
                mutable.count = mutable.count + 1
                return { count = mutable.count }
              end,
            }
        "#,
        "plugin.mutable": "return { count = 0 }",
    });
    let left = engine(4, 4);
    let right = engine(4, 4);
    load_image(&left, image.clone(), "entry");
    load_image(&right, image, "entry");

    let left_first = invoke(&left, "increment");
    assert_kind(&left_first, OutcomeKind::Completed);
    assert_eq!(value(&left_first), json!({"count": 1}));
    let left_second = invoke(&left, "increment");
    assert_kind(&left_second, OutcomeKind::Completed);
    assert_eq!(value(&left_second), json!({"count": 2}));

    let right_first = invoke(&right, "increment");
    assert_kind(&right_first, OutcomeKind::Completed);
    assert_eq!(
        value(&right_first),
        json!({"count": 1}),
        "a module-table mutation in one state leaked through the per-generation cache",
    );
    assert_kind(&left.close(left.handle().generation), OutcomeKind::Closed);
    assert_kind(&right.close(right.handle().generation), OutcomeKind::Closed);
}

#[test]
fn module_resolver_returns_typed_errors_and_rejects_reserved_source_shadowing() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                return {
                  startup = function() end,
                  probe = function()
                    local function message(name)
                      local ok, err = pcall(require, name)
                      return ok, tostring(err)
                    end
                    local exact_ok, exact = message("subspace")
                    local reserved_ok, reserved = message("subspace.unknown")
                    local missing_ok, missing = message("missing.module")
                    local dots_ok, dots = message("plugin..bad")
                    local path_ok, path = message("../../etc/passwd")
                    return {
                      exact_ok = exact_ok, exact = exact,
                      reserved_ok = reserved_ok, reserved = reserved,
                      missing_ok = missing_ok, missing = missing,
                      dots_ok = dots_ok, dots = dots,
                      path_ok = path_ok, path = path,
                    }
                  end,
                }
            "#,
        }),
        "entry",
    );
    let probe = invoke(&state, "probe");
    assert_kind(&probe, OutcomeKind::Completed);
    let result = value(&probe);
    for (ok_key, error_key, expected) in [
        ("exact_ok", "exact", "E_RESERVED_MODULE"),
        ("reserved_ok", "reserved", "E_RESERVED_MODULE"),
        ("missing_ok", "missing", "E_MODULE_NOT_FOUND"),
        ("dots_ok", "dots", "E_INVALID_MODULE_NAME"),
        ("path_ok", "path", "E_INVALID_MODULE_NAME"),
    ] {
        assert_eq!(result[ok_key], json!(false), "{ok_key}: {result}");
        assert!(
            result[error_key]
                .as_str()
                .is_some_and(|message| message.contains(expected)),
            "{error_key} did not retain {expected}: {result}",
        );
    }
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);

    for invalid_name in ["subspace", "subspace.runtime", "Plugin.upper", "plugin..empty", "plugin."] {
        let rejected = engine(4, 4);
        let sources = serde_json::to_string(&json!({
            "entry": "return { startup = function() end }",
            invalid_name: "return { shadow = true }",
        }))
        .expect("fixture source map must serialize");
        let outcome = rejected.load_program_image(rejected.handle().generation, &sources, "entry");
        assert_kind(&outcome, OutcomeKind::ValidationFailure);
        assert_kind(&rejected.close(rejected.handle().generation), OutcomeKind::Closed);
    }
}

#[test]
fn recursive_and_effectful_module_loads_fail_without_partial_cache_entries() {
    let cycle = engine(4, 4);
    load_image(
        &cycle,
        json!({
            "entry": r#"
                return {
                  startup = function() end,
                  probe = function()
                    local ok, err = pcall(require, "plugin.a")
                    return { ok = ok, error = tostring(err) }
                  end,
                }
            "#,
            "plugin.a": "return require('plugin.b')",
            "plugin.b": "return require('plugin.a')",
        }),
        "entry",
    );
    let cycle_outcome = invoke(&cycle, "probe");
    assert_kind(&cycle_outcome, OutcomeKind::Completed);
    assert_eq!(value(&cycle_outcome)["ok"], json!(false));
    assert!(
        value(&cycle_outcome)["error"]
            .as_str()
            .is_some_and(|error| error.contains("E_MODULE_CYCLE")),
        "recursive require did not return E_MODULE_CYCLE: {:?}",
        cycle_outcome.to_json()
    );
    assert_kind(&cycle.close(cycle.handle().generation), OutcomeKind::Closed);

    for (name, effect) in [
        ("spawn", "api.spawn(function() end)"),
        ("sleep", "api.sleep(1)"),
        ("log", "api.info({message = 'not allowed during module load'})"),
    ] {
        let state = engine(4, 4);
        let module = if name == "log" { "subspace.log" } else { "subspace.runtime" };
        let source = format!(
            "attempts = (attempts or 0) + 1; local api = require('{module}'); {effect}; return {{ cached = true }}"
        );
        load_image(
            &state,
            json!({
                "entry": r#"
                    return {
                      startup = function() end,
                      probe = function()
                        local first_ok, first_error = pcall(require, "plugin.effect")
                        local second_ok, second_error = pcall(require, "plugin.effect")
                        return {
                          first_ok = first_ok,
                          first_error = tostring(first_error),
                          second_ok = second_ok,
                          second_error = tostring(second_error),
                          attempts = attempts,
                        }
                      end,
                    }
                "#,
                "plugin.effect": source,
            }),
            "entry",
        );
        let outcome = invoke(&state, "probe");
        assert_kind(&outcome, OutcomeKind::Completed);
        let result = value(&outcome);
        for key in ["first_ok", "second_ok"] {
            assert_eq!(result[key], json!(false), "{name} load unexpectedly succeeded: {result}");
        }
        for key in ["first_error", "second_error"] {
            assert!(
                result[key]
                    .as_str()
                    .is_some_and(|error| error.contains("effect-call-during-load")),
                "{name} did not expose the load effect guard: {result}",
            );
        }
        assert_eq!(
            result["attempts"],
            json!(2),
            "{name} failure left a partial cache entry instead of retrying the failed module: {result}",
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    }
}

#[test]
fn callback_tables_reject_metatables_and_wrong_recognized_keys_but_retain_valid_handles() {
    for (name, entry, expected) in [
        (
            "metatable",
            "local callbacks = {}; return setmetatable(callbacks, { __index = { startup = function() end } })",
            "callback table has metatable",
        ),
        (
            "missing required callback",
            "return { handle_readiness = function() return {ready = true} end }",
            "required callback 'startup' is missing",
        ),
        (
            "wrong required callback type",
            "return { startup = 'not a function' }",
            "expected function for callback 'startup', got string",
        ),
        (
            "wrong optional callback type",
            "return { startup = function() end, handle_readiness = 7 }",
            "expected function for callback 'handle_readiness', got integer",
        ),
    ] {
        let state = engine(4, 4);
        let sources = serde_json::to_string(&json!({ "entry": entry }))
            .expect("fixture source map must serialize");
        let outcome = state.load_program_image(state.handle().generation, &sources, "entry");
        assert_kind(&outcome, OutcomeKind::ValidationFailure);
        assert!(
            diagnostic(&outcome).contains(expected),
            "{name} failed with the wrong validation detail: {:?}",
            outcome.to_json()
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    }

    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local starts = 0
                return {
                  startup = function() starts = starts + 1 end,
                  handle_readiness = function() return { ready = starts == 2 } end,
                  unrecognized = "ignored during validation",
                }
            "#,
        }),
        "entry",
    );
    assert_kind(&invoke(&state, "startup"), OutcomeKind::Completed);
    assert_kind(&invoke(&state, "startup"), OutcomeKind::Completed);
    let readiness = invoke(&state, "handle_readiness");
    assert_kind(&readiness, OutcomeKind::Completed);
    assert_eq!(value(&readiness), json!({"ready": true}));
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn callback_normalization_preserves_allowed_values_and_rejects_each_invalid_value_whole() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local function nested(depth)
                  local root = {}
                  local current = root
                  for _ = 1, depth do
                    local child = {}
                    current.child = child
                    current = child
                  end
                  return root
                end
                return {
                  startup = function() end,
                  nil_value = function() return nil end,
                  bool_value = function() return true end,
                  number_value = function() return 12.5 end,
                  utf8_value = function() return "Καλημέρα" end,
                  string_value = function() return "bounded string" end,
                  array_value = function() return { "first", false, 3 } end,
                  map_value = function() return { alpha = 1, nested = { beta = "two" } } end,
                  cycle = function() local value = {}; value.self = value; return value end,
                  metatable = function() return setmetatable({ safe = true }, {}) end,
                  function_value = function() return function() end end,
                  thread = function() return coroutine.create(function() end) end,
                  non_finite = function() return 0 / 0 end,
                  invalid_utf8 = function() return string.char(255) end,
                  mixed = function() return { "array", named = "map" } end,
                  sparse = function() return { [1] = "first", [3] = "third" } end,
                  deep = function() return nested(12) end,
                  too_many_entries = function()
                    local value = {}
                    for index = 1, 1001 do value["key" .. index] = index end
                    return value
                  end,
                  too_long_string = function() return string.rep("x", 65537) end,
                  shared_table_alias = function()
                    local leaf = { value = "shared" }
                    return { leaf, leaf }
                  end,
                  aggregate_string_bytes = function()
                    local values = {}
                    for index = 1, 128 do values[index] = string.rep("x", 1024) end
                    return values
                  end,
                }
            "#,
        }),
        "entry",
    );

    for (callback, expected) in [
        ("nil_value", Value::Null),
        ("bool_value", json!(true)),
        ("number_value", json!(12.5)),
        ("utf8_value", json!("Καλημέρα")),
        ("string_value", json!("bounded string")),
        ("array_value", json!(["first", false, 3])),
        ("map_value", json!({"alpha": 1, "nested": {"beta": "two"}})),
    ] {
        let outcome = invoke(&state, callback);
        assert_kind(&outcome, OutcomeKind::Completed);
        assert_eq!(value(&outcome), expected, "{callback} was normalized incorrectly");
    }

    for callback in [
        "cycle",
        "metatable",
        "function_value",
        "thread",
        "non_finite",
        "invalid_utf8",
        "mixed",
        "sparse",
        "deep",
        "too_many_entries",
        "too_long_string",
        "shared_table_alias",
        "aggregate_string_bytes",
    ] {
        assert_invalid_value(&invoke(&state, callback));
    }

    let recovery = invoke(&state, "map_value");
    assert_kind(&recovery, OutcomeKind::Completed);
    assert_eq!(value(&recovery), json!({"alpha": 1, "nested": {"beta": "two"}}));
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn program_image_callbacks_interrupt_and_allocator_denial_without_stranding_close() {
    let interrupted = StateEngine::new(MEMORY_LIMIT, HOOK_INTERVAL, 1_000, 4, 4)
        .unwrap_or_else(|outcome| panic!("state creation failed: {:?}", outcome.to_json()));
    load_image(
        &interrupted,
        json!({
            "entry": r#"
                return {
                  startup = function() end,
                  loop = function() while true do end end,
                }
            "#,
        }),
        "entry",
    );
    assert_kind(&invoke(&interrupted, "loop"), OutcomeKind::Interrupted);
    assert_kind(&interrupted.close(interrupted.handle().generation), OutcomeKind::Closed);

    let denied = engine(4, 4);
    load_image(
        &denied,
        json!({
            "entry": r#"
                return {
                  startup = function() end,
                  allocate = function() return string.rep("x", 65536) end,
                }
            "#,
        }),
        "entry",
    );
    assert_kind(
        &denied.lower_memory_limit(denied.handle().generation, 1),
        OutcomeKind::Completed,
    );
    let allocation = invoke(&denied, "allocate");
    assert_kind(&allocation, OutcomeKind::MemoryFailure);
    assert!(
        allocation
            .to_json()
            .get("deniedAllocations")
            .and_then(Value::as_u64)
            .is_some_and(|count| count >= 1),
        "allocator denial was not surfaced in bridge telemetry: {:?}",
        allocation.to_json()
    );
    assert_kind(&denied.close(denied.handle().generation), OutcomeKind::Closed);
}

#[test]
fn runtime_spawn_sleep_context_bounds_and_raw_yield_boundary_are_observable_through_state_engine() {
    let context = engine(2, 2);
    load_image(
        &context,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                return {
                  startup = function() end,
                  invalid_context = function()
                    local spawn_value, spawn_error = runtime.spawn(function() end)
                    local sleep_value, sleep_error = runtime.sleep(1)
                    local invalid_spawn, invalid_spawn_error = runtime.spawn("not a function")
                    local invalid_sleep, invalid_sleep_error = runtime.sleep(-1)
                    return {
                      spawn_value_is_nil = spawn_value == nil,
                      spawn_error = spawn_error.error,
                      sleep_value_is_nil = sleep_value == nil,
                      sleep_error = sleep_error.error,
                      invalid_spawn_is_nil = invalid_spawn == nil,
                      invalid_spawn_error = invalid_spawn_error.error,
                      invalid_sleep_is_nil = invalid_sleep == nil,
                      invalid_sleep_error = invalid_sleep_error.error,
                    }
                  end,
                  child_spawn_observed = function()
                    local child = coroutine.create(function()
                      return runtime.spawn(function() end)
                    end)
                    local resumed, result, error = coroutine.resume(child)
                    return {
                      resumed = resumed,
                      result_is_nil = result == nil,
                      error = error.error,
                    }
                  end,
                  ignored_invalid_spawn = function()
                    runtime.spawn(function() end)
                    return { plugin_tried_to_hide = true }
                  end,
                  composition = function()
                    local child = coroutine.create(function()
                      coroutine.yield("child-yield")
                      return "child-complete"
                    end)
                    local first_ok, first = coroutine.resume(child)
                    local second_ok, second = coroutine.resume(child)
                    return {
                      first_ok = first_ok,
                      first = first,
                      second_ok = second_ok,
                      second = second,
                    }
                  end,
                  raw_yield = function() return coroutine.yield("escapes actor boundary") end,
                }
            "#,
        }),
        "entry",
    );
    let invalid_context = invoke(&context, "invalid_context");
    assert_kind(&invalid_context, OutcomeKind::Completed);
    assert_eq!(
        value(&invalid_context),
        json!({
            "spawn_value_is_nil": true,
            "spawn_error": "E_INVALID_CONTEXT",
            "sleep_value_is_nil": true,
            "sleep_error": "E_INVALID_CONTEXT",
            "invalid_spawn_is_nil": true,
            "invalid_spawn_error": "E_INVALID_ARGUMENT",
            "invalid_sleep_is_nil": true,
            "invalid_sleep_error": "E_INVALID_ARGUMENT",
        }),
    );
    let child_spawn = invoke(&context, "child_spawn_observed");
    assert_kind(&child_spawn, OutcomeKind::Completed);
    assert_eq!(
        value(&child_spawn),
        json!({
            "resumed": true,
            "result_is_nil": true,
            "error": "E_INVALID_CONTEXT",
        }),
        "an observed child-coroutine spawn violation must remain a normal E_INVALID_CONTEXT pair",
    );
    let ignored_spawn = invoke(&context, "ignored_invalid_spawn");
    assert_kind(&ignored_spawn, OutcomeKind::RuntimeFailure);
    assert!(
        diagnostic(&ignored_spawn).contains("E_INVALID_CONTEXT"),
        "a synchronous callback hid a prohibited spawn instead of latching its invocation: {:?}",
        ignored_spawn.to_json(),
    );
    assert!(
        ignored_spawn.to_json().get("value").is_none(),
        "a callback that ignores E_INVALID_CONTEXT returned a normal plugin value: {:?}",
        ignored_spawn.to_json(),
    );
    let composition = invoke(&context, "composition");
    assert_kind(&composition, OutcomeKind::Completed);
    assert_eq!(
        value(&composition),
        json!({
            "first_ok": true,
            "first": "child-yield",
            "second_ok": true,
            "second": "child-complete",
        }),
    );
    let raw_yield = invoke(&context, "raw_yield");
    assert_kind(&raw_yield, OutcomeKind::RuntimeFailure);
    assert!(diagnostic(&raw_yield).contains("E_INVALID_YIELD"));
    assert_kind(&context.close(context.handle().generation), OutcomeKind::Closed);

}

#[test]
fn admitted_startup_spawn_capacity_releases_after_completion_and_cancellation() {
    let state = engine(1, 2);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                local phase = 0
                return {
                  startup = function()
                    phase = phase + 1
                    local child
                    if phase == 2 then
                      child = function() return runtime.sleep(1) end
                    else
                      child = function() return "completed task" end
                    end
                    local first, first_error = runtime.spawn(child)
                    local second, second_error = runtime.spawn(function() return "must not run" end)
                    return {
                      phase = phase,
                      first = first,
                      first_error_is_nil = first_error == nil,
                      second_is_nil = second == nil,
                      second_error = second_error.error,
                    }
                  end,
                }
            "#,
        }),
        "entry",
    );
    let admit_all: Arc<dyn SpawnAdmitter> = Arc::new(|_: i64| SpawnAdmission::Accepted);
    let invoke_startup = || {
        state.invoke_callback_with_spawn_admitter(
            generation,
            "startup",
            "null",
            Arc::clone(&admit_all),
        )
    };
    let admitted_id = |outcome: &Outcome| {
        let json = outcome.to_json();
        let ids = json["spawnedCoroutines"]
            .as_array()
            .unwrap_or_else(|| panic!("accepted spawn omitted coroutine identity: {json:?}"));
        assert_eq!(ids.len(), 1, "capacity rejection retained an extra task: {json:?}");
        ids[0]
            .as_i64()
            .unwrap_or_else(|| panic!("spawned coroutine id was not an integer: {json:?}"))
    };
    let assert_capacity = |outcome: &Outcome, phase: i64| {
        assert_kind(outcome, OutcomeKind::Completed);
        assert_eq!(
            value(outcome),
            json!({
                "phase": phase,
                "first": true,
                "first_error_is_nil": true,
                "second_is_nil": true,
                "second_error": "E_BUSY",
            }),
            "native capacity did not synchronously reject the unretained extra coroutine",
        );
    };

    let first = invoke_startup();
    assert_capacity(&first, 1);
    let first_id = admitted_id(&first);
    let completed = state.start_coroutine_with_spawn_admitter(
        generation,
        first_id,
        Arc::clone(&admit_all),
    );
    assert_kind(&completed, OutcomeKind::Completed);
    assert_eq!(value(&completed), json!("completed task"));

    let sleeping = invoke_startup();
    assert_capacity(&sleeping, 2);
    let sleeping_id = admitted_id(&sleeping);
    let yielded = state.start_coroutine_with_spawn_admitter(
        generation,
        sleeping_id,
        Arc::clone(&admit_all),
    );
    assert_kind(&yielded, OutcomeKind::Yielded);
    let operation_id = yielded.to_json()["operationId"]
        .as_i64()
        .unwrap_or_else(|| panic!("sleeping task omitted operation identity: {:?}", yielded.to_json()));
    let cancelled = state.cancel_coroutine(generation, sleeping_id, operation_id);
    assert_kind(&cancelled, OutcomeKind::Cancelled);

    let after_cancel = invoke_startup();
    assert_capacity(&after_cancel, 3);
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn structured_logs_silently_drop_at_bound_and_reject_invalid_payloads_atomically() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local log = require("subspace.log")
                return {
                  startup = function() end,
                  probe = function()
                    local accepted = 0
                    for sequence = 1, 256 do
                      local ok, err = log.info({ message = "logged", sequence = sequence })
                      if ok and err == nil then accepted = accepted + 1 end
                    end
                    local rejected, rejected_error = log.warn({ bad = function() end })
                    return {
                      accepted = accepted,
                      rejected_is_nil = rejected == nil,
                      rejected_error = rejected_error.error,
                    }
                  end,
                  healthy = function() return { ready = true } end,
                }
            "#,
        }),
        "entry",
    );
    let probe = invoke(&state, "probe");
    assert_kind(&probe, OutcomeKind::Completed);
    assert_eq!(
        value(&probe),
        json!({
            "accepted": 256,
            "rejected_is_nil": true,
            "rejected_error": "E_INVALID_VALUE",
        }),
        "rate-dropped logs must still report (true, nil), while invalid payloads reject",
    );
    let probe_json = probe.to_json();
    let logs = probe_json
        .get("logs")
        .and_then(Value::as_array)
        .unwrap_or_else(|| panic!("accepted structured log was not observable: {probe_json:?}"));
    assert!(
        !logs.is_empty() && logs.len() < 256 && logs.len() <= 128,
        "valid logs were not bounded and silently dropped: {:?}",
        probe.to_json(),
    );
    for encoded in logs {
        let recorded: Value = serde_json::from_str(
            encoded
                .as_str()
                .unwrap_or_else(|| panic!("log was not encoded as JSON: {:?}", probe.to_json())),
        )
        .expect("recorded log must be valid JSON");
        assert_eq!(recorded["level"], json!("info"));
        assert_eq!(recorded["payload"]["message"], json!("logged"));
    }
    let healthy = invoke(&state, "healthy");
    assert_kind(&healthy, OutcomeKind::Completed);
    assert_eq!(value(&healthy), json!({"ready": true}));
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}
