## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain at most one active channel at any time. The active channel is the destination for PTT audio captures.

#### Scenario: One channel is active
- **WHEN** a channel is configured and enabled
- **THEN** PTT captures SHALL be dispatched to that channel

#### Scenario: No channel is active
- **WHEN** no channel is configured or all channels are disabled
- **THEN** PTT captures SHALL NOT be dispatched to any channel

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel immediately deactivates all other channels.

#### Scenario: Channel activated while another is active
- **WHEN** Channel A is active and the user activates Channel B
- **THEN** the system SHALL deactivate Channel A and set Channel B as the only active channel
- **AND** PTT captures SHALL be routed to Channel B

## REMOVED Requirements

### Requirement: PTT routing mutual exclusion
**Reason**: Replaced by standard channel activation mutual exclusion. There is no longer a distinction between a "test mode" and a "channel"; both are standard channels.
**Migration**: Use the unified "Active channel selection" and "Channel activation mutual exclusion" rules.
