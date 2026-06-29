## MODIFIED Requirements

### Requirement: Recording starts only after the ready beep completes

The system SHALL start audio recording only after the ready beep playback has
fully completed, preserving the `sco-audio` capture-priming contract. This
sequencing (acquire SCO, play ready beep with cold-start priming, then record)
SHALL be owned by the capture session, not duplicated per channel controller.
The capture service SHALL release the SCO route on every `startSession`
outcome that acquired SCO and did not hand off a running session
(`Cancelled`, `RecordingFailed`), so a failed setup does not leak a SCO
reference. Channel controllers SHALL NOT release the SCO route on those
outcomes, because the service already did.

#### Scenario: Recording starts after beep completes while PTT held
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system starts recording from the selected microphone source

#### Scenario: PTT released during the ready beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system does not start recording
- **AND** the system cancels the session
- **AND** the capture service releases the SCO route (triggering the 30-second warmup retention window)
- **AND** the channel controller SHALL NOT additionally release the SCO route

#### Scenario: PTT released during SCO acquisition
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system continues SCO acquisition to completion
- **AND** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the capture service releases the SCO route (triggering the 30-second warmup retention window)
- **AND** the channel controller SHALL NOT additionally release the SCO route

#### Scenario: Source open fails after beep
- **WHEN** the ready beep completes, PTT is still held, and the capture source cannot be opened
- **THEN** the system does not start recording
- **AND** the capture service releases the SCO route (triggering the 30-second warmup retention window)
- **AND** the channel controller SHALL NOT additionally release the SCO route

### Requirement: Single active capture session

The system SHALL own exactly one active PTT audio capture session at a time. A
second start attempt while a session is active SHALL be rejected by the capture
service rather than relying solely on dispatch-layer guards. The service's
`active` session reference SHALL transition to null synchronously when a
session finalizes (via `stop`, `cancelSession`, or max-duration), so a rapid
PTT re-press after finalization is accepted rather than rejected as
`SessionActive`.

#### Scenario: Second start while a session is active is rejected
- **WHEN** a capture session is active and a second start is requested
- **THEN** the system rejects the second start without creating a new `AudioRecord`
- **AND** the system leaves the existing capture session running unchanged

#### Scenario: No session active accepts a start
- **WHEN** no capture session is active and a start is requested
- **THEN** the system creates a single new capture session

#### Scenario: Rapid re-press after stop is accepted
- **WHEN** a capture session is finalized via `stop` and a new start is requested before any coroutine continuation runs
- **THEN** the system accepts the new start
- **AND** the system does not reject it as `SessionActive`

#### Scenario: Rapid re-press after cancelSession is accepted
- **WHEN** a capture session is finalized via `cancelSession` and a new start is requested before any coroutine continuation runs
- **THEN** the system accepts the new start
- **AND** the system does not reject it as `SessionActive`

## ADDED Requirements

### Requirement: Capture session exposes negotiated sample rate

The capture session SHALL expose the sample rate negotiated by the opened
capture source, so consumers that write capture-derived artifacts (WAV
headers, metadata) use the rate the source actually negotiated rather than a
hardcoded value.

#### Scenario: 16 kHz source exposes 16000
- **WHEN** the capture source negotiates a 16 kHz sample rate and a session starts
- **THEN** the session's `sampleRate` is 16000

#### Scenario: 8 kHz source exposes 8000
- **WHEN** the capture source negotiates an 8 kHz sample rate and a session starts
- **THEN** the session's `sampleRate` is 8000