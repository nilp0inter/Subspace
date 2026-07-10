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

### Requirement: Audio input terminal ownership is atomic
The audio input subsystem SHALL assign exactly one terminal owner to each active audio input session before launching suspendable completion, cancellation, setup-failure, or route-cleanup work. A later terminal signal for the same session SHALL NOT replace the claimed terminal owner, redeliver a channel terminal event, or release the route again. The subsystem SHALL retain the claimed session until its terminal capture and route cleanup are complete.

#### Scenario: Normal release precedes connection-ended callback
- **WHEN** an active On-the-road capture receives a normal release signal
- **AND** a connection-ended callback arrives before normal completion has finished
- **THEN** the normal release remains the session's terminal owner
- **AND** the selected channel receives exactly one terminal recording event
- **AND** the later callback SHALL NOT convert the session into cancellation
- **AND** the route is released exactly once

#### Scenario: Forced cancellation wins before terminal recording exists
- **WHEN** an active or pending session is force-cancelled before a terminal recording can be delivered
- **THEN** cancellation becomes the session's terminal owner
- **AND** the selected committed target receives at most one cancellation event
- **AND** later release callbacks do not emit terminal recording delivery
- **AND** the session route is released exactly once

### Requirement: On-the-road setup cancellation releases the Telecom output
The audio input subsystem SHALL release a resolved On-the-road Telecom output exactly once when its session is cancelled during route gating, capture preflight, ready-beep playback, or a short press before capture handoff. Work setup retains CaptureService/SCO warm-release semantics, and local setup retains no-op route cleanup.

#### Scenario: Pending Telecom setup is cancelled
- **WHEN** an On-the-road session has resolved its Telecom output route
- **AND** it is cancelled before a running capture session is handed off
- **THEN** the subsystem SHALL invoke the resolved output's route cleanup exactly once
- **AND** clear the communication device and audio mode through the Telecom cleanup contract
- **AND** clear the pending session only after cleanup is requested

#### Scenario: Short On-the-road press cancels during ready setup
- **WHEN** the user releases On-the-road PTT during capture preflight or ready-beep playback
- **THEN** the subsystem SHALL not deliver channel-visible recording
- **AND** invoke Telecom output route cleanup exactly once
- **AND** leave no active session or route lease behind

### Requirement: Local responses use the normal media route
The audio input subsystem SHALL play On-a-pinch channel responses through Android's normal media route rather than a communication route. Response playback SHALL begin only after the audio mode and communication device have remained non-SCO for the configured stability window and transient media focus has been granted. A route-readiness timeout or denied focus SHALL fail closed without starting response output.

#### Scenario: Phone Debug response while a car media route is connected
- **WHEN** phone PTT selects On-a-pinch while a car or Android Auto media route is connected
- **AND** the selected channel returns recorded response playback
- **THEN** ready and problem beeps remain on the existing raw local feedback output
- **AND** recorded response playback awaits a stable normal non-SCO route
- **AND** requests transient media focus before starting an unpinned media output
- **AND** abandons media focus after playback or playback failure

#### Scenario: Normal media route does not stabilize
- **WHEN** an On-a-pinch channel response is ready
- **AND** the normal non-SCO media route does not remain stable before the readiness timeout
- **THEN** the subsystem SHALL NOT request media focus
- **AND** SHALL NOT start response output

### Requirement: Audio route gates use observed OS state
The audio input subsystem SHALL treat route transition success as satisfied only by observed route/capture facts reported by Android APIs or subsystem-owned capture results. Elapsed time SHALL only bound waiting and SHALL NOT by itself prove that a route is ready, released, or safe for capture.

#### Scenario: Timeout fails a route transition
- **WHEN** an audio input session is waiting for a route acquisition, route release, or capture-readiness gate
- **AND** the configured timeout expires before the required observed facts are true
- **THEN** the audio input subsystem SHALL fail or cancel the pending session
- **AND** SHALL NOT deliver a channel input started event
- **AND** SHALL release any route resources owned by that session exactly once

#### Scenario: Observed route facts satisfy a transition
- **WHEN** an audio input session is waiting for a route transition
- **AND** the subsystem observes all required route facts for the selected input mode
- **THEN** the session may proceed to the next setup phase
- **AND** the selected channel remains unaware of the route facts used to make that decision

### Requirement: Channel start follows capture-readiness proof
The audio input subsystem SHALL deliver channel input start only after route readiness and capture startup have both succeeded for the active session. A route that is only partially ready SHALL result in cancellation or failure rather than a channel-visible capture session.

#### Scenario: Route readiness succeeds but capture startup fails
- **WHEN** the selected route reports readiness
- **AND** the capture source cannot be opened, is silenced by Android, or otherwise cannot be proven usable by the subsystem
- **THEN** the audio input subsystem SHALL report channel input failure or cancellation
- **AND** SHALL release the active session route exactly once
- **AND** SHALL NOT deliver `Started` to the selected channel

#### Scenario: Capture is ready before channel handoff
- **WHEN** the route gate succeeds
- **AND** the capture service returns a running capture session for the active session
- **THEN** the audio input subsystem SHALL deliver a channel input session to the channel selected at session start
- **AND** the channel input session SHALL expose only stream/sample-rate data allowed by the channel input contract


### Requirement: Ready beep commits selected-channel delivery
The audio input subsystem SHALL treat the ready beep as a mandatory commit signal. After the ready beep completes for a PTT, audio captured from that PTT SHALL be delivered through the channel input contract to the channel target selected at PTT start and accepted before the beep. The ready beep SHALL NOT be played for a PTT that has not been accepted by that selected channel.

#### Scenario: Ready beep follows channel commitment
- **WHEN** a PTT source requests capture
- **AND** the selected channel accepts the input request
- **AND** route and capture preflight succeed for the selected input mode
- **THEN** the audio input subsystem SHALL play the ready beep exactly once before accepting user speech for channel delivery
- **AND** audio captured after the ready beep SHALL reach the committed channel target

#### Scenario: Channel cannot accept input
- **WHEN** a PTT source requests capture
- **AND** the selected channel is unavailable, unconfigured, uninitialized, or otherwise refuses the input request before commitment
- **THEN** the audio input subsystem SHALL NOT play the ready beep
- **AND** SHALL NOT report a committed channel input session
- **AND** SHALL provide problem feedback when possible

#### Scenario: Setup fails after route readiness but before commitment
- **WHEN** route readiness succeeds
- **AND** capture preflight, ready beep playback, or selected-channel commitment fails before the ready beep contract is satisfied
- **THEN** the audio input subsystem SHALL release session-owned route resources exactly once
- **AND** SHALL NOT deliver a channel-visible started event
- **AND** SHALL provide problem feedback when possible

### Requirement: Problem beep marks uncommitted PTT
The audio input subsystem SHALL treat the problem beep as user-visible feedback that a user-visible PTT attempt will not reach the selected channel. Problem beep SHALL NOT imply that the audio route is unusable; it only means the selected channel will not process this PTT audio.

#### Scenario: Pre-commit failure produces problem feedback
- **WHEN** a user-visible PTT attempt fails before ready beep due to route validation, Telecom timeout, capture preflight, channel refusal, stale session, wrong source, or cancellation
- **THEN** the audio input subsystem SHALL play the problem beep when a safe feedback route is available
- **AND** SHALL NOT play the ready beep
- **AND** SHALL leave no active committed channel input behind

#### Scenario: Problem beep is best effort
- **WHEN** the input subsystem cannot safely play the problem beep on the failed route
- **THEN** the system SHALL still fail closed and clean up the route/session state
- **AND** SHALL NOT reinterpret the failure as a ready state

### Requirement: Session target remains immutable after commitment
The audio input subsystem SHALL bind a committed input session to the channel target accepted before ready beep. Subsequent active-channel or debug-mode changes SHALL NOT redirect start, release, cancellation, failure, terminal PCM, or playback-completion events for that active session.

#### Scenario: Channel selection changes during active PTT
- **WHEN** a PTT session has committed to a channel target
- **AND** the user changes the active channel before release
- **THEN** terminal input events for that session SHALL still be delivered to the committed target
- **AND** route cleanup SHALL remain owned by the audio input subsystem

