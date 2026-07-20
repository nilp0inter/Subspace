use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use parking_lot::Mutex;

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
fn namespace_enumeration_has_only_allowed_modules_and_missing_require_is_side_effect_free() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local function keys(table_value)
                  local result = {}
                  for key in pairs(table_value) do result[#result + 1] = key end
                  table.sort(result)
                  return result
                end
                local preloaded = keys(subspace._preloaded)
                local globals = keys(_G)
                local loaded_before = keys(subspace._modules)
                local required, error_value = pcall(require, "missing.namespace.module")
                local loaded_after = keys(subspace._modules)
                local forbidden = {}
                local forbidden_roots = {
                  "http", "https", "filesystem", "fs", "file", "path", "lfs",
                  "socket", "tcp", "udp", "net", "network", "dns", "websocket",
                  "event", "events", "event_loop", "eventloop", "uv", "async",
                  "package", "persistent", "state", "storage", "database", "db",
                  "sqlite", "os", "io", "ffi", "debug",
                }
                local function inspect(names, source)
                  for _, name in ipairs(names) do
                    for _, root in ipairs(forbidden_roots) do
                      if name == root or string.sub(name, 1, #root + 1) == root .. "." then
                        forbidden[#forbidden + 1] = source .. ":" .. name
                      end
                    end
                  end
                end
                inspect(preloaded, "preload")
                inspect(globals, "global")
                inspect(loaded_after, "loaded")
                return {
                  startup = function() end,
                  probe = function()
                    return {
                      preloaded = preloaded,
                      globals = globals,
                      loaded_before = loaded_before,
                      loaded_after = loaded_after,
                      forbidden = forbidden,
                      missing_ok = required,
                      missing_error = tostring(error_value),
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
    assert_eq!(result["preloaded"], json!([
        "subspace.channel",
        "subspace.log",
        "subspace.playback",
        "subspace.runtime",
        "subspace.synthesis",
        "subspace.transcription",
    ]));
    assert_eq!(result["forbidden"], json!([]), "forbidden namespace entries: {result}");
    assert_eq!(result["loaded_before"], result["loaded_after"]);
    assert_eq!(result["missing_ok"], json!(false));
    assert!(
        result["missing_error"]
            .as_str()
            .is_some_and(|message| message.contains("E_MODULE_NOT_FOUND")),
        "missing module did not return the stable not-found error: {result}",
    );
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn loaded_package_images_expose_reserved_audio_module_functions() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local transcription = require("subspace.transcription")
                local synthesis = require("subspace.synthesis")
                local playback = require("subspace.playback")
                return {
                  startup = function() end,
                  probe = function()
                    return {
                      transcribe = type(transcription.transcribe),
                      synthesize = type(synthesis.synthesize),
                      schedule = type(playback.schedule),
                    }
                  end,
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
            "transcribe": "function",
            "synthesize": "function",
            "schedule": "function",
        }),
    );
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn reserved_semantic_module_names_are_rejected_from_program_images() {
    for invalid_name in [
        "subspace",
        "subspace.transcription",
        "subspace.synthesis",
        "subspace.playback",
    ] {
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
fn reserved_transcription_table_requires_but_rejects_calls_without_host_effects() {
    let state = engine(4, 4);
    load_image(
        &state,
        json!({
            "entry": r#"
                local transcription = require("subspace.transcription")
                return {
                  startup = function() end,
                  probe = function()
                    local result, err = transcription.transcribe("input")
                    return {
                      resolved = type(transcription) == "table",
                      callable = type(transcription.transcribe) == "function",
                      result_nil = result == nil,
                      error = err and err.error,
                    }
                  end,
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
            "resolved": true,
            "callable": true,
            "result_nil": true,
            "error": "E_INVALID_CONTEXT",
        }),
    );
    assert!(
        probe.to_json().get("logs").is_none(),
        "reserved transcription call retained a host effect: {:?}",
        probe.to_json()
    );
    assert!(
        probe.to_json().get("spawnedCoroutines").is_none(),
        "reserved transcription call admitted a host coroutine: {:?}",
        probe.to_json()
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
fn host_calls_during_entry_or_lazy_module_evaluation_fail_and_discard_the_complete_image() {
    for (name, effect) in [
        (
            "entry spawn",
            "local runtime = require('subspace.runtime'); runtime.spawn(function() end)",
        ),
        (
            "entry sleep",
            "local runtime = require('subspace.runtime'); runtime.sleep(1)",
        ),
        (
            "entry log",
            "local log = require('subspace.log'); log.info({message = 'during load'})",
        ),
    ] {
        let state = engine(4, 4);
        let source_map = serde_json::to_string(&json!({
            "entry": format!("{effect}; return {{ startup = function() end }}"),
        }))
        .expect("entry effect fixture must serialize");
        let failed = state.load_program_image(state.handle().generation, &source_map, "entry");
        assert_kind(&failed, OutcomeKind::RuntimeFailure);
        assert!(
            diagnostic(&failed).contains("effect-call-during-load"),
            "{name} did not fail with the load guard: {:?}",
            failed.to_json(),
        );
        assert!(
            failed.to_json().get("value").is_none(),
            "{name} returned a normal value instead of discarding the image: {:?}",
            failed.to_json(),
        );
        let after_failure = state.snapshot(state.handle().generation);
        assert_kind(&after_failure, OutcomeKind::Completed);
        assert_eq!(
            after_failure.to_json()["bridgeBytes"],
            json!(0),
            "{name} retained staged source bytes after rollback: {:?}",
            after_failure.to_json(),
        );

        load_image(
            &state,
            json!({"entry": "return { startup = function() end }"}),
            "entry",
        );
        let startup = invoke(&state, "startup");
        assert_kind(&startup, OutcomeKind::Completed);
        assert!(
            startup.to_json().get("spawnedCoroutines").is_none(),
            "failed {name} image retained an admitted child: {:?}",
            startup.to_json(),
        );
        assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
    }

    let state = engine(4, 4);
    let failed_lazy = serde_json::to_string(&json!({
        "entry": r#"
            local lazy = require("plugin.lazy")
            return { startup = function() end, handle_readiness = function() return { ready = lazy.value == "failed" } end }
        "#,
        "plugin.lazy": r#"
            local log = require("subspace.log")
            log.info({message = "lazy module effect"})
            return { value = "failed" }
        "#,
    }))
    .expect("lazy effect fixture must serialize");
    let failed = state.load_program_image(state.handle().generation, &failed_lazy, "entry");
    assert_kind(&failed, OutcomeKind::RuntimeFailure);
    assert!(diagnostic(&failed).contains("effect-call-during-load"));
    assert!(failed.to_json().get("value").is_none());
    let after_lazy_failure = state.snapshot(state.handle().generation);
    assert_kind(&after_lazy_failure, OutcomeKind::Completed);
    assert_eq!(
        after_lazy_failure.to_json()["bridgeBytes"],
        json!(0),
        "lazy module failure retained staged source bytes: {:?}",
        after_lazy_failure.to_json(),
    );

    // A successful replacement using the same module names proves the failed
    // lazy evaluation did not retain its callback table or module result.
    load_image(
        &state,
        json!({
            "entry": r#"
                local lazy = require("plugin.lazy")
                return {
                    startup = function() end,
                    handle_readiness = function() return { ready = lazy.value == "reloaded" } end,
                }
            "#,
            "plugin.lazy": "return { value = 'reloaded' }",
        }),
        "entry",
    );
    let readiness = invoke(&state, "handle_readiness");
    assert_kind(&readiness, OutcomeKind::Completed);
    assert_eq!(value(&readiness), json!({"ready": true}));
    assert!(readiness.to_json().get("spawnedCoroutines").is_none());
    assert!(readiness.to_json().get("logs").is_none());
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn invalid_callback_and_unmanaged_child_context_pairs_complete_without_effects() {
    let state = engine(2, 2);
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                return {
                    startup = function() end,
                    handle_sos = function()
                        local spawn_value, spawn_error = runtime.spawn(function() end)
                        local sleep_value, sleep_error = runtime.sleep(1)
                        local child = coroutine.create(function()
                            local child_spawn, child_spawn_error = runtime.spawn(function() end)
                            local child_sleep, child_sleep_error = runtime.sleep(1)
                            return {
                                spawn_nil = child_spawn == nil,
                                spawn_error = child_spawn_error.error,
                                sleep_nil = child_sleep == nil,
                                sleep_error = child_sleep_error.error,
                            }
                        end)
                        local resumed, child_result = coroutine.resume(child)
                        return {
                            spawn_nil = spawn_value == nil,
                            spawn_error = spawn_error.error,
                            sleep_nil = sleep_value == nil,
                            sleep_error = sleep_error.error,
                            child_resumed = resumed,
                            child_result = child_result,
                        }
                    end,
                }
            "#,
        }),
        "entry",
    );

    let outcome = invoke(&state, "handle_sos");
    assert_kind(&outcome, OutcomeKind::Completed);
    assert_eq!(
        value(&outcome),
        json!({
            "spawn_nil": true,
            "spawn_error": "E_INVALID_CONTEXT",
            "sleep_nil": true,
            "sleep_error": "E_INVALID_CONTEXT",
            "child_resumed": true,
            "child_result": {
                "spawn_nil": true,
                "spawn_error": "E_INVALID_CONTEXT",
                "sleep_nil": true,
                "sleep_error": "E_INVALID_CONTEXT",
            },
        }),
    );
    assert!(
        outcome.to_json().get("spawnedCoroutines").is_none(),
        "invalid contexts admitted a managed task: {:?}",
        outcome.to_json(),
    );
    assert!(
        outcome.to_json().get("logs").is_none(),
        "invalid contexts retained a host log effect: {:?}",
        outcome.to_json(),
    );
    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
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
                  child_sleep_observed = function()
                    local child = coroutine.create(function()
                      return runtime.sleep(1)
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
    let child_sleep = invoke(&context, "child_sleep_observed");
    assert_kind(&child_sleep, OutcomeKind::Completed);
    assert_eq!(
        value(&child_sleep),
        json!({
            "resumed": true,
            "result_is_nil": true,
            "error": "E_INVALID_CONTEXT",
        }),
        "a plugin-created child coroutine must not be able to start a managed sleep",
    );
    let ignored_spawn = invoke(&context, "ignored_invalid_spawn");
    assert_kind(&ignored_spawn, OutcomeKind::Completed);
    assert_eq!(
        value(&ignored_spawn),
        json!({"plugin_tried_to_hide": true}),
        "a synchronous callback may continue after an invalid-context pair: {:?}",
        ignored_spawn.to_json(),
    );
    assert!(ignored_spawn.to_json().get("spawnedCoroutines").is_none());
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
fn managed_tasks_can_spawn_descendants_and_invalid_runtime_arguments_do_not_suspend() {
    let state = engine(2, 1);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                return {
                  startup = function()
                    local admitted = runtime.spawn(function()
                      local child, child_error = runtime.spawn(function() return "grandchild" end)
                      return child and "child-spawned" or (child_error and child_error.error or "failed")
                    end)
                    return {admitted = admitted}
                  end,
                  invalid_arguments = function()
                    local spawn_string, spawn_string_error = runtime.spawn("not-a-function")
                    local spawn_number, spawn_number_error = runtime.spawn(42)
                    local spawn_extra, spawn_extra_error = runtime.spawn(function() end, "extra")
                    local negative, negative_error = runtime.sleep(-1)
                    local nan, nan_error = runtime.sleep(0 / 0)
                    local positive_infinity, positive_infinity_error = runtime.sleep(math.huge)
                    local too_long, too_long_error = runtime.sleep(86401)
                    return {
                      spawn_string = spawn_string,
                      spawn_string_error = spawn_string_error.error,
                      spawn_number = spawn_number,
                      spawn_number_error = spawn_number_error.error,
                      spawn_extra_error = spawn_extra_error.error,
                      negative = negative,
                      negative_error = negative_error.error,
                      nan = nan,
                      nan_error = nan_error.error,
                      positive_infinity = positive_infinity,
                      positive_infinity_error = positive_infinity_error.error,
                      too_long = too_long,
                      too_long_error = too_long_error.error,
                    }
                  end,
                }
            "#,
        }),
        "entry",
    );
    let startup = state.invoke_callback_with_spawn_admitter(
        generation,
        "startup",
        "null",
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&startup, OutcomeKind::Completed);
    assert_eq!(value(&startup), json!({"admitted": true}));
    let parent_id = startup.to_json()["spawnedCoroutines"][0].as_i64().unwrap();

    let parent = state.start_coroutine_with_spawn_admitter(
        generation,
        parent_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&parent, OutcomeKind::Completed);
    assert_eq!(value(&parent), json!("child-spawned"));
    let child_id = parent.to_json()["spawnedCoroutines"][0].as_i64().unwrap();
    let child = state.start_coroutine_with_spawn_admitter(
        generation,
        child_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&child, OutcomeKind::Completed);
    assert_eq!(value(&child), json!("grandchild"));

    let invalid = state.invoke_callback(generation, "invalid_arguments", "null");
    assert_kind(&invalid, OutcomeKind::Completed);
    assert_eq!(
        value(&invalid),
        json!({
            "spawn_string_error": "E_INVALID_ARGUMENT",
            "spawn_number_error": "E_INVALID_ARGUMENT",
            "spawn_extra_error": "E_INVALID_ARGUMENT",
            "negative_error": "E_INVALID_ARGUMENT",
            "nan_error": "E_INVALID_ARGUMENT",
            "positive_infinity_error": "E_INVALID_ARGUMENT",
            "too_long_error": "E_INVALID_ARGUMENT",
        }),
    );
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn managed_sleep_uses_timer_capacity_and_live_cancellation_delivery() {
    let state = engine(2, 1);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                return {
                  startup = function()
                    local first = runtime.spawn(function()
                      local ok, err = runtime.sleep(1)
                      return ok and "ok" or (err and err.error or "failed")
                    end)
                    local second = runtime.spawn(function()
                      local ok, err = runtime.sleep(1)
                      return ok and "ok" or (err and err.error or "failed")
                    end)
                    return {first = first, second = second}
                  end,
                }
            "#,
        }),
        "entry",
    );
    let startup = state.invoke_callback_with_spawn_admitter(
        generation,
        "startup",
        "null",
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&startup, OutcomeKind::Completed);
    let startup_json = startup.to_json();
    let ids = startup_json["spawnedCoroutines"].as_array().unwrap();
    assert_eq!(ids.len(), 2);

    let first_id = ids[0].as_i64().unwrap();
    let first = state.start_coroutine_with_spawn_admitter(
        generation,
        first_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&first, OutcomeKind::Yielded);
    let first_operation = first.to_json()["operationId"].as_i64().unwrap();
    let second_id = ids[1].as_i64().unwrap();
    let second = state.start_coroutine_with_spawn_admitter(
        generation,
        second_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&second, OutcomeKind::Completed);
    assert_eq!(value(&second), json!("E_BUSY"));

    let cancelled = state.resume_coroutine(
        generation,
        first_id,
        first_operation,
        false,
        "E_CANCELLED",
    );
    assert_kind(&cancelled, OutcomeKind::Completed);
    assert_eq!(value(&cancelled), json!("E_CANCELLED"));
    let duplicate = state.resume_coroutine(
        generation,
        first_id,
        first_operation,
        true,
        "",
    );
    assert_kind(&duplicate, OutcomeKind::Completed);
    assert_eq!(value(&duplicate), json!("E_CANCELLED"));
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn repeated_operation_specific_sleeps_survive_without_generic_task_expiry_and_close_blocks_resume() {
    let state = engine(1, 1);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")
                return {
                  startup = function()
                    return {admitted = runtime.spawn(function()
                      for _ = 1, 8 do
                        local ok, err = runtime.sleep(0)
                        if not ok then return err.error end
                      end
                      return "completed"
                    end)}
                  end,
                }
            "#,
        }),
        "entry",
    );
    let startup = state.invoke_callback_with_spawn_admitter(
        generation,
        "startup",
        "null",
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    let coroutine_id = startup.to_json()["spawnedCoroutines"][0].as_i64().unwrap();
    let mut outcome = state.start_coroutine_with_spawn_admitter(
        generation,
        coroutine_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    for _ in 0..8 {
        assert_kind(&outcome, OutcomeKind::Yielded);
        let operation_id = outcome.to_json()["operationId"].as_i64().unwrap();
        outcome = state.resume_coroutine(generation, coroutine_id, operation_id, true, "");
    }
    assert_kind(&outcome, OutcomeKind::Completed);
    assert_eq!(value(&outcome), json!("completed"));

    // A still-suspended operation is discarded by close; neither a late timer
    // completion nor an explicit resume may re-enter the Lua state.
    let second_start = state.invoke_callback_with_spawn_admitter(
        generation,
        "startup",
        "null",
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    let second_id = second_start.to_json()["spawnedCoroutines"][0].as_i64().unwrap();
    let second = state.start_coroutine_with_spawn_admitter(
        generation,
        second_id,
        Arc::new(|_| SpawnAdmission::Accepted),
    );
    assert_kind(&second, OutcomeKind::Yielded);
    let second_operation = second.to_json()["operationId"].as_i64().unwrap();
    assert_kind(&state.close(generation), OutcomeKind::Closed);
    let late = state.resume_coroutine(generation, second_id, second_operation, true, "");
    assert_kind(&late, OutcomeKind::Stale);
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

#[test]
fn captured_audio_userdata_input_callback_behavior() {
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                saved_userdata = nil

                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio_userdata = event.audio
                    saved_userdata = audio_userdata

                    if type(audio_userdata) ~= "userdata" then
                      return { error = { code = "E_TYPE", detail = "expected userdata, got " .. type(audio_userdata) } }
                    end
                    if tostring(audio_userdata) ~= "opaque_audio" then
                      return { error = { code = "E_TOSTRING", detail = "expected opaque_audio, got " .. tostring(audio_userdata) } }
                    end
                    if getmetatable(audio_userdata) ~= false then
                      return { error = { code = "E_METATABLE", detail = "expected locked metatable" } }
                    end

                    -- Verify no properties can be accessed or set
                    local ok, res = pcall(function() return audio_userdata.token end)
                    if ok then
                      return { error = { code = "E_PROPERTY_GET", detail = "property get succeeded" } }
                    end
                    local ok_write, res_write = pcall(function() audio_userdata.token = "new_token" end)
                    if ok_write then
                      return { error = { code = "E_PROPERTY_SET", detail = "property set succeeded" } }
                    end
                    local ok_setmt, res_setmt = pcall(function() setmetatable(audio_userdata, {}) end)
                    if ok_setmt then
                      return { error = { code = "E_SETMETATABLE", detail = "setmetatable succeeded" } }
                    end

                    return { ok = true }
                  end,

                  get_saved_tostring = function()
                    return tostring(saved_userdata)
                  end,

                  get_saved_userdata_directly = function()
                    return saved_userdata
                  end,

                  get_saved_userdata_nested = function()
                    return { ok = true, data = saved_userdata }
                  end,
                }
            "#,
        }),
        "entry",
    );

    let admit_all: Arc<dyn SpawnAdmitter> = Arc::new(|_: i64| SpawnAdmission::Accepted);

    // 1. Invoke input callback, passing arguments and a token.
    let outcome = state.invoke_input_callback_with_spawn_admitter(
        generation,
        r#"{"foo": "bar"}"#,
        "secret_token_12345",
        Arc::clone(&admit_all),
    );

    assert_kind(&outcome, OutcomeKind::Completed);
    assert_eq!(value(&outcome), json!({"ok": true}));

    // 2. Verify that tostring does not expose the token
    let tostring_outcome = invoke(&state, "get_saved_tostring");
    assert_kind(&tostring_outcome, OutcomeKind::Completed);
    assert_eq!(value(&tostring_outcome), json!("opaque_audio"));

    // 3. Verify that returning invalid user data directly or nested is not normalized (rejection with E_INVALID_VALUE)
    let direct_outcome = invoke(&state, "get_saved_userdata_directly");
    assert_invalid_value(&direct_outcome);

    let nested_outcome = invoke(&state, "get_saved_userdata_nested");
    assert_invalid_value(&nested_outcome);

    assert_kind(&state.close(state.handle().generation), OutcomeKind::Closed);
}

#[test]
fn opaque_audio_userdata_is_rejected_atomically_across_callback_config_errors_and_logs() {
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                saved_audio = nil

                return {
                  startup = function(config)
                    -- Startup is also a generic configuration/result boundary.
                    return { config = saved_audio }
                  end,
                  handle_input = function(event)
                    local audio = event.audio
                    saved_audio = audio
                    return { ok = true }
                  end,
                  handle_readiness = function()
                    -- A plain callback table must not be partially serialized.
                    return { ready = true, payload = saved_audio, after = "must not escape" }
                  end,
                  handle_lifecycle = function()
                    -- Structured callback errors are subject to the same rejection.
                    return { error = { code = "E_AUDIO", detail = saved_audio } }
                  end,
                  handle_sos = function()
                    local ok, err = require("subspace.log").info({
                      message = "before opaque audio",
                      audio = saved_audio,
                    })
                    return {
                      log_rejected = ok == nil,
                      log_error = err and err.error,
                    }
                  end,
                }
            "#,
        }),
        "entry",
    );

    let admit_all: Arc<dyn SpawnAdmitter> = Arc::new(|_: i64| SpawnAdmission::Accepted);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        r#"{"source":"captured"}"#,
        "opaque-token-for-rejection-test",
        Arc::clone(&admit_all),
    );
    assert_kind(&input, OutcomeKind::Completed);
    assert_eq!(value(&input), json!({"ok": true}));

    let startup = state.invoke_callback(generation, "startup", r#"{"schema_version":1}"#);
    assert_invalid_value(&startup);

    let readiness = state.invoke_callback(generation, "handle_readiness", "null");
    assert_invalid_value(&readiness);

    let lifecycle = state.invoke_callback(generation, "handle_lifecycle", "null");
    assert_invalid_value(&lifecycle);

    let sos = state.invoke_callback(generation, "handle_sos", "null");
    assert_kind(&sos, OutcomeKind::Completed);
    assert_eq!(
        value(&sos),
        json!({"log_rejected": true, "log_error": "E_INVALID_VALUE"}),
        "structured logging must return an error pair without failing the callback",
    );
    assert!(
        sos.to_json().get("logs").is_none(),
        "invalid userdata payload must not be partially persisted as a log: {:?}",
        sos.to_json(),
    );

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn native_input_userdata_boundary_consults_admission_only_at_runtime_spawn_and_never_leaks_token() {
    // Boundary under test:
    //
    //   host (Kotlin)
    //     OpaqueAudioRegistry.admitCaptured(owner, recording) -> Token?
    //       null on rejection (capacity / closed / unknown owner)
    //       => LuaAdapterRuntime returns ChannelInputResult.None WITHOUT
    //          crossing into the Rust crate. No userdata, no handle_input.
    //     non-null token
    //       => LuaNativeKernelBridge.invokeInputCallback
    //          => JNI Java_..._nativeInvokeInputCallback
    //             => StateEngine::invoke_input_callback_with_spawn_admitter
    //
    // The Rust crate exposes invoke_input_callback_with_spawn_admitter as the
    // userdata construction boundary: it ALWAYS builds the captured audio
    // userdata from the supplied token and resumes handle_input. The
    // SpawnAdmitter argument is consulted only if Lua calls runtime.spawn
    // inside handle_input; it is never read before userdata construction.
    // This test pins that boundary so any future change pushing host audio
    // admission into the Rust crate must update or remove these assertions.
    //
    // Consequences verified below for every SpawnAdmission variant:
    //   1. handle_input is invoked and the userdata is direct opaque userdata.
    //   2. The host SpawnAdmitter closure is never invoked (input callbacks
    //      have no spawn authority, so the callback-context guard rejects
    //      runtime.spawn before the admitter is reached).
    //   3. The token never leaks (tostring, metatable, properties, JSON echo).
    //   4. runtime.spawn inside handle_input returns E_INVALID_CONTEXT
    //      regardless of the admission variant.
    //   5. Accepted admission does not grant userdata escape authority.
    //
    // LIMITATION: this test cannot prove "rejected host audio admission does
    // not invoke handle_input/create userdata" at the Rust API, because that
    // gate lives entirely in the Kotlin OpaqueAudioRegistry before the JNI
    // call. There is no Rust-side seam short of inventing a fake quota
    // implementation, which the task explicitly forbids. Kotlin coverage for
    // that gate is provided by OpaqueAudioQuotaTests.

    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(
        &state,
        json!({
            "entry": r#"
                local runtime = require("subspace.runtime")

                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio_userdata = event.audio
                    -- The value crossing the boundary must be direct opaque
                    -- userdata, never a token string, scalar, or table that a
                    -- plugin could forge or rewrap.
                    local report = {
                      invoked = true,
                      type = type(audio_userdata),
                      args_source = event.source,
                      tostring = tostring(audio_userdata),
                      metatable_locked = getmetatable(audio_userdata) == false,
                    }

                    -- The token must not be readable or writable through the
                    -- userdata; the metatable must not be replaceable.
                    local ok_get = pcall(function() return audio_userdata.token end)
                    report.property_get_blocked = not ok_get
                    local ok_set = pcall(function() audio_userdata.token = "leak" end)
                    report.property_set_blocked = not ok_set
                    local ok_mt = pcall(function() setmetatable(audio_userdata, {}) end)
                    report.setmetatable_blocked = not ok_mt

                    -- Input callbacks carry no spawn authority. runtime.spawn
                    -- must be denied by the callback-context guard before the
                    -- host SpawnAdmitter is consulted, regardless of variant.
                    local spawn_ok, spawn_err = runtime.spawn(function() end)
                    report.spawn_admitted = (spawn_ok == true)
                    report.spawn_error = spawn_err and spawn_err.error

                    return report
                  end,
                }
            "#,
        }),
        "entry",
    );

    let secret_token = "HOST_ADMISSION_TOKEN_MUST_NOT_LEAK_1234567890";

    for (label, admission) in [
        ("Accepted", SpawnAdmission::Accepted),
        ("Rejected", SpawnAdmission::Rejected),
        ("Closed", SpawnAdmission::Closed),
        ("Capacity", SpawnAdmission::Capacity),
    ] {
        let consulted = Arc::new(AtomicUsize::new(0));
        let consulted_for_closure = Arc::clone(&consulted);
        let admitter: Arc<dyn SpawnAdmitter> = Arc::new(move |_coroutine_id: i64| {
            consulted_for_closure.fetch_add(1, Ordering::SeqCst);
            admission
        });

        let outcome = state.invoke_input_callback_with_spawn_admitter(
            generation,
            &format!(r#"{{"source":"{label}"}}"#),
            secret_token,
            Arc::clone(&admitter),
        );

        // (1) LIMITATION: the SpawnAdmission decision does not gate userdata
        // construction or handle_input invocation at the native Rust API.
        // Host audio admission lives in the Kotlin registry, before the JNI
        // call. handle_input completes regardless of the variant here.
        assert_kind(&outcome, OutcomeKind::Completed);
        let report = value(&outcome);
        assert_eq!(
            report["invoked"],
            json!(true),
            "SpawnAdmission={label}: handle_input must be invoked; admission lives in the Kotlin registry, not the Rust API",
        );
        assert_eq!(
            report["args_source"],
            json!(label),
            "SpawnAdmission={label}: arguments must reach handle_input unmodified",
        );

        // (2) LIMITATION: the SpawnAdmitter closure is never consulted during
        // an input callback. Input callbacks carry no spawn authority, so the
        // callback-context guard at host_spawn rejects runtime.spawn before
        // the admitter would be reached. This is the only Rust-side admission
        // seam available; exercising a pre-userdata seam would require a fake
        // quota implementation, which the task forbids.
        assert_eq!(
            consulted.load(Ordering::SeqCst),
            0,
            "SpawnAdmission={label}: SpawnAdmitter must not be consulted unless Lua calls runtime.spawn with spawn authority",
        );

        assert_eq!(
            report["type"],
            json!("userdata"),
            "SpawnAdmission={label}: handle_input must receive full userdata, report={report:?}",
        );
        assert_eq!(
            report["tostring"],
            json!("opaque_audio"),
            "SpawnAdmission={label}: tostring must not leak the token",
        );
        assert_eq!(
            report["metatable_locked"],
            json!(true),
            "SpawnAdmission={label}: userdata metatable must remain locked",
        );
        assert_eq!(
            report["property_get_blocked"],
            json!(true),
            "SpawnAdmission={label}: property get must be blocked",
        );
        assert_eq!(
            report["property_set_blocked"],
            json!(true),
            "SpawnAdmission={label}: property set must be blocked",
        );
        assert_eq!(
            report["setmetatable_blocked"],
            json!(true),
            "SpawnAdmission={label}: setmetatable must be blocked",
        );

        // (4) runtime.spawn denial is admission-independent inside handle_input.
        assert_eq!(
            report["spawn_admitted"],
            json!(false),
            "SpawnAdmission={label}: runtime.spawn must be denied inside handle_input regardless of admission",
        );
        assert_eq!(
            report["spawn_error"],
            json!("E_INVALID_CONTEXT"),
            "SpawnAdmission={label}: callback-context guard must reject spawn before SpawnAdmitter is consulted",
        );

        // The raw token string must not appear anywhere in the outcome JSON.
        let outcome_str = outcome.to_json().to_string();
        assert!(
            !outcome_str.contains(secret_token),
            "SpawnAdmission={label}: token leaked into outcome JSON: {outcome_str}",
        );
    }

    // (5) Accepted admission does not grant userdata escape authority. Even
    // when the host admits the slice, the userdata cannot leave the runtime
    // through a return value (direct or nested) — it is rejected atomically
    // as E_INVALID_VALUE. The token therefore cannot be smuggled out.
    let escape_state = engine(4, 4);
    let escape_generation = escape_state.handle().generation;
    load_image(
        &escape_state,
        json!({
            "entry": r#"
                local saved
                return {
                  startup = function() end,
                  handle_input = function(event)
                    local audio = event.audio
                    saved = audio
                    return { ok = true }
                  end,
                  leak_direct = function() return saved end,
                  leak_nested = function() return { payload = saved } end,
                }
            "#,
        }),
        "entry",
    );
    let admit_all: Arc<dyn SpawnAdmitter> = Arc::new(|_| SpawnAdmission::Accepted);
    let accepted = escape_state.invoke_input_callback_with_spawn_admitter(
        escape_generation,
        r#"{"source":"captured"}"#,
        secret_token,
        Arc::clone(&admit_all),
    );
    assert_kind(&accepted, OutcomeKind::Completed);
    assert_eq!(value(&accepted), json!({"ok": true}));

    let direct = escape_state.invoke_callback(escape_generation, "leak_direct", "null");
    assert_invalid_value(&direct);
    assert!(
        !direct.to_json().to_string().contains(secret_token),
        "Accepted admission: token leaked through direct return: {:?}",
        direct.to_json(),
    );

    let nested = escape_state.invoke_callback(escape_generation, "leak_nested", "null");
    assert_invalid_value(&nested);
    assert!(
        !nested.to_json().to_string().contains(secret_token),
        "Accepted admission: token leaked through nested return: {:?}",
        nested.to_json(),
    );

    assert_kind(&state.close(generation), OutcomeKind::Closed);
    assert_kind(&escape_state.close(escape_generation), OutcomeKind::Closed);
}


#[test]
fn transcription_input_borrows_userdata_and_normalizes_validation_and_failures() {
    struct TranscriptionAdmitter { probes: AtomicUsize }
    impl SpawnAdmitter for TranscriptionAdmitter {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_transcription(&self, operation_id: i64, token: String) -> i32 {
            assert_eq!(token, "captured-token");
            if operation_id == 0 { self.probes.fetch_add(1, Ordering::SeqCst); }
            0
        }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local t = require("subspace.transcription")
        return {
          startup = function() end,
          outside = function() local x,e=t.transcribe("bad") return {text=x,error=e and e.error} end,
          handle_input = function(event)
            if event and event.invalid then local x,e=t.transcribe("bad") return {text=x,error=e and e.error} end
            if event and event.fail then local x,e=t.transcribe(event.audio) return {text=x,error=e and e.error} end
            local x,e=t.transcribe(event.audio); local y,f=t.transcribe(event.audio)
            return {first=x,first_error=e,second=y,second_error=f}
          end,
        }
    "#}), "entry");
    let outside = invoke(&state, "outside");
    assert_kind(&outside, OutcomeKind::Completed);
    assert_eq!(value(&outside), json!({"error": "E_INVALID_CONTEXT"}));
    let host = Arc::new(TranscriptionAdmitter { probes: AtomicUsize::new(0) });
    let input = state.invoke_input_callback_with_spawn_admitter(generation, "{}", "captured-token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&input, OutcomeKind::Yielded);
    let coroutine = input.to_json()["coroutineId"].as_i64().unwrap();
    let operation = input.to_json()["operationId"].as_i64().unwrap();
    let first = state.resume_coroutine_with_spawn_admitter(generation, coroutine, operation, true, "héllo", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&first, OutcomeKind::Yielded);
    let second = state.resume_coroutine_with_spawn_admitter(generation, coroutine, first.to_json()["operationId"].as_i64().unwrap(), true, "world", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&second, OutcomeKind::Completed);
    assert_eq!(value(&second), json!({"first":{"text":"héllo"},"second":{"text":"world"}}));
    assert_eq!(host.probes.load(Ordering::SeqCst), 2);

    let invalid = state.invoke_input_callback_with_spawn_admitter(generation, r#"{"invalid":true}"#, "captured-token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&invalid, OutcomeKind::Completed);
    assert_eq!(value(&invalid), json!({"error":"E_INVALID_ARGUMENT"}));
    assert_eq!(host.probes.load(Ordering::SeqCst), 2);

    let allowed = [
        "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
        "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
    ];
    for injected in allowed.into_iter().chain([
        "panic: /endpoint=https://secret.example credential=top-secret transport reset",
        "unknown backend detail",
    ]) {
        let failed = state.invoke_input_callback_with_spawn_admitter(
            generation, "{\"fail\":true}", "captured-token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&failed, OutcomeKind::Yielded);
        let failed_result = state.resume_coroutine(
            generation,
            failed.to_json()["coroutineId"].as_i64().unwrap(),
            failed.to_json()["operationId"].as_i64().unwrap(),
            false,
            injected,
        );
        assert_kind(&failed_result, OutcomeKind::Completed);
        let expected = if injected.starts_with("E_") { injected } else { "E_HOST_FAILURE" };
        assert_eq!(value(&failed_result), json!({"error": expected}), "injected={injected}");
        assert!(!failed_result.to_json().to_string().contains("secret.example"));
        assert!(!failed_result.to_json().to_string().contains("top-secret"));
        assert!(!failed_result.to_json().to_string().contains("transport reset"));
    }
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn transcription_non_userdata_in_input_is_invalid_argument_without_host_admission() {
    struct RejectingAdmitter(AtomicUsize);
    impl SpawnAdmitter for RejectingAdmitter {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_transcription(&self, _: i64, _: String) -> i32 { self.0.fetch_add(1, Ordering::SeqCst); 0 }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local t = require("subspace.transcription")
        return { startup = function() end, handle_input = function(event) local x,e=t.transcribe("bad") return {x=x,error=e and e.error} end }
    "#}), "entry");
    let host = Arc::new(RejectingAdmitter(AtomicUsize::new(0)));
    let outcome = state.invoke_input_callback_with_spawn_admitter(generation, "{}", "token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&outcome, OutcomeKind::Completed);
    assert_eq!(value(&outcome), json!({"error":"E_INVALID_ARGUMENT"}));
    assert_eq!(host.0.load(Ordering::SeqCst), 0);
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn opaque_audio_invalid_ownership_status_maps_to_invalid_argument_without_backend_admission() {
    struct Rejecting { probes: AtomicUsize }
    impl SpawnAdmitter for Rejecting {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_transcription(&self, _: i64, _: String) -> i32 { self.probes.fetch_add(1, Ordering::SeqCst); 3 }
        fn admit_playback(&self, _: i64, _: String, _: f64) -> i32 { self.probes.fetch_add(1, Ordering::SeqCst); 3 }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"local t=require("subspace.transcription"); return {startup=function()end,handle_input=function(event) local _,e=t.transcribe(event.audio); return {error=e and e.error} end}"#}), "entry");
    let host = Arc::new(Rejecting { probes: AtomicUsize::new(0) });
    let out = state.invoke_input_callback_with_spawn_admitter(generation, "{}", "foreign-owner", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&out, OutcomeKind::Completed);
    assert_eq!(value(&out), json!({"error":"E_INVALID_ARGUMENT"}));
    assert_eq!(host.probes.load(Ordering::SeqCst), 1);
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn synthesis_operation_validates_parameters_and_resumes_opaque_userdata() {
    struct SynthesisAdmitter { admissions: AtomicUsize, params: Mutex<Vec<String>> }
    impl SpawnAdmitter for SynthesisAdmitter {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, params: String) -> i32 {
            self.admissions.fetch_add(1, Ordering::SeqCst);
            self.params.lock().push(params);
            0
        }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local s = require("subspace.synthesis")
        return {
          startup = function() end,
          outside = function() local x,e=s.synthesize({text="x",language="en-US",voice="v"}); return e and e.error end,
          handle_input = function(event)
            local a = event or {}
            local p
            if a.k == "missing_text" then p={language="en-US",voice="v"}
            elseif a.k == "extra" then p={text="x",language="en-US",voice="v",extra="x"}
            elseif a.k == "blank_text" then p={text=" ",language="en-US",voice="v"}
            elseif a.k == "long_text" then p={text=string.rep("x",16385),language="en-US",voice="v"}
            elseif a.k == "bad_language" then p={text="x",language="en_US",voice="v"}
            elseif a.k == "missing_voice" then p={text="x",language="en-US"}
            elseif a.k == "blank_voice" then p={text="x",language="en-US",voice=" "}
            elseif a.k == "long_voice" then p={text="x",language="en-US",voice=string.rep("v",129)}
            elseif a.k == "nan" then p={text="x",language="en-US",voice="v",speed=0/0}
            elseif a.k == "infinite" then p={text="x",language="en-US",voice="v",speed=1/0}
            elseif a.k == "zero" then p={text="x",language="en-US",voice="v",speed=0}
            elseif a.k == "negative" then p={text="x",language="en-US",voice="v",speed=-1}
            else p={text="hello",language="en-US",voice="v"} end
            local x,e=s.synthesize(p)
            if e then return {error=e.error} end
            return {text=tostring(x), meta=getmetatable(x)}
          end,
        }
    "#}), "entry");
    let outside = invoke(&state, "outside");
    assert_kind(&outside, OutcomeKind::Completed);
    assert_eq!(value(&outside), json!("E_INVALID_CONTEXT"));
    let host = Arc::new(SynthesisAdmitter { admissions: AtomicUsize::new(0), params: Mutex::new(Vec::new()) });
    for k in ["missing_text","extra","blank_text","long_text","bad_language","missing_voice","blank_voice","long_voice","nan","infinite","zero","negative"] {
        let out = state.invoke_input_callback_with_spawn_admitter(generation, &format!(r#"{{"k":"{k}"}}"#), "token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
        assert_kind(&out, OutcomeKind::Completed);
        assert_eq!(value(&out), json!({"error":"E_INVALID_ARGUMENT"}), "case {k}");
    }
    assert_eq!(host.admissions.load(Ordering::SeqCst), 0);
    let valid = state.invoke_input_callback_with_spawn_admitter(generation, r#"{"k":"valid"}"#, "token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&valid, OutcomeKind::Yielded);
    assert_eq!(host.admissions.load(Ordering::SeqCst), 1);
    let params: Value = serde_json::from_str(&host.params.lock()[0]).unwrap();
    assert_eq!(params["speed"], json!(1.0));
    assert_eq!(params["text"], json!("hello"));
    let resumed = state.resume_coroutine_with_spawn_admitter(generation, valid.to_json()["coroutineId"].as_i64().unwrap(), valid.to_json()["operationId"].as_i64().unwrap(), true, "synthesized:result", Arc::clone(&host) as Arc<dyn SpawnAdmitter>);
    assert_kind(&resumed, OutcomeKind::Completed);
    assert_eq!(value(&resumed), json!({"text":"opaque_audio","meta":false}));
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn synthesis_host_failure_resumes_normalized_error() {
    struct Failing;
    impl SpawnAdmitter for Failing {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, _: String) -> i32 { 0 }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"local s=require("subspace.synthesis"); return {startup=function()end,handle_input=function() local x,e=s.synthesize({text="x",language="en-US",voice="v"}); return {error=e and e.error} end}"#}), "entry");
    let host = Arc::new(Failing);
    let allowed = [
        "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
        "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
    ];
    for injected in allowed.into_iter().chain([
        "exception-like: /endpoint=https://secret.example credential=top-secret transport reset",
        "provider failure detail",
    ]) {
        let yielded = state.invoke_input_callback_with_spawn_admitter(
            generation, "{}", "token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&yielded, OutcomeKind::Yielded);
        let failed = state.resume_coroutine(
            generation,
            yielded.to_json()["coroutineId"].as_i64().unwrap(),
            yielded.to_json()["operationId"].as_i64().unwrap(),
            false,
            injected,
        );
        assert_kind(&failed, OutcomeKind::Completed);
        let expected = if injected.starts_with("E_") { injected } else { "E_HOST_FAILURE" };
        assert_eq!(value(&failed), json!({"error": expected}), "injected={injected}");
        assert!(!failed.to_json().to_string().contains("secret.example"));
        assert!(!failed.to_json().to_string().contains("top-secret"));
        assert!(!failed.to_json().to_string().contains("transport reset"));
    }
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn playback_schedule_validates_options_before_admission_and_returns_exact_status() {
    struct PlaybackAdmitter { admissions: AtomicUsize }
    impl SpawnAdmitter for PlaybackAdmitter {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_playback(&self, _: i64, _: String, _: f64) -> i32 {
            self.admissions.fetch_add(1, Ordering::SeqCst);
            0
        }
    }

    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local p = require("subspace.playback")
        return {
          startup = function() end,
          handle_input = function(event)
            local audio = event.audio
            local args = event or {}
            local options
            if args and args.case == "extra" then options = {delay_seconds=1, extra=true}
            elseif args and args.case == "non_table" then options = "bad"
            elseif args and args.case == "nan" then options = {delay_seconds=0/0}
            elseif args and args.case == "infinite" then options = {delay_seconds=1/0}
            elseif args and args.case == "negative" then options = {delay_seconds=-1}
            elseif args and args.case == "oversize" then options = {delay_seconds=86401}
            elseif args and args.case == "missing" then options = nil
            else options = {delay_seconds=1} end
            local result, error = p.schedule(audio, options)
            if error then return {error=error.error} end
            return result
          end,
          raw_yield = function() return coroutine.yield("untagged") end,
        }
    "#}), "entry");

    let host = Arc::new(PlaybackAdmitter { admissions: AtomicUsize::new(0) });
    for case in ["missing", "extra", "non_table", "nan", "infinite", "negative", "oversize"] {
        let out = state.invoke_input_callback_with_spawn_admitter(
            generation, &format!(r#"{{"case":"{case}"}}"#), "captured-token",
            Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&out, OutcomeKind::Completed);
        assert_eq!(value(&out), json!({"error": if case == "negative" || case == "oversize" { "E_INVALID_VALUE" } else { "E_INVALID_ARGUMENT" }}), "case {case}");
    }
    assert_eq!(host.admissions.load(Ordering::SeqCst), 0);

    let valid = state.invoke_input_callback_with_spawn_admitter(
        generation, "{}", "captured-token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&valid, OutcomeKind::Yielded);
    assert_eq!(host.admissions.load(Ordering::SeqCst), 1);
    let resumed = state.resume_coroutine_with_spawn_admitter(
        generation,
        valid.to_json()["coroutineId"].as_i64().unwrap(),
        valid.to_json()["operationId"].as_i64().unwrap(),
        true,
        "ignored",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&resumed, OutcomeKind::Completed);
    assert_eq!(value(&resumed), json!({"status":"scheduled"}));
    let allowed = [
        "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
        "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE",
    ];
    for injected in allowed.into_iter().chain([
        "exception-like: /endpoint=https://secret.example credential=top-secret transport reset",
        "playback backend detail",
    ]) {
        let yielded = state.invoke_input_callback_with_spawn_admitter(
            generation, "{}", "captured-token", Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&yielded, OutcomeKind::Yielded);
        let failed = state.resume_coroutine(
            generation,
            yielded.to_json()["coroutineId"].as_i64().unwrap(),
            yielded.to_json()["operationId"].as_i64().unwrap(),
            false,
            injected,
        );
        assert_kind(&failed, OutcomeKind::Completed);
        let expected = if injected.starts_with("E_") { injected } else { "E_HOST_FAILURE" };
        assert_eq!(value(&failed), json!({"error": expected}), "injected={injected}");
        assert!(!failed.to_json().to_string().contains("secret.example"));
        assert!(!failed.to_json().to_string().contains("top-secret"));
        assert!(!failed.to_json().to_string().contains("transport reset"));
    }
 

    let untagged = state.invoke_callback(generation, "raw_yield", "null");
    assert_kind(&untagged, OutcomeKind::RuntimeFailure);
    assert!(diagnostic(&untagged).contains("E_INVALID_YIELD"));
    assert_eq!(host.admissions.load(Ordering::SeqCst), 14);
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn detached_snapshots_reflect_lifecycle_and_reject_foreign_generations() {
    let state = engine(4, 4);
    let generation = state.handle().generation;

    // Fresh state: snapshot shows zero accounting
    let fresh = state.snapshot(generation);
    assert_kind(&fresh, OutcomeKind::Completed);
    assert_eq!(fresh.to_json()["bridgeBytes"], json!(0));

    // After loading an image: snapshot shows positive bridge bytes
    load_image(
        &state,
        json!({"entry": "return { startup = function() end }"}),
        "entry",
    );
    let loaded = state.snapshot(generation);
    assert_kind(&loaded, OutcomeKind::Completed);
    assert!(
        loaded.to_json()["bridgeBytes"].as_u64().unwrap_or(0) > 0,
        "snapshot after image load must report positive bridge bytes: {:?}",
        loaded.to_json(),
    );

    // After close: snapshot returns Stale (the generation is no longer live)
    assert_kind(&state.close(generation), OutcomeKind::Closed);
    let after_close = state.snapshot(generation);
    assert_kind(&after_close, OutcomeKind::Stale);

    // Foreign generation on this state returns Stale
    let other = engine(4, 4);
    let other_gen = other.handle().generation;
    let foreign = state.snapshot(other_gen);
    assert_kind(&foreign, OutcomeKind::Stale);

    assert_kind(&other.close(other_gen), OutcomeKind::Closed);
}

#[test]
fn audio_wrong_kind_is_rejected_before_host_admission() {
    struct WrongKindGuard;
    impl SpawnAdmitter for WrongKindGuard {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, _: String) -> i32 { 0 }
    }

    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local s = require("subspace.synthesis")
        local t = require("subspace.transcription")
        return {
          startup = function() end,
          handle_input = function(event)
            local syn, syn_err = s.synthesize({text="hello", language="en-US", voice="v"})
            if syn_err then return {error = "synthesis-failed:" .. syn_err.error} end
            local x, e = t.transcribe(syn)
            return {error = e and e.error}
          end,
        }
    "#}), "entry");

    let host = Arc::new(WrongKindGuard);
    let outcome = state.invoke_input_callback_with_spawn_admitter(
        generation, "{}", "captured-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    // First yield from synthesis.synthesize
    assert_kind(&outcome, OutcomeKind::Yielded);
    let coroutine_id = outcome.to_json()["coroutineId"].as_i64().unwrap();
    let operation_id = outcome.to_json()["operationId"].as_i64().unwrap();

    // Resume synthesis: creates synthesized audio userdata
    let resumed = state.resume_coroutine_with_spawn_admitter(
        generation, coroutine_id, operation_id, true,
        "synthesized:wrong-kind-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    // The transcribe call with synthesized (wrong-kind) userdata must fail
    // before host admission, returning E_INVALID_VALUE from the kind check
    // without invoking admit_transcription.
    assert_kind(&resumed, OutcomeKind::Completed);
    assert_eq!(
        value(&resumed),
        json!({"error": "E_INVALID_VALUE"}),
        "wrong-kind audio passed to transcription must be rejected as E_INVALID_VALUE",
    );

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn audio_admission_quota_stale_closed_mapped_before_yield() {
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local t = require("subspace.transcription")
        local s = require("subspace.synthesis")
        local p = require("subspace.playback")
        return {
          startup = function() end,
          handle_input = function(event)
            local args = event or {}
            if args.op == "quota" then
              local x, e = t.transcribe(event.audio)
              return {error = e and e.error}
            elseif args.op == "stale" then
              local x, e = t.transcribe(event.audio)
              return {error = e and e.error}
            elseif args.op == "closed" then
              local result, e = p.schedule(event.audio, {delay_seconds=0})
              if e then return {error = e and e.error} end
              return result
            else
              return {error = "unknown-op"}
            end
          end,
        }
    "#}), "entry");

    // E_BUSY from transcription admitter (status 2)
    {
        struct BusyAdmitter;
        impl SpawnAdmitter for BusyAdmitter {
            fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
            fn admit_transcription(&self, _: i64, _: String) -> i32 { 2 }
        }
        let host = Arc::new(BusyAdmitter);
        let out = state.invoke_input_callback_with_spawn_admitter(
            generation, r#"{"op":"quota"}"#, "busy-token",
            Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&out, OutcomeKind::Completed);
        assert_eq!(value(&out), json!({"error":"E_BUSY"}));
    }

    // E_STALE from transcription admitter (status 5)
    {
        struct StaleAdmitter;
        impl SpawnAdmitter for StaleAdmitter {
            fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
            fn admit_transcription(&self, _: i64, _: String) -> i32 { 5 }
        }
        let host = Arc::new(StaleAdmitter);
        let out = state.invoke_input_callback_with_spawn_admitter(
            generation, r#"{"op":"stale"}"#, "stale-token",
            Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&out, OutcomeKind::Completed);
        assert_eq!(value(&out), json!({"error":"E_STALE"}));
    }

    // E_CLOSED from playback admitter (status 6)
    {
        struct ClosedAdmitter;
        impl SpawnAdmitter for ClosedAdmitter {
            fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
            fn admit_playback(&self, _: i64, _: String, _: f64) -> i32 { 6 }
        }
        let host = Arc::new(ClosedAdmitter);
        let out = state.invoke_input_callback_with_spawn_admitter(
            generation, r#"{"op":"closed"}"#, "closed-token",
            Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
        );
        assert_kind(&out, OutcomeKind::Completed);
        assert_eq!(value(&out), json!({"error":"E_CLOSED"}));
    }

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn yielded_audio_operation_releases_execution_slot() {
    struct AcceptSynthesisForSlot;
    impl SpawnAdmitter for AcceptSynthesisForSlot {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, _: String) -> i32 { 0 }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local s = require("subspace.synthesis")
        return {
          startup = function() return {ready = true} end,
          handle_input = function(event)
            local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
            if err then return {error = "synthesis-failed:" .. err.error} end
            return {synthesized = tostring(syn)}
          end,
        }
    "#}), "entry");

    let host = Arc::new(AcceptSynthesisForSlot);
    let yield_out = state.invoke_input_callback_with_spawn_admitter(
        generation, "{}", "captured-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&yield_out, OutcomeKind::Yielded);
    let coroutine_id = yield_out.to_json()["coroutineId"].as_i64().unwrap();
    let operation_id = yield_out.to_json()["operationId"].as_i64().unwrap();

    // While handle_input is yielded on an audio operation, another callback
    // must be invocable — proving the serialized actor slot was released.
    let interleaved = state.invoke_callback(generation, "startup", "null");
    assert_kind(&interleaved, OutcomeKind::Completed);
    assert_eq!(value(&interleaved), json!({"ready": true}));

    // Resume the suspended audio operation
    let resumed = state.resume_coroutine_with_spawn_admitter(
        generation, coroutine_id, operation_id, true,
        "synthesized:slot-release-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&resumed, OutcomeKind::Completed);

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn spawned_task_audio_operation_cancellation_discards_coroutine() {
    struct AcceptSynthesis;
    impl SpawnAdmitter for AcceptSynthesis {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, _: String) -> i32 { 0 }
    }
    let state = engine(2, 1);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local runtime = require("subspace.runtime")
        local s = require("subspace.synthesis")
        return {
          startup = function()
            runtime.spawn(function()
              local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
              if err then return "synthesis-failed" end
              return "completed:" .. tostring(syn)
            end)
          end,
        }
    "#}), "entry");

    let host = Arc::new(AcceptSynthesis);
    let startup = state.invoke_callback_with_spawn_admitter(
        generation, "startup", "null",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&startup, OutcomeKind::Completed);
    let task_id = startup.to_json()["spawnedCoroutines"][0]
        .as_i64()
        .expect("startup must have spawned a task");

    // Start the spawned task: it calls synthesis.synthesize and yields
    let yielded = state.start_coroutine_with_spawn_admitter(
        generation, task_id,
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&yielded, OutcomeKind::Yielded);
    let operation_id = yielded.to_json()["operationId"]
        .as_i64()
        .expect("synthesis must yield with an operation id");

    // Cancel the spawned task while its audio operation is pending
    let cancelled = state.cancel_coroutine(generation, task_id, operation_id);
    assert_kind(&cancelled, OutcomeKind::Cancelled);

    // Late resume echoes the Cancelled terminal without re-entering Lua
    // (the coroutine was released, so the cached terminal outcome is echoed).
    let late = state.resume_coroutine_with_spawn_admitter(
        generation, task_id, operation_id, true,
        "synthesized:late-after-cancel",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&late, OutcomeKind::Cancelled);

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn generation_close_during_audio_operation_suppresses_late_completion() {
    struct AcceptSynthesisForClose;
    impl SpawnAdmitter for AcceptSynthesisForClose {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_synthesis(&self, _: i64, _: String) -> i32 { 0 }
    }
    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local s = require("subspace.synthesis")
        return {
          startup = function() end,
          handle_input = function(event)
            local syn, err = s.synthesize({text="hello", language="en-US", voice="v"})
            if err then return {error = "synthesis-failed:" .. err.error} end
            return {synthesized = tostring(syn)}
          end,
        }
    "#}), "entry");

    let host = Arc::new(AcceptSynthesisForClose);
    let yield_out = state.invoke_input_callback_with_spawn_admitter(
        generation, "{}", "captured-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&yield_out, OutcomeKind::Yielded);
    let coroutine_id = yield_out.to_json()["coroutineId"].as_i64().unwrap();
    let operation_id = yield_out.to_json()["operationId"].as_i64().unwrap();

    // Close the generation while the audio operation is suspended
    assert_kind(&state.close(generation), OutcomeKind::Closed);

    // Late completion must be rejected as stale
    let late = state.resume_coroutine_with_spawn_admitter(
        generation, coroutine_id, operation_id, true,
        "synthesized:late-after-close",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&late, OutcomeKind::Stale);

    // Second close is idempotent
    assert_kind(&state.close(generation), OutcomeKind::Closed);
}

#[test]
fn audio_operation_duplicate_terminal_echoes_without_reentry() {
    struct AcceptTranscription;
    impl SpawnAdmitter for AcceptTranscription {
        fn admit(&self, _: i64) -> SpawnAdmission { SpawnAdmission::Accepted }
        fn admit_transcription(&self, _: i64, _: String) -> i32 { 0 }
    }

    let state = engine(4, 4);
    let generation = state.handle().generation;
    load_image(&state, json!({"entry": r#"
        local t = require("subspace.transcription")
        return {
          startup = function() end,
          handle_input = function(event)
            local x, e = t.transcribe(event.audio)
            if e then return {error = e.error} end
            return {text = x.text}
          end,
        }
    "#}), "entry");

    let host = Arc::new(AcceptTranscription);
    let yield_out = state.invoke_input_callback_with_spawn_admitter(
        generation, "{}", "captured-token",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&yield_out, OutcomeKind::Yielded);
    let coroutine_id = yield_out.to_json()["coroutineId"].as_i64().unwrap();
    let operation_id = yield_out.to_json()["operationId"].as_i64().unwrap();

    // First resume completes the transcription operation
    let first = state.resume_coroutine_with_spawn_admitter(
        generation, coroutine_id, operation_id, true, "hello world",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&first, OutcomeKind::Completed);
    assert_eq!(value(&first), json!({"text": "hello world"}));

    // Duplicate resume echoes the exact same terminal outcome
    // without re-entering Lua (the "must not execute" string never reaches Lua).
    let duplicate = state.resume_coroutine_with_spawn_admitter(
        generation, coroutine_id, operation_id, true, "must not execute",
        Arc::clone(&host) as Arc<dyn SpawnAdmitter>,
    );
    assert_kind(&duplicate, OutcomeKind::Completed);
    assert_eq!(
        value(&duplicate),
        json!({"text": "hello world"}),
        "duplicate resume must echo the exact terminal outcome without re-entry",
    );

    assert_kind(&state.close(generation), OutcomeKind::Closed);
}
