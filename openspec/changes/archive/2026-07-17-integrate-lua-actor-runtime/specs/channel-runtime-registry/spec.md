## MODIFIED Requirements

### Requirement: Committed input targets survive catalogue reconciliation
Once a runtime accepts an input target, the registry SHALL hold a committed runtime lease for that target until the target has received and completed exactly one terminal release, cancellation, failure, or failed-start callback. Selection, reorder, update, removal, provider replacement, or shutdown SHALL NOT redirect the session or close the leased runtime. A retired runtime SHALL close only after new preparation has been prevented and its final committed lease has been released.

A successor runtime generation SHALL NOT be published ready or become current for new work for the same channel instance until its predecessor has stopped admission of new callbacks, the predecessor's committed input target has terminated and completed exactly one terminal callback, the predecessor's owned descendants and outstanding effects have drained or been cancelled, the predecessor's instance-scoped capability leases have been revoked, and the predecessor runtime has closed exactly once. While the predecessor drains, the registry SHALL route new preparation to the successor only after the predecessor has closed; it SHALL NOT route new preparation to a predecessor that has stopped admission. The committed terminal callback of the predecessor's target SHALL NOT be redirected to, or replayed against, the successor generation.

#### Scenario: Active selection changes during capture
- **WHEN** another channel becomes active while a committed target is processing a PTT session
- **THEN** the existing session SHALL continue using the original target
- **AND** subsequent sessions SHALL use the newly active runtime

#### Scenario: Committed instance is removed
- **WHEN** a channel instance is removed while one of its targets is committed
- **THEN** the registry SHALL prevent new preparation through the retired entry
- **AND** the target SHALL receive and complete its terminal event
- **AND** the retired runtime SHALL close only after its final committed lease is released

#### Scenario: Runtime is replaced while target is committed
- **WHEN** reconciliation installs a replacement runtime while a target from the previous runtime remains committed
- **THEN** subsequent preparation SHALL use the replacement runtime only after the previous runtime has stopped admission, its committed target has terminated, its descendants and effects have drained or been cancelled, its capability leases have been revoked, and it has closed
- **AND** the previous runtime SHALL remain alive until its final committed target completes and releases its lease
- **AND** the replacement SHALL NOT be published ready for new work before that predecessor closure completes

#### Scenario: Terminal callback releases runtime lease once
- **WHEN** a committed target reaches release, cancellation, failure, or failed start
- **THEN** the host SHALL complete the target's terminal callback before the registry releases that target's runtime lease
- **AND** the registry SHALL release that lease exactly once

#### Scenario: Successor is prepared before predecessor closes
- **WHEN** a successor generation is constructed before its predecessor's committed target has terminated
- **THEN** the registry SHALL NOT publish the successor as ready or current for new work
- **AND** new preparation SHALL NOT be routed to the successor until the predecessor has closed
- **AND** the predecessor's committed terminal callback SHALL NOT be delivered to the successor

### Requirement: Runtime lifecycle is bounded and idempotent
Each runtime SHALL own its channel-domain child work and provide idempotent closure. Registry replacement, removal, and service shutdown SHALL first prevent new preparation, then terminate and await committed targets, then release their leases, and only then cancel and join remaining runtime-owned work and close the runtime exactly once. Closure SHALL prevent late callbacks from mutating current state. The registry SHALL NOT invoke provider or runtime code while holding an internal registry lock.

For a runtime that owns cooperative descendants, such as an actor's suspended coroutines, operation tokens, and ready-queue work, replacement and removal SHALL drain or cancel those descendants and revoke their generation-scoped capability authorizations before the runtime closes. A successor generation SHALL NOT become ready or current for new work until the predecessor has completed this full drain-revoke-close sequence. The registry SHALL NOT persist or restore volatile descendant state, such as suspended coroutines, mailbox entries, operation tokens, or ready-queue positions, across the replacement or across process restart.

#### Scenario: Removed idle runtime closes
- **WHEN** a runtime is removed and has no committed targets
- **THEN** the registry SHALL prevent new preparation and close it exactly once

#### Scenario: Service shuts down
- **WHEN** the foreground service shuts down
- **THEN** the host SHALL prevent new runtime preparation
- **AND** it SHALL terminate and await the active host PTT session before releasing its committed target lease
- **AND** it SHALL close all live and retired runtimes exactly once only after their committed leases are released

#### Scenario: Runtime close callback executes outside lock
- **WHEN** reconciliation or shutdown requires a runtime to close
- **THEN** the registry SHALL select and mark the runtime for closure while synchronized
- **AND** it SHALL invoke and await runtime closure only after releasing its internal lock

#### Scenario: Late completion after closure
- **WHEN** cancelled runtime work completes after the runtime has closed
- **THEN** it SHALL NOT publish status, playback, or other effects into current application state

#### Scenario: Replacement drains cooperative descendants
- **WHEN** reconciliation replaces a runtime that owns suspended coroutines or outstanding operation tokens
- **THEN** the registry SHALL cancel or drain those descendants and revoke their generation-scoped capability authorizations before the predecessor closes
- **AND** the successor SHALL NOT become ready for new work until that drain-revoke-close sequence completes
- **AND** no volatile descendant state SHALL be persisted or restored into the successor

## ADDED Requirements

