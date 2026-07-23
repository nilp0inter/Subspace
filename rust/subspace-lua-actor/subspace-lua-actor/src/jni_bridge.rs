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
//! `LuaNativeKernel`. The JVM resolves native methods via name mangling:
//! `Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_native<Name>`.
//!
//! # State registry
//!
//! States are stored in a process-global [`LazyLock<Mutex<Registry>>`] keyed by
//! [`StateId`]. The JNI passes `(stateId, generation)` opaque handles; we look
//! up by `stateId` and let the engine validate `generation`.
//!
//! ## Mutex discipline (split-lock)
//!
//! The **registry mutex** is held only briefly for entry lookup/clone
//! (microseconds) and for the admit+tombstone critical section inside close.
//! It is **never** held across Lua execution. Engine operations acquire the
//! **per-state mutex** (inside [`StateEngine`]) with the registry lock
//! already released. This ensures one long-running Lua state cannot block
//! unrelated states from dispatching.
//!
//! ## Close protocol (typed, no unsafe)
//!
//! Closing atomically replaces a [`Live`] entry with a [`Tombstone`] that
//! records the generation at close time. A close **only** tombstones if:
//!
//! - the entry is `Live`,
//! - the engine is not yet closed, and
//! - the caller's generation matches the live generation (admitted close).
//!
//! A stale/foreign/unknown close returns the appropriate outcome without
//! mutating the registry — the live engine is preserved exactly. Repeated
//! close on an already-tombstoned state returns `closed` idempotently.
//!
//! ## Bounded tombstone history
//!
//! Tombstone entries are FIFO-bounded by [`MAX_TOMBSTONES`]. When the count
//! of tombstones exceeds the bound, the oldest (by close order) is evicted.
//! Live entries are never evicted. Evicted state IDs become `invalid_ownership`
//! on late dispatch — they do not block new state IDs.

use std::collections::{HashMap, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;
use std::sync::{LazyLock, Mutex};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::json;

use crate::outcome::{Outcome, OutcomeKind};
use crate::ownership::{Generation, OperationId, StateId};
use crate::state::{SpawnAdmission, SpawnAdmitter, StateEngine};

struct HostAdmitter {
    vm: jni::JavaVM,
    host: GlobalRef,
}

impl SpawnAdmitter for HostAdmitter {
    fn admit(&self, coroutine_id: i64) -> SpawnAdmission {
        let mut env = match self.vm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return SpawnAdmission::Closed,
        };
        match env
            .call_method(
                &self.host,
                "admitTask",
                "(J)I",
                &[JValue::Long(coroutine_id)],
            )
            .and_then(|v| v.i())
        {
            Ok(0) => SpawnAdmission::Accepted,
            Ok(2) => SpawnAdmission::Capacity,
            _ => {
                let _ = env.exception_clear();
                SpawnAdmission::Closed
            }
        }
    }
}

fn spawn_admitter(
    env: &mut JNIEnv,
    host_admitter: JObject,
) -> Result<Arc<dyn SpawnAdmitter>, Outcome> {
    let vm = env
        .get_java_vm()
        .map_err(|_| Outcome::runtime_failure("unable to acquire JavaVM for spawn admission"))?;
    let host = env
        .new_global_ref(host_admitter)
        .map_err(|_| Outcome::runtime_failure("unable to retain host spawn admission"))?;
    Ok(Arc::new(HostAdmitter { vm, host }))
}

// ---------------------------------------------------------------------------
// Registry (process-global, split-lock, bounded tombstone history)
// ---------------------------------------------------------------------------

/// Maximum tombstone entries retained in the registry. When this count is
/// exceeded the oldest tombstone (by close order) is evicted.
const MAX_TOMBSTONES: usize = 16;

struct Registry {
    entries: HashMap<StateId, RegistryEntry>,
    /// FIFO of state ids whose entries are tombstones, in close order.
    /// Used for bounded eviction: pop front when `tombstone_count > MAX_TOMBSTONES`.
    tombstone_fifo: VecDeque<StateId>,
}

impl Registry {
    fn new() -> Self {
        Registry {
            entries: HashMap::new(),
            tombstone_fifo: VecDeque::new(),
        }
    }

    /// Count tombstones currently in the map.
    fn tombstone_count(&self) -> usize {
        self.entries
            .values()
            .filter(|e| matches!(e, RegistryEntry::Tombstone { .. }))
            .count()
    }

    /// Insert a tombstone entry for `state_id`, record its close order, and
    /// evict the oldest tombstone if the bound would be exceeded.
    fn insert_tombstone(&mut self, state_id: StateId, closed_generation: Generation) {
        // Defensive: ensure any stale fifo entry for this id is cleaned.
        self.tombstone_fifo.retain(|id| *id != state_id);

        let tomb_count = self.tombstone_count();
        self.entries
            .insert(state_id, RegistryEntry::Tombstone { closed_generation });
        self.tombstone_fifo.push_back(state_id);

        if tomb_count >= MAX_TOMBSTONES {
            // Evict the oldest tombstone that's still in the map.
            while let Some(evicted) = self.tombstone_fifo.pop_front() {
                if matches!(
                    self.entries.get(&evicted),
                    Some(RegistryEntry::Tombstone { .. })
                ) {
                    self.entries.remove(&evicted);
                    break;
                }
            }
        }
    }
}

/// A registry entry: either a live engine or a tombstone from a closed engine.
enum RegistryEntry {
    /// A live engine that can process commands. Wrapped in `Arc` so the
    /// split-lock pattern can clone it cheaply under the registry mutex and
    /// then release the lock before invoking the engine.
    Live(Arc<StateEngine>),
    /// A closed engine. Retains only the generation at close time so repeated
    /// close returns `closed` (idempotent) and late calls with a stale
    /// generation return `stale`/`closed` without retaining the Lua VM.
    Tombstone { closed_generation: Generation },
}

/// Process-global registry of live + closed state engines keyed by state id.
static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| Mutex::new(Registry::new()));

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
        Err(_) => std::ptr::null_mut(),
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

// ---------------------------------------------------------------------------
// Non-close dispatch (split-lock: registry mutex → clone → release → engine)
// ---------------------------------------------------------------------------

/// Look up a state engine by id and run a closure on it **without** holding
/// the registry mutex during closure execution.
///
/// 1. Acquires the registry mutex, looks up the entry.
/// 2. If `Tombstone`: returns `closed`/`stale` immediately (no closure call).
/// 3. If `Live`: clones the `Arc<StateEngine>` and drops the registry lock.
/// 4. Calls `f(&engine)` — the closure runs under the per-state mutex only.
///
/// This guarantees the process-global registry mutex is never held across
/// Lua execution, so one long-running state cannot block unrelated states.
fn with_state<F>(state_id: StateId, generation: Generation, f: F) -> Outcome
where
    F: FnOnce(&StateEngine) -> Outcome,
{
    let engine = {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        match guard.entries.get(&state_id) {
            None => {
                return Outcome::invalid_ownership(format!("state id {state_id} not found"));
            }
            Some(RegistryEntry::Tombstone { closed_generation }) => {
                if generation != *closed_generation {
                    return Outcome::stale(state_id, generation);
                }
                return Outcome::closed(state_id, *closed_generation);
            }
            Some(RegistryEntry::Live(engine)) => engine.clone(),
        }
    };
    // Registry lock is released here — only the per-state mutex will be
    // acquired inside f.
    f(&engine)
}

// ---------------------------------------------------------------------------
// Close dispatch (admit-only tombstone, bounded eviction)
// ---------------------------------------------------------------------------

