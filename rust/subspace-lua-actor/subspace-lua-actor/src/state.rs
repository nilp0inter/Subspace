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
    LuaOptions, StdLib, Table, Thread, Value, VmState,
};
use serde_json::json;

use crate::accounting::{Accountant, MemoryReport};
use crate::outcome::{Outcome, OutcomeKind};
use crate::ownership::{
    assign_operation_id, assign_state_id, Generation, Lifecycle, OperationId, OperationVerdict,
    OwnershipVerdict, StateHandle, StateId,
};

/// Host decision made synchronously at the native spawn boundary.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SpawnAdmission {
    Accepted,
    /// No host admission context exists for this execution slice.
    Rejected,
    Closed,
    Capacity,
}

/// Called only while one Lua execution slice is active. The candidate coroutine
/// has been created but is not retained until this admits it.
pub trait SpawnAdmitter: Send + Sync {
    fn admit(&self, coroutine_id: i64) -> SpawnAdmission;
}

impl<F> SpawnAdmitter for F
where
    F: Fn(i64) -> SpawnAdmission + Send + Sync,
{
    fn admit(&self, coroutine_id: i64) -> SpawnAdmission {
        self(coroutine_id)
    }
}

struct AcceptAllSpawnAdmitter;

impl SpawnAdmitter for AcceptAllSpawnAdmitter {
    fn admit(&self, _: i64) -> SpawnAdmission {
        SpawnAdmission::Accepted
    }
}
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
/// - Plugin-created coroutines are mediated by Rust so their per-thread hooks
///   share the state instruction budget and interrupt flag. Only the tagged
///   `subspace.yield_operation` protocol may cross the actor boundary.
///
/// Loaded before user source so entrypoints can call any of these.
const OPERATION_YIELD_PREFIX: &str = "\0subspace-operation:";
const SUBSPACE_BOOTSTRAP: &str = r#"
subspace = subspace or {}
subspace._operation_yield_prefix = "\0subspace-operation:"
subspace.yield_operation = function(label)
  return coroutine.yield(subspace._operation_yield_prefix .. tostring(label))
end
subspace._modules = {}
subspace._loading = {}
subspace._sources = {}
subspace._evaluating = 0
local host_spawn = subspace.host_spawn
local host_acknowledge_spawn_context = subspace.host_acknowledge_spawn_context
local host_prepare_sleep = subspace.host_prepare_sleep
local host_log = subspace.host_log
local host_create_coroutine = subspace.host_create_coroutine

-- Keep Lua-level composition, but route every child through Rust so the
-- per-thread hook shares the state interrupt flag and instruction budget.
local native_coroutine_resume = coroutine.resume
coroutine.create = function(fn)
  if type(fn) ~= "function" then
    error("bad argument #1 to 'create' (function expected)", 2)
  end
  return host_create_coroutine(fn)
end
coroutine.resume = function(thread, ...)
  local results = table.pack(native_coroutine_resume(thread, ...))
  if not results[1] then
    error("attempt to resume child coroutine", 2)
  end
  return table.unpack(results, 1, results.n)
end
coroutine.wrap = function(fn)
  local thread = coroutine.create(fn)
  return function(...)
    local results = table.pack(coroutine.resume(thread, ...))
    if not results[1] then
      error(results[2], 2)
    end
    return table.unpack(results, 2, results.n)
  end
end

subspace.module_put = function(name, value)
  subspace._modules[tostring(name)] = value
end
subspace.module_get = function(name)
  return subspace._modules[tostring(name)]
end
subspace.module_clear = function(name)
  subspace._modules[tostring(name)] = nil
end

-- Event constants
subspace.channel = {
  LIFECYCLE_READY = "ready",
  CAPTURE_COMPLETE = "capture",
  SOS_TRIGGERED = "sos",
}

-- Version constants
subspace.runtime = {
  LUA_VERSION = "Lua 5.4",
  LUA_RELEASE = "5.4.8",
  API_VERSION = "subspace-lua-v1",
}

-- Preloaded host modules
subspace._preloaded = {
  ["subspace.runtime"] = subspace.runtime,
  ["subspace.channel"] = subspace.channel,
}

subspace.runtime.spawn = function(fn)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
  if type(fn) ~= "function" then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end

  -- Authorization is deliberately host-owned: host_spawn verifies the active
  -- Rust dispatch context and coroutine identity. Lua-visible globals cannot
  -- grant a plugin-created thread or synchronous callback this capability.
  local ok, res = host_spawn(fn)
  if not ok then
    if res == "E_INVALID_CONTEXT" then
      local observed = false
      local err = setmetatable({}, {
        __index = function(_, key)
          if key == "error" then
            if not observed then
              observed = true
              host_acknowledge_spawn_context()
            end
            return res
          end
        end,
        __metatable = false,
      })
      return nil, err
    end
    return nil, { error = res }
  end
  return true, nil
end

subspace.runtime.sleep = function(seconds)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
  if type(seconds) ~= "number" or seconds ~= seconds or seconds == math.huge or seconds == -math.huge or seconds < 0 or seconds > 86400 then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end
  local _, is_main = coroutine.running()
  if is_main then
    return nil, { error = "E_INVALID_CONTEXT" }
  end
  local ok, res = host_prepare_sleep(seconds)
  if not ok then
    return nil, { error = res }
  end
  local success, value = coroutine.yield(subspace._operation_yield_prefix .. "sleep:" .. tostring(res))
  if success then
    return true, nil
  else
    return nil, { error = value }
  end
end

local function make_log_fn(level)
  return function(payload)
    if type(payload) ~= "table" then
      return nil, { error = "E_INVALID_VALUE" }
    end
    if subspace._evaluating and subspace._evaluating > 0 then
      error("effect-call-during-load")
    end
    return host_log(level, payload)
  end
end

subspace.log = {
  debug = make_log_fn("debug"),
  info = make_log_fn("info"),
  warn = make_log_fn("warn"),
  error = make_log_fn("error"),
}

subspace._preloaded["subspace.log"] = subspace.log

-- Each program image receives a private, read-only view of host capabilities.
-- Host closures remain in private backing tables; plugin writes never reach the
-- shared namespace or its reserved module tables.
function subspace._new_image_namespace(sources, modules, image_env)
  local host = subspace
  local function readonly(backing)
    return setmetatable({}, {
      __index = backing,
      __pairs = function() return pairs(backing) end,
      __newindex = function() error("attempt to modify read-only subspace namespace", 2) end,
      __metatable = false,
    })
  end
  local runtime, channel, log = {}, {}, {}
  for key, value in pairs(host.runtime) do runtime[key] = value end
  for key, value in pairs(host.channel) do channel[key] = value end
  for key, value in pairs(host.log) do log[key] = value end
  local private = {
    _sources = sources,
    _modules = modules,
    _loading = {},
    _image_env = image_env,
    _operation_yield_prefix = host._operation_yield_prefix,
    runtime = readonly(runtime),
    channel = readonly(channel),
    log = readonly(log),
  }
  private.module_put = function(name, value) modules[tostring(name)] = value end
  private.module_get = function(name) return modules[tostring(name)] end
  private.module_clear = function(name) modules[tostring(name)] = nil end
  private.yield_operation = function(label)
    return coroutine.yield(private._operation_yield_prefix .. tostring(label))
  end
  private._preloaded = {
    ["subspace.runtime"] = private.runtime,
    ["subspace.channel"] = private.channel,
    ["subspace.log"] = private.log,
  }
  local proxy = readonly(private)
  return proxy
end

-- Preserve `load` for package-local source compilation, but never expose the
-- base loader's binary mode to plugin code.
local native_load = load
load = function(chunk, source, mode, env)
  if type(chunk) == "string" and string.byte(chunk, 1) == 27 then
    return nil, "binary Lua chunks are not accepted"
  end
  if mode ~= nil and mode ~= "t" then
    return nil, "binary Lua chunks are not accepted"
  end
  return native_load(chunk, source, "t", env or _G)
end

-- Custom require replacement
function require(name)
  if type(name) ~= "string" then
    error("E_INVALID_MODULE_NAME")
  end
  if name == "" then
    error("E_INVALID_MODULE_NAME")
  end
  for segment in string.gmatch(name .. ".", "([^%.]*)%.") do
    if segment == "" then
      error("E_INVALID_MODULE_NAME")
    end
    if not string.match(segment, "^[a-z][a-z0-9_]*$") then
      error("E_INVALID_MODULE_NAME")
    end
  end

  local is_reserved = false
  if name == "subspace" or string.sub(name, 1, 9) == "subspace." then
    is_reserved = true
  end

  local image_env = subspace._image_env or _G
  local image_subspace = rawget(image_env, "subspace") or subspace
  if is_reserved then
    local preloaded = image_subspace._preloaded[name]
    if preloaded then
      return preloaded
    else
      error("E_RESERVED_MODULE")
    end
  end

  local cached = image_subspace._modules[name]
  if cached ~= nil then
    return cached
  end

  if image_subspace._loading[name] then
    error("E_MODULE_CYCLE")
  end
  local source = image_subspace._sources[name]
  if not source then
    error("E_MODULE_NOT_FOUND")
  end

  image_subspace._loading[name] = true
  local chunk, err = native_load(source, "@" .. name, "t", image_env)
  if not chunk then
    image_subspace._loading[name] = nil
    error(err)
  end

  subspace._evaluating = (subspace._evaluating or 0) + 1
  local success, result = pcall(chunk)
  subspace._evaluating = subspace._evaluating - 1
  image_subspace._loading[name] = nil

  if not success then
    error(result)
  end

  if result == nil then
    result = true
  end
  image_subspace._modules[name] = result

  return result
