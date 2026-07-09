## Purpose

Defines centralized ownership of PTT audio input session lifecycle across route acquisition, capture, channel handoff, terminal delivery, route release, cancellation, and pending Telecom acquisition.

## Requirements

### Requirement: Single active audio input session lifecycle
The system SHALL own exactly one active PTT audio input session at a time across all PTT sources, input modes, and channels. The active audio input session SHALL include pending route acquisition, ready-beep setup, active capture, terminal result delivery, post-capture release, and forced cancellation.

#### Scenario: Session starts from an available source
- **WHEN** a PTT source requests capture and no audio input session is active or pending
- **THEN** the system creates one active audio input session for that source
- **AND** records the source, selected channel, input mode, and resolved route as internal session state

#### Scenario: Second source is rejected while session active
- **WHEN** an audio input session is active, pending route acquisition, capturing, or releasing
- **AND** any PTT source requests another capture
- **THEN** the system rejects or ignores the second request
- **AND** the existing audio input session continues under its original source and route

#### Scenario: Session completes cleanup
- **WHEN** an audio input session reaches a terminal state
- **THEN** the system releases the route owned by that session exactly once
- **AND** clears the active session only after session-owned cleanup has been requested

### Requirement: Audio input session owns route lifecycle
The system SHALL keep route acquisition and route release inside the audio input subsystem. Channels SHALL NOT receive route objects and SHALL NOT call route release APIs.

#### Scenario: Route acquired for Work session
- **WHEN** an audio input session starts in `Work` mode
- **THEN** the audio input subsystem acquires the target RSM-owned Work route
- **AND** hides `ScoRoute`, `PcmOutput`, `CaptureSource`, and `ResolvedAudioRoute` from the selected channel

#### Scenario: Route released after terminal capture
- **WHEN** a channel finishes consuming a captured audio result
- **THEN** the audio input subsystem releases the route associated with that session
- **AND** the channel does not call `releaseRoute()` or `ScoRoute.release()`

#### Scenario: Route released after forced cancellation
- **WHEN** service teardown, source loss, mode switch, or fail-safe cancellation aborts an active audio input session
- **THEN** the audio input subsystem releases the route associated with that active session exactly once
- **AND** no channel-specific default route is used for cleanup

### Requirement: Channels receive input events and audio data only
The system SHALL expose a channel-facing input contract that contains button/session events, live audio frames, negotiated sample rate, terminal recorded PCM, cancellation, and failure. The contract SHALL NOT expose input-mode strategy objects or Android route details.

#### Scenario: Channel receives live stream
- **WHEN** a capture session starts and the selected channel supports live streaming
- **THEN** the channel receives a channel input session exposing live PCM frames and negotiated sample rate
- **AND** the channel does not receive the route used to produce the stream

#### Scenario: Channel receives terminal recording
- **WHEN** a capture session ends with recorded audio
- **THEN** the selected channel receives the terminal `RecordedPcm`
- **AND** the audio input subsystem remains responsible for route release

#### Scenario: Channel receives cancellation
- **WHEN** an audio input session is cancelled before a terminal recording is available
- **THEN** the selected channel receives a cancellation or failure event
- **AND** the route cleanup remains owned by the audio input subsystem

### Requirement: Input-mode strategies select capture route internally
The system SHALL select the capture route through input-mode-specific strategy logic inside the audio input subsystem. The first implementation MAY delegate to the existing mode route resolver, but the selected channel SHALL observe only the channel input contract.

#### Scenario: Work strategy
- **WHEN** a session starts after the RSM actuator selects `Work`
- **THEN** the Work strategy resolves the target RSM-owned SCO route
- **AND** capture uses the voice-communication source associated with that route

#### Scenario: On-a-pinch strategy
- **WHEN** a session starts after phone PTT selects `OnAPinch`
- **THEN** the On-a-pinch strategy resolves the local microphone and local output route
- **AND** no Work or car route is acquired

#### Scenario: On-the-road strategy
- **WHEN** a session starts after car PTT selects `OnTheRoad`
- **THEN** the On-the-road strategy resolves the Telecom-backed capture route
- **AND** route release triggers the mandatory Telecom route switch before any response playback

### Requirement: Stale releases cannot affect newer sessions
The system SHALL associate every active audio input session with an internal identity. Release, cancel, source-loss, and completion events SHALL affect only the matching active session.

#### Scenario: Old release arrives after new session starts
- **WHEN** a release or cancellation callback from an older session arrives after a newer session has become active
- **THEN** the system ignores the stale callback for route cleanup
- **AND** the newer session's route remains active

#### Scenario: Wrong source releases while another source owns session
- **WHEN** an audio input session is owned by one PTT source
- **AND** another source reports release
- **THEN** the system ignores the wrong-source release
- **AND** the active session remains owned by its original source

### Requirement: Telecom pending route acquisition reserves audio input ownership
The system SHALL treat an On-the-road Telecom PTT request as an active audio input session while waiting for an acceptable Telecom Bluetooth route. The subsystem SHALL release that reservation when Telecom route acquisition starts capture, times out, fails, or is cancelled.

#### Scenario: Car route pending blocks competing PTT
- **WHEN** car PTT has placed or is preparing a Telecom call and waits for an acceptable Bluetooth call route
- **AND** phone or RSM PTT is pressed
- **THEN** the system rejects or ignores the competing PTT request
- **AND** the car pending session remains responsible for cleanup

#### Scenario: Car route timeout clears reservation
- **WHEN** the Telecom route does not become acceptable before timeout
- **THEN** the system cancels the pending audio input session
- **AND** clears the active session reservation
- **AND** leaves no Work or car route lease behind
