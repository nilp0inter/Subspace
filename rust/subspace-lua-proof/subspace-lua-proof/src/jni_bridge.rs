//! JNI bridge exports.
//!
//! Every function returns a JSON string ([`Outcome::to_json_string`]) and must
//! not throw for expected input. All JNI function bodies are wrapped in
//! [`std::panic::catch_unwind`] so a Rust panic never propagates across the JNI
//! boundary into the JVM. Expected errors (Lua failures, ownership rejections,
//! validation errors) are converted to JSON outcomes.
//!
//! # JNI function names
//!
//! The JNI name must match the Kotlin `external fun` declaration in
//! `LuaProofNative`. The JVM resolves native methods via name mangling:
//! `Java_dev_nilp0inter_subspace_lua_LuaProofNative_native<Name>`.
//!
//! # State registry
//!
//! States are stored in a process-global [`LazyLock<Mutex<HashMap>`] keyed by
//! [`StateId`]. The JNI passes `(stateId, generation)` opaque handles; we look
//! up by `stateId` and let the engine validate `generation`.
//!
//! After close the live [`StateEngine`] is replaced with a [`Tombstone`] that
//! records the generation at close time. This lets repeated close return
//! `closed` (idempotent) and late operations with a stale generation return
//! `stale`/`closed` without retaining the Lua VM.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{LazyLock, Mutex};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::json;

use crate::outcome::Outcome;
use crate::ownership::{Generation, OperationId, StateId};
use crate::state::StateEngine;
use crate::topology::Topology;

/// Process-global registry of live + closed state engines keyed by state id.
static REGISTRY: LazyLock<Mutex<HashMap<StateId, RegistryEntry>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// A registry entry: either a live engine or a tombstone from a closed engine.
enum RegistryEntry {
    /// A live engine that can process commands.
    Live(StateEngine),
    /// A closed engine. Retains only the generation at close time so repeated
    /// close returns `closed` (idempotent) and late calls with a stale
    /// generation return `stale`/`closed` without retaining the Lua VM.
    Tombstone { closed_generation: Generation },
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

/// Extract a Rust String from a JNI JString. `JavaStr` uses JNI modified
/// UTF-8, so convert through its owned `String` implementation; calling
/// `to_str()` on the raw bytes incorrectly rejects Java strings containing
/// U+0000, including binary-chunk probes.
fn jstring_to_rust(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

/// Create a JNI return string from a Rust String. Returns a null jstring on
/// failure (the JSON serialization itself should never fail).
fn rust_to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => {
            // Fallback: return a minimal error JSON. This path should be
            // unreachable for valid UTF-8 outcome strings.
            match env.new_string(r#"{"kind":"runtime_failure","diagnostic":"jni string encoding failed"}"#) {
                Ok(fallback) => fallback.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// The catch-all JNI body wrapper. Runs the closure, catches panics, and
/// converts everything to a JSON outcome string. This is the single point
/// that guarantees no Rust panic crosses the JNI boundary.
fn jni_body<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> Outcome + std::panic::RefUnwindSafe,
{
    let result = catch_unwind(AssertUnwindSafe(|| f(env)));
    let outcome = match result {
        Ok(o) => o,
        Err(panic_payload) => {
            let msg = panic_payload
                .downcast_ref::<&'static str>()
                .copied()
                .or_else(|| panic_payload.downcast_ref::<String>().map(|s| s.as_str()))
                .unwrap_or("unknown panic");
            Outcome::runtime_failure(format!("native panic: {msg}"))
        }
    };
    rust_to_jstring(env, outcome.to_json_string())
}

/// Look up a state engine by id and run a closure on it.
///
/// If the state is not found at all, returns `invalid_ownership`. If the
/// state has been closed (tombstone): close commands return `closed`
/// (idempotent) regardless of generation; non-close commands with a stale
/// generation return `stale`; non-close commands with the pre-close
/// generation return `closed`.
fn with_state<F>(state_id: StateId, generation: Generation, is_close: bool, f: F) -> Outcome
where
    F: FnOnce(&StateEngine) -> Outcome,
{
    let registry = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
    match registry.get(&state_id) {
        Some(RegistryEntry::Live(engine)) => f(engine),
        Some(RegistryEntry::Tombstone { closed_generation }) => {
            if is_close {
                // Idempotent: any generation on a tombstone returns closed.
                Outcome::closed(state_id, *closed_generation)
            } else if generation != *closed_generation {
                // Stale generation on a closed state.
                Outcome::stale(state_id, generation)
            } else {
                // Matching the pre-close generation on a closed state: the
                // state is closed, not just stale.
                Outcome::closed(state_id, *closed_generation)
            }
        }
        None => Outcome::invalid_ownership(format!("state id {state_id} not found")),
    }
}

// ---------------------------------------------------------------------------
// JNI exports
//
// Names match: Java_dev_nilp0inter_subspace_lua_LuaProofNative_native<Name>
// ---------------------------------------------------------------------------

/// `nativeCreate(topology: String, memoryLimitBytes: Long, hookInterval: Int, instructionBudget: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    topology: JString,
    memory_limit_bytes: jlong,
    hook_interval: jint,
    instruction_budget: jlong,
) -> jstring {
    jni_body(&mut env, |env| {
        let topo_str = match jstring_to_rust(env, &topology) {
            Some(s) => s,
            None => return Outcome::validation_failure("topology string is invalid"),
        };
        let topology = match Topology::parse(&topo_str) {
            Some(t) => t,
            None => {
                return Outcome::validation_failure(format!(
                    "unknown topology: '{topo_str}' (expected jvm_owned)"
                ))
            }
        };
        let memory_limit = if memory_limit_bytes < 0 { 0 } else { memory_limit_bytes as u64 };
        let hook_int = if hook_interval < 0 { 0 } else { hook_interval as u32 };
        let budget = if instruction_budget < 0 { 0 } else { instruction_budget as u64 };

        match StateEngine::new(topology, memory_limit, hook_int, budget) {
            Ok(engine) => {
                let handle = engine.handle();
                let state_id = handle.state_id;
                let generation = handle.generation;

                let mut registry = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
                registry.insert(state_id, RegistryEntry::Live(engine));

                Outcome::created(state_id, generation, topology.as_str())
            }
            Err(outcome) => outcome,
        }
    })
}

/// `nativeLoad(stateId: Long, generation: Long, source: String, entrypoint: String): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    source: JString,
    entrypoint: JString,
) -> jstring {
    jni_body(&mut env, |env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;

        let src = match jstring_to_rust(env, &source) {
            Some(s) => s,
            None => return Outcome::validation_failure("source string is invalid"),
        };
        let entry = match jstring_to_rust(env, &entrypoint) {
            Some(s) => s,
            None => return Outcome::validation_failure("entrypoint string is invalid"),
        };

        with_state(sid, gen, false, |engine| engine.load(gen, &src, &entry))
    })
}

/// `nativeStart(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        with_state(sid, gen, false, |engine| engine.start(gen))
    })
}