end

-- Source-only mode: disable base loadfile/dofile
loadfile = nil
dofile = nil
-- Bootstrap wrappers close over native callbacks; plugins cannot call them.
subspace.host_spawn = nil
subspace.host_acknowledge_spawn_context = nil
subspace.host_prepare_sleep = nil
subspace.host_log = nil
subspace.host_create_coroutine = nil
"#;

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

/// Authoritative ownership and lifecycle for one retained Lua coroutine.
///
/// `EngineInner::lifecycle` is only the last dispatch-facing aggregate. Every
/// operation transition is validated and applied through this record instead.
struct CoroutineState {
    thread: Thread,
    lifecycle: Lifecycle,
    operation: Option<OperationId>,
    label: Option<String>,
    managed_task: bool,
}

/// A terminal operation remains bound to its issuing coroutine so a caller
/// cannot replay an otherwise valid operation id against a different thread.
#[derive(Clone)]
struct TerminalOperation {
    coroutine_id: i64,
    outcome: Outcome,
}

struct EngineInner {
    state_id: StateId,
    generation: Generation,
    lua: Option<Lua>,
    entrypoint: Option<Function>,
    entrypoint_name: Option<String>,
    /// Compatibility lifecycle for the main entry dispatch and non-coroutine
    /// operations. It is never consulted to own a retained coroutine.
    lifecycle: Lifecycle,
    main_coroutine_id: Option<i64>,
    next_coroutine_id: i64,
    /// Recent terminal outcomes keyed by operation id. Bounded by
    /// `TERMINAL_OPERATION_CACHE_CAPACITY` so a long-lived state cannot grow
    /// unboundedly with each completed resume/cancel.
    terminal_operations: HashMap<OperationId, TerminalOperation>,
    /// FIFO order for `terminal_operations` eviction.
    terminal_operation_order: VecDeque<(OperationId, i64)>,
    /// Recently evicted terminal identities. This bounded tombstone FIFO lets
    /// a duplicate that falls just outside the outcome cache receive typed
    /// `Stale` without admitting it against another coroutine.
    evicted_terminal_operation_order: VecDeque<(OperationId, i64)>,
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
    source_map: HashMap<String, String>,
    coroutines: HashMap<i64, CoroutineState>,
    spawned_coroutines: Vec<i64>,
    active_sleep_operations: std::collections::HashSet<OperationId>,
    callback_state: Arc<Mutex<CallbackState>>,
}

/// Commands dispatched to [`EngineInner::process`].
enum Command {
    Load {
        source: String,
        entrypoint: String,
    },
    Start,
    Resume {
        coroutine_id: i64,
        operation_id: OperationId,
        success: bool,
        value: String,
    },
    Cancel {
        coroutine_id: i64,
        operation_id: OperationId,
    },
    InvalidateSuspended {
        /// The operation id the interrupt observed at its decision point.
        /// The handler invalidates only a record still carrying this exact id,
        /// so a concurrent resume cannot invalidate a fresh yield.
        expected_operation_id: OperationId,
    },
    LowerMemoryLimit {
        new_limit_bytes: u64,
    },
    Snapshot,
    Close,
    LoadProgramImage {
        source_map_json: String,
        entrypoint: String,
    },
    InvokeCallback {
        callback_name: String,
        arguments_json: String,
    },
    StartCoroutine {
        coroutine_id: i64,
    },
}

struct PendingSpawn {
    coroutine_id: i64,
    thread: Thread,
}

/// Opaque host-only spawn authority for the currently executing Lua thread.
/// Lua code cannot observe, modify, or synthesize this pointer identity.
#[derive(Clone, Copy)]
struct SpawnAuthority {
    thread_identity: usize,
}

/// State exclusively owned by host callbacks while Lua is executing.  It is
/// deliberately disjoint from `EngineInner`: callbacks never borrow, alias, or
/// re-lock the Lua-owning engine.  Dispatch drains their bounded pending work
/// after Lua returns, while still holding the engine mutex.
struct CallbackState {
    dispatch_active: bool,
    evaluating_module: bool,
    module_effect_attempted: bool,
    /// Spawned work admitted by `host_spawn`, including work awaiting dispatch.
    active_managed_tasks: usize,
    /// Sleep requests issued by the running callback but not yet yielded.
    pending_sleep_reservations: usize,
    invocation_violation: bool,
    next_coroutine_id: i64,
    latch_invalid_spawn: bool,
    spawn_authority: Option<SpawnAuthority>,
    unacknowledged_invalid_spawns: usize,
    spawn_admitter: Arc<dyn SpawnAdmitter>,
    /// Sleep/timer operations currently yielded to the host.
    active_sleep_timers: usize,
    pending_spawns: Vec<PendingSpawn>,
    logs: VecDeque<serde_json::Value>,
    log_bucket: f64,
    last_log_time: Instant,
}

impl CallbackState {
    fn new() -> Self {
        Self {
            dispatch_active: false,
            evaluating_module: false,
            module_effect_attempted: false,
            active_managed_tasks: 0,
            pending_sleep_reservations: 0,
            active_sleep_timers: 0,
            pending_spawns: Vec::new(),
            invocation_violation: false,
            next_coroutine_id: 1,
            latch_invalid_spawn: false,
            spawn_authority: None,
            unacknowledged_invalid_spawns: 0,
            spawn_admitter: Arc::new(AcceptAllSpawnAdmitter),
            logs: VecDeque::new(),
            log_bucket: 100.0,
            last_log_time: Instant::now(),
        }
    }
}

struct CallbackDispatchGuard {
    state: Arc<Mutex<CallbackState>>,
}

impl Drop for CallbackDispatchGuard {
    fn drop(&mut self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.dispatch_active = false;
        state.spawn_authority = None;
    }
}

