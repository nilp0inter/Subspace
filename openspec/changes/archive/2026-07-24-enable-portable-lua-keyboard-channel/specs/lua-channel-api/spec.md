## MODIFIED Requirements

### Requirement: Event callbacks have explicit yield contexts and exact terminal results
`startup`, `handle_lifecycle`, and `handle_readiness` SHALL execute synchronously and SHALL NOT suspend across the host boundary. `handle_input` and `handle_sos` SHALL execute in host-managed yield-capable coroutines. The host SHALL reject a raw `coroutine.yield` escaping any callback stack as a typed callback-contract failure. Outside source/module evaluation, a context-restricted host operation called from an ineligible callback SHALL return its normal `(nil, {error = "E_INVALID_CONTEXT"})` pair before suspension or effect; that denied call SHALL NOT by itself fail the callback if Lua handles or ignores it. `startup` MAY call `subspace.runtime.spawn` to admit managed background tasks. Lifecycle, SOS, and readiness callbacks SHALL receive `E_INVALID_CONTEXT` from `spawn` and `defer` without task admission. Input SHALL receive `E_INVALID_CONTEXT` from `spawn` but MAY call `subspace.runtime.defer` under its terminal-bound contract.

`handle_input` MAY invoke authorized yielding operations defined by the public Lua capability APIs available to input execution owners. `handle_sos` MAY invoke authorized yielding operations explicitly permitted for SOS execution owners, including declared `keyboard.output`. Each admitted operation MAY suspend its callback, release the serialized Lua execution slot, and resume it exactly once with a terminal result while the owner and generation remain current. `subspace.runtime.sleep`, `subspace.runtime.spawn`, and raw escaping `coroutine.yield` are not authorized input or SOS suspension paths. Sleep and spawn SHALL return `E_INVALID_CONTEXT`; raw escaping yield SHALL fail the callback. `runtime.defer` is synchronous input-only task reservation and SHALL return `E_INVALID_CONTEXT` from SOS.

For `startup` and `handle_lifecycle`, a normal return without an `error` field SHALL be success/no-op according to callback role. `handle_sos` SHALL return exactly `{ok = true}` or `{error = {code = <non-empty string>, detail = <non-empty string>}}`. An application failure for other general callbacks SHALL be exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}`; any malformed table containing `error` SHALL be a typed callback-contract failure. `handle_input` and `handle_readiness` SHALL use their separately defined exact result shapes.

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
- **WHEN** `handle_input` invokes an authorized semantic-audio, audio-file, filesystem, or keyboard-output operation
- **THEN** the host SHALL suspend that callback and release the execution slot
- **AND** it SHALL resume the callback exactly once while its execution owner remains current
- **AND** it SHALL evaluate the callback result only after terminal completion

#### Scenario: SOS callback uses authorized yielding operation
- **WHEN** `handle_sos` invokes an authorized keyboard-output operation
- **THEN** the host SHALL suspend that callback and release the execution slot
- **AND** it SHALL resume the callback at most once while its SOS execution owner remains current
- **AND** raw yield, sleep, spawn, and defer SHALL remain unavailable

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

### Requirement: `subspace.channel` provides event constants for callback argument matching
The `subspace.channel` module SHALL be a host-injected, reserved module that plugins can `require` to access channel event type constants. The plugin SHALL NOT override or shadow the injected `subspace.channel` module with source-map content. In v1 the module SHALL define only three string constants: `LIFECYCLE_READY = "ready"`, `CAPTURE_COMPLETE = "capture"`, and `SOS_TRIGGERED = "sos"`. There SHALL be no callable functions on the module in v1; the plugin's callback table is the sole interface for channel lifecycle, input, SOS, and readiness projection. Callbacks receive event tables whose `event` string field corresponds to one of these constants.

#### Scenario: Plugin reads event constants
- **WHEN** a callback reads `subspace.channel.LIFECYCLE_READY`, `subspace.channel.CAPTURE_COMPLETE`, or `subspace.channel.SOS_TRIGGERED`
- **THEN** it SHALL receive the exact strings `"ready"`, `"capture"`, and `"sos"` respectively

#### Scenario: Lifecycle callback is invoked after startup before Ready publication
- **WHEN** the host has completed `startup` successfully and is about to publish the channel as Ready, and the plugin has supplied `handle_lifecycle`
- **THEN** the host SHALL invoke the callback with a table containing `event` set to `"ready"` matching `subspace.channel.LIFECYCLE_READY`
- **AND** the callback SHALL receive no platform objects, actor identities, or audio data
- **AND** the host SHALL publish Ready immediately after the callback returns successfully

#### Scenario: Lifecycle callback is absent
- **WHEN** the callback table lacks `handle_lifecycle`
- **THEN** lifecycle events SHALL produce no effect
- **AND** the host SHALL proceed directly from `startup` success to Ready publication

#### Scenario: Readiness callback projects readiness status and preparation
- **WHEN** the host refreshes readiness and `handle_readiness` is present
- **THEN** it SHALL invoke the callback synchronously with one context table whose `capabilities` map contains every declared public capability ID exactly once with value `available`, `recoverable`, or `unavailable`, whose resource map follows declared resource contracts, and whose configuration-reference map contains every required dynamic scalar reference exactly once with value `available` or `unavailable`
- **AND** the callback SHALL return a plain exact-key table containing required boolean `ready`, optional bounded valid-UTF-8 string `status`, and optional bounded duplicate-free array `prepare`
- **AND** every `prepare` entry SHALL name a declared host-preparable public capability, and nonempty `prepare` SHALL be valid only when `ready = false`
- **AND** a missing callback SHALL use the neutral not-ready default with no preparation request
- **AND** a thrown error, raw yield, non-table, error table, missing or non-boolean `ready`, unknown result key, metatable, invalid status, invalid prepare array, unknown/duplicate/undeclared/non-preparable capability, or `ready = true` with nonempty prepare SHALL cache not-ready with no preparation and record one local typed failure

#### Scenario: Readiness callback updates host-visible availability
- **WHEN** the host obtains a valid or invalid readiness projection
- **THEN** `ready = true` SHALL publish the runtime snapshot as available
- **AND** `ready = false` with a nonempty valid `prepare` array SHALL publish it as recoverable
- **AND** `ready = false` without a preparation path, a missing callback, or a malformed result SHALL publish it as unavailable
- **AND** the phone and car catalogue projections SHALL derive their readiness status from that current runtime snapshot rather than activation state

#### Scenario: Readiness callback filters by selected dependency subset
- **WHEN** `handle_readiness` supports configurations or modes depending on different capabilities
- **THEN** it SHALL evaluate only the selected dependency subset
- **AND** it MAY request preparation only for the selected subset's declared recoverable dependencies

#### Scenario: Cached readiness accepts input
- **WHEN** cached readiness is `ready = true` and `handle_input` is valid
- **THEN** the host SHALL accept input without a new Lua call at initial acceptance
- **AND** capture SHALL proceed

#### Scenario: Cached readiness requests preparation
- **WHEN** cached readiness is false with a nonempty valid `prepare` array
- **THEN** the host SHALL run bounded generic preparation before accepting input
- **AND** it SHALL refresh readiness after successful preparation
- **AND** capture SHALL proceed only if refreshed readiness is true

#### Scenario: Input is rejected because readiness is false
- **WHEN** cached readiness is false with no successful preparation path or `handle_input` is absent
- **THEN** the host SHALL refuse input with a typed result
- **AND** capture SHALL NOT start

#### Scenario: Input capture lifecycle delivers opaque audio userdata
- **WHEN** capture completes successfully after the snapshot entered RECORDING
- **THEN** the snapshot SHALL enter PROCESSING and the host SHALL invoke `handle_input` with event `capture`, session identity, bounded metadata, and opaque unforgeable recording userdata
- **AND** the callback SHALL execute in its yield-capable context

#### Scenario: Input capture is cancelled by host
- **WHEN** capture is cancelled before release
- **THEN** the snapshot SHALL enter IDLE
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: Input capture fails before release
- **WHEN** the host capture subsystem fails before release
- **THEN** the snapshot SHALL enter FAILED
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: SOS callback completes through managed coroutine
- **WHEN** SOS occurs and `handle_sos` is present
- **THEN** the host SHALL invoke it with event `sos` matching `subspace.channel.SOS_TRIGGERED` under one bounded SOS execution owner
- **AND** exact success SHALL complete the SOS invocation once
- **AND** application failure, malformed result, throw, invalid yield, cancellation, timeout, or revocation SHALL be locally contained and diagnosed without crashing the actor or unrelated generation

#### Scenario: SOS callback is absent
- **WHEN** the callback table lacks `handle_sos`
- **THEN** SOS events SHALL be unhandled
- **AND** no Lua callback SHALL be invoked

#### Scenario: Proactive-only plugin omits optional callbacks
- **WHEN** the callback table contains only required `startup`
- **THEN** the host SHALL keep successfully admitted managed background tasks live
- **AND** the channel SHALL not be available for PTT input selection and readiness SHALL default to not ready
