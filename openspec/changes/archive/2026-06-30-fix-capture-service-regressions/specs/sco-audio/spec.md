## ADDED Requirements

### Requirement: Audio output owns route release

The `PcmOutput` implementation paired with a resolved audio route SHALL own
the release of that route's resources via `releaseRoute()`. Channel
controllers SHALL call `route.output.releaseRoute()` to release the audio
route after the post-capture consumer (playback, transcription, synthesis)
finishes, and SHALL NOT call `ScoRoute.release()` directly in the PTT flow.
The release mode (warm 30-second retention for SCO, immediate release for
telecom, no-op for local fallback) SHALL be a property of the `PcmOutput`
implementation, not a per-controller decision.

#### Scenario: SCO output releases with warm retention
- **WHEN** a PTT cycle completes on the SCO route and the controller calls `route.output.releaseRoute()`
- **THEN** the SCO route's `release()` is invoked
- **AND** the SCO controller starts the 30-second warmup retention window

#### Scenario: Telecom output releases immediately
- **WHEN** a PTT cycle completes on the telecom route and the controller calls `route.output.releaseRoute()`
- **THEN** the SCO route is released immediately (no warmup window)
- **AND** the system awaits telecom disconnect before any post-capture playback

#### Scenario: Local fallback output release is a no-op
- **WHEN** a PTT cycle completes on the local fallback route and the controller calls `route.output.releaseRoute()`
- **THEN** no route resources are released (there are none)

#### Scenario: Controller does not touch ScoRoute directly
- **WHEN** a channel controller handles a PTT press, release, cancel, or max-duration on any route
- **THEN** the controller SHALL NOT call `ScoRoute.release()` on the post-capture path
- **AND** the controller SHALL call `route.output.releaseRoute()` instead

### Requirement: SCO release on capture setup failure

When the capture service acquires the SCO route during `startSession` and the
setup fails before a running session is handed off (cancelled because PTT was
released during acquisition or beep, or the capture source could not be
opened), the capture service SHALL release the SCO route itself. Channel
controllers SHALL NOT release the SCO route on those failure outcomes.

#### Scenario: Capture cancelled during beep — service releases SCO
- **WHEN** the capture service acquires SCO, begins the ready beep, and PTT is released before the beep completes
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

#### Scenario: Capture source open fails — service releases SCO
- **WHEN** the capture service acquires SCO, plays the ready beep, and the capture source cannot be opened
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

### Requirement: SCO release on post-capture consumer cancellation

When a channel controller's post-capture consumer (transcription, synthesis,
playback) is cancelled by a new PTT press or a mode switch, the controller
SHALL release the audio route via `route.output.releaseRoute()` on every
exit path including cancellation. The release SHALL run in a `finally` block
so it is guaranteed to execute on normal completion, failure, and
cancellation.

#### Scenario: Transcription cancelled by new PTT press releases the route
- **WHEN** a STT or STT↔TTS transcription job is in flight and the user presses PTT again
- **THEN** the transcription job is cancelled
- **AND** the `finally` block releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced (no leak)

#### Scenario: Transcription completes normally and releases the route
- **WHEN** a STT or STT↔TTS transcription job completes successfully
- **THEN** the `finally` block releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced

#### Scenario: Transcription fails and releases the route
- **WHEN** a STT or STT↔TTS transcription job fails with a non-cancellation error
- **THEN** the `finally` block releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced