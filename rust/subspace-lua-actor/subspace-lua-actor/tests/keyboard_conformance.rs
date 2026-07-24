//! Contract tests for the typed keyboard-output actor broker (tasks 7.1–7.9),
//! the reserved `subspace.keyboard_output` module (tasks 8.1–8.4), and the
//! bounded SOS execution-owner primitives (tasks 9.1–9.5).
//!
//! The kernel is the validation/ownership boundary: bounded UTF-8 text and
//! semantic keys are validated natively, payloads live only in the typed
//! host-operation registry, yields carry only an opaque request identity, and
//! the SOS owner may yield only explicitly authorized typed operations.

use std::sync::Arc;

use serde_json::json;
use subspace_lua_actor::{Outcome, OutcomeKind, SpawnAdmission, SpawnAdmitter, StateEngine};

const CAPTURE_EVENT: &str =
    r#"{"metadata":{"duration_ms":1,"sample_rate":16000,"channels":1,"pcm_bytes":32}}"#;

fn engine() -> StateEngine {
    StateEngine::new(1 << 24, 1000, 10_000_000, 8, 8).unwrap()
}

fn host() -> Arc<dyn SpawnAdmitter> {
    struct AcceptAdmitter;
    impl SpawnAdmitter for AcceptAdmitter {
        fn admit(&self, _: i64) -> SpawnAdmission {
            SpawnAdmission::Accepted
        }
    }
    Arc::new(AcceptAdmitter)
}

fn load_image(state: &StateEngine, source_map: serde_json::Value, entrypoint: &str) -> Outcome {
    let generation = state.handle().generation;
    state.load_program_image(generation, &source_map.to_string(), entrypoint)
}

fn install_rc(state: &StateEngine, rc: serde_json::Value) {
    let generation = state.handle().generation;
    let outcome = state.set_resource_context(generation, &rc.to_string());
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
}

/// Resource context declaring keyboard-output eligibility.
fn keyboard_rc() -> serde_json::Value {
    json!({ "keyboardOutput": true })
}

fn value(outcome: &Outcome) -> serde_json::Value {
    outcome.to_json()["value"].clone()
}

/// Invoke handle_input and assert it yielded a keyboard request.
fn invoke_input_yielded(state: &StateEngine, source: &str) -> (i64, i64, i64, Outcome) {
    let generation = state.handle().generation;
    let loaded = load_image(state, json!({ "entry": source }), "entry");
    assert_eq!(loaded.kind(), OutcomeKind::Completed, "{:?}", loaded.to_json());
    let outcome = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        host(),
    );
    assert_eq!(outcome.kind(), OutcomeKind::Yielded, "{:?}", outcome.to_json());
    let json = outcome.to_json();
    (
        generation,
        json["coroutineId"].as_i64().unwrap(),
        json["operationId"].as_i64().unwrap(),
        outcome,
    )
}

/// Claim the request identity carried by a yielded outcome.
fn claim(state: &StateEngine, generation: i64, yielded: &Outcome) -> Outcome {
    let request_id = yielded.to_json()["value"]
        .as_str()
        .expect("yielded label must be a string")
        .parse::<i64>()
        .expect("yielded keyboard label must be the bare opaque request identity");
    state.claim_host_operation(generation, request_id)
}

/// Resume the yielded owner with a delivered terminal result table.
fn resume_delivered(
    state: &StateEngine,
    generation: i64,
    coroutine: i64,
    operation: i64,
) -> Outcome {
    state.resume_coroutine_with_spawn_admitter(
        generation,
        coroutine,
        operation,
        true,
        r#"{"status":"delivered"}"#,
        host(),
    )
}

// ---------------------------------------------------------------------------
// 8.1 / 8.3 — reserved module surface
// ---------------------------------------------------------------------------

