## MODIFIED Requirements

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

## ADDED Requirements

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
