//! Non-Journal conformance fixture for `subspace.fs` (tasks 5.5–5.10).
//!
//! Exercises all six filesystem operations through a generic fixture package,
//! proves actor work continues while I/O is suspended, and verifies no
//! leakage or shadowing of the reserved `subspace.fs` module.

use std::sync::Arc;

use serde_json::json;
use subspace_lua_actor::{Outcome, OutcomeKind, SpawnAdmission, SpawnAdmitter, StateEngine};
const CAPTURE_EVENT: &str =
    r#"{"metadata":{"duration_ms":1,"sample_rate":16000,"channels":1,"pcm_bytes":32}}"#;

fn engine() -> StateEngine {
    StateEngine::new(1 << 24, 1000, 10_000_000, 8, 8).unwrap()
}

fn load_image(state: &StateEngine, source_map: serde_json::Value, entrypoint: &str) {
    let generation = state.handle().generation;
    let outcome = state.load_program_image(generation, &source_map.to_string(), entrypoint);
    assert_eq!(
        outcome.kind(),
        OutcomeKind::Completed,
        "load failed: {:?}",
        outcome.to_json()
    );
}

fn value(outcome: &Outcome) -> serde_json::Value {
    outcome.to_json()["value"].clone()
}

struct AcceptAdmitter;
impl SpawnAdmitter for AcceptAdmitter {
    fn admit(&self, _: i64) -> SpawnAdmission {
        SpawnAdmission::Accepted
    }
}

fn install_rc(state: &StateEngine, rc: serde_json::Value) {
    let generation = state.handle().generation;
    let outcome = state.set_resource_context(generation, &rc.to_string());
    assert_eq!(outcome.kind(), OutcomeKind::Completed);
}

/// Drive one yielded fs operation: claim it, assert the expected kind, resume
/// with the given JSON result, and return the next outcome.
fn drive_fs(
    state: &StateEngine,
    generation: i64,
    coroutine: i64,
    operation: i64,
    yielded: &Outcome,
    expected_kind: &str,
    resume_json: &str,
    host: Arc<dyn SpawnAdmitter>,
) -> Outcome {
    let request_id = yielded.to_json()["value"]
        .as_str()
        .unwrap()
        .parse::<i64>()
        .unwrap();
    let claim = state.claim_host_operation(generation, request_id);
    assert_eq!(claim.kind(), OutcomeKind::Completed, "claim failed");
    assert_eq!(claim.to_json()["hostOperationKind"], json!(expected_kind));
    state.resume_coroutine_with_spawn_admitter(
        generation,
        coroutine,
        operation,
        true,
        resume_json,
        host,
    )
}