#[test]
fn keyboard_output_module_is_reserved_with_exact_surface() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let outcome = load_image(
        &state,
        json!({"entry": r#"
        local function keys(t)
          local out = {}
          for k in pairs(t) do out[#out + 1] = k end
          table.sort(out)
          return out
        end
        local kb = require("subspace.keyboard_output")
        local preloaded = keys(subspace._preloaded)
        local surface = keys(kb)
        local same = (kb == require("subspace.keyboard_output"))
        local writable = pcall(function() kb.send_extra = 1 end)
        local host_fn_visible = type(subspace.host_keyboard_output)
        return {
          startup = function()
            return {
              preloaded = preloaded,
              surface = surface,
              same = same,
              writable = writable,
              host_fn_visible = host_fn_visible,
            }
          end,
        }
    "#}),
        "entry",
    );
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    let generation = state.handle().generation;
    let startup = state.invoke_callback(generation, "startup", "");
    assert_eq!(startup.kind(), OutcomeKind::Completed, "{:?}", startup.to_json());
    let result = value(&startup);
    // The reserved module is preloaded under its exact public name.
    assert!(
        result["preloaded"]
            .as_array()
            .unwrap()
            .contains(&json!("subspace.keyboard_output")),
        "preloaded: {:?}",
        result["preloaded"]
    );
    // Exactly send_text and send_key; nothing else.
    assert_eq!(
        result["surface"],
        json!(["send_key", "send_text"]),
        "module surface must be exactly send_text/send_key"
    );
    assert_eq!(result["same"], json!(true), "require must cache the reserved module");
    assert_eq!(result["writable"], json!(false), "image module view must be read-only");
    assert_eq!(
        result["host_fn_visible"],
        json!("nil"),
        "the native registration function must not be visible to package code"
    );
}

#[test]
fn keyboard_output_shadow_source_name_rejected_before_state_use() {
    let state = engine();
    let outcome = load_image(
        &state,
        json!({ "subspace.keyboard_output": "return {}", "entry": "return { startup = function() end }" }),
        "entry",
    );
    assert_eq!(
        outcome.kind(),
        OutcomeKind::ValidationFailure,
        "shadowing a reserved module name must be rejected: {:?}",
        outcome.to_json()
    );
}

#[test]
fn keyboard_output_call_during_module_evaluation_rejected() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let outcome = load_image(
        &state,
        json!({"entry": r#"
        local kb = require("subspace.keyboard_output")
        kb.send_text({ text = "during-load", profile = "linux:us" })
        return { startup = function() end }
    "#}),
        "entry",
    );
    assert_eq!(
        outcome.kind(),
        OutcomeKind::RuntimeFailure,
        "{:?}",
        outcome.to_json()
    );
    let diagnostic = outcome.to_json()["diagnostic"].as_str().unwrap().to_string();
    assert!(
        diagnostic.contains("effect-call-during-load"),
        "diagnostic: {diagnostic}"
    );
}

// ---------------------------------------------------------------------------
// 7.2 — exact native argument/value/UTF-8 byte bounds
// ---------------------------------------------------------------------------

/// Run invalid keyboard requests inside handle_input; every call must fail
/// with the expected code and yield nothing. The body runs inside
/// handle_input with `kb` already bound to the reserved module.
fn input_collects(rc: serde_json::Value, body: &str) -> serde_json::Value {
    let state = engine();
    install_rc(&state, rc);
    let source = format!(
        "local kb = require(\"subspace.keyboard_output\")\nreturn {{\n  startup = function() end,\n  handle_input = function(event)\n{body}\n  end,\n}}\n"
    );
    let generation = state.handle().generation;
    let loaded = load_image(&state, json!({ "entry": source }), "entry");
    assert_eq!(loaded.kind(), OutcomeKind::Completed, "{:?}", loaded.to_json());
    let outcome = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        host(),
    );
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    value(&outcome)
}

#[test]
fn send_text_validates_exact_request_shape_and_bounds() {
    let result = input_collects(keyboard_rc(), r#"
        local function code(r, e) if r == nil then return e.error else return "ok" end end
        local out = {}
        out.non_table = code(kb.send_text("nope"))
        out.arity = code(kb.send_text({ text = "x", profile = "p" }, 1))
        out.missing_profile = code(kb.send_text({ text = "x" }))
        out.missing_text = code(kb.send_text({ profile = "p" }))
        out.extra_key = code(kb.send_text({ text = "x", profile = "p", extra = 1 }))
        out.wrong_type = code(kb.send_text({ text = 1, profile = "p" }))
        out.metatable = code(kb.send_text(setmetatable({ text = "x", profile = "p" }, {})))
        out.empty_text = code(kb.send_text({ text = "", profile = "p" }))
        out.big_text = code(kb.send_text({ text = string.rep("a", 16385), profile = "p" }))
        out.blank_profile = code(kb.send_text({ text = "x", profile = "   " }))
        out.empty_profile = code(kb.send_text({ text = "x", profile = "" }))
        out.big_profile = code(kb.send_text({ text = "x", profile = string.rep("p", 257) }))
        out.bad_utf8 = code(kb.send_text({ text = string.char(255, 254), profile = "p" }))
        return { codes = out }
    "#);
    let codes = &result["codes"];
    assert_eq!(codes["non_table"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["arity"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["missing_profile"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["missing_text"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["extra_key"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["wrong_type"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["metatable"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["empty_text"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["big_text"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["blank_profile"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["empty_profile"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["big_profile"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["bad_utf8"], json!("E_INVALID_ARGUMENT"));
}

#[test]
fn send_key_validates_semantic_key_vocabulary() {
    let result = input_collects(keyboard_rc(), r#"
        local kb = require("subspace.keyboard_output")
        local function code(r, e) if r == nil then return e.error else return "ok" end end
        local out = {}
        out.non_table = code(kb.send_key("enter"))
        out.arity = code(kb.send_key({ key = "enter", profile = "p" }, 1))
        out.tab = code(kb.send_key({ key = "tab", profile = "p" }))
        out.enter_upper = code(kb.send_key({ key = "ENTER", profile = "p" }))
        out.wrong_type = code(kb.send_key({ key = 1, profile = "p" }))
        out.extra_key = code(kb.send_key({ key = "enter", profile = "p", text = "x" }))
        out.missing_key = code(kb.send_key({ profile = "p" }))
        out.blank_profile = code(kb.send_key({ key = "enter", profile = "  " }))
        return { codes = out }
    "#);
    let codes = &result["codes"];
    assert_eq!(codes["non_table"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["arity"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["tab"], json!("E_INVALID_VALUE"));
    assert_eq!(codes["enter_upper"], json!("E_INVALID_VALUE"));
    assert_eq!(codes["wrong_type"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["extra_key"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["missing_key"], json!("E_INVALID_ARGUMENT"));
    assert_eq!(codes["blank_profile"], json!("E_INVALID_ARGUMENT"));
}

#[test]
fn send_text_accepts_exact_byte_bounds() {
    // 16384-byte text and 256-byte profile are the inclusive bounds: both
    // must be admitted and yield an opaque request.
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, _co, _op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local r, e = kb.send_text({ text = string.rep("a", 16384), profile = string.rep("p", 256) })
            if not r then return { error = { code = "BOUND", detail = e.error } } end
            return { ok = true }
          end,
        }
    "#);
    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    assert_eq!(claim.to_json()["hostOperationKind"], json!("KEYBOARD_SEND_TEXT"));
    assert_eq!(claim.to_json()["text"].as_str().unwrap().len(), 16384);
    assert_eq!(claim.to_json()["profile"].as_str().unwrap().len(), 256);
}

// ---------------------------------------------------------------------------
// 8.5 native recheck / context authorization
// ---------------------------------------------------------------------------

#[test]
fn keyboard_output_requires_declared_capability() {
    // Resource context without keyboardOutput: no suspension or effect.
    let result = input_collects(json!({}), r#"
        local kb = require("subspace.keyboard_output")
        local r, e = kb.send_text({ text = "x", profile = "p" })
        return { codes = { undeclared = r == nil and e.error or "ok" } }
    "#);
    assert_eq!(result["codes"]["undeclared"], json!("E_CAPABILITY_UNDECLARED"));
}

#[test]
fn keyboard_output_rejects_ineligible_execution_contexts() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let generation = state.handle().generation;
    let loaded = load_image(
        &state,
        json!({"entry": r#"
        local kb = require("subspace.keyboard_output")
        local function code(r, e) if r == nil then return e.error else return "ok" end end
        return {
          startup = function()
            local child_code
            local child = coroutine.create(function()
              return code(kb.send_text({ text = "x", profile = "p" }))
            end)
            local ok, result = coroutine.resume(child)
            child_code = ok and result or "resume-failed"
            return {
              startup = code(kb.send_text({ text = "x", profile = "p" })),
              unmanaged = child_code,
            }
          end,
          handle_readiness = function()
            return { ready = true, kb = code(kb.send_key({ key = "enter", profile = "p" })) }
          end,
        }
    "#}),
        "entry",
    );
    assert_eq!(loaded.kind(), OutcomeKind::Completed, "{:?}", loaded.to_json());
    let startup = state.invoke_callback(generation, "startup", "");
    assert_eq!(startup.kind(), OutcomeKind::Completed, "{:?}", startup.to_json());
    let startup_value = value(&startup);
    assert_eq!(startup_value["startup"], json!("E_INVALID_CONTEXT"));
    assert_eq!(startup_value["unmanaged"], json!("E_INVALID_CONTEXT"));
    let readiness = state.invoke_callback(generation, "handle_readiness", "{}");
    assert_eq!(readiness.kind(), OutcomeKind::Completed, "{:?}", readiness.to_json());
    assert_eq!(value(&readiness)["kb"], json!("E_INVALID_CONTEXT"));
}

// ---------------------------------------------------------------------------
// 7.1 / 7.3 / 7.4 — typed yield, opaque identity, content privacy
// ---------------------------------------------------------------------------

#[test]
fn send_text_yields_only_opaque_identity_and_claims_typed_payload() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result, err = kb.send_text({ text = "secret hello", profile = "linux:us" })
            if not result then return { error = { code = "SEND", detail = err.error } } end
            if result.status ~= "delivered" then
              return { error = { code = "STATUS", detail = result.status } }
            end
            return { ok = true }
          end,
        }
    "#);
    // 7.4 — the yielded label is exactly the opaque request identity.
    let label = outcome.to_json()["value"].as_str().unwrap().to_string();
    assert!(
        label.parse::<i64>().is_ok(),
        "label must be the bare opaque request identity, got {label:?}"
    );
    assert!(!label.contains("secret"), "label leaks content: {label}");
    assert!(!label.contains("linux"), "label leaks profile: {label}");

    // 7.1/7.3 — the typed claim is the only way to obtain the payload.
    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    let claim_json = claim.to_json();
    assert_eq!(claim_json["hostOperationKind"], json!("KEYBOARD_SEND_TEXT"));
    assert_eq!(claim_json["text"], json!("secret hello"));
    assert_eq!(claim_json["profile"], json!("linux:us"));
    assert_eq!(claim_json["coroutineId"], json!(co));
    assert_eq!(claim_json["operationId"], json!(op));
    assert_eq!(claim_json["requestId"].as_i64().unwrap().to_string(), label);

    let completed = resume_delivered(&state, generation, co, op);
    assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
    assert_eq!(value(&completed), json!({"ok": true}));
}

#[test]
fn send_key_yields_and_claims_semantic_enter() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result, err = kb.send_key({ key = "enter", profile = "mac:iso" })
            if not result then return { error = { code = "SEND", detail = err.error } } end
            if result.status ~= "delivered" then
              return { error = { code = "STATUS", detail = result.status } }
            end
            return { ok = true }
          end,
        }
    "#);
    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    let claim_json = claim.to_json();
    assert_eq!(claim_json["hostOperationKind"], json!("KEYBOARD_SEND_KEY"));
    assert_eq!(claim_json["key"], json!("enter"));
    assert_eq!(claim_json["profile"], json!("mac:iso"));
    assert!(claim_json.get("text").is_none() || claim_json["text"].is_null());
    let completed = resume_delivered(&state, generation, co, op);
    assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
}

#[test]
fn semantic_non_delivered_outcomes_reach_lua_as_result_tables() {
    for (status, reason) in [
        ("rejected", "E_POLICY"),
        ("failed", "E_TRANSPORT"),
        ("indeterminate", "E_TIMEOUT"),
    ] {
        let state = engine();
        install_rc(&state, keyboard_rc());
        let (generation, co, op, outcome) = invoke_input_yielded(&state, &format!(
            r#"
            local kb = require("subspace.keyboard_output")
            return {{
              startup = function() end,
              handle_input = function(event)
                local result, err = kb.send_key({{ key = "escape", profile = "p" }})
                if not result then return {{ error = {{ code = "SEND", detail = err.error }} }} end
                return {{ status = result.status, reason = result.reason }}
              end,
            }}
        "#
        ));
        let claim = claim(&state, generation, &outcome);
        assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
        let resume_json = json!({"status": status, "reason": reason}).to_string();
        let completed = state.resume_coroutine_with_spawn_admitter(
            generation, co, op, true, &resume_json, host(),
        );
        assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
        let result = value(&completed);
        assert_eq!(result["status"], json!(status), "status {status}");
        assert_eq!(result["reason"], json!(reason), "reason for {status}");
    }
}

#[test]
fn failure_resume_normalizes_to_stable_error_codes() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result, err = kb.send_text({ text = "x", profile = "p" })
            return { code = result == nil and err.error or "ok" }
          end,
        }
    "#);
    let claim1 = claim(&state, generation, &outcome);
    assert_eq!(claim1.kind(), OutcomeKind::Completed, "{:?}", claim1.to_json());
    // Stable codes pass through; unknown diagnostics collapse to E_HOST_FAILURE.
    let failed = state.resume_coroutine_with_spawn_admitter(
        generation, co, op, false, "E_BUSY", host(),
    );
    assert_eq!(value(&failed), json!({"code": "E_BUSY"}));

    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result, err = kb.send_text({ text = "x", profile = "p" })
            return { code = result == nil and err.error or "ok" }
          end,
        }
    "#);
    let claim2 = claim(&state, generation, &outcome);
    assert_eq!(claim2.kind(), OutcomeKind::Completed, "{:?}", claim2.to_json());
    let failed = state.resume_coroutine_with_spawn_admitter(
        generation,
        co,
        op,
        false,
        "gatt-disconnect-0x3e transport detail",
        host(),
    );
    assert_eq!(
        value(&failed),
        json!({"code": "E_HOST_FAILURE"}),
        "unknown diagnostics must collapse and never transport raw detail"
    );
}

// ---------------------------------------------------------------------------
// 7.5 — exactly-once typed claiming
// ---------------------------------------------------------------------------

#[test]
fn duplicate_unknown_and_foreign_claims_rejected() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result = kb.send_text({ text = "x", profile = "p" })
            return { ok = true }
          end,
        }
    "#);
    let request_id: i64 = outcome.to_json()["value"].as_str().unwrap().parse().unwrap();

    // Unknown request identity.
    let unknown = state.claim_host_operation(generation, request_id + 999);
    assert_eq!(unknown.kind(), OutcomeKind::InvalidOwnership, "{:?}", unknown.to_json());

    // First claim admits exactly once.
    let first = state.claim_host_operation(generation, request_id);
    assert_eq!(first.kind(), OutcomeKind::Completed, "{:?}", first.to_json());

    // Duplicate claim never re-enters Lua or re-admits.
    let duplicate = state.claim_host_operation(generation, request_id);
    assert_eq!(duplicate.kind(), OutcomeKind::InvalidOwnership, "{:?}", duplicate.to_json());

    // Foreign state: the same identity is unknown to another engine.
    let other = engine();
    install_rc(&other, keyboard_rc());
    let foreign = other.claim_host_operation(other.handle().generation, request_id);
    assert_eq!(foreign.kind(), OutcomeKind::InvalidOwnership, "{:?}", foreign.to_json());

    // Completion consumes the request: a late claim after resume is rejected.
    let completed = resume_delivered(&state, generation, co, op);
    assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
    let late = state.claim_host_operation(generation, request_id);
    assert_ne!(late.kind(), OutcomeKind::Completed, "{:?}", late.to_json());
}

// ---------------------------------------------------------------------------
// 7.6 / 7.8 — terminal races, cancellation, close, late completion
// ---------------------------------------------------------------------------

#[test]
fn cancel_discards_without_reentry_and_late_claim_fails() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            kb.send_text({ text = "x", profile = "p" })
            return { ok = true }
          end,
        }
    "#);
    let request_id: i64 = outcome.to_json()["value"].as_str().unwrap().parse().unwrap();
    let cancelled = state.cancel_coroutine(generation, co, op);
    assert_eq!(cancelled.kind(), OutcomeKind::Cancelled, "{:?}", cancelled.to_json());

    // The request is gone: no capability acquisition can start from it.
    let late_claim = state.claim_host_operation(generation, request_id);
    assert_ne!(late_claim.kind(), OutcomeKind::Completed, "{:?}", late_claim.to_json());

    // Late completion echoes the exact terminal without re-entering Lua.
    let late_resume = resume_delivered(&state, generation, co, op);
    assert_eq!(late_resume.kind(), OutcomeKind::Cancelled, "{:?}", late_resume.to_json());
}

#[test]
fn duplicate_resume_echoes_exact_terminal() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            local result = kb.send_text({ text = "x", profile = "p" })
            return { ok = true }
          end,
        }
    "#);
    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    let first = resume_delivered(&state, generation, co, op);
    assert_eq!(first.kind(), OutcomeKind::Completed, "{:?}", first.to_json());
    let second = resume_delivered(&state, generation, co, op);
    assert_eq!(second.kind(), OutcomeKind::Completed, "{:?}", second.to_json());
    assert_eq!(first.to_json()["value"], second.to_json()["value"]);
}

#[test]
fn close_revokes_yielded_keyboard_operation() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let (generation, co, op, outcome) = invoke_input_yielded(&state, r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function() end,
          handle_input = function(event)
            kb.send_key({ key = "enter", profile = "p" })
            return { ok = true }
          end,
        }
    "#);
    let request_id: i64 = outcome.to_json()["value"].as_str().unwrap().parse().unwrap();
    let closed = state.close(generation);
    assert_eq!(closed.kind(), OutcomeKind::Closed, "{:?}", closed.to_json());

    let late_resume = resume_delivered(&state, generation, co, op);
    assert_eq!(late_resume.kind(), OutcomeKind::Stale, "{:?}", late_resume.to_json());
    let late_claim = state.claim_host_operation(generation, request_id);
    assert_eq!(late_claim.kind(), OutcomeKind::Stale, "{:?}", late_claim.to_json());
}

// ---------------------------------------------------------------------------
// 7.7 / 7.9 — concurrent actor work through one serialized slot
// ---------------------------------------------------------------------------

#[test]
fn input_and_task_keyboard_operations_complete_independently() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let generation = state.handle().generation;
    let loaded = load_image(
        &state,
        json!({"entry": r#"
        local kb = require("subspace.keyboard_output")
        return {
          startup = function()
            subspace.runtime.spawn(function()
              local result, err = kb.send_text({ text = "task-text", profile = "task-profile" })
              if not result or result.status ~= "delivered" then error("task delivery failed") end
            end)
            return nil
          end,
          handle_input = function(event)
            local result, err = kb.send_text({ text = "input-text", profile = "input-profile" })
            if not result then return { error = { code = "SEND", detail = err.error } } end
            if result.status ~= "delivered" then
              return { error = { code = "STATUS", detail = result.status } }
            end
            return { ok = true }
          end,
        }
    "#}),
        "entry",
    );
    assert_eq!(loaded.kind(), OutcomeKind::Completed, "{:?}", loaded.to_json());
    let startup = state.invoke_callback_with_spawn_admitter(generation, "startup", "", host());
    assert_eq!(startup.kind(), OutcomeKind::Completed, "{:?}", startup.to_json());
    let task_id = startup.to_json()["spawnedCoroutines"][0].as_i64().unwrap();

    // Input yields its keyboard request first.
    let input_outcome = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        host(),
    );
    assert_eq!(input_outcome.kind(), OutcomeKind::Yielded, "{:?}", input_outcome.to_json());
    let input_co = input_outcome.to_json()["coroutineId"].as_i64().unwrap();
    let input_op = input_outcome.to_json()["operationId"].as_i64().unwrap();

    // The task starts and yields its own independent request.
    let task_outcome = state.start_coroutine_with_spawn_admitter(generation, task_id, host());
    assert_eq!(task_outcome.kind(), OutcomeKind::Yielded, "{:?}", task_outcome.to_json());
    let task_op = task_outcome.to_json()["operationId"].as_i64().unwrap();

    // Each claim returns only its owner's typed payload.
    let input_claim = claim(&state, generation, &input_outcome);
    assert_eq!(input_claim.to_json()["text"], json!("input-text"));
    assert_eq!(input_claim.to_json()["profile"], json!("input-profile"));
    assert_eq!(input_claim.to_json()["coroutineId"], json!(input_co));
    let task_claim = claim(&state, generation, &task_outcome);
    assert_eq!(task_claim.to_json()["text"], json!("task-text"));
    assert_eq!(task_claim.to_json()["profile"], json!("task-profile"));
    assert_eq!(task_claim.to_json()["coroutineId"], json!(task_id));

    // Completing the input does not disturb the task's suspended request.
    let input_done = resume_delivered(&state, generation, input_co, input_op);
    assert_eq!(input_done.kind(), OutcomeKind::Completed, "{:?}", input_done.to_json());
    assert_eq!(value(&input_done), json!({"ok": true}));
    let task_duplicate_claim = state.claim_host_operation(
        generation,
        task_claim.to_json()["requestId"].as_i64().unwrap(),
    );
    assert_eq!(
        task_duplicate_claim.kind(),
        OutcomeKind::InvalidOwnership,
        "the task request was already claimed exactly once: {:?}",
        task_duplicate_claim.to_json()
    );
    let task_done = resume_delivered(&state, generation, task_id, task_op);
    assert_eq!(task_done.kind(), OutcomeKind::Completed, "{:?}", task_done.to_json());
}

#[test]
fn managed_task_cancellation_never_reenters_lua() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    let generation = state.handle().generation;
    let loaded = load_image(
        &state,
        json!({"entry": r#"
        local kb = require("subspace.keyboard_output")
        _G.entered = 0
        return {
          startup = function()
            subspace.runtime.spawn(function()
              local result = kb.send_text({ text = "task-text", profile = "p" })
              _G.entered = _G.entered + 1
            end)
            return nil
          end,
        }
    "#}),
        "entry",
    );
    assert_eq!(loaded.kind(), OutcomeKind::Completed, "{:?}", loaded.to_json());
    let startup = state.invoke_callback_with_spawn_admitter(generation, "startup", "", host());
    assert_eq!(startup.kind(), OutcomeKind::Completed, "{:?}", startup.to_json());
    let task_id = startup.to_json()["spawnedCoroutines"][0].as_i64().unwrap();
    let task_outcome = state.start_coroutine_with_spawn_admitter(generation, task_id, host());
    assert_eq!(task_outcome.kind(), OutcomeKind::Yielded, "{:?}", task_outcome.to_json());
    let task_op = task_outcome.to_json()["operationId"].as_i64().unwrap();
    let request_id: i64 = task_outcome.to_json()["value"].as_str().unwrap().parse().unwrap();

    // Cancel the task while the physical operation may still be pending host-side.
    let cancelled = state.cancel_coroutine(generation, task_id, task_op);
    assert_eq!(cancelled.kind(), OutcomeKind::Cancelled, "{:?}", cancelled.to_json());

    // Late claim and late completion both fail without Lua re-entry.
    let late_claim = state.claim_host_operation(generation, request_id);
    assert_ne!(late_claim.kind(), OutcomeKind::Completed, "{:?}", late_claim.to_json());
    let late_resume = resume_delivered(&state, generation, task_id, task_op);
    assert_eq!(late_resume.kind(), OutcomeKind::Cancelled, "{:?}", late_resume.to_json());
}

// ---------------------------------------------------------------------------
// 9.1–9.5 — bounded SOS execution owner
// ---------------------------------------------------------------------------

fn load_sos_image(state: &StateEngine, body: &str) {
    let source = format!(
        r#"
        local kb = require("subspace.keyboard_output")
        return {{
          startup = function() end,
          handle_sos = function(event)
            {body}
          end,
        }}
    "#
    );
    let outcome = load_image(state, json!({ "entry": source }), "entry");
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
}

#[test]
fn sos_no_yield_completes_in_one_slice_like_synchronous_callbacks() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(&state, "return { ok = true }");
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    assert_eq!(value(&outcome), json!({"ok": true}));

    // Nil remains the valid no-op terminal.
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(&state, "return nil");
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    assert_eq!(value(&outcome), json!(null));

    // Application failure shape passes through unchanged.
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"return { error = { code = "SOS_BUSY", detail = "transport unavailable" } }"#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    assert_eq!(
        value(&outcome),
        json!({"error": {"code": "SOS_BUSY", "detail": "transport unavailable"}})
    );
}

#[test]
fn sos_yields_keyboard_operation_through_typed_broker() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"
        local result, err = kb.send_key({ key = "enter", profile = "sos-profile" })
        if not result then return { error = { code = "SEND", detail = err.error } } end
        if result.status ~= "delivered" then
          return { error = { code = "STATUS", detail = result.status } }
        end
        return { ok = true }
        "#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Yielded, "{:?}", outcome.to_json());
    let co = outcome.to_json()["coroutineId"].as_i64().unwrap();
    let op = outcome.to_json()["operationId"].as_i64().unwrap();
    let label = outcome.to_json()["value"].as_str().unwrap().to_string();
    assert!(label.parse::<i64>().is_ok(), "SOS yield must be opaque: {label}");
    assert!(!label.contains("sos-profile"));

    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    assert_eq!(claim.to_json()["hostOperationKind"], json!("KEYBOARD_SEND_KEY"));
    assert_eq!(claim.to_json()["key"], json!("enter"));
    assert_eq!(claim.to_json()["profile"], json!("sos-profile"));

    let completed = resume_delivered(&state, generation, co, op);
    assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
    assert_eq!(value(&completed), json!({"ok": true}));
}

#[test]
fn sos_chains_multiple_keyboard_operations() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"
        local r1, e1 = kb.send_text({ text = "sos-text", profile = "p" })
        if not r1 then return { error = { code = "FIRST", detail = e1.error } } end
        local r2, e2 = kb.send_key({ key = "enter", profile = "p" })
        if not r2 then return { error = { code = "SECOND", detail = e2.error } } end
        return { ok = true }
        "#,
    );
    let generation = state.handle().generation;
    let first =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(first.kind(), OutcomeKind::Yielded, "{:?}", first.to_json());
    let co = first.to_json()["coroutineId"].as_i64().unwrap();
    let op1 = first.to_json()["operationId"].as_i64().unwrap();
    let claim1 = claim(&state, generation, &first);
    assert_eq!(claim1.to_json()["hostOperationKind"], json!("KEYBOARD_SEND_TEXT"));
    assert_eq!(claim1.to_json()["text"], json!("sos-text"));

    let second = resume_delivered(&state, generation, co, op1);
    assert_eq!(second.kind(), OutcomeKind::Yielded, "{:?}", second.to_json());
    let op2 = second.to_json()["operationId"].as_i64().unwrap();
    let claim2 = claim(&state, generation, &second);
    assert_eq!(claim2.to_json()["hostOperationKind"], json!("KEYBOARD_SEND_KEY"));
    assert_eq!(claim2.to_json()["key"], json!("enter"));

    let completed = resume_delivered(&state, generation, co, op2);
    assert_eq!(completed.kind(), OutcomeKind::Completed, "{:?}", completed.to_json());
    assert_eq!(value(&completed), json!({"ok": true}));
}

#[test]
fn sos_owner_denies_sleep_spawn_defer_and_raw_yield() {
    // sleep / spawn / defer fail with E_INVALID_CONTEXT before any effect.
    for (name, call, expected) in [
        (
            "sleep",
            "local r, e = subspace.runtime.sleep(0.1)\nreturn { denied = r == nil and e.error or \"ok\" }",
            "E_INVALID_CONTEXT",
        ),
        (
            "spawn",
            "local r, e = subspace.runtime.spawn(function() end)\nreturn { denied = r == nil and e.error or \"ok\" }",
            "E_INVALID_CONTEXT",
        ),
        (
            "defer",
            "local r, e = subspace.runtime.defer(function() end)\nreturn { denied = r == nil and e.error or \"ok\" }",
            "E_INVALID_CONTEXT",
        ),
    ] {
        let state = engine();
        install_rc(&state, keyboard_rc());
        load_sos_image(&state, call);
        let generation = state.handle().generation;
        let outcome = state.invoke_sos_callback_with_spawn_admitter(
            generation,
            r#"{"reason":"test"}"#,
            host(),
        );
        assert_eq!(outcome.kind(), OutcomeKind::Completed, "{name}: {:?}", outcome.to_json());
        assert_eq!(value(&outcome)["denied"], json!(expected), "{name}");
    }

    // A raw yield is never an authorized typed operation.
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(&state, r#"coroutine.yield("raw")"#);
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::RuntimeFailure, "{:?}", outcome.to_json());
    assert!(
        outcome.to_json()["diagnostic"]
            .as_str()
            .unwrap()
            .contains("E_INVALID_YIELD"),
        "{:?}",
        outcome.to_json()
    );
}

#[test]
fn sos_owner_denies_audio_and_filesystem_effects() {
    let state = engine();
    install_rc(
        &state,
        json!({
            "keyboardOutput": true,
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_sos_image(
        &state,
        r#"
        local fs = require("subspace.fs")
        local mount_ok, mount_err = fs.mount("data")
        local transcribe_code
        local transcription = require("subspace.transcription")
        local t_ok, t_err = transcription.transcribe(0)
        transcribe_code = t_ok == nil and t_err.error or "ok"
        local io_code
        if mount_ok then
          local r, e = fs.stat(mount_ok, "x")
          io_code = r == nil and e.error or "ok"
        else
          io_code = "mount-failed"
        end
        return { transcribe = transcribe_code, io = io_code, mount = mount_ok ~= nil }
        "#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Completed, "{:?}", outcome.to_json());
    let result = value(&outcome);
    // Mount is a synchronous lookup (not an effect) and stays available;
    // every I/O and audio effect is denied for the SOS owner.
    assert_eq!(result["mount"], json!(true));
    assert_eq!(result["transcribe"], json!("E_INVALID_CONTEXT"));
    assert_eq!(result["io"], json!("E_INVALID_CONTEXT"));
}

#[test]
fn sos_terminal_result_shape_is_validated() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(&state, "return 42");
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::RuntimeFailure, "{:?}", outcome.to_json());
    assert!(
        outcome.to_json()["diagnostic"]
            .as_str()
            .unwrap()
            .contains("callback contract violation"),
        "{:?}",
        outcome.to_json()
    );
}

#[test]
fn sos_terminal_shape_validated_after_keyboard_resume() {
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"
        kb.send_key({ key = "enter", profile = "p" })
        return "not-a-table"
        "#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Yielded, "{:?}", outcome.to_json());
    let co = outcome.to_json()["coroutineId"].as_i64().unwrap();
    let op = outcome.to_json()["operationId"].as_i64().unwrap();
    let claim = claim(&state, generation, &outcome);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "{:?}", claim.to_json());
    let completed = resume_delivered(&state, generation, co, op);
    assert_eq!(completed.kind(), OutcomeKind::RuntimeFailure, "{:?}", completed.to_json());
    assert!(
        completed.to_json()["diagnostic"]
            .as_str()
            .unwrap()
            .contains("callback contract violation"),
        "{:?}",
        completed.to_json()
    );
}

#[test]
fn sos_suspension_cancel_close_and_late_completion() {
    // Cancel: exactly-once terminal, no re-entry, late resume echoes.
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"
        kb.send_key({ key = "enter", profile = "p" })
        return { ok = true }
        "#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Yielded, "{:?}", outcome.to_json());
    let co = outcome.to_json()["coroutineId"].as_i64().unwrap();
    let op = outcome.to_json()["operationId"].as_i64().unwrap();
    let request_id: i64 = outcome.to_json()["value"].as_str().unwrap().parse().unwrap();
    let cancelled = state.cancel_coroutine(generation, co, op);
    assert_eq!(cancelled.kind(), OutcomeKind::Cancelled, "{:?}", cancelled.to_json());
    let late_claim = state.claim_host_operation(generation, request_id);
    assert_ne!(late_claim.kind(), OutcomeKind::Completed, "{:?}", late_claim.to_json());
    let late_resume = resume_delivered(&state, generation, co, op);
    assert_eq!(late_resume.kind(), OutcomeKind::Cancelled, "{:?}", late_resume.to_json());

    // Close: generation revocation suppresses every late resume/claim.
    let state = engine();
    install_rc(&state, keyboard_rc());
    load_sos_image(
        &state,
        r#"
        kb.send_key({ key = "enter", profile = "p" })
        return { ok = true }
        "#,
    );
    let generation = state.handle().generation;
    let outcome =
        state.invoke_sos_callback_with_spawn_admitter(generation, r#"{"reason":"test"}"#, host());
    assert_eq!(outcome.kind(), OutcomeKind::Yielded, "{:?}", outcome.to_json());
    let co = outcome.to_json()["coroutineId"].as_i64().unwrap();
    let op = outcome.to_json()["operationId"].as_i64().unwrap();
    let request_id: i64 = outcome.to_json()["value"].as_str().unwrap().parse().unwrap();
    let closed = state.close(generation);
    assert_eq!(closed.kind(), OutcomeKind::Closed, "{:?}", closed.to_json());
    let late_resume = resume_delivered(&state, generation, co, op);
    assert_eq!(late_resume.kind(), OutcomeKind::Stale, "{:?}", late_resume.to_json());
    let late_claim = state.claim_host_operation(generation, request_id);
    assert_eq!(late_claim.kind(), OutcomeKind::Stale, "{:?}", late_claim.to_json());
}
