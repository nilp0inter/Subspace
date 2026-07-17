## MODIFIED Requirements

### Requirement: Runtime callbacks do not own asynchronous orchestration
A channel runtime SHALL request durable work, transcription, remote completion, tool execution, synthesis, and playback only through explicit semantic host capabilities. Host-invoked runtime callbacks SHALL remain bounded: they SHALL NOT create process-global scopes, launch untracked threads, own unmanaged network clients, retain PTT callbacks, or implement playback routing or transport cleanup. A conforming host-owned actor MAY own generation-scoped background tasks whose Lua policy controls polling, protocol, retry, and backoff behavior, provided every such task is admitted, tracked, cancelled, and torn down within the actor's runtime generation and does not retain PTT callbacks, recorder state, route leases, or transient audio handles. The host SHALL expose typed acceptance, progress, terminal, cancelled, failed, and indeterminate outcomes and SHALL remain authoritative for Android resource ownership, durable record persistence, playback admission, and platform cleanup.

#### Scenario: Runtime requests asynchronous work
- **WHEN** a runtime needs to continue channel processing after an input event
- **THEN** it SHALL submit a semantic operation with explicit channel and generation identity
- **AND** the host SHALL own scheduling, durable state, cancellation, and effect delivery for that operation
- **AND** no ambient coroutine, thread, SDK client, or platform resource SHALL be injected into the runtime

#### Scenario: Host operation fails
- **WHEN** a host-owned asynchronous stage fails
- **THEN** the framework SHALL deliver a normalized semantic failure associated with the run identity
- **AND** unrelated channel instances and their queues SHALL remain operational

#### Scenario: Host-invoked callback stays bounded
- **WHEN** the host invokes a runtime callback to deliver an event, operation completion, or lifecycle transition
- **THEN** the callback SHALL NOT create a process-global scope, launch an untracked thread, or retain a PTT callback
- **AND** any work that cannot complete within the bounded callback SHALL be deferred to a generation-scoped actor task or a semantic host operation

#### Scenario: Actor owns generation-scoped background work
- **WHEN** a host-owned actor schedules a background task whose Lua policy controls polling, protocol, or retry behavior
- **THEN** the task SHALL be admitted and tracked within the actor's runtime generation
- **AND** the host SHALL cancel or drain the task on generation retirement and SHALL NOT permit it to outlive that generation
- **AND** the task SHALL NOT retain PTT callbacks, recorder state, route leases, or transient audio handles

## ADDED Requirements

### Requirement: Channel selection routes PTT input independently of background execution
Selection of the active channel instance SHALL be the routing decision for new user-driven PTT input. An enabled channel instance that is not the active PTT destination SHALL be permitted to perform independent background work through its host-owned actor while the foreground service is alive, subject to its runtime generation, capability leases, and resource policy. Selection SHALL NOT gate background execution by other enabled instances, and background execution SHALL NOT redirect, preempt, or capture PTT input addressed to the active channel.

#### Scenario: Unselected enabled instance continues background work
- **WHEN** an enabled channel instance is not the active PTT destination and its actor is live
- **THEN** that instance SHALL be permitted to continue independent background work within its generation
- **AND** that work SHALL NOT be suspended merely because the instance is not selected for PTT

#### Scenario: Background execution does not capture PTT input
- **WHEN** a background task of a non-active instance is running and PTT input is prepared
- **THEN** the PTT input SHALL be routed to the active channel instance only
- **AND** the background task SHALL NOT redirect, intercept, or consume that input

#### Scenario: Selection change does not cancel unrelated background work
- **WHEN** the active channel selection changes from instance A to instance B
- **THEN** background work owned by a third enabled instance C SHALL remain within its own generation and queue
- **AND** only the retirement of instance C's generation SHALL cancel or drain that work

### Requirement: Actor work remains generation-scoped
Every actor task, suspended coroutine, outstanding operation, and retained handle SHALL remain scoped to the runtime generation that admitted it. Generation retirement, runtime replacement, or shutdown SHALL cancel or drain all descendant work, SHALL revoke its capability leases, and SHALL prevent late effects from mutating current channel state. No actor work SHALL escape its generation to become process-global, outlive replacement, or retain authorization after the generation closes.

#### Scenario: Generation retirement drains descendant work
- **WHEN** a runtime generation is retired by replacement or shutdown
- **THEN** the host SHALL cancel or drain every background task, suspended coroutine, and outstanding operation owned by that generation
- **AND** no such work SHALL remain schedulable after the generation closes

#### Scenario: Late effect from a retired generation is suppressed
- **WHEN** a late completion or effect from a retired generation arrives after replacement or shutdown
- **THEN** the host SHALL reject or ignore it as stale
- **AND** it SHALL NOT publish a response, play audio, execute a tool, or mutate current channel state

#### Scenario: Actor work does not become process-global
- **WHEN** an actor schedules a long-lived polling, subscription, or retry task
- **THEN** that task SHALL be tracked within the admitting generation and SHALL NOT create a process-global scope, untracked thread, or ambient timer that outlives the generation
- **AND** closing the generation SHALL be sufficient to terminate the task