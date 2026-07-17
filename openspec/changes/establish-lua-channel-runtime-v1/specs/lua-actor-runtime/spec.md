## MODIFIED Requirements

### Requirement: Cooperative coroutine scheduling allows multiple suspensions under a single native entry
The actor SHALL allow multiple logical coroutines to suspend independently and resume through generation-safe opaque operation completions while at most one Lua entry executes at any time. A coroutine that yields a host-operation token SHALL release the native execution thread and SHALL NOT retain an operating-system thread during suspension. The actor SHALL resume the owning coroutine exactly once when the operation completes, and SHALL NOT permit a second coroutine to enter Lua while another coroutine is entered.

Managed background tasks admitted via `subspace.runtime.spawn` MAY remain live until their function returns, fails, or the owning generation is closed. The host SHALL NOT terminate a task solely because a generic wall-clock lifetime has elapsed. The host SHALL bound the maximum number of concurrently managed tasks admitted per generation; exhaustion SHALL return `E_BUSY`. Suspended time — periods during which a background coroutine is suspended in `sleep` or awaiting a host operation — SHALL NOT be charged against active Lua execution limits; only active Lua execution slices remain subject to host-configured instruction-count and wall-clock bounds.

Each `subspace.runtime.sleep` call SHALL establish an operation-specific deadline computed as `requested_delay + bounded_slack`, where `bounded_slack` is a host-configured timer margin. If timer completion wins before that deadline, the host SHALL resume the coroutine with `(true, nil)`. If the deadline wins before timer completion, the host SHALL resume the coroutine exactly once with `(nil, {error = "E_TIMEOUT"})`; a later timer completion SHALL be rejected as stale and SHALL NOT resume Lua again. On generation close, the sleeping coroutine SHALL NOT be resumed and neither timeout, cancellation, nor any outcome SHALL be delivered.

#### Scenario: Multiple coroutines suspend independently
- **WHEN** several coroutines of one actor yield host-operation tokens concurrently
- **THEN** each suspended coroutine SHALL release the native execution thread
- **AND** at most one coroutine SHALL be entered at any time
- **AND** each coroutine SHALL resume exactly once when its owning operation completes

#### Scenario: Coroutine resumes while another is entered
- **WHEN** an operation completes for a suspended coroutine while another coroutine of the same actor is entered
- **THEN** the runtime SHALL queue the resume and SHALL NOT enter the second coroutine concurrently
- **AND** the queued resume SHALL execute once the entered coroutine yields or completes

#### Scenario: Background task loops with periodic sleep beyond a former generic task deadline
- **WHEN** a spawned background task repeatedly performs brief work and calls `subspace.runtime.sleep`, and its cumulative wall-clock lifetime exceeds the generic deadline used by the pre-change actor policy
- **THEN** the runtime SHALL continue to resume the task across sleep boundaries because each sleep establishes a new operation-specific deadline
- **AND** the runtime SHALL NOT terminate the task or fail a sleep solely because cumulative task wall time exceeds that former generic deadline
- **AND** the task MAY remain live as long as each active slice satisfies execution limits, each sleep completes within its operation-specific deadline, and the generation remains open

#### Scenario: Long sleep uses its own operation-specific deadline
- **WHEN** a spawned background task requests a sleep longer than the generic operation deadline used by the pre-change actor policy and timer completion wins before the sleep's own `requested_delay + bounded_slack` deadline
- **THEN** the runtime SHALL resume the coroutine with `(true, nil)`
- **AND** the runtime SHALL NOT fail the sleep solely because elapsed wall time exceeded that former generic deadline

#### Scenario: Sleep deadline wins before timer completion
- **WHEN** a spawned background task calls `subspace.runtime.sleep` and its operation-specific deadline passes before timer completion
- **THEN** the host SHALL classify the operation as timed out
- **AND** it SHALL resume the sleeping coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_TIMEOUT"})`
- **AND** a later timer completion SHALL be rejected as stale without resuming Lua again
- **AND** the coroutine SHALL remain live and available for further host operations

#### Scenario: Infinite active work is interrupted by instruction policy
- **WHEN** a spawned background task enters an infinite pure-Lua loop without yielding
- **THEN** the runtime SHALL interrupt execution within a finite instruction-count bound
- **AND** the actor SHALL close without relying on cooperative Lua return
- **AND** the interruption SHALL NOT depend on task lifetime, accumulated wall time, or a generic operation timeout

#### Scenario: Sleeping coroutine is cleaned up on generation close
- **WHEN** a spawned background task is suspended in `subspace.runtime.sleep` and the owning actor generation is closed or replaced
- **THEN** the host SHALL NOT resume the sleeping coroutine
- **AND** it SHALL NOT deliver a timeout, cancellation, or any outcome to the coroutine
- **AND** the generation's operation tokens SHALL be invalidated and the Lua state closed

#### Scenario: Task admission limit is exhausted
- **WHEN** a callback calls `subspace.runtime.spawn` and the per-generation maximum number of concurrently managed background tasks is already reached
- **THEN** the spawn call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the function SHALL NOT be executed or queued
