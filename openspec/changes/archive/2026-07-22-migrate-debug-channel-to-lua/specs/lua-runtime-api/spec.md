## Purpose

Modifies Lua Runtime API value-normalization and context-restricted callback yielding rules to support opaque audio userdata and yield-capable input callbacks.

## MODIFIED Requirements

### Requirement: Public values, errors, and cancellation are normalized Lua data values
All host-to-Lua and Lua-to-host data exchange SHALL use normalized Lua data types: `nil`, boolean, finite number, valid-UTF-8 string, and plain table, with the sole exception of host-constructed opaque audio userdata values. A table SHALL be either a string-keyed map or a contiguous 1..n integer-keyed array; mixed, sparse, non-string map keys, and non-integer array keys SHALL be rejected. The runtime SHALL interpret callback results according to the exact result shape declared for that callback; it SHALL NOT treat an arbitrary normal value as failure merely because another callback uses an error table. Host-operation failures SHALL use `(nil, error_table)` with a stable string `error` code. Explicit cancellation while the generation remains live SHALL use `(nil, {error = "E_CANCELLED"})`. Generation close SHALL discard suspended coroutines without delivering cancellation or any other result.

Normalization SHALL be bounded by host-configurable finite limits on table depth, entry count, and string byte length. The host SHALL reject a whole value containing a cycle, metatable, function, thread, platform object, non-finite number, invalid UTF-8, an invalid table shape, or any userdata other than host-constructed opaque audio userdata values with `E_INVALID_VALUE`; it SHALL NOT perform partial or unbounded traversal. All normalized serialization, logging, configuration, callback terminal return, and error formatting paths SHALL reject audio userdata values with `E_INVALID_VALUE`. Limits are host-configurable and non-normative. Lua references retained exclusively inside the owning state — callback functions, cached module values, and the function supplied to `subspace.runtime.spawn` — are not normalized host data. `spawn` is the only public host call that accepts a function argument; it SHALL retain that reference internally and SHALL NOT serialize it to Kotlin or public host data.

#### Scenario: Callback returns a value allowed by its callback contract
- **WHEN** a protected Lua callback returns a normalized value permitted by that callback's declared result shape
- **THEN** the runtime SHALL interpret the value according to that callback contract
- **AND** it SHALL NOT coerce the value into a different callback's success or failure shape

#### Scenario: Callback returns a declared application failure
- **WHEN** a protected callback returns the exact error-table shape declared by its callback contract
- **THEN** the runtime SHALL classify that invocation as the declared application failure
- **AND** it SHALL NOT issue further plugin callbacks for that operation

#### Scenario: Live operation cancellation returns E_CANCELLED
- **WHEN** a host operation is explicitly cancelled while the actor generation remains live
- **THEN** the coroutine SHALL be resumed exactly once and the calling function SHALL return `(nil, {error = "E_CANCELLED"})`
- **AND** the plugin SHALL be able to distinguish cancellation from success, timeout, and application failure by the stable error code

#### Scenario: Platform object is returned to Lua
- **WHEN** a host operation attempts to deliver a Kotlin object, Android object, JNI handle, or actor identity as an operation result
- **THEN** the runtime SHALL deny delivery at the boundary
- **AND** it SHALL resume the coroutine with `E_INVALID_VALUE` instead

#### Scenario: Value contains a cycle, metatable, function, or platform object
- **WHEN** a callback returns a table that contains a cycle, has a metatable, or contains a function, thread, or platform value, or any userdata other than host-constructed opaque audio userdata
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL NOT perform partial or unbounded traversal
- **AND** it SHALL NOT strip fields to produce a partial value

#### Scenario: Value contains a non-finite number
- **WHEN** a callback returns a number that is `NaN`, positive infinity, or negative infinity
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL NOT coerce the number to a string or zero

#### Scenario: Table has mixed, sparse, or invalid keys
- **WHEN** a callback returns a table that mixes array and map keys, has gaps in integer keys, or uses an invalid key type
- **THEN** the runtime SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL NOT perform partial traversal or strip keys

### Requirement: Host operations use validated contexts and normalized success/error pairs
Outside source-map module chunk evaluation, every public host operation available through the `subspace.*` modules SHALL return two values to the caller: `(value, nil)` on success and `(nil, error_table)` on failure, where `error_table` is a table with at least an `error` string field and optionally a `reason` field. During entry or lazy-module evaluation, the module-loader effect guard defined above takes precedence: a host-call attempt fails the whole load with the typed effect-call-during-load outcome and does not return a normal success/error pair to continued chunk evaluation. A yielding operation such as `subspace.runtime.sleep` or yielding audio operations SHALL suspend internally without exposing their operation tokens; a non-yielding admission operation such as `subspace.runtime.spawn` SHALL return synchronously. Cancellation of a live operation SHALL produce `(nil, {error = "E_CANCELLED"})`. The plugin SHALL NOT receive an opaque operation token, coroutine reference, or host platform handle from any public host call; it SHALL receive only normalized values or host-constructed opaque audio userdata. Duplicate completions, completions after cancellation, and completions after close SHALL be rejected by the host without resuming Lua.

