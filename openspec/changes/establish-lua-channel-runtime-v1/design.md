## Context

The production-internal Lua actor runtime (`integrate-lua-actor-runtime`) proved that stock Lua 5.4.8 can execute safely on the supported Android boundary, and promoted the proof kernel into one host-owned actor per runtime generation. That change delivered a bounded mailbox, cooperative ready/suspended scheduling, opaque generation-safe operation tokens, a failure latch, drain-before-ready replacement, and deterministic close—all inside the existing `ChannelRuntimeRegistry`, `RuntimeGenerationInvocationGate`, and `RevocableChannelCapabilityScope` stack.

It did **not** define:
- A public Lua runtime contract or versioned API;
- Any Subspace Lua module (`subspace.runtime`, `subspace.channel`, `subspace.log`);
- An immutable program-image format or package-local module loader;
- A `ChannelImplementationProvider` / `ChannelRuntime` adapter that translates a Lua program image through the existing registry and actor paths;
- A black-box verification harness exercising the full provider-to-actor path;
- An extension to `ChannelRuntimeConstructionRequest` that supplies an opaque host-owned generation execution context.

These gaps block every downstream concern—package installation, distribution, official-channel migration, and plugin-author tooling—from depending on a public, stable Lua compatibility boundary. This change fills them.

### Existing surfaces the adapter targets

The existing provider/registry/actor stack already provides the host-owned boundary:

- `ChannelImplementationProvider` / `ChannelImplementationDescriptor`: registration, schema migration/validation, runtime construction from validated configuration. `constructRuntime` receives a `ChannelRuntimeConstructionRequest` carrying the `ChannelDefinition`, `ValidatedChannelConfiguration`, and a `ChannelCapabilityScope`. It returns `ChannelRuntimeConstructionResult`.

- `ChannelRuntime` (provider-neutral): `prepareInput()`, `handleSos()`, `refreshReadiness()`, `activate()`, `close()`, and a `StateFlow<ChannelRuntimeSnapshot>` for readiness/execution-status projection.

- `ChannelRuntimeRegistry`: per-instance runtime lifecycle, staged successor semantics, drain-before-ready replacement, committed-input leases, shutdown ordering. Constructs runtimes via the provider's `constructRuntime` outside registry locks.

- `RuntimeGenerationInvocationGate`: bounded FIFO admission, serialized callback execution, timeout, `stopAdmission`, `invalidate`, `commitIfLive`, idempotent `close`, committed-target terminal phases. The gate is created by the registry for each pending generation entry.

- `RevocableChannelCapabilityScope`: instance-scoped capability leases bound to `CapabilityScopeIdentity(channelInstanceId, runtimeGeneration)`. Staged successors use `initiallyAuthorized: false` and receive `authorize()` only after the predecessor closes. Revocation is monotonic via `ScopeState` CAS (`PENDING -> OPEN -> REVOKED`).

- The production-internal Lua actor (one per runtime generation): owns one Lua state, one event mailbox, one ready-coroutine scheduler, a set of suspended operation tokens, and a background-task scope—all bounded by the generation's lifetime and invocation gate.

- `ChannelRuntimeConstructionRequest` (current shape): `val definition: ChannelDefinition, val configuration: ValidatedChannelConfiguration, val capabilities: ChannelCapabilityScope`. It has no generation identity, no parent-scope handle, no continuation-authority object, and no reference to the `RuntimeGenerationInvocationGate` or the generation's actor scheduling domain.

## Goals / Non-Goals

### Goals

    and no filesystem or native-module fallback. Module caches are per-generation (scoped to the
    Lua state lifetime), not shared across replacement or across instances.

- Define the initial public Lua module surface: `subspace.runtime` (version/identity, generation-bound spawn/sleep/timer semantics), `subspace.channel` (channel lifecycle callbacks, input/SOS/readiness/terminal-outcome contracts), and `subspace.log` (bounded structured plugin logging).

- Define one public asynchronous operation/result model shared by timer operations and future Subspace-native modules: normalized Lua data values, exactly-once continuation or completion, and opaque cancellation—without exposing actor identities, JNI outcome JSON, Kotlin coroutine objects, `CoroutineScope`, `RuntimeGenerationInvocationGate`, `RuntimeGeneration`, `CapabilityScopeIdentity`, Android objects, or native handles.

- Define an immutable Lua program image and a Lua-backed `ChannelImplementationProvider`/`ChannelRuntime` adapter that translates program-image construction, activation, generic channel events, input targets, readiness projection, replacement, and close through the existing actor stack and generation gate.

- Extend provider construction with an opaque host-owned generation execution context (`GenerationExecutionContext`) that supplies lifecycle and continuation authority without exposing `CoroutineScope`, `RuntimeGenerationInvocationGate`, `RuntimeGeneration`, actors, `CapabilityScopeIdentity`, registry gates, or platform objects to plugin programs.

- Prove the public contract with black-box Lua source fixtures through the real provider registry, runtime registry, capability scope, actor kernel, replacement, and shutdown paths, including independent same-provider instances, package-local `require`, proactive timer work while unselected, normalized failures, and stale-effect suppression.

- Keep ordinary application startup Kotlin-only: no production Lua provider is registered, no persisted catalogue entry changes, and no Lua state is created unless an explicitly supplied Lua program-image provider is resolved.

### Non-Goals

- No package format, installation, discovery, update, rollback, signing, or provenance contract.
- No channel catalogue or configuration-schema changes.
- No declarative configuration UI schema, RSM controls, or host-rendered control surface for Lua plugins.
- No durable message, output, playback, or control API beyond channel lifecycle outcomes.
- No secure credential selection, plugin-owned writable directories, or background subscription/poll contract beyond timers and explicitly declared host capabilities.
- No Lua bytecode loading, `package.loadlib`, C-module searchers, JNI, FFI, shared-object loading, or plugin-supplied native modules.
- No official channel migration, no built-in Kotlin channel replacement, and no user-visible channel behavior change.
- No execution guarantee outside the foreground-service lifetime.
- No claim of containment for native engine defects, unprotected panics, unrecoverable process OOM, or Android process death.

## Decisions

### D1. Subspace Lua Runtime v1 versioning

The runtime environment is identified by exact version constants on `subspace.runtime`:

| Constant | Value |
|----------|-------|
| `LUA_VERSION` | `"Lua 5.4"` |
| `LUA_RELEASE` | `"5.4.8"` |
| `API_VERSION` | `"subspace-lua-v1"` |

There is no `runtime.version()` function, no integer runtime version, and no integer API version. The constants above are the sole public identity. A future API version would assign a new `API_VERSION` string.

Program images declare exact execution requirements via metadata fields `luaVersion` and `apiVersion`, each compared for equality against the host's constants. An image requiring `"subspace-lua-v1"` will be rejected by a host at `"subspace-lua-v2"`, and vice versa. No range matching, no ordering — exact string equality only.

**Alternative considered:** expose a `subspace.runtime.version()` function returning a version table. Rejected to keep the surface minimal. The three constants are discoverable and match the program-image metadata contract directly.

**Alternative considered:** use separate integer major/minor/API versions. Rejected because the API version is a single identifier that package signatures and image metadata compare by exact string equality. Integer ranges would imply compatibility semantics that v1 does not define.

**Alternative considered:** include plugin version/label metadata in the program image. Rejected: the program image declares execution requirements only; plugin identity and version tracking are package-format concerns, which is explicitly a non-goal in this change.

### D2. Standard-library policy: restricted and stable

The v1 environment exposes a restricted subset of Lua 5.4's standard libraries. The policy is to retain only what is safe, deterministic in a single-threaded cooperative environment, and unlikely to conflict with host-owned platform concerns. Libraries that imply blocking I/O, raw memory access, loadable modules, or debugging are removed entirely. The specific set:

**Available:**
- `_G` (global environment, with removed entries cleared)
- `table` (full)
- `string` (full, except `string.dump` is removed to keep source-only enforcement unambiguous — bytecode cannot be loaded so the host rejects it at the standard-library level)
- `math` (full)
- `utf8` (full)
- `coroutine` (full – the plugin may wrap the runtime's managed coroutines for its own internal composition. However, `coroutine.yield` inside a plugin-created coroutine yields only to that coroutine's Lua resumer; it is NOT equivalent to suspending to the actor scheduler. Only `subspace.runtime.sleep` may suspend a spawned task across the host boundary. If a raw/unrecognized yield escapes a host callback or task boundary — i.e., the host resumes through `pcall` and receives a yield instead of a return value — the runtime detects the invalid-yield event and terminates the callback/task with a typed invalid-yield error. No actor-scheduler yield occurs.)
- `basic` functions that are safe: `type`, `pairs`, `ipairs`, `next`, `select`, `tonumber`, `tostring`, `error`, `assert`, `pcall`, `xpcall`, `rawget`, `rawset`, `rawlen`, `setmetatable`, `getmetatable`. The stock `_VERSION` global is retained with value `"Lua 5.4"`. `load` is removed (no arbitrary bytecode or source loading from plugin control).

**Removed:**
- `io` (filesystem I/O is host-owned via capabilities)
- `os` (process, environment, clock — all host-owned)
- `package` (the built-in package loader/searcher is replaced by the host-domain source map; `package.loadlib`, `package.cpath`, `package.path`, package preload/searchers are not available)
- `load` (from `basic` — no arbitrary bytecode or source loading from plugin control)
- `dofile` (filesystem I/O)
- `loadfile` (filesystem I/O)
- `require` (replaced with the host-domain source-map `require`; original `package.searchers` are not present so the stock `require` is replaced)
- `debug` (full removal — no inspection of stack, locals, upvalues, metatables of non-plugin objects, or host internals)

The global `_ENV` is the sandboxed table; the removed globals are literally absent, not `nil`. An attempt to read a removed global returns `nil` (the default in Lua) rather than throwing. An attempt to set a removed global creates a new global with that name (standard Lua behavior). The `_VERSION` global is retained with value `"Lua 5.4"`. This matches the vision's "trusted programs" model — a Lua program cannot accidentally or deliberately reference host objects through the standard library.

Remaining standard-library semantics (e.g., `string` pattern engine, `math` randomness) are the stock Lua 5.4.8 implementations. A future runtime version may replace them with deterministic variants; v1 does not.

**Alternative considered:** expose the full standard library. Rejected because `io`, `os`, `load`, `dofile`, `loadfile`, and `debug` create clear host-ownership violations. Exposing them would require every one to be shimmed or permission-checked, adding complexity and fragility without benefit.

**Alternative considered:** remove `coroutine` because the actor scheduler manages its own coroutines. Rejected because plugin authors may want internal cooperative composition (e.g., a generator or iterator pattern) that does not cross the host boundary. The actor's managed coroutines and Lua's unmanaged `coroutine` are orthogonal: a plugin coroutine created via `coroutine.create` is still a Lua coroutine that the scheduler can detect via the instruction hook, but it does not interleave with the actor scheduler's managed set. Plugin code managed by the actor scheduler that attempts `coroutine.yield` will produce a typed invalid-yield error rather than inadvertently yielding to the actor scheduler — the actor detects the yield at the `pcall` boundary and fails the callback/task.

### D3. Host-injected `subspace.*` modules and plugin source-map module loading

The `subspace.*` namespace is reserved for host-injected Subspace API modules (`subspace.runtime`, `subspace.channel`, `subspace.log`, and any future official modules). These modules are **not** part of the plugin source map. They are injected into the Lua state at initialization time as precompiled closures bound to the generation's host-owned capability and lifecycle context.

Plugin authors call them via standard `require`:
```
local runtime = require("subspace.runtime")
local channel = require("subspace.channel")
```

The stock `package` library and its `require` implementation are removed. The `package` global is absent entirely — no `package.path`, `package.cpath`, `package.searchers`, `package.preload`, `package.loadlib`, or `package.loaded` is exposed to plugin code. The replacement `require` is a standalone function injected into the globals, not a table method.

The replacement `require` uses a fixed three-step resolution order:

1. **Reserved host module check.** If the requested name starts with `"subspace."`, look it up in the host's preloaded module table. Plugin source map modules are never consulted for names under `subspace.*`. If the name is a reserved prefix but not a known module, raise a typed protected module error (detectable via `pcall`).
2. **Per-generation module cache.** If the module was previously loaded from the source map, return the cached value. The cache is scoped to the current actor/generation — it is never shared across generations or replacement instances.
3. **Immutable source map lookup.** If the name exists in the immutable host-domain source map, load the source in a new Lua closure sharing the actor's restricted global environment (same `_ENV` as the actor state), execute it, cache the result, and return it. If not found, raise a typed protected module error.
**Module return semantics.** The `require` shim follows Lua conventions with one adjustment: if the loaded module chunk returns `nil`, the cache stores and returns `true`. Otherwise the exact first returned Lua value is cached inside the owning Lua state, including functions or tables containing functions. Module values never cross the normalized host-data boundary. This preserves ordinary pure-Lua library patterns while eliminating the `nil + "module not found"` ambiguity.
Every source-map module closure uses the same restricted global environment as the entry module and all other host-managed closures. Isolation is provided by the per-generation Lua state and the sandboxed global table, not by per-module environments. The per-generation cache ensures each module is loaded exactly once per generation.

The `ImmutableProgramImage` snapshots the source map once at image creation. The underlying bytes of a well-formed image may be shared across generations (no per-generation deep copy is required), but each generation constructs its own independent cache and Lua state.

**Alternative considered:** merge subspace.* modules into the source map as ordinary entries. Rejected because it would let a plugin shadow `subspace.runtime` with its own version, breaking the public API contract. The host module namespace is reserved and immutable.

**Alternative considered:** keep the stock `require` and preconfigure its searchers to only use the source map. Rejected because the stock searcher contract is complex, platform-dependent, and exposes `package.path`, `package.cpath`, and error reporting that would be misleading for a source-map-only environment. A clean shim with no visible `package` table is simpler to audit and document.

**Alternative considered:** give each plugin a writable data directory with a second source-map layer. Rejected because v1 has no writable plugin storage contract; that is a non-goal. The source map is immutable within a generation.

### D4. Entry module returns a validated callback table (fail-closed validation)

