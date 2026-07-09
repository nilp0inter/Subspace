## ADDED Requirements

### Requirement: Capture service is called by the audio input subsystem
The capture service SHALL remain the low-level owner of the active `AudioRecord`, live frames, terminal PCM, and ready-beep-before-record sequencing. Higher-level PTT source, input-mode, route, and channel lifecycle orchestration SHALL be owned by the audio input subsystem.

#### Scenario: Subsystem starts capture
- **WHEN** the audio input subsystem has resolved and acquired the selected input route
- **THEN** it calls the capture service to run ready-beep and recording setup
- **AND** the capture service returns either a running capture session or a typed setup failure

#### Scenario: Setup fails before handoff
- **WHEN** capture setup fails before a running capture session is handed to the audio input subsystem
- **THEN** the capture service performs its existing setup-failure route cleanup contract
- **AND** the audio input subsystem marks the audio input session failed or cancelled
- **AND** the selected channel does not release the route

#### Scenario: Running capture is handed off
- **WHEN** the capture service returns a running capture session
- **THEN** the audio input subsystem owns when to stop or cancel that session
- **AND** the audio input subsystem delivers live frames and terminal PCM to the selected channel through the channel input contract

### Requirement: Capture cancellation during setup is route-safe
If the audio input session is cancelled while capture setup is suspended after route acquisition and before a running capture session is handed off, the system SHALL release the acquired route exactly once.

#### Scenario: Session cancelled during setup suspension
- **WHEN** an audio input session is cancelled while route acquisition, ready-beep playback, or source open is in progress
- **THEN** the system cancels setup
- **AND** releases any route acquired for that setup exactly once
- **AND** does not leave an active communication device or route lease behind