/// Process a close command. Tombsones the entry **iff** the close was
/// admitted by the engine (live, matching generation, not already closed).
///
/// A stale/foreign/unknown close returns the appropriate outcome without
/// mutating the registry. Repeated close on an already-tombstoned state
/// returns `closed` idempotently.
fn close_internal(state_id: StateId, generation: Generation) -> Outcome {
    // Phase 1: lookup under registry lock, clone Arc, drop lock.
    let engine = {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        match guard.entries.get(&state_id) {
            None => {
                return Outcome::invalid_ownership(format!("state id {state_id} not found"));
            }
            Some(RegistryEntry::Tombstone { closed_generation }) => {
                return Outcome::closed(state_id, *closed_generation);
            }
            Some(RegistryEntry::Live(engine)) => engine.clone(),
        }
    };

    // Phase 2: engine close under the per-state mutex (registry lock NOT
    // held).  The engine bumps its generation, drops the Lua VM, and
    // returns the pre-close generation in the outcome.
    let outcome = engine.close(generation);

    // Phase 3: tombstone writeback — only on an **admitted** close.
    //
    // An admitted close returns `Closed` with the pre-close generation
    // matching the caller's generation.  The engine's idempotent-already-
    // closed short-circuit returns `Closed` with the bumped (post-close)
    // generation, which differs from the requested `generation` — that
    // path is excluded.
    if outcome.kind() == OutcomeKind::Closed {
        let outcome_gen = outcome.to_json().get("generation").and_then(|v| v.as_i64());
        if outcome_gen == Some(generation) {
            let mut guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
            // Defensive: only tombstone if the entry is still Live (a
            // concurrent admit-close may have already replaced it).
            if matches!(guard.entries.get(&state_id), Some(RegistryEntry::Live(_))) {
                guard.insert_tombstone(state_id, generation);
            }
        }
    }

    outcome
}

// ---------------------------------------------------------------------------
// JNI exports
//
// Names match: Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_native<Name>
// ---------------------------------------------------------------------------

/// `nativeCreate(memoryLimitBytes: Long, hookInterval: Int, instructionBudget: Long, maxConcurrentTasks: Int, maxTimerSlots: Int): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    memory_limit_bytes: jlong,
    hook_interval: jint,
    instruction_budget: jlong,
    max_concurrent_tasks: jint,
    max_timer_slots: jint,
) -> jstring {
    jni_body(&mut env, |_env| {
        let memory_limit = if memory_limit_bytes < 0 {
            0
        } else {
            memory_limit_bytes as u64
        };
        let hook_int = if hook_interval < 0 {
            0
        } else {
            hook_interval as u32
        };
        let budget = if instruction_budget < 0 {
            0
        } else {
            instruction_budget as u64
        };
        let max_tasks = if max_concurrent_tasks <= 0 {
            16
        } else {
            max_concurrent_tasks as usize
        };
        let max_slots = if max_timer_slots <= 0 {
            16
        } else {
            max_timer_slots as usize
        };

        match StateEngine::new(memory_limit, hook_int, budget, max_tasks, max_slots) {
            Ok(engine) => {
                let handle = engine.handle();
                let state_id = handle.state_id;
                let generation = handle.generation;

                let mut registry = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
                registry
                    .entries
                    .insert(state_id, RegistryEntry::Live(Arc::new(engine)));

                Outcome::created(state_id, generation, "jvm_owned")
            }
            Err(outcome) => outcome,
        }
    })
}

/// `nativeLoad(stateId: Long, generation: Long, source: String, entrypoint: String): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeLoad(
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

        with_state(sid, gen, |engine| engine.load(gen, &src, &entry))
    })
}

/// `nativeStart(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let reject_spawns: Arc<dyn SpawnAdmitter> = Arc::new(|_| SpawnAdmission::Closed);
        with_state(state_id as StateId, generation as Generation, |engine| {
            engine.start_with_spawn_admitter(generation as Generation, reject_spawns)
        })
    })
}

