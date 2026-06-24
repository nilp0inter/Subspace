## Purpose

TBD. Defines channel identity, selection, routing, and persisted configuration.

## Requirements

### Requirement: Channel data model
The system SHALL represent each channel as a typed entity with an identifier, a user-visible name, an enabled state, and type-specific configuration.

#### Scenario: Channel has identity and configuration
- **WHEN** a channel exists in the system
- **THEN** it SHALL have a unique string identifier, a display name, and an enabled/disabled state

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

### Requirement: Channel configuration persistence
The system SHALL persist channel configuration across app restarts. Configuration changes SHALL take effect immediately without requiring a service restart.

#### Scenario: App restarted after channel configuration
- **WHEN** the user configures a channel and the app is killed and restarted
- **THEN** the channel configuration SHALL be restored to the previously saved state

#### Scenario: Configuration changed at runtime
- **WHEN** the user changes a channel configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT capture without restarting the service