#[test]
fn fs_all_operations_through_handle_input() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount, me = fs.mount("data")
            if not mount then return {error={code="MOUNT_FAIL",detail=me.error}} end
            local r1, e1 = fs.mkdir(mount, "a/b", {parents=true})
            if not r1 then return {error={code="MKDIR",detail=e1.error}} end
            local r2, e2 = fs.write_text(mount, "a/b/f.txt", "hello", {mode="create-new"})
            if not r2 then return {error={code="WRITE",detail=e2.error}} end
            local r3, e3 = fs.stat(mount, "a/b/f.txt")
            if not r3 then return {error={code="STAT",detail=e3.error}} end
            local r4, e4 = fs.read_text(mount, "a/b/f.txt", {max_bytes=1024})
            if not r4 then return {error={code="READ",detail=e4.error}} end
            local r5, e5 = fs.list(mount, "a/b", {limit=10})
            if not r5 then return {error={code="LIST",detail=e5.error}} end
            local r6, e6 = fs.remove(mount, "a/b/f.txt", {missing_ok=false})
            if not r6 then return {error={code="REMOVE",detail=e6.error}} end
            return {ok=true}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Yielded);
    let co = input.to_json()["coroutineId"].as_i64().unwrap();
    let op = input.to_json()["operationId"].as_i64().unwrap();

    // mkdir
    let next = drive_fs(
        &state,
        generation,
        co,
        op,
        &input,
        "FS_MKDIR",
        &json!({"status":"created"}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Yielded);
    let op2 = next.to_json()["operationId"].as_i64().unwrap();

    // write_text
    let next = drive_fs(
        &state,
        generation,
        co,
        op2,
        &next,
        "FS_WRITE_TEXT",
        &json!({"status":"written","bytes":5}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Yielded);
    let op3 = next.to_json()["operationId"].as_i64().unwrap();

    // stat
    let next = drive_fs(
        &state,
        generation,
        co,
        op3,
        &next,
        "FS_STAT",
        &json!({"kind":"file","size":5}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Yielded);
    let op4 = next.to_json()["operationId"].as_i64().unwrap();

    // read_text
    let next = drive_fs(
        &state,
        generation,
        co,
        op4,
        &next,
        "FS_READ_TEXT",
        &json!({"text":"hello","bytes":5}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Yielded);
    let op5 = next.to_json()["operationId"].as_i64().unwrap();

    // list
    let next = drive_fs(
        &state,
        generation,
        co,
        op5,
        &next,
        "FS_LIST",
        &json!({"entries":[{"name":"f.txt","kind":"file"}]}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Yielded);
    let op6 = next.to_json()["operationId"].as_i64().unwrap();

    // remove
    let next = drive_fs(
        &state,
        generation,
        co,
        op6,
        &next,
        "FS_REMOVE",
        &json!({"status":"removed"}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(next.kind(), OutcomeKind::Completed);
    assert_eq!(value(&next), json!({"ok": true}));
}

#[test]
fn fs_list_accepts_empty_mount_root_selector() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("data")
            local page, err = fs.list(mount, "", {limit=10})
            if not page then return {error=err.error} end
            return {ok=true}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Yielded);
    let claim = state.claim_host_operation(
        generation,
        input.to_json()["value"].as_str().unwrap().parse().unwrap(),
    );
    assert_eq!(claim.kind(), OutcomeKind::Completed);
    assert_eq!(claim.to_json()["hostOperationKind"], json!("FS_LIST"));
    assert_eq!(claim.to_json()["path"], json!(""));
}

#[test]
fn fs_actor_work_continues_while_io_suspended() {
    // A spawned managed task performs fs I/O while the input coroutine also
    // yields. Both coroutines are independently resumable.
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        local runtime = require("subspace.runtime")
        local task_done = false
        return {
          startup = function()
            runtime.spawn(function()
              local mount = fs.mount("data")
              fs.mkdir(mount, "task_dir", {parents=false})
              task_done = true
            end)
          end,
          handle_input = function(event)
            local mount = fs.mount("data")
            fs.write_text(mount, "input.txt", "data", {mode="replace"})
            return {ok=true}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    // Start: spawns the managed task
    let start =
        state.invoke_callback_with_spawn_admitter(generation, "startup", "{}", Arc::clone(&host));
    assert_eq!(start.kind(), OutcomeKind::Completed);
    let start_json = start.to_json();
    let spawned = start_json["spawnedCoroutines"].as_array().unwrap();
    let task_co = spawned[0].as_i64().unwrap();

    // Start the task coroutine: it will yield on mkdir
    let task_start =
        state.start_coroutine_with_spawn_admitter(generation, task_co, Arc::clone(&host));
    assert_eq!(task_start.kind(), OutcomeKind::Yielded);
    let task_op = task_start.to_json()["operationId"].as_i64().unwrap();
    let task_req = task_start.to_json()["value"]
        .as_str()
        .unwrap()
        .parse::<i64>()
        .unwrap();

    // Meanwhile, invoke handle_input: it yields on write_text
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Yielded);
    let input_co = input.to_json()["coroutineId"].as_i64().unwrap();
    let input_op = input.to_json()["operationId"].as_i64().unwrap();
    let input_req = input.to_json()["value"]
        .as_str()
        .unwrap()
        .parse::<i64>()
        .unwrap();

    // Both operations are independently claimable
    let task_claim = state.claim_host_operation(generation, task_req);
    assert_eq!(task_claim.to_json()["hostOperationKind"], json!("FS_MKDIR"));
    let input_claim = state.claim_host_operation(generation, input_req);
    assert_eq!(
        input_claim.to_json()["hostOperationKind"],
        json!("FS_WRITE_TEXT")
    );

    // Resume task first
    let task_done = state.resume_coroutine_with_spawn_admitter(
        generation,
        task_co,
        task_op,
        true,
        &json!({"status":"created"}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(task_done.kind(), OutcomeKind::Completed);

    // Then resume input
    let input_done = state.resume_coroutine_with_spawn_admitter(
        generation,
        input_co,
        input_op,
        true,
        &json!({"status":"written","bytes":4}).to_string(),
        Arc::clone(&host),
    );
    assert_eq!(input_done.kind(), OutcomeKind::Completed);
    assert_eq!(value(&input_done), json!({"ok": true}));
}

#[test]
fn fs_mount_userdata_not_serializable_in_callback_result() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("data")
            return {ok=true, mount=mount}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    // The callback result contains a mount userdata → E_INVALID_VALUE
    assert_eq!(
        input.kind(),
        OutcomeKind::RuntimeFailure,
        "expected runtime failure, got {:?}",
        input.to_json()
    );
}

#[test]
fn fs_mount_userdata_not_loggable() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        local log = require("subspace.log")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("data")
            local ok, err = log.info({mount=mount})
            return {ok=true, log_ok=ok, log_err=err and err.error or "nil"}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    // log.info with mount userdata → returns (nil, {error="E_INVALID_VALUE"})
    assert_eq!(input.kind(), OutcomeKind::Completed);
    let v = value(&input);
    assert_eq!(v["log_ok"], json!(null));
    assert_eq!(v["log_err"], json!("E_INVALID_VALUE"));
}

#[test]
fn fs_module_cannot_be_shadowed_via_module_put() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        return {
          startup = function()
            local ok, err = pcall(function()
              subspace.module_put("subspace.fs", {fake=true})
            end)
            assert(not ok, "module_put should have raised E_RESERVED_MODULE")
          end,
          handle_input = function(event) return {ok=true} end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let startup =
        state.invoke_callback_with_spawn_admitter(generation, "startup", "{}", Arc::clone(&host));
    assert_eq!(
        startup.kind(),
        OutcomeKind::Completed,
        "startup should succeed (assert passes): {:?}",
        startup.to_json()
    );
}

#[test]
fn fs_require_reserved_module_returns_real_fs() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function()
            assert(type(fs.mount) == "function")
            assert(type(fs.mkdir) == "function")
            assert(type(fs.stat) == "function")
            assert(type(fs.list) == "function")
            assert(type(fs.read_text) == "function")
            assert(type(fs.write_text) == "function")
            assert(type(fs.remove) == "function")
          end,
          handle_input = function(event) return {ok=true} end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let startup =
        state.invoke_callback_with_spawn_admitter(generation, "startup", "{}", Arc::clone(&host));
    assert_eq!(startup.kind(), OutcomeKind::Completed);
}

#[test]
fn fs_capability_undeclared_rejects_mount() {
    let state = engine();
    let generation = state.handle().generation;
    // No storageFiles declared
    install_rc(&state, json!({ "storageFiles": false, "mounts": {} }));
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount, err = fs.mount("data")
            return {error={code="GOT",detail=err and err.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_CAPABILITY_UNDECLARED"}})
    );
}

#[test]
fn fs_invalid_path_rejected_before_effect() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("data")
            local r, e = fs.mkdir(mount, "../escape", {parents=false})
            return {error={code="GOT",detail=e and e.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_INVALID_PATH"}})
    );
}

#[test]
fn fs_read_only_mount_rejects_write() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "ro": { "access": "read-only", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("ro")
            local r, e = fs.write_text(mount, "f.txt", "data", {mode="create-new"})
            return {error={code="GOT",detail=e and e.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_READ_ONLY"}})
    );
}

#[test]
fn fs_mount_unavailable_rejects_mount() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "unavailable" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount, err = fs.mount("data")
            return {error={code="GOT",detail=err and err.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_MOUNT_UNAVAILABLE"}})
    );
}

#[test]
fn fs_effect_call_during_load_rejected() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    // Module-level call to fs.mount → effect-call-during-load → load fails
    let outcome = state.load_program_image(
        generation,
        &json!({"entry": r#"
        local fs = require("subspace.fs")
        local mount = fs.mount("data")
        return {
          startup = function() end,
          handle_input = function(event) return {ok=true} end,
        }
    "#})
        .to_string(),
        "entry",
    );
    assert_eq!(outcome.kind(), OutcomeKind::RuntimeFailure);
}

#[test]
fn fs_error_normalization_unknown_collapses_to_e_io() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount = fs.mount("data")
            local r, e = fs.stat(mount, "f.txt")
            return {error={code="GOT",detail=e and e.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Yielded);
    let co = input.to_json()["coroutineId"].as_i64().unwrap();
    let op = input.to_json()["operationId"].as_i64().unwrap();
    let req = input.to_json()["value"]
        .as_str()
        .unwrap()
        .parse::<i64>()
        .unwrap();
    state.claim_host_operation(generation, req);
    // Resume with an unknown error string → should normalize to E_IO
    let done = state.resume_coroutine(generation, co, op, false, "E_UNKNOWN_PLATFORM_ERROR");
    assert_eq!(done.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&done),
        json!({"error":{"code":"GOT","detail":"E_IO"}})
    );
}

#[test]
fn fs_io_from_startup_context_rejected() {
    // fs I/O is only eligible from handle_input or a managed task. Startup
    // (synchronous callback) must receive E_INVALID_CONTEXT before any effect.
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function()
            local mount = fs.mount("data")
            local r, e = fs.stat(mount, "f.txt")
            startup_result = { r = r, e = e and e.error or "nil" }
          end,
          probe = function() return startup_result end,
          handle_input = function(event) return {ok=true} end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    // mount() is a synchronous lookup allowed in startup; stat() is I/O and
    // must fail E_INVALID_CONTEXT in a synchronous callback.
    let startup =
        state.invoke_callback_with_spawn_admitter(generation, "startup", "{}", Arc::clone(&host));
    assert_eq!(
        startup.kind(),
        OutcomeKind::Completed,
        "{:?}",
        startup.to_json()
    );
    let probe =
        state.invoke_callback_with_spawn_admitter(generation, "probe", "{}", Arc::clone(&host));
    let v = value(&probe);
    assert_eq!(v["r"], json!(null));
    assert_eq!(v["e"], json!("E_INVALID_CONTEXT"));
}

#[test]
fn fs_mount_handle_invalidated_after_resource_context_replaced() {
    // A mount handle minted under one resource context must fail live
    // revalidation after the context is replaced: replacement clears the mount
    // registry, so the stale handle resolves to E_STALE before any effect.
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        local holder = {}
        return {
          startup = function() end,
          handle_input = function(event)
            if event and event.fresh then
              holder.mount = fs.mount("data")
              return {ok=true}
            end
            local r, e = fs.stat(holder.mount, "f.txt")
            return {error={code="GOT",detail=e and e.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );
    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    // First input mints a live mount handle into the module-local holder.
    let fresh = state.invoke_input_callback_with_spawn_admitter(
        generation,
        r#"{"fresh":true,"metadata":{"duration_ms":1,"sample_rate":16000,"channels":1,"pcm_bytes":32}}"#,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(
        fresh.kind(),
        OutcomeKind::Completed,
        "{:?}",
        fresh.to_json()
    );
    assert_eq!(value(&fresh), json!({"ok": true}));

    // Replace the resource context: this clears the mount registry, so the
    // handle stored in `holder` is now stale.
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );

    // A subsequent I/O call with the stale handle fails E_STALE before effect.
    let stale = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(
        stale.kind(),
        OutcomeKind::Completed,
        "{:?}",
        stale.to_json()
    );
    assert_eq!(
        value(&stale),
        json!({"error":{"code":"GOT","detail":"E_STALE"}})
    );
}

#[test]
fn fs_unknown_mount_id_rejected() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "available" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount, err = fs.mount("nonexistent")
            return {error={code="GOT",detail=err and err.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_INVALID_ARGUMENT"}})
    );
}

#[test]
fn fs_needs_reauthorization_status_rejected() {
    let state = engine();
    let generation = state.handle().generation;
    install_rc(
        &state,
        json!({
            "storageFiles": true,
            "mounts": { "data": { "access": "read-write", "status": "needs-reauthorization" } }
        }),
    );
    load_image(
        &state,
        json!({"entry": r#"
        local fs = require("subspace.fs")
        return {
          startup = function() end,
          handle_input = function(event)
            local mount, err = fs.mount("data")
            return {error={code="GOT",detail=err and err.error or "nil"}}
          end,
        }
    "#}),
        "entry",
    );

    let host: Arc<dyn SpawnAdmitter> = Arc::new(AcceptAdmitter);
    let input = state.invoke_input_callback_with_spawn_admitter(
        generation,
        CAPTURE_EVENT,
        "tok",
        Arc::clone(&host),
    );
    assert_eq!(input.kind(), OutcomeKind::Completed);
    assert_eq!(
        value(&input),
        json!({"error":{"code":"GOT","detail":"E_REAUTHORIZATION_REQUIRED"}})
    );
}
