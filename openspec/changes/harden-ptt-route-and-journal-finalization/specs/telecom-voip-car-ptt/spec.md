## ADDED Requirements

### Requirement: Recorded Telecom hang finalizes the committed channel
The system SHALL treat a Telecom disconnect, abort, reject, or call-loss callback after car capture has started as a normal terminal release of the committed input target. The connection-ended callback SHALL not override that release with cancellation before terminal channel delivery completes.

#### Scenario: Driver hangs an active Journal car call
- **WHEN** an On-the-road Journal capture has started
- **AND** the driver ends the self-managed Telecom call
- **THEN** the system SHALL stop the capture once
- **AND** deliver its terminal recording to the committed Journal target once
- **AND** release the Telecom route exactly once after terminal channel handling

#### Scenario: Recorded car response moves to media playback
- **WHEN** the committed channel returns a recorded response after active car capture
- **THEN** the audio session manager SHALL release the Telecom capture route exactly once
- **AND** await Telecom disconnection before starting response media playback
- **AND** final session cleanup SHALL NOT invoke the Telecom release callback again

#### Scenario: Car connection ends before capture handoff
- **WHEN** an On-the-road Telecom connection ends before capture starts
- **THEN** the system SHALL cancel the pending audio input session
- **AND** SHALL NOT deliver a terminal recording to a channel
- **AND** SHALL release the resolved Telecom output route exactly once

### Requirement: Failed car HFP priming is cleaned before setup failure
When car HFP voice recognition was requested for On-the-road setup and its observed-audio readiness wait fails, the system SHALL stop voice recognition for that same car device before returning setup failure.

#### Scenario: Car HFP prime times out after start request
- **WHEN** `startVoiceRecognition(car)` succeeds
- **AND** car HFP audio does not become observed as connected before the configured timeout
- **THEN** the system SHALL call `stopVoiceRecognition(car)` before abandoning setup
- **AND** SHALL not place or retain a Telecom PTT route for that attempt

### Requirement: Car HFP priming fully hands ownership to Telecom
Successful On-the-road HFP priming SHALL be a bounded pulse on the exact non-RSM car device. The system SHALL observe that device's HFP audio connect, stop voice recognition on the same device, and observe its HFP audio disconnect before placing the Telecom call. The priming route SHALL NOT remain concurrently owned while Telecom acquires call audio.

#### Scenario: Car HFP prime becomes connected
- **WHEN** `startVoiceRecognition(car)` succeeds
- **AND** HFP audio becomes connected for that same car device
- **THEN** the system SHALL call `stopVoiceRecognition(car)`
- **AND** wait until HFP audio is disconnected for that device
- **AND** only then place the self-managed Telecom call

#### Scenario: Car HFP handoff does not disconnect
- **WHEN** voice recognition was stopped for the primed car device
- **AND** its HFP audio does not disconnect before the configured timeout
- **THEN** the system SHALL fail the car PTT setup
- **AND** SHALL NOT place the Telecom call

#### Scenario: Car HFP priming is cancelled
- **WHEN** the priming operation is cancelled after voice recognition starts
- **THEN** the system SHALL stop voice recognition on the exact started device
- **AND** SHALL NOT retain a priming route for later Telecom cleanup

### Requirement: Telecom capture requires a stable acceptable active Bluetooth route
The self-managed Telecom connection SHALL report capture-route readiness only while the active call route is Bluetooth, an active Bluetooth device is present, and any readable active-device name does not identify the target RSM. An absent or unreadable device name SHALL remain acceptable because Android does not consistently expose that metadata. Bluetooth support in the route mask alone SHALL NOT establish readiness. The acceptable predicate SHALL remain continuously true for the configured stability window before capture starts.

#### Scenario: Bluetooth is supported but not active
- **WHEN** the supported route mask includes Bluetooth
- **AND** the active call route is not Bluetooth or has no active Bluetooth device
- **THEN** the connection SHALL report the capture route as unavailable

#### Scenario: Active Bluetooth route is identified as the RSM
- **WHEN** the active call route is Bluetooth
- **AND** the readable active Bluetooth device name identifies the target RSM
- **THEN** the connection SHALL report the car capture route as unavailable

#### Scenario: Acceptable active Bluetooth route remains stable
- **WHEN** the active call route has an active Bluetooth device
- **AND** its readable name does not identify the target RSM, or its name is unavailable
- **AND** that acceptable predicate remains continuously true for the stability window
- **THEN** the connection SHALL publish capture-route readiness once

#### Scenario: Route becomes unacceptable during stabilization
- **WHEN** an otherwise acceptable Bluetooth route becomes unacceptable before the stability window completes
- **THEN** the pending readiness publication SHALL be cancelled
- **AND** a later acceptable route SHALL begin a new full stability window

### Requirement: Car media state follows owned PTT terminal phases
The Android Auto media session SHALL derive its PTT presentation from the audio session manager's owned phase. A held PTT session SHALL publish Recording/playing, claimed terminal processing SHALL publish Finalizing/buffering, and only terminal completion SHALL publish Ready/paused when On-the-road remains available. Connection callbacks SHALL NOT publish Ready while terminal work is still owned.

#### Scenario: Car release begins terminal processing
- **WHEN** an active car PTT is released
- **THEN** the media session SHALL publish Finalizing with buffering playback state while terminal capture, channel processing, response playback, or route cleanup remains active
- **AND** SHALL publish Ready with paused playback state only after the terminal owner clears the session

#### Scenario: Terminal completion owns idle-retention timing
- **WHEN** an On-the-road terminal owner finishes channel handling, response playback, and route cleanup
- **THEN** the media session SHALL publish Ready with paused playback state when On-the-road remains available
- **AND** the system SHALL start the 30-second On-the-road idle-retention timer only after that terminal completion
- **AND** a new car PTT SHALL cancel the active idle-retention timer

#### Scenario: Car media controls release an active PTT
- **WHEN** Android Auto sends Pause, Stop, or Play/Pause while the media session is Recording
- **THEN** the command bus SHALL release the active PTT
- **AND** a standalone Play command SHALL continue to start car PTT

#### Scenario: Play/Pause is received while not recording
- **WHEN** Android Auto sends Play/Pause while the media session is not Recording
- **THEN** the command bus SHALL start car PTT rather than send a release
