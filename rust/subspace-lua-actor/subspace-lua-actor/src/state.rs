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
    chunk::ChunkMode, thread::ThreadStatus, AnyUserData, Error as LuaError, Function, HookTriggers,
    Lua, LuaOptions, MetaMethod, StdLib, Table, Thread, UserData, Value, VmState,
};
use serde_json::json;

use crate::accounting::{Accountant, MemoryReport};
use crate::hostop::{HostOperationEntry, HostOperationKind, HostOperationPayload};
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

/// Normalize host-facing semantic audio failures before they cross into Lua.
/// Unknown diagnostics intentionally collapse to a language-neutral code.
fn normalize_audio_error_code(value: &str) -> &'static str {
    match value {
        "E_INVALID_ARGUMENT" => "E_INVALID_ARGUMENT",
        "E_INVALID_VALUE" => "E_INVALID_VALUE",
        "E_INVALID_CONTEXT" => "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED" => "E_CAPABILITY_UNDECLARED",
        "E_UNAVAILABLE" => "E_UNAVAILABLE",
        "E_BUSY" => "E_BUSY",
        "E_TIMEOUT" => "E_TIMEOUT",
        "E_CANCELLED" => "E_CANCELLED",
        "E_CLOSED" => "E_CLOSED",
        "E_STALE" => "E_STALE",
        "E_HOST_FAILURE" => "E_HOST_FAILURE",
        _ => "E_HOST_FAILURE",
    }
}

