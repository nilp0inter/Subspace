## Purpose

Defines Lua channel callbacks, activation, input, timers, and structured
logging exposed to host-supplied programs.

## Requirements

### Requirement: The entry module returns a validated callback table with one required non-yielding callback and optional event callbacks
A Lua program image SHALL designate exactly one entry module that, after loading, returns a Lua table. The runtime SHALL validate that the returned value is a plain table (no metatable). The table SHALL contain exactly one required entry point: a function at key `startup`. The `startup` callback SHALL take no arguments; the host SHALL NOT pass validated provider configuration, catalogue configuration, or any config object to `startup`. Declarative/plugin configuration exposure to Lua is a non-goal of this change. The generic provider SHALL still validate its (possibly empty or test) configuration through the standard provider-construction path; that validation does not produce a value delivered to plugin code. Additional keys SHALL be treated as optional event callbacks: `handle_lifecycle`, `handle_input`, `handle_sos`, and `handle_readiness`. The runtime SHALL validate recognized keys by identity string equality and SHALL NOT interpret non-string keys, metatable keys, prototype chains, or integer indices. When an optional key is absent or its value is nil, the runtime SHALL apply the documented neutral default for that callback (nil is equivalent to absent in Lua). When an optional key is present with a non-nil, non-function value (e.g. a number or string), construction SHALL fail with a typed invalid-callback-type error; the runtime SHALL NOT treat a non-function value as absent or silently discard it. Unrecognized keys in the returned table SHALL be silently ignored so that a future API version with additional callbacks does not break v1 entry modules. The callback table SHALL be the single interface through which the host invokes plugin code; no other global, metatable, or registry convention SHALL be used for host-to-Lua dispatch.

Every source-map module chunk evaluation — the entry module and every module loaded lazily via `require` — SHALL be synchronous and non-yielding. During evaluation the chunk MAY `require` Subspace modules (to read constants), call pure Lua functions, construct tables, define functions, and return values. The chunk MUST NOT invoke any host-provided callable — including `subspace.runtime.sleep`, `subspace.runtime.spawn`, `subspace.log.info` (and all other log levels), or any other call on a host-injected module — during its top-level evaluation. Any attempt to call a host-provided function during module chunk evaluation SHALL fail the module load or entry evaluation with a typed effect-call-during-load error, SHALL NOT cache a partial result, and the program SHALL NOT be activated. Host-provided effectful functions SHALL be invoked only from returned callback functions executing in authorized `startup`, event callback, or spawned background task contexts.

#### Scenario: Entry module returns a valid table with required startup callback
- **WHEN** the program image entry module executes and returns a table containing a function at key `startup`
- **THEN** the runtime SHALL accept the table as the program's public interface
- **AND** it SHALL NOT require `handle_lifecycle`, `handle_input`, `handle_sos`, or `handle_readiness` to be present

#### Scenario: Entry module returns a non-table value
- **WHEN** the entry module returns a number, string, boolean, nil, or function instead of a table
- **THEN** the runtime SHALL classify construction as failed with a typed entrypoint-validation error
- **AND** it SHALL NOT publish readiness
#### Scenario: Entry module returns a table with a metatable
- **WHEN** the entry module returns a table that has a metatable (including `__index`, `__call`, or any other metatable field)
- **THEN** the runtime SHALL produce a typed construction failure
- **AND** it SHALL NOT examine or interpret metatable entries
- **AND** it SHALL NOT publish readiness

#### Scenario: Entry module returns a table missing the required startup callback
- **WHEN** the returned table lacks the `startup` key or maps it to nil
- **THEN** the runtime SHALL produce a typed missing-callback error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Required startup callback is present with wrong type
- **WHEN** the returned table maps `startup` to a non-function value
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Optional callback key is present with wrong type
- **WHEN** the returned table has `handle_readiness` mapped to a number or string instead of a function
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT treat the incorrect value as absent or apply the neutral default
#### Scenario: Optional callback key is present with nil value
- **WHEN** the returned table has `handle_readiness` set to nil
- **THEN** the runtime SHALL treat the key as absent (nil is equivalent to absent in Lua)
- **AND** it SHALL apply the neutral default for the callback
- **AND** it SHALL NOT raise an error for the nil value

