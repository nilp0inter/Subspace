## MODIFIED Requirements

### Requirement: Runtime mechanisms remain distinct from plugin policy and host-native capabilities
The runtime SHALL own scheduling, mailbox admission, coroutine continuation, typed host-operation request ownership, instruction/memory policy, failure containment, replacement draining, and deterministic teardown. Lua SHALL own package protocol, retry, polling, storage layout, durable state-machine, rendering, and recovery policy. Every blocking runtime/host operation SHALL yield an opaque request token before execution and remain host-owned, lifecycle-bound, capability/resource-authorized, and generation-safe. The actor SHALL host public modules defined by the revised Lua v1 API but SHALL NOT embed package-specific policy, Journal operation kinds, platform storage identifiers, package discovery/install policy, automatic provider migration, or plugin-driven foreground-service retention.

#### Scenario: Blocking host operation is requested
- **WHEN** Lua requests a host effect that cannot complete in a deliberately small bounded native call
- **THEN** the runtime SHALL create and yield an opaque typed request before the effect executes
- **AND** the effect SHALL remain host-owned, lifecycle-bound, and generation-authorized
- **AND** the Lua execution thread SHALL not block waiting for completion

#### Scenario: Journal policy is expressed in Lua
- **WHEN** the external package implements paths, metadata, derivation, rendering, or recovery
- **THEN** policy SHALL execute within actor instruction/memory/task bounds
- **AND** runtime scheduling, ownership, authorization, and containment SHALL remain authoritative without knowing Journal semantics

## ADDED Requirements

### Requirement: Typed request registry replaces operation-label argument encoding
Each actor state SHALL own a bounded registry of immutable typed host-operation requests. A public module SHALL validate Lua arguments, resolve current userdata, create one request with kind and bounded payload, and yield only an unforgeable request token. Paths, text, JSON, audio/mount tokens, codec parameters, and platform identifiers SHALL not be concatenated into operation labels. The host SHALL claim each request at most once and SHALL reject unknown, duplicate, foreign, stale, cancelled, or closed tokens without executing an effect.

#### Scenario: Filesystem operation is admitted
- **WHEN** a valid `fs.write_text` call is admitted
- **THEN** the state registry SHALL retain its bounded typed payload
- **AND** the yielded outcome SHALL expose only opaque operation identity

#### Scenario: Completed request is claimed twice
- **WHEN** the dispatcher attempts to claim a terminal request again
- **THEN** the actor SHALL return a typed stale/duplicate outcome
- **AND** it SHALL not execute or resume again

### Requirement: Generic host requests share exact-once actor lifecycle
Every typed request SHALL carry the owning state, actor generation, execution owner, operation ID, kind, eligibility, and one atomic terminal gate. Only its live owner MAY receive completion. Deadline, input cancellation, task cancellation, capability/resource revocation, generation close, and normal completion SHALL race through that gate. Generation close SHALL discard suspended coroutines, invalidate pending requests/userdata, release bounded payloads, and suppress late completions. A request registry SHALL not grow without finite per-execution, per-generation, and process limits.

#### Scenario: Generation closes with storage request pending
- **WHEN** close wins before a document-provider request completes
- **THEN** the actor SHALL discard the coroutine and request payload without re-entry
- **AND** late provider completion SHALL not mutate successor state or return another result

### Requirement: Deferred tasks are reserved by input and committed by terminal success
An input execution SHALL be able to reserve bounded task capacity for `runtime.defer(function)` without running the function. The actor SHALL associate the reservation with that exact input owner. Exact `{ok=true}` terminal commit SHALL transfer the function into a new managed-task owner and ready queue after input completion. Failure, malformed return, throw, cancellation, timeout, retirement, or close before commit SHALL discard the reservation/function. Deferred tasks SHALL count against task bounds, own their yielded requests/audio independently, and follow ordinary task failure/cancellation rules.

#### Scenario: Input commits deferred task
- **WHEN** a live input with one admitted defer returns exact success
- **THEN** the actor SHALL terminally complete the input before making the task runnable
- **AND** the task SHALL have a distinct execution owner

#### Scenario: Input fails after defer
- **WHEN** the input throws or returns failure after reservation
- **THEN** the actor SHALL discard the deferred function without execution
- **AND** task capacity SHALL be released

### Requirement: Deferred tasks cannot inherit input-scoped userdata ownership
Closure capture SHALL not transfer opaque audio ownership. A deferred task that presents an audio token owned by its input predecessor SHALL receive a foreign/stale error before host effect. Generation-owned mount handles MAY be retained and used by the deferred task while current. The task MAY create its own audio ownership by successfully reopening a stored Recording.

#### Scenario: Deferred closure captures capture audio
- **WHEN** deferred code calls transcription/export with the predecessor input Recording
- **THEN** audio registry resolution SHALL reject it before effect
- **AND** mount handles captured from the same generation MAY remain usable under live revalidation
