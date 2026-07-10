## MODIFIED Requirements

### Requirement: Capture service is called by the audio input subsystem
The capture service SHALL remain the low-level owner of the active `AudioRecord`, live frames, terminal PCM, and ready-beep-before-record sequencing. Higher-level PTT source, input-mode, route, and channel lifecycle orchestration SHALL be owned by the audio input subsystem. Capture startup outcomes SHALL give the audio input subsystem enough information to fail closed and release the session route when a running capture session is not handed off.

#### Scenario: Subsystem starts capture
- **WHEN** the audio input subsystem has resolved and validated the selected input route
- **THEN** it calls the capture service to run ready-beep and recording setup
- **AND** the capture service returns either a running capture session or a typed setup failure

#### Scenario: Setup fails before handoff
- **WHEN** capture setup fails before a running capture session is handed to the audio input subsystem
- **THEN** the capture service closes any low-level recorder resources it opened
- **AND** the audio input subsystem marks the audio input session failed or cancelled
- **AND** the audio input subsystem releases the route associated with that session exactly once
- **AND** the selected channel does not release the route

#### Scenario: Running capture is handed off
- **WHEN** the capture service returns a running capture session
- **THEN** the audio input subsystem owns when to stop or cancel that session
- **AND** the audio input subsystem delivers live frames and terminal PCM to the selected channel through the channel input contract

## ADDED Requirements

### Requirement: Capture startup exposes internal readiness evidence
The capture layer SHALL expose internal startup evidence needed by the audio input subsystem to decide whether capture may be reported as started. This evidence MAY include recorder open success, negotiated sample rate, active recording configuration, reported input device, and Android silencing status when those facts are available. The evidence SHALL remain internal to the audio input subsystem and SHALL NOT be exposed to channels.

#### Scenario: Android reports recorder silenced
- **WHEN** the capture source opens an `AudioRecord`
- **AND** Android reports the active recording configuration for that recorder is silenced
- **THEN** the audio input subsystem SHALL fail or cancel capture setup before delivering channel input start
- **AND** SHALL release the active session route exactly once

#### Scenario: Recording configuration is unavailable
- **WHEN** the capture source opens an `AudioRecord`
- **AND** Android does not provide enough recording configuration detail to identify the physical input device
- **THEN** the system SHALL use the required route facts that are available
- **AND** SHALL keep missing best-effort recording configuration facts as diagnostics rather than leaking them to channels