/// `nativeResume(stateId, generation, coroutineId, operationId, success, value, hostAdmitter): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeResume(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    coroutine_id: jlong,
    operation_id: jlong,
    success: jni::sys::jboolean,
    value: JString,
    host_admitter: JObject,
) -> jstring {
    jni_body(&mut env, |env| {
        let val = match jstring_to_rust(env, &value) {
            Some(value) => value,
            None => return Outcome::validation_failure("value string is invalid"),
        };
        let admitter = match spawn_admitter(env, host_admitter) {
            Ok(admitter) => admitter,
            Err(outcome) => return outcome,
        };
        with_state(state_id as StateId, generation as Generation, |engine| {
            engine.resume_coroutine_with_spawn_admitter(
                generation as Generation,
                coroutine_id as i64,
                operation_id as OperationId,
                success != 0,
                &val,
                admitter,
            )
        })
    })
}

/// `nativeCancel(stateId: Long, generation: Long, coroutineId: Long, operationId: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeCancel(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    coroutine_id: jlong,
    operation_id: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let co_id = coroutine_id as i64;
        let op_id = operation_id as OperationId;
        with_state(sid, gen, |engine| {
            engine.cancel_coroutine(gen, co_id, op_id)
        })
    })
}

/// `nativeInterrupt(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInterrupt(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        with_state(sid, gen, |engine| engine.interrupt(gen))
    })
}

/// `nativeSnapshot(stateId: Long, generation: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeSnapshot(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let outcome = with_state(sid, gen, |engine| engine.snapshot(gen));
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
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeClose(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        close_internal(sid, gen)
    })
}

/// `nativeLoadProgramImage(stateId: Long, generation: Long, sourceMapJson: String, entrypoint: String): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeLoadProgramImage(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    source_map_json: JString,
    entrypoint: JString,
) -> jstring {
    jni_body(&mut env, |env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;

        let map_json = match jstring_to_rust(env, &source_map_json) {
            Some(s) => s,
            None => return Outcome::validation_failure("sourceMapJson string is invalid"),
        };
        let entry = match jstring_to_rust(env, &entrypoint) {
            Some(s) => s,
            None => return Outcome::validation_failure("entrypoint string is invalid"),
        };

        with_state(sid, gen, |engine| {
            engine.load_program_image(gen, &map_json, &entry)
        })
    })
}

/// `nativeInvokeCallback(stateId: Long, generation: Long, callbackName: String, argumentsJson: String, hostAdmitter: Object): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInvokeCallback(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    callback_name: JString,
    arguments_json: JString,
    host_admitter: JObject,
) -> jstring {
    jni_body(&mut env, |env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let name = match jstring_to_rust(env, &callback_name) {
            Some(s) => s,
            None => return Outcome::validation_failure("callbackName string is invalid"),
        };
        let args = match jstring_to_rust(env, &arguments_json) {
            Some(s) => s,
            None => return Outcome::validation_failure("argumentsJson string is invalid"),
        };
        let vm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(_) => {
                return Outcome::runtime_failure("unable to acquire JavaVM for spawn admission")
            }
        };
        let host: GlobalRef = match env.new_global_ref(host_admitter) {
            Ok(host) => host,
            Err(_) => return Outcome::runtime_failure("unable to retain host spawn admission"),
        };
        let admitter: Arc<dyn SpawnAdmitter> = Arc::new(move |coroutine_id| {
            let mut callback_env = match vm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return SpawnAdmission::Closed,
            };
            let decision = callback_env
                .call_method(&host, "admitTask", "(J)I", &[JValue::Long(coroutine_id)])
                .and_then(|value| value.i());
            match decision {
                Ok(0) => SpawnAdmission::Accepted,
                Ok(2) => SpawnAdmission::Capacity,
                _ => {
                    let _ = callback_env.exception_clear();
                    SpawnAdmission::Closed
                }
            }
        });
        with_state(sid, gen, |engine| {
            engine.invoke_callback_with_spawn_admitter(gen, &name, &args, admitter)
        })
    })
}

