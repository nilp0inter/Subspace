## MODIFIED Requirements

### Requirement: Runtime registry resolves channel instances by ID
The system SHALL maintain a host-owned runtime registry keyed by stable channel instance ID. Every published catalogue definition SHALL have exactly one corresponding registry entry resolved from its stable implementation provider reference. An available provider entry SHALL own the live runtime constructed from the provider-validated configuration payload; a missing, incompatible, migration-failed, construction-failed, or otherwise unavailable provider SHALL produce an explicit unavailable entry for that same instance ID. Input preparation SHALL resolve the selected instance through the registry without exhaustive built-in ID, provider, or channel-kind branches.

#### Scenario: Catalogue definition creates runtime
- **WHEN** a valid catalogue definition has no corresponding registry entry and its referenced provider is available
- **THEN** the registry SHALL ask that provider to construct a runtime from the validated configuration payload
- **AND** it SHALL associate the resulting entry with the definition's stable instance ID

#### Scenario: Selected instance prepares input
- **WHEN** PTT preparation requests a known enabled instance whose runtime is ready
- **THEN** the registry SHALL return that runtime's accepted committed input target

#### Scenario: Provider is unavailable
- **WHEN** a valid catalogue definition references a provider that is missing, incompatible, failed migration, or failed runtime construction
- **THEN** the registry SHALL retain an explicit unavailable entry associated with that definition's stable instance ID
- **AND** the entry SHALL expose a typed actionable reason without discarding the catalogue definition or opaque configuration payload

#### Scenario: Unknown or unavailable instance prepares input
- **WHEN** input preparation references an unknown, disabled, unready, failed, or unavailable registry entry
- **THEN** the registry SHALL return a typed refused or unavailable result
- **AND** capture SHALL NOT start for that request

#### Scenario: Additional provider is registered
- **WHEN** an additional channel implementation provider is registered and the catalogue contains a definition referencing it
- **THEN** its runtime SHALL participate in selection, readiness, input routing, and ordered projections without changing core dispatch or projection code

### Requirement: Runtime state projects readiness and execution status generically
Each registry entry SHALL expose its instance ID, effective display name, availability, readiness, and generic execution status through a runtime snapshot. An unavailable entry SHALL remain in the ordered projection at its catalogue position and SHALL expose a typed unavailability reason. Catalogue and runtime changes SHALL update the ordered application projection without persisting ephemeral availability, readiness, execution status, or failure diagnostics into the channel definition.

#### Scenario: External dependency changes readiness
- **WHEN** a runtime's semantic host dependency becomes available or unavailable
- **THEN** the corresponding runtime snapshot SHALL update readiness
- **AND** the persisted definition SHALL remain unchanged

#### Scenario: Runtime reports processing failure
- **WHEN** channel-domain processing fails
- **THEN** the runtime SHALL expose a failed status associated with that instance ID
- **AND** other runtime instances SHALL remain operational

#### Scenario: Unavailable entry is projected in order
- **WHEN** an ordered catalogue contains a definition whose provider cannot supply a runtime
- **THEN** the registry SHALL project an unavailable snapshot at that definition's catalogue position
- **AND** the snapshot SHALL retain the definition's stable instance ID and effective display name
- **AND** later entries SHALL NOT shift forward as if the unavailable definition were absent

### Requirement: Committed input targets survive catalogue reconciliation
Once a runtime accepts an input target, the registry SHALL hold a committed runtime lease for that target until the target has received and completed exactly one terminal release, cancellation, failure, or failed-start callback. Selection, reorder, update, removal, provider replacement, or shutdown SHALL NOT redirect the session or close the leased runtime. A retired runtime SHALL close only after new preparation has been prevented and its final committed lease has been released.

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
- **THEN** subsequent preparation SHALL use the replacement runtime
- **AND** the previous runtime SHALL remain alive until its final committed target completes and releases its lease

#### Scenario: Terminal callback releases runtime lease once
- **WHEN** a committed target reaches release, cancellation, failure, or failed start
- **THEN** the host SHALL complete the target's terminal callback before the registry releases that target's runtime lease
- **AND** the registry SHALL release that lease exactly once

### Requirement: Runtime lifecycle is bounded and idempotent
Each runtime SHALL own its channel-domain child work and provide idempotent closure. Registry replacement, removal, and service shutdown SHALL first prevent new preparation, then terminate and await committed targets, then release their leases, and only then cancel and join remaining runtime-owned work and close the runtime exactly once. Closure SHALL prevent late callbacks from mutating current state. The registry SHALL NOT invoke provider or runtime code while holding an internal registry lock.

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

## ADDED Requirements

### Requirement: Registry callbacks execute outside internal locks
The registry SHALL establish state transitions and ownership under its internal synchronization, but it SHALL invoke provider migration, validation, runtime construction, preparation, update, target, and close callbacks only after releasing registry locks. Callback completion SHALL be committed back under synchronization only if the originating entry generation is still current. A slow, reentrant, suspended, or failing callback SHALL NOT block unrelated registry lookup, projection, reconciliation, or lease release.

#### Scenario: Provider constructs a runtime
- **WHEN** reconciliation needs a provider to construct a runtime
- **THEN** the registry SHALL record the pending entry generation before releasing its lock
- **AND** it SHALL invoke the provider outside the lock
- **AND** it SHALL install the result only if that definition generation remains current

#### Scenario: Runtime preparation reenters registry observation
- **WHEN** a runtime preparation callback synchronously or asynchronously observes registry state
- **THEN** the observation SHALL complete without deadlocking on a lock held across that callback
- **AND** unrelated registry entries SHALL remain accessible

#### Scenario: Callback completes after entry changes
- **WHEN** a provider or runtime callback completes after its definition was replaced, removed, or made unavailable
- **THEN** the registry SHALL NOT publish the stale result into the current entry
- **AND** it SHALL retire or close any stale runtime result outside the registry lock and according to committed-lease ordering

#### Scenario: Callback throws
- **WHEN** a provider or runtime callback throws a non-cancellation failure
- **THEN** the registry SHALL normalize it to the appropriate typed unavailable or failed state after reacquiring synchronization
- **AND** it SHALL leave unrelated entries and registry progress operational