The entry module (named by the program image's `entryPoint` field) is loaded and executed during construction. Its return value MUST be a table that the runtime validates structurally. If the entry module returns something other than a table, construction fails with a typed validation error and the generation is refused readiness.

**Pure module evaluation.** Evaluation of ANY source-map module chunk (entry module or lazy `require`d module) is synchronous, non-yielding, and cannot invoke any callable `subspace.*` host-injected function (`subspace.runtime.spawn`, `subspace.runtime.sleep`, `subspace.log.*`, etc.). Module chunks may `require` other source-map modules, read constants, define functions, construct tables, and perform pure-Lua operations — but no host-injected effect may be called during top-level chunk evaluation. An attempt to invoke any `subspace.*` callable during module evaluation produces a typed construction or require error. Host-injected effects occur only when the returned callback functions are invoked by the runtime in startup/callback/spawned-task contexts.

Validation is **fail-closed**: the runtime validates every recognized callback key and ignores unrecognized keys so that a future API version with additional callbacks does not break v1 entry modules. A recognized callback field of the wrong type causes construction failure. An absent optional callback is treated with a documented neutral default; an absent required callback causes construction failure.

The entry module's return table recognizes these keys:

```lua
{
  -- REQUIRED. A non-yielding initialization callback.
  -- Called during authorized activate(), after entry module validation
  -- but before readiness publication. Must not yield (no sleep).
  -- MAY call subspace.runtime.spawn to synchronously admit background
  -- coroutines. The plugin may use startup to initialize module-level
  -- state and admit background tasks. Event callbacks are registered
  -- by key presence in this table, not by calling registration functions.
  startup = function() end,

  -- Optional. Called synchronously during activate(), immediately before
  -- readiness publication, to deliver lifecycle events. In v1, called
  -- once with { event = "ready" }. Must not yield, must not spawn.
  -- Default (when absent): lifecycle events produce no effect.
  handle_lifecycle = function(event) end,

  -- Optional. Called synchronously after capture completion, with a
  -- table containing event identity and bounded scalar metadata.
  -- Receives { event = "capture", session = string,
  --             metadata = { ... bounded scalars ... } }.
  -- Returns { ok = true } on success or
  -- { error = { code = string, detail = string } } on failure.
  -- Synchronous, non-yielding, must not spawn.
  -- Default (when absent): inputs are refused (no capture occurs).
  -- Must not retain audio after return or start durable work.
  handle_input = function(event) end,

  -- Optional. Called synchronously on SOS activation.
  -- Must not yield, must not spawn.
  -- Default (when absent): no-op (SOS ignored).
  -- Returned error table, throw, or yield is local-contained:
  -- the runtime logs a diagnostic, the callback failure
  -- does not propagate and does not change snapshot state.
  handle_sos = function(event) end,

  -- Optional. Called during refreshReadiness() to project channel
  -- readiness. Returns { ready = boolean }.
  -- Synchronous, non-yielding, must not spawn.
  -- Default (when absent): cached as not ready (neutral not-ready).
  -- The runtime caches the return value and uses it in prepareInput
  -- without calling Lua again.
  handle_readiness = function() end,
}
```

Validation rules (enforced at construction, in order):

1. Return value must be a plain table with no metatable. If a metatable is present → construction failure (metatables on the callback table are prohibited).
2. All recognized-key access during validation uses raw access (`rawget` / direct field access), not `__index` — this prevents a metatable on the runtime's visitor from synthesizing callbacks. (The check in step 1 already rejects metatables; raw access is defense-in-depth.)
3. `startup` must be present and non-nil. Otherwise → construction failure: `"required callback 'startup' is missing"`.
4. If `startup` is present and non-nil but is not a function → construction failure: `"expected function for callback 'startup', got <type>"`.
5. For every optional recognized key (`handle_lifecycle`, `handle_input`, `handle_sos`, `handle_readiness`):
   - If the key is absent or its value is `nil`, the runtime applies that callback's neutral default.
   - If the value is present, non-nil, and not a function → construction failure: `"expected function for callback 'X', got <type>"`.
6. Unrecognized keys are silently ignored; raw access ensures `__index` cannot inject recognized keys.

Note: In Lua semantics, a table key assigned `nil` is indistinguishable from absence. The validator treats both cases identically — an optional callback set to `nil` uses the neutral default, not a validation error. Only present non-nil non-function values cause failure.

At runtime (when the respective ChannelRuntime method is called):

- **`startup`** runs during `activate()`, after entry module validation and before readiness publication. It is non-yielding; any yield attempt is a contract violation. If startup throws or returns a failure table, `activate()` returns `ChannelActivationResult.Failed` and readiness is not published.
- **`handle_input`** return value validation (fail-closed): success is exactly `{ok = true}` with no `error` field; failure is exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}` with no `ok` field. A non-table, conflicting fields, wrong field types, or any other table shape records the input as failed with a typed invalid-outcome error.
- **`handle_sos`** return value: unused. Any normal non-error return is ignored. If the callback throws, returns an error table, or attempts to yield, the runtime logs a diagnostic and treats it as a local-contained failure — no snapshot change, no propagation to the host.
- **`handle_readiness`** return value: `{ready = true}` caches ready; absent or nil `ready` caches not ready. A non-table, an error table, a non-boolean non-nil `ready`, a throw, or a yield logs a local diagnostic and caches not ready.

A plugin that only runs proactive background work (spawned tasks, periodic polling) and never handles PTT input or SOS simply omits all optional callbacks. The runtime keeps the plugin live: background tasks run, sleep timers fire, but the channel is not available for PTT selection.

**Alternative considered:** require `handle_input` and `handle_sos` as mandatory. Rejected to support proactive-only plugins. The neutral defaults let such plugins skip those callbacks.

**Alternative considered:** require plugin authors to expose individual global functions. Rejected because a returned table is more composable, avoids global namespace pollution, and lets the runtime validate the contract at construction by inspecting the single returned value.

**Alternative considered:** allow `startup` to yield for async initialization. Rejected because construction must complete synchronously inside the registry's lock-free reconciliation path; yielding during construction would require a gate slot before the runtime is registered. Background initialization is done by calling `subspace.runtime.spawn(fn)` from `startup`.

**Alternative considered:** include a `close` callback for cleanup notification. Rejected: close notification is not part of the v1 callback surface. The generation's close lifecycle is handled entirely by the host; plugins needing cleanup at close should use generation-bound resource management.
### D5. Public module shapes

#### `subspace.runtime`

Provides runtime identity, cooperative sleep, and background task spawning.

```lua
subspace.runtime.LUA_VERSION = "Lua 5.4"
subspace.runtime.LUA_RELEASE = "5.4.8"
subspace.runtime.API_VERSION = "subspace-lua-v1"

-- Admit a background function synchronously. The function runs in a
-- generation-bound coroutine and may call sleep() for async delays.
-- Returns (true, nil) on successful admission.
-- Returns (nil, { error = "E_BUSY" }) if the actor cannot admit another task.
-- Errors in spawned coroutines are logged and contained; they do not
-- latch the actor as failed.
-- Synchronous admission: no yield at the call site.
subspace.runtime.spawn(fn: function) -> (true, nil) | (nil, error_table)