#### Scenario: Optional callback is absent and host uses neutral default
- **WHEN** the returned table lacks the `handle_readiness` key entirely
- **THEN** the host SHALL apply the neutral default: the channel SHALL be treated as not ready for PTT
- **AND** it SHALL NOT raise an error for the missing callback

#### Scenario: Entry module attempts a host-provided call during top-level evaluation
- **WHEN** the entry module calls `subspace.runtime.spawn`, `subspace.runtime.sleep`, `subspace.log.info`, or any other host-injected callable during top-level evaluation before returning the callback table
- **THEN** the runtime SHALL produce a typed construction failure with the specific error identifying the prohibited call
- **AND** no background task SHALL be admitted or queued
- **AND** the program SHALL NOT be activated

### Requirement: Event callbacks are synchronous, non-yielding, and context-restricted
Every event callback (`startup`, `handle_lifecycle`, `handle_input`, `handle_sos`, `handle_readiness`) SHALL be a synchronous, non-yielding function. The runtime SHALL NOT yield a coroutine during any event callback invocation. If a callback attempts to call a yielding operation such as `subspace.runtime.sleep` or `coroutine.yield` at any stack depth, the runtime SHALL treat the yield attempt as a callback contract violation and SHALL produce a typed callback-error outcome. The `startup` callback MAY call `subspace.runtime.spawn` to admit background coroutines that themselves MAY call yielding operations. Input, lifecycle, SOS, and readiness callbacks SHALL NOT use `spawn` in this version; an attempt SHALL produce a typed callback-error outcome with `E_INVALID_CONTEXT`.


For `startup`, `handle_lifecycle`, and `handle_sos`, a normal return other than a table with an `error` field SHALL be success or no-op according to the callback's role. A returned failure SHALL use exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}`. A table containing `error` with any other shape SHALL be a typed callback contract error rather than an application-supplied failure. `handle_input` and `handle_readiness` SHALL use their separately defined result shapes.
#### Scenario: Startup callback admits a background spawn
- **WHEN** the `startup` callback calls `subspace.runtime.spawn(function() ... end)` and returns
- **THEN** the runtime SHALL synchronously admit the background task
- **AND** the spawned task SHALL be queued but SHALL NOT begin executing until after the activation sequence completes
- **AND** startup itself SHALL NOT have yielded

#### Scenario: Input callback attempts to yield
- **WHEN** `handle_input` calls `subspace.runtime.sleep(1.0)` or `coroutine.yield()` at any stack depth
- **THEN** the runtime SHALL treat the yield attempt as a callback contract violation
- **AND** it SHALL produce a typed callback-error outcome for that input
- **AND** the input SHALL be recorded as failed

#### Scenario: Input callback attempts to spawn
- **WHEN** `handle_input` calls `subspace.runtime.spawn(function() ... end)`
- **THEN** the runtime SHALL treat the spawn as a callback contract violation in this version
- **AND** it SHALL produce a typed callback-error outcome with `E_INVALID_CONTEXT`

### Requirement: Activation sequence is serialized and fail-closed
The host SHALL invoke `startup()` once (with no arguments) during authorized activation, after construction and callback-table validation, and immediately before readiness publication, as a synchronous, non-yielding call. After `startup` returns successfully, the host SHALL invoke `handle_lifecycle` with `{event = "ready"}` synchronously, immediately before publishing the channel as Ready. The `startup` and `handle_lifecycle` callbacks SHALL be serialized: no background task admitted via `spawn` during `startup` SHALL begin executing between `startup` and `handle_lifecycle`. If either `startup` or `handle_lifecycle` throws an error, returns an error table, or attempts to yield, the host SHALL fail activation, SHALL discard and cancel all startup-admitted queued tasks, SHALL NOT publish the channel as Ready, and SHALL return a typed activation-failure result. Background tasks admitted via `spawn` SHALL become runnable only after the complete activation sequence succeeds.

#### Scenario: Successful activation publishes Ready
- **WHEN** `startup()` returns successfully and `handle_lifecycle` is absent or returns successfully
- **THEN** the host SHALL publish the channel as Ready immediately after the lifecycle callback step completes
- **AND** background tasks admitted via `spawn` during `startup` SHALL become runnable only after Ready publication