fn normalize_audio_file_error_code(value: &str) -> &'static str {
    match value {
        "E_INVALID_ARGUMENT" => "E_INVALID_ARGUMENT",
        "E_INVALID_VALUE" => "E_INVALID_VALUE",
        "E_INVALID_PATH" => "E_INVALID_PATH",
        "E_INVALID_CONTEXT" => "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED" => "E_CAPABILITY_UNDECLARED",
        "E_MOUNT_UNAVAILABLE" => "E_MOUNT_UNAVAILABLE",
        "E_REAUTHORIZATION_REQUIRED" => "E_REAUTHORIZATION_REQUIRED",
        "E_READ_ONLY" => "E_READ_ONLY",
        "E_NOT_FOUND" => "E_NOT_FOUND",
        "E_EXISTS" => "E_EXISTS",
        "E_TOO_LARGE" => "E_TOO_LARGE",
        "E_NO_SPACE" => "E_NO_SPACE",
        "E_BUSY" => "E_BUSY",
        "E_TIMEOUT" => "E_TIMEOUT",
        "E_CANCELLED" => "E_CANCELLED",
        "E_CLOSED" => "E_CLOSED",
        "E_STALE" => "E_STALE",
        "E_UNSUPPORTED" => "E_UNSUPPORTED",
        "E_IO" => "E_IO",
        "E_HOST_FAILURE" => "E_HOST_FAILURE",
        _ => "E_HOST_FAILURE",
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
/// Callback-table contract: one required startup function and these optional functions.
/// Keeping the native allowlist centralized ensures validation and callback discovery
/// cannot drift apart; every other key is intentionally ignored.
const REQUIRED_CALLBACK: &str = "startup";
const OPTIONAL_CALLBACKS: [&str; 4] = [
    "handle_lifecycle",
    "handle_input",
    "handle_sos",
    "handle_readiness",
];
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
local host_defer = subspace.host_defer
local host_instance_id = subspace.host_instance_id
local host_acknowledge_spawn_context = subspace.host_acknowledge_spawn_context
local host_prepare_sleep = subspace.host_prepare_sleep
local host_audio_describe = subspace.host_audio_describe
local host_audio_file = subspace.host_audio_file
local host_log = subspace.host_log
local host_create_coroutine = subspace.host_create_coroutine

-- Keep Lua-level composition, but route every child through Rust so the
-- per-thread hook shares the state interrupt flag and instruction budget.
local native_coroutine_resume = coroutine.resume
coroutine.create = function(fn)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
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
  name = tostring(name)
  if name == "subspace" or string.sub(name, 1, 9) == "subspace." then
    error("E_RESERVED_MODULE")
  end
  subspace._modules[name] = value
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
-- Semantic audio operations validate synchronously, register a typed host
-- operation request in the native registry, and yield only its opaque
-- identity. No path, text, JSON, audio token, or delay crosses in the label.
local function transcribe(captured, ...)
  if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = subspace.host_transcribe(captured)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return { text = value }, nil end
  return nil, { error = value }
end
local function synthesize(params, ...)
  if select(string.char(35), ...) ~= 0 or type(params) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = subspace.host_synthesize(params)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
subspace.transcription = { transcribe = transcribe }
subspace.synthesis = { synthesize = synthesize }
local function schedule(audio, options, ...)
  if select(string.char(35), ...) ~= 0 or type(audio) ~= "userdata" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = subspace.host_playback(audio, options)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return { status = "scheduled" }, nil end
  return nil, { error = value }
end
subspace.playback = { schedule = schedule }

local function audio_file_yield(kind, args)
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = host_audio_file(kind, args)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
subspace.audio = {
  describe = function(recording, ...)
    if select(string.char(35), ...) ~= 0 or type(recording) ~= "userdata" then return nil, { error = "E_INVALID_ARGUMENT" } end
    if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
    local ok, value = host_audio_describe(recording)
    if not ok then return nil, { error = value } end
    return value, nil
  end,
  open = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(mount) ~= "userdata" or type(path) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for key in pairs(options) do if key ~= "format" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if options.format ~= "wav-pcm-s16le" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return audio_file_yield("open", { mount = mount, path = path, format = options.format })
  end,
  export = function(recording, mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(recording) ~= "userdata" or type(mount) ~= "userdata" or type(path) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for key in pairs(options) do if key ~= "format" and key ~= "mode" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if options.format ~= "wav-pcm-s16le" and options.format ~= "ogg-vorbis" then return nil, { error = "E_INVALID_ARGUMENT" } end
    local mode = options.mode or "create-new"
    if mode ~= "create-new" and mode ~= "replace" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return audio_file_yield("export", { recording = recording, mount = mount, path = path, format = options.format, mode = mode })
  end,
}

-- Filesystem operations. `mount` is a bounded synchronous lookup returning an
-- opaque generation-owned handle. Every I/O function validates, registers a
-- typed host-operation request, and yields only its opaque identity.
local function fs_yield(kind, mount, path, extra)
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  if type(mount) ~= "userdata" or type(path) ~= "string" then return nil, { error = "E_INVALID_ARGUMENT" } end
  local args = { mount = mount, path = path }
  if extra then for k, v in pairs(extra) do args[k] = v end end
  local ok, request_or_error = subspace.host_fs_io(kind, args)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
subspace.fs = {
  mount = function(id, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
    local ok, result = subspace.host_fs_mount(id)
    if not ok then return nil, { error = result } end
    return result, nil
  end,
  mkdir = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "parents" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.parents) ~= "boolean" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("mkdir", mount, path, { parents = options.parents })
  end,
  stat = function(mount, path, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("stat", mount, path, nil)
  end,
  list = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    local extra = {}
    if options ~= nil then
      if type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
      for k in pairs(options) do
        if k ~= "limit" and k ~= "cursor" then return nil, { error = "E_INVALID_ARGUMENT" } end
      end
      if options.limit ~= nil then extra.limit = options.limit end
      if options.cursor ~= nil then extra.cursor = options.cursor end
    end
    return fs_yield("list", mount, path, extra)
  end,
  read_text = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "max_bytes" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.max_bytes) ~= "number" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("read_text", mount, path, { max_bytes = options.max_bytes })
  end,
  write_text = function(mount, path, text, options, ...)
    if select(string.char(35), ...) ~= 0 or type(text) ~= "string" or type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    for k in pairs(options) do if k ~= "mode" then return nil, { error = "E_INVALID_ARGUMENT" } end end
    if type(options.mode) ~= "string" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return fs_yield("write_text", mount, path, { text = text, mode = options.mode })
  end,
  remove = function(mount, path, options, ...)
    if select(string.char(35), ...) ~= 0 then return nil, { error = "E_INVALID_ARGUMENT" } end
    local extra = {}
    if options ~= nil then
      if type(options) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
      for k in pairs(options) do if k ~= "missing_ok" then return nil, { error = "E_INVALID_ARGUMENT" } end end
      if options.missing_ok ~= nil then extra.missing_ok = options.missing_ok end
    end
    return fs_yield("remove", mount, path, extra)
  end,
}

-- Keyboard output operations validate synchronously, register a typed
-- host-operation request in the native registry, and yield only its opaque
-- identity. No text, profile, key, JSON, or transport data crosses in the
-- label; the host obtains the bounded payload only through exactly-once
-- typed claim.
local function keyboard_output_yield(kind, request)
  if subspace._evaluating and subspace._evaluating > 0 then error("effect-call-during-load") end
  local ok, request_or_error = subspace.host_keyboard_output(kind, request)
  if not ok then return nil, { error = request_or_error } end
  local success, value = subspace.yield_operation(request_or_error)
  if success then return value, nil end
  return nil, { error = value }
end
subspace.keyboard_output = {
  send_text = function(request, ...)
    if select(string.char(35), ...) ~= 0 or type(request) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return keyboard_output_yield("send_text", request)
  end,
  send_key = function(request, ...)
    if select(string.char(35), ...) ~= 0 or type(request) ~= "table" then return nil, { error = "E_INVALID_ARGUMENT" } end
    return keyboard_output_yield("send_key", request)
  end,
}

-- Preloaded host modules
subspace._preloaded = {
  ["subspace.runtime"] = subspace.runtime,
  ["subspace.channel"] = subspace.channel,
  ["subspace.log"] = subspace.log,
  ["subspace.transcription"] = subspace.transcription,
  ["subspace.synthesis"] = subspace.synthesis,
  ["subspace.playback"] = subspace.playback,
  ["subspace.fs"] = subspace.fs,
  ["subspace.audio"] = subspace.audio,
  ["subspace.keyboard_output"] = subspace.keyboard_output,
}

subspace.runtime.spawn = function(fn, ...)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(fn) ~= "function" then
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

subspace.runtime.defer = function(fn, ...)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(fn) ~= "function" then
    return nil, { error = "E_INVALID_ARGUMENT" }
  end
  local ok, res = host_defer(fn)
  if not ok then
    return nil, { error = res }
  end
  return true, nil
end

subspace.runtime.sleep = function(seconds, ...)
  if subspace._evaluating and subspace._evaluating > 0 then
    error("effect-call-during-load")
  end
  if select(string.char(35), ...) ~= 0 or type(seconds) ~= "number" or seconds ~= seconds or seconds == math.huge or seconds == -math.huge or seconds < 0 or seconds > 86400 then
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

setmetatable(subspace.runtime, {
  __index = function(_, key)
    if key == "INSTANCE_ID" then return host_instance_id() end
    return nil
  end,
  __newindex = function(table_value, key, value)
    if key == "INSTANCE_ID" then error("INSTANCE_ID is immutable", 2) end
    rawset(table_value, key, value)
  end,
  __metatable = false,
})

local function make_log_fn(level)
  return function(payload)
    if subspace._evaluating and subspace._evaluating > 0 then
      error("effect-call-during-load")
    end
    if type(payload) ~= "table" then
      return nil, { error = "E_INVALID_VALUE" }
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
  local runtime, channel, log, transcription, synthesis, playback, audio, fs, keyboard_output = {}, {}, {}, {}, {}, {}, {}, {}, {}
  for key, value in pairs(host.runtime) do runtime[key] = value end
  runtime.INSTANCE_ID = host.runtime.INSTANCE_ID
  for key, value in pairs(host.channel) do channel[key] = value end
  for key, value in pairs(host.log) do log[key] = value end
  for key, value in pairs(host.transcription) do transcription[key] = value end
  for key, value in pairs(host.synthesis) do synthesis[key] = value end
  for key, value in pairs(host.playback) do playback[key] = value end
  for key, value in pairs(host.audio) do audio[key] = value end
  for key, value in pairs(host.fs) do fs[key] = value end
  for key, value in pairs(host.keyboard_output) do keyboard_output[key] = value end
  local private = {
    _sources = sources,
    _modules = modules,
    _loading = {},
    _image_env = image_env,
    _operation_yield_prefix = host._operation_yield_prefix,
    runtime = readonly(runtime),
    channel = readonly(channel),
    log = readonly(log),
    transcription = readonly(transcription),
    synthesis = readonly(synthesis),
    playback = readonly(playback),
    audio = readonly(audio),
    fs = readonly(fs),
    keyboard_output = readonly(keyboard_output),
  }
  private.module_put = function(name, value)
    name = tostring(name)
    if name == "subspace" or string.sub(name, 1, 9) == "subspace." then
      error("E_RESERVED_MODULE")
    end
    modules[name] = value
  end
  private.module_get = function(name) return modules[tostring(name)] end
  private.module_clear = function(name) modules[tostring(name)] = nil end
  private.yield_operation = function(label)
    return coroutine.yield(private._operation_yield_prefix .. tostring(label))
  end
  private._preloaded = {
    ["subspace.runtime"] = private.runtime,
    ["subspace.channel"] = private.channel,
    ["subspace.log"] = private.log,
    ["subspace.transcription"] = private.transcription,
    ["subspace.synthesis"] = private.synthesis,
    ["subspace.playback"] = private.playback,
    ["subspace.audio"] = private.audio,
    ["subspace.fs"] = private.fs,
    ["subspace.keyboard_output"] = private.keyboard_output,
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
subspace.host_defer = nil
subspace.host_acknowledge_spawn_context = nil
subspace.host_audio_describe = nil
subspace.host_audio_file = nil
subspace.host_instance_id = nil
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
    /// True only for the terminal-bound `handle_input` invocation owner.
    input_callback: bool,
    /// True only for the bounded `handle_sos` invocation owner. The SOS
    /// owner may yield only explicitly authorized typed operations; sleep,
    /// spawn, defer, and raw yields remain rejected.
    sos_callback: bool,
    /// Whether the currently executing callback/coroutine may issue audio operations.
    audio_operation_eligible: bool,
    /// Whether the currently executing callback/coroutine may issue
    /// keyboard-output operations. Authorized owners are host-managed input,
    /// host-managed SOS, and runtime-managed task coroutines.
    keyboard_operation_eligible: bool,
    /// Opaque host-operation request id currently yielded by this coroutine.
    host_operation_id: Option<i64>,
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
    /// Typed host-operation request registry keyed by opaque request id.
    /// Payloads live only here; Lua observes only the opaque identity.
    host_operations: HashMap<i64, HostOperationEntry>,
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
    InvokeInputCallback {
        arguments_json: String,
        captured_audio_token: String,
    },
    InvokeSosCallback {
        arguments_json: String,
    },
    StartCoroutine {
        coroutine_id: i64,
    },
    ClaimHostOperation {
        request_id: i64,
    },
    SetResourceContext {
        resource_context_json: String,
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

/// Opaque authority for `runtime.defer`, bound to one input coroutine and its
/// currently executing Lua thread.
#[derive(Clone, Copy)]
struct DeferAuthority {
    thread_identity: usize,
    input_coroutine_id: i64,
}

/// Semantic kind of an audio value crossing the Lua boundary.
///
/// The token is deliberately only meaningful to the host-side registry. Lua
/// receives this as native full userdata, never as a scalar or table, so it
/// cannot manufacture a value that resolves in the registry.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum OpaqueAudioKind {
    Recording,
    Synthesized,
}

/// State-local opaque audio handle carried by Lua full userdata.
///
/// Keep this structure free of audio bytes, paths, routes, platform pointers,
/// and operation identities. Those artifacts remain in the host registry and
/// are bridged later using `(kind, token)` while validating state/generation/
/// execution ownership there.
#[derive(Eq, PartialEq)]
pub(crate) struct OpaqueAudioUserData {
    token: String,
    pub(crate) kind: OpaqueAudioKind,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct AudioMetadata {
    sample_rate: u32,
    channels: u8,
    duration_ms: u64,
    pcm_bytes: u64,
}

#[derive(Clone, Debug)]
struct AudioRegistryEntry {
    owner_thread: usize,
    metadata: AudioMetadata,
}

fn json_integral_u64(value: &serde_json::Value) -> Option<u64> {
    if let Some(integer) = value.as_u64() {
        return Some(integer);
    }
    let number = value.as_f64()?;
    if !number.is_finite() || number < 0.0 || number.fract() != 0.0 || number >= u64::MAX as f64 {
        return None;
    }
    Some(number as u64)
}

fn parse_audio_metadata(value: &serde_json::Value) -> Option<AudioMetadata> {
    let object = value.as_object()?;
    if object.len() != 4
        || !object.keys().all(|key| {
            matches!(
                key.as_str(),
                "sample_rate" | "channels" | "duration_ms" | "pcm_bytes"
            )
        })
    {
        return None;
    }
    let sample_rate = u32::try_from(json_integral_u64(object.get("sample_rate")?)?).ok()?;
    let channels = u8::try_from(json_integral_u64(object.get("channels")?)?).ok()?;
    let duration_ms = json_integral_u64(object.get("duration_ms")?)?;
    let pcm_bytes = json_integral_u64(object.get("pcm_bytes")?)?;
    if sample_rate == 0
        || sample_rate > 384_000
        || channels != 1
        || duration_ms > 86_400_000
        || pcm_bytes > 64 * 1024 * 1024
    {
        return None;
    }
    Some(AudioMetadata {
        sample_rate,
        channels,
        duration_ms,
        pcm_bytes,
    })
}

impl UserData for OpaqueAudioUserData {
    // No fields or regular methods are registered. mlua creates a protected
    // userdata metatable (`__metatable = false`) for UserData types. The only
    // metamethod is a constant representation: tostring must not leak the
    // token or expose an address that could be treated as a handle.
    fn add_methods<M: mlua::UserDataMethods<Self>>(methods: &mut M) {
        methods.add_meta_method(MetaMethod::ToString, |_, _, ()| Ok("opaque_audio"));
    }
}

/// Construct an opaque audio value for the current Lua state.
///
/// Registry admission and ownership checks intentionally live outside this
/// helper; callers must only invoke it after admission has succeeded.
pub(crate) fn create_opaque_audio_userdata(
    lua: &Lua,
    token: String,
    kind: OpaqueAudioKind,
) -> Result<AnyUserData, LuaError> {
    lua.create_userdata(OpaqueAudioUserData { token, kind })
}

/// Declared mount access mode from the package manifest `resources.mounts`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MountAccess {
    ReadWrite,
    ReadOnly,
}

/// Resolved live mount status supplied by the host binding store. Readiness
/// observes this; it is never an authorization grant. Every filesystem call
/// rechecks it through the registered mount lease.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MountStatus {
    Available,
    ReadOnly,
    NeedsReauthorization,
    Unavailable,
}

/// One declared mount plus its resolved live status.
#[derive(Clone, Debug)]
pub struct MountDeclaration {
    pub access: MountAccess,
    pub status: MountStatus,
}

/// Immutable per-state resource context installed by the host before dispatch.
/// Carries only declared capability eligibility and declared mount authority;
/// no platform grant blob, path, URI, or provider identity ever enters here.
#[derive(Clone, Debug, Default)]
pub struct ResourceContext {
    pub storage_files_declared: bool,
    pub audio_files_declared: bool,
    /// Declared `keyboard.output` public capability eligibility. Rechecked by
    /// the kernel before every keyboard-output admission; the authoritative
    /// declaration check lives in the host runtime.
    pub keyboard_output_declared: bool,
    pub instance_id: Option<String>,
    pub mounts: HashMap<String, MountDeclaration>,
}

impl ResourceContext {
    /// Parse the host-supplied resource context JSON:
    /// `{ "storageFiles": bool, "mounts": { id: { "access": "read-write"|"read-only",
    ///   "status": "available"|"read-only"|"needs-reauthorization"|"unavailable" } } }`.
    pub fn from_json(json_str: &str) -> Result<Self, Outcome> {
        let value: serde_json::Value = serde_json::from_str(json_str).map_err(|e| {
            Outcome::validation_failure(format!("invalid resource context JSON: {e}"))
        })?;
        let obj = value
            .as_object()
            .ok_or_else(|| Outcome::validation_failure("resource context must be an object"))?;
        let instance_id = match obj.get("instanceId") {
            None | Some(serde_json::Value::Null) => None,
            Some(serde_json::Value::String(value)) if !value.is_empty() && value.len() <= 256 => {
                Some(value.clone())
            }
            _ => {
                return Err(Outcome::validation_failure(
                    "resource context instanceId must be a nonempty bounded string",
                ))
            }
        };
        let audio_files_declared = obj
            .get("audioFiles")
            .and_then(|value| value.as_bool())
            .unwrap_or(false);
        let keyboard_output_declared = obj
            .get("keyboardOutput")
            .and_then(|value| value.as_bool())
            .unwrap_or(false);
        let storage_files_declared = obj
            .get("storageFiles")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let mut mounts = HashMap::new();
        if let Some(serde_json::Value::Object(mounts_obj)) = obj.get("mounts") {
            for (id, decl) in mounts_obj {
                if !is_valid_plugin_module_name(id) {
                    return Err(Outcome::validation_failure(format!(
                        "invalid mount declaration id: {id}"
                    )));
                }
                let decl_obj = decl.as_object().ok_or_else(|| {
                    Outcome::validation_failure("mount declaration must be an object")
                })?;
                let access = match decl_obj.get("access").and_then(|v| v.as_str()) {
                    Some("read-write") => MountAccess::ReadWrite,
                    Some("read-only") => MountAccess::ReadOnly,
                    _ => {
                        return Err(Outcome::validation_failure(
                            "mount access must be read-write or read-only",
                        ))
                    }
                };
                let status = match decl_obj.get("status").and_then(|v| v.as_str()) {
                    Some("available") => MountStatus::Available,
                    Some("read-only") => MountStatus::ReadOnly,
                    Some("needs-reauthorization") => MountStatus::NeedsReauthorization,
                    Some("unavailable") => MountStatus::Unavailable,
                    _ => {
                        return Err(Outcome::validation_failure(
                            "mount status must be available, read-only, needs-reauthorization, or unavailable",
                        ))
                    }
                };
                mounts.insert(id.clone(), MountDeclaration { access, status });
            }
        }
        Ok(ResourceContext {
            storage_files_declared,
            instance_id,
            audio_files_declared,
            keyboard_output_declared,
            mounts,
        })
    }
}

/// One live mount lease in the per-state registry. Created synchronously by
/// `fs.mount`, keyed by an unforgeable state-local token, and invalidated on
/// generation close or program-image reload.
struct MountEntry {
    /// Declared mount id; travels in the claim payload so the host dispatcher
    /// resolves the platform MountHandle for this declaration.
    declaration_id: String,
    access: MountAccess,
    generation: Generation,
}

/// State-local opaque mount handle carried by Lua full userdata.
///
/// Carries only a state-local random token, the declaration id, and the
/// generation that issued it. No platform grant, path, URI, document id, or
/// provider identity is stored here; those remain in the host binding store
/// and are resolved per-operation by the platform adapter.
#[derive(Eq, PartialEq)]
pub(crate) struct OpaqueMountUserData {
    token: String,
    declaration_id: String,
    generation: Generation,
}

impl UserData for OpaqueMountUserData {
    // No fields or regular methods are registered. mlua creates a protected
    // userdata metatable (`__metatable = false`). tostring returns a constant
    // that leaks neither the token nor an address usable as a handle.
    fn add_methods<M: mlua::UserDataMethods<Self>>(methods: &mut M) {
        methods.add_meta_method(MetaMethod::ToString, |_, _, ()| Ok("opaque_mount"));
    }
}

/// Construct an opaque mount handle for the current Lua state. Registry
/// admission and ownership checks live in the caller.
fn create_opaque_mount_userdata(
    lua: &Lua,
    token: String,
    declaration_id: String,
    generation: Generation,
) -> Result<AnyUserData, LuaError> {
    lua.create_userdata(OpaqueMountUserData {
        token,
        declaration_id,
        generation,
    })
}

// Filesystem path bounds. These are host policy numbers, not Lua compatibility
// promises; they keep every validated path finite before provider access.
const FS_MAX_PATH_BYTES: usize = 4096;
const FS_MAX_COMPONENTS: usize = 64;
const FS_MAX_COMPONENT_BYTES: usize = 255;
const FS_MAX_LIST_LIMIT: u32 = 1000;
const FS_DEFAULT_LIST_LIMIT: u32 = 100;
const FS_MAX_READ_BYTES: u64 = 16 * 1024 * 1024;
const FS_MAX_WRITE_BYTES: usize = 16 * 1024 * 1024;

/// Validate a canonical mount-relative logical path. Returns the validated
/// components joined by `/` (the canonical path) or a portable error code.
///
/// The empty string is the mount-root selector only when `allow_mount_root` is
/// true. Otherwise rejects empty paths, absolute/platform paths,
/// empty/`.`/`..` components, NUL, backslash, invalid UTF-8 (impossible for
/// `&str`), and over-bound component count, component bytes, and total bytes
/// before any mount resolution or provider access.
fn validate_fs_path(path: &str, allow_mount_root: bool) -> Result<String, &'static str> {
    if path.is_empty() {
        return if allow_mount_root {
            Ok(String::new())
        } else {
            Err("E_INVALID_PATH")
        };
    }
    if path.as_bytes().len() > FS_MAX_PATH_BYTES {
        return Err("E_INVALID_PATH");
    }
    if path.contains('\0') || path.contains('\\') {
        return Err("E_INVALID_PATH");
    }
    if path.starts_with('/') {
        return Err("E_INVALID_PATH");
    }
    let components: Vec<&str> = path.split('/').collect();
    if components.len() > FS_MAX_COMPONENTS {
        return Err("E_INVALID_PATH");
    }
    for component in &components {
        // Empty component catches trailing slashes and repeated separators.
        if component.is_empty() || *component == "." || *component == ".." {
            return Err("E_INVALID_PATH");
        }
        if component.as_bytes().len() > FS_MAX_COMPONENT_BYTES {
            return Err("E_INVALID_PATH");
        }
    }
    Ok(path.to_string())
}

/// Normalize host-facing filesystem failures before they cross into Lua.
/// Unknown diagnostics intentionally collapse to the portable `E_IO` code.
fn normalize_fs_error_code(value: &str) -> &'static str {
    match value {
        "E_INVALID_ARGUMENT" => "E_INVALID_ARGUMENT",
        "E_INVALID_PATH" => "E_INVALID_PATH",
        "E_INVALID_CONTEXT" => "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED" => "E_CAPABILITY_UNDECLARED",
        "E_MOUNT_UNAVAILABLE" => "E_MOUNT_UNAVAILABLE",
        "E_REAUTHORIZATION_REQUIRED" => "E_REAUTHORIZATION_REQUIRED",
        "E_READ_ONLY" => "E_READ_ONLY",
        "E_NOT_FOUND" => "E_NOT_FOUND",
        "E_EXISTS" => "E_EXISTS",
        "E_NOT_DIRECTORY" => "E_NOT_DIRECTORY",
        "E_IS_DIRECTORY" => "E_IS_DIRECTORY",
        "E_TOO_LARGE" => "E_TOO_LARGE",
        "E_NO_SPACE" => "E_NO_SPACE",
        "E_BUSY" => "E_BUSY",
        "E_TIMEOUT" => "E_TIMEOUT",
        "E_CANCELLED" => "E_CANCELLED",
        "E_CLOSED" => "E_CLOSED",
        "E_STALE" => "E_STALE",
        "E_UNSUPPORTED" => "E_UNSUPPORTED",
        "E_IO" => "E_IO",
        _ => "E_IO",
    }
}

