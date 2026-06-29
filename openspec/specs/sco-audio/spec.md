## Purpose

Defines the Bluetooth SCO (Synchronous Connection Oriented) audio route lifecycle — acquisition from inactive state, cold-start priming for reliable first-playback audio, device-level AudioTrack routing, and the warmup retention window that keeps the SCO path active between PTT sessions.

## Requirements

### Requirement: SCO route is acquired on PTT press

The system SHALL acquire the Bluetooth SCO communication route when the user presses PTT while any audio-capturing mode is enabled.

#### Scenario: SCO is inactive on PTT press
- **WHEN** SCO is inactive, an audio-capturing mode is enabled, and the user presses PTT
- **THEN** the system sets the audio manager mode to `MODE_IN_COMMUNICATION`
- **AND** the system sets the SCO device as the communication device
- **AND** the system polls for the communication device type to become `TYPE_BLUETOOTH_SCO`
- **AND** the system reports SCO state transitions: `Inactive → Starting → Active`

#### Scenario: SCO is already active on PTT press
- **WHEN** SCO is already active (warm from a previous session) and the user presses PTT
- **THEN** the system returns immediately without re-acquisition
- **AND** the system reports SCO state `Active`

#### Scenario: SCO acquisition times out
- **WHEN** the system polls for 5 seconds and the SCO route does not become active
- **THEN** the system reports SCO state `Failed("Timed out waiting for SCO route")`
- **AND** the system returns acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: SCO device is not found
- **WHEN** no Bluetooth SCO device is available during acquisition
- **THEN** the system reports SCO state `Failed("Bluetooth SCO headset not available")`
- **AND** the system returns acquisition failure

### Requirement: Ready beep plays at SCO activation instant with reliable audible output

The system SHALL play the ready beep at the exact instant the SCO route becomes active, and SHALL ensure the beep is audibly routed through the SCO headset.

#### Scenario: Cold-start SCO — first beep after inactive SCO
- **WHEN** SCO was inactive, the system acquires SCO successfully, and the SCO route just became active
- **THEN** the system SHALL route the AudioTrack explicitly to the SCO communication device via `setPreferredDevice`
- **AND** the system SHALL send a priming PCM buffer (short silence, 100ms) through the SCO route before the beep waveform to establish the link-layer SCO packet stream
- **AND** the system SHALL play the ready beep (880 Hz sine, 150ms) immediately after priming
- **AND** the user SHALL hear the beep through the Bluetooth headset

#### Scenario: Warm-start SCO — subsequent beeps within warmup window
- **WHEN** SCO is already active from a previous session (warm) and PTT is pressed
- **THEN** the system SHALL play the ready beep through the SCO device route without priming
- **AND** the user SHALL hear the beep through the Bluetooth headset

### Requirement: Recording always starts after ready beep completes

The system SHALL start audio recording only after the ready beep playback has fully completed.

#### Scenario: Beep completes before recording starts
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system SHALL start recording mono PCM audio from the Bluetooth SCO microphone source
- **AND** the system SHALL report status transition: `Beeping → Recording`

#### Scenario: PTT is released during beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL NOT start recording
- **AND** the system SHALL cancel the session
- **AND** the system SHALL retain the warm SCO route (see SCO warmup requirement)

#### Scenario: PTT is released during SCO acquisition (short tap)
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system SHALL continue SCO acquisition to completion
- **AND** the system SHALL NOT play the ready beep
- **AND** the system SHALL NOT start recording
- **AND** the system SHALL retain the SCO route warm for 30 seconds

### Requirement: SCO warmup retention

The system SHALL use centralized reference counting to keep the SCO route active for 30 seconds after the last active client releases the route, so that subsequent sessions or rapid navigational announcements get instant SCO acquisition and reliable beep playback.

#### Scenario: Active client drops to zero — SCO retained warm
- **WHEN** all components that have acquired the SCO route release it (active client count drops to 0)
- **THEN** the system SHALL keep the SCO communication device set for 30 seconds
- **AND** the system SHALL report SCO state as `Active` during the warmup window

#### Scenario: Warmup expires
- **WHEN** 30 seconds pass with the active client count remaining at 0
- **THEN** the system SHALL clear the communication device
- **AND** the system SHALL set audio manager mode back to `MODE_NORMAL`
- **AND** the system SHALL report SCO state transition: `Active → Closing → Inactive`

#### Scenario: Route acquired during warmup
- **WHEN** a warmup delay is active and a component requests to acquire the SCO route
- **THEN** the system SHALL cancel the warmup delay
- **AND** the system SHALL increment the active client count
- **AND** the system SHALL return immediately without re-acquisition since the route is already active

### Requirement: AudioTrack routes through SCO device

The system SHALL explicitly route all `PcmOutput` AudioTrack instances through the active SCO communication device to guarantee the audio reaches the Bluetooth headset.

#### Scenario: AudioTrack is created with SCO device preference
- **WHEN** the system creates an AudioTrack for beep playback or audio playback
- **THEN** the system SHALL set the AudioTrack's preferred device to the currently active SCO communication device
- **AND** the system SHALL fall back to the default routing if no SCO device is active (Audio track's default behavior)

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
