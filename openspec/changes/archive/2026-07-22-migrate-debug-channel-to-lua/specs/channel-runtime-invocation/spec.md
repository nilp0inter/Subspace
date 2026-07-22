## MODIFIED Requirements

### Requirement: Runtime callbacks are serialized per instance
The host SHALL invoke provider and runtime callbacks through a host-owned invocation boundary that permits at most one host-admitted callback to execute at a time for a runtime generation. Host-admitted callbacks SHALL be ordered deterministically in admission order, and each executing callback SHALL observe the effects of the preceding completed callback before it begins. Runtime callbacks SHALL NOT be invoked directly from arbitrary callers.

Where the runtime is a cooperative actor runtime, the invocation boundary SHALL distinguish host-to-runtime adapter admission from in-actor scheduling. A host callback SHALL occupy the serialized adapter queue only while it admits an event and runs that event's current Lua slice. If the slice yields, the adapter callback SHALL return a normalized admitted or yielded result and release its invocation slot; the suspended coroutine remains actor-owned and does not keep the host callback active. Later host callbacks MAY then admit later events in FIFO order. Actor continuations resume through the bounded ready queue and do not constitute new host-admitted callbacks. At most one native Lua entry SHALL execute for the generation regardless of queued callbacks, ready continuations, or suspended coroutines. Separate runtime instances MAY execute independently.

When a yielded actor continuation is resumed synchronously from inside an executing serialized callback, the boundary SHALL treat that continuation as already covered by the callback's execution serialization. The continuation resume SHALL NOT block on, or attempt to re-acquire, the non-reentrant execution lock already held by the enclosing callback in a way that deadlocks the runtime generation; liveness and closed-state checks for that generation SHALL still apply.

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

#### Scenario: Continuation resumed from within a serialized callback does not self-deadlock
- **WHEN** an executing host-admitted callback synchronously resumes a yielded actor continuation through the invocation boundary
- **THEN** the boundary SHALL run that continuation under the enclosing callback's existing execution serialization
- **AND** it SHALL NOT block waiting for a non-reentrant execution lock already held by that same callback
- **AND** the generation SHALL complete both the callback and the continuation without a timeout-based invalidation
- **AND** continuations resumed from outside any serialized callback SHALL still acquire the execution lock and remain serialized against host-admitted callbacks