#### Scenario: Startup failure prevents Ready and discards queued tasks
- **WHEN** `startup` throws an error, returns `{error = {code = <non-empty string>, detail = <non-empty string>}}`, returns a malformed table containing `error`, or attempts to yield
- **THEN** the host SHALL fail activation with a typed activation-failure result
- **AND** it SHALL NOT publish the channel as Ready
- **AND** it SHALL discard and cancel all tasks admitted via `spawn` during `startup`
- **AND** no queued task SHALL execute

#### Scenario: Lifecycle-ready callback failure prevents Ready and discards queued tasks
- **WHEN** `handle_lifecycle` throws an error, returns `{error = {code = <non-empty string>, detail = <non-empty string>}}`, returns a malformed table containing `error`, or attempts to yield during the activation sequence
- **THEN** the host SHALL fail activation with a typed activation-failure result
- **AND** it SHALL NOT publish the channel as Ready
- **AND** it SHALL discard and cancel all tasks admitted via `spawn` during `startup`
- **AND** no queued task SHALL execute

### Requirement: `subspace.channel` provides event constants for callback argument matching
The `subspace.channel` module SHALL be a host-injected, reserved module that plugins can `require` to access channel event type constants. The plugin SHALL NOT override or shadow the injected `subspace.channel` module with source-map content. In v1 the module SHALL define only three string constants: `LIFECYCLE_READY = "ready"`, `CAPTURE_COMPLETE = "capture"`, and `SOS_TRIGGERED = "sos"`. There SHALL be no callable functions on the module in v1; the plugin's callback table is the sole interface for channel lifecycle, input, SOS, and readiness projection. Callbacks receive event tables whose `event` string field corresponds to one of these constants.

#### Scenario: Plugin reads event constants
- **WHEN** a callback reads `subspace.channel.LIFECYCLE_READY`, `subspace.channel.CAPTURE_COMPLETE`, or `subspace.channel.SOS_TRIGGERED`
- **THEN** it SHALL receive the exact strings `"ready"`, `"capture"`, and `"sos"` respectively

#### Scenario: Lifecycle callback is invoked after startup before Ready publication
- **WHEN** the host has completed `startup` successfully and is about to publish the channel as Ready, and the plugin has supplied `handle_lifecycle`
- **THEN** the host SHALL invoke the callback with a table containing `event` set to `"ready"` (matching `subspace.channel.LIFECYCLE_READY`)
- **AND** the callback SHALL receive no platform objects, actor identities, or audio data
- **AND** the host SHALL publish Ready immediately after the callback returns successfully

#### Scenario: Lifecycle callback is absent (neutral default)
- **WHEN** the callback table lacks `handle_lifecycle`
- **THEN** the host SHALL apply the neutral default: lifecycle events produce no effect
- **AND** the host SHALL proceed directly from `startup` success to Ready publication

#### Scenario: Readiness callback projects channel readiness
- **WHEN** the host calls `refreshReadiness` and the plugin has supplied `handle_readiness`
- **THEN** the host SHALL invoke the callback synchronously
- **AND** the callback SHALL return a table with a `ready` boolean field; absent or nil `ready` SHALL be cached as not ready
- **AND** when `handle_readiness` is absent, the neutral default SHALL be not ready
- **AND** if `handle_readiness` throws an error, returns an error table, returns a non-table, returns a table whose non-nil `ready` field is not boolean, or attempts to yield, the host SHALL cache readiness as not ready and SHALL record a local typed failure for that readiness refresh

#### Scenario: Input is accepted synchronously based on cached readiness
- **WHEN** the cached readiness projection (obtained from prior `handle_readiness` or its neutral default) is `ready = true` and the callback table contains a valid `handle_input` function
- **THEN** the host SHALL accept the input synchronously without making a new Lua call at input-acceptance time
- **AND** capture SHALL proceed

#### Scenario: Input is rejected because readiness is false
- **WHEN** the cached readiness projection is not ready or `handle_input` is absent
- **THEN** the host SHALL refuse the input with a typed not-ready or input-not-supported result
- **AND** capture SHALL NOT start

#### Scenario: Input capture lifecycle maps to snapshot states
- **WHEN** the host starts capture, the snapshot SHALL enter RECORDING; when capture completes and is released for processing, the snapshot SHALL enter PROCESSING before `handle_input` is invoked
- **THEN** the host SHALL invoke `handle_input` with a table having `event` set to `"capture"` (matching `subspace.channel.CAPTURE_COMPLETE`), a `session` identifier string, and a `metadata` table of bounded scalars
- **AND** the table SHALL NOT contain an audio handle, raw PCM, or any host audio object
- **AND** the callback SHALL return synchronously without yielding