-- Suspend the current coroutine for at least `seconds` seconds.
-- Must only be called from spawned background tasks, not from synchronous
-- event callbacks (startup, handle_lifecycle, handle_input, handle_sos,
-- handle_readiness).
-- Returns (true, nil) on success when the timer fires.
-- Returns (nil, { error = "E_BUSY" }) if the timer cannot be started
-- (resource limit reached). When the generation closes while sleeping,
-- the suspended coroutine is silently discarded — no resume occurs,
-- no error is delivered.
-- The coroutine is resumed on the actor's ready queue after the delay.
subspace.runtime.sleep(seconds: number) -> (true, nil) | (nil, error_table)
```

Design rationale:

- `spawn` is synchronous admission: the function is immediately enqueued on the actor's ready queue. It does not yield at the call site. Admission may fail if the per-generation spawn limit is reached, returning `(nil, { error = "E_BUSY" })`. No silent drops.
- `sleep` is the only yielding primitive exposed in v1. The coroutine is suspended via the actor's internal yield mechanism and resumed when the host timer fires. When the generation closes, the actor's operation tokens are invalidated and the Lua state is closed — any suspended coroutine is silently discarded without resume. Plugin code must not depend on receiving an error or cancellation value when the generation closes; the coroutine simply stops executing.
- Both `spawn` and `sleep` return `(value, nil)` on success, `(nil, error_table)` on failure. This matches the normalized async contract (D6).

#### `subspace.channel`

Host-injected module providing channel event type constants for callback argument matching. Plugins callbacks receive event tables whose `event` string field corresponds to one of these constants.

```lua
subspace.channel.LIFECYCLE_READY = "ready"
subspace.channel.CAPTURE_COMPLETE = "capture"
subspace.channel.SOS_TRIGGERED = "sos"
```

In v1 the module defines only these three constants. There are no callable functions — the plugin's callback table (D4) is the sole interface for channel lifecycle, input, SOS, and readiness projection. Future API versions may add callable channel functions.

Terminal outcome for an input is determined by `handle_input`'s return value after capture completes. No `on_*` registration functions exist in v1.

#### `subspace.log`

Provides bounded structured logging that feeds into the host's observable diagnostics.

```lua
subspace.log.debug(payload: table) -> (true, nil) | (nil, { error = "E_INVALID_VALUE" })
subspace.log.info(payload: table) -> (true, nil) | (nil, { error = "E_INVALID_VALUE" })
subspace.log.warn(payload: table) -> (true, nil) | (nil, { error = "E_INVALID_VALUE" })
subspace.log.error(payload: table) -> (true, nil) | (nil, { error = "E_INVALID_VALUE" })
```

Return convention:
- `(true, nil)` — entry accepted, or rate-limited and silently dropped within the bounded window
- `(nil, { error = "E_INVALID_VALUE" })` — payload rejected as invalid (contains non-serializable content); the host does NOT recursively log the invalid payload

Each function accepts a structured table payload. The host enriches each entry with the channel instance identifier, runtime generation, timestamp, and level. Payload fields must be normalized Lua data (no functions, userdata, coroutine references). An invalid payload (containing non-serializable fields) rejects the whole call — the host never strips fields.

Log calls are non-blocking, non-yielding, and never produce side effects beyond the bounded ring buffer. Excessive entries from one actor within a window are silently dropped. Rate limits and buffer sizes are host-configurable and non-normative; no specific numeric default is part of the v1 API contract.

**Alternative considered:** tag-plus-message string signature. Rejected to match the specification's structured table contract, which allows richer payloads without concatenation and gives the host structured diagnostic data.

**Alternative considered:** expose a single `log(level, payload)` function. Rejected because four named functions are more discoverable and consistent with Lua convention.

**Alternative considered:** expose `enabled(level)` for skipping expensive payload construction. Rejected because v1's structured payload expectation favors simple inlining; a future version may add level gating if measurement shows it is needed.
### D6. Async operation/result model
The two operations in v1 that cross the host boundary with a time or concurrency dimension are `subspace.runtime.spawn(fn)` (synchronous admission) and `subspace.runtime.sleep(duration)` (cooperative yielding). Both follow this public contract:

**Argument validation (fail-closed):**
- `spawn`: requires exactly one argument of type `function`. If the argument is missing, not a function, or of an invalid type, returns `(nil, {error = "E_INVALID_ARGUMENT"})` without attempting admission.
- `sleep`: requires exactly one argument that is a finite number >= 0. NaN, ±inf, or negative values produce `(nil, {error = "E_INVALID_ARGUMENT"})`. Values above the configured maximum timer duration are rejected with `E_INVALID_ARGUMENT` (out of range). Zero is clamped to the host's minimum timer tick.
**Normalised platform object boundary:**
All host-to-Lua and Lua-to-host data exchange uses normalized Lua types only: nil, boolean, finite number (Lua integer or float — reject NaN / ±inf), string, and plain table. The normalizer enforces these rules:

- **Table shape:** a table is treated as either a contiguous 1..n array or a string-keyed map, never mixed, never sparse, never with non-string keys.
- **Rejected whole-value:** cycles, metatables, functions, userdata, threads (coroutines) are rejected as a whole value — the runtime does not attempt partial serialization or unbounded traversal. An invalid value at any level produces `E_INVALID_VALUE` for the entire operation.
- **Configurable bounds applied before conversion:** maximum table nesting depth, maximum table entry count (per table), maximum string byte length, valid UTF-8. Exceeding any bound or providing an invalid-UTF-8 Lua byte string produces `E_INVALID_VALUE`.

These bounds apply to callback return values, event table construction, `subspace.log` payloads, and any other data crossing the Lua/host boundary. All numeric limits are internal configuration defaults (non-normative, host-configurable).

**Internal Lua references are not host data.** The function passed to `spawn`, callback-table functions, and cached module values remain referenced only inside the owning Lua state and scheduler; they are never serialized or delivered as Kotlin/public host data. `spawn` is the only public host call that accepts a Lua function argument. Close discards these internal references with the Lua state.

```lua
-- On success: first is the result value (true for spawn/sleep); second is nil.
local ok, err = subspace.runtime.sleep(1.0)        -- (true, nil)
local ok, err = subspace.runtime.spawn(some_fn)    -- (true, nil)

-- On failure: first is nil; second is an error table with classification.
-- E_BUSY: spawn could not admit the task.
local ok, err = subspace.runtime.spawn(some_fn)    -- (nil, { error = "E_BUSY" })
| Error string | Source | Meaning |
|---|---|---|
| `"E_INVALID_ARGUMENT"` | spawn, sleep | Argument has wrong type or out-of-range (non-function, NaN, negative) |
| `"E_BUSY"` | spawn, sleep | Resource limit reached (task admission or timer slot exhaustion) |
| `"E_INVALID_CONTEXT"` | spawn, sleep | Called from a context that cannot use the operation (plugin-created child coroutine, wrong coroutine) |
| `"E_INVALID_VALUE"` | Any host/Lua value boundary | Value fails normalized type check (invalid UTF-8, wrong shape, bound exceeded, illegal type) |
| `"E_TIMEOUT"` | sleep | Operation-specific deadline expired (requested delay + host slack exceeded; generation close produces no value at all) |
| `"E_CANCELLED"` | Any explicitly cancellable live host operation | Operation was cancelled while the generation remained live; generation close instead discards the suspended coroutine with no value |

**Require error strings (from `require` in any source-map module):**

| Error string | Meaning |
|---|---|
| `"E_MODULE_NOT_FOUND"` | Module name not in the source map |
| `"E_INVALID_MODULE_NAME"` | Module name fails grammar validation (not a string, contains path separators, invalid characters, empty segment, etc.) |
| `"E_RESERVED_MODULE"` | Module name is reserved (e.g. `subspace.*`) |
| `"E_MODULE_CYCLE"` | Recursive require cycle detected |

**Coroutine and spawn call contexts.** `spawn` and `sleep` are allowed only from specific call sites:

