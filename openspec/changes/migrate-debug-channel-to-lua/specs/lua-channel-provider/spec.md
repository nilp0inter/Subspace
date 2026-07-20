## MODIFIED Requirements

### Requirement: The entry module returns a validated callback table with one required non-yielding callback and optional event callbacks
A Lua program image SHALL designate exactly one entry module that, after loading, returns a plain Lua table with no metatable. The table SHALL contain exactly one required entry point: a function at key `startup`. During authorized activation the host SHALL invoke that function with exactly one detached configuration snapshot table containing `schema_version` and a `values` table of validated flat scalar values. Lua function arity is not introspected; the host SHALL always supply the snapshot and SHALL NOT provide a no-argument invocation path, legacy configuration object, or fallback callback. The generic provider SHALL validate configuration through the standard provider-construction path before producing the snapshot. Optional recognized callbacks are `handle_lifecycle`, `handle_input`, `handle_sos`, and `handle_readiness`; an absent or nil optional callback receives its documented neutral default, while a present non-function recognized callback fails construction. Unrecognized callback-table keys SHALL be ignored.

Every source-map module chunk evaluation—the entry module and every module loaded lazily via `require`—SHALL be synchronous and non-yielding. During evaluation the chunk MAY require Subspace modules to read constants, call pure Lua functions, construct tables, define functions, and return values. It MUST NOT invoke any host-provided callable, including `subspace.runtime.sleep`, `subspace.runtime.spawn`, `subspace.log` functions, or any callable on another host-injected module. A host-call attempt during module evaluation SHALL fail the complete module load or entry evaluation with a typed effect-call-during-load error, SHALL NOT cache a partial result, and SHALL NOT admit work or produce a host effect.

#### Scenario: Entry module returns a valid table with required startup callback
- **WHEN** the program image entry module executes and returns a plain table containing a function at key `startup`
- **THEN** the runtime SHALL accept the table as the program's public interface
- **AND** it SHALL NOT require `handle_lifecycle`, `handle_input`, `handle_sos`, or `handle_readiness` to be present

#### Scenario: Entry module returns a non-table value
- **WHEN** the entry module returns a number, string, boolean, nil, or function instead of a table
- **THEN** the runtime SHALL classify construction as failed with a typed entrypoint-validation error
- **AND** it SHALL NOT publish readiness

#### Scenario: Entry module returns a table with a metatable
- **WHEN** the entry module returns a table that has a metatable
- **THEN** the runtime SHALL produce a typed construction failure
- **AND** it SHALL NOT examine or interpret metatable entries or publish readiness

#### Scenario: Entry module omits required startup callback
- **WHEN** the returned table lacks the `startup` key or maps it to nil
- **THEN** the runtime SHALL produce a typed missing-callback error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Required startup callback has wrong type
- **WHEN** the returned table maps `startup` to a non-function value
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Optional callback has wrong type
- **WHEN** a recognized optional callback key maps to a non-nil non-function value
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT treat the value as absent or apply the neutral default

#### Scenario: Optional callback is absent or nil
- **WHEN** a recognized optional callback key is absent or maps to nil
- **THEN** the runtime SHALL apply that callback's documented neutral default
- **AND** it SHALL NOT raise an error for the missing callback

#### Scenario: Host invokes startup with validated configuration
- **WHEN** authorized activation invokes the required `startup` function
- **THEN** the host SHALL pass exactly one detached configuration snapshot argument
- **AND** it SHALL NOT retry the invocation without arguments or expose a legacy configuration object when the callback ignores or mishandles that argument

#### Scenario: Entry module attempts host call during top-level evaluation
- **WHEN** the entry module invokes any host-injected callable before returning the callback table
- **THEN** the runtime SHALL fail construction with the typed effect-call-during-load outcome
- **AND** no background task, timer, log, capability operation, or partial module result SHALL be admitted or retained

### Requirement: Event callbacks have explicit yield contexts and exact terminal results
`startup`, `handle_lifecycle`, `handle_sos`, and `handle_readiness` SHALL execute synchronously and SHALL NOT suspend across the host boundary. The host SHALL reject a raw `coroutine.yield` escaping any of those callback stacks as a typed callback-contract failure. Outside source/module evaluation, a context-restricted host operation called from an ineligible callback SHALL instead return its normal `(nil, {error = "E_INVALID_CONTEXT"})` pair before suspension or effect; that denied call SHALL NOT by itself fail the callback if Lua handles or ignores the result. `startup` MAY call `subspace.runtime.spawn` to admit managed background tasks. Lifecycle, SOS, readiness, and input callbacks SHALL receive `E_INVALID_CONTEXT` from `spawn` without task admission.