/// `nativeInvokeInputCallback(stateId: Long, generation: Long, argumentsJson: String, capturedAudioToken: String, hostAdmitter: Object): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInvokeInputCallback(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    arguments_json: JString,
    captured_audio_token: JString,
    host_admitter: JObject,
) -> jstring {
    jni_body(&mut env, |env| {
        let args = match jstring_to_rust(env, &arguments_json) {
            Some(s) => s,
            None => return Outcome::validation_failure("argumentsJson string is invalid"),
        };
        let token = match jstring_to_rust(env, &captured_audio_token) {
            Some(s) => s,
            None => return Outcome::validation_failure("capturedAudioToken string is invalid"),
        };
        let admitter = match spawn_admitter(env, host_admitter) {
            Ok(admitter) => admitter,
            Err(outcome) => return outcome,
        };
        with_state(state_id as StateId, generation as Generation, |engine| {
            engine.invoke_input_callback_with_spawn_admitter(
                generation as Generation,
                &args,
                &token,
                admitter,
            )
        })
    })
}

/// `nativeStartCoroutine(stateId, generation, coroutineId, hostAdmitter): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeStartCoroutine(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    coroutine_id: jlong,
    host_admitter: JObject,
) -> jstring {
    jni_body(&mut env, |env| {
        let admitter = match spawn_admitter(env, host_admitter) {
            Ok(admitter) => admitter,
            Err(outcome) => return outcome,
        };
        with_state(state_id as StateId, generation as Generation, |engine| {
            engine.start_coroutine_with_spawn_admitter(
                generation as Generation,
                coroutine_id as i64,
                admitter,
            )
        })
    })
}

/// `nativeClaimHostOperation(stateId: Long, generation: Long, requestId: Long): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeClaimHostOperation(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    request_id: jlong,
) -> jstring {
    jni_body(&mut env, |_env| {
        with_state(state_id as StateId, generation as Generation, |engine| {
            engine.claim_host_operation(generation as Generation, request_id as i64)
        })
    })
}

/// `nativeSetResourceContext(stateId: Long, generation: Long, resourceContextJson: String): String`
#[no_mangle]
pub extern "system" fn Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeSetResourceContext(
    mut env: JNIEnv,
    _class: JClass,
    state_id: jlong,
    generation: jlong,
    resource_context_json: JString,
) -> jstring {
    jni_body(&mut env, |env| {
        let sid = state_id as StateId;
        let gen = generation as Generation;
        let json = match jstring_to_rust(env, &resource_context_json) {
            Some(s) => s,
            None => return Outcome::validation_failure("resourceContextJson string is invalid"),
        };
        with_state(sid, gen, |engine| engine.set_resource_context(gen, &json))
    })
}
// Prevent LLVM from stripping the JNI symbols in release builds.
#[used]
static JNI_SYMBOLS: &[&str] = &[
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeCreate",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeLoad",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeStart",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeResume",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeCancel",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInterrupt",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeSnapshot",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeClose",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeLoadProgramImage",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInvokeCallback",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeInvokeInputCallback",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeStartCoroutine",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeClaimHostOperation",
    "Java_dev_nilp0inter_subspace_lua_LuaNativeKernel_nativeSetResourceContext",
];

// ---------------------------------------------------------------------------
// Test / registry-test seam (only compiled for cargo test or explicit feature)
// ---------------------------------------------------------------------------

/// Test-only registry inspection and manipulation.  Not available in
/// production builds.  Enabled under `cargo test` or the `registry-test`
/// Cargo feature.
///
/// # Mutex discipline
///
/// All functions acquire the registry mutex internally and release it before
/// returning.  Tests must NOT hold the registry mutex across a call into any
/// of these functions — they are not re-entrant.  Tests may safely hold the
/// per-state mutex (by calling engine methods) while calling `entry()` or
/// `live_count()`, because the lock order is registry → per-state (never
/// reversed).
#[cfg(any(test, feature = "registry-test"))]
pub mod registry_test {
    use super::*;

    /// Maximum number of tombstone entries retained in the registry
    /// (re-exported for test assertions).
    pub const MAX_TOMBSTONES: usize = super::MAX_TOMBSTONES;

