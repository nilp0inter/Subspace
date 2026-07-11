## ADDED Requirements

### Requirement: Runtime callbacks are serialized per instance
The host SHALL invoke provider and runtime callbacks through a host-owned invocation boundary that permits at most one callback to execute at a time for a runtime generation. Callback ordering SHALL be deterministic in admission order for accepted work, while separate runtime instances MAY execute independently. Runtime callbacks SHALL NOT be invoked directly from arbitrary callers.

#### Scenario: Concurrent events target one runtime
- **WHEN** two runtime callbacks are admitted concurrently for the same runtime generation
- **THEN** the invocation boundary SHALL execute them without overlap in deterministic admission order
- **AND** each callback SHALL observe the effects of the preceding completed callback

#### Scenario: Events target different runtimes
- **WHEN** independent callbacks are admitted for different live runtime instances
- **THEN** serialization of either instance SHALL NOT require execution under the other instance's callback queue or lock

### Requirement: Invocation admission and execution are bounded
The host SHALL use a bounded queue and an explicit deadline or timeout policy for provider construction and runtime callback work. When the queue has no capacity, the host SHALL reject new work with a typed busy result rather than block an unbounded number of callers. When an invocation exceeds its deadline, the host SHALL cancel it and return a typed timeout result without waiting indefinitely for provider or runtime code.

#### Scenario: Runtime queue is saturated
- **WHEN** a runtime generation's bounded invocation queue cannot admit another callback
- **THEN** the host SHALL reject that callback with a typed busy or unavailable result
- **AND** it SHALL NOT allocate an unbounded waiter or start the rejected callback later

#### Scenario: Callback exceeds its deadline
- **WHEN** a provider or runtime callback does not complete within its configured invocation deadline
- **THEN** the host SHALL request cancellation and complete the caller with a typed timeout result
- **AND** subsequent work SHALL NOT depend on the timed-out callback completing successfully

#### Scenario: Callback completes within bounds
- **WHEN** an admitted callback completes before its deadline
- **THEN** the host SHALL return its normalized result exactly once
- **AND** it SHALL release the invocation slot for the next admitted callback

### Requirement: Cancellation is propagated and normalized
The invocation boundary SHALL propagate caller, session, replacement, and shutdown cancellation to queued and executing callback work. Cancellation SHALL remain distinguishable from timeout and non-cancellation failure, SHALL complete the invocation exactly once, and SHALL NOT be converted into success or an untyped exception.

#### Scenario: Caller cancels queued work
- **WHEN** a caller cancels an invocation before its callback starts
- **THEN** the host SHALL remove or invalidate the queued callback
- **AND** the callback SHALL NOT execute
- **AND** the caller SHALL receive a typed cancelled result

#### Scenario: Caller cancels executing work
- **WHEN** cancellation reaches a running callback
- **THEN** the host SHALL propagate cancellation to the callback's child work
- **AND** it SHALL complete the invocation with a typed cancelled result exactly once

#### Scenario: Runtime throws cancellation
- **WHEN** a runtime callback terminates with the host's cancellation signal
- **THEN** the invocation boundary SHALL classify the outcome as cancellation rather than runtime failure

### Requirement: Callback failures are contained and mapped
The host SHALL catch every non-fatal provider or runtime callback failure at the invocation boundary and map it to a typed channel failure containing the operation phase and affected instance without exposing an implementation stack trace as channel data. A callback failure SHALL NOT escape into the registry, audio-session coordinator, main thread, or another runtime's invocation queue.

#### Scenario: Runtime callback throws
- **WHEN** a runtime callback throws a non-cancellation exception
- **THEN** the host SHALL complete that invocation with a typed runtime failure
- **AND** it SHALL perform the caller's required terminal cleanup exactly once
- **AND** unrelated runtime instances SHALL remain available

#### Scenario: Provider constructor throws
- **WHEN** provider runtime construction throws a non-cancellation exception
- **THEN** the host SHALL map it to a typed provider-construction failure for that definition
- **AND** it SHALL project the affected instance as unavailable rather than terminate catalogue reconciliation

### Requirement: Callbacks execute outside registry locks and the Android main thread
The host SHALL NOT call provider code, runtime code, capability operations, cancellation handlers, or close callbacks while holding a runtime-registry or catalogue synchronization lock. Potentially blocking or externally supplied callbacks SHALL execute on a bounded host-owned worker dispatcher and SHALL NOT execute on the Android main thread. Locks SHALL protect only short host-owned state transitions and SHALL be released before awaiting callback completion.

