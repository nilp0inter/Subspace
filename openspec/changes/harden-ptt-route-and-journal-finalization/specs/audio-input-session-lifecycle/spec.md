## ADDED Requirements

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
