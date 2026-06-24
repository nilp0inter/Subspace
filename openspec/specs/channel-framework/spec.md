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
- **THEN** PTT captures SHALL be dispatched to the legacy test controller if one is active

### Requirement: PTT routing mutual exclusion
The system SHALL ensure that the active channel and the legacy test mode (echo, STT test, TTS test, STT+TTS test) are mutually exclusive. Only one dispatch path SHALL be active at any time.

#### Scenario: Channel activated while test mode is running
- **WHEN** a test mode is active and the user activates a channel
- **THEN** the system SHALL deactivate the test mode and route PTT to the channel

#### Scenario: Test mode activated while channel is active
- **WHEN** a channel is active and the user starts a test mode
- **THEN** the system SHALL deactivate the channel routing and route PTT to the test controller

### Requirement: Channel configuration persistence
The system SHALL persist channel configuration across app restarts. Configuration changes SHALL take effect immediately without requiring a service restart.

#### Scenario: App restarted after channel configuration
- **WHEN** the user configures a channel and the app is killed and restarted
- **THEN** the channel configuration SHALL be restored to the previously saved state

#### Scenario: Configuration changed at runtime
- **WHEN** the user changes a channel configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT capture without restarting the service