impl EngineInner {
    fn new(
        state_id: StateId,
        memory_limit_bytes: u64,
        hook_interval: u32,
        instruction_budget: u64,
        max_concurrent_tasks: usize,
        max_timer_slots: usize,
    ) -> Result<Self, Outcome> {
        let lua = Lua::new_with(safe_stdlib(), LuaOptions::new())
            .map_err(|e| Outcome::runtime_failure(format!("failed to create Lua VM: {e}")))?;

        let callback_state = Arc::new(Mutex::new(CallbackState::new()));
        // Set per-state memory limit. mlua's allocator returns NULL on denial,
        // surfacing as Error::MemoryError which we normalize later.
        if memory_limit_bytes > 0 {
            let _ = lua.set_memory_limit(memory_limit_bytes as usize);
        }

        // Create subspace table first
        let subspace = lua.create_table().map_err(|e| {
            if matches!(&e, mlua::Error::MemoryError(_)) {
                Outcome::memory_failure(format!("failed to create subspace table: {e}"))
            } else {
                Outcome::runtime_failure(format!("failed to create subspace table: {e}"))
            }
        })?;
        lua.globals()
            .set("subspace", subspace.clone())
            .map_err(|e| {
                if matches!(&e, mlua::Error::MemoryError(_)) {
                    Outcome::memory_failure(format!("failed to set subspace global: {e}"))
                } else {
                    Outcome::runtime_failure(format!("failed to set subspace global: {e}"))
                }
            })?;

        // Sandboxing: clear all globals not explicitly allowed
        {
            let globals = lua.globals();

            let allowed_globals = [
                "load",
                "type",
                "pairs",
                "ipairs",
                "next",
                "select",
                "tonumber",
                "tostring",
                "error",
                "assert",
                "pcall",
                "xpcall",
                "rawget",
                "rawset",
                "rawlen",
                "setmetatable",
                "getmetatable",
                "_G",
                "_VERSION",
            ];
            let allowed_libs = ["table", "string", "math", "utf8", "coroutine", "subspace"];

            // Set _VERSION to "Lua 5.4"
            let _ = globals.set("_VERSION", "Lua 5.4");

            let mut keys_to_remove = Vec::new();
            for pair in globals.pairs::<mlua::Value, mlua::Value>() {
                if let Ok((mlua::Value::String(k), _)) = pair {
                    if let Ok(k_str) = k.to_str() {
                        if !allowed_globals.contains(&k_str.as_ref())
                            && !allowed_libs.contains(&k_str.as_ref())
                        {
                            keys_to_remove.push(k_str.to_string());
                        }
                    }
                }
            }
            for k in keys_to_remove {
                let _ = globals.set(k, mlua::Value::Nil);
            }
        }

        // Disable string.dump
        {
            let globals = lua.globals();
            if let Ok(string_table) = globals.get::<Table>("string") {
                let disabled_dump = lua
                    .create_function(|_, _: ()| -> Result<(), mlua::Error> {
                        Err(mlua::Error::runtime("string.dump is disabled"))
                    })
                    .map_err(|e| {
                        Outcome::runtime_failure(format!(
                            "failed to create disabled string.dump: {e}"
                        ))
                    })?;
                let _ = string_table.set("dump", disabled_dump);
            }
        }

        // Every host-created thread shares this engine's interrupt and budget
        // controls; each top-level dispatch resets those controls exactly once.
        let interrupt_flag = Arc::new(AtomicBool::new(false));
        let instruction_count = Arc::new(AtomicI64::new(0));
        let hook_interval = if hook_interval == 0 {
            1000
        } else {
            hook_interval
        };
        let instruction_budget = instruction_budget as i64;

        let hash_state = Arc::clone(&callback_state);
        let host_hash_fn = lua
            .create_function(move |_, label: String| {
                let mut state = hash_state.lock().unwrap_or_else(|e| e.into_inner());
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let mut hash: u32 = 0x811c_9dc5;
                for byte in label.as_bytes() {
                    hash ^= *byte as u32;
                    hash = hash.wrapping_mul(0x0100_0193);
                }
                Ok(format!("{hash:08x}"))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_hash: {e}")))?;

        let call_state = Arc::clone(&callback_state);
        let host_call_fn = lua
            .create_function(move |_, label: String| {
                let mut state = call_state.lock().unwrap_or_else(|e| e.into_inner());
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                Ok((false, format!("rejected:{label}")))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_call: {e}")))?;

        // Private registry table marking plugin-created children. It is only
        // captured by Rust closures, never exposed to Lua globals.
        let plugin_created_threads = lua.create_table().map_err(|e| {
            Outcome::runtime_failure(format!("failed to create child thread registry: {e}"))
        })?;
        // Callback closures capture only disjoint callback state and immutable
        // execution controls; they never borrow or lock `EngineInner`.
        let spawn_state = Arc::clone(&callback_state);
        let spawn_interrupt = Arc::clone(&interrupt_flag);
        let spawn_count = Arc::clone(&instruction_count);
        let spawn_plugin_threads = plugin_created_threads.clone();
        let host_spawn_fn = lua
            .create_function(move |lua, func: Function| {
                let mut state = spawn_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Err(LuaError::runtime("host callback outside dispatch"));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let current_thread = lua.current_thread();
                if state
                    .spawn_authority
                    .map(|authority| authority.thread_identity)
                    != Some(current_thread.to_pointer() as usize)
                {
                    let plugin_child = matches!(
                        spawn_plugin_threads.raw_get::<Value>(current_thread.clone()),
                        Ok(Value::Boolean(true))
                    );
                    if state.latch_invalid_spawn && !plugin_child {
                        state.unacknowledged_invalid_spawns += 1;
                    }
                    return Ok((false, Some("E_INVALID_CONTEXT".to_string())));
                }
                if state.active_managed_tasks >= max_concurrent_tasks {
                    return Ok((false, Some("E_BUSY".to_string())));
                }
                let thread = lua.create_thread(func)?;
                let coroutine_id = state.next_coroutine_id;
                match state.spawn_admitter.admit(coroutine_id) {
                    SpawnAdmission::Accepted => {}
                    SpawnAdmission::Rejected => {
                        return Ok((false, Some("E_INVALID_CONTEXT".to_string())))
                    }
                    SpawnAdmission::Closed | SpawnAdmission::Capacity => {
                        return Ok((false, Some("E_BUSY".to_string())))
                    }
                }
                let interrupt = Arc::clone(&spawn_interrupt);
                let count = Arc::clone(&spawn_count);
                let _ = thread.set_hook(
                    HookTriggers::new().every_nth_instruction(hook_interval),
                    move |_lua, _debug| {
                        if interrupt.load(Ordering::Relaxed) {
                            return Err(LuaError::runtime("interrupted"));
                        }
                        let previous = count.fetch_add(hook_interval as i64, Ordering::Relaxed);
                        if previous + hook_interval as i64 > instruction_budget {
                            return Err(LuaError::runtime("instruction budget exhausted"));
                        }
                        Ok(VmState::Continue)
                    },
                );
                state.next_coroutine_id += 1;
                state.pending_spawns.push(PendingSpawn {
                    coroutine_id,
                    thread,
                });
                state.active_managed_tasks += 1;
                Ok((true, None))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_spawn: {e}")))?;
        let acknowledgement_state = Arc::clone(&callback_state);
        let host_acknowledge_spawn_context_fn = lua
            .create_function(move |_, _: ()| {
                let mut state = acknowledgement_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                state.unacknowledged_invalid_spawns =
                    state.unacknowledged_invalid_spawns.saturating_sub(1);
                Ok(())
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!(
                    "failed to bind spawn-context acknowledgement: {e}"
                ))
            })?;

        let sleep_state = Arc::clone(&callback_state);
        let host_prepare_sleep_fn = lua
            .create_function(move |_lua, seconds: f64| {
                let mut state = sleep_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Err(LuaError::runtime("host callback outside dispatch"));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                if seconds.is_nan() || seconds.is_infinite() || seconds < 0.0 || seconds > 86400.0 {
                    return Ok((false, Some("E_INVALID_ARGUMENT".to_string())));
                }
                if state.active_sleep_timers + state.pending_sleep_reservations >= max_timer_slots {
                    return Ok((false, Some("E_BUSY".to_string())));
                }
                state.pending_sleep_reservations += 1;
                Ok((
                    true,
                    Some(if seconds == 0.0 { 0.001 } else { seconds }.to_string()),
                ))
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_prepare_sleep: {e}"))
            })?;

        let log_state = Arc::clone(&callback_state);
        let host_log_fn = lua
            .create_function(move |lua, (level, payload): (String, Value)| {
                let mut state = log_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Err(LuaError::runtime("host callback outside dispatch"));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let json_val = match normalize_lua_value(&payload, 0) {
                    Ok(value) => value,
                    Err(_) => {
                        let error = lua.create_table()?;
                        error.set("error", "E_INVALID_VALUE")?;
                        return Ok((Value::Nil, Some(Value::Table(error))));
                    }
                };
                let now = Instant::now();
                state.log_bucket = (state.log_bucket
                    + now.duration_since(state.last_log_time).as_secs_f64() * 10.0)
                    .min(100.0);
                state.last_log_time = now;
                if state.log_bucket >= 1.0 {
                    state.log_bucket -= 1.0;
                    if state.logs.len() == 128 {
                        state.logs.pop_front();
                    }
                    state
                        .logs
                        .push_back(json!({"level": level, "payload": json_val}));
                }
                Ok((Value::Boolean(true), None))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_log: {e}")))?;

        let create_state = Arc::clone(&callback_state);
        let create_interrupt = Arc::clone(&interrupt_flag);
        let create_count = Arc::clone(&instruction_count);
        let create_plugin_threads = plugin_created_threads;
        let host_create_coroutine_fn = lua
            .create_function(move |lua, func: Function| {
                let mut state = create_state.lock().unwrap_or_else(|e| e.into_inner());
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let thread = lua.create_thread(func)?;
                create_plugin_threads.raw_set(thread.clone(), true)?;
                let interrupt = Arc::clone(&create_interrupt);
                let count = Arc::clone(&create_count);
                let _ = thread.set_hook(
                    HookTriggers::new().every_nth_instruction(hook_interval),
                    move |_lua, _debug| {
                        if interrupt.load(Ordering::Relaxed) {
                            return Err(LuaError::runtime("interrupted"));
                        }
                        let previous = count.fetch_add(hook_interval as i64, Ordering::Relaxed);
                        if previous + hook_interval as i64 > instruction_budget {
                            return Err(LuaError::runtime("instruction budget exhausted"));
                        }
                        Ok(VmState::Continue)
                    },
                );
                Ok(thread)
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_create_coroutine: {e}"))
            })?;

        // Bind all host functions on subspace
        {
            let globals = lua.globals();
            let subspace: Table = globals
                .get("subspace")
                .map_err(|e| Outcome::runtime_failure(format!("subspace global missing: {e}")))?;
            let _ = subspace.set("host_hash", host_hash_fn);
            let _ = subspace.set("host_call", host_call_fn);
            let _ = subspace.set("host_spawn", host_spawn_fn);
            let _ = subspace.set(
                "host_acknowledge_spawn_context",
                host_acknowledge_spawn_context_fn,
            );
            let _ = subspace.set("host_prepare_sleep", host_prepare_sleep_fn);
            let _ = subspace.set("host_log", host_log_fn);
            let _ = subspace.set("host_create_coroutine", host_create_coroutine_fn);
        }

        // Run bootstrap
        if let Err(e) = lua
            .load(SUBSPACE_BOOTSTRAP)
            .set_mode(ChunkMode::Text)
            .exec()
        {
            if is_memory_error(&e) {
                return Err(Outcome::memory_failure(format!(
                    "failed to bootstrap subspace: {e}"
                )));
            }
            return Err(Outcome::runtime_failure(format!(
                "failed to bootstrap subspace: {e}"
            )));
        }

        Ok(EngineInner {
            state_id,
            generation: 1,
            lua: Some(lua),
            lifecycle: Lifecycle::Init,
            entrypoint: None,
            entrypoint_name: None,
            main_coroutine_id: None,
            next_coroutine_id: 1,
            terminal_operations: HashMap::new(),
            terminal_operation_order: VecDeque::new(),
            evicted_terminal_operation_order: VecDeque::new(),
            interrupt_post_peek_hook: None,
            accountant: Accountant::new(memory_limit_bytes),
            interrupt_flag,
            instruction_count,
            instruction_budget,
            hook_interval,
            created_at: Instant::now(),
            bridge_bytes: 0,
            source_map: HashMap::new(),
            coroutines: HashMap::new(),
            spawned_coroutines: Vec::new(),
            active_sleep_operations: std::collections::HashSet::new(),
            callback_state,
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

    /// Validate the full coroutine/operation identity before a terminal action.
    fn check_operation(&self, coroutine_id: i64, operation_id: OperationId) -> OperationVerdict {
        if let Some(terminal) = self.terminal_operations.get(&operation_id) {
            return if terminal.coroutine_id == coroutine_id {
                OperationVerdict::Duplicate
            } else {
                OperationVerdict::Foreign
            };
        }
        match self.coroutines.get(&coroutine_id) {
            Some(state)
                if state.lifecycle == Lifecycle::Yielded
                    && state.operation == Some(operation_id) =>
            {
                OperationVerdict::Admit
            }
            _ => OperationVerdict::Foreign,
        }
    }

    fn is_recently_evicted_terminal_operation(
        &self,
        coroutine_id: i64,
        operation_id: OperationId,
    ) -> bool {
        self.evicted_terminal_operation_order
            .contains(&(operation_id, coroutine_id))
    }

    /// Record an accepted terminal operation with bounded FIFO retention.
    fn record_terminal_operation(
        &mut self,
        coroutine_id: i64,
        operation_id: OperationId,
        outcome: Outcome,
    ) {
        if self.terminal_operations.contains_key(&operation_id) {
            self.terminal_operations.insert(
                operation_id,
                TerminalOperation {
                    coroutine_id,
                    outcome,
                },
            );
            return;
        }

        if self.terminal_operation_order.len() == TERMINAL_OPERATION_CACHE_CAPACITY {
            let evicted = self
                .terminal_operation_order
                .pop_front()
                .expect("terminal operation FIFO length matched cache capacity");
            self.terminal_operations.remove(&evicted.0);
            self.record_evicted_terminal_operation(evicted);
        }

        self.terminal_operation_order
            .push_back((operation_id, coroutine_id));
        self.terminal_operations.insert(
            operation_id,
            TerminalOperation {
                coroutine_id,
                outcome,
            },
        );
    }

    fn record_evicted_terminal_operation(&mut self, identity: (OperationId, i64)) {
        if self.evicted_terminal_operation_order.len()
            == EVICTED_TERMINAL_OPERATION_TOMBSTONE_CAPACITY
        {
            self.evicted_terminal_operation_order.pop_front();
        }
        self.evicted_terminal_operation_order.push_back(identity);
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

    fn outcome_with_telemetry(&mut self, mut outcome: Outcome) -> Outcome {
        let report = self.memory_report();
        let elapsed = self.created_at.elapsed().as_nanos() as u64;
        outcome = outcome
            .with("stateId", json!(self.state_id))
            .with("generation", json!(self.generation))
            .with("currentBytes", json!(report.current_bytes))
            .with("peakBytes", json!(report.peak_bytes))
            .with("deniedAllocations", json!(report.denied_allocations))
            .with("bridgeBytes", json!(report.bridge_bytes))
            .with("elapsedNanos", json!(elapsed));

        if matches!(
            outcome.kind(),
            OutcomeKind::Completed | OutcomeKind::Yielded
        ) {
            let spawned: Vec<i64> = self.spawned_coroutines.drain(..).collect();
            if !spawned.is_empty() {
                outcome = outcome.with("spawnedCoroutines", json!(spawned));
            }
            let logs: Vec<serde_json::Value> = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .logs
                .drain(..)
                .collect();
            if !logs.is_empty() {
                let log_strings: Vec<String> = logs.iter().map(|v| v.to_string()).collect();
                outcome = outcome.with("logs", json!(log_strings));
            }
        }
        outcome
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

        let callback_state = Arc::clone(&self.callback_state);
        {
            let mut state = callback_state.lock().unwrap_or_else(|e| e.into_inner());
            state.dispatch_active = true;
            state.invocation_violation = false;
            state.latch_invalid_spawn = true;
            state.spawn_authority = None;
            state.unacknowledged_invalid_spawns = 0;
            state.next_coroutine_id = self.next_coroutine_id;
        }
        let _callback_dispatch = CallbackDispatchGuard {
            state: callback_state,
        };
        let mut outcome = match command {
            Command::Load { source, entrypoint } => self.handle_load(source, entrypoint),
            Command::Start => self.handle_start(),
            Command::Resume {
                coroutine_id,
                operation_id,
                success,
                value,
            } => self.handle_resume(coroutine_id, operation_id, success, value),
            Command::Cancel {
                coroutine_id,
                operation_id,
            } => self.handle_cancel(coroutine_id, operation_id),
            Command::InvalidateSuspended {
                expected_operation_id,
            } => self.handle_invalidate_suspended(expected_operation_id),
            Command::LowerMemoryLimit { new_limit_bytes } => {
                self.handle_lower_memory_limit(new_limit_bytes)
            }
            Command::Snapshot => self.handle_snapshot(),
            Command::Close => self.handle_close(),
            Command::LoadProgramImage {
                source_map_json,
                entrypoint,
            } => self.handle_load_program_image(source_map_json, entrypoint),
            Command::InvokeCallback {
                callback_name,
                arguments_json,
            } => self.handle_invoke_callback(callback_name, arguments_json),
            Command::StartCoroutine { coroutine_id } => self.handle_start_coroutine(coroutine_id),
        };
        let callback_state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let invocation_violation =
            callback_state.invocation_violation || callback_state.unacknowledged_invalid_spawns > 0;
        drop(callback_state);
        if invocation_violation {
            outcome = Outcome::runtime_failure(
                "E_INVALID_CONTEXT: spawn is prohibited in this invocation",
            );
        }
        let spawned = self.drain_callback_spawns();
        if !spawned.is_empty() {
            if matches!(
                outcome.kind(),
                OutcomeKind::Completed | OutcomeKind::Yielded
            ) {
                outcome = outcome.with("spawnedCoroutines", json!(spawned));
            } else {
                for coroutine_id in spawned {
                    self.release_coroutine(coroutine_id);
                }
            }
        }
        if !matches!(
            outcome.kind(),
            OutcomeKind::Completed | OutcomeKind::Yielded
        ) {
            self.callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .logs
                .clear();
        }
        outcome
    }

    fn drain_callback_spawns(&mut self) -> Vec<i64> {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let mut ids = Vec::with_capacity(state.pending_spawns.len());
        for pending in state.pending_spawns.drain(..) {
            let coroutine_id = pending.coroutine_id;
            let thread = pending.thread;
            self.next_coroutine_id = self.next_coroutine_id.max(coroutine_id + 1);
            self.coroutines.insert(
                coroutine_id,
                CoroutineState {
                    thread: thread.clone(),
                    lifecycle: Lifecycle::Loaded,
                    operation: None,
                    label: None,
                    managed_task: true,
                },
            );
            ids.push(coroutine_id);
        }
        ids
    }

    fn release_coroutine(&mut self, coroutine_id: i64) {
        let managed_task = self
            .coroutines
            .remove(&coroutine_id)
            .is_some_and(|state| state.managed_task);
        if managed_task {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.active_managed_tasks = state.active_managed_tasks.saturating_sub(1);
        }
    }

    fn discard_pending_sleep_reservation(&self) {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state.pending_sleep_reservations = state.pending_sleep_reservations.saturating_sub(1);
    }

    fn activate_sleep_reservation(&self) {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state.pending_sleep_reservations = state.pending_sleep_reservations.saturating_sub(1);
        state.active_sleep_timers += 1;
    }

    fn release_sleep_reservation(&self) {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state.active_sleep_timers = state.active_sleep_timers.saturating_sub(1);
    }

    fn handle_load(&mut self, source: String, entrypoint: String) -> Outcome {
        // Reject the Lua binary signature before the binding sees the chunk.
        // Android's JNI string conversion must not turn a binary request into
        // an entrypoint validation failure.
        if source.as_bytes().first() == Some(&0x1b) {
            let outcome = Outcome::syntax_failure("binary Lua chunks are not accepted");
            return self.outcome_with_telemetry(outcome);
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
                return self.outcome_with_telemetry(outcome);
            }
            let outcome = classify_load_error(&e);
            return self.outcome_with_telemetry(outcome);
        }

        let globals = self
            .lua
            .as_ref()
            .expect("lua live in handle_load")
            .globals();
        let entry_fn: Result<Function, _> = globals.get(entrypoint.as_str());
        match entry_fn {
            Ok(f) => {
                self.entrypoint = Some(f);
                self.entrypoint_name = Some(entrypoint.clone());
                self.lifecycle = Lifecycle::Loaded;
                self.main_coroutine_id = None;
                self.coroutines.clear();
                self.terminal_operations.clear();
                self.terminal_operation_order.clear();
                self.evicted_terminal_operation_order.clear();

                let outcome =
                    Outcome::new(OutcomeKind::Completed).with("diagnostic", json!("loaded"));
                self.outcome_with_telemetry(outcome)
            }
            Err(_) => {
                self.accountant.sub_bridge(source_bytes);
                self.bridge_bytes = 0;
                let outcome = Outcome::validation_failure(format!(
                    "entrypoint '{entrypoint}' is not a defined global function"
                ));
                self.outcome_with_telemetry(outcome)
            }
        }
    }

    fn handle_start(&mut self) -> Outcome {
        if self.lifecycle != Lifecycle::Loaded {
            let outcome = Outcome::validation_failure(format!(
                "cannot start: lifecycle is {:?}, expected Loaded",
                self.lifecycle
            ));
            return self.outcome_with_telemetry(outcome);
        }

        let entry_fn = match &self.entrypoint {
            Some(f) => f.clone(),
            None => {
                let outcome = Outcome::validation_failure("no entrypoint loaded");
                return self.outcome_with_telemetry(outcome);
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
                    return self.outcome_with_telemetry(outcome);
                }
                let outcome = Outcome::runtime_failure(format!("failed to create coroutine: {e}"));
                return self.outcome_with_telemetry(outcome);
            }
        };

        // Set the instruction hook on this coroutine.
        self.reset_execution_budget();
        self.setup_hook(&thread);

        self.lifecycle = Lifecycle::Running;
        let co_id = self.next_coroutine_id;
        self.next_coroutine_id += 1;
        self.main_coroutine_id = Some(co_id);
        self.coroutines.insert(
            co_id,
            CoroutineState {
                thread: thread.clone(),
                lifecycle: Lifecycle::Running,
                operation: None,
                label: None,
                managed_task: false,
            },
        );

        // Resume the coroutine for the first time.
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.spawn_authority = Some(SpawnAuthority {
                thread_identity: thread.to_pointer() as usize,
            });
        }
        let result = thread.resume::<Value>(());
        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .spawn_authority = None;
        let outcome = self.classify_resume_result(result, &thread, co_id);

        self.outcome_with_telemetry(outcome)
    }

    fn handle_resume(
        &mut self,
        coroutine_id: i64,
        operation_id: OperationId,
        success: bool,
        value: String,
    ) -> Outcome {
        if self.is_recently_evicted_terminal_operation(coroutine_id, operation_id) {
            return self
                .outcome_with_telemetry(self.stale_evicted_terminal_operation(operation_id));
        }
        match self.check_operation(coroutine_id, operation_id) {
            OperationVerdict::Foreign => {
                return self.outcome_with_telemetry(Outcome::invalid_ownership(
                    "coroutine/operation is not live in this state",
                ))
            }
            OperationVerdict::Duplicate => {
                return self.outcome_with_telemetry(self.echo_terminal(operation_id))
            }
            OperationVerdict::Admit => {}
        }

        if self.active_sleep_operations.remove(&operation_id) {
            self.release_sleep_reservation();
        }
        let thread = {
            let state = self
                .coroutines
                .get_mut(&coroutine_id)
                .expect("admitted coroutine record disappeared");
            state.operation = None;
            state.label = None;
            state.lifecycle = Lifecycle::Running;
            state.thread.clone()
        };
        self.lifecycle = Lifecycle::Running;
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.spawn_authority = Some(SpawnAuthority {
                thread_identity: thread.to_pointer() as usize,
            });
        }
        let result = thread.resume::<Value>((success, value));
        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .spawn_authority = None;
        let outcome = self.classify_resume_result(result, &thread, coroutine_id);
        self.record_terminal_operation(coroutine_id, operation_id, outcome.clone());
        self.outcome_with_telemetry(outcome)
    }

    fn handle_cancel(&mut self, coroutine_id: i64, operation_id: OperationId) -> Outcome {
        if self.is_recently_evicted_terminal_operation(coroutine_id, operation_id) {
            return self
                .outcome_with_telemetry(self.stale_evicted_terminal_operation(operation_id));
        }
        match self.check_operation(coroutine_id, operation_id) {
            OperationVerdict::Foreign => {
                return self.outcome_with_telemetry(Outcome::invalid_ownership(
                    "coroutine/operation is not live in this state",
                ))
            }
            OperationVerdict::Duplicate => {
                return self.outcome_with_telemetry(self.echo_terminal(operation_id))
            }
            OperationVerdict::Admit => {}
        }

        if self.active_sleep_operations.remove(&operation_id) {
            self.release_sleep_reservation();
        }
        self.release_coroutine(coroutine_id);
        self.lifecycle = Lifecycle::Cancelled;
        let outcome = Outcome::new(OutcomeKind::Cancelled)
            .with("coroutineId", json!(coroutine_id))
            .with("operationId", json!(operation_id));
        self.record_terminal_operation(coroutine_id, operation_id, outcome.clone());
        self.outcome_with_telemetry(outcome)
    }

    /// Echo the exact accepted outcome for a duplicate resume/cancel without
    /// re-entering Lua. This preserves both terminal kind and result payload.
    fn echo_terminal(&self, operation_id: OperationId) -> Outcome {
        self.terminal_operations
            .get(&operation_id)
            .map(|terminal| terminal.outcome.clone())
            .unwrap_or_else(|| {
                Outcome::invalid_ownership("terminal outcome missing for consumed operation")
            })
    }

    fn handle_snapshot(&mut self) -> Outcome {
        let report = self.memory_report();
        let elapsed = self.created_at.elapsed().as_nanos() as u64;

        let outcome = Outcome::new(OutcomeKind::Completed)
            .with("stateId", json!(self.state_id))
            .with("generation", json!(self.generation))
            .with("currentBytes", json!(report.current_bytes))
            .with("peakBytes", json!(report.peak_bytes))
            .with("deniedAllocations", json!(report.denied_allocations))
            .with("bridgeBytes", json!(report.bridge_bytes))
            .with("elapsedNanos", json!(elapsed))
            .with("luaVersion", json!(crate::LUA_VERSION))
            .with("bindingVersion", json!(crate::BINDING_VERSION))
            .with("operation", json!("snapshot"))
            .with("topology", json!("jvm_owned"));
        self.outcome_with_telemetry(outcome)
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
        self.main_coroutine_id = None;
        self.entrypoint_name = None;
        self.coroutines.clear();
        self.spawned_coroutines.clear();
        self.source_map.clear();
        self.active_sleep_operations.clear();
        self.terminal_operations.clear();
        self.terminal_operation_order.clear();
        self.evicted_terminal_operation_order.clear();
        self.accountant.sub_bridge(self.bridge_bytes);
        self.bridge_bytes = 0;
        let mut callback_state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        callback_state.pending_spawns.clear();
        callback_state.active_managed_tasks = 0;
        callback_state.pending_sleep_reservations = 0;
        callback_state.active_sleep_timers = 0;
        callback_state.logs.clear();
        callback_state.evaluating_module = false;
        callback_state.module_effect_attempted = false;

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
    /// - If a yielded coroutine record still carries `expected_operation_id`,
    ///   this is the original suspension. Record its terminal rejection, drop
    ///   only that coroutine, and return `Interrupted`; later matching
    ///   resume/cancel echoes the cached terminal result.
    /// - Otherwise a concurrent resume/cancel/close won first. Leave the
    ///   resulting state unchanged, clear the flag set for the old suspended
    ///   operation, and return typed `Stale` carrying the observed operation
    ///   id. This prevents an old suspended interrupt from affecting a newer
    ///   yielded operation.
    fn handle_invalidate_suspended(&mut self, expected_operation_id: OperationId) -> Outcome {
        let coroutine_id = self.coroutines.iter().find_map(|(&id, state)| {
            (state.lifecycle == Lifecycle::Yielded
                && state.operation == Some(expected_operation_id))
            .then_some(id)
        });
        if let Some(coroutine_id) = coroutine_id {
            let outcome =
                Outcome::runtime_failure("suspended operation was terminally invalidated");
            self.record_terminal_operation(coroutine_id, expected_operation_id, outcome);
            if self.active_sleep_operations.remove(&expected_operation_id) {
                self.release_sleep_reservation();
            }
            self.release_coroutine(coroutine_id);
            self.lifecycle = Lifecycle::Failed;
            return self.outcome_with_telemetry(
                Outcome::new(OutcomeKind::Interrupted)
                    .with("coroutineId", json!(coroutine_id))
                    .with("operationId", json!(expected_operation_id))
                    .with(
                        "diagnostic",
                        json!("suspended state terminally invalidated"),
                    ),
            );
        }

        self.interrupt_flag.store(false, Ordering::Relaxed);
        self.outcome_with_telemetry(
            Outcome::new(OutcomeKind::Stale)
                .with("operationId", json!(expected_operation_id))
                .with(
                    "diagnostic",
                    json!("interrupt overtaken by concurrent resume; suspension unchanged"),
                ),
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
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "set_memory_limit failed: {e}"
                )));
            }
        }
        // Update the Accountant's tracking to match.
        self.accountant.lower_limit(new_limit_bytes);
        self.outcome_with_telemetry(
            Outcome::new(OutcomeKind::Completed).with("memoryLimitBytes", json!(new_limit_bytes)),
        )
    }

    /// Begin a top-level dispatch with a fresh shared instruction budget.
    /// Descendant coroutine hooks deliberately share these atomics.
    fn reset_execution_budget(&self) {
        self.instruction_count.store(0, Ordering::Relaxed);
        self.interrupt_flag.store(false, Ordering::Relaxed);
    }

    /// Set up the instruction-count hook on a coroutine thread.
    fn setup_hook(&self, thread: &Thread) {
        let interval = self.hook_interval;
        let budget = self.instruction_budget;
        let interrupt_flag = self.interrupt_flag.clone();
        let count = self.instruction_count.clone();

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
    /// `thread` is the coroutine handle that was resumed; its matching
    /// `CoroutineState` is authoritative for the resulting transition.
    fn classify_resume_result(
        &mut self,
        result: Result<Value, LuaError>,
        thread: &Thread,
        coroutine_id: i64,
    ) -> Outcome {
        let was_interrupted = self.interrupt_flag.load(Ordering::Relaxed);
        match result {
            Ok(value) if thread.status() == ThreadStatus::Resumable => {
                let raw_label = value_to_string(&value);
                let Some(label) = raw_label.strip_prefix(OPERATION_YIELD_PREFIX) else {
                    self.discard_pending_sleep_reservation();
                    self.release_coroutine(coroutine_id);
                    self.lifecycle = Lifecycle::Failed;
                    return Outcome::runtime_failure("E_INVALID_YIELD");
                };
                let label = label.to_string();
                let operation_id = assign_operation_id();
                let state = self
                    .coroutines
                    .get_mut(&coroutine_id)
                    .expect("resumed coroutine record disappeared");
                state.lifecycle = Lifecycle::Yielded;
                state.operation = Some(operation_id);
                state.label = Some(label.clone());
                self.lifecycle = Lifecycle::Yielded;
                if label.starts_with("sleep:") {
                    self.activate_sleep_reservation();
                    self.active_sleep_operations.insert(operation_id);
                }
                Outcome::new(OutcomeKind::Yielded)
                    .with("coroutineId", json!(coroutine_id))
                    .with("operationId", json!(operation_id))
                    .with("value", json!(label))
            }
            Ok(value) => {
                self.discard_pending_sleep_reservation();
                self.release_coroutine(coroutine_id);
                self.lifecycle = Lifecycle::Completed;
                Outcome::new(OutcomeKind::Completed)
                    .with("coroutineId", json!(coroutine_id))
                    .with("value", json!(value_to_string(&value)))
            }
            Err(e) => {
                self.discard_pending_sleep_reservation();
                self.release_coroutine(coroutine_id);
                let error_text = e.to_string();
                let outcome = if was_interrupted {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("execution interrupted"))
                } else if self.instruction_count.load(Ordering::Relaxed) > self.instruction_budget
                    && error_text.contains("instruction budget exhausted")
                {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("instruction budget exhausted"))
                } else if is_memory_error(&e) {
                    self.accountant.record_denial();
                    Outcome::memory_failure(error_text)
                } else {
                    Outcome::runtime_failure(error_text)
                };
                self.lifecycle = Lifecycle::Failed;
                outcome
            }
        }
    }

    fn handle_load_program_image(
        &mut self,
        source_map_json: String,
        entrypoint: String,
    ) -> Outcome {
        let lua = match &self.lua {
            Some(l) => l,
            None => {
                let outcome = Outcome::new(OutcomeKind::Closed);
                return self.outcome_with_telemetry(outcome);
            }
        };

        let map: HashMap<String, String> = match serde_json::from_str(&source_map_json) {
            Ok(m) => m,
            Err(e) => {
                let outcome = Outcome::validation_failure(format!("invalid source map JSON: {e}"));
                return self.outcome_with_telemetry(outcome);
            }
        };
        if map.keys().any(|name| !is_valid_plugin_module_name(name)) {
            return self.outcome_with_telemetry(Outcome::validation_failure(
                "invalid or reserved module name",
            ));
        }

        let mut source_bytes = entrypoint.len() as i64;
        for (k, v) in &map {
            source_bytes += k.len() as i64 + v.len() as i64;
        }
        let previous_source_map = self.source_map.clone();
        let previous_bridge_bytes = self.bridge_bytes;
        self.accountant.sub_bridge(previous_bridge_bytes);
        self.bridge_bytes = source_bytes;
        self.accountant.add_bridge(source_bytes);

        // Swap in per-image tables before requiring so absent old sources and
        // cached modules cannot leak into the new image. Keep old tables alive
        // until validation commits, allowing every early error to restore them.
        let subspace: Table = match lua.globals().get("subspace") {
            Ok(t) => t,
            Err(e) => {
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "subspace missing: {e}"
                )));
            }
        };
        let old_sources: Table = match subspace.get("_sources") {
            Ok(t) => t,
            Err(e) => {
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "_sources missing: {e}"
                )));
            }
        };
        let old_modules: Table = match subspace.get("_modules") {
            Ok(t) => t,
            Err(e) => {
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "_modules missing: {e}"
                )));
            }
        };
        let old_callbacks: Value = subspace.get("_callbacks").unwrap_or(Value::Nil);
        let old_image_env: Value = subspace.get("_image_env").unwrap_or(Value::Nil);
        macro_rules! rollback_image {
            ($outcome:expr) => {{
                let _ = subspace.set("_sources", old_sources.clone());
                let _ = subspace.set("_modules", old_modules.clone());
                let _ = subspace.set("_callbacks", old_callbacks.clone());
                let _ = subspace.set("_image_env", old_image_env.clone());
                self.source_map = previous_source_map.clone();
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                self.lifecycle = Lifecycle::Loaded;
                return self.outcome_with_telemetry($outcome);
            }};
        }
        macro_rules! rollback_image_memory_failure {
            ($denied_before:expr, $context:literal, $error:expr) => {{
                if self.accountant.report(lua.used_memory() as u64).denied_allocations
                    == $denied_before
                {
                    self.accountant.record_denial();
                }
                rollback_image!(Outcome::memory_failure(format!("{}: {}", $context, $error)))
            }};
        }
        let denied_before = self.accountant.report(lua.used_memory() as u64).denied_allocations;
        let sources = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => {
                rollback_image_memory_failure!(denied_before, "failed to stage sources", error)
            }
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to stage sources: {error}"
            ))),
        };
        let denied_before = self.accountant.report(lua.used_memory() as u64).denied_allocations;
        let modules = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => rollback_image_memory_failure!(
                denied_before,
                "failed to stage module cache",
                error
            ),
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to stage module cache: {error}"
            ))),
        };
        let denied_before = self.accountant.report(lua.used_memory() as u64).denied_allocations;
        let image_env = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => rollback_image_memory_failure!(
                denied_before,
                "failed to create image environment",
                error
            ),
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to create image environment: {error}"
            ))),
        };
        let denied_before = self.accountant.report(lua.used_memory() as u64).denied_allocations;
        let image_metatable = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => rollback_image_memory_failure!(
                denied_before,
                "failed to create image environment metatable",
                error
            ),
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to create image environment metatable: {error}"
            ))),
        };
        let image_factory: Function = match subspace.get("_new_image_namespace") {
            Ok(factory) => factory,
            Err(e) => rollback_image!(Outcome::runtime_failure(format!(
                "image namespace factory missing: {e}"
            ))),
        };
        let image_namespace: Table =
            match image_factory.call((sources.clone(), modules.clone(), image_env.clone())) {
                Ok(namespace) => namespace,
                Err(e) => rollback_image!(Outcome::runtime_failure(format!(
                    "failed to create image namespace: {e}"
                ))),
            };
        let globals = lua.globals();
        if let Err(e) = image_env
            .set("_G", image_env.clone())
            .and_then(|_| image_env.set("subspace", image_namespace))
            .and_then(|_| image_metatable.set("__index", globals))
            .and_then(|_| image_metatable.set("__metatable", false))
            .and_then(|_| image_env.set_metatable(Some(image_metatable)))
        {
            rollback_image!(Outcome::runtime_failure(format!(
                "failed to initialize image environment: {e}"
            )));
        }
        if let Err(e) = subspace
            .set("_sources", sources.clone())
            .and_then(|_| subspace.set("_modules", modules))
            .and_then(|_| subspace.set("_image_env", image_env))
        {
            rollback_image!(Outcome::runtime_failure(format!(
                "failed to stage image tables: {e}"
            )));
        }
        for (key, value) in &map {
            if let Err(e) = sources.set(key.as_str(), value.as_str()) {
                rollback_image!(Outcome::runtime_failure(format!(
                    "failed to set source {key}: {e}"
                )));
            }
        }

        // `require` holds the bootstrap's private native loader; image code
        // must not observe a dynamic compilation primitive while evaluating.
        let _ = lua.globals().set("load", Value::Nil);
        let require_fn: Function = match lua.globals().get("require") {
            Ok(f) => f,
            Err(e) => rollback_image!(Outcome::runtime_failure(format!(
                "require function missing: {e}"
            ))),
        };

        let main_thread = lua.current_thread();
        self.reset_execution_budget();
        self.setup_hook(&main_thread);

        self.lifecycle = Lifecycle::Running;
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.evaluating_module = true;
            state.module_effect_attempted = false;
        }
        let result = require_fn.call::<Value>(entrypoint.clone());
        let module_effect_attempted = {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.evaluating_module = false;
            state.module_effect_attempted
        };
        self.lifecycle = Lifecycle::Completed;

        if module_effect_attempted {
            rollback_image!(Outcome::runtime_failure("effect-call-during-load"));
        }
        let was_interrupted = self.interrupt_flag.load(Ordering::Relaxed);
        let val = match result {
            Ok(v) => v,
            Err(e) => {
                let outcome = if was_interrupted {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("execution interrupted"))
                } else if self.instruction_count.load(Ordering::Relaxed) > self.instruction_budget {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("instruction budget exhausted"))
                } else if is_memory_error(&e) {
                    self.accountant.record_denial();
                    Outcome::memory_failure(format!("{e}"))
                } else {
                    classify_load_error(&e)
                };
                rollback_image!(outcome);
            }
        };

        let table = match val {
            Value::Table(t) => t,
            _ => rollback_image!(Outcome::validation_failure("entrypoint returned non-table")),
        };
        if table.metatable().is_some() {
            rollback_image!(Outcome::validation_failure("callback table has metatable"));
        }
        let startup: Value = match table.raw_get("startup") {
            Ok(v) => v,
            Err(e) => rollback_image!(Outcome::runtime_failure(e.to_string())),
        };
        match startup {
            Value::Function(_) => {}
            Value::Nil => rollback_image!(Outcome::validation_failure(
                "required callback 'startup' is missing"
            )),
            _ => rollback_image!(Outcome::validation_failure(format!(
                "expected function for callback 'startup', got {}",
                startup.type_name()
            ))),
        }
        let optional_keys = [
            "handle_lifecycle",
            "handle_input",
            "handle_sos",
            "handle_readiness",
        ];
        let mut callbacks_list = vec!["startup".to_string()];
        for key in optional_keys {
            let cb: Value = match table.raw_get(key) {
                Ok(v) => v,
                Err(e) => rollback_image!(Outcome::runtime_failure(e.to_string())),
            };
            match cb {
                Value::Function(_) => callbacks_list.push(key.to_string()),
                Value::Nil => {}
                _ => rollback_image!(Outcome::validation_failure(format!(
                    "expected function for callback '{}', got {}",
                    key,
                    cb.type_name()
                ))),
            }
        }
        let subspace: Table = match lua.globals().get("subspace") {
            Ok(t) => t,
            Err(e) => rollback_image!(Outcome::runtime_failure(e.to_string())),
        };
        if let Err(e) = subspace.set("_callbacks", table) {
            rollback_image!(Outcome::runtime_failure(e.to_string()));
        }
        // Program images do not expose dynamic source compilation after their
        // entrypoint has been transactionally evaluated.
        let _ = lua.globals().set("load", Value::Nil);

        self.source_map = map;
        self.lifecycle = Lifecycle::Loaded;
        self.entrypoint_name = Some(entrypoint);
        self.main_coroutine_id = None;
        self.coroutines.clear();
        self.terminal_operations.clear();
        self.terminal_operation_order.clear();
        self.evicted_terminal_operation_order.clear();

        let callbacks_json = json!(callbacks_list).to_string();
        let outcome = Outcome::new(OutcomeKind::Completed)
            .with("value", json!(callbacks_json))
            .with("diagnostic", json!("loaded"));
        self.outcome_with_telemetry(outcome)
    }

    fn handle_invoke_callback(&mut self, callback_name: String, arguments_json: String) -> Outcome {
        let lua = match &self.lua {
            Some(l) => l,
            None => return self.outcome_with_telemetry(Outcome::new(OutcomeKind::Closed)),
        };

        let args_val: serde_json::Value = if arguments_json.is_empty() {
            serde_json::Value::Null
        } else {
            match serde_json::from_str(&arguments_json) {
                Ok(value) => value,
                Err(e) => {
                    return self.outcome_with_telemetry(Outcome::validation_failure(format!(
                        "invalid arguments JSON: {e}"
                    )))
                }
            }
        };

        let lua_args = match json_to_lua(lua, &args_val) {
            Ok(v) => v,
            Err(e) => {
                let outcome =
                    Outcome::validation_failure(format!("failed to convert arguments to Lua: {e}"));
                return self.outcome_with_telemetry(outcome);
            }
        };

        let subspace: Table = match lua.globals().get("subspace") {
            Ok(t) => t,
            Err(e) => {
                let outcome = Outcome::runtime_failure(format!("subspace global missing: {e}"));
                return self.outcome_with_telemetry(outcome);
            }
        };
        let callbacks: Table = match subspace.get("_callbacks") {
            Ok(t) => t,
            Err(e) => {
                let outcome = Outcome::runtime_failure(format!("_callbacks missing: {e}"));
                return self.outcome_with_telemetry(outcome);
            }
        };
        let callback_fn: Function = match callbacks.raw_get(callback_name.as_str()) {
            Ok(Value::Function(f)) => f,
            _ => {
                let outcome =
                    Outcome::validation_failure(format!("callback '{callback_name}' not found"));
                return self.outcome_with_telemetry(outcome);
            }
        };

        let main_thread = lua.current_thread();
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.spawn_authority = (callback_name == "startup").then_some(SpawnAuthority {
                thread_identity: main_thread.to_pointer() as usize,
            });
        }
        self.reset_execution_budget();
        self.setup_hook(&main_thread);

        self.lifecycle = Lifecycle::Running;
        let result = if arguments_json.is_empty() {
            callback_fn.call::<Value>(())
        } else {
            callback_fn.call::<Value>(lua_args)
        };

        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .spawn_authority = None;

        let was_interrupted = self.interrupt_flag.load(Ordering::Relaxed);
        self.lifecycle = Lifecycle::Completed;

        let outcome = match result {
            Ok(val) => {
                if let Err(err_msg) = validate_callback_return(&callback_name, &val) {
                    Outcome::runtime_failure(format!("callback contract violation: {err_msg}"))
                } else {
                    match normalize_lua_value(&val, 0) {
                        Ok(json_res) => {
                            Outcome::new(OutcomeKind::Completed).with("value", json_res)
                        }
                        Err(e) => Outcome::runtime_failure(format!("E_INVALID_VALUE: {e}")),
                    }
                }
            }
            Err(e) => {
                if was_interrupted {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("execution interrupted"))
                } else if self.instruction_count.load(Ordering::Relaxed) > self.instruction_budget {
                    Outcome::new(OutcomeKind::Interrupted)
                        .with("diagnostic", json!("instruction budget exhausted"))
                } else if is_memory_error(&e) {
                    self.accountant.record_denial();
                    Outcome::memory_failure(format!("{e}"))
                } else {
                    let err_msg = e.to_string();
                    if err_msg.contains("attempt to yield") {
                        Outcome::runtime_failure("E_INVALID_YIELD")
                    } else {
                        Outcome::runtime_failure(err_msg)
                    }
                }
            }
        };

        self.outcome_with_telemetry(outcome)
    }

    fn handle_start_coroutine(&mut self, coroutine_id: i64) -> Outcome {
        let thread = match self.coroutines.get_mut(&coroutine_id) {
            Some(state) if state.lifecycle == Lifecycle::Loaded => {
                state.lifecycle = Lifecycle::Running;
                state.thread.clone()
            }
            Some(_) => {
                return self.outcome_with_telemetry(Outcome::invalid_ownership(
                    "coroutine is not ready to start",
                ))
            }
            None => {
                return self
                    .outcome_with_telemetry(Outcome::invalid_ownership("coroutine id not found"))
            }
        };
        self.lifecycle = Lifecycle::Running;
        self.reset_execution_budget();
        self.setup_hook(&thread);

        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.spawn_authority = Some(SpawnAuthority {
                thread_identity: thread.to_pointer() as usize,
            });
        }
        let result = thread.resume::<Value>(());
        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .spawn_authority = None;
        let outcome = self.classify_resume_result(result, &thread, coroutine_id);

        self.outcome_with_telemetry(outcome)
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
        max_concurrent_tasks: usize,
        max_timer_slots: usize,
    ) -> Result<Self, Outcome> {
        let state_id = assign_state_id();
        let inner_val = EngineInner::new(
            state_id,
            memory_limit_bytes,
            hook_interval,
            instruction_budget,
            max_concurrent_tasks,
            max_timer_slots,
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
        self.start_with_spawn_admitter(generation, Arc::new(|_| SpawnAdmission::Rejected))
    }

    /// Production bridges that lack a host execution context use this variant
    /// to reject child-spawn admission while retaining the raw test API above.
    pub fn start_with_spawn_admitter(
        &self,
        generation: Generation,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(generation, Command::Start, admitter)
    }

    pub fn resume(
        &self,
        generation: Generation,
        operation_id: OperationId,
        success: bool,
        value: &str,
    ) -> Outcome {
        let coroutine_id = {
            let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            inner.main_coroutine_id.unwrap_or_default()
        };
        self.resume_coroutine(generation, coroutine_id, operation_id, success, value)
    }

    pub fn resume_coroutine(
        &self,
        generation: Generation,
        coroutine_id: i64,
        operation_id: OperationId,
        success: bool,
        value: &str,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::Resume {
                coroutine_id,
                operation_id,
                success,
                value: value.to_string(),
            },
        )
    }
    pub fn resume_coroutine_with_spawn_admitter(
        &self,
        generation: Generation,
        coroutine_id: i64,
        operation_id: OperationId,
        success: bool,
        value: &str,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(
            generation,
            Command::Resume {
                coroutine_id,
                operation_id,
                success,
                value: value.to_string(),
            },
            admitter,
        )
    }

    pub fn cancel(&self, generation: Generation, operation_id: OperationId) -> Outcome {
        let coroutine_id = {
            let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            inner.main_coroutine_id.unwrap_or_default()
        };
        self.cancel_coroutine(generation, coroutine_id, operation_id)
    }

    pub fn cancel_coroutine(
        &self,
        generation: Generation,
        coroutine_id: i64,
        operation_id: OperationId,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::Cancel {
                coroutine_id,
                operation_id,
            },
        )
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

    pub fn load_program_image(
        &self,
        generation: Generation,
        source_map_json: &str,
        entrypoint: &str,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::LoadProgramImage {
                source_map_json: source_map_json.to_string(),
                entrypoint: entrypoint.to_string(),
            },
        )
    }
    /// Invoke one callback with a host admission decision scoped to this native
    /// execution slice. Direct Rust callers retain the accepting default.
    pub fn invoke_callback_with_spawn_admitter(
        &self,
        generation: Generation,
        callback_name: &str,
        arguments_json: &str,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(
            generation,
            Command::InvokeCallback {
                callback_name: callback_name.to_string(),
                arguments_json: arguments_json.to_string(),
            },
            admitter,
        )
    }

    pub fn start_coroutine_with_spawn_admitter(
        &self,
        generation: Generation,
        coroutine_id: i64,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(
            generation,
            Command::StartCoroutine { coroutine_id },
            admitter,
        )
    }

    pub fn invoke_callback(
        &self,
        generation: Generation,
        callback_name: &str,
        arguments_json: &str,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::InvokeCallback {
                callback_name: callback_name.to_string(),
                arguments_json: arguments_json.to_string(),
            },
        )
    }

    pub fn start_coroutine(&self, generation: Generation, coroutine_id: i64) -> Outcome {
        self.dispatch(generation, Command::StartCoroutine { coroutine_id })
    }

    /// Set the atomic interrupt flag and, if the state is suspended (Yielded),
    /// terminally invalidate the coroutine/operation so a later resume/cancel
    /// cannot produce a Lua effect. The atomic flag handles the running case
    /// fire-and-forget; the suspended invalidation goes through the normal
    /// dispatch path for topology-safe serialization.
    ///
    /// Linearization: the peek reads one yielded record's exact operation id
    /// atomically with ownership under the state mutex. Tagged invalidation
    /// later removes only a record still carrying that id. A concurrent resume
    /// that consumed it or produced a fresh yield makes the identity mismatch,
    /// yielding typed `Stale` rather than destroying another suspension.
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
                inner.coroutines.values().find_map(|state| {
                    (state.lifecycle == Lifecycle::Yielded)
                        .then_some(state.operation)
                        .flatten()
                }),
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
    fn dispatch_with_spawn_admitter(
        &self,
        generation: Generation,
        command: Command,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let mut state = inner
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let previous = std::mem::replace(&mut state.spawn_admitter, admitter);
        state.latch_invalid_spawn = true;
        drop(state);
        let outcome = inner.process(generation, command);
        let mut state = inner
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state.spawn_admitter = previous;
        state.latch_invalid_spawn = false;
        outcome
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
        Value::Number(n) if n.fract() == 0.0 => format!("{n:.1}"),
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.to_string_lossy(),
        _ => String::new(),
    }
}

fn classify_load_error(error: &LuaError) -> Outcome {
    match error {
        LuaError::SyntaxError { message, .. } => Outcome::syntax_failure(message.clone()),
        LuaError::MemoryError(message) => Outcome::memory_failure(message.clone()),
        _ => Outcome::runtime_failure(format!("{error}")),
    }
}

fn is_memory_error(error: &LuaError) -> bool {
    match error {
        LuaError::MemoryError(_) => true,
        LuaError::CallbackError { cause, .. } => is_memory_error(cause),
        _ => false,
    }
}

fn is_valid_plugin_module_name(name: &str) -> bool {
    !name.is_empty()
        && name != "subspace"
        && !name.starts_with("subspace.")
        && name.split('.').all(|segment| {
            let mut chars = segment.chars();
            matches!(chars.next(), Some(first) if first.is_ascii_lowercase())
                && chars.all(|character| {
                    character.is_ascii_lowercase() || character.is_ascii_digit() || character == '_'
                })
        })
}

fn normalize_lua_value(value: &Value, depth: usize) -> Result<serde_json::Value, String> {
    let mut budget = NormalizationBudget::default();
    normalize_lua_value_inner(value, depth, &mut budget)
}

#[derive(Default)]
struct NormalizationBudget {
    entries: usize,
    string_bytes: usize,
    output_work: usize,
    tables: std::collections::HashSet<usize>,
}

