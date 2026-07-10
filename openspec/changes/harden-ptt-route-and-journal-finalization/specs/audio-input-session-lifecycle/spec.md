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

### Requirement: Setup cancellation releases resolved output routes
The audio input subsystem SHALL release a resolved route's output exactly once when a session is cancelled during route gating, capture preflight, ready-beep playback, or a short press before capture handoff. This applies even when the low-level `ScoRoute.release()` is a no-op.

#### Scenario: Pending Telecom setup is cancelled
- **WHEN** an On-the-road session has resolved its Telecom output route
- **AND** it is cancelled before a running capture session is handed off
- **THEN** the subsystem SHALL invoke the resolved output's route cleanup exactly once
- **AND** clear the communication device, audio mode, and any primed HFP path through the Telecom cleanup contract
- **AND** clear the pending session only after cleanup is requested

#### Scenario: Short On-the-road press cancels during ready setup
- **WHEN** the user releases On-the-road PTT during capture preflight or ready-beep playback
- **THEN** the subsystem SHALL not deliver channel-visible recording
- **AND** invoke Telecom output route cleanup exactly once
- **AND** leave no active session or route lease behind