`subspace.runtime.spawn` SHALL accept a single function argument. If the argument is not a function, the call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})` and SHALL NOT admit a task. Spawn is allowed only from the `startup` callback and from background tasks already executing in a runtime-managed spawned coroutine. Spawn from synchronous event callbacks (`handle_lifecycle`, `handle_input`, `handle_sos`, `handle_readiness`) or from plugin-created child coroutines SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` and SHALL NOT admit a task. Spawn attempted during entry or lazy-module evaluation SHALL instead fail that module evaluation with the typed effect-call-during-load outcome and SHALL NOT continue or cache a partial module result.

`subspace.runtime.sleep` SHALL accept a single finite number argument >= 0. If the argument is negative, `NaN`, positive infinity, negative infinity, or above the host-configured maximum delay, the call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})` and SHALL NOT suspend the coroutine or start a timer. A value of 0 MAY be clamped to the host's minimum timer tick. Sleep is allowed only from runtime-managed spawned coroutines (the coroutine created for the background function passed to `spawn`). Sleep from synchronous event callbacks (`startup`, `handle_readiness`, `handle_lifecycle`, `handle_sos`), the yield-capable `handle_input` callback, or plugin-created child coroutines SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` and SHALL NOT suspend or start a timer. Sleep attempted during entry or lazy-module evaluation SHALL instead fail that module evaluation with the typed effect-call-during-load outcome and SHALL NOT continue or cache a partial module result. When the per-generation timer slot limit is reached, sleep SHALL return `(nil, {error = "E_BUSY"})` without suspension.

Managed background tasks admitted via `subspace.runtime.spawn` MAY remain live until their function returns, fails, or the owning generation is closed. The host SHALL NOT terminate a task solely because a generic wall-clock lifetime has elapsed. The host SHALL bound the maximum number of concurrently managed tasks admitted per generation; exhaustion SHALL return `E_BUSY`. Suspended time — periods during which a background coroutine is suspended in `sleep` or awaiting a host operation — SHALL NOT be charged against active Lua execution limits; only active Lua execution slices remain subject to host-configured instruction-count and wall-clock bounds.

Each `sleep` call SHALL establish an operation-specific deadline computed as `requested_delay + bounded_slack`, where `bounded_slack` is a host-configured timer margin. If timer completion wins before that deadline, the host SHALL resume the coroutine with `(true, nil)`. If the deadline wins before timer completion, the host SHALL resume the coroutine exactly once with `(nil, {error = "E_TIMEOUT"})`; a later timer completion SHALL be rejected as stale and SHALL NOT resume Lua again. On generation close, the sleeping coroutine SHALL NOT be resumed and neither timeout, cancellation, nor any outcome SHALL be delivered.

The Lua function `coroutine.yield` SHALL remain available only for pure-Lua child-coroutine composition that is fully resumed inside the same active host callback or runtime-managed spawned task. It SHALL NOT become a host operation or suspend execution across the actor boundary. A raw yield escaping a callback or spawned-task boundary SHALL produce `E_INVALID_YIELD` and the owning callback/task failure defined for that boundary. By contrast, a call to `subspace.runtime.sleep`, `subspace.runtime.spawn`, or a semantic audio function from an ineligible loaded callback SHALL be rejected before yielding with that host function's ordinary `(nil, {error = "E_INVALID_CONTEXT"})` pair. `handle_input` is eligible only for semantic audio yielding; sleep and spawn remain context-denied.

#### Scenario: Sleep timer fires normally in a spawned task
- **WHEN** a spawned background task calls `subspace.runtime.sleep(1.0)` and the timer expires while the actor is live
- **THEN** the host SHALL internally suspend the coroutine, fire the timer, and resume the coroutine
- **AND** the sleep call SHALL return `(true, nil)` to the callback
- **AND** the spawned task SHALL NOT have received an operation token or handle at any point

#### Scenario: Sleep operation is cancelled while the actor is live
- **WHEN** a spawned background task is suspended in `subspace.runtime.sleep` and that operation is cancelled before timer completion while the actor remains live
- **THEN** the host SHALL resume the coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_CANCELLED"})`

#### Scenario: Synchronous event callback calls context-restricted operation
- **WHEN** `startup`, `handle_readiness`, `handle_lifecycle`, or `handle_sos` calls `subspace.runtime.sleep` or a semantic audio operation after module loading
- **THEN** the operation SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or host effect
- **AND** the denied call SHALL NOT by itself fail the callback if Lua handles or ignores the result
- **AND** an unrecognized raw `coroutine.yield` escaping the callback SHALL instead produce a typed callback-contract failure

