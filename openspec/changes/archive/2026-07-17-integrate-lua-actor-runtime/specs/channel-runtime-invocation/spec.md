## MODIFIED Requirements

### Requirement: Runtime callbacks are serialized per instance
The host SHALL invoke provider and runtime callbacks through a host-owned invocation boundary that permits at most one host-admitted callback to execute at a time for a runtime generation. Host-admitted callbacks SHALL be ordered deterministically in admission order, and each executing callback SHALL observe the effects of the preceding completed callback before it begins. Runtime callbacks SHALL NOT be invoked directly from arbitrary callers.

Where the runtime is a cooperative actor runtime, the invocation boundary SHALL distinguish host-to-runtime adapter admission from in-actor scheduling. A host callback SHALL occupy the serialized adapter queue only while it admits an event and runs that event's current Lua slice. If the slice yields, the adapter callback SHALL return a normalized admitted or yielded result and release its invocation slot; the suspended coroutine remains actor-owned and does not keep the host callback active. Later host callbacks MAY then admit later events in FIFO order. Actor continuations resume through the bounded ready queue and do not constitute new host-admitted callbacks. At most one native Lua entry SHALL execute for the generation regardless of queued callbacks, ready continuations, or suspended coroutines. Separate runtime instances MAY execute independently.

#### Scenario: Concurrent events target one runtime
- **WHEN** two runtime callbacks are admitted concurrently for the same runtime generation
- **THEN** the invocation boundary SHALL execute them without overlap in deterministic admission order
- **AND** each callback SHALL observe the effects of the preceding completed callback

#### Scenario: Events target different runtimes
- **WHEN** independent callbacks are admitted for different live runtime instances
- **THEN** serialization of either instance SHALL NOT require execution under the other instance's callback queue or lock

#### Scenario: Suspended Lua entry interleaves other coroutines
- **WHEN** a Lua entry yields a host-operation token and suspends on a cooperative actor runtime
- **THEN** the actor MAY resume other suspended coroutines through its bounded ready queue while the entry remains suspended
- **AND** native Lua entry SHALL NOT overlap concurrently for that generation
- **AND** the resumed coroutines SHALL NOT be treated as newly host-admitted callbacks for adapter ordering

#### Scenario: Yield releases the adapter invocation slot
- **WHEN** a host-admitted actor event yields an opaque operation token
- **THEN** the adapter callback SHALL return its normalized admitted or yielded result without waiting for the operation to finish
- **AND** the invocation boundary SHALL permit the next admitted host callback to run
- **AND** the suspended coroutine SHALL remain owned by the actor ready/suspended scheduler

#### Scenario: Host operation completes while another coroutine runs
- **WHEN** a suspended Lua entry's host-operation token completes while a different coroutine is running in the same actor
- **THEN** the actor SHALL NOT resume the suspended entry concurrently with the running coroutine
- **AND** it SHALL resume the suspended entry only when native Lua entry is again available for that generation

### Requirement: Invocation admission and execution are bounded
The host SHALL use a bounded queue and an explicit deadline or timeout policy for provider construction and runtime callback work. When the queue has no capacity, the host SHALL reject new work with a typed busy result rather than block an unbounded number of callers. When an invocation exceeds its deadline, the host SHALL cancel it and return a typed timeout result without waiting indefinitely for provider or runtime code.

For a cooperative actor runtime, the host SHALL distinguish the active execution budget of a Lua entry from the yielded wait time of a suspended operation. An active execution budget bounds pure-Lua computation while the VM is entered; a yielded operation wait bounds the time a suspended coroutine waits for a host-operation token to complete. The host SHALL apply the active execution budget to a resumed coroutine only while it is running native Lua entry, and SHALL NOT charge yielded wait time against the active execution budget. A suspended operation whose wait exceeds its configured operation deadline SHALL be completed with a typed timed-out operation result, and the owning coroutine SHALL be resumed exactly once with that normalized result. A Lua entry whose active execution budget is exceeded SHALL be interrupted within a measured finite bound and SHALL NOT continue executing Lua instructions.

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

#### Scenario: Active Lua execution exceeds its budget
- **WHEN** a resumed coroutine executing native Lua entry exceeds the configured active execution budget for that generation
- **THEN** the host SHALL interrupt the Lua entry within a measured finite instruction and elapsed-time bound
- **AND** it SHALL NOT continue executing Lua instructions for that entry past the budget
- **AND** the affected runtime generation SHALL remain closable

#### Scenario: Yielded operation exceeds its wait deadline
- **WHEN** a suspended coroutine waits for a host-operation token longer than the configured operation wait deadline
- **THEN** the host SHALL complete the owning token with a typed timed-out operation result exactly once
- **AND** it SHALL resume the owning coroutine exactly once with that normalized result
- **AND** the active execution budget SHALL NOT be consumed by the yielded wait time

### Requirement: Cancellation is propagated and normalized
The invocation boundary SHALL propagate caller, session, replacement, and shutdown cancellation to queued and executing callback work. Cancellation SHALL remain distinguishable from timeout and non-cancellation failure, SHALL complete the invocation exactly once, and SHALL NOT be converted into success or an untyped exception.

For a cooperative actor runtime, cancellation SHALL reach both the active Lua entry and every suspended operation token owned by the generation. A suspended operation token SHALL accept at most one terminal cancellation or completion; racing cancellation and completion SHALL produce exactly one terminal outcome and SHALL NOT resume Lua more than once. Cancelling an active Lua entry SHALL interrupt it within the measured finite bound used by the active execution budget. Cancelling a generation SHALL invalidate all of its suspended operation tokens without resuming their coroutines for the purpose of producing effects.

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

#### Scenario: Cancelled suspended operation races completion
- **WHEN** cancellation and completion race for one suspended operation token on a cooperative actor runtime
- **THEN** exactly one terminal outcome SHALL win
- **AND** the owning coroutine SHALL be resumed at most once
- **AND** the losing request SHALL observe a typed cancelled, completed, or stale result

#### Scenario: Generation cancellation reaches suspended operations
- **WHEN** a runtime generation is cancelled while coroutines are suspended on operation tokens
- **THEN** the host SHALL invalidate every suspended operation token for that generation
- **AND** it SHALL NOT resume those coroutines to publish effects into current application state
- **AND** the active Lua entry SHALL be interrupted within the configured finite bound