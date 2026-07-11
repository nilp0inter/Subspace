## Purpose

TBD. Defines channel identity, selection, routing, and persisted configuration.

## Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel instance at any time, identified by a stable ID present in the persisted ordered channel catalogue. The active channel instance is the intended destination for PTT audio captures, subject to its runtime readiness evaluation. The migrated default catalogue SHALL include Journal, Debug, and Keyboard instances, while the runtime SHALL support catalogue additions, removals, and multiple instances of a supported kind.

#### Scenario: One channel instance is active
- **WHEN** a channel instance is selected as active
- **THEN** PTT captures SHALL be evaluated against that instance's runtime readiness state
- **AND** every other catalogue instance SHALL be inactive

#### Scenario: Multiple instances share a kind
- **WHEN** the catalogue contains two instances of the same supported channel kind
- **THEN** either instance SHALL be independently selectable by its stable instance ID
- **AND** selection SHALL NOT be inferred from channel kind

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel inherently establishes it as the sole active channel.

#### Scenario: Channel activated while another is active
- **WHEN** Channel A is active and the user activates Channel B
- **THEN** the system SHALL set Channel B as the active channel
- **AND** PTT captures SHALL be routed to Channel B, provided it is ready

### Requirement: Channel configuration persistence
The system SHALL persist the ordered channel definitions and kind-specific configuration across app restarts, including every Journal, Debug, and Keyboard instance. Configuration changes SHALL be addressed by stable instance ID and take effect for subsequent PTT preparation without requiring a service restart. A configuration change SHALL NOT alter the target already committed to an active PTT session.

#### Scenario: App restarted after instance configuration
- **WHEN** the user configures a channel instance and the app is killed and restarted
- **THEN** that instance's ID, kind, name, position, enabled state, and kind-specific configuration SHALL be restored

#### Scenario: Built-in configuration changed at runtime
- **WHEN** the user changes a built-in channel instance's valid configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT preparation for that instance
- **AND** the current committed target, if any, SHALL retain its accepted configuration snapshot

#### Scenario: Configuration action targets one instance
- **WHEN** a kind-specific editor changes a definition while another instance shares that kind
- **THEN** the action SHALL retain the editor's instance ID through navigation and persistence
- **AND** it SHALL NOT read or mutate a same-kind definition selected by list position or legacy singleton ID

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
A channel target that has accepted input SHALL either consume the input events for that session or report failure or cancellation through the channel input contract. It SHALL NOT silently ignore a committed input start because selection, order, configuration, catalogue membership, or mutable runtime state changed after acceptance.

#### Scenario: Committed target snapshots required definition state
- **WHEN** a runtime accepts a PTT input request
- **THEN** the target SHALL bind the instance ID and all configuration and domain resources required for that session before the ready beep is played
- **AND** subsequent catalogue mutations SHALL NOT redirect the committed session

#### Scenario: Journal input target commits paths before ready beep
- **WHEN** a Journal instance accepts a PTT input request
- **THEN** Journal SHALL have the base directory and entry paths needed to process the input before the ready beep is played
- **AND** the committed session SHALL use those paths for live-frame writing and terminal metadata

#### Scenario: Debug input target snapshots mode before ready beep
- **WHEN** a Debug instance accepts a PTT input request
- **THEN** Debug SHALL bind the current debug mode and controller target for that session before the ready beep is played
- **AND** release for that session SHALL use the same committed debug target

#### Scenario: Instance removed after commitment
- **WHEN** a channel instance is removed after its target has accepted an input request
- **THEN** the target SHALL continue to receive the terminal event for that session
- **AND** the removed instance SHALL refuse subsequent input preparation
