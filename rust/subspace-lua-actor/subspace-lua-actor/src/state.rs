//! The common state engine.
//!
//! [`StateEngine`] owns the restricted stock Lua 5.4 VM, the ownership/terminal
//! state machine, per-state allocator accounting, the instruction hook + budget,
//! the interrupt flag, and the operation id space. The engine drives the same
//! [`EngineInner::process`] logic; the caller thread acquires the per-state
//! mutex directly.
//!
//! # Restricted standard library
//!
//! The VM is created with `StdLib::COROUTINE | TABLE | STRING | UTF8 | MATH`.
//! No `IO`, `OS`, `PACKAGE`, `DEBUG`, or `FFI`. Because `PACKAGE` is absent
//! there is no `package.loadlib`, no `require`, and no dynamic C searchers.
//! The base library (`_G`, loaded unconditionally by mlua via `luaopen_base`)
//! is safe and retained.
//!
//! # Text-only loading
//!
//! Source chunks are loaded with [`mlua::chunk::ChunkMode::Text`], forcing text
//! mode. Binary chunks (starting with `\x1b` — the Lua signature) are rejected
//! at the parser level as `syntax_failure`.
//!
//! # Yielding operations
//!
//! Before user source loads, a `subspace` global is injected:
//! ```lua
//! subspace.yield_operation = function(label)
//!   return coroutine.yield(tostring(label))
//! end
//! ```
//! When the entrypoint calls `subspace.yield_operation("fetch")`, the coroutine
//! suspends and `Thread::resume` returns `"fetch"`. The engine assigns an
//! operation id, records the yielded state, and returns a `yielded` outcome.
//! On resume, the host passes `(success, value)` which becomes the return of
//! `coroutine.yield` inside Lua.

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use mlua::{
    chunk::ChunkMode, thread::ThreadStatus, Error as LuaError, Function, HookTriggers, Lua,
    LuaOptions, StdLib, Thread, Value, VmState,
};
use serde_json::json;

use crate::accounting::{Accountant, MemoryReport};
use crate::outcome::{Outcome, OutcomeKind};
use crate::ownership::{
    assign_operation_id, assign_state_id, Generation, Lifecycle, OperationId, OperationVerdict,
    OwnershipVerdict, StateHandle, StateId,
};

/// Safe standard library subset. Excludes IO, OS, PACKAGE, DEBUG, FFI.
/// No `package.loadlib`, no `require`, no dynamic C searchers.
fn safe_stdlib() -> StdLib {
    StdLib::COROUTINE | StdLib::TABLE | StdLib::STRING | StdLib::UTF8 | StdLib::MATH
}

/// Bootstrap Lua snippet that injects the `subspace` kernel namespace:
/// - `subspace.yield_operation(label)` suspends the coroutine yielding an
///   opaque operation (unchanged contract seam).
/// - `subspace._modules` is a per-state pure-Lua module-cache table keyed by
///   caller-chosen string names. `module_put`/`module_get`/`module_clear`
///   manipulate it using only the `table`/`string` stdlib. Per-state
///   isolation comes from each `StateEngine` owning its own `Lua` and thus
///   its own `_modules` table; no PACKAGE/IO/OS/debug/FFI is loaded.
/// - `subspace.host_hash(label)` is the tiny bounded native host function
///   bound from Rust (see `EngineInner::new`); it returns a fixed-size hash
///   string synchronously and never yields.
/// - `subspace.host_call(label)` is the blocking-operation negative host
///   function bound from Rust; it immediately returns a normalized rejection
///   `(false, "rejected:"..label)` without performing work, preserving the
///   yield-before-external-work invariant.
/// - Plugin-created coroutines are disabled after bootstrap: instruction hooks
///   are per-thread, so `create`/`resume`/`wrap`/`close` are removed to prevent
///   child threads from bypassing the finite budget or interrupt flag. The
///   host-created coroutine retains `coroutine.yield` for yield operations.
///
/// Loaded before user source so entrypoints can call any of these.
const SUBSPACE_BOOTSTRAP: &str = r#"
subspace = subspace or {}
subspace.yield_operation = function(label)
  return coroutine.yield(tostring(label))
end
subspace._modules = {}
subspace.module_put = function(name, value)
  subspace._modules[tostring(name)] = value
end
subspace.module_get = function(name)
  return subspace._modules[tostring(name)]
end
subspace.module_clear = function(name)
  subspace._modules[tostring(name)] = nil
end

-- The host owns coroutine lifecycle. `StateEngine` installs its instruction
-- hook on the host-created mlua thread; plugin-created child threads would not
-- inherit that per-thread hook and could evade instruction budgets/interrupts.
-- Keep only coroutine.yield for subspace.yield_operation and remove all APIs
-- that can create or drive another thread.
coroutine.create = nil
coroutine.resume = nil
coroutine.wrap = nil
coroutine.close = nil

-- Source-only mode: disable base dynamic loaders so constructed bytecode or
-- filesystem access cannot execute through Lua's own load/dofile/loadfile.
-- load is replaced with a text-only wrapper; loadfile and dofile are removed
-- entirely.
do
  local legacy_load = load
  load = function(chunk, chunkname, mode, env)
    if type(chunk) == "string" and chunk:sub(1, 1) == "\27" then
      return nil, "attempt to load a binary chunk (source-only mode)"
    end
    return legacy_load(chunk, chunkname, "t", env)
  end
  loadfile = nil
  dofile = nil
end
"#;