#### Scenario: Input callback calls sleep or raw yield
- **WHEN** the host-managed `handle_input` callback calls `subspace.runtime.sleep`
- **THEN** sleep SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` without suspending or starting a timer
- **AND** the denied call SHALL NOT by itself fail the callback if Lua handles or ignores the result
- **AND** an unrecognized raw `coroutine.yield` escaping the input callback SHALL instead produce a typed callback-contract failure

#### Scenario: Input callback yields on semantic audio operations
- **WHEN** the host-managed `handle_input` callback invokes a yielding semantic audio operation such as `subspace.transcription.transcribe`
- **THEN** the host SHALL suspend the callback coroutine, execute the host operation, and resume the coroutine with the operation result when complete
- **AND** the actor's execution slot SHALL be released during the suspension

#### Scenario: Spawned task uses `coroutine.yield` for child-coroutine composition
- **WHEN** a spawned background task creates a child coroutine via `coroutine.wrap` or `coroutine.create` and the child coroutine calls `coroutine.yield`
- **THEN** the runtime SHALL allow the child coroutine to yield and resume within pure Lua
- **AND** the runtime SHALL NOT treat the yield as a host operation or an unrecognized yield

#### Scenario: Spawned task calls raw `coroutine.yield` at top level
- **WHEN** a spawned background task calls `coroutine.yield()` at the top level (not inside a child coroutine)
- **THEN** the runtime SHALL detect the unrecognized yield that escapes the spawned-task boundary
- **AND** it SHALL produce `E_INVALID_YIELD` for that task

#### Scenario: Sleep is called from plugin-created child coroutine
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `subspace.runtime.sleep`
- **THEN** the runtime SHALL NOT suspend or start a timer
- **AND** the sleep call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no timer token SHALL be created

#### Scenario: Spawn is called from synchronous event callback other than startup
- **WHEN** `handle_input`, `handle_lifecycle`, `handle_readiness`, or `handle_sos` calls `subspace.runtime.spawn`
- **THEN** the runtime SHALL NOT admit the task
- **AND** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn is called from plugin-created child coroutine
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `subspace.runtime.spawn`
- **THEN** the runtime SHALL NOT admit the task
- **AND** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn argument is not a function
- **WHEN** a callback calls `subspace.runtime.spawn("not_a_function")` or `subspace.runtime.spawn(42)`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT admit a task

#### Scenario: Sleep argument is negative
- **WHEN** a spawned background task calls `subspace.runtime.sleep(-1.0)`
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT suspend the coroutine or start a timer

#### Scenario: Sleep argument exceeds the configured maximum delay
- **WHEN** a spawned background task calls `subspace.runtime.sleep` with a finite duration greater than the host-configured maximum delay
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT suspend the coroutine or start a timer

#### Scenario: Sleep at zero clamps to minimum tick
- **WHEN** a spawned background task calls `subspace.runtime.sleep(0)`
- **THEN** the host MAY clamp the duration to its minimum timer tick
- **AND** the call SHALL proceed as a valid sleep with the clamped duration
- **AND** it SHALL return `(true, nil)` after the timer fires

#### Scenario: Sleeping coroutine is terminated on generation close
- **WHEN** a spawned background task is suspended in `subspace.runtime.sleep` and its owning actor generation is closed or replaced
- **THEN** the host SHALL terminate the sleeping coroutine without resuming it
- **AND** the coroutine SHALL NOT receive any result, cancellation, or closed outcome
- **AND** the actor's operation tokens SHALL be invalidated and the Lua state closed

#### Scenario: Timer completion is stale after generation close
- **WHEN** a timer operation completes after its owning actor has closed
- **THEN** the host SHALL reject the completion as stale
- **AND** it SHALL NOT resume any Lua coroutine for that effect

#### Scenario: Background task loops with periodic sleep beyond a former generic task deadline
- **WHEN** a spawned background task repeatedly performs brief work and calls `subspace.runtime.sleep`, and its cumulative wall-clock lifetime exceeds the generic deadline used by the pre-change actor policy
- **THEN** the runtime SHALL continue to resume the task across sleep boundaries because each sleep establishes a new operation-specific deadline
- **AND** the runtime SHALL NOT terminate the task or fail a sleep solely because cumulative task wall time exceeds that former generic deadline
- **AND** the task MAY remain live as long as each active slice satisfies execution limits, each sleep completes within its operation-specific deadline, and the generation remains open

#### Scenario: Sleep deadline wins before timer completion
- **WHEN** a spawned background task calls `subspace.runtime.sleep` and its operation-specific deadline passes before timer completion
- **THEN** the host SHALL classify the operation as timed out
- **AND** it SHALL resume the sleeping coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_TIMEOUT"})`
- **AND** a later timer completion SHALL be rejected as stale without resuming Lua again
- **AND** the coroutine SHALL remain live and available for further host operations
