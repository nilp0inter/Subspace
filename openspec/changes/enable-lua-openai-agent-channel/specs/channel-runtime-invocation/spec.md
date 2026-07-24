## ADDED Requirements

### Requirement: Input invocation supports durable work submission
The `HANDLE_INPUT` phase SHALL permit a package with declared `work.queue` to submit bounded work through its input execution owner. Submission MAY yield and release the serialized Lua slot, but the input phase SHALL not report exact success until durable admission commits. The phase deadline, cancellation, replacement, and terminal-result gate SHALL cover submission. Successful admission SHALL detach the queue item from PTT/audio/callback ownership; no accepted work MAY retain a Recording, route, callback, or input execution token.

#### Scenario: Input admits asynchronous turn
- **WHEN** a committed capture is transcribed and queue submission commits within the phase deadline
- **THEN** exact `{ok=true}` SHALL complete the input callback and release route ownership
- **AND** managed queue processing MAY continue after the PTT operation ends

#### Scenario: Input closes during submission
- **WHEN** generation close wins before durable commit
- **THEN** the callback SHALL be discarded under close semantics
- **AND** no partial queue item SHALL become claimable

### Requirement: Managed workers wait for durable work without polling
A startup-admitted managed task MAY call `Queue:receive` after Ready publication. The receive operation SHALL suspend under actor/task ownership, release the execution slot, and wake only for claimable FIFO work or terminal lifecycle outcome. It SHALL not require periodic `sleep`, busy polling, a retained thread, selection state, PTT activity, or a second service. At most one active Job per queue SHALL be admitted by the durable subsystem.

#### Scenario: Empty queue worker waits
- **WHEN** a worker calls receive on an empty live queue
- **THEN** it SHALL suspend without consuming active Lua instruction budget or an OS thread
- **AND** a later committed submission SHALL wake it under the same generation if still current

### Requirement: Replacement cause determines work-epoch reconciliation
Runtime invocation/reconciliation SHALL distinguish ordinary process reconstruction with unchanged package/configuration/profile revision from intentional SOS, configuration/profile change, package update/rollback/removal, instance deletion, and shutdown. Unchanged restart SHALL allow safe durable work reclamation into a fresh actor. Intentional replacement/reset SHALL retire the prior epoch before successor work admission; safe unstarted items become cancelled and ambiguous effects indeterminate. Successor Ready publication SHALL not race predecessor work authority.

#### Scenario: Profile edit replaces active runtime
- **WHEN** a selected profile revision changes while a Job is active
- **THEN** predecessor admission/effects SHALL be revoked and reconciled before successor readiness
- **AND** no late predecessor result SHALL update successor conversation or status

#### Scenario: Process restarts with queued item
- **WHEN** active package/configuration/profile revisions are unchanged and the item has no ambiguous effect
- **THEN** a fresh worker SHALL receive it in original FIFO order
- **AND** no predecessor Lua state SHALL be restored

### Requirement: Generic work status projects without provider payloads
Runtime snapshots and channel surfaces SHALL derive bounded idle, queued, active, failed, and indeterminate execution state plus queued count from the generic work subsystem where a package declares queues. Projection SHALL remain associated with the channel instance and current work epoch, shall coexist with existing preparation/readiness, and SHALL not expose payloads, effect keys/results, profile values, secrets, provider protocol, or ledger internals.

#### Scenario: Turn remains queued
- **WHEN** one Job is active and another is waiting
- **THEN** the channel projection SHALL report active processing and bounded queued count
- **AND** surfaces SHALL not receive transcript or provider data