// ---------------------------------------------------------------------------
// EngineInner — the mutable state behind the per-state mutex
// ---------------------------------------------------------------------------

/// Maximum number of exact terminal outcomes retained for duplicate
/// resume/cancel requests. The FIFO is deliberately bounded: a state may
/// yield indefinitely, while host retry semantics only require a recent
/// duplicate to echo its exact terminal result.
///
/// This is public solely so integration tests can exercise the eviction
/// boundary without duplicating a production implementation detail.
#[doc(hidden)]
pub const TERMINAL_OPERATION_CACHE_CAPACITY: usize = 64;

/// Maximum number of recently evicted operation ids retained as typed stale
/// tombstones. Keeping this separate FIFO bounded preserves finite memory
/// while distinguishing an immediately evicted own operation from a foreign
/// operation id.
const EVICTED_TERMINAL_OPERATION_TOMBSTONE_CAPACITY: usize = 64;

struct EngineInner {
    state_id: StateId,
    generation: Generation,
    lua: Option<Lua>,
    lifecycle: Lifecycle,
    entrypoint: Option<Function>,
    entrypoint_name: Option<String>,
    coroutine: Option<Thread>,
    coroutine_id: i64,
    next_coroutine_id: i64,
    current_operation: Option<OperationId>,
    current_label: Option<String>,
    /// Recent terminal outcomes keyed by operation id. Bounded by
    /// `TERMINAL_OPERATION_CACHE_CAPACITY` so a long-lived state cannot grow
    /// unboundedly with each completed resume/cancel.
    terminal_operations: HashMap<OperationId, Outcome>,
    /// FIFO order for `terminal_operations` eviction.
    terminal_operation_order: VecDeque<OperationId>,
    /// Recently evicted terminal operation ids. This bounded tombstone FIFO
    /// lets a duplicate that falls just outside the outcome cache receive the
    /// typed `Stale` result rather than being mistaken for a foreign id.
    evicted_terminal_operation_order: VecDeque<OperationId>,
    /// Test-only synchronization hook invoked after an interrupt has observed
    /// its target and released the state mutex, but before it sets the flag
    /// and dispatches tagged invalidation. `None` in production, with no
    /// allocation unless an integration test installs a hook.
    interrupt_post_peek_hook: Option<Arc<dyn Fn() + Send + Sync + 'static>>,
    accountant: Accountant,
    interrupt_flag: Arc<AtomicBool>,
    instruction_count: Arc<AtomicI64>,
    instruction_budget: i64,
    hook_interval: u32,
    created_at: Instant,
    /// Bridge-owned bytes: source string + entrypoint name + yielded labels.
    bridge_bytes: i64,
}

/// Commands dispatched to [`EngineInner::process`].
enum Command {
    Load { source: String, entrypoint: String },
    Start,
    Resume {
        operation_id: OperationId,
        success: bool,
        value: String,
    },
    Cancel { operation_id: OperationId },
    InvalidateSuspended {
        /// The operation id the interrupt observed at its decision point.
        /// The handler only invalidates the suspension when the live
        /// `current_operation` still equals this id, so a concurrent resume
        /// that produced a fresh yield cannot be invalidated by a stale peek.
        expected_operation_id: OperationId,
    },
    LowerMemoryLimit { new_limit_bytes: u64 },
    Snapshot,
    Close,
}

