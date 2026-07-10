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

### Requirement: Car HFP selection and priming hand exact ownership to Telecom
On-the-road setup SHALL select the car HFP endpoint by `BluetoothDevice` identity, not display name. Selection SHALL exclude the exact target RSM and SHALL succeed only when exactly one connected non-RSM HFP candidate remains. Successful priming SHALL be a bounded pulse on that exact car device: observe its HFP audio connect, stop voice recognition on the same device, observe its HFP audio disconnect, and reserve that device as the expected Telecom route before placing the call. The priming route SHALL NOT remain concurrently owned while Telecom acquires call audio.

#### Scenario: Exact target RSM is excluded from car selection
- **WHEN** the target RSM identity is known
- **AND** the connected HFP set contains that RSM and exactly one other connected device
- **THEN** the system SHALL select the other device as the car regardless of either device's display name

#### Scenario: Car HFP selection is ambiguous
- **WHEN** target RSM identity is unavailable while multiple HFP devices are connected
- **OR** more than one connected non-RSM HFP candidate remains after excluding the exact target RSM
- **THEN** the system SHALL fail car PTT setup before priming or Telecom placement
- **AND** SHALL NOT choose the first device or infer ownership from display names

#### Scenario: Car HFP prime becomes connected
- **WHEN** `startVoiceRecognition(car)` succeeds for the selected exact car
- **AND** HFP audio becomes connected for that same car device
- **THEN** the system SHALL call `stopVoiceRecognition(car)`
- **AND** wait until HFP audio is disconnected for that device
- **AND** reserve that exact car as the expected Telecom Bluetooth route
- **AND** only then place the self-managed Telecom call

#### Scenario: Car HFP handoff does not disconnect
- **WHEN** voice recognition was stopped for the primed car device
- **AND** its HFP audio does not disconnect before the configured timeout
- **THEN** the system SHALL fail the car PTT setup
- **AND** SHALL NOT place the Telecom call

#### Scenario: Car HFP priming is cancelled
- **WHEN** the priming operation is cancelled after voice recognition starts
- **THEN** the system SHALL stop voice recognition on the exact started device
- **AND** SHALL NOT retain a priming route or expected-device reservation for later Telecom cleanup

#### Scenario: Expected-device reservation cannot be created
- **WHEN** a previous Telecom connection or expected-device reservation still owns the coordinator
- **THEN** the system SHALL fail the new car PTT setup before `placeCall`
- **AND** SHALL preserve a single owner rather than overwrite the existing reservation

### Requirement: Telecom capture acquires and stabilizes the exact primed car route
Before `placeCall`, the coordinator SHALL reserve the exact car `BluetoothDevice` selected and primed by On-the-road setup. The outgoing `ConnectionService` SHALL claim that reservation exactly once and construct the Subspace connection with it; a missing or duplicate claim SHALL fail closed. Capture-route readiness SHALL require both an active Bluetooth call route and `CallAudioState.activeBluetoothDevice` identity equal to the reserved car. Display names SHALL be diagnostic only: a same-name device, a differently named device, or an unreadable name SHALL NOT substitute for identity. Bluetooth support in the route mask alone SHALL NOT establish readiness.

While the reserved car remains in `supportedBluetoothDevices` but is not the active Bluetooth call device, the connection SHALL request Bluetooth audio for that exact car immediately and retry through one serialized delayed loop until the exact route becomes active or ownership terminates. It SHALL NOT request any substitute device. The exact acceptable predicate SHALL then remain continuously true for the configured stability window before capture starts.

#### Scenario: Outgoing connection claims the reserved car
- **WHEN** On-the-road setup has reserved an exact primed car before `placeCall`
- **AND** Android creates the outgoing Subspace connection
- **THEN** the connection service SHALL claim that exact device once
- **AND** bind route acquisition and readiness to that device for the connection lifetime

#### Scenario: Expected car reservation is missing or already claimed
- **WHEN** Android creates an outgoing Subspace connection without an unclaimed expected-car reservation
- **THEN** connection creation SHALL fail
- **AND** the coordinator SHALL abort the pending lifecycle and clear its ownership

#### Scenario: Outgoing connection attachment collides
- **WHEN** the expected car was claimed but another connection owns the coordinator or its lifecycle is no longer waiting for route acquisition
- **THEN** connection creation SHALL fail as busy
- **AND** the coordinator SHALL abort the pending lifecycle rather than attach ambiguously

#### Scenario: Bluetooth is supported but the exact car is not active
- **WHEN** the supported route mask includes Bluetooth
- **AND** the active call route is not Bluetooth or its active Bluetooth device is not the reserved car
- **THEN** the connection SHALL report the capture route as unavailable
- **AND** SHALL NOT start route stabilization

#### Scenario: Wrong Bluetooth device has the same or unreadable name
- **WHEN** the active call route is Bluetooth
- **AND** its active device is not the reserved car by identity
- **AND** its display name equals the car name or cannot be read
- **THEN** the connection SHALL report the car capture route as unavailable

#### Scenario: Supported exact car is not selected
- **WHEN** the reserved car remains in `supportedBluetoothDevices`
- **AND** the active call route does not use that car
- **THEN** the connection SHALL request Bluetooth audio for the reserved car immediately
- **AND** SHALL retry that same exact-device request at the configured interval through no more than one pending retry loop

#### Scenario: Repeated wrong-route callbacks do not multiply retries
- **WHEN** Android repeatedly reports an unacceptable route before a scheduled exact-car retry runs
- **THEN** the system SHALL retain only one scheduled retry loop
- **AND** SHALL NOT create parallel request sequences

#### Scenario: Exact car activation becomes stable
- **WHEN** the active call route is Bluetooth on the reserved car
- **THEN** pending exact-device route retries SHALL stop
- **AND** the exact acceptable predicate SHALL remain continuously true for the full stability window
- **AND** the connection SHALL then publish capture-route readiness once

#### Scenario: Route becomes unacceptable during stabilization
- **WHEN** the exact car route becomes inactive or another device becomes active before the stability window completes
- **THEN** the pending readiness publication SHALL be cancelled
- **AND** later activation of the exact car SHALL begin a new full stability window

#### Scenario: Reserved car disappears from supported devices
- **WHEN** the reserved car is not active
- **AND** it is no longer present in `supportedBluetoothDevices`
- **THEN** exact-device retries SHALL stop
- **AND** the system SHALL NOT request another Bluetooth device
- **AND** route acquisition SHALL remain unavailable until normal timeout or termination

#### Scenario: Telecom ownership terminates during route acquisition
- **WHEN** the connection disconnects, aborts, is destroyed, times out, or otherwise relinquishes ownership
- **THEN** all pending stability and exact-device retry callbacks SHALL be cancelled
- **AND** no later route request SHALL be emitted for that connection

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
