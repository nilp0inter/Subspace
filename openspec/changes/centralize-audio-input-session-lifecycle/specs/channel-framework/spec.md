## ADDED Requirements

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