impl EngineInner {
    fn new(
        state_id: StateId,
        memory_limit_bytes: u64,
        hook_interval: u32,
        instruction_budget: u64,
    ) -> Result<Self, Outcome> {
        let lua = Lua::new_with(safe_stdlib(), LuaOptions::new())
            .map_err(|e| Outcome::runtime_failure(format!("failed to create Lua VM: {e}")))?;

        // Set per-state memory limit. mlua's allocator returns NULL on denial,
        // surfacing as Error::MemoryError which we normalize later.
        if memory_limit_bytes > 0 {
            let _ = lua.set_memory_limit(memory_limit_bytes as usize);
        }

        // Inject subspace.yield_operation before any user source. With an
        // extremely tight memory limit (e.g. 1 KiB), the VM's stdlib may
        // already exceed the cap and this bootstrap fails with a memory error
        // — classify it as memory_failure so the host gets a deterministic
        // creation-phase denial.
        if let Err(e) = lua.load(SUBSPACE_BOOTSTRAP).set_mode(ChunkMode::Text).exec() {
            if is_memory_error(&e) {
                return Err(Outcome::memory_failure(format!(
                    "failed to bootstrap subspace: {e}"
                )));
            }
            return Err(Outcome::runtime_failure(format!(
                "failed to bootstrap subspace: {e}"
            )));
        }

        // Register the tiny bounded native host function `subspace.host_hash`.
        // It computes a fixed-size FNV-1a 32-bit hash over the string bytes
        // synchronously inside the bounded call and returns a Lua string. It
        // never yields and allocates only the result string.
        let host_hash_fn = lua
            .create_function(|_, label: String| {
                let mut hash: u32 = 0x811c_9dc5;
                for byte in label.as_bytes() {
                    hash ^= *byte as u32;
                    hash = hash.wrapping_mul(0x0100_0193);
                }
                Ok(format!("{hash:08x}"))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_hash: {e}")))?;

        // Register the blocking-operation negative host function
        // `subspace.host_call`. It immediately returns a normalized rejection
        // `(false, "rejected:"..label)` without performing work, preserving
        // the yield-before-external-work invariant. It never calls
        // `coroutine.yield` and does no external work.
        let host_call_fn = lua
            .create_function(|_, label: String| Ok((false, format!("rejected:{label}"))))
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_call: {e}")))?;

        // Bind both host functions on the `subspace` table created by the
        // bootstrap snippet. They are internal kernel seams — no JNI entry
        // points are added; entrypoints reach them only via Lua.
        {
            let globals = lua.globals();
            let subspace: mlua::Table = globals
                .get("subspace")
                .map_err(|e| Outcome::runtime_failure(format!("subspace global missing: {e}")))?;
            subspace
                .set("host_hash", host_hash_fn)
                .map_err(|e| Outcome::runtime_failure(format!("failed to bind subspace.host_hash: {e}")))?;
            subspace
                .set("host_call", host_call_fn)
                .map_err(|e| Outcome::runtime_failure(format!("failed to bind subspace.host_call: {e}")))?;
        }

        let interrupt_flag = Arc::new(AtomicBool::new(false));
        let instruction_count = Arc::new(AtomicI64::new(0));

        Ok(EngineInner {
            state_id,
            generation: 1,
            lua: Some(lua),
            lifecycle: Lifecycle::Init,
            entrypoint: None,
            entrypoint_name: None,
            coroutine: None,
            coroutine_id: 0,
            next_coroutine_id: 1,
            current_operation: None,
            current_label: None,
            terminal_operations: HashMap::new(),
            terminal_operation_order: VecDeque::new(),
            evicted_terminal_operation_order: VecDeque::new(),
            interrupt_post_peek_hook: None,
            accountant: Accountant::new(memory_limit_bytes),
            interrupt_flag,
            instruction_count,
            instruction_budget: instruction_budget as i64,
            hook_interval: if hook_interval == 0 { 1000 } else { hook_interval },
            created_at: Instant::now(),
            bridge_bytes: 0,
        })
    }

    fn handle(&self) -> StateHandle {
        StateHandle::new(self.state_id, self.generation)
    }

    /// Check generation ownership. Generation is checked before closed-state
    /// so that post-close operations with a stale generation report `stale`
    /// (the generation they hold is no longer live), while a close on an
    /// already-closed state is handled specially in [`EngineInner::process`]
    /// to remain idempotent.
    fn check_ownership(&self, generation: Generation) -> OwnershipVerdict {
        if generation != self.generation {
            OwnershipVerdict::Stale
        } else if self.lifecycle.is_closed() {
            OwnershipVerdict::Closed
        } else {
            OwnershipVerdict::Admit
        }
    }

    /// Check whether an operation id is valid for resume/cancel.
    fn check_operation(&self, operation_id: OperationId) -> OperationVerdict {
        if self.terminal_operations.contains_key(&operation_id) {
            OperationVerdict::Duplicate
        } else if self.current_operation == Some(operation_id) {
            OperationVerdict::Admit
        } else {
            OperationVerdict::Foreign
        }
    }

    /// Whether an operation recently aged out of the exact terminal-outcome
    /// cache. These bounded tombstones prevent the immediate post-eviction
    /// duplicate from being confused with an operation owned by another state.
    fn is_recently_evicted_terminal_operation(&self, operation_id: OperationId) -> bool {
        self.evicted_terminal_operation_order
            .contains(&operation_id)
    }

    /// Record an accepted terminal operation with bounded FIFO retention.
    /// Recent duplicates echo their exact cached outcome. On overflow, retain
    /// only the evicted id in a second bounded FIFO so it gets typed `Stale`.
    fn record_terminal_operation(&mut self, operation_id: OperationId, outcome: Outcome) {
        if self.terminal_operations.contains_key(&operation_id) {
            self.terminal_operations.insert(operation_id, outcome);
            return;
        }

        if self.terminal_operation_order.len() == TERMINAL_OPERATION_CACHE_CAPACITY {
            let evicted_operation_id = self
                .terminal_operation_order
                .pop_front()
                .expect("terminal operation FIFO length matched cache capacity");
            self.terminal_operations.remove(&evicted_operation_id);
            self.record_evicted_terminal_operation(evicted_operation_id);
        }

        self.terminal_operation_order.push_back(operation_id);
        self.terminal_operations.insert(operation_id, outcome);
    }

    /// Retain a recently evicted id in a bounded FIFO. This cannot grow with
    /// the number of operations a long-lived state executes.
    fn record_evicted_terminal_operation(&mut self, operation_id: OperationId) {
        if self.evicted_terminal_operation_order.len()
            == EVICTED_TERMINAL_OPERATION_TOMBSTONE_CAPACITY
        {
            self.evicted_terminal_operation_order.pop_front();
        }
        self.evicted_terminal_operation_order.push_back(operation_id);
    }

    fn stale_evicted_terminal_operation(&self, operation_id: OperationId) -> Outcome {
        Outcome::new(OutcomeKind::Stale)
            .with("operationId", json!(operation_id))
            .with("diagnostic", json!("terminal operation outcome evicted"))
    }

    fn memory_report(&self) -> MemoryReport {
        // After close the Lua VM is dropped (`self.lua` is `None`), so the
        // terminal Lua allocation reading is zero while the sampled peak,
        // denied count, and bridge bytes survive in the `Accountant`.
        let current = self.lua.as_ref().map_or(0, |lua| lua.used_memory() as u64);
        self.accountant.report(current)
    }

    fn outcome_with_telemetry(&self, mut outcome: Outcome) -> Outcome {
        let report = self.memory_report();
        let elapsed = self.created_at.elapsed().as_nanos() as u64;
        outcome = outcome
            .with("currentBytes", json!(report.current_bytes))
            .with("peakBytes", json!(report.peak_bytes))
            .with("deniedAllocations", json!(report.denied_allocations))
            .with("bridgeBytes", json!(report.bridge_bytes))
            .with("elapsedNanos", json!(elapsed));
        outcome
    }

    fn outcome_with_handle(&self, outcome: Outcome) -> Outcome {
        outcome
            .with("stateId", json!(self.state_id))
            .with("generation", json!(self.generation))
    }

    /// Main dispatch: check ownership, then route to the specific handler.
    fn process(&mut self, generation: Generation, command: Command) -> Outcome {
        // Close is idempotent: an already-closed state always returns `closed`
        // for a close, even with a stale generation.
        if self.lifecycle.is_closed() && matches!(command, Command::Close) {
            return Outcome::closed(self.state_id, self.generation);
        }

        match self.check_ownership(generation) {
            OwnershipVerdict::Closed => return Outcome::closed(self.state_id, self.generation),
            OwnershipVerdict::Stale => return Outcome::stale(self.state_id, generation),
            OwnershipVerdict::Admit => {}
        }


        match command {
            Command::Load { source, entrypoint } => self.handle_load(source, entrypoint),
            Command::Start => self.handle_start(),
            Command::Resume {
                operation_id,
                success,
                value,
            } => self.handle_resume(operation_id, success, value),
            Command::Cancel { operation_id } => self.handle_cancel(operation_id),
            Command::InvalidateSuspended { expected_operation_id } => self.handle_invalidate_suspended(expected_operation_id),
            Command::LowerMemoryLimit { new_limit_bytes } => self.handle_lower_memory_limit(new_limit_bytes),
            Command::Snapshot => self.handle_snapshot(),
            Command::Close => self.handle_close(),
        }
    }

    fn handle_load(&mut self, source: String, entrypoint: String) -> Outcome {
        // Reject the Lua binary signature before the binding sees the chunk.
        // Android's JNI string conversion must not turn a binary request into
        // an entrypoint validation failure.
        if source.as_bytes().first() == Some(&0x1b) {
            let outcome = Outcome::syntax_failure("binary Lua chunks are not accepted");
            return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
        }
        // Track bridge bytes for the source string.
        let source_bytes = source.len() as i64 + entrypoint.len() as i64;
        self.bridge_bytes = source_bytes;
        self.accountant.add_bridge(source_bytes);

        // Load user source in text-only mode.
        let lua = self.lua.as_ref().expect("lua live in handle_load");
        let load_result = lua.load(&source).set_mode(ChunkMode::Text).exec();
        if let Err(e) = load_result {
            self.accountant.sub_bridge(source_bytes);
            self.bridge_bytes = 0;
            if is_memory_error(&e) {
                self.accountant.record_denial();
                let outcome = Outcome::memory_failure(format!("{e}"));
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
            let outcome = classify_load_error(&e);
            return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
        }

        let globals = self.lua.as_ref().expect("lua live in handle_load").globals();
        let entry_fn: Result<Function, _> = globals.get(entrypoint.as_str());
        match entry_fn {
            Ok(f) => {
                self.entrypoint = Some(f);
                self.entrypoint_name = Some(entrypoint.clone());
                self.lifecycle = Lifecycle::Loaded;
                self.coroutine = None;
                self.coroutine_id = 0;
                self.current_operation = None;
                self.terminal_operations.clear();
                self.terminal_operation_order.clear();
                self.evicted_terminal_operation_order.clear();

                let outcome = Outcome::new(OutcomeKind::Completed)
                    .with("diagnostic", json!("loaded"));
                self.outcome_with_handle(self.outcome_with_telemetry(outcome))
            }
            Err(_) => {
                self.accountant.sub_bridge(source_bytes);
                self.bridge_bytes = 0;
                let outcome = Outcome::validation_failure(format!(
                    "entrypoint '{entrypoint}' is not a defined global function"
                ));
                self.outcome_with_handle(self.outcome_with_telemetry(outcome))
            }
        }
    }

    fn handle_start(&mut self) -> Outcome {
        if self.lifecycle != Lifecycle::Loaded {
            let outcome = Outcome::validation_failure(format!(
                "cannot start: lifecycle is {:?}, expected Loaded",
                self.lifecycle
            ));
            return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
        }

        let entry_fn = match &self.entrypoint {
            Some(f) => f.clone(),
            None => {
                let outcome = Outcome::validation_failure("no entrypoint loaded");
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
        };

        // Create the coroutine.
        let lua = self.lua.as_ref().expect("lua live in handle_start");
        let thread = match lua.create_thread(entry_fn) {
            Ok(t) => t,
            Err(e) => {
                if is_memory_error(&e) {
                    self.accountant.record_denial();
                    let outcome = Outcome::memory_failure(format!("{e}"));
                    return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
                }
                let outcome =
                    Outcome::runtime_failure(format!("failed to create coroutine: {e}"));
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
        };

        // Set the instruction hook on this coroutine.
        self.setup_hook(&thread);

        self.lifecycle = Lifecycle::Running;
        let co_id = self.next_coroutine_id;
        self.next_coroutine_id += 1;

        // Store the coroutine handle *before* resume so classify can read
        // its status even on the first start.
        self.coroutine = Some(thread.clone());

        // Resume the coroutine for the first time.
        let result = thread.resume::<Value>(());
        let outcome = self.classify_resume_result(result, &thread, co_id);

        self.outcome_with_handle(self.outcome_with_telemetry(outcome))
    }

    fn handle_resume(
        &mut self,
        operation_id: OperationId,
        success: bool,
        value: String,
    ) -> Outcome {
        // Check operation verdict BEFORE lifecycle so duplicates echo the
        // accepted terminal state without re-entering Lua (exactly-once).
        if self.is_recently_evicted_terminal_operation(operation_id) {
            return self.outcome_with_handle(
                self.outcome_with_telemetry(self.stale_evicted_terminal_operation(operation_id)),
            );
        }
        match self.check_operation(operation_id) {
            OperationVerdict::Foreign => {
                let outcome =
                    Outcome::invalid_ownership("operation id not issued by this state");
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
            OperationVerdict::Duplicate => {
                return self.outcome_with_handle(
                    self.outcome_with_telemetry(self.echo_terminal(operation_id)),
                );
            }
            OperationVerdict::Admit => {}
        }

        if self.lifecycle != Lifecycle::Yielded {
            let outcome = Outcome::validation_failure(format!(
                "cannot resume: lifecycle is {:?}, expected Yielded",
                self.lifecycle
            ));
            return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
        }

        // Clear the live operation before entering Lua. Its exact outcome is
        // retained below so duplicate host completions receive the same result.
        self.current_operation = None;

        let thread = match &self.coroutine {
            Some(t) => t.clone(),
            None => {
                let outcome = Outcome::runtime_failure("no active coroutine to resume");
                self.lifecycle = Lifecycle::Failed;
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
        };

        self.lifecycle = Lifecycle::Running;

        // Resume with (success, value) — the Lua coroutine receives these as
        // the return values of coroutine.yield.
        let result = thread.resume::<Value>((success, value));
        let co_id = self.coroutine_id;
        let outcome = self.classify_resume_result(result, &thread, co_id);
        self.record_terminal_operation(operation_id, outcome.clone());

        self.outcome_with_handle(self.outcome_with_telemetry(outcome))
    }

    fn handle_cancel(&mut self, operation_id: OperationId) -> Outcome {
        // Check operation verdict BEFORE lifecycle so duplicates echo the
        // accepted terminal state without re-entering Lua (exactly-once).
        if self.is_recently_evicted_terminal_operation(operation_id) {
            return self.outcome_with_handle(
                self.outcome_with_telemetry(self.stale_evicted_terminal_operation(operation_id)),
            );
        }
        match self.check_operation(operation_id) {
            OperationVerdict::Foreign => {
                let outcome =
                    Outcome::invalid_ownership("operation id not issued by this state");
                return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
            }
            OperationVerdict::Duplicate => {
                return self.outcome_with_handle(
                    self.outcome_with_telemetry(self.echo_terminal(operation_id)),
                );
            }
            OperationVerdict::Admit => {}
        }

        if self.lifecycle != Lifecycle::Yielded {
            let outcome = Outcome::validation_failure(format!(
                "cannot cancel: lifecycle is {:?}, expected Yielded",
                self.lifecycle
            ));
            return self.outcome_with_handle(self.outcome_with_telemetry(outcome));
        }

        // Mark operation consumed and abandon the coroutine.
        self.current_operation = None;
        self.lifecycle = Lifecycle::Cancelled;

        let outcome = Outcome::new(OutcomeKind::Cancelled)
            .with("coroutineId", json!(self.coroutine_id))
            .with("operationId", json!(operation_id));
        self.record_terminal_operation(operation_id, outcome.clone());
        self.outcome_with_handle(self.outcome_with_telemetry(outcome))
    }

    /// Echo the exact accepted outcome for a duplicate resume/cancel without
    /// re-entering Lua. This preserves both terminal kind and result payload.
    fn echo_terminal(&self, operation_id: OperationId) -> Outcome {
        self.terminal_operations
            .get(&operation_id)
            .cloned()
            .unwrap_or_else(|| {
                Outcome::invalid_ownership("terminal outcome missing for consumed operation")
            })
    }

    fn handle_snapshot(&mut self) -> Outcome {
        let report = self.memory_report();
        let elapsed = self.created_at.elapsed().as_nanos() as u64;

        let outcome = Outcome::new(OutcomeKind::Completed)
            .with("currentBytes", json!(report.current_bytes))
            .with("peakBytes", json!(report.peak_bytes))
            .with("deniedAllocations", json!(report.denied_allocations))
            .with("bridgeBytes", json!(report.bridge_bytes))
            .with("elapsedNanos", json!(elapsed))
            .with("luaVersion", json!(crate::LUA_VERSION))
            .with("bindingVersion", json!(crate::BINDING_VERSION))
            .with("operation", json!("snapshot"))
            .with("topology", json!("jvm_owned"));
        self.outcome_with_handle(outcome)
    }

    fn handle_close(&mut self) -> Outcome {
        if self.lifecycle.is_closed() {
            // Idempotent: already closed at this generation.
            return Outcome::closed(self.state_id, self.generation);
        }

        // Capture the final Lua reading so the sampled peak reflects the
        // last live state before dropping the VM.
        let report = self.memory_report();
        let elapsed = self.created_at.elapsed().as_nanos() as u64;

        let prev_generation = self.generation;
        self.lifecycle = Lifecycle::Closed;
        self.generation += 1;
        self.coroutine = None;
        self.entrypoint = None;
        self.current_operation = None;

        // Drop the Lua VM: terminal Lua allocation becomes zero. All fields
        // below are derived from the `Accountant` (atomic), which survives.
        let _ = self.lua.take();

        Outcome::closed(self.state_id, prev_generation)
            .with("currentBytes", json!(0u64))
            .with("peakBytes", json!(report.peak_bytes))
            .with("deniedAllocations", json!(report.denied_allocations))
            .with("bridgeBytes", json!(report.bridge_bytes))
            .with("elapsedNanos", json!(elapsed))
    }

    /// Terminally invalidate the exact suspended operation an interrupt
    /// observed while the state was `Yielded`.
    ///
    /// The caller read `expected_operation_id` atomically with ownership and
    /// lifecycle under the per-state mutex, then released that lock to set the
    /// interrupt flag. This handler is the second lock acquisition. Its
    /// identity comparison prevents that gap from invalidating a newer yield.
    ///
    /// - If `current_operation == Some(expected)`, this is the original live
    ///   suspension. Record its terminal rejection, drop its coroutine, and
    ///   return `Interrupted`; later resume/cancel echoes the cached terminal
    ///   result.
    /// - Otherwise a concurrent resume/cancel/close won first. Leave the
    ///   resulting state unchanged, clear the flag set for the old suspended
    ///   operation, and return typed `Stale` carrying the observed operation
    ///   id. This prevents an old suspended interrupt from affecting a newer
    ///   yielded operation.
    fn handle_invalidate_suspended(&mut self, expected_operation_id: OperationId) -> Outcome {
        let live = if self.lifecycle == Lifecycle::Yielded {
            self.current_operation
        } else {
            None
        };

        if live == Some(expected_operation_id) {
            // The suspension interrupt observed is still the live one.
            // Invalidate it terminally.
            let outcome = Outcome::runtime_failure(
                "suspended operation was terminally invalidated",
            );
            self.record_terminal_operation(expected_operation_id, outcome);
            self.coroutine = None;
            self.current_operation = None;
            self.current_label = None;
            self.lifecycle = Lifecycle::Failed;
            return self.outcome_with_handle(
                Outcome::new(OutcomeKind::Interrupted)
                    .with("operationId", json!(expected_operation_id))
                    .with("diagnostic", json!("suspended state terminally invalidated")),
            );
        }

        // A concurrent resume/cancel/close won the old operation before
        // interrupt re-acquired the lock. Do not touch the resulting state.
        // This flag belonged to the old suspended operation; clear it so it
        // cannot interrupt a newly yielded operation on a later resume.
        self.interrupt_flag.store(false, Ordering::Relaxed);
        self.outcome_with_handle(
            Outcome::new(OutcomeKind::Stale)
                .with("operationId", json!(expected_operation_id))
                .with("diagnostic", json!("interrupt overtaken by concurrent resume; suspension unchanged")),
        )
    }

    /// Internal test seam: lower the Lua per-state allocator limit after
    /// creation. Accepts any new limit (including raising), but the primary
    /// use case is lowering to inject deterministic denial. The new limit
    /// takes effect immediately via `mlua::Lua::set_memory_limit`.
    ///
    /// Returns a `Completed` outcome with telemetry on success, or a
    /// `runtime_failure` if `set_memory_limit` returns an error (which
    /// typically means the MemoryState is not available — this should not
    /// happen for a normal `Lua::new_with` state).
    ///
    /// To deterministically deny coroutine creation, set the limit to a
    /// value strictly below the current allocation (e.g. 1). Setting the
    /// limit exactly to the current bytes may not always deny because the
    /// internal allocator check is `new_used_memory > mem_limit`, and any
    /// intervening free (via GC step) could temporarily satisfy the check.
    ///
    /// Not exposed over JNI. Tests use this seam.
    fn handle_lower_memory_limit(&mut self, new_limit_bytes: u64) -> Outcome {
        // Call set_memory_limit and check for failure (MemoryControlNotAvailable).
        if let Some(lua) = self.lua.as_ref() {
            if let Err(e) = lua.set_memory_limit(new_limit_bytes as usize) {
                return self.outcome_with_handle(
                    Outcome::runtime_failure(
                        format!("set_memory_limit failed: {e}")
                    )
                );
            }
        }
        // Update the Accountant's tracking to match.
        self.accountant.lower_limit(new_limit_bytes);
        self.outcome_with_handle(self.outcome_with_telemetry(
            Outcome::new(OutcomeKind::Completed)
                .with("memoryLimitBytes", json!(new_limit_bytes))
        ))
    }

    /// Set up the instruction-count hook on a coroutine thread.
    fn setup_hook(&self, thread: &Thread) {
        let interval = self.hook_interval;
        let budget = self.instruction_budget;
        let interrupt_flag = self.interrupt_flag.clone();
        let count = self.instruction_count.clone();

        // Reset counters for a fresh execution.
        count.store(0, Ordering::Relaxed);
        interrupt_flag.store(false, Ordering::Relaxed);

        let _ = thread.set_hook(
            HookTriggers::new().every_nth_instruction(interval),
            move |_lua, _debug| {
                if interrupt_flag.load(Ordering::Relaxed) {
                    return Err(LuaError::runtime("interrupted"));
                }
                let prev = count.fetch_add(interval as i64, Ordering::Relaxed);
                if prev + interval as i64 > budget {
                    return Err(LuaError::runtime("instruction budget exhausted"));
                }
                Ok(VmState::Continue)
            },
        );
    }

    /// Classify the result of a `Thread::resume` call into an [`Outcome`].
    /// Updates lifecycle and operation tracking.
    ///
    /// `thread` is the coroutine handle that was resumed, passed explicitly so
    /// the status can be read even before `self.coroutine` is assigned on the
    /// first start.
    fn classify_resume_result(
        &mut self,
        result: Result<Value, LuaError>,
        thread: &Thread,
        coroutine_id: i64,
    ) -> Outcome {
        let was_interrupted = self.interrupt_flag.load(Ordering::Relaxed);

        match result {
            Ok(value) => {
                // Coroutine either completed or yielded. Check status on the
                // thread that was actually resumed.
                let status = thread.status();

                match status {
                    ThreadStatus::Resumable => {
                        // Yielded: assign a globally-unique operation id.
                        let label = value_to_string(&value);
                        let op_id = assign_operation_id();
                        self.current_operation = Some(op_id);
                        self.current_label = Some(label.clone());
                        self.lifecycle = Lifecycle::Yielded;

                        Outcome::new(OutcomeKind::Yielded)
                            .with("coroutineId", json!(coroutine_id))
                            .with("operationId", json!(op_id))
                            .with("value", json!(label))
                    }
                    _ => {
                        // Completed.
                        let value_str = value_to_string(&value);
                        self.lifecycle = Lifecycle::Completed;
                        self.current_operation = None;

                        Outcome::new(OutcomeKind::Completed)
                            .with("coroutineId", json!(coroutine_id))
                            .with("value", json!(value_str))
                    }
                }
            }
            Err(e) => {
                // Terminally invalidate the coroutine on any error so a
                // later resume/cancel cannot re-enter Lua.
                self.coroutine = None;
                self.current_operation = None;

                // Determine failure category.
                let outcome = if was_interrupted {
                    self.lifecycle = Lifecycle::Failed;
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("execution interrupted"))
                } else if self.instruction_count.load(Ordering::Relaxed)
                    > self.instruction_budget
                {
                    self.lifecycle = Lifecycle::Failed;
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("instruction budget exhausted"))
                } else if is_memory_error(&e) {
                    self.lifecycle = Lifecycle::Failed;
                    self.accountant.record_denial();
                    Outcome::memory_failure(format!("{e}"))
                } else {
                    self.lifecycle = Lifecycle::Failed;
                    Outcome::runtime_failure(format!("{e}"))
                };
                outcome
            }
        }
    }
}

// ---------------------------------------------------------------------------
// StateEngine — public API wrapping the per-state mutex
// ---------------------------------------------------------------------------

/// A snapshot of the engine's observable state.
#[derive(Debug, Clone)]
pub struct StateSnapshot {
    pub memory: MemoryReport,
    pub elapsed_nanos: u64,
    pub lifecycle: Lifecycle,
}

/// The common state engine. `Send + Sync`.
///
/// Operations lock the inner mutex directly on the caller thread — there is no
/// per-state worker thread. The engine drives [`EngineInner::process`] and
/// serializes access through the per-state mutex.
#[derive(Clone)]
pub struct StateEngine {
    inner: Arc<Mutex<EngineInner>>,
    state_id: StateId,
    interrupt_flag: Arc<AtomicBool>,
}

impl StateEngine {
    /// Create a new state engine. Returns the engine on success, or an
    /// `Outcome` (validation/memory failure) on failure.
    pub fn new(
        memory_limit_bytes: u64,
        hook_interval: u32,
        instruction_budget: u64,
    ) -> Result<Self, Outcome> {
        let state_id = assign_state_id();
        let inner_val = EngineInner::new(
            state_id,
            memory_limit_bytes,
            hook_interval,
            instruction_budget,
        )?;
        let interrupt_flag = inner_val.interrupt_flag.clone();

        let inner = Arc::new(Mutex::new(inner_val));

        Ok(StateEngine {
            inner,
            state_id,
            interrupt_flag,
        })
    }

    /// Integration-test seam for exercising bounded terminal-outcome eviction
    /// without duplicating the implementation capacity.
    #[doc(hidden)]
    pub const fn terminal_operation_cache_capacity() -> usize {
        TERMINAL_OPERATION_CACHE_CAPACITY
    }

    /// Install or clear an integration-test synchronization hook invoked by
    /// [`Self::interrupt`] after it has observed a suspended operation and
    /// released the per-state mutex, but before tagged invalidation. Tests use
    /// this to force `peek(op1) -> resume(op1)->yield(op2) -> invalidate(op1)`.
    ///
    /// The hook is never installed by production callers. It executes without
    /// the state mutex held and must not panic.
    #[doc(hidden)]
    pub fn set_interrupt_post_peek_hook(
        &self,
        hook: Option<Arc<dyn Fn() + Send + Sync + 'static>>,
    ) {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.interrupt_post_peek_hook = hook;
    }

    pub fn handle(&self) -> StateHandle {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.handle()
    }

    pub fn state_id(&self) -> StateId {
        self.state_id
    }


    pub fn load(&self, generation: Generation, source: &str, entrypoint: &str) -> Outcome {
        self.dispatch(
            generation,
            Command::Load {
                source: source.to_string(),
                entrypoint: entrypoint.to_string(),
            },
        )
    }

    pub fn start(&self, generation: Generation) -> Outcome {
        self.dispatch(generation, Command::Start)
    }

    pub fn resume(
        &self,
        generation: Generation,
        operation_id: OperationId,
        success: bool,
        value: &str,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::Resume {
                operation_id,
                success,
                value: value.to_string(),
            },
        )
    }

    pub fn cancel(&self, generation: Generation, operation_id: OperationId) -> Outcome {
        self.dispatch(generation, Command::Cancel { operation_id })
    }

    /// Internal test seam: lower the Lua per-state allocator limit after
    /// creation. Only lowering is permitted (new limit must be strictly less
    /// than the current limit unless the current limit is unlimited). Returns
    /// the engine outcome with telemetry on success, or a `validation_failure`
    /// if the state is closed or the new limit is not lower.
    ///
    /// Not exposed over JNI. Tests use this to deterministically inject denial
    /// at coroutine creation or error-format string building.
    pub fn lower_memory_limit(&self, generation: Generation, new_limit_bytes: u64) -> Outcome {
        self.dispatch(generation, Command::LowerMemoryLimit { new_limit_bytes })
    }

    /// Set the atomic interrupt flag and, if the state is suspended (Yielded),
    /// terminally invalidate the coroutine/operation so a later resume/cancel
    /// cannot produce a Lua effect. The atomic flag handles the running case
    /// fire-and-forget; the suspended invalidation goes through the normal
    /// dispatch path for topology-safe serialization.
    ///
    /// Linearization: the peek reads `current_operation` atomically with the
    /// ownership/lifecycle check under the same lock acquisition, then tags the
    /// `InvalidateSuspended` command with that exact id. When the dispatch
    /// path re-acquires the lock, the handler only invalidates when the live
    /// `current_operation` still equals the tagged id. A concurrent resume that
    /// won the old operation (consuming it or producing a fresh yield with a
    /// different id) makes the ids mismatch, so the interrupt observes the
    /// resulting state consistently and returns a typed `Stale` outcome instead
    /// of destroying a suspension it never observed. In that stale-suspended
    /// case the handler clears the flag, so the old decision cannot affect a
    /// newly yielded operation; active interrupts retain the flag for the hook.
    pub fn interrupt(&self, generation: Generation) -> Outcome {
        // Ownership, lifecycle, and the exact live operation id are read
        // atomically under a single lock acquisition. The operation id is the
        // identity token for the suspended target at this decision point.
        let (observed_op, post_peek_hook) = {
            let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            match inner.check_ownership(generation) {
                OwnershipVerdict::Closed => {
                    return Outcome::closed(self.state_id, inner.generation);
                }
                OwnershipVerdict::Stale => {
                    return Outcome::stale(self.state_id, generation);
                }
                OwnershipVerdict::Admit => {}
            }
            (
                if inner.lifecycle == Lifecycle::Yielded {
                    inner.current_operation
                } else {
                    None
                },
                inner.interrupt_post_peek_hook.clone(),
            )
        };
        // Test hooks deliberately run after releasing the state mutex. They
        // make the old peek -> resume -> tagged-invalidate interleaving
        // deterministic without changing production synchronization.
        if observed_op.is_some() {
            if let Some(hook) = post_peek_hook {
                hook();
            }
        }
        // Set the atomic flag fire-and-forget (read by the instruction hook).
        self.interrupt_flag.store(true, Ordering::Relaxed);
        match observed_op {
            Some(op) => {
                // Terminally invalidate the exact suspended operation the
                // interrupt observed, through the normal dispatch path for
                // topology-safe serialization.
                self.dispatch(
                    generation,
                    Command::InvalidateSuspended {
                        expected_operation_id: op,
                    },
                )
            }
            None => {
                // Active (Running) or non-suspended: the atomic flag drives
                // the stop at the next hook tick.
                Outcome::new(OutcomeKind::Interrupted)
                    .with("stateId", json!(self.state_id))
                    .with("generation", json!(generation))
                    .with("diagnostic", json!("interrupt flag set"))
            }
        }
    }

    pub fn snapshot(&self, generation: Generation) -> Outcome {
        self.dispatch(generation, Command::Snapshot)
    }

    pub fn close(&self, generation: Generation) -> Outcome {
        self.dispatch(generation, Command::Close)
    }

    fn dispatch(&self, generation: Generation, command: Command) -> Outcome {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.process(generation, command)
    }
}


// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert a Lua Value to a Rust String without panicking.
fn value_to_string(value: &Value) -> String {
    match value {
        Value::Nil => String::new(),
        Value::Boolean(b) => b.to_string(),
        Value::Integer(i) => i.to_string(),
        Value::Number(n) => {
            if n.fract() == 0.0 {
                format!("{:.1}", n)
            } else {
                n.to_string()
            }
        }
        Value::String(s) => s.to_string_lossy(),
        _ => String::new(),
    }
}

/// Classify a load-time error into the appropriate outcome.
fn classify_load_error(e: &LuaError) -> Outcome {
    match e {
        LuaError::SyntaxError { message, .. } => {
            Outcome::syntax_failure(message.clone())
        }
        LuaError::MemoryError(msg) => Outcome::memory_failure(msg.clone()),
        _ => Outcome::runtime_failure(format!("{e}")),
    }
}

/// Check if a Lua error is a memory error (directly or wrapped).
fn is_memory_error(e: &LuaError) -> bool {
    match e {
        LuaError::MemoryError(_) => true,
        LuaError::CallbackError { cause, .. } => is_memory_error(cause),
        _ => false,
    }
}
