## REMOVED Requirements

### Requirement: Channel data model
**Reason**: The enabled state is replaced by a single global active channel.
**Migration**: Use the `isReady` property and the `activeChannelId` in `AppState`.

## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel at any time, identified by a unique ID. The active channel is the intended destination for PTT audio captures, subject to readiness evaluation.

#### Scenario: One channel is active
- **WHEN** a channel is selected as active
- **THEN** PTT captures SHALL be evaluated against that channel's readiness state

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel inherently establishes it as the sole active channel.

#### Scenario: Channel activated while another is active
- **WHEN** Channel A is active and the user activates Channel B
- **THEN** the system SHALL set Channel B as the active channel
- **AND** PTT captures SHALL be routed to Channel B, provided it is ready
