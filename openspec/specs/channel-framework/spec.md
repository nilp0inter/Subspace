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

### Requirement: Channels consume audio input events
Channels SHALL consume PTT input through channel-level events and audio data supplied by the audio input subsystem. Channels SHALL NOT receive `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, `CaptureSource`, `InputMode`, or Android audio route objects.

#### Scenario: Active channel receives input session
- **WHEN** PTT capture starts for the active channel
- **THEN** the active channel receives a channel input session or started event from the audio input subsystem
- **AND** the channel can consume live frames and terminal PCM through that contract

#### Scenario: Channel does not own input route cleanup
- **WHEN** PTT capture ends, fails, or is cancelled
- **THEN** the channel handles its domain work and status updates
- **AND** the audio input subsystem releases any route resources
- **AND** the channel does not release SCO, Telecom, or local route resources directly

#### Scenario: Channel switch during active session
- **WHEN** the active channel selection changes while an audio input session is active
- **THEN** the session remains associated with the channel selected at session start until the session is released or cancelled
- **AND** changing channel selection does not by itself leak or release the active audio route outside the audio input subsystem