// Keyboard-output request bounds. Host policy numbers, not Lua compatibility
// promises: every validated request is finite before queue admission,
// capability acquisition, keymap compilation, or physical output.
const KEYBOARD_MAX_TEXT_BYTES: usize = 16 * 1024;
const KEYBOARD_MAX_PROFILE_BYTES: usize = 256;

/// Validate an exact-key keyboard-output request table into a bounded typed
/// payload. Shape, type, UTF-8, and byte bounds are enforced before any
/// admission or effect; the semantic key vocabulary (`enter`/`escape`) is the
/// only accepted value domain.
fn validate_keyboard_request(
    kind: &str,
    table: &Table,
) -> Result<HostOperationPayload, &'static str> {
    let mut text: Option<String> = None;
    let mut key: Option<String> = None;
    let mut profile: Option<String> = None;
    let mut count = 0usize;
    for pair in table.clone().pairs::<Value, Value>() {
        let (field, field_value) = pair.map_err(|_| "E_INVALID_ARGUMENT")?;
        count += 1;
        if count > 2 {
            return Err("E_INVALID_ARGUMENT");
        }
        let field = match field {
            Value::String(field) => field
                .to_str()
                .map(|value| value.to_string())
                .map_err(|_| "E_INVALID_ARGUMENT")?,
            _ => return Err("E_INVALID_ARGUMENT"),
        };
        match field.as_str() {
            "text" if kind == "send_text" => {
                let value = match field_value {
                    Value::String(value) => value,
                    _ => return Err("E_INVALID_ARGUMENT"),
                };
                let value = value
                    .to_str()
                    .map(|value| value.to_string())
                    .map_err(|_| "E_INVALID_ARGUMENT")?;
                if value.is_empty() || value.len() > KEYBOARD_MAX_TEXT_BYTES {
                    return Err("E_INVALID_ARGUMENT");
                }
                text = Some(value);
            }
            "key" if kind == "send_key" => {
                let value = match field_value {
                    Value::String(value) => value,
                    _ => return Err("E_INVALID_ARGUMENT"),
                };
                let value = value
                    .to_str()
                    .map(|value| value.to_string())
                    .map_err(|_| "E_INVALID_ARGUMENT")?;
                if value != "enter" && value != "escape" {
                    return Err("E_INVALID_VALUE");
                }
                key = Some(value);
            }
            "profile" => {
                let value = match field_value {
                    Value::String(value) => value,
                    _ => return Err("E_INVALID_ARGUMENT"),
                };
                let value = value
                    .to_str()
                    .map(|value| value.to_string())
                    .map_err(|_| "E_INVALID_ARGUMENT")?;
                if value.trim().is_empty() || value.len() > KEYBOARD_MAX_PROFILE_BYTES {
                    return Err("E_INVALID_ARGUMENT");
                }
                profile = Some(value);
            }
            _ => return Err("E_INVALID_ARGUMENT"),
        }
    }
    match kind {
        "send_text" => match (text, profile) {
            (Some(text), Some(profile)) if count == 2 => {
                Ok(HostOperationPayload::KeyboardSendText { text, profile })
            }
            _ => Err("E_INVALID_ARGUMENT"),
        },
        "send_key" => match (key, profile) {
            (Some(key), Some(profile)) if count == 2 => {
                Ok(HostOperationPayload::KeyboardSendKey { key, profile })
            }
            _ => Err("E_INVALID_ARGUMENT"),
        },
        _ => Err("E_INVALID_ARGUMENT"),
    }
}

/// Normalize host-facing keyboard-output failures before they cross into
/// Lua. Unknown diagnostics intentionally collapse to a language-neutral
/// code so transport detail never reaches package source.
fn normalize_keyboard_error_code(value: &str) -> &'static str {
    match value {
        "E_INVALID_ARGUMENT" => "E_INVALID_ARGUMENT",
        "E_INVALID_VALUE" => "E_INVALID_VALUE",
        "E_INVALID_CONTEXT" => "E_INVALID_CONTEXT",
        "E_CAPABILITY_UNDECLARED" => "E_CAPABILITY_UNDECLARED",
        "E_BUSY" => "E_BUSY",
        "E_TIMEOUT" => "E_TIMEOUT",
        "E_CANCELLED" => "E_CANCELLED",
        "E_CLOSED" => "E_CLOSED",
        "E_STALE" => "E_STALE",
        "E_UNAVAILABLE" => "E_UNAVAILABLE",
        "E_UNSUPPORTED" => "E_UNSUPPORTED",
        "E_HOST_FAILURE" => "E_HOST_FAILURE",
        _ => "E_HOST_FAILURE",
    }
}

/// State exclusively owned by host callbacks while Lua is executing.  It is
/// deliberately disjoint from `EngineInner`: callbacks never borrow, alias, or
/// re-lock the Lua-owning engine.  Dispatch drains their bounded pending work
/// after Lua returns, while still holding the engine mutex.
struct CallbackState {
    dispatch_active: bool,
    evaluating_module: bool,
    /// Managed-execution host-effect eligibility. True only for `handle_input`
    /// and runtime-managed tasks; gates every audio AND filesystem host effect.
    /// Startup, readiness, lifecycle, SOS, and unmanaged coroutines are ineligible.
    audio_operation_eligible: bool,
    /// Keyboard-output host-effect eligibility. True only for host-managed
    /// input, host-managed SOS, and runtime-managed task owners; gates every
    /// `subspace.keyboard_output` operation. Startup, readiness, lifecycle,
    /// synchronous callbacks, and unmanaged coroutines are ineligible.
    keyboard_operation_eligible: bool,
    module_effect_attempted: bool,
    /// Spawned work admitted by `host_spawn`, including work awaiting dispatch.
    active_managed_tasks: usize,
    /// Sleep requests issued by the running callback but not yet yielded.
    pending_sleep_reservations: usize,
    invocation_violation: bool,
    next_coroutine_id: i64,
    latch_invalid_spawn: bool,
    spawn_authority: Option<SpawnAuthority>,
    defer_authority: Option<DeferAuthority>,
    unacknowledged_invalid_spawns: usize,
    spawn_admitter: Arc<dyn SpawnAdmitter>,
    /// Sleep/timer operations currently yielded to the host.
    active_sleep_timers: usize,
    pending_spawns: Vec<PendingSpawn>,
    /// Input-owned closures reserved by `runtime.defer` but not publishable
    /// until that exact input invocation returns terminal `{ ok = true }`.
    pending_defers: HashMap<i64, Vec<PendingSpawn>>,
    /// Monotonic opaque host-operation request id source for this state.
    next_host_operation_id: i64,
    /// Current state generation, synced from `EngineInner` at each dispatch.
    /// Mount leases stamp it so cross-generation handles fail live revalidation.
    generation: Generation,
    /// Typed payloads registered by host callbacks during the current Lua
    /// execution slice, keyed by opaque request id. The engine links each to
    /// its yielded operation and moves it into `EngineInner::host_operations`.
    pending_host_operations: HashMap<i64, HostOperationPayload>,
    /// Recording metadata indexed by opaque host token. Ownership is the exact
    /// Lua execution thread; userdata carries no metadata or owner identity.
    audio_registry: HashMap<String, AudioRegistryEntry>,
    /// Installed package resource context: declared `storage.files` capability
    /// eligibility and declared mount authority with resolved live status.
    resource_context: ResourceContext,
    /// Live generation-owned mount leases keyed by unforgeable state-local token.
    /// Cleared on generation close and program-image reload.
    mount_registry: HashMap<String, MountEntry>,
    /// Monotonic mount-token source for this state.
    next_mount_token: i64,
    logs: VecDeque<serde_json::Value>,
    log_bucket: f64,
    last_log_time: Instant,
}

impl CallbackState {
    fn new() -> Self {
        Self {
            dispatch_active: false,
            evaluating_module: false,
            audio_operation_eligible: false,
            keyboard_operation_eligible: false,
            module_effect_attempted: false,
            active_managed_tasks: 0,
            pending_sleep_reservations: 0,
            active_sleep_timers: 0,
            generation: 1,
            pending_spawns: Vec::new(),
            pending_defers: HashMap::new(),
            next_host_operation_id: 1,
            pending_host_operations: HashMap::new(),
            audio_registry: HashMap::new(),
            resource_context: ResourceContext::default(),
            mount_registry: HashMap::new(),
            next_mount_token: 1,
            invocation_violation: false,
            next_coroutine_id: 1,
            latch_invalid_spawn: false,
            spawn_authority: None,
            defer_authority: None,
            unacknowledged_invalid_spawns: 0,
            spawn_admitter: Arc::new(AcceptAllSpawnAdmitter),
            logs: VecDeque::new(),
            log_bucket: 100.0,
            last_log_time: Instant::now(),
        }
    }