    /// Observable kind of a registry entry.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum Entry {
        /// A live engine with the given current generation.
        Live { generation: Generation },
        /// A closed engine that retains only the generation at close time.
        Tombstone { closed_generation: Generation },
        /// No entry for this state id (never registered or evicted).
        Missing,
    }

    /// Inspect the registry entry for `state_id` without acquiring the
    /// per-state mutex (the engine mutex is acquired briefly inside
    /// `StateEngine::handle` just to read the live generation).
    pub fn entry(state_id: StateId) -> Entry {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        match guard.entries.get(&state_id) {
            None => Entry::Missing,
            Some(RegistryEntry::Tombstone { closed_generation }) => Entry::Tombstone {
                closed_generation: *closed_generation,
            },
            Some(RegistryEntry::Live(engine)) => Entry::Live {
                generation: engine.handle().generation,
            },
        }
    }

    /// Insert a fresh live engine (its baked-in `state_id` is the lookup
    /// key).  If a tombstone for the same id somehow exists it is replaced.
    pub fn register(engine: StateEngine) {
        let state_id = engine.state_id();
        let mut guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        // state_ids are globally unique — a tombstone for this id should
        // never coexist, but handle it defensively.
        guard.tombstone_fifo.retain(|id| *id != state_id);
        guard
            .entries
            .insert(state_id, RegistryEntry::Live(Arc::new(engine)));
    }

    /// Lookup-only non-close dispatch.  Acquires the registry mutex briefly
    /// to clone an `Arc<StateEngine>`, releases it, then invokes
    /// `f(&engine)`.  The registry mutex is never held across `f`.
    ///
    /// This is the same code path as the production `with_state` (private).
    /// It is exposed so conformance tests can assert the split-lock behavior.
    pub fn dispatch<F>(state_id: StateId, generation: Generation, f: F) -> Outcome
    where
        F: FnOnce(&StateEngine) -> Outcome,
    {
        super::with_state(state_id, generation, f)
    }

    /// Tombstoning close.  Only installs a `Tombstone` if the close was
    /// admitted (state is `Live`, generation matches, not already closed).
    /// Stale/foreign/unknown close returns the outcome without mutating the
    /// registry.
    ///
    /// This is the same code path as `nativeClose` and `close_internal`.
    pub fn close(state_id: StateId, generation: Generation) -> Outcome {
        super::close_internal(state_id, generation)
    }

    /// Remove a registry entry entirely (both `Live` and `Tombstone`).
    /// Cleans up the tombstone FIFO as well.  After removal, `entry(id)`
    /// returns `Missing`.
    pub fn remove(state_id: StateId) {
        let mut guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        guard.entries.remove(&state_id);
        guard.tombstone_fifo.retain(|id| *id != state_id);
    }

    /// Number of currently-live (`StateEngine`) entries.
    pub fn live_count() -> usize {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        guard
            .entries
            .values()
            .filter(|e| matches!(e, RegistryEntry::Live(_)))
            .count()
    }

    /// Number of tombstone entries (closed states with retained generation).
    pub fn tombstone_count() -> usize {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        guard.tombstone_count()
    }

    /// Total entries (live + tombstone).
    pub fn total_count() -> usize {
        let guard = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
        guard.entries.len()
    }

    /// Returns `true` if the global registry mutex is **not** currently held.
    ///
    /// Deterministic split-lock probe: under the split-lock refactor the
    /// registry mutex is released before the dispatch closure runs, so a
    /// closure that calls this function returns `true`. Under the old
    /// global-lock model (where the closure ran while holding the registry
    /// mutex) this would return `false`, since `try_lock` would fail.
    ///
    /// Tests should call this *inside* the `dispatch`/`with_state` closure
    /// to assert the registry is free during Lua execution.
    pub fn registry_lock_available_for_test() -> bool {
        REGISTRY.try_lock().is_ok()
    }
}