fn normalize_lua_value_inner(
    value: &Value,
    depth: usize,
    budget: &mut NormalizationBudget,
) -> Result<serde_json::Value, String> {
    const MAX_DEPTH: usize = 10;
    const MAX_ENTRIES: usize = 1_000;
    const MAX_STRING_BYTES: usize = 65_536;
    const MAX_OUTPUT_WORK: usize = 1_000;
    if depth > MAX_DEPTH {
        return Err("depth exceeded".to_string());
    }
    budget.output_work = budget
        .output_work
        .checked_add(1)
        .ok_or("output work exceeded")?;
    if budget.output_work > MAX_OUTPUT_WORK {
        return Err("output work exceeded".to_string());
    }
    match value {
        Value::Nil => Ok(serde_json::Value::Null),
        Value::Boolean(b) => Ok(serde_json::Value::Bool(*b)),
        Value::Integer(i) => Ok(serde_json::Value::Number((*i).into())),
        Value::Number(n) if n.is_finite() => serde_json::Number::from_f64(*n)
            .map(serde_json::Value::Number)
            .ok_or_else(|| "invalid number".to_string()),
        Value::Number(_) => Err("non-finite number".to_string()),
        Value::String(s) => {
            let bytes = s.as_bytes();
            budget.string_bytes = budget
                .string_bytes
                .checked_add(bytes.len())
                .ok_or("string bytes exceeded")?;
            if budget.string_bytes > MAX_STRING_BYTES {
                return Err("string bytes exceeded".to_string());
            }
            std::str::from_utf8(&bytes)
                .map(|text| serde_json::Value::String(text.to_owned()))
                .map_err(|_| "invalid utf-8".to_string())
        }
        Value::Table(table) => {
            if table.metatable().is_some() {
                return Err("table has metatable".to_string());
            }
            let identity = table.to_pointer() as usize;
            if !budget.tables.insert(identity) {
                return Err("repeated or cyclic table".to_string());
            }
            let result = (|| {
                let mut pairs = Vec::new();
                let mut array = true;
                let mut map = true;
                let mut max_index = 0i64;
                for pair in table.clone().pairs::<Value, Value>() {
                    let (key, child) = pair.map_err(|error| error.to_string())?;
                    budget.entries = budget.entries.checked_add(1).ok_or("entries exceeded")?;
                    if budget.entries > MAX_ENTRIES {
                        return Err("entries exceeded".to_string());
                    }
                    match &key {
                        Value::Integer(index) if *index > 0 => {
                            map = false;
                            max_index = max_index.max(*index);
                        }
                        Value::String(text) => {
                            array = false;
                            let bytes = text.as_bytes();
                            budget.string_bytes = budget
                                .string_bytes
                                .checked_add(bytes.len())
                                .ok_or("string bytes exceeded")?;
                            if budget.string_bytes > MAX_STRING_BYTES
                                || std::str::from_utf8(&bytes).is_err()
                            {
                                return Err("invalid string key".to_string());
                            }
                        }
                        Value::Integer(_) => {
                            array = false;
                            map = false;
                        }
                        _ => return Err("invalid key type".to_string()),
                    }
                    pairs.push((key, child));
                }
                if array && max_index as usize == pairs.len() {
                    let mut values = Vec::with_capacity(pairs.len());
                    for index in 1..=pairs.len() {
                        let child: Value = table.get(index).map_err(|error| error.to_string())?;
                        values.push(normalize_lua_value_inner(&child, depth + 1, budget)?);
                    }
                    Ok(serde_json::Value::Array(values))
                } else if map {
                    let mut object = serde_json::Map::new();
                    for (key, child) in pairs {
                        let Value::String(key) = key else {
                            return Err("mixed table".to_string());
                        };
                        let key = std::str::from_utf8(&key.as_bytes())
                            .map_err(|_| "invalid utf-8 string key")?
                            .to_owned();
                        object.insert(key, normalize_lua_value_inner(&child, depth + 1, budget)?);
                    }
                    Ok(serde_json::Value::Object(object))
                } else {
                    Err("mixed or sparse table".to_string())
                }
            })();
            result
        }
        _ => Err("disallowed type".to_string()),
    }
}