- **`startup`** may call `spawn` (synchronous task admission). It must not call `sleep` — yielding is prohibited during activation; any yield attempt is a callback violation that fails activation.
- **Runtime-managed spawned tasks** (the coroutine created by `subspace.runtime.spawn`) may call both `spawn` and `sleep`.
- **Entry module evaluation** and **event callbacks** (`handle_lifecycle`, `handle_input`, `handle_sos`, `handle_readiness`) must not call `sleep` or `spawn` — any attempt is an invocation contract violation that fails the construction or callback (detected by the host's fail-closed callback error mechanism), not a return of `E_INVALID_CONTEXT`.
- **Plugin-created child coroutines** (`coroutine.create`/`coroutine.wrap`) inside a managed spawned task may attempt `sleep` only; the runtime detects the wrong coroutine and returns `(nil, {error = "E_INVALID_CONTEXT"})` without creating a timer or operation token. `spawn` in a child coroutine also returns `E_INVALID_CONTEXT` with no task admission.

`subspace.log` functions are non-yielding and may be called from callbacks, spawned tasks, and plugin-created child coroutines subject to the same bound limits. They MUST NOT be called during entry-module or source-map module evaluation, where every host-provided callable is prohibited.
### D7. Channel input acceptance and terminal outcomes

#### Readiness projection (cached)

The adapter projects channel readiness through two paths:

1. **Initial default**: Before any `refreshReadiness` call, the cached readiness is the neutral default: not ready. An absent `handle_readiness` callback also defaults to not ready.
2. **`refreshReadiness()` invocation**: Extend the registry readiness path to invoke `refreshReadiness()` once immediately after generation activation passes the live-generation gate and generation readiness is published, then preserve the existing periodic calls. It never calls the Lua readiness callback before `startup` and `handle_lifecycle({event = "ready"})` succeed. The adapter calls `handle_readiness()` synchronously if present. If the callback returns `{ ready = true }`, readiness is cached as ready; any other return (including absent `ready` field, `{ ready = false }`, or absent callback) is cached as not ready.

The cached readiness is stored in an internal `StateFlow<Boolean>` that the adapter updates. It is never read from Lua during `prepareInput`.

#### Synchronous prepareInput flow

When the host calls `prepareInput()`:

1. Read the **cached** readiness value (no Lua call).
2. If readiness is `true` AND the callback table has a `handle_input` function: return `ChannelInputAcceptance.Accepted(target)` — input capture begins. The target is a real `ChannelInputTarget` identifying this runtime's committed-input lease.
3. Otherwise: return `ChannelInputAcceptance.Refused` with a typed reason (`"not ready"` or `"input not supported"`).

Flow diagram:

```
Host calls adapter's prepareInput()
  → adapter reads cached readiness (from last refreshReadiness)
  → checks callbackTable.handleInput != null
  → if both ready: return Accepted(ChannelInputTarget(this_runtime))
     → host commits input target, capture begins
  → if not ready or no handle_input: return Refused(reason)
```

There is no Lua yield, no coroutine suspension, and no Lua function call in this path. The decision is purely based on the cached projection and the callback table structure.

#### handle_input: terminal outcome for completed capture

Once capture completes (audio data is captured by the host), the host delivers a capture-complete event through the committed target's invoke path. The adapter calls `handle_input(event)` synchronously:

```
Capture completes
  → host invokes committed target's terminal phase
    → adapter calls Lua handle_input(event) synchronously
      event = {
        event = "capture",         -- constant from subspace.channel.CAPTURE_COMPLETE
        session = "session-uuid",  -- unique session identifier
        metadata = {               -- bounded scalar metadata, no audio handles
          duration_ms = number,
          sample_rate = number,
          channels = number,
        },
      }
    → handle_input returns { ok = true } or { error = { code = "...", detail = "..." } }
  → adapter maps to terminal outcome: success or failure
```

The `handle_input` callback:
- Receives only normalized event identity and bounded scalar metadata — **no opaque audio handle**, no raw PCM, no host audio object. Raw audio data remains host-owned.
- Runs **synchronously** inside the gate's committed-target invocation phase. The lease is released only after the callback returns.
- Must not yield (no `sleep`), must not spawn, must not retain audio after return.
- Returns `{ ok = true }` for successful processing, or `{ error = { code = "E_...", detail = "..." } }` for failure.
- A non-table return value, or a table without the expected shape, is treated as a contract error: the input is recorded as failed with a typed invalid-outcome error (fail-closed).

If the plugin needs to perform async processing on the capture metadata, it can call `subspace.runtime.spawn(fn)` from `startup` only (not from `handle_input`). The terminal callback itself remains synchronous.
### D8. Immutable program image

The `ImmutableProgramImage` is a host-side sealed interface:

```kotlin
sealed interface ImmutableProgramImage {
    val sourceMap: Map<String, String>  // module name → Lua source
    val entryPoint: String              // entry module name
    val requirements: LuaProgramRequirements
}

data class LuaProgramRequirements(
    val luaVersion: String,   // exact LUA_VERSION required, e.g. "Lua 5.4"
    val apiVersion: String,   // exact API_VERSION required, e.g. "subspace-lua-v1"
)

```

LuaProgramRequirements contains only the exact execution requirements. There is no plugin version, label, or identity metadata — those are package-format concerns (non-goal). Both fields are compared by exact string equality against the host's `LUA_VERSION` and `API_VERSION` constants. No range or ordering semantics.

Construction path, with compatibility check BEFORE Lua state creation:

The program image is supplied by the host (e.g., test fixture provides it directly). It is NOT decoded from a persisted provider configuration — the provider's `ValidatedChannelConfiguration` remains provider-defined and may be empty for a Lua runtime whose program image is supplied by the host.

Source-map validation includes configurable bounds:
- Maximum source-map entry count (distinct module names)
- Maximum bytes per module source text
- Maximum total source-map bytes

All bounds are internal configuration defaults, not part of the public API contract. An image exceeding any configured bound is rejected during Step 2 without creating a Lua state.

  Step 1 — Requirements compatibility check (no Lua state, no kernel bridge)
  ├── Validate host LUA_VERSION equals requirements.luaVersion
  ├── Validate host API_VERSION equals requirements.apiVersion

  Step 2 — Source map validation (no Lua state)
  ├── Validate entryPoint exists in sourceMap
  ├── Validate no module name shadows the "subspace." reserved prefix
  ├── Validate every module name matches [a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*
  ├── Validate all source text is valid UTF-8
  ├── Validate source map entry count, per-module bytes, total bytes against configurable bounds
  └── If any check fails → fail construction without creating a Lua state

  Step 3 — Lua state creation and initialization
  ├── Create Lua state via kernel bridge (allocator, instruction hook)
  ├── Inject restricted globals (filtered standard libraries)
  ├── Inject subspace.* host modules as preloaded closures
  ├── Install replacement require shim (host module → cache → source map)
  └── If any step fails → close state, fail construction

  Step 4 — Load entry module only; other modules resolved lazily via require
  ├── Load and execute entry module from sourceMap (pure evaluation — no spawn/sleep/effects)
  ├── Validate returned callback table (fail-closed, D4)
  └── If any step fails → close state, fail construction


**Immutability guarantee.** The `ImmutableProgramImage` is a sealed interface; the only concrete implementation is a validated value-copy-once data class whose `sourceMap` is an unmodifiable copy created during construction. The `Map<String, String>` interface alone does not guarantee immutability — the factory wraps the provided map in `Collections.unmodifiableMap(...)` and takes a defensive copy of every string value. After construction, no code can mutate the source map, entry point, or requirements. The same image instance may be safely shared across generations without deep copy because all referenced data is effectively immutable.
At adapter construction (registry reconciliation time):
  → Run Step 1 and Step 2 (pure Kotlin/source validation).
  → If they pass, proceed with Step 3 and Step 4 inside the generation-gated construction path.

The program image is immutable for the lifetime of the runtime generation. The source map is captured once at image creation; safe image bytes may be shared across generations without per-generation deep copy. The source map is never mutated — no scenario exists for source-map alteration. A program update (new version) requires a replacement generation and a fresh program image.

### D9. Provider/runtime adapter with generation execution context

The current `ChannelRuntimeConstructionRequest` has three fields: `definition`, `configuration`, and `capabilities`. It lacks a generation identity and generation-bound lifecycle/timer/task authority.

This change extends provider construction by adding an opaque, host-owned **generation execution context** to the construction request. This context wraps only the provider-neutral lifecycle authority that all providers need, without exposing Kotlin coroutine scopes, actor types, invocation gates, capability scope identities, or registry internals.


Compatibility failure is provider-neutral and typed at the host boundary. Extend `ChannelProviderError` with a distinct runtime-compatibility case carrying the requirement name plus required and supported version strings; do not collapse a Lua/API mismatch into `RuntimeConstructionFailed.detail`.
The extension takes the form of a new field on `ChannelRuntimeConstructionRequest`:

```kotlin
data class ChannelRuntimeConstructionRequest(
    val definition: ChannelDefinition,
    val configuration: ValidatedChannelConfiguration,
    val capabilities: ChannelCapabilityScope,
    // NEW: opaque provider-neutral generation execution context
    val generationContext: GenerationExecutionContext,
)
```

`GenerationExecutionContext` is a public sealed interface — any provider may call its methods, but only host-owned implementation types exist. Specified as public so the construction request is compilable by all providers without package-privacy issues.

```kotlin
/**
 * Provider-neutral generation execution context.
 *
 * Supplies typed generation-bound admission for internal timers and
 * background tasks plus a liveness query. Does NOT expose CoroutineScope,
 * RuntimeGenerationInvocationGate, CapabilityScopeIdentity,
 * RuntimeGeneration, or any other internal type.
 *
 * The context is bound to a single generation and rejects operations
 * initiated after that generation is closed or replaced.
 *
 * Sealed so only host-owned implementation types exist.
 */
sealed interface GenerationExecutionContext {
    /** Stable channel instance identifier. Consistent across generations. */
    val instanceId: String

    /**
     * Check whether the generation is still active (not closed or replaced).
     * Functions return false/throw after the generation closes.
     */
    fun isActive(): Boolean

    /**
     * Schedule a one-shot timer bound to this generation. The callback fires
     * at most once while live and is suppressed after close. The accepted
     * handle cancels the timer idempotently.
     */
    fun scheduleTimer(
        delaySeconds: Double,
        callback: suspend () -> Unit,
    ): GenerationAdmission<Disposable>

    /**
     * Admit a generation-bound background task. During construction and
     * activation, admission reserves bounded capacity and stages the task;
     * it cannot execute yet. After the registry publishes generation
     * readiness, later admissions become runnable after the current
     * invocation slice.
     */
    fun admitTask(task: suspend () -> Unit): GenerationAdmission<Unit>
}

sealed interface GenerationAdmission<out T> {
    data class Accepted<T>(val value: T) : GenerationAdmission<T>
    data class Rejected(val reason: GenerationAdmissionRejection) : GenerationAdmission<Nothing>
}

enum class GenerationAdmissionRejection {
    CLOSED,
    CAPACITY_EXHAUSTED,
}

interface Disposable {
    fun dispose()  // cancel the timer; idempotent
}
```

Context operations:

- **`instanceId`**: Stable channel instance identifier, consistent across generation replacements. Used by the adapter for diagnostics and snapshot projection.
- **`isActive()`**: Lifecycle-continuation check. The adapter calls `isActive()` after a coroutine yields or resumes to verify the generation is still live before entering Lua. Post-close operations are rejected.
- **`scheduleTimer(delaySeconds, callback)`**: Returns `Accepted(Disposable)` for a generation-bound one-shot timer, `Rejected(CLOSED)` after retirement, or `Rejected(CAPACITY_EXHAUSTED)` at the timer bound. A close suppresses an accepted timer callback; explicit disposal is idempotent.
- **`admitTask(task)`**: Phase-aware background-task admission. Before generation readiness publication, `Accepted(Unit)` reserves bounded capacity and stages the task without execution. After publication it admits runnable work after the current invocation slice. Rejection distinguishes `CLOSED` from `CAPACITY_EXHAUSTED`.

The host-owned context implementation also has non-public registry operations: `authorizeStagedTasksAfterReady()` and `discardStagedTasks()`. `ChannelRuntimeRegistry` calls authorization only after the activation result has passed the generation gate and the host has published generation readiness. Retirement, failed activation, replacement, or shutdown calls discard. Neither operation appears on the provider-visible interface. This makes startup `spawn` synchronous and capacity-aware while preventing execution before readiness or selective-cleanup ambiguity.
#### Internal ActorRuntimeFactory

The Lua provider's `constructRuntime` implementation calls an internal (package-level) `ActorRuntimeFactory` to create the Lua actor. The factory is a static/internal helper that accepts the `GenerationExecutionContext` plus the provider's kernel bridge and capability scope:

```kotlin
/**
 * Internal helper that creates a Lua actor runtime for a generation.
 * Consumes the GenerationExecutionContext for lifecycle-continuation
 * authority. The context's implementation carries the necessary
 * internal adapter ports (gate, scope, coroutine context) but
 * exposes only the provider-neutral interface to the factory.
 *
 * Capabilities are provided through the generic ChannelCapabilityScope
 * from the construction request. The factory accesses a safe subset
 * suitable for plugin operations; the registry already owns capability
 * revocation and the scope instance is bound to this generation.
 */
internal object ActorRuntimeFactory {
    suspend fun create(
        context: GenerationExecutionContext,
        programImage: ImmutableProgramImage,
        kernelBridge: LuaKernelBridge,
        capabilityScope: ChannelCapabilityScope,
    ): LuaActorRuntime
}
```

The factory is called from the provider's `constructRuntime`:

The flow above shows that the registry never inspects, decodes, or validates the program image — it provides the generation execution context and calls the provider. Image ownership and validation are entirely the provider's responsibility.

The `GenerationExecutionContext` implementation (created by the registry) may implement internal adapter ports that the factory accesses via package-level trust — the factory is an internal type in the same host package. Raw `CoroutineScope`, `RuntimeGenerationInvocationGate`, and `CapabilityScopeIdentity` remain hidden from the provider interface.

The plugin program (Lua source) never sees `GenerationExecutionContext` or `ActorRuntimeFactory`. The adapter keeps them as internal references, translating their authority into the public module APIs (`subspace.runtime.spawn`, `subspace.runtime.sleep`, etc.) without exposing any internal Kotlin types.

**Alternative considered:** include `createGate`, `registerRuntime`, and `postEvent` in `GenerationExecutionContext`. Rejected because the context is a provider-neutral lifecycle authority, not a registry management interface. Gate and registry operations are internal concerns handled by the registry and the actor factory.

**Alternative considered:** expose `CoroutineScope` through `GenerationExecutionContext`. Rejected because that would leak Kotlin coroutine internals to every provider. The abstraction boundary keeps the provider interface clean.

#### Required actor policy changes for plugin lifecycle semantics

The existing internal `ActorPolicy` applies generic wall-clock deadlines that conflict with plugin lifecycle guarantees. The Lua channel adapter MUST select operation and task policies that preserve these invariants:

1. **No whole-task wall timeout for runtime-managed background tasks.** A plugin polling loop (`spawn` → loop: sleep → work → sleep) must not be terminated solely because its cumulative wall-clock lifetime exceeds the generic deadline used by the pre-change actor policy. Represent the absence of a whole-task deadline explicitly (for example, nullable policy), not with `Long.MAX_VALUE`, whose deadline arithmetic can overflow. A task still ends when its function returns or fails, or when the generation closes. Every active Lua slice remains instruction-bounded, and concurrent task admission remains bounded.

2. **Operation-specific sleep deadlines, not a generic timeout.** For `subspace.runtime.sleep(duration)`, compute an overflow-safe deadline as `requested_delay + host_completion_slack`; do not reuse `ActorRuntime.operationWaitDeadlineMillis`. Timer completion and deadline expiry race through one exactly-once terminal admission: timer completion first resumes `(true, nil)`; deadline expiry first resumes `(nil, {error = "E_TIMEOUT"})`; a later completion is stale. Explicit cancellation while the generation remains live resumes `(nil, {error = "E_CANCELLED"})`. Generation close silently discards the suspended coroutine with no result.

3. **Verification invariants.** A periodic loop survives beyond the former generic task deadline while active slices remain within instruction policy. A sleep longer than the former generic operation timeout succeeds when its timer wins before its own deadline. A delayed timer returns `E_TIMEOUT` exactly once when its deadline wins. An infinite active Lua loop is interrupted by instruction policy. Generation close never resumes a pending sleep.

These policy changes are internal to the actor/runtime adapter. Existing default policies for other actor users remain unchanged.
### D10. Lua adapter runtime implementation

`LuaAdapterRuntime` owns the actor, callback references, per-generation module cache, cached input readiness, diagnostics buffer, and runtime snapshot. It does not own capability revocation or execution-context retirement; `ChannelRuntimeRegistry` retains those responsibilities.

#### Linearized lifecycle and snapshot state

The adapter uses one atomic lifecycle state:

```text
CONSTRUCTED ──activate claim──> ACTIVATING ──callbacks succeed──> READY
                                  │                                  │
                                  └──callback/contract failure──> FAILED
CONSTRUCTED / ACTIVATING / READY / FAILED ──close wins──> CLOSED
```

`CLOSED` is monotonic. Every snapshot mutation evaluates lifecycle state **inside** the `MutableStateFlow.update` transform. If state is `CLOSED`, the transform returns the current closed snapshot unchanged. `close()` first wins the lifecycle transition to `CLOSED`, then writes the terminal disabled/`RuntimeClosed` snapshot, then shuts down actor-owned resources. Consequently, a transition that began before close either linearizes before the terminal snapshot or retries against `CLOSED` and becomes a no-op; it cannot overwrite the terminal snapshot afterward.

#### Activation and close race

`activate()` follows this order:

1. CAS `CONSTRUCTED -> ACTIVATING`; any other state returns a typed activation failure.
2. Invoke `startup()` synchronously through the actor. `spawn` admissions reserve bounded context capacity and remain staged.
3. Validate the exact startup outcome, then check both adapter lifecycle and `generationContext.isActive()`.
4. Invoke `handle_lifecycle({event = "ready"})` synchronously when present. No staged task can run.
5. Validate the exact lifecycle outcome, recheck lifecycle/context liveness, and CAS `ACTIVATING -> READY`.
6. Return `ChannelActivationResult.Ready` only after that CAS. The registry still subjects the result to its generation gate and live-entry check.
7. The registry publishes generation readiness first and only then invokes its internal `authorizeStagedTasksAfterReady()` context operation. Runnable task execution cannot precede host readiness publication.

Any throw, declared failure, malformed failure, prohibited host call, yield, cancellation, close, or failed liveness/CAS check returns a typed activation failure. Registry failure/retirement invokes `discardStagedTasks()` and releases every reservation and Lua function reference. If close wins after the adapter returns Ready but before registry commit, the generation gate rejects the result; readiness is not published and staged tasks are discarded.

#### Per-invocation contract violation latching

The actor records a prohibited yield/spawn/effect attempt in the current synchronous invocation result even if Lua code ignores the direct `(nil, error)` return. This is an **invocation-local** latch, not an actor-fatal latch. The adapter applies callback-specific policy to that result:

- `startup` or `handle_lifecycle`: fail activation and discard staged tasks;
- `handle_input`: mark the input FAILED and return no playback;
- `handle_readiness`: record a local diagnostic and cache not ready;
- `handle_sos`: record a local diagnostic with no snapshot or generation-state mutation.

This preserves fail-closed callback contracts without contradicting SOS/readiness local containment.

#### Readiness projection

The cached readiness starts false. Generation readiness publication and input readiness are separate: successful activation makes the runtime generation live, but it does not imply PTT eligibility. This change adds one registry-triggered `refreshReadiness()` immediately after generation publication and retains periodic refreshes thereafter. `refreshReadiness()` calls `handle_readiness` synchronously when present, accepts only the exact readiness result, and updates the cached host projection with the linearized snapshot helper. Absence or any local callback failure caches not ready. `prepareInput()` reads only this cache plus `handle_input` presence and never enters Lua.

#### Input and SOS translation

An accepted input creates one `ChannelInputTarget`. Start projects RECORDING. Release validates positive sample rate, projects PROCESSING, computes duration from host-owned sample count/rate, and invokes `handle_input` with only event identity, a per-target session string, and bounded scalar metadata (`duration_ms`, `sample_rate`, `channels = 1`). Raw PCM and audio objects never cross the boundary. Exactly `{ok = true}` projects SUCCESS; exactly `{error = {code, detail}}` projects FAILED; every malformed/ambiguous result, throw, yield, or context violation projects FAILED. All paths return `ChannelInputResult.None`. Host cancellation projects IDLE and host capture failure projects FAILED without invoking Lua.

`handle_sos` is fire-and-forget. Absence is a neutral no-op. Normal return is ignored. A declared failure, malformed failure, throw, yield, or prohibited spawn is logged as an invocation-local diagnostic and does not mutate the runtime snapshot, propagate to the host, latch the actor, or fail the generation.

#### Deterministic close

`close()` is idempotent and may race activation or any callback. After winning `CLOSED`, it prevents new calls, publishes the terminal snapshot, interrupts/closes the actor, and waits for actor-owned callbacks, module references, operations, tasks, timers, diagnostics, and Lua state to drain. The registry then retires the generation context and revokes the capability scope in its established shutdown order. No adapter path directly revokes a registry-owned capability scope.
### D11. No registry specialization for Lua providers

The Lua-backed provider uses the exact same `ChannelImplementationProviderRegistry`, `ChannelRuntimeRegistry`, and `RuntimeGenerationInvocationGate` paths as Kotlin built-in providers. There is no:
- Special registry branch for Lua
- Separate `LuaRuntimeRegistry`
- Override in `RuntimeGenerationInvocationGate` for Lua vs Kotlin
- Provider-descriptor feature flag indicating "is Lua"

The provider descriptor declares capabilities just like any Kotlin descriptor. The runtime construction calls `provider.constructRuntime(request)` which, for the Lua provider, creates the Lua state and adapter. Everything downstream—readiness projection, replacement sequencing, shutdown, capability acquisition—is identical to the Kotlin provider path.

This is possible because `GenerationExecutionContext` is provider-neutral and the `integrate-lua-actor-runtime` change already made the invocation boundary distinguish host-admitted callbacks from in-actor continuation scheduling. The Lua adapter simply participates in the same framework.

### D12. Multiple independent same-provider instances

Each `constructRuntime` call for a distinct instance ID receives a fresh `GenerationExecutionContext` with its own lifecycle authority. Two instances of the Lua provider share no Lua state, no module cache, no spawned task pools, no timer slots, and no capability leases.

This is enforced by:
- `GenerationExecutionContext` being per-construction with separate lifecycle, timer, and task admission scope
- The actor runtime creating a fresh Lua state per generation (D5 from the `integrate-lua-actor-runtime` design)
- The registry assigning independent runtime generations per instance, each with its own invocation gate and capability scope identity
### D13. Verification: black-box Lua source fixtures

Verification uses immutable Lua source fixtures loaded through the real provider/registry/actor path. Each fixture is an `ImmutableProgramImage` with its own source map and entry point. Tests construct the Lua provider's adapter, register it with a `ChannelImplementationProviderRegistry`, create catalogue definitions referencing it, and exercise the full reconciliation → construction → activation → event dispatch → replacement → shutdown cycle.

1. **Well-formed plugin**: valid entry module returning callback table with required `startup`; exercises construction, activate(startup), refreshReadiness, prepareInput, handleSos, close through the adapter.
2. **Plugin that spawns background work from startup**: calls `subspace.runtime.spawn()` in `startup`; spawned task calls `sleep()` and exercises async yield/resume lifecycle with `(true, nil)` success convention.
3. **Plugin with periodic background poll**: spawns a loop with `sleep`; validates that the generation close terminates the loop silently (coroutine discarded, no resume, no error delivered).
4. **Plugin that logs across lifecycle phases**: validates `subspace.log.info`, `subspace.log.warn`, etc. with structured table payloads; verifies non-blocking behavior and typed rejection of invalid payloads.
5. **Plugin using `require`**: has a multi-module source map with non-reserved names; validates module loading, caching, and isolation across instances.
6. **Plugin that rejects input at acceptance**: omits `handle_input` callback; validates `ChannelInputAcceptance.Refused` with reason `"input not supported"`.
7. **Plugin that returns terminal failed on capture**: `handle_input` returns `{ error = { code = "E_BUSY", detail = "..." } }`; validates `ChannelExecutionStatus.FAILED` snapshot transition.
8. **Plugin with readiness projection**: provides `handle_readiness` returning `{ ready = true }` and `handle_input`; validates that `prepareInput` accepts after `refreshReadiness` projects ready, and refuses when readiness is not ready.
9. **Plugin with pending sleep after generation close**: spawns a long-running `sleep()` then generation closes; validates that the suspended coroutine is silently discarded (no resume, no cancellation value — actor tokens invalidated, Lua state closed).
10. **Two plugin instances from the same provider**: independent states, independent spawned tasks, independent module caches, independent readiness projections.
11. **Replacement with pending sleep drain**: generation G has spawned task with pending `sleep`; H is published only after G's state is closed (coroutines discarded, no stale work continues).

Tests run both on JVM (with a fake/IoC-dispatched kernel bridge) and on Android device (with the real JNI kernel) to confirm end-to-end behavior. The JVM tests verify the adapter logic, lifecycle, and capability interactions; the Android device tests verify the kernel bridge integration under real concurrency and allocator policy.

Fixture verification asserting compatibility failure (D8) tests that an API version mismatch produces a typed failure outcome before any Lua state is created — no entry module loading, no callback validation, no startup call. This fixture does not use a valid entry module; it uses a program image with a deliberately incompatible metadata version.


### D14. No production provider registration

The Lua provider is defined and available for testing but is **not registered** in production startup. The `BuiltInChannelDescriptors` object and `PttForegroundService` provider-registry composition (where built-in providers are wired) do not include the Lua provider. Ordinary application startup creates no Lua state. The provider is initialized only when a test or a future integration change explicitly registers it.

This is achieved by:
- The provider class being in an internal package with no `BuiltInChannelDescriptors` entry.
- No dormant configuration flag or feature gate: there is simply no production registration callsite. An integration that needs a Lua provider must explicitly call the registry's `register` with the Lua descriptor.

## Risks / Trade-offs
- **[Risk] Plugin program may call `subspace.runtime.sleep` with an extremely large delay.** → The runtime rejects values above the configured maximum timer duration with `E_INVALID_ARGUMENT` (out of range). Zero is clamped to the host's minimum timer tick. The actor's instruction hook bounds the callback execution regardless. All numeric limits are host-configurable and non-normative.

- **[Risk] Plugin program may exhaust the concurrent sleep/timer slot limit.** → The per-actor pending sleep count is bounded by an internal configurable maximum. Exceeding it returns `(nil, { error = "E_BUSY" })` to the sleeping coroutine.

- **[Risk] Plugin program may spam `subspace.log` with high-frequency messages.** → Rate-limited: excessive entries from one actor within a window are silently dropped. The host does not block the caller or allocate growing buffers for dropped entries. Rate limits and buffer sizes are host-configurable and non-normative.

- **[Risk] A plugin that calls `spawn` for many background tasks may overwhelm the ready queue.** → The spawn count is bounded by an internal configurable maximum. Exceeding it returns `(nil, { error = "E_BUSY" })` from the spawn call (no silent drop).

- **[Risk] The entry module's `handle_input` returns an unexpected value.** → The runtime validates the return value at runtime (fail-closed). If the value is not a table, or lacks the expected shape, the input is recorded as failed with a typed invalid-outcome error.

- **[Risk] The plugin's `require` shim is called with a module name that contains path-traversal characters.** → The source map keys are validated at image construction to match `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*`. The require shim does a lookup-only; no path manipulation or filesystem access occurs. There is no traversal risk.

- **[Risk] The `GenerationExecutionContext` timer or task authority is used after the generation closes.** → Typed admission returns `Rejected(CLOSED)` rather than conflating closure with saturation. Already-accepted timer callbacks are suppressed after close, and the adapter's atomic lifecycle prevents post-close Lua entry.

- **[Risk] A synchronous callback (startup, handle_lifecycle, handle_input, handle_sos, handle_readiness) attempts to yield.** → The actor runtime detects the yield attempt and produces a typed callback-error outcome. The callback contract is documented as non-yielding; detection is a defense-in-depth measure.

- **[Trade-off] This change does not deliver installable plugins, a package format, or a writing plugin-author guide.** → Accepted: the public boundaries, program-image contract, module API, and verification harness are defined first so that the subsequent package/install/distribution change can depend on a stable v1 contract.

- **[Trade-off] Plugin-writable storage is not part of v1.** → Accepted: v1 plugins can use `subspace.runtime.spawn` for background work and `sleep` for polling, but any durable plugin state must be designed and added as a future capability or API version.

- **[Trade-off] The `subspace.runtime.spawn` model admits tasks synchronously with no cancellation handle, inter-coroutine channel, or structured communication.** → Accepted: v1 establishes the spawn mechanism with `(true,nil)` or `(nil,error)` return. A future API version may add richer patterns.

- **[Risk] A replacement that creates a new Lua state (fresh `GenerationExecutionContext`, fresh actor) may be slower than reusing an existing state.** → Accepted: correctness (drain-before-ready, no cross-generation state bleeding) outranks startup latency. The kernel's state creation and source loading is bounded and measured; the replacement latency is dominated by the predecessor drain, not the new state creation.


**Prerequisite dependency.** This change modifies actor-policy behaviour introduced and stabilised by the completed `integrate-lua-actor-runtime` change (specifically `ActorPolicy`, `ActorTaskScope`, `ActorRuntime.yieldOperation`). That change MUST be synced and archived before this change applies its actor delta. The changes below are additive overrides — they do not alter or remove the existing `ActorPolicy` defaults; they introduce a plugin-specific policy variant for the Lua channel adapter.

## Migration Plan

1. Sync and archive `integrate-lua-actor-runtime`; then extend actor task and yielded-operation policy with explicit no-whole-task-deadline and operation-specific-deadline modes. Preserve existing defaults for every non-Lua actor caller.
2. Define sealed `GenerationExecutionContext`, extend `ChannelRuntimeConstructionRequest` without defaults or compatibility constructors, populate the context in `ChannelRuntimeRegistry`, and migrate every direct request constructor.
3. Add provider-neutral typed compatibility failure to `ChannelProviderError`; ensure Lua/API mismatches project this type before actor or Lua-state creation.
4. Define `ImmutableProgramImage`, execution requirements, immutable source-map snapshotting, canonical-name and source-text validation, configured bounds, and deterministic validation failures.
5. Extend the retained kernel/actor boundary for restricted-global initialization, host-module injection, source-map chunk loading, raw callback-table inspection, normalized value conversion, and protected synchronous callback invocation. Do not introduce a second bridge or proof-only path.
6. Implement the replacement `require` resolver: reserved host modules first, immutable source map second, typed not-found otherwise; enforce per-generation cache, cycle detection, nil-to-true caching, and no filesystem/native fallback.
7. Implement `subspace.runtime`, `subspace.channel`, and `subspace.log` with exact v1 constants, context restrictions, bounded normalized values, a generation-scoped log buffer, and stable error strings.
8. Implement `sleep` with overflow-safe `requested_delay + slack` deadlines and exactly-once races among timer completion, deadline expiry, explicit live cancellation, and close. Implement `spawn` with synchronous admission, activation staging, bounded concurrency, and no cumulative task wall timeout.
9. Implement `LuaAdapterRuntime`, callback-table validation, serialized activation, cached readiness, host-owned capture metadata delivery, strict input outcome mapping, local-contained SOS/readiness errors, snapshot transitions, and idempotent close.
10. Implement the Lua provider descriptor and `ChannelImplementationProvider` around a host-supplied program image. Exercise it only through explicit test registration; add no production registration or catalogue entry.
11. Add black-box JVM fixtures through the real provider registry, runtime registry, generation context, capability scope, actor, replacement, and shutdown paths. Cover module isolation, callback contracts, normalized values, `require`, proactive timers while unselected, deadline races, task lifetime, stale suppression, compatibility, image bounds, logging, input outcomes, and ordinary Kotlin-provider regression.
12. Add Android device conformance through the real JNI kernel for restricted module loading, callback dispatch, spawn/sleep suspension, instruction interruption, allocator denial, replacement, and deterministic close. Record provenance plus observed distributions without promoting device measurements into public limits.
13. Verify ordinary production startup creates no Lua provider, actor, or state; run release-equivalent APK verification and the established physical-device evidence flow.

**Rollback:** Remove the context extension, program-image types, public module implementations, adapter/provider, actor-policy extensions, fixtures, and conformance tests. No persisted data, catalogue definitions, provider registration, permission, or user-visible behavior is migrated, so rollback is code removal.
## Open Questions

None. The following architectural decisions are resolved above:

- Runtime/API version structure and reporting (D1).
- Standard-library scope (D2).
- Module loading from immutable source map (D3).
- Entry-module callback table validation (D4).
- Public module API surfaces (D5).
- Async operation error model (D6).
- Input acceptance and terminal outcome flow (D7).
- Immutable program image format and construction (D8).
- Provider construction context extension with `GenerationExecutionContext` (D9).
- Adapter runtime shape and actor integration (D10).
- No registry specialization (D11).
- Multi-instance isolation (D12).
- Test fixture strategy and tooling (D13).
- No production provider registration (D14).

Implementation-level details (exact Kotlin package paths, internal config field names, the precise ring-buffer class name, and JVM fake-bridge wiring) are non-contractual and left to the implementation change.

**Internal policy numbers are non-normative and configurable.** Any numeric limits stated in this design (timer slot limit, spawn concurrency cap, ring buffer size, instruction hook interval, timer clamp bounds) are internal configuration defaults that the host may override at deployment or integration time. They are **not** part of the public API contract. Plugins must not assume any particular value; the only guarantee is that the limits exist and are enforced.