    /// Revalidate a mount handle against the live registry and current
    /// generation. Returns the live lease or a portable error code. Foreign,
    /// stale, cross-generation, and post-close handles all fail here before
    /// any provider access.
    fn validate_mount(
        &self,
        token: &str,
        generation: Generation,
    ) -> Result<&MountEntry, &'static str> {
        match self.mount_registry.get(token) {
            Some(entry) if entry.generation == generation => Ok(entry),
            Some(_) => Err("E_STALE"),
            None => Err("E_STALE"),
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
        state.audio_operation_eligible = false;
        state.keyboard_operation_eligible = false;
        state.spawn_authority = None;
        state.defer_authority = None;
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
        let instance_state = Arc::clone(&callback_state);
        let host_instance_id_fn = lua
            .create_function(move |_, _: ()| {
                let state = instance_state.lock().unwrap_or_else(|e| e.into_inner());
                Ok(state.resource_context.instance_id.clone())
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_instance_id: {e}",))
            })?;
        let transcription_state = Arc::clone(&callback_state);
        let host_transcribe_fn = lua
            .create_function(move |_lua, captured: Value| {
                let mut state = transcription_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                if !state.audio_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                let userdata = match captured {
                    Value::UserData(value) => value,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let audio = match userdata.borrow::<OpaqueAudioUserData>() {
                    Ok(value) => value,
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                if audio.kind != OpaqueAudioKind::Recording {
                    return Ok((false, "E_INVALID_VALUE".to_string()));
                }
                let request_id = state.next_host_operation_id;
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(
                    request_id,
                    HostOperationPayload::Transcribe {
                        audio_token: audio.token.clone(),
                    },
                );
                Ok((true, request_id.to_string()))
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_transcribe: {e}"))
            })?;

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
        let synthesis_state = Arc::clone(&callback_state);
        let host_synthesize_fn = lua
            .create_function(move |_lua, params: Value| {
                let mut state = synthesis_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active || !state.audio_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let value = match normalize_lua_value(&params, 0) {
                    Ok(v) => v,
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let obj = match value.as_object() {
                    Some(v) => v,
                    None => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                if obj.len() < 3
                    || obj.len() > 4
                    || !obj
                        .keys()
                        .all(|k| matches!(k.as_str(), "text" | "language" | "voice" | "speed"))
                {
                    return Ok((false, "E_INVALID_ARGUMENT".to_string()));
                }
                let text = match obj.get("text").and_then(|v| v.as_str()) {
                    Some(v) if !v.trim().is_empty() && v.len() <= 16_384 => v,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let language = match obj.get("language").and_then(|v| v.as_str()) {
                    Some(v)
                        if !v.is_empty()
                            && v.len() <= 64
                            && v.split('-').all(|p| {
                                !p.is_empty()
                                    && p.len() <= 8
                                    && p.chars().all(|c| c.is_ascii_alphanumeric())
                            }) =>
                    {
                        v
                    }
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let voice = match obj.get("voice").and_then(|v| v.as_str()) {
                    Some(v) if !v.trim().is_empty() && v.len() <= 128 => v,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                if let Some(speed) = obj.get("speed") {
                    match speed.as_f64() {
                        Some(v) if v.is_finite() && v > 0.0 && v <= 4.0 => {}
                        _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                    }
                }
                let speed = obj.get("speed").and_then(|v| v.as_f64()).unwrap_or(1.0);
                let request_id = state.next_host_operation_id;
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(
                    request_id,
                    HostOperationPayload::Synthesize {
                        text: text.to_string(),
                        language: language.to_string(),
                        voice: voice.to_string(),
                        speed,
                    },
                );
                Ok((true, request_id.to_string()))
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_synthesize: {e}"))
            })?;
        let playback_state = Arc::clone(&callback_state);
        let host_playback_fn = lua
            .create_function(move |_lua, (audio_value, options): (Value, Value)| {
                let mut state = playback_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active || !state.audio_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string(), 0.0));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let userdata = match audio_value {
                    Value::UserData(value) => value,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string(), 0.0)),
                };
                let audio = match userdata.borrow::<OpaqueAudioUserData>() {
                    Ok(value) => value,
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string(), 0.0)),
                };
                let obj = match normalize_lua_value(&options, 0)
                    .ok()
                    .and_then(|v| v.as_object().cloned())
                {
                    Some(v) => v,
                    None => return Ok((false, "E_INVALID_ARGUMENT".to_string(), 0.0)),
                };
                if obj.keys().any(|k| k != "delay_seconds") {
                    return Ok((false, "E_INVALID_ARGUMENT".to_string(), 0.0));
                }
                let delay = match obj.get("delay_seconds") {
                    None => 0.0,
                    Some(v) => match v.as_f64() {
                        Some(v) if v.is_finite() && v >= 0.0 && v <= 86_400.0 => v,
                        _ => return Ok((false, "E_INVALID_VALUE".to_string(), 0.0)),
                    },
                };
                let request_id = state.next_host_operation_id;
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(
                    request_id,
                    HostOperationPayload::Playback {
                        audio_token: audio.token.clone(),
                        delay_seconds: delay,
                    },
                );
                Ok((true, request_id.to_string(), delay))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_playback: {e}")))?;
        let spawn_interrupt = Arc::clone(&interrupt_flag);
        let spawn_count = Arc::clone(&instruction_count);
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
        let defer_state = Arc::clone(&callback_state);
        let defer_interrupt = Arc::clone(&interrupt_flag);
        let defer_count = Arc::clone(&instruction_count);
        let host_defer_fn = lua
            .create_function(move |lua, func: Function| {
                let mut state = defer_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Err(LuaError::runtime("host callback outside dispatch"));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let current_thread = lua.current_thread();
                let authority = match state.defer_authority {
                    Some(authority)
                        if authority.thread_identity == current_thread.to_pointer() as usize =>
                    {
                        authority
                    }
                    _ => return Ok((false, "E_INVALID_CONTEXT".to_string())),
                };
                if state.active_managed_tasks >= max_concurrent_tasks {
                    return Ok((false, "E_BUSY".to_string()));
                }
                let thread = lua.create_thread(func)?;
                let coroutine_id = state.next_coroutine_id;
                state.next_coroutine_id += 1;
                let interrupt = Arc::clone(&defer_interrupt);
                let count = Arc::clone(&defer_count);
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
                state
                    .pending_defers
                    .entry(authority.input_coroutine_id)
                    .or_default()
                    .push(PendingSpawn {
                        coroutine_id,
                        thread,
                    });
                state.active_managed_tasks += 1;
                Ok((true, String::new()))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_defer: {e}")))?;
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
            .create_function(move |lua, seconds: f64| {
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
                // Sleep is authorized only by a host-managed spawned task.
                // Startup has spawn authority for task admission but runs on
                // the main thread (rejected by the Lua wrapper); input,
                // lifecycle, readiness, SOS, and plugin-created children have
                // no authority and must fail before reserving a timer slot.
                let current_thread = lua.current_thread();
                if state
                    .spawn_authority
                    .map(|authority| authority.thread_identity)
                    != Some(current_thread.to_pointer() as usize)
                {
                    return Ok((false, Some("E_INVALID_CONTEXT".to_string())));
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

        // Filesystem host functions. `host_fs_mount` is a bounded synchronous
        // lookup returning an opaque generation-owned Mount userdata. The I/O
        // functions validate arguments, mount lease, access mode, and logical
        // path, register a typed host-operation request, and return only its
        // opaque request id for yielding. No path, text, or provider argument
        // ever crosses in a yielded label.
        let fs_mount_state = Arc::clone(&callback_state);
        let host_fs_mount_fn = lua
            .create_function(move |lua, id: Value| {
                let mut state = fs_mount_state.lock().unwrap_or_else(|e| e.into_inner());
                let err = |code: &str| -> Result<(bool, Value), LuaError> {
                    Ok((false, Value::String(lua.create_string(code)?)))
                };
                if !state.dispatch_active {
                    return err("E_INVALID_CONTEXT");
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let id = match &id {
                    Value::String(s) => match s.to_str() {
                        Ok(s) => s.to_string(),
                        Err(_) => return err("E_INVALID_ARGUMENT"),
                    },
                    _ => return err("E_INVALID_ARGUMENT"),
                };
                if !is_valid_plugin_module_name(&id) {
                    return err("E_INVALID_ARGUMENT");
                }
                if !state.resource_context.storage_files_declared {
                    return err("E_CAPABILITY_UNDECLARED");
                }
                let decl = match state.resource_context.mounts.get(&id) {
                    Some(decl) => decl.clone(),
                    None => return err("E_INVALID_ARGUMENT"),
                };
                let access = match decl.status {
                    MountStatus::Available => decl.access,
                    MountStatus::ReadOnly => MountAccess::ReadOnly,
                    MountStatus::NeedsReauthorization => return err("E_REAUTHORIZATION_REQUIRED"),
                    MountStatus::Unavailable => return err("E_MOUNT_UNAVAILABLE"),
                };
                let token = format!("fs-mount-{}", state.next_mount_token);
                state.next_mount_token += 1;
                let generation = state.generation;
                state.mount_registry.insert(
                    token.clone(),
                    MountEntry {
                        declaration_id: id.clone(),
                        access,
                        generation,
                    },
                );
                drop(state);
                let userdata = create_opaque_mount_userdata(lua, token, id, generation)?;
                Ok((true, Value::UserData(userdata)))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_fs_mount: {e}")))?;

        let fs_io_state = Arc::clone(&callback_state);
        let host_fs_io_fn = lua
            .create_function(move |_lua, (kind, args): (String, Value)| {
                let mut state = fs_io_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active || !state.audio_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let args = match args {
                    Value::Table(t) => t,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                // Resolve and revalidate the mount handle.
                let mount_value: Value = match args.get("mount") {
                    Ok(v) => v,
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let mount_ud = match mount_value {
                    Value::UserData(ud) => ud,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let (token, generation) = match mount_ud.borrow::<OpaqueMountUserData>() {
                    Ok(m) => (m.token.clone(), m.generation),
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let entry = match state.validate_mount(&token, generation) {
                    Ok(entry) => entry,
                    Err(code) => return Ok((false, code.to_string())),
                };
                let access = entry.access;
                let declaration_id = entry.declaration_id.clone();
                // Validate the canonical logical path before any effect.
                let path_value: Value = match args.get("path") {
                    Ok(v) => v,
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let path = match &path_value {
                    Value::String(s) => match s.to_str() {
                        Ok(s) => s.to_string(),
                        Err(_) => return Ok((false, "E_INVALID_PATH".to_string())),
                    },
                    _ => return Ok((false, "E_INVALID_PATH".to_string())),
                };
                let path = match validate_fs_path(&path, kind == "list") {
                    Ok(p) => p,
                    Err(code) => return Ok((false, code.to_string())),
                };
                let request_id = state.next_host_operation_id;
                let payload = match kind.as_str() {
                    "mkdir" => {
                        if access != MountAccess::ReadWrite {
                            return Ok((false, "E_READ_ONLY".to_string()));
                        }
                        let parents = match args.get("parents") {
                            Ok(Value::Boolean(b)) => b,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        HostOperationPayload::FsMkdir {
                            declaration_id,
                            mount_token: token,
                            path,
                            parents,
                        }
                    }
                    "stat" => HostOperationPayload::FsStat {
                        declaration_id,
                        mount_token: token,
                        path,
                    },
                    "list" => {
                        let limit: u32 = match args.get("limit") {
                            Ok(Value::Integer(n)) if n > 0 && n <= FS_MAX_LIST_LIMIT as i64 => {
                                n as u32
                            }
                            Ok(Value::Nil) | Err(_) => FS_DEFAULT_LIST_LIMIT,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        let cursor: Option<String> = match args.get("cursor") {
                            Ok(Value::String(s)) => match s.to_str() {
                                Ok(s) => Some(s.to_string()),
                                Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                            },
                            Ok(Value::Nil) | Err(_) => None,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        HostOperationPayload::FsList {
                            declaration_id,
                            mount_token: token,
                            path,
                            limit,
                            cursor,
                        }
                    }
                    "read_text" => {
                        let max_bytes: u64 = match args.get("max_bytes") {
                            Ok(Value::Integer(n)) if n > 0 && n <= FS_MAX_READ_BYTES as i64 => {
                                n as u64
                            }
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        HostOperationPayload::FsReadText {
                            declaration_id,
                            mount_token: token,
                            path,
                            max_bytes,
                        }
                    }
                    "write_text" => {
                        if access != MountAccess::ReadWrite {
                            return Ok((false, "E_READ_ONLY".to_string()));
                        }
                        let text: String = match args.get("text") {
                            Ok(Value::String(s)) => match s.to_str() {
                                Ok(s) => s.to_string(),
                                Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                            },
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        if text.len() > FS_MAX_WRITE_BYTES {
                            return Ok((false, "E_TOO_LARGE".to_string()));
                        }
                        let mode: String = match args.get("mode") {
                            Ok(Value::String(s)) => match s.to_str() {
                                Ok(s) => s.to_string(),
                                Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                            },
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        if mode != "create-new" && mode != "replace" {
                            return Ok((false, "E_INVALID_ARGUMENT".to_string()));
                        }
                        HostOperationPayload::FsWriteText {
                            declaration_id,
                            mount_token: token,
                            path,
                            text,
                            mode,
                        }
                    }
                    "remove" => {
                        if access != MountAccess::ReadWrite {
                            return Ok((false, "E_READ_ONLY".to_string()));
                        }
                        let missing_ok = match args.get("missing_ok") {
                            Ok(Value::Boolean(b)) => b,
                            Ok(Value::Nil) | Err(_) => false,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        HostOperationPayload::FsRemove {
                            declaration_id,
                            mount_token: token,
                            path,
                            missing_ok,
                        }
                    }
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(request_id, payload);
                Ok((true, request_id.to_string()))
            })
            .map_err(|e| Outcome::runtime_failure(format!("failed to bind host_fs_io: {e}")))?;

        // Keyboard-output host function. Validates the exact request table
        // and all byte/value bounds, rechecks declared eligibility and owner
        // authorization, registers one typed host-operation request, and
        // returns only its opaque identity for yielding. No text, profile,
        // key, JSON, or transport data ever crosses in a yielded label.
        let keyboard_state = Arc::clone(&callback_state);
        let host_keyboard_output_fn = lua
            .create_function(move |_lua, (kind, request): (String, Value)| {
                let mut state = keyboard_state.lock().unwrap_or_else(|e| e.into_inner());
                if !state.dispatch_active {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                if !state.keyboard_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if !state.resource_context.keyboard_output_declared {
                    return Ok((false, "E_CAPABILITY_UNDECLARED".to_string()));
                }
                let table = match request {
                    Value::Table(table) => table,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                if table.metatable().is_some() {
                    return Ok((false, "E_INVALID_ARGUMENT".to_string()));
                }
                let payload = match validate_keyboard_request(&kind, &table) {
                    Ok(payload) => payload,
                    Err(code) => return Ok((false, code.to_string())),
                };
                let request_id = state.next_host_operation_id;
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(request_id, payload);
                Ok((true, request_id.to_string()))
            })
            .map_err(|e| {
                Outcome::runtime_failure(format!("failed to bind host_keyboard_output: {e}"))
            })?;

        let audio_describe_state = Arc::clone(&callback_state);
        let host_audio_describe_fn = lua
            .create_function(move |lua, value: Value| {
                let mut state = audio_describe_state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if !state.dispatch_active || !state.audio_operation_eligible {
                    return Ok((
                        false,
                        Value::String(lua.create_string("E_INVALID_CONTEXT")?),
                    ));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                let userdata = match value {
                    Value::UserData(userdata) => userdata,
                    _ => {
                        return Ok((
                            false,
                            Value::String(lua.create_string("E_INVALID_ARGUMENT")?),
                        ))
                    }
                };
                let audio = match userdata.borrow::<OpaqueAudioUserData>() {
                    Ok(audio) if audio.kind == OpaqueAudioKind::Recording => audio,
                    _ => {
                        return Ok((
                            false,
                            Value::String(lua.create_string("E_INVALID_ARGUMENT")?),
                        ))
                    }
                };
                let entry = match state.audio_registry.get(&audio.token) {
                    Some(entry)
                        if entry.owner_thread == lua.current_thread().to_pointer() as usize =>
                    {
                        entry
                    }
                    _ => {
                        return Ok((
                            false,
                            Value::String(lua.create_string("E_INVALID_ARGUMENT")?),
                        ))
                    }
                };
                let result = lua.create_table()?;
                result.set("sample_rate", entry.metadata.sample_rate)?;
                result.set("channels", entry.metadata.channels)?;
                result.set("duration_ms", entry.metadata.duration_ms)?;
                result.set("pcm_bytes", entry.metadata.pcm_bytes)?;
                Ok((true, Value::Table(result)))
            })
            .map_err(|error| {
                Outcome::runtime_failure(format!("failed to bind host_audio_describe: {error}",))
            })?;

        let audio_file_state = Arc::clone(&callback_state);
        let host_audio_file_fn = lua
            .create_function(move |lua, (kind, args): (String, Value)| {
                let mut state = audio_file_state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                if !state.dispatch_active || !state.audio_operation_eligible {
                    return Ok((false, "E_INVALID_CONTEXT".to_string()));
                }
                if state.evaluating_module {
                    state.module_effect_attempted = true;
                    return Err(LuaError::runtime("effect-call-during-load"));
                }
                if !state.resource_context.audio_files_declared
                    || !state.resource_context.storage_files_declared
                {
                    return Ok((false, "E_CAPABILITY_UNDECLARED".to_string()));
                }
                let args = match args {
                    Value::Table(table) => table,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let mount = match args.get::<Value>("mount") {
                    Ok(Value::UserData(userdata)) => userdata,
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let (mount_token, mount_generation) = match mount.borrow::<OpaqueMountUserData>() {
                    Ok(mount) => (mount.token.clone(), mount.generation),
                    Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let mount_entry = match state.validate_mount(&mount_token, mount_generation) {
                    Ok(entry) => entry,
                    Err(code) => return Ok((false, code.to_string())),
                };
                let declaration_id = mount_entry.declaration_id.clone();
                let access = mount_entry.access;
                let path = match args.get::<Value>("path") {
                    Ok(Value::String(path)) => match path.to_str() {
                        Ok(path) => match validate_fs_path(path.as_ref(), false) {
                            Ok(path) => path,
                            Err(code) => return Ok((false, code.to_string())),
                        },
                        Err(_) => return Ok((false, "E_INVALID_PATH".to_string())),
                    },
                    _ => return Ok((false, "E_INVALID_PATH".to_string())),
                };
                let format = match args.get::<Value>("format") {
                    Ok(Value::String(format)) => match format.to_str() {
                        Ok(format) => format.to_string(),
                        Err(_) => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                    },
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                let request_id = state.next_host_operation_id;
                let payload = match kind.as_str() {
                    "open" if format == "wav-pcm-s16le" => HostOperationPayload::AudioOpen {
                        declaration_id,
                        mount_token,
                        path,
                        format,
                    },
                    "export" if format == "wav-pcm-s16le" || format == "ogg-vorbis" => {
                        if access != MountAccess::ReadWrite {
                            return Ok((false, "E_READ_ONLY".to_string()));
                        }
                        let recording = match args.get::<Value>("recording") {
                            Ok(Value::UserData(userdata)) => userdata,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        let audio = match recording.borrow::<OpaqueAudioUserData>() {
                            Ok(audio) if audio.kind == OpaqueAudioKind::Recording => audio,
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        if !state.audio_registry.get(&audio.token).is_some_and(|entry| {
                            entry.owner_thread == lua.current_thread().to_pointer() as usize
                        }) {
                            return Ok((false, "E_INVALID_ARGUMENT".to_string()));
                        }
                        let mode = match args.get::<Value>("mode") {
                            Ok(Value::String(mode)) => match mode.to_str() {
                                Ok(mode) if mode == "create-new" => "create-new".to_string(),
                                Ok(mode) if mode == "replace" => "replace".to_string(),
                                _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                            },
                            _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                        };
                        HostOperationPayload::AudioExport {
                            audio_token: audio.token.clone(),
                            declaration_id,
                            mount_token,
                            path,
                            format,
                            mode,
                        }
                    }
                    _ => return Ok((false, "E_INVALID_ARGUMENT".to_string())),
                };
                state.next_host_operation_id += 1;
                state.pending_host_operations.insert(request_id, payload);
                Ok((true, request_id.to_string()))
            })
            .map_err(|error| {
                Outcome::runtime_failure(format!("failed to bind host_audio_file: {error}"))
            })?;

        // Bind all host functions on subspace
        {
            let globals = lua.globals();
            let subspace: Table = globals
                .get("subspace")
                .map_err(|e| Outcome::runtime_failure(format!("subspace global missing: {e}")))?;
            let _ = subspace.set("host_hash", host_hash_fn);
            let _ = subspace.set("host_transcribe", host_transcribe_fn);
            let _ = subspace.set("host_synthesize", host_synthesize_fn);
            let _ = subspace.set("host_playback", host_playback_fn);
            let _ = subspace.set("host_audio_describe", host_audio_describe_fn);
            let _ = subspace.set("host_audio_file", host_audio_file_fn);
            let _ = subspace.set("host_call", host_call_fn);
            let _ = subspace.set("host_spawn", host_spawn_fn);
            let _ = subspace.set("host_defer", host_defer_fn);
            let _ = subspace.set(
                "host_acknowledge_spawn_context",
                host_acknowledge_spawn_context_fn,
            );
            let _ = subspace.set("host_prepare_sleep", host_prepare_sleep_fn);
            let _ = subspace.set("host_log", host_log_fn);
            let _ = subspace.set("host_create_coroutine", host_create_coroutine_fn);
            let _ = subspace.set("host_fs_mount", host_fs_mount_fn);
            let _ = subspace.set("host_fs_io", host_fs_io_fn);
            let _ = subspace.set("host_keyboard_output", host_keyboard_output_fn);
            let _ = subspace.set("host_instance_id", host_instance_id_fn);
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
            host_operations: HashMap::new(),
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
            state.generation = self.generation;
            state.invocation_violation = false;
            state.latch_invalid_spawn = true;
            state.spawn_authority = None;
            state.defer_authority = None;
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
            Command::InvokeInputCallback {
                arguments_json,
                captured_audio_token,
            } => self.handle_invoke_input_callback(arguments_json, captured_audio_token),
            Command::InvokeSosCallback { arguments_json } => {
                self.handle_invoke_sos_callback(arguments_json)
            }
            Command::StartCoroutine { coroutine_id } => self.handle_start_coroutine(coroutine_id),
            Command::ClaimHostOperation { request_id } => {
                self.handle_claim_host_operation(request_id)
            }
            Command::SetResourceContext {
                resource_context_json,
            } => self.handle_set_resource_context(resource_context_json),
        };
        self.next_coroutine_id = self.next_coroutine_id.max(
            self.callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .next_coroutine_id,
        );
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
                    input_callback: false,
                    sos_callback: false,
                    audio_operation_eligible: true,
                    keyboard_operation_eligible: true,
                    host_operation_id: None,
                },
            );
            ids.push(coroutine_id);
        }
        ids
    }

    fn commit_deferred_spawns(&self, input_coroutine_id: i64) -> Result<(), &'static str> {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let pending = state
            .pending_defers
            .remove(&input_coroutine_id)
            .unwrap_or_default();
        let mut failure = None;
        for deferred in &pending {
            match state.spawn_admitter.admit(deferred.coroutine_id) {
                SpawnAdmission::Accepted => {}
                SpawnAdmission::Rejected => {
                    failure.get_or_insert("E_INVALID_CONTEXT");
                }
                SpawnAdmission::Closed | SpawnAdmission::Capacity => {
                    failure.get_or_insert("E_BUSY");
                }
            }
        }
        if let Some(code) = failure {
            state.active_managed_tasks = state.active_managed_tasks.saturating_sub(pending.len());
            return Err(code);
        }
        state.pending_spawns.extend(pending);
        Ok(())
    }

    fn discard_deferred_spawns(&self, input_coroutine_id: i64) {
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let count = state
            .pending_defers
            .remove(&input_coroutine_id)
            .map_or(0, |pending| pending.len());
        state.active_managed_tasks = state.active_managed_tasks.saturating_sub(count);
    }

    fn release_coroutine(&mut self, coroutine_id: i64) {
        let removed = self.coroutines.remove(&coroutine_id);
        // Drop any typed host-operation request the coroutine still owned so a
        // late claim observes a foreign request rather than a live one.
        if let Some(request_id) = removed.as_ref().and_then(|state| state.host_operation_id) {
            self.host_operations.remove(&request_id);
        }
        if let Some(owner_thread) = removed
            .as_ref()
            .map(|state| state.thread.to_pointer() as usize)
        {
            self.callback_state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .audio_registry
                .retain(|_, entry| entry.owner_thread != owner_thread);
        }
        let managed_task = removed.as_ref().is_some_and(|state| state.managed_task);
        let input_callback = removed.as_ref().is_some_and(|state| state.input_callback);
        if input_callback {
            self.discard_deferred_spawns(coroutine_id);
        }
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

        let source_bytes = source.len() as i64 + entrypoint.len() as i64;
        let previous_bridge_bytes = self.bridge_bytes;
        self.accountant.sub_bridge(previous_bridge_bytes);
        self.bridge_bytes = source_bytes;
        self.accountant.add_bridge(source_bytes);

        let lua = self.lua.as_ref().expect("lua live in handle_load");
        let subspace: Option<Table> = lua.globals().get("subspace").ok();
        let previous_modules: Option<Table> = subspace
            .as_ref()
            .and_then(|table| table.get("_modules").ok());
        let previous_entrypoint = self.entrypoint.clone();
        let previous_entrypoint_name = self.entrypoint_name.clone();
        let previous_lifecycle = self.lifecycle;
        macro_rules! rollback_load {
            ($outcome:expr) => {{
                if let (Some(table), Some(modules)) = (&subspace, &previous_modules) {
                    let _ = table.set("_modules", modules.clone());
                }
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                self.entrypoint = previous_entrypoint.clone();
                self.entrypoint_name = previous_entrypoint_name.clone();
                self.lifecycle = previous_lifecycle;
                return self.outcome_with_telemetry($outcome);
            }};
        }
        let staged_modules = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => {
                self.accountant.record_denial();
                rollback_load!(Outcome::memory_failure(format!(
                    "failed to stage module cache: {error}"
                )));
            }
            Err(error) => rollback_load!(Outcome::runtime_failure(format!(
                "failed to stage module cache: {error}"
            ))),
        };
        if let Some(table) = &subspace {
            if let Err(error) = table.set("_modules", staged_modules.clone()) {
                rollback_load!(Outcome::runtime_failure(format!(
                    "failed to stage module cache: {error}"
                )));
            }
        }

        // Entry chunks are module evaluation too: host calls must be rejected
        // before they can reserve an operation, timer, log, or task. The guard
        // is native-state owned so Lua cannot clear it or bypass it via a
        // copied namespace.
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.evaluating_module = true;
            state.module_effect_attempted = false;
        }
        let load_result = lua.load(&source).set_mode(ChunkMode::Text).exec();
        let module_effect_attempted = {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.evaluating_module = false;
            state.module_effect_attempted
        };
        if module_effect_attempted {
            rollback_load!(Outcome::runtime_failure("effect-call-during-load"));
        }
        if let Err(e) = load_result {
            if is_memory_error(&e) {
                self.accountant.record_denial();
                rollback_load!(Outcome::memory_failure(format!("{e}")));
            }
            rollback_load!(classify_load_error(&e));
        }

        let globals = lua.globals();
        let entry_fn: Result<Function, _> = globals.get(entrypoint.as_str());
        match entry_fn {
            Ok(f) => {
                self.entrypoint = Some(f);
                self.entrypoint_name = Some(entrypoint);
                self.lifecycle = Lifecycle::Loaded;
                self.main_coroutine_id = None;
                self.coroutines.clear();
                self.host_operations.clear();
                self.callback_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .mount_registry
                    .clear();
                self.terminal_operations.clear();
                self.terminal_operation_order.clear();
                self.evicted_terminal_operation_order.clear();
                let outcome =
                    Outcome::new(OutcomeKind::Completed).with("diagnostic", json!("loaded"));
                self.outcome_with_telemetry(outcome)
            }
            Err(_) => rollback_load!(Outcome::validation_failure(format!(
                "entrypoint '{entrypoint}' is not a defined global function"
            ))),
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
                input_callback: false,
                sos_callback: false,
                audio_operation_eligible: false,
                keyboard_operation_eligible: false,
                host_operation_id: None,
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
        let (
            thread,
            audio_operation_eligible,
            keyboard_operation_eligible,
            input_callback,
            sos_callback,
            host_operation_id,
        ) = {
            let state = self
                .coroutines
                .get_mut(&coroutine_id)
                .expect("admitted coroutine record disappeared");
            let host_operation_id = state.host_operation_id.take();
            state.operation = None;
            state.label = None;
            state.lifecycle = Lifecycle::Running;
            (
                state.thread.clone(),
                state.audio_operation_eligible,
                state.keyboard_operation_eligible,
                state.input_callback,
                state.sos_callback,
                host_operation_id,
            )
        };
        // The terminal completion consumes the typed request: capture its kind
        // for normalized completion handling and drop the registry entry so a
        // late claim observes a foreign request rather than re-entering Lua.
        let host_kind = host_operation_id
            .and_then(|request_id| self.host_operations.remove(&request_id))
            .map(|entry| entry.kind());
        self.lifecycle = Lifecycle::Running;
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.audio_operation_eligible = audio_operation_eligible;
            state.keyboard_operation_eligible = keyboard_operation_eligible;
            // The bounded SOS owner never receives spawn authority on any
            // slice: sleep, spawn, and defer remain synchronously rejected.
            state.spawn_authority = (!sos_callback).then_some(SpawnAuthority {
                thread_identity: thread.to_pointer() as usize,
            });
            state.defer_authority = input_callback.then_some(DeferAuthority {
                thread_identity: thread.to_pointer() as usize,
                input_coroutine_id: coroutine_id,
            });
        }
        let is_fs = host_kind.is_some_and(|kind| kind.is_fs());
        let is_audio_file = matches!(
            host_kind,
            Some(HostOperationKind::AudioOpen | HostOperationKind::AudioExport)
        );
        let is_keyboard = host_kind.is_some_and(|kind| kind.is_keyboard());
        let mut resume_success = success;
        let resume_value = if success && host_kind == Some(HostOperationKind::Synthesize) {
            match self.lua.as_ref().and_then(|lua| {
                create_opaque_audio_userdata(lua, value.clone(), OpaqueAudioKind::Synthesized).ok()
            }) {
                Some(userdata) => Value::UserData(userdata),
                None => {
                    resume_success = false;
                    Value::String(
                        self.lua
                            .as_ref()
                            .unwrap()
                            .create_string("E_HOST_FAILURE")
                            .unwrap(),
                    )
                }
            }
        } else if success && host_kind == Some(HostOperationKind::AudioOpen) {
            let lua = self.lua.as_ref().unwrap();
            let opened = serde_json::from_str::<serde_json::Value>(&value)
                .ok()
                .and_then(|value| {
                    let object = value.as_object()?;
                    let token = object.get("token")?.as_str()?.to_string();
                    let metadata = parse_audio_metadata(object.get("metadata")?)?;
                    Some((token, metadata))
                })
                .and_then(|(token, metadata)| {
                    let userdata = create_opaque_audio_userdata(
                        lua,
                        token.clone(),
                        OpaqueAudioKind::Recording,
                    )
                    .ok()?;
                    self.callback_state
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .audio_registry
                        .insert(
                            token,
                            AudioRegistryEntry {
                                owner_thread: thread.to_pointer() as usize,
                                metadata,
                            },
                        );
                    Some(userdata)
                });
            match opened {
                Some(userdata) => Value::UserData(userdata),
                None => {
                    resume_success = false;
                    Value::String(lua.create_string("E_HOST_FAILURE").unwrap())
                }
            }
        } else if success && (is_fs || is_keyboard || host_kind == Some(HostOperationKind::AudioExport)) {
            let lua = self.lua.as_ref().unwrap();
            match serde_json::from_str::<serde_json::Value>(&value)
                .ok()
                .and_then(|json_value| json_to_lua(lua, &json_value).ok())
            {
                Some(table) => table,
                None => {
                    resume_success = false;
                    Value::String(
                        lua.create_string(if is_fs { "E_IO" } else { "E_HOST_FAILURE" })
                            .unwrap(),
                    )
                }
            }
        } else if !success && host_kind.is_some() {
            let code = if is_fs {
                normalize_fs_error_code(&value)
            } else if is_audio_file {
                normalize_audio_file_error_code(&value)
            } else if is_keyboard {
                normalize_keyboard_error_code(&value)
            } else {
                normalize_audio_error_code(&value)
            };
            Value::String(self.lua.as_ref().unwrap().create_string(code).unwrap())
        } else {
            Value::String(self.lua.as_ref().unwrap().create_string(&value).unwrap())
        };
        let result = thread.resume::<Value>((resume_success, resume_value));
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.spawn_authority = None;
            state.defer_authority = None;
        }
        let outcome = self.classify_resume_result(result, &thread, coroutine_id);
        self.record_terminal_operation(coroutine_id, operation_id, outcome.clone());
        self.outcome_with_telemetry(outcome)
    }

    /// Claim one yielded host-operation request exactly once.
    ///
    /// Generation and closed-state validation already ran in [`Self::process`].
    /// Here the request must exist (else foreign/unknown), be unclaimed (else
    /// duplicate), and still be owned by a coroutine suspended on its linked
    /// operation (else stale/cancelled). A successful claim marks the entry
    /// claimed and returns the typed kind and payload; the host then performs
    /// the work and completes through the normal operation terminal gate.
    fn handle_claim_host_operation(&mut self, request_id: i64) -> Outcome {
        // Phase 1: validate and snapshot the request under immutable borrows
        // only, so no borrow of `self` spans the telemetry calls in phase 2.
        enum Claim {
            Unknown,
            Duplicate,
            Stale,
            Admit {
                coroutine_id: i64,
                operation_id: OperationId,
                kind: HostOperationKind,
                payload: HostOperationPayload,
            },
        }
        let claim = match self.host_operations.get(&request_id) {
            None => Claim::Unknown,
            Some(entry) if entry.claimed => Claim::Duplicate,
            Some(entry) => {
                let coroutine_id = entry.coroutine_id;
                let operation_id = entry.operation_id;
                let live = self.coroutines.get(&coroutine_id).is_some_and(|state| {
                    state.lifecycle == Lifecycle::Yielded
                        && state.operation == Some(operation_id)
                        && state.host_operation_id == Some(request_id)
                });
                if live {
                    Claim::Admit {
                        coroutine_id,
                        operation_id,
                        kind: entry.kind(),
                        payload: entry.payload.clone(),
                    }
                } else {
                    Claim::Stale
                }
            }
        };
        // Phase 2: the immutable borrow from phase 1 has ended, so telemetry and
        // the exactly-once claim mark may re-borrow `self` without conflict.
        match claim {
            Claim::Unknown => self.outcome_with_telemetry(Outcome::invalid_ownership(
                "unknown host operation request",
            )),
            Claim::Duplicate => self.outcome_with_telemetry(Outcome::invalid_ownership(
                "duplicate host operation claim",
            )),
            Claim::Stale => {
                self.outcome_with_telemetry(Outcome::stale(self.state_id, self.generation))
            }
            Claim::Admit {
                coroutine_id,
                operation_id,
                kind,
                payload,
            } => {
                if let Some(entry) = self.host_operations.get_mut(&request_id) {
                    entry.claimed = true;
                }
                let mut outcome = Outcome::new(OutcomeKind::Completed)
                    .with("operation", json!("claimHostOperation"))
                    .with("requestId", json!(request_id))
                    .with("coroutineId", json!(coroutine_id))
                    .with("operationId", json!(operation_id))
                    .with("hostOperationKind", json!(kind.as_str()));
                outcome = payload.to_claim_fields(outcome);
                self.outcome_with_telemetry(outcome)
            }
        }
    }

    /// Install the package resource context: declared `storage.files`
    /// capability eligibility and declared mount authority with resolved live
    /// status. Callable at any time before dispatch; replacing it invalidates
    /// outstanding mount leases so stale handles fail live revalidation.
    fn handle_set_resource_context(&mut self, resource_context_json: String) -> Outcome {
        let resource_context = match ResourceContext::from_json(&resource_context_json) {
            Ok(context) => context,
            Err(outcome) => return self.outcome_with_telemetry(outcome),
        };
        let mut state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state.resource_context = resource_context;
        state.mount_registry.clear();
        drop(state);
        self.outcome_with_telemetry(
            Outcome::new(OutcomeKind::Completed)
                .with("diagnostic", json!("resource context installed")),
        )
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
        self.host_operations.clear();
        self.accountant.sub_bridge(self.bridge_bytes);
        self.bridge_bytes = 0;
        let mut callback_state = self
            .callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        callback_state.pending_spawns.clear();
        callback_state.pending_defers.clear();
        callback_state.pending_host_operations.clear();
        callback_state.audio_registry.clear();
        callback_state.active_managed_tasks = 0;
        callback_state.pending_sleep_reservations = 0;
        callback_state.active_sleep_timers = 0;
        callback_state.logs.clear();
        callback_state.evaluating_module = false;
        callback_state.module_effect_attempted = false;
        callback_state.mount_registry.clear();
        callback_state.generation = self.generation;

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
                // Link a registered host-operation request when the opaque
                // label is its identity; otherwise this is an ordinary opaque
                // yield the host interprets. The generic `yield_operation`
                // contract seam is preserved — forged or unknown request
                // identities are rejected at claim time, not at yield time.
                let linked = if label.starts_with("sleep:") {
                    None
                } else {
                    match label.parse::<i64>().ok() {
                        Some(request_id) => self
                            .callback_state
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .pending_host_operations
                            .remove(&request_id)
                            .map(|payload| (request_id, payload)),
                        None => None,
                    }
                };
                let host_request_id = match linked {
                    Some((request_id, payload)) => {
                        self.host_operations.insert(
                            request_id,
                            HostOperationEntry {
                                payload,
                                coroutine_id,
                                operation_id,
                                claimed: false,
                            },
                        );
                        Some(request_id)
                    }
                    None => None,
                };
                let state = self
                    .coroutines
                    .get_mut(&coroutine_id)
                    .expect("resumed coroutine record disappeared");
                state.lifecycle = Lifecycle::Yielded;
                state.operation = Some(operation_id);
                state.label = Some(label.clone());
                state.host_operation_id = host_request_id;
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
                // The bounded SOS owner enforces the exact terminal result
                // shape on every slice, like the synchronous callback path.
                if self
                    .coroutines
                    .get(&coroutine_id)
                    .is_some_and(|state| state.sos_callback)
                {
                    if let Err(err_msg) = validate_callback_return("handle_sos", &value) {
                        self.release_coroutine(coroutine_id);
                        self.lifecycle = Lifecycle::Failed;
                        return Outcome::runtime_failure(format!(
                            "callback contract violation: {err_msg}"
                        ));
                    }
                }
                let normalized = match normalize_lua_value(&value, 0) {
                    Ok(value) => value,
                    Err(error) => {
                        self.release_coroutine(coroutine_id);
                        self.lifecycle = Lifecycle::Failed;
                        return Outcome::runtime_failure(format!("E_INVALID_VALUE: {error}"));
                    }
                };
                let input_success = self
                    .coroutines
                    .get(&coroutine_id)
                    .is_some_and(|state| state.input_callback)
                    && normalized.as_object().is_some_and(|object| {
                        object.len() == 1
                            && object.get("ok") == Some(&serde_json::Value::Bool(true))
                    });
                if input_success {
                    if let Err(code) = self.commit_deferred_spawns(coroutine_id) {
                        self.release_coroutine(coroutine_id);
                        self.lifecycle = Lifecycle::Failed;
                        return Outcome::runtime_failure(code);
                    }
                }
                self.release_coroutine(coroutine_id);
                self.lifecycle = Lifecycle::Completed;
                Outcome::new(OutcomeKind::Completed)
                    .with("coroutineId", json!(coroutine_id))
                    .with("value", normalized)
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
        let previous_lifecycle = self.lifecycle;
        let previous_entrypoint_name = self.entrypoint_name.clone();
        let previous_main_coroutine_id = self.main_coroutine_id;
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
                self.entrypoint_name = previous_entrypoint_name.clone();
                self.main_coroutine_id = previous_main_coroutine_id;
                self.accountant.sub_bridge(source_bytes);
                self.accountant.add_bridge(previous_bridge_bytes);
                self.bridge_bytes = previous_bridge_bytes;
                self.lifecycle = previous_lifecycle;
                return self.outcome_with_telemetry($outcome);
            }};
        }
        macro_rules! rollback_image_memory_failure {
            ($denied_before:expr, $context:literal, $error:expr) => {{
                if self
                    .accountant
                    .report(lua.used_memory() as u64)
                    .denied_allocations
                    == $denied_before
                {
                    self.accountant.record_denial();
                }
                rollback_image!(Outcome::memory_failure(format!("{}: {}", $context, $error)))
            }};
        }
        let denied_before = self
            .accountant
            .report(lua.used_memory() as u64)
            .denied_allocations;
        let sources = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => {
                rollback_image_memory_failure!(denied_before, "failed to stage sources", error)
            }
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to stage sources: {error}"
            ))),
        };
        let denied_before = self
            .accountant
            .report(lua.used_memory() as u64)
            .denied_allocations;
        let modules = match lua.create_table() {
            Ok(table) => table,
            Err(error) if is_memory_error(&error) => {
                rollback_image_memory_failure!(denied_before, "failed to stage module cache", error)
            }
            Err(error) => rollback_image!(Outcome::runtime_failure(format!(
                "failed to stage module cache: {error}"
            ))),
        };
        let denied_before = self
            .accountant
            .report(lua.used_memory() as u64)
            .denied_allocations;
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
        let denied_before = self
            .accountant
            .report(lua.used_memory() as u64)
            .denied_allocations;
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
        let startup: Value = match table.raw_get(REQUIRED_CALLBACK) {
            Ok(v) => v,
            Err(e) => rollback_image!(Outcome::runtime_failure(e.to_string())),
        };
        match startup {
            Value::Function(_) => {}
            Value::Nil => rollback_image!(Outcome::validation_failure(format!(
                "required callback '{}' is missing",
                REQUIRED_CALLBACK
            ))),
            _ => rollback_image!(Outcome::validation_failure(format!(
                "expected function for callback '{}', got {}",
                REQUIRED_CALLBACK,
                startup.type_name()
            ))),
        }
        let mut callbacks_list = vec![REQUIRED_CALLBACK.to_string()];
        for key in OPTIONAL_CALLBACKS {
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
        self.host_operations.clear();
        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .mount_registry
            .clear();

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

    /// Invoke handle_input inside its own Lua coroutine. This is separate from
    /// synchronous callbacks so semantic host operations may yield and retain
    /// their exact coroutine/operation owner for later resumption.
    fn handle_invoke_input_callback(
        &mut self,
        arguments_json: String,
        captured_audio_token: String,
    ) -> Outcome {
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
        let event_object = match &args_val {
            serde_json::Value::Object(object) => object,
            _ => {
                return self.outcome_with_telemetry(Outcome::validation_failure(
                    "input event must be a table",
                ))
            }
        };
        let captured_metadata = match event_object.get("metadata").and_then(parse_audio_metadata) {
            Some(metadata) => metadata,
            None => {
                return self.outcome_with_telemetry(Outcome::validation_failure(
                    "input event metadata is invalid",
                ))
            }
        };
        if event_object.contains_key("audio") {
            return self.outcome_with_telemetry(Outcome::validation_failure(
                "input event audio field is reserved",
            ));
        }
        let lua_event = match json_to_lua(lua, &args_val) {
            Ok(Value::Table(table)) => table,
            Ok(_) => {
                return self.outcome_with_telemetry(Outcome::validation_failure(
                    "input event must be a table",
                ))
            }
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::validation_failure(format!(
                    "failed to convert arguments to Lua: {e}"
                )))
            }
        };
        let callbacks: Table = match lua
            .globals()
            .get::<Table>("subspace")
            .and_then(|subspace: Table| subspace.get("_callbacks"))
        {
            Ok(table) => table,
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "_callbacks missing: {e}"
                )))
            }
        };
        let callback_fn: Function = match callbacks.raw_get("handle_input") {
            Ok(Value::Function(function)) => function,
            _ => {
                return self.outcome_with_telemetry(Outcome::validation_failure(
                    "callback 'handle_input' not found",
                ))
            }
        };
        let thread = match lua.create_thread(callback_fn) {
            Ok(thread) => thread,
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "failed to create input coroutine: {e}"
                )))
            }
        };
        self.reset_execution_budget();
        self.setup_hook(&thread);
        self.lifecycle = Lifecycle::Running;
        let coroutine_id = self.next_coroutine_id;
        self.next_coroutine_id += 1;
        self.coroutines.insert(
            coroutine_id,
            CoroutineState {
                thread: thread.clone(),
                lifecycle: Lifecycle::Running,
                operation: None,
                label: None,
                managed_task: false,
                input_callback: true,
                sos_callback: false,
                audio_operation_eligible: true,
                keyboard_operation_eligible: true,
                host_operation_id: None,
            },
        );
        let userdata = match create_opaque_audio_userdata(
            lua,
            captured_audio_token.clone(),
            OpaqueAudioKind::Recording,
        ) {
            Ok(userdata) => userdata,
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "failed to create captured audio userdata: {e}"
                )))
            }
        };
        // Input callbacks never receive spawn authority; `runtime.spawn`
        // remains synchronously denied. `runtime.defer` receives a distinct
        // terminal-bound authority tied to this exact coroutine.
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.audio_operation_eligible = true;
            state.keyboard_operation_eligible = true;
            state.audio_registry.insert(
                captured_audio_token,
                AudioRegistryEntry {
                    owner_thread: thread.to_pointer() as usize,
                    metadata: captured_metadata,
                },
            );
            state.defer_authority = Some(DeferAuthority {
                thread_identity: thread.to_pointer() as usize,
                input_coroutine_id: coroutine_id,
            });
        }
        if let Err(e) = lua_event.set("audio", userdata) {
            return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                "failed to attach captured audio userdata: {e}"
            )));
        }
        let result = thread.resume::<Value>(lua_event);
        self.callback_state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .defer_authority = None;
        let outcome = self.classify_resume_result(result, &thread, coroutine_id);
        self.outcome_with_telemetry(outcome)
    }

    /// Invoke handle_sos inside a bounded host-managed coroutine. The SOS
    /// owner may yield only explicitly authorized typed operations (initially
    /// keyboard output); sleep, spawn, defer, and raw yields remain rejected.
    /// A handle_sos that never yields completes in this one slice exactly
    /// like the synchronous callback path, with the same terminal result
    /// shape validation.
    fn handle_invoke_sos_callback(&mut self, arguments_json: String) -> Outcome {
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
                return self.outcome_with_telemetry(Outcome::validation_failure(format!(
                    "failed to convert arguments to Lua: {e}"
                )))
            }
        };
        let callbacks: Table = match lua
            .globals()
            .get::<Table>("subspace")
            .and_then(|subspace: Table| subspace.get("_callbacks"))
        {
            Ok(table) => table,
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "_callbacks missing: {e}"
                )))
            }
        };
        let callback_fn: Function = match callbacks.raw_get("handle_sos") {
            Ok(Value::Function(function)) => function,
            _ => {
                return self.outcome_with_telemetry(Outcome::validation_failure(
                    "callback 'handle_sos' not found",
                ))
            }
        };
        let thread = match lua.create_thread(callback_fn) {
            Ok(thread) => thread,
            Err(e) => {
                return self.outcome_with_telemetry(Outcome::runtime_failure(format!(
                    "failed to create SOS coroutine: {e}"
                )))
            }
        };
        self.reset_execution_budget();
        self.setup_hook(&thread);
        self.lifecycle = Lifecycle::Running;
        let coroutine_id = self.next_coroutine_id;
        self.next_coroutine_id += 1;
        self.coroutines.insert(
            coroutine_id,
            CoroutineState {
                thread: thread.clone(),
                lifecycle: Lifecycle::Running,
                operation: None,
                label: None,
                managed_task: false,
                input_callback: false,
                sos_callback: true,
                audio_operation_eligible: false,
                keyboard_operation_eligible: true,
                host_operation_id: None,
            },
        );
        {
            let mut state = self
                .callback_state
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            state.keyboard_operation_eligible = true;
            // Deliberately no spawn authority, no defer authority, and no
            // audio eligibility: the SOS owner may yield only authorized
            // typed keyboard operations.
        }
        let result = thread.resume::<Value>(lua_args);
        let outcome = self.classify_resume_result(result, &thread, coroutine_id);
        self.outcome_with_telemetry(outcome)
    }
    fn handle_start_coroutine(&mut self, coroutine_id: i64) -> Outcome {
        let (thread, audio_operation_eligible, keyboard_operation_eligible) =
            match self.coroutines.get_mut(&coroutine_id) {
                Some(state) if state.lifecycle == Lifecycle::Loaded => {
                    state.lifecycle = Lifecycle::Running;
                    (
                        state.thread.clone(),
                        state.audio_operation_eligible,
                        state.keyboard_operation_eligible,
                    )
                }
                Some(_) => {
                    return self.outcome_with_telemetry(Outcome::invalid_ownership(
                        "coroutine is not ready to start",
                    ))
                }
                None => {
                    return self.outcome_with_telemetry(Outcome::invalid_ownership(
                        "coroutine id not found",
                    ))
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
            state.audio_operation_eligible = audio_operation_eligible;
            state.keyboard_operation_eligible = keyboard_operation_eligible;
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

    /// Invoke handle_input in a dedicated host-managed coroutine. The caller
    /// retains the yielded coroutine/operation pair as its execution owner.
    pub fn invoke_input_callback_with_spawn_admitter(
        &self,
        generation: Generation,
        arguments_json: &str,
        captured_audio_token: &str,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(
            generation,
            Command::InvokeInputCallback {
                arguments_json: arguments_json.to_string(),
                captured_audio_token: captured_audio_token.to_string(),
            },
            admitter,
        )
    }

    /// Invoke handle_sos in a dedicated bounded host-managed coroutine. The
    /// SOS owner may yield only explicitly authorized typed operations
    /// (initially keyboard output); the caller retains the yielded
    /// coroutine/operation pair as its execution owner and completes it
    /// through the same claim/resume path. A handle_sos that never yields
    /// completes in one slice like the synchronous callback.
    pub fn invoke_sos_callback_with_spawn_admitter(
        &self,
        generation: Generation,
        arguments_json: &str,
        admitter: Arc<dyn SpawnAdmitter>,
    ) -> Outcome {
        self.dispatch_with_spawn_admitter(
            generation,
            Command::InvokeSosCallback {
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

    /// Claim one yielded host-operation request exactly once, returning its
    /// typed kind and payload. Duplicate, foreign, stale, cancelled, and closed
    /// claims are rejected before any host effect.
    pub fn claim_host_operation(&self, generation: Generation, request_id: i64) -> Outcome {
        self.dispatch(generation, Command::ClaimHostOperation { request_id })
    }

    /// Install the package resource context (declared `storage.files`
    /// capability and declared mount authority with resolved live status).
    /// Must be called before any filesystem operation. Replacing the context
    /// invalidates all outstanding mount leases.
    pub fn set_resource_context(
        &self,
        generation: Generation,
        resource_context_json: &str,
    ) -> Outcome {
        self.dispatch(
            generation,
            Command::SetResourceContext {
                resource_context_json: resource_context_json.to_string(),
            },
        )
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
        Value::UserData(userdata)
            if userdata.is::<OpaqueAudioUserData>() || userdata.is::<OpaqueMountUserData>() =>
        {
            Err("E_INVALID_VALUE: opaque userdata is not serializable".to_string())
        }
        _ => Err("disallowed type".to_string()),
    }
}

/// Detect opaque host handles (audio or mount) before callback contract
/// validation. Structured error validation must reject such a value before
/// inspecting or retaining any other fields; the semantic audio and filesystem
/// APIs are the only allowed direct boundaries for these handles.
fn contains_opaque_userdata(
    value: &Value,
    depth: usize,
    tables: &mut std::collections::HashSet<usize>,
) -> Result<bool, String> {
    if depth > 10 {
        return Ok(false);
    }
    match value {
        Value::UserData(userdata) => {
            Ok(userdata.is::<OpaqueAudioUserData>() || userdata.is::<OpaqueMountUserData>())
        }
        Value::Table(table) => {
            if !tables.insert(table.to_pointer() as usize) {
                return Ok(false);
            }
            for pair in table.clone().pairs::<Value, Value>() {
                let (_, child) = pair.map_err(|error| error.to_string())?;
                if contains_opaque_userdata(&child, depth + 1, tables)? {
                    return Ok(true);
                }
            }
            Ok(false)
        }
        _ => Ok(false),
    }
}

fn callback_contains_opaque_userdata(value: &Value) -> Result<bool, String> {
    contains_opaque_userdata(value, 0, &mut std::collections::HashSet::new())
}

fn json_to_lua(lua: &Lua, value: &serde_json::Value) -> Result<Value, mlua::Error> {
    match value {
        serde_json::Value::Null => Ok(Value::Nil),
        serde_json::Value::Bool(b) => Ok(Value::Boolean(*b)),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(Value::Integer(i))
            } else if let Some(f) = n.as_f64() {
                if f.fract() == 0.0 && f >= i64::MIN as f64 && f < i64::MAX as f64 {
                    Ok(Value::Integer(f as i64))
                } else {
                    Ok(Value::Number(f))
                }
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
    if callback_contains_opaque_userdata(value)? {
        return Err("E_INVALID_VALUE: opaque userdata is not serializable".to_string());
    }
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
                let mut keys = std::collections::HashSet::new();
                for pair in t.pairs::<Value, Value>() {
                    let (key, _) = pair.map_err(|_| "invalid callback result key")?;
                    let Value::String(key) = key else {
                        return Err("callback result keys must be strings".to_string());
                    };
                    keys.insert(
                        key.to_str()
                            .map_err(|_| "invalid callback result key")?
                            .to_string(),
                    );
                }
                let ok_val: Value = t.raw_get("ok").map_err(|e| e.to_string())?;
                let err_val: Value = t.raw_get("error").map_err(|e| e.to_string())?;
                if keys == ["ok"].into_iter().map(str::to_string).collect()
                    && ok_val == Value::Boolean(true)
                {
                    Ok(())
                } else if keys == ["error"].into_iter().map(str::to_string).collect()
                    && ok_val == Value::Nil
                    && err_val != Value::Nil
                {
                    let err_table = match err_val {
                        Value::Table(et) => et,
                        _ => return Err("error field must be a table".to_string()),
                    };
                    let mut error_keys = std::collections::HashSet::new();
                    for pair in err_table.pairs::<Value, Value>() {
                        let (key, _) = pair.map_err(|_| "invalid error key")?;
                        let Value::String(key) = key else {
                            return Err("error keys must be strings".to_string());
                        };
                        error_keys
                            .insert(key.to_str().map_err(|_| "invalid error key")?.to_string());
                    }
                    if error_keys != ["code", "detail"].into_iter().map(str::to_string).collect() {
                        return Err("error must contain exactly code and detail".to_string());
                    }
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
                    Err("expected exactly ok=true or error={code,detail}".to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_to_lua_preserves_mathematical_integers() {
        let lua = Lua::new();

        let integral = json_to_lua(&lua, &serde_json::json!(1784796437093.0)).unwrap();
        assert!(matches!(integral, Value::Integer(1784796437093)));

        let fractional = json_to_lua(&lua, &serde_json::json!(1.5)).unwrap();
        assert!(matches!(fractional, Value::Number(value) if value == 1.5));
    }

    #[test]
    fn audio_metadata_accepts_integral_json_floats() {
        let metadata = serde_json::json!({
            "sample_rate": 16000.0,
            "channels": 1.0,
            "duration_ms": 2500.0,
            "pcm_bytes": 80000.0,
        });

        let parsed = parse_audio_metadata(&metadata).expect("integral floats are valid metadata");
        assert_eq!(parsed.sample_rate, 16000);
        assert_eq!(parsed.channels, 1);
        assert_eq!(parsed.duration_ms, 2500);
        assert_eq!(parsed.pcm_bytes, 80000);
    }

    #[test]
    fn audio_metadata_rejects_fractional_json_numbers() {
        let metadata = serde_json::json!({
            "sample_rate": 16000.0,
            "channels": 1.0,
            "duration_ms": 2500.5,
            "pcm_bytes": 80000.0,
        });

        assert!(parse_audio_metadata(&metadata).is_none());
    }

    #[test]
    fn test_opaque_audio_userdata_safety() {
        let engine = StateEngine::new(4 * 1024 * 1024, 100, 50000, 16, 16).unwrap();
        let inner = engine.inner.lock().unwrap_or_else(|e| e.into_inner());
        let lua = inner.lua.as_ref().unwrap();

        // 1. Create userdata
        let userdata =
            create_opaque_audio_userdata(lua, "987654321".to_string(), OpaqueAudioKind::Recording)
                .unwrap();
        lua.globals().set("u", userdata).unwrap();

        // 2. Verify type
        let is_userdata: bool = lua.load("type(u) == 'userdata'").eval().unwrap();
        assert!(is_userdata);

        // 3. Stringify safety: tostring must not leak the token or address
        let str_rep: String = lua.load("tostring(u)").eval().unwrap();
        assert_eq!(str_rep, "opaque_audio");

        // 4. Locked metatable: getmetatable(u) must return false, and setmetatable must fail
        let get_mt_res: mlua::Value = lua.load("getmetatable(u)").eval().unwrap();
        match get_mt_res {
            mlua::Value::Boolean(false) => {}
            other => panic!(
                "Expected metatable of opaque audio to be false, got {:?}",
                other
            ),
        }

        let set_mt_fails: bool = lua
            .load("not pcall(function() setmetatable(u, {}) end)")
            .eval()
            .unwrap();
        assert!(set_mt_fails);

        // 5. No public fields/methods & no write access
        let read_field_fails: bool = lua
            .load("not pcall(function() return u.token end)")
            .eval()
            .unwrap();
        assert!(read_field_fails);

        let call_method_fails: bool = lua
            .load("not pcall(function() u:token() end)")
            .eval()
            .unwrap();
        assert!(call_method_fails);
        let write_field_fails: bool = lua
            .load("not pcall(function() u.token = 123 end)")
            .eval()
            .unwrap();
        assert!(write_field_fails);

        // 6. Call safety: userdata cannot be called as a function
        let call_fails: bool = lua.load("not pcall(function() u() end)").eval().unwrap();
        assert!(call_fails);
    }
}
