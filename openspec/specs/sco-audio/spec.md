## Purpose

Defines the Bluetooth SCO (Synchronous Connection Oriented) audio route lifecycle — acquisition from inactive state, cold-start priming for reliable first-playback audio, device-level AudioTrack routing, and the warmup retention window that keeps the SCO path active between PTT sessions.

## Requirements

### Requirement: SCO route is acquired on PTT press
The system SHALL acquire the Work Bluetooth SCO communication route by targeting the `B02PTT-FF01` `BluetoothDevice` through the `BluetoothHeadset` profile, then using the active `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` only as the transport after target RSM ownership is proven.

#### Scenario: Work SCO is inactive on PTT press
- **WHEN** Work SCO is inactive, Work mode is selected or auto-selected, and the user presses RSM PTT
- **THEN** the system SHALL resolve the target RSM `BluetoothDevice`
- **AND** verify the target RSM Headset/HFP profile connection is connected
- **AND** call `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** poll `BluetoothHeadset.isAudioConnected(targetRsm)` or observe target audio state until RSM HFP audio is connected
- **AND** select the active `TYPE_BLUETOOTH_SCO` communication device as the Work transport
- **AND** report SCO state transitions: `Inactive → Starting → Active`

#### Scenario: Work SCO is already active on PTT press
- **WHEN** Work SCO is already active and owned by `B02PTT-FF01` from a previous warm Work session
- **AND** the user presses RSM PTT
- **THEN** the system SHALL return immediately without re-acquisition
- **AND** the system SHALL report SCO state `Active`

#### Scenario: Target RSM HFP is not connected
- **WHEN** Work route acquisition starts
- **AND** the target RSM Headset/HFP profile is not connected
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP not connected")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted voice recognition start fails
- **WHEN** Work route acquisition calls `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** the call returns false or throws
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP audio start failed")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted HFP audio connection times out
- **WHEN** Work route acquisition starts target RSM voice recognition
- **AND** `BluetoothHeadset.isAudioConnected(targetRsm)` does not become true before timeout
- **THEN** the system SHALL report SCO state `Failed("Timed out waiting for target RSM HFP audio")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Generic SCO device is not found after target ownership proof
- **WHEN** `BluetoothHeadset.isAudioConnected(targetRsm)` is true
- **BUT** no `TYPE_BLUETOOTH_SCO` communication transport is available to route AudioTrack output
- **THEN** the system SHALL report SCO state `Failed("Bluetooth SCO transport not available")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure

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


### Requirement: AudioTrack routes through SCO device

The system SHALL explicitly route all `PcmOutput` AudioTrack instances through the active SCO communication device to guarantee the audio reaches the Bluetooth headset.

#### Scenario: AudioTrack is created with SCO device preference
- **WHEN** the system creates an AudioTrack for beep playback or audio playback
- **THEN** the system SHALL set the AudioTrack's preferred device to the currently active SCO communication device
- **AND** the system SHALL fall back to the default routing if no SCO device is active (Audio track's default behavior)

### Requirement: Audio output owns route release

The `PcmOutput` implementation paired with a resolved audio route SHALL retain the route-specific release behavior exposed by `releaseRoute()`. The central audio input session owner SHALL invoke `releaseRoute()` for the active session when capture, post-capture processing, failure, or cancellation reaches a terminal state. Channel controllers SHALL NOT receive the resolved route and SHALL NOT call `releaseRoute()` or `ScoRoute.release()` directly in the PTT flow. The release mode (warm 30-second retention for Work SCO, immediate release for Telecom, no-op for local fallback) SHALL remain a property of the resolved route output, not a per-channel decision.

#### Scenario: SCO output releases with warm retention
- **WHEN** a PTT cycle completes on the SCO route and the audio input session owner releases the active route
- **THEN** the SCO route's `release()` is invoked
- **AND** the SCO controller starts the 30-second warmup retention window

#### Scenario: Telecom output releases immediately
- **WHEN** a PTT cycle completes on the telecom route and the audio input session owner releases the active route
- **THEN** the SCO route is released immediately (no warmup window)
- **AND** the system awaits telecom disconnect before any post-capture playback

#### Scenario: Local fallback output release is a no-op
- **WHEN** a PTT cycle completes on the local fallback route and the audio input session owner releases the active route
- **THEN** no route resources are released (there are none)

#### Scenario: Channel controller does not touch route release
- **WHEN** a channel controller handles PTT input, terminal audio, cancellation, or max-duration on any route
- **THEN** the controller SHALL NOT call `ScoRoute.release()` or `PcmOutput.releaseRoute()`
- **AND** the audio input session owner SHALL release the active route exactly once

### Requirement: Work route cleanup is session-owned
The Work SCO route SHALL be cleared only by the audio input session that owns it or by explicit fail-safe service teardown. A stale release from an older session SHALL NOT clear a newer session's communication device.

#### Scenario: Stale Work release ignored
- **WHEN** an older Work session release runs after a newer audio input session has become active
- **THEN** the older release does not clear the newer session's communication device
- **AND** the newer session remains responsible for its own route release

### Requirement: SCO release on capture setup failure
The capture service SHALL release the SCO route itself when it acquires the SCO route during `startSession` and setup fails before a running session is handed off (cancelled because PTT was released during acquisition or beep, or the capture source could not be opened). Channel controllers SHALL NOT release the SCO route on those failure outcomes.

#### Scenario: Capture cancelled during beep — service releases SCO
- **WHEN** the capture service acquires SCO, begins the ready beep, and PTT is released before the beep completes
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

#### Scenario: Capture source open fails — service releases SCO
- **WHEN** the capture service acquires SCO, plays the ready beep, and the capture source cannot be opened
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

### Requirement: SCO release on post-capture consumer cancellation
The audio input session owner SHALL release the audio route via `route.output.releaseRoute()` on every post-capture consumer exit path including normal completion, failure, and cancellation. Channel controllers SHALL NOT release the route directly.

#### Scenario: Transcription cancelled by new PTT press releases the route
- **WHEN** a STT or STT↔TTS transcription job is in flight and the user presses PTT again
- **THEN** the transcription job is cancelled
- **AND** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced (no leak)

#### Scenario: Transcription completes normally and releases the route
- **WHEN** a STT or STT↔TTS transcription job completes successfully
- **THEN** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced

#### Scenario: Transcription fails and releases the route
- **WHEN** a STT or STT↔TTS transcription job fails with a non-cancellation error
- **THEN** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced
