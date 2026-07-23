## MODIFIED Requirements

### Requirement: Event callbacks have explicit yield contexts and exact terminal results
`startup`, `handle_lifecycle`, `handle_sos`, and `handle_readiness` SHALL execute synchronously and SHALL NOT suspend across the host boundary. The host SHALL reject a raw `coroutine.yield` escaping any of those callback stacks as a typed callback-contract failure. Outside source/module evaluation, a context-restricted host operation called from an ineligible callback SHALL return its normal `(nil, {error = "E_INVALID_CONTEXT"})` pair before suspension or effect; that denied call SHALL NOT by itself fail the callback if Lua handles or ignores it. `startup` MAY call `subspace.runtime.spawn` to admit managed background tasks. Lifecycle, SOS, and readiness callbacks SHALL receive `E_INVALID_CONTEXT` from `spawn` and `defer` without task admission. Input SHALL receive `E_INVALID_CONTEXT` from `spawn` but MAY call `subspace.runtime.defer` under its terminal-bound contract.

`handle_input` SHALL execute in a host-managed yield-capable coroutine. It MAY invoke authorized yielding operations defined by `lua-audio-api`, `lua-audio-file-api`, and `lua-filesystem-api`; each admitted operation MAY suspend the coroutine, release the serialized Lua execution slot, and resume it exactly once with a terminal result. `subspace.runtime.sleep`, `subspace.runtime.spawn`, and raw escaping `coroutine.yield` are not authorized input suspension paths. Sleep and spawn SHALL return `E_INVALID_CONTEXT`; raw escaping yield SHALL fail the callback. `runtime.defer` is synchronous task reservation and SHALL NOT itself suspend or run its function before input success.

For `startup`, `handle_lifecycle`, and `handle_sos`, a normal return without an `error` field SHALL be success/no-op according to callback role. An application failure SHALL be exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}`; any other table containing `error` SHALL be a typed callback-contract failure. `handle_input` and `handle_readiness` SHALL use their separately defined exact result shapes.

#### Scenario: Startup admits a background task
- **WHEN** `startup` calls `subspace.runtime.spawn(function() ... end)` and returns successfully
- **THEN** the runtime SHALL synchronously admit and queue the task
- **AND** the task SHALL NOT begin until activation completes
- **AND** startup itself SHALL NOT yield

#### Scenario: Input callback calls spawn
- **WHEN** `handle_input` calls `subspace.runtime.spawn`
- **THEN** it SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` without admitting or executing a task
- **AND** the callback MAY handle that result and continue

#### Scenario: Input callback uses authorized yielding operation
- **WHEN** `handle_input` invokes an authorized semantic-audio, audio-file, or filesystem operation
- **THEN** the host SHALL suspend that callback and release the execution slot
- **AND** it SHALL resume the callback exactly once while its execution owner remains current
- **AND** it SHALL evaluate the callback result only after terminal completion

#### Scenario: Input callback defers post-input work
- **WHEN** `handle_input` successfully calls `runtime.defer(function() ... end)`
- **THEN** the runtime SHALL reserve but not execute the task until the callback returns exact success

#### Scenario: Input callback returns success
- **WHEN** `handle_input` returns exactly `{ok = true}`
- **THEN** the host SHALL record input SUCCESS and SHALL NOT invoke another callback for that input
- **AND** it SHALL release transient input resources and release committed deferred tasks to the managed scheduler

#### Scenario: Input callback returns application failure
- **WHEN** `handle_input` returns exactly `{error = {code = "E_CAPTURE_FAILURE", detail = "processing failed"}}`
- **THEN** the host SHALL record input FAILED without automatic replay
- **AND** it SHALL discard every task deferred by that invocation

#### Scenario: Input callback returns malformed result
- **WHEN** `handle_input` returns a non-table, unknown key, both `ok` and `error`, an `ok` value other than `true`, or malformed error object
- **THEN** the host SHALL reject the complete result as a typed callback-contract failure
- **AND** it SHALL record input FAILED and discard deferred tasks

#### Scenario: Input callback throws or yields raw coroutine
- **WHEN** `handle_input` throws or an unrecognized raw `coroutine.yield` escapes
- **THEN** the host SHALL record input FAILED with a locally contained typed callback failure
- **AND** it SHALL discard deferred tasks without crashing the actor or application

## ADDED Requirements

### Requirement: Capture events include authoritative portable wall-clock metadata
Every `handle_input` capture event SHALL include exact `timestamp` with integer `unix_ms` and exact `local_time` containing integers `year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`, and `utc_offset_minutes`. The host SHALL capture the instant and local offset at input release before invoking Lua and SHALL validate all fields against bounded calendar ranges. The event SHALL expose no Android date/time object, timezone object, locale, formatter, or mutable clock reference.

#### Scenario: Input receives timestamp
- **WHEN** the host releases a completed capture for Lua processing
- **THEN** the event SHALL contain one authoritative Unix timestamp and matching local calendar/offset fields
- **AND** the package SHALL be able to derive deterministic names without Lua `os`

### Requirement: Readiness includes declared resource status
When a provider declares resources, `handle_readiness` context SHALL contain exact `resources.mounts` mapping every declared mount ID once to `available`, `read-only`, `needs-reauthorization`, or `unavailable`. Undeclared mounts, platform grants, paths, URIs, URLs, document IDs, and diagnostics SHALL be absent. The callback remains synchronous and non-yielding, and cached resource status SHALL not authorize later effects.

#### Scenario: Package checks required output mount
- **WHEN** readiness runs for a package declaring mount `output`
- **THEN** `context.resources.mounts.output` SHALL contain one portable status
- **AND** the callback SHALL perform no storage I/O to obtain it

### Requirement: `runtime.defer` admits terminal-bound managed tasks
`subspace.runtime.defer` SHALL accept exactly one function and be usable only from the current host-managed `handle_input` coroutine. It SHALL synchronously reserve bounded managed-task capacity and return `(true, nil)` or a normalized error without running the function. Exact input success SHALL release the function as a new managed task with its own execution owner; input failure, malformed return, throw, cancellation, generation close, or unsuccessful terminal delivery SHALL discard it. Deferred code SHALL not inherit input audio ownership and SHALL follow ordinary task cancellation, yielding, quota, logging, and close rules after start.

#### Scenario: Successful input releases deferred task
- **WHEN** an input invocation admitted a deferred function and returns `{ok=true}`
- **THEN** the task SHALL become runnable only after terminal success is committed
- **AND** it SHALL execute under a new task owner

#### Scenario: Cancelled input discards deferred task
- **WHEN** the input invocation is cancelled before successful terminal commit
- **THEN** every function deferred by that invocation SHALL be discarded without execution

#### Scenario: Defer capacity is exhausted
- **WHEN** an input calls `defer` after reaching the generation's reservation bound
- **THEN** the call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** no task SHALL be retained