#### Scenario: Input callback returns success
- **WHEN** `handle_input` returns `{ok = true}`
- **THEN** the host SHALL record the input as SUCCESS in the runtime snapshot
- **AND** it SHALL NOT attempt further callbacks for that input
- **AND** the host SHALL release any transient audio resources for the capture
- **AND** the input target SHALL return `ChannelInputResult.None` (no playback in v1)

#### Scenario: Input callback returns an error outcome
- **WHEN** `handle_input` returns `{error = {code = "E_CAPTURE_FAILURE", detail = "processing failed"}}`
- **THEN** the host SHALL record the input as FAILED in the runtime snapshot
- **AND** it SHALL NOT retry the input automatically
- **AND** the input target SHALL return `ChannelInputResult.None` (no playback in v1)

#### Scenario: Input callback returns an ambiguous or malformed outcome table
- **WHEN** `handle_input` returns a table containing both `ok` and `error`, an `ok` value other than exactly `true`, or an `error` value that is not a table with non-empty string `code` and `detail` fields
- **THEN** the host SHALL reject the whole outcome as a contract error
- **AND** it SHALL record the input as FAILED with a typed invalid-outcome error
- **AND** the input target SHALL return `ChannelInputResult.None` (no playback in v1)

#### Scenario: Input callback returns an unrecognized outcome table
- **WHEN** `handle_input` returns `{action = "confirm"}`, `{weird_field = true}`, a non-table value, or a table without the expected shape
- **THEN** the host SHALL reject the outcome as a contract error
- **AND** it SHALL record the input as FAILED with a typed invalid-outcome error
- **AND** the input target SHALL return `ChannelInputResult.None` (no playback in v1)

#### Scenario: Input callback throws or yields
- **WHEN** `handle_input` throws an error or attempts to yield
- **THEN** the host SHALL record the input as FAILED in the runtime snapshot
- **AND** the error SHALL be local-contained (logged, not propagated to crash the actor)
- **AND** the input target SHALL return `ChannelInputResult.None` (no playback in v1)

#### Scenario: Input capture is cancelled by the host
- **WHEN** the host cancels an in-progress capture before release
- **THEN** the snapshot SHALL enter IDLE
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: Input capture fails due to host capture error
- **WHEN** the host's capture subsystem fails before release
- **THEN** the snapshot SHALL enter FAILED
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: SOS callback is invoked as fire-and-forget
- **WHEN** an SOS event occurs for the channel and the plugin has supplied `handle_sos`
- **THEN** the host SHALL invoke the callback with a table containing `event` set to `"sos"` (matching `subspace.channel.SOS_TRIGGERED`) and host-domain values
- **AND** the callback's normal return value SHALL be ignored (SOS is fire-and-forget)
- **AND** if the callback throws an error, returns an error table, or attempts to yield, the error SHALL be local-contained and logged as a diagnostic, SHALL NOT mutate the runtime snapshot, and SHALL NOT crash the actor or fail the generation
- **AND** the callback SHALL NOT produce a terminal SOS outcome classification exposed to the host

#### Scenario: SOS callback is absent (neutral default)
- **WHEN** the callback table lacks `handle_sos`
- **THEN** the host SHALL apply the neutral default: SOS events are unhandled
- **AND** no Lua callback SHALL be invoked for SOS events

#### Scenario: No optional event callbacks are supplied (proactive-only plugin)
- **WHEN** the callback table contains only the required `startup` entry and no `handle_lifecycle`, `handle_input`, `handle_sos`, or `handle_readiness`
- **THEN** the plugin SHALL be considered a proactive-only plugin
- **AND** the host SHALL keep the plugin live: background tasks admitted via `spawn` SHALL execute and timers SHALL fire
- **AND** the channel SHALL NOT be available for PTT input selection
- **AND** readiness SHALL default to not ready

### Requirement: `subspace.runtime` provides runtime identity and cooperative timer operations with validated arguments
The `subspace.runtime` module SHALL be a host-injected, reserved module. It SHALL expose exact runtime and API version constants: `LUA_VERSION` as `"Lua 5.4"`, `LUA_RELEASE` as `"5.4.8"`, and `API_VERSION` as `"subspace-lua-v1"`. There SHALL be no `version()` function, no integer runtime version, and no integer API version. The module SHALL provide cooperative timer operations: `sleep(seconds)` to suspend the current coroutine for at least the specified duration (usable only from runtime-managed spawned background tasks, not from event callbacks, entry evaluation, or plugin-created child coroutines), and `spawn(function)` to synchronously admit a background task (usable only from `startup` and runtime-managed spawned tasks, not from event callbacks, entry evaluation, or plugin-created child coroutines).