### Requirement: Host-owned actor readiness and failure latch are projected
For a runtime that exposes an actor scheduling boundary, the host SHALL own a per-generation readiness and fatal-failure latch projected through the registry entry. The host SHALL publish ready only after protected startup reports a normalized ready outcome; a startup coroutine suspended on an operation SHALL remain initializing until it resumes and reports ready. Startup, lifecycle-critical, instruction, memory, ownership-integrity, or another generation-fatal outcome SHALL latch failed. An ordinary event, background-task, or host-operation failure SHALL remain local unless explicitly escalated as generation-fatal. Neither latch SHALL be persisted or restored across a service or process restart.

#### Scenario: Actor becomes ready
- **WHEN** an actor's protected startup reports a normalized ready outcome
- **THEN** the host SHALL publish the registry entry as ready through the host-owned latch
- **AND** selection and projection SHALL observe that readiness without querying the in-actor scheduler

#### Scenario: Startup suspends
- **WHEN** an actor's startup coroutine yields an operation before reporting ready
- **THEN** the host SHALL keep the registry entry in its initializing state
- **AND** the actor SHALL NOT admit ordinary events or background work until startup resumes and reports ready

#### Scenario: Actor reaches a fatal failure
- **WHEN** startup, lifecycle-critical execution, instruction policy, memory policy, or ownership integrity reaches a generation-fatal outcome
- **THEN** the host SHALL latch the registry entry as failed
- **AND** a later in-actor resumption SHALL NOT clear the latch

#### Scenario: Ordinary operation fails locally
- **WHEN** an ordinary event, background task, or yielded host operation reports a normalized non-fatal failure
- **THEN** the host SHALL report that failure to its owning event, task, or coroutine
- **AND** it SHALL NOT latch the actor failed solely because of that local failure

#### Scenario: Service restarts after readiness or failure
- **WHEN** the service or process restarts after an actor was ready or failed
- **THEN** the registry SHALL create a fresh generation with fresh readiness and failure latches
- **AND** it SHALL NOT restore the prior generation's readiness, failure latch, suspended coroutines, mailbox entries, or operation tokens

### Requirement: Actor scope authorization is generation-scoped
For a runtime that exposes an actor scheduling boundary, every coroutine resumption, operation-token completion, descendant task, and capability invocation SHALL carry the generation authorization of the runtime that owns it. The host SHALL reject any descendant, resumption, or capability effect whose generation authorization is not current for the owning channel instance. A successor MAY receive an internal identity sufficient to create its state and load or validate source while staged, but the host SHALL NOT issue its event-admission or effect authorization before the predecessor has closed. A predecessor's outstanding authorizations SHALL be revoked before close. Authorizations SHALL be opaque host-domain values and SHALL NOT expose Lua state references, coroutine pointers, or in-actor scheduler objects to the registry, capabilities, or persisted records.

#### Scenario: Resumption carries current generation authorization
- **WHEN** an operation-token completion resumes a suspended coroutine on an actor runtime
- **THEN** the completion SHALL carry the generation authorization of the owning runtime
- **AND** the host SHALL reject it if that generation is no longer current for the channel instance

#### Scenario: Capability invocation is generation-scoped
- **WHEN** an actor descendant invokes a host capability
- **THEN** the capability boundary SHALL require a current generation authorization
- **AND** it SHALL reject invocations whose authorization belongs to a closed or superseded generation

#### Scenario: Successor effect authorization is withheld until predecessor closes
- **WHEN** a successor generation is staged while the predecessor has not closed
- **THEN** the successor MAY create state and load or validate source without running plugin startup or effects
- **AND** the host SHALL NOT issue successor event-admission or effect authorization until the predecessor has closed
- **AND** the predecessor's outstanding authorizations SHALL be revoked before its close completes

#### Scenario: Authorization remains opaque
- **WHEN** a generation authorization crosses the registry, capability, or persistence boundary
- **THEN** it SHALL be an opaque host-domain value
- **AND** it SHALL NOT expose a Lua state reference, coroutine pointer, or in-actor scheduler object

### Requirement: Fresh-generation recovery does not persist volatile coroutine state
On service or process restart, a reconstructed actor runtime SHALL start from a fresh generation and SHALL NOT restore suspended coroutines, mailbox entries, operation tokens, ready-queue positions, or in-actor scheduler state from the prior process. Persisted durable host records, durable run records, and durable messages MAY be reconciled through an explicit host recovery operation under the fresh generation, but volatile actor state SHALL NOT be persisted or replayed. A late completion bearing a prior generation's operation-token authorization SHALL be classified as stale regardless of its run status, and SHALL NOT resume a coroutine in the fresh generation.

#### Scenario: Restart discards volatile actor state
- **WHEN** the service restarts while an actor runtime has suspended coroutines, pending mailbox entries, or outstanding operation tokens
- **THEN** the fresh generation SHALL NOT restore any suspended coroutine, mailbox entry, operation token, or ready-queue position
- **AND** the fresh actor SHALL reconstruct its work only through an explicit host recovery operation using durable host records

#### Scenario: Late operation completion after restart
- **WHEN** an operation-token completion from the prior generation arrives after a fresh actor generation is installed
- **THEN** the registry SHALL classify it as stale
- **AND** it SHALL NOT resume a coroutine in the fresh generation
- **AND** it SHALL NOT publish an effect, mutate readiness, or advance a current run

#### Scenario: Durable recovery is explicit and fresh
- **WHEN** the host recovers durable run and message records into a fresh actor generation
- **THEN** the recovery SHALL use host-owned durable state under the fresh generation authorization
- **AND** it SHALL NOT import volatile conversation context or actor scheduler state from the prior process