## ADDED Requirements

### Requirement: Input callbacks may durably submit work without retaining input authority
A yield-capable `handle_input` callback MAY call `Queue:submit` for a manifest-declared queue when the package declared `work.queue`. Submission SHALL remain owned by the input execution until its durable admission result returns. Exact `{ok=true}` MAY be returned only after successful durable commit. The resulting queue item SHALL not retain or inherit the callback, Recording, audio route, input operation, opaque audio userdata, secret/profile reference, or input execution owner. Input failure, cancellation, malformed terminal result, timeout, or generation closure before successful commit SHALL create no accepted item.

#### Scenario: Transcript is durably admitted
- **WHEN** input transcribes its Recording and queue submission commits
- **THEN** the callback MAY return `{ok=true}` while a managed worker processes the item later
- **AND** callback/audio ownership SHALL end under the existing terminal rules

#### Scenario: Submission is cancelled before commit
- **WHEN** input cancellation wins before durable admission
- **THEN** the callback SHALL receive cancellation while live or be discarded on close
- **AND** no worker SHALL observe a partial item

### Requirement: Startup may acquire declared queue handles before admitting workers
Synchronous `startup(configuration)` MAY call `subspace.work.open` for a declared queue because opening performs bounded state-local binding without persistent mutation or suspension. It MAY retain the Queue in a function passed to `subspace.runtime.spawn`; the task SHALL begin only after successful activation. Startup SHALL NOT call `Queue:receive`, submit work, claim Jobs, resolve secrets, issue HTTP, or execute work effects.

#### Scenario: Startup creates worker closure
- **WHEN** startup opens `turns` and spawns a function that later calls `receive`
- **THEN** activation SHALL remain synchronous and side-effect free apart from bounded task admission
- **AND** receive SHALL begin only after Ready publication

### Requirement: Dynamic resolver callbacks are not channel lifecycle callbacks
Package choice-resolver modules and their `resolve` function SHALL use the separate resolver contract and SHALL not be added to or invoked through the channel entry module callback table. Resolver execution SHALL not call `startup`, `handle_readiness`, `handle_lifecycle`, `handle_input`, or `handle_sos`, and its return SHALL not update runtime snapshots directly. This separation SHALL preserve the existing exact channel callback table for packages that declare no resolver.

#### Scenario: Package has both channel and resolver modules
- **WHEN** the editor invokes the resolver module
- **THEN** the channel entry callbacks SHALL not execute
- **AND** later channel construction SHALL load its entry module independently

### Requirement: Job effect functions are task-local protected scopes rather than callbacks
A function passed to `Job:effect` SHALL execute only inside the calling managed task after the durable effect-start boundary. It SHALL not become part of the channel callback table, actor mailbox, lifecycle event set, or task registry; it SHALL return to the caller only after durable result commit. Errors and raw yields SHALL be contained to the effect/job according to durable-work rules unless independent actor integrity limits make the generation fatal.

#### Scenario: Effect function throws
- **WHEN** a job effect function raises a Lua error after its start marker
- **THEN** the job SHALL fail or become indeterminate under effect evidence
- **AND** the host SHALL not invoke a channel callback as fallback
