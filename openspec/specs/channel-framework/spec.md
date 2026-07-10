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
Channels SHALL consume PTT input through channel-level events and audio data supplied by the audio input subsystem. Channels SHALL expose whether they can accept a candidate input before the audio input subsystem plays the ready beep. Channels SHALL NOT receive `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, `CaptureSource`, `InputMode`, Android audio route objects, Bluetooth HFP state, Telecom endpoint state, route-gate status, or recorder diagnostic objects.

#### Scenario: Active channel accepts input session
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the channel is configured, initialized, and able to process this input
- **THEN** the channel provides an accepted channel input target to the audio input subsystem
- **AND** the target can consume live frames and terminal PCM through the channel input contract
- **AND** the channel does not receive the route-readiness facts used before the start event

#### Scenario: Active channel refuses input before ready beep
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the channel is missing required configuration, unavailable, uninitialized, or unable to create its input target
- **THEN** the channel reports refusal to the audio input subsystem
- **AND** the audio input subsystem SHALL NOT play the ready beep for that PTT
- **AND** route cleanup remains owned by the audio input subsystem

#### Scenario: Channel does not own input route cleanup
- **WHEN** PTT capture ends, fails, or is cancelled
- **THEN** the committed channel target handles its domain work and status updates
- **AND** the audio input subsystem releases any route resources
- **AND** the channel does not release SCO, Telecom, or local route resources directly

#### Scenario: Channel switch during active session
- **WHEN** the active channel selection changes while an audio input session is active
- **THEN** the session remains associated with the channel target accepted at session commitment until the session is released or cancelled
- **AND** changing channel selection does not by itself leak or release the active audio route outside the audio input subsystem

#### Scenario: Route or commitment failure is reported without route internals
- **WHEN** an input session fails because Android route state, Telecom state, Bluetooth HFP state, recorder state, or channel acceptance cannot prove a committed input path
- **THEN** the selected channel receives only cancellation or failure through the channel input contract if a channel target exists
- **AND** the channel does not receive Android route objects or route-gate internals

### Requirement: Channels do not silently drop committed input
A channel target that has accepted input SHALL either consume the input events for that session or report failure/cancellation through the channel input contract. It SHALL NOT silently ignore a committed input start due to mutable channel state that changed after acceptance.

#### Scenario: Journal input target commits paths before ready beep
- **WHEN** Journal accepts a PTT input request
- **THEN** Journal SHALL have the base directory and entry paths needed to process the input before the ready beep is played
- **AND** the committed session uses those paths for live-frame writing and terminal metadata

#### Scenario: Debug input target snapshots mode before ready beep
- **WHEN** Debug accepts a PTT input request
- **THEN** Debug SHALL bind the current debug mode/controller target for that session before the ready beep is played
- **AND** release for that session SHALL use the same committed debug target
