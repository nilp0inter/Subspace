## MODIFIED Requirements

### Requirement: Recording starts only after the ready beep completes
The system SHALL deliver channel-visible audio only after the ready beep playback has fully completed, preserving the `sco-audio` capture-priming contract and the user-facing meaning that post-beep speech reaches the committed channel. The audio input subsystem MAY open or pre-arm the capture source before the ready beep to prove capture readiness, but any frames captured before ready beep completion SHALL NOT be delivered to channels as user speech. This sequencing SHALL be owned by the capture/audio input subsystem, not duplicated per channel controller. The capture service SHALL release the acquired route on every `startSession` outcome that acquired a route and did not hand off a committed running session, so failed setup does not leak route resources. Channel controllers SHALL NOT release the route on those outcomes.

#### Scenario: Channel-visible recording starts after beep completes while PTT held
- **WHEN** route readiness, channel commitment, and capture preflight have succeeded
- **AND** the ready beep finishes playing
- **AND** PTT is still held
- **THEN** the system delivers subsequent captured microphone audio to the committed channel target

#### Scenario: PTT released during the ready beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system does not deliver channel-visible user audio
- **AND** the system cancels the session
- **AND** the capture service or audio input subsystem releases the acquired route exactly once
- **AND** the channel controller SHALL NOT additionally release the route

#### Scenario: PTT released during route or capture preflight
- **WHEN** route acquisition or capture preflight is in progress
- **AND** the user releases PTT before the ready beep completes
- **THEN** the system does not play the ready beep if it has not started
- **AND** the system does not deliver channel-visible user audio
- **AND** the audio input subsystem cancels the session and releases acquired route resources exactly once

#### Scenario: Source preflight fails before ready beep
- **WHEN** the capture source cannot be opened or proven usable before the ready beep contract is satisfied
- **THEN** the system does not play the ready beep
- **AND** the system does not start committed channel recording
- **AND** the audio input subsystem releases acquired route resources exactly once
- **AND** problem feedback is provided when possible

#### Scenario: Source fails after ready beep
- **WHEN** the ready beep completes and channel-visible capture has been committed
- **AND** the capture source subsequently fails
- **THEN** the system reports failure or cancellation to the committed channel target
- **AND** releases route resources exactly once

### Requirement: Capture service is called by the audio input subsystem
The capture service SHALL remain the low-level owner of the active `AudioRecord`, live frames, terminal PCM, and ready-beep-before-user-speech sequencing. Higher-level PTT source, input-mode, route, channel commitment, and route lifecycle orchestration SHALL be owned by the audio input subsystem. Capture startup outcomes SHALL give the audio input subsystem enough information to withhold ready beep, fail closed, and release the session route when committed user audio cannot be delivered.

#### Scenario: Subsystem starts capture preflight
- **WHEN** the audio input subsystem has resolved and validated the selected input route
- **AND** the selected channel target has accepted the input request
- **THEN** it calls the capture service to perform capture preflight, ready-beep sequencing, and recording setup
- **AND** the capture service returns either a committed running capture session or a typed setup failure

#### Scenario: Setup fails before handoff
- **WHEN** capture setup fails before a committed running capture session is handed to the audio input subsystem
- **THEN** the capture service closes any low-level recorder resources it opened
- **AND** the audio input subsystem marks the audio input session failed or cancelled
- **AND** the audio input subsystem releases the route associated with that session exactly once
- **AND** the selected channel does not release the route

#### Scenario: Running capture is handed off
- **WHEN** the capture service returns a committed running capture session
- **THEN** the audio input subsystem owns when to stop or cancel that session
- **AND** the audio input subsystem delivers post-beep live frames and terminal PCM to the committed channel target through the channel input contract
