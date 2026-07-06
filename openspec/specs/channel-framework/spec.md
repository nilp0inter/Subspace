## Purpose

TBD. Defines channel identity, selection, routing, and persisted configuration.

## Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel at any time, identified by
a unique ID. The active channel is the intended destination for PTT audio
captures, subject to readiness evaluation. The set of channels SHALL include
`JournalChannel`, `DebugChannel`, and `KeyboardChannel`.

#### Scenario: One channel is active
- **WHEN** a channel is selected as active
- **THEN** PTT captures SHALL be evaluated against that channel's readiness
  state

#### Scenario: Keyboard channel selected as active
- **WHEN** the user activates the keyboard channel
- **THEN** it SHALL become the sole active channel
- **AND** PTT captures SHALL be routed to it, provided it is ready

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel inherently establishes it as the sole active channel.

#### Scenario: Channel activated while another is active
- **WHEN** Channel A is active and the user activates Channel B
- **THEN** the system SHALL set Channel B as the active channel
- **AND** PTT captures SHALL be routed to Channel B, provided it is ready

### Requirement: Channel configuration persistence
The system SHALL persist channel configuration across app restarts for all
channels, including `KeyboardChannel`. Configuration changes SHALL take effect
immediately without requiring a service restart.

#### Scenario: App restarted after keyboard channel configuration
- **WHEN** the user configures the keyboard channel's host profile and the app
  is killed and restarted
- **THEN** the keyboard channel configuration SHALL be restored to the
  previously saved state

#### Scenario: Keyboard host profile changed at runtime
- **WHEN** the user changes the keyboard channel's host profile while the
  service is running
- **THEN** the new profile SHALL take effect for the next PTT capture without
  restarting the service