/// `nativeResume(stateId: Long, generation: Long, operationId: Long, success: Boolean, value: String): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeResume(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    operation_id: jlong,
    success: jni::sys::jboolean,
    value: JString,
) -> jstring {
    jni_body(&mut env, |env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let op_id = operation_id as OperationId;
        let ok = success != 0;

        let val = match jstring_to_rust(env, &value) {
            Some(s) => s,
            None => return Outcome::validation_failure("value string is invalid"),
        };

        with_state(sid, gen, false, |engine| engine.resume(gen, op_id, ok, &val))
    })
}

/// `nativeCancel(stateId: Long, generation: Long, operationId: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeCancel(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    operation_id: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let op_id = operation_id as OperationId;
        with_state(sid, gen, false, |engine| engine.cancel(gen, op_id))
    })
}

/// `nativeInterrupt(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeInterrupt(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        with_state(sid, gen, false, |engine| engine.interrupt(gen))
    })
}

/// `nativeSnapshot(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeSnapshot(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let outcome = with_state(sid, gen, false, |engine| engine.snapshot(gen));
        // Tag snapshot outcomes so the Kotlin codec can distinguish them from
        // ordinary `completed` outcomes without adding a new `kind`.
        if outcome.kind() == crate::outcome::OutcomeKind::Completed {
            outcome.with("operation", json!("snapshot"))
        } else {
            outcome
        }
    })
}

/// `nativeClose(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeClose(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;

        let outcome = with_state(sid, gen, true, |engine| engine.close(gen));

        // Replace live engine with a tombstone so repeated close and late calls
        // get correct closed/stale semantics without retaining the Lua VM.
        // The engine.close() returns Closed with the pre-close generation if
        // the close was admitted, so we capture that as closed_generation.
        let closed_gen = match outcome.kind() {
            crate::outcome::OutcomeKind::Closed => {
                // The close outcome carries the pre-close generation.
                outcome
                    .to_json()
                    .get("generation")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(gen)
            }
            _ => gen,
        };

        let mut registry = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        registry.insert(sid, RegistryEntry::Tombstone { closed_generation: closed_gen });

        outcome
    })
}

// Prevent LLVM from stripping the JNI symbols in release builds.
#[used]
static JNI_SYMBOLS: &[&str] = &[
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeCreate",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeLoad",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeStart",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeResume",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeCancel",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeInterrupt",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeSnapshot",
    "Java_dev_nilp0inter_subspace_lua_LuaProofNative_nativeClose",
];