## ADDED Requirements

### Requirement: Route switch occurs after terminal capture stop
The system SHALL NOT trigger the on-the-road Telecom/SCO route switch until the active channel capture has terminally stopped. The route switch MAY happen before asynchronous derived work such as Journal encoding or transcription, but it SHALL happen only after the microphone capture source is closed and the channel has finalized the capture artifact needed by downstream work.

#### Scenario: No-playback Journal release stops capture before route switch
- **WHEN** a Journal PTT capture is active in `OnTheRoad` mode
- **AND** the user presses hang-up
- **THEN** the system SHALL stop the active `CaptureSession` before releasing the Telecom/SCO route
- **AND** the system SHALL finalize the Journal capture WAV before releasing the Telecom/SCO route
- **AND** the system SHALL trigger the route switch without audible response playback
- **AND** the car SHALL NOT redial

#### Scenario: Playback channel stops capture before response route switch
- **WHEN** a playback-producing channel completes a PTT capture in `OnTheRoad` mode
- **THEN** the system SHALL stop the active `CaptureSession` before releasing the Telecom/SCO route
- **AND** the system SHALL release the Telecom/SCO route before playing any response through media audio

#### Scenario: Derived Journal work does not delay car route recovery
- **WHEN** a Journal capture has been stopped, finalized, and marked finished in metadata
- **AND** Journal encoding or transcription remains pending
- **THEN** the system SHALL allow the on-the-road route switch to complete
- **AND** the car SHALL return to normal media mode without waiting for derived Journal work