fn json_to_lua(lua: &Lua, value: &serde_json::Value) -> Result<Value, mlua::Error> {
    match value {
        serde_json::Value::Null => Ok(Value::Nil),
        serde_json::Value::Bool(b) => Ok(Value::Boolean(*b)),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(Value::Integer(i))
            } else if let Some(f) = n.as_f64() {
                Ok(Value::Number(f))
            } else {
                Err(mlua::Error::runtime("invalid JSON number"))
            }
        }
        serde_json::Value::String(s) => Ok(Value::String(lua.create_string(s)?)),
        serde_json::Value::Array(arr) => {
            let table = lua.create_table()?;
            for (i, val) in arr.iter().enumerate() {
                table.set(i + 1, json_to_lua(lua, val)?)?;
            }
            Ok(Value::Table(table))
        }
        serde_json::Value::Object(obj) => {
            let table = lua.create_table()?;
            for (key, val) in obj.iter() {
                table.set(key.as_str(), json_to_lua(lua, val)?)?;
            }
            Ok(Value::Table(table))
        }
    }
}

fn validate_callback_return(callback_name: &str, value: &Value) -> Result<(), String> {
    match callback_name {
        "startup" | "handle_lifecycle" | "handle_sos" => match value {
            Value::Nil => Ok(()),
            Value::Table(t) => {
                if let Ok(err_val) = t.raw_get::<Value>("error") {
                    if err_val != Value::Nil {
                        let err_table = match err_val {
                            Value::Table(et) => et,
                            _ => return Err("error field must be a table".to_string()),
                        };
                        let code: String = err_table
                            .raw_get("code")
                            .map_err(|_| "missing or invalid code")?;
                        let detail: String = err_table
                            .raw_get("detail")
                            .map_err(|_| "missing or invalid detail")?;
                        if code.is_empty() || detail.is_empty() {
                            return Err("code and detail must be non-empty".to_string());
                        }
                    }
                }
                Ok(())
            }
            _ => Err("expected table or nil".to_string()),
        },
        "handle_input" => match value {
            Value::Table(t) => {
                let ok_val: Value = t.raw_get("ok").map_err(|e| e.to_string())?;
                let err_val: Value = t.raw_get("error").map_err(|e| e.to_string())?;
                if ok_val == Value::Boolean(true) && err_val == Value::Nil {
                    Ok(())
                } else if ok_val == Value::Nil && err_val != Value::Nil {
                    let err_table = match err_val {
                        Value::Table(et) => et,
                        _ => return Err("error field must be a table".to_string()),
                    };
                    let code: String = err_table
                        .raw_get("code")
                        .map_err(|_| "missing or invalid code")?;
                    let detail: String = err_table
                        .raw_get("detail")
                        .map_err(|_| "missing or invalid detail")?;
                    if code.is_empty() || detail.is_empty() {
                        return Err("code and detail must be non-empty".to_string());
                    }
                    Ok(())
                } else {
                    Err("expected ok=true or error table".to_string())
                }
            }
            _ => Err("expected table".to_string()),
        },
        "handle_readiness" => match value {
            Value::Table(t) => {
                let ready_val: Value = t.raw_get("ready").map_err(|e| e.to_string())?;
                match ready_val {
                    Value::Boolean(_) | Value::Nil => Ok(()),
                    _ => Err("expected ready field to be boolean".to_string()),
                }
            }
            _ => Err("expected table".to_string()),
        },
        _ => Ok(()),
    }
}