Both `sleep` and `spawn` SHALL return `(value, nil)` on success or `(nil, error_table)` on failure; the plugin SHALL NOT receive an operation token, coroutine reference, userdata, or handle from either call. A `sleep` call SHALL resume after the requested delay regardless of whether the channel is selected or unselected. A `spawn` call SHALL synchronously admit the task and return immediately without blocking the caller. `spawn` SHALL accept only a function argument; a non-function argument SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`. `sleep` SHALL accept only a finite number >= 0; negative, NaN, infinity, or above-maximum values SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`. Exhaustion of per-generation limits SHALL return `(nil, {error = "E_BUSY"})`. Invalid context SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`. The runtime SHALL NOT expose `os.execute`, `os.tmpname`, `io.*`, `file.*`, `debug.*`, `loadfile`, or `string.dump`.

#### Scenario: Program queries runtime version constants
- **WHEN** a callback reads `subspace.runtime.LUA_VERSION`
- **THEN** it SHALL receive the exact string `"Lua 5.4"`
- **AND** reading `subspace.runtime.LUA_RELEASE` SHALL return the exact string `"5.4.8"`
- **AND** reading `subspace.runtime.API_VERSION` SHALL return the exact string `"subspace-lua-v1"`

#### Scenario: Startup synchronously admits a background spawn
- **WHEN** the `startup` callback calls `subspace.runtime.spawn(function() ... end)`
- **THEN** the runtime SHALL synchronously admit the background task
- **AND** the spawn call SHALL return `(true, nil)`
- **AND** the background coroutine SHALL be queued and SHALL begin executing only after the activation sequence completes successfully

#### Scenario: Spawn fails because the task-admission limit is reached
- **WHEN** a callback calls `subspace.runtime.spawn(function() ... end)` and the per-generation task-admission limit is reached
- **THEN** the spawn call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn fails because the argument is not a function
- **WHEN** a callback calls `subspace.runtime.spawn(42)` or `subspace.runtime.spawn("bad")`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** no task SHALL be admitted

#### Scenario: Spawn called from invalid context
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `subspace.runtime.spawn`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no task SHALL be admitted

#### Scenario: Spawned task calls sleep and resumes
- **WHEN** a spawned background task calls `subspace.runtime.sleep(5.0)`
- **THEN** the host SHALL suspend the coroutine and set a timer for at least 5 seconds
- **AND** after the timer fires, the host SHALL resume the coroutine
- **AND** the sleep call SHALL return `(true, nil)`

#### Scenario: Sleep fails because the timer limit is reached
- **WHEN** a spawned background task calls `subspace.runtime.sleep(5.0)` and the per-generation timer slot limit is reached
- **THEN** the sleep call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the coroutine SHALL NOT be suspended

#### Scenario: Sleep fails because the argument is invalid
- **WHEN** a spawned background task calls `subspace.runtime.sleep(-1.0)` or `subspace.runtime.sleep(1e6)` (above maximum)
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** the coroutine SHALL NOT be suspended and no timer SHALL be started

#### Scenario: Sleep fails because context is invalid
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `subspace.runtime.sleep`
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no timer SHALL be started and no coroutine suspension SHALL occur

#### Scenario: Sleep at zero clamps to minimum tick
- **WHEN** a spawned background task calls `subspace.runtime.sleep(0)`
- **THEN** the host MAY clamp the duration to its minimum timer tick
- **AND** the call SHALL proceed as a valid sleep and SHALL return `(true, nil)` after the timer fires

#### Scenario: Sleep fires while the channel is unselected
- **WHEN** a spawned background task sets a timer and the channel is not the active selection when the timer fires
- **THEN** the runtime SHALL resume the background task's coroutine regardless of selection state
- **AND** it SHALL NOT require the channel to be selected for background work to progress

#### Scenario: Program accesses restricted standard library
- **WHEN** a callback attempts to call `os.execute`, `io.open`, `debug.getregistry`, `loadfile`, or `string.dump`
- **THEN** the runtime SHALL raise an error that the function is disabled or nil
- **AND** the callback SHALL NOT execute a restricted operation

### Requirement: `subspace.log` provides bounded structured logging with typed validation
The `subspace.log` module SHALL be a host-injected, reserved module. It SHALL expose four named log functions: `debug`, `info`, `warn`, and `error`. Each function SHALL accept a single structured payload table (not a level-plus-payload pair). Log calls SHALL be non-blocking, non-yielding, and SHALL be rate-limited such that an excessive number of log entries from one actor does not exhaust host memory. The log entry SHALL include the channel instance identifier, runtime generation, a timestamp, the level, and the provided payload. Log payloads SHALL be normalized Lua tables without executable code, file handles, userdata, or coroutine references. Log functions are usable from the `startup` callback and all subsequent event callbacks and spawned background tasks.

Each log function SHALL return `(true, nil)` when the entry is recorded or silently rate-dropped within the bounded actor window, and `(nil, {error = "E_INVALID_VALUE"})` when the whole payload is rejected as invalid. The host SHALL NOT recursively log an invalid payload, SHALL NOT strip or partially write fields, and SHALL NOT suspend, fail, or crash the calling coroutine when a payload is rejected.

Every record actually accepted into the actor's bounded log buffer SHALL be offered exactly once to a bounded host observability sink. The sink SHALL map the Lua level to the corresponding host log level, use a host-owned plugin tag, and serialize only the host-attributed instance ID, runtime generation, timestamp, and normalized payload. It SHALL write no throwable and SHALL NOT accept a plugin-supplied tag, timestamp, instance identity, generation, or log level. A record silently dropped by the actor rate limit, rejected by value validation, or received after its generation has closed SHALL NOT enter host observability. Host-sink saturation or persistence failure SHALL remain bounded, SHALL NOT block or re-enter Lua, and SHALL NOT change the result already returned to the plugin.

#### Scenario: Program writes a structured log entry
- **WHEN** a callback executes `subspace.log.info({message = "timer fired", duration_ms = 1520})`
- **THEN** the runtime SHALL record a structured log entry associated with the calling channel instance
- **AND** the entry SHALL include the instance identifier, runtime generation, timestamp, level `info`, and the supplied payload fields
- **AND** the function SHALL return `(true, nil)`
- **AND** the accepted record SHALL be offered exactly once to the bounded host observability sink

#### Scenario: Log payload contains disallowed types
- **WHEN** a callback calls `subspace.log.warn({handle = some_userdata})`
- **THEN** the runtime SHALL reject the entire log call with a typed validation result
- **AND** the function SHALL return `(nil, {error = "E_INVALID_VALUE"})`
- **AND** it SHALL NOT write a partial or incomplete actor or host log entry
- **AND** it SHALL NOT recursively log the invalid payload
- **AND** it SHALL NOT suspend, fail, or crash the calling coroutine

#### Scenario: Log rate limit is exceeded
- **WHEN** a callback issues log entries faster than the bounded actor rate limit allows
- **THEN** the runtime SHALL silently drop subsequent log entries within that actor's current window
- **AND** the function SHALL return `(true, nil)` for dropped entries
- **AND** it SHALL NOT forward dropped entries to host observability
- **AND** it SHALL NOT block the caller or allocate growing buffers for dropped entries

#### Scenario: Accepted record is mapped to host observability
- **WHEN** an accepted Lua log record reaches the host sink while its runtime generation remains live
- **THEN** the sink SHALL preserve its semantic level and host timestamp and SHALL encode its normalized payload under a host-owned plugin tag
- **AND** the persistent and reactive log surfaces SHALL receive at most one corresponding entry

#### Scenario: Generation closes before a pending record is published
- **WHEN** a runtime generation closes or is replaced before its pending accepted record can enter the host sink
- **THEN** the sink SHALL suppress the stale record or retain its original predecessor attribution according to the accepted-publication boundary
- **AND** SHALL never attribute that record to the successor generation

#### Scenario: Host observability sink is saturated
- **WHEN** the bounded host sink cannot accept another plugin record immediately
- **THEN** the host SHALL drop or coalesce the projection under bounded policy and record only bounded host-owned loss diagnostics
- **AND** SHALL NOT block Lua, grow an overflow queue, retry without bound, or report a second result to the plugin