`handle_input` SHALL execute in a host-managed yield-capable coroutine. It MAY invoke only the semantic audio operations authorized by `lua-audio-api`; an authorized call MAY suspend the coroutine, release the serialized Lua execution slot, and resume it with the operation result. `subspace.runtime.sleep`, `subspace.runtime.spawn`, and a raw escaping `coroutine.yield` are not authorized input suspension paths. Sleep and spawn SHALL return `E_INVALID_CONTEXT` without suspending or admitting work; a raw escaping yield SHALL fail the callback contract.

For `startup`, `handle_lifecycle`, and `handle_sos`, a normal return without an `error` field SHALL be success or no-op according to the callback role. An application failure SHALL be exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}`; any other table containing `error` SHALL be a typed callback-contract failure. `handle_input` and `handle_readiness` SHALL use their separately defined exact result shapes.

#### Scenario: Startup admits a background task
- **WHEN** `startup` calls `subspace.runtime.spawn(function() ... end)` and returns successfully
- **THEN** the runtime SHALL synchronously admit and queue the task
- **AND** the task SHALL NOT begin until the activation sequence completes
- **AND** startup itself SHALL NOT have yielded

#### Scenario: Input callback calls spawn
- **WHEN** `handle_input` calls `subspace.runtime.spawn`
- **THEN** the call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` without admitting or executing the task
- **AND** the callback MAY handle that result and continue

#### Scenario: Input callback uses semantic audio operation
- **WHEN** `handle_input` invokes an authorized yielding semantic audio operation
- **THEN** the host SHALL suspend that callback coroutine and release the execution slot
- **AND** it SHALL resume the callback exactly once with the operation terminal result while the execution owner remains current
- **AND** the callback result SHALL be evaluated only after terminal completion

#### Scenario: Input callback returns success
- **WHEN** `handle_input` returns exactly `{ok = true}`
- **THEN** the host SHALL record input SUCCESS and SHALL NOT invoke another callback for that input
- **AND** it SHALL release every remaining transient resource owned by that invocation

#### Scenario: Input callback returns application failure
- **WHEN** `handle_input` returns exactly `{error = {code = "E_CAPTURE_FAILURE", detail = "processing failed"}}`
- **THEN** the host SHALL record input FAILED without automatic replay

#### Scenario: Input callback returns malformed result
- **WHEN** `handle_input` returns a non-table, unknown key, both `ok` and `error`, an `ok` value other than `true`, or an error object missing non-empty string `code` or `detail`
- **THEN** the host SHALL reject the complete result as a typed callback-contract failure
- **AND** it SHALL record input FAILED without automatic replay

#### Scenario: Input callback throws or yields raw coroutine
- **WHEN** `handle_input` throws or an unrecognized raw `coroutine.yield` escapes its callback stack
- **THEN** the host SHALL record input FAILED with a local-contained typed callback failure
- **AND** the failure SHALL NOT crash the actor or application

### Requirement: Activation sequence is serialized and fail closed
After callback-table validation and authorization, the host SHALL invoke `startup(configuration)` exactly once as a synchronous callback. If it succeeds, the host SHALL invoke present `handle_lifecycle({event = "ready"})` synchronously before publishing Ready. A task admitted by startup `spawn` SHALL remain queued until both activation callbacks have succeeded and Ready publication occurs. A thrown Lua error, application error result, malformed error result, or raw coroutine yield escaping either activation callback SHALL fail activation, discard every startup-admitted task, and prevent Ready. A context-denied host call that returns `E_INVALID_CONTEXT` SHALL affect activation only through the callback's eventual returned result or thrown error.

#### Scenario: Successful activation publishes Ready
- **WHEN** `startup(configuration)` and present lifecycle-ready callback return successfully
- **THEN** the host SHALL publish Ready after the lifecycle step
- **AND** only then SHALL startup-admitted tasks become runnable

#### Scenario: Startup failure discards admitted tasks
- **WHEN** startup throws, returns a valid application failure, returns malformed failure data, or lets a raw coroutine yield escape
- **THEN** the host SHALL fail activation without publishing Ready
- **AND** it SHALL discard and cancel every task admitted during startup before any can execute

#### Scenario: Lifecycle-ready failure discards admitted tasks
- **WHEN** lifecycle-ready throws, returns a valid application failure, returns malformed failure data, or lets a raw coroutine yield escape
- **THEN** the host SHALL fail activation without publishing Ready
- **AND** it SHALL discard and cancel every task admitted during startup before any can execute

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

#### Scenario: Readiness callback projects exact readiness and status
- **WHEN** the host refreshes readiness and `handle_readiness` is present
- **THEN** it SHALL invoke the callback synchronously with one context table whose `capabilities` map contains every declared public capability ID exactly once with value `"available"`, `"recoverable"`, or `"unavailable"`
- **AND** the callback SHALL return a plain table containing required boolean `ready` and only optional bounded valid-UTF-8 string `status`
- **AND** a missing callback SHALL use the neutral not-ready default
- **AND** a thrown error, raw yield, non-table, error table, missing or non-boolean `ready`, unknown result key, metatable, or non-string/over-bound `status` SHALL cache not-ready and record one local typed failure for that refresh

