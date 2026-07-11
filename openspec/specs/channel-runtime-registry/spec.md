## Purpose

Defines the host-owned runtime registry that reconciles persisted channel definitions into live runtime instances while preserving committed PTT input targets.

## Requirements

### Requirement: Runtime registry resolves channel instances by ID
The system SHALL maintain a host-owned runtime registry keyed by channel instance ID. Every published catalogue definition SHALL have one corresponding live runtime entry created by the factory registered for its kind, and input preparation SHALL resolve the selected instance through that registry without exhaustive built-in ID branches.

#### Scenario: Catalogue definition creates runtime
- **WHEN** a valid catalogue definition has no corresponding runtime entry
- **THEN** the registry SHALL create an entry using the factory registered for the definition's kind
- **AND** it SHALL associate that entry with the definition's instance ID

#### Scenario: Selected instance prepares input
- **WHEN** PTT preparation requests a known enabled instance whose runtime is ready
- **THEN** the registry SHALL return that runtime's accepted committed input target

#### Scenario: Unknown or unavailable instance prepares input
- **WHEN** input preparation references an unknown, disabled, unready, failed, or unsupported runtime entry
- **THEN** the registry SHALL return a typed refused or unavailable result
- **AND** capture SHALL NOT start for that request

#### Scenario: Additional runtime kind is registered
- **WHEN** a factory for an additional channel kind is registered and the catalogue contains that kind
- **THEN** the runtime SHALL participate in selection, readiness, input routing, and ordered projections without changing core dispatch or projection code

### Requirement: Runtime state projects readiness and execution status generically
Each runtime SHALL expose its instance ID, effective display name, readiness, and generic execution status through a runtime snapshot. Catalogue and runtime changes SHALL update the ordered application projection without persisting ephemeral readiness or execution status into the channel definition.

#### Scenario: External dependency changes readiness
- **WHEN** a runtime's external dependency becomes available or unavailable
- **THEN** the corresponding runtime snapshot SHALL update readiness
- **AND** the persisted definition SHALL remain unchanged

#### Scenario: Runtime reports processing failure
- **WHEN** channel-domain processing fails
- **THEN** the runtime SHALL expose a failed status associated with that instance ID
- **AND** other runtime instances SHALL remain operational

### Requirement: Definition changes reconcile runtime entries
The registry SHALL reconcile each committed catalogue snapshot with its runtime entries. Selection and reorder SHALL preserve existing runtime entries. A definition configuration change SHALL either update its runtime safely or replace and retire it. Removal SHALL retire the removed entry and prevent new input preparation through it.

#### Scenario: Reorder preserves runtime
- **WHEN** a catalogue snapshot changes only list order
- **THEN** the registry SHALL preserve the runtime entries and their domain state
- **AND** the ordered runtime projection SHALL immediately match the reordered catalogue snapshot

#### Scenario: Configuration replacement
- **WHEN** a definition change cannot be safely applied in place
- **THEN** the registry SHALL make a replacement runtime available for subsequent preparation
- **AND** it SHALL retire the previous runtime without redirecting its committed targets

#### Scenario: Removed instance rejects new preparation
- **WHEN** a committed catalogue no longer contains an instance ID
- **THEN** the registry SHALL reject subsequent preparation for that ID
- **AND** it SHALL retire the corresponding runtime entry

### Requirement: Committed input targets survive catalogue reconciliation
Once a runtime accepts an input target, that target SHALL remain bound to the PTT session until it receives one terminal release, cancellation, or failure. Selection, reorder, update, or removal SHALL NOT redirect that session or close its runtime while the target remains committed.

#### Scenario: Active selection changes during capture
- **WHEN** another channel becomes active while a committed target is processing a PTT session
- **THEN** the existing session SHALL continue using the original target
- **AND** subsequent sessions SHALL use the newly active runtime

#### Scenario: Committed instance is removed
- **WHEN** a channel instance is removed while one of its targets is committed
- **THEN** the target SHALL receive its terminal event
- **AND** the retired runtime SHALL close only after its final committed target terminates

#### Scenario: Terminal callback releases runtime lease once
- **WHEN** a committed target reaches release, cancellation, failure, or failed start
- **THEN** the registry SHALL release that target's runtime lease exactly once

### Requirement: Runtime lifecycle is bounded and idempotent
Each runtime SHALL own its channel-domain child work and provide idempotent closure. Registry replacement, removal, and service shutdown SHALL cancel and join owned work before closure completes, prevent late callbacks from mutating current state, and close each runtime exactly once.

#### Scenario: Removed idle runtime closes
- **WHEN** a runtime is removed and has no committed targets
- **THEN** the registry SHALL close it exactly once

#### Scenario: Service shuts down
- **WHEN** the foreground service shuts down
- **THEN** the host SHALL prevent new runtime preparation
- **AND** it SHALL terminate the active host PTT session
- **AND** it SHALL close all live and retired runtimes exactly once

#### Scenario: Late completion after closure
- **WHEN** cancelled runtime work completes after the runtime has closed
- **THEN** it SHALL NOT publish status, playback, or other effects into current application state

### Requirement: Host exclusively owns audio input lifecycle
Channel runtimes and committed targets SHALL receive only channel-level input data and events. The host SHALL remain the sole owner of input-mode route selection, route gating, capture acquisition, ready and error beeps, playback route ordering, and route release. Built-in runtimes SHALL NOT start independent capture sessions or release host audio routes.

#### Scenario: Built-in runtime accepts input
- **WHEN** Journal, Debug, or Keyboard accepts a PTT input
- **THEN** the host SHALL acquire and operate the capture session
- **AND** the runtime SHALL receive only the committed channel input contract

#### Scenario: Runtime requests playback
- **WHEN** a committed target returns a valid playback result
- **THEN** the host SHALL perform playback using the session's resolved output
- **AND** the host SHALL release the route according to the existing endpoint ordering

#### Scenario: Runtime callback throws
- **WHEN** a runtime callback throws a non-cancellation failure
- **THEN** the host SHALL convert it to a typed channel failure
- **AND** it SHALL clear the PTT session and release the route exactly once
- **AND** other runtimes SHALL remain available