#### Scenario: Registry reconciliation constructs a runtime
- **WHEN** reconciliation determines under synchronization that a runtime must be created, updated, retired, or closed
- **THEN** it SHALL record the required transition and release the synchronization lock before invoking provider or runtime code
- **AND** it SHALL validate the transition is still current before publishing its result

#### Scenario: Runtime callback is initiated by the UI thread
- **WHEN** an Android main-thread action requests a provider or runtime callback
- **THEN** the invocation boundary SHALL dispatch callback execution to its bounded worker context
- **AND** the Android main thread SHALL NOT block waiting synchronously for callback completion

#### Scenario: Callback re-enters host state
- **WHEN** a runtime callback publishes status or invokes a host capability
- **THEN** the host SHALL process that interaction without requiring the callback to re-enter a registry lock held by its invoker

### Requirement: Runtime closure follows deterministic ordering
Closing a runtime generation SHALL atomically stop admission of new callbacks, invalidate queued callbacks, request cancellation of executing callbacks and their child work, wait only within the configured closure bound, revoke instance-scoped capability leases, invoke the runtime's terminal close callback at most once, and publish terminal closure exactly once. An accepted committed input target SHALL receive its required terminal event before its runtime generation is terminally closed.

#### Scenario: Idle runtime closes
- **WHEN** an idle runtime generation is retired or the host shuts down
- **THEN** the host SHALL stop admission, invalidate queued work, revoke capability leases, invoke terminal close at most once, and publish closure in the defined order

#### Scenario: Runtime closes with a committed target
- **WHEN** a runtime is retired while one of its input targets remains committed
- **THEN** the host SHALL refuse new preparation for that generation
- **AND** it SHALL preserve the generation until the committed target receives one terminal release, cancellation, or failure
- **AND** it SHALL then perform terminal runtime closure exactly once

#### Scenario: Executing callback ignores cancellation
- **WHEN** closure cancellation does not stop an executing callback within the closure bound
- **THEN** host closure SHALL complete without waiting indefinitely
- **AND** the callback's generation SHALL remain invalidated so that any later result or effect is rejected

#### Scenario: Close is requested repeatedly
- **WHEN** replacement, lease release, and host shutdown each request closure of the same runtime generation
- **THEN** only the first request SHALL perform terminal close and publication
- **AND** all requests SHALL observe the same terminal closed state

### Requirement: Late results and effects are suppressed
The host SHALL gate callback result publication and capability effects by the runtime generation's live state. Once work is cancelled, timed out, superseded, retired for terminal closure, or closed, its later completion SHALL NOT publish readiness, execution status, playback, text output, persistence, or any other effect into current application state. Suppression SHALL occur at the host boundary even when provider or runtime code does not cooperate with cancellation.

#### Scenario: Timed-out callback returns later
- **WHEN** a callback returns a value after its invocation has completed as timed out
- **THEN** the host SHALL discard the late value
- **AND** it SHALL NOT convert the timeout into success or publish effects from that value

#### Scenario: Replaced generation publishes status
- **WHEN** an old runtime generation attempts to publish status after a replacement generation is current
- **THEN** the host SHALL reject the publication
- **AND** the replacement generation's state SHALL remain unchanged

#### Scenario: Closed runtime invokes a capability
- **WHEN** closed or invalidated runtime work attempts a host capability operation
- **THEN** the capability boundary SHALL return a typed closed or cancelled result
- **AND** it SHALL produce no hardware, transport, audio, text-output, persistence, or state-publication effect

### Requirement: Invocation hardening does not embed a script engine
The invocation boundary SHALL treat runtime implementations as opaque callback providers and SHALL NOT require or introduce a Lua interpreter, Lua scheduler, package loader, package execution policy, or script API. Its serialization, bounds, cancellation, failure mapping, confinement, closure, and effect-gating guarantees SHALL apply to Kotlin built-in runtimes and any future conforming runtime provider.

#### Scenario: Kotlin built-in callback is invoked
- **WHEN** a built-in Kotlin runtime callback enters the invocation boundary
- **THEN** the host SHALL apply the same queueing, timeout, cancellation, failure, thread, closure, and late-effect rules
- **AND** no scripting subsystem SHALL be initialized