#### Scenario: Readiness callback filters by selected mode's dependency subset
- **WHEN** `handle_readiness` is invoked for a provider that supports multiple modes depending on different capabilities
- **THEN** it SHALL evaluate the capability availability map for only the selected mode's dependency subset
- **AND** it SHALL return `ready = true` only if the required capabilities for that specific mode are available

#### Scenario: Input is accepted synchronously based on cached readiness
- **WHEN** the cached readiness projection (obtained from prior `handle_readiness` or its neutral default) is `ready = true` and the callback table contains a valid `handle_input` function
- **THEN** the host SHALL accept the input synchronously without making a new Lua call at input-acceptance time
- **AND** capture SHALL proceed

#### Scenario: Input is rejected because readiness is false
- **WHEN** the cached readiness projection is not ready or `handle_input` is absent
- **THEN** the host SHALL refuse the input with a typed not-ready or input-not-supported result
- **AND** capture SHALL NOT start

#### Scenario: Input capture lifecycle maps to snapshot states and delivers opaque audio userdata
- **WHEN** the host starts capture, the snapshot SHALL enter RECORDING; when capture completes and is released for processing, the snapshot SHALL enter PROCESSING before `handle_input` is invoked
- **THEN** the host SHALL invoke `handle_input` with a table having `event` set to `"capture"` (matching `subspace.channel.CAPTURE_COMPLETE`), a `session` identifier string, and a `metadata` table of bounded scalars
- **AND** the table SHALL contain a host-supplied `audio` field holding an opaque, unforgeable native full userdata representing the captured audio recording
- **AND** the callback SHALL be executed in the yield-capable context

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
- **AND** if the callback throws, returns an application error table, or lets a raw coroutine yield escape, the host SHALL local-contain and diagnostically log that failure without mutating the runtime snapshot, crashing the actor, or failing the generation
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

## ADDED Requirements

### Requirement: Callback runtime injects the semantic audio modules defined by Lua Audio API
In addition to the existing `subspace.runtime`, `subspace.channel`, and `subspace.log` modules, the callback runtime SHALL reserve and inject `subspace.transcription`, `subspace.synthesis`, and `subspace.playback` for every package. Requiring these modules SHALL NOT grant a capability. Their functions, normalized arguments and results, stable errors, context eligibility, deadlines, opaque-userdata ownership, consume/dispose rules, cancellation, and revocation semantics SHALL be exactly those defined by `lua-audio-api`; this provider contract SHALL NOT define an alternative audio surface. The provider adapter SHALL map each eligible invocation through the current execution owner, runtime generation context, and revocable semantic capability scope without exposing host implementation objects.

During entry or lazy-module evaluation, any call to one of these host-injected functions SHALL be handled by the effect-call-during-load guard and fail the complete module evaluation without returning a normal Lua error pair. After loading, an ineligible callback or unmanaged coroutine SHALL receive `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or host effect. An eligible `handle_input` callback or runtime-managed spawned task MAY yield through these modules under the execution-owner rules.

#### Scenario: Input callback uses audio module through provider adapter
- **WHEN** an eligible `handle_input` callback invokes a declared semantic audio operation
- **THEN** the provider adapter SHALL delegate through the current generation and input-invocation owner
- **AND** the Lua-visible result and resource lifecycle SHALL follow `lua-audio-api` exactly

#### Scenario: Spawned task uses audio module through provider adapter
- **WHEN** an eligible runtime-managed spawned task invokes synthesis or playback
- **THEN** the provider adapter SHALL delegate through that task's execution-owner identity and current generation
- **AND** task termination, timeout, cancellation, and generation revocation SHALL use the `lua-audio-api` lifecycle without a callback-invocation fallback

#### Scenario: Module evaluation attempts audio effect
- **WHEN** an entry or lazy-loaded module calls a semantic audio function during top-level evaluation
- **THEN** the effect-call-during-load guard SHALL fail and discard the complete module evaluation
- **AND** no capability acquisition, operation, audio resource, queue entry, or partial module cache value SHALL be created

#### Scenario: Live input cancellation differs from generation close
- **WHEN** a live generation cancels an input invocation suspended in an audio operation
- **THEN** the provider SHALL permit the `lua-audio-api` cancellation path to resume that callback exactly once with `E_CANCELLED`
- **AND** when the generation instead retires or closes, the provider SHALL discard suspended executions without re-entering Lua and revoke all generation-owned operations, audio tokens, leases, and queued playback

