## Purpose

TBD. Defines self-managed Telecom VoIP-based car PTT capture through Subspace's PhoneAccount, with Telecom callbacks as the authoritative stop signal.

## Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace. Each PTT cycle SHALL be a complete call lifecycle: place call, acquire a verified car capture route, commit the selected channel target, play the mandatory ready beep through the active call route, capture post-beep audio, release on hang-up, and perform a mandatory route switch. Telecom call audio state is a required input to readiness, but SHALL NOT by itself prove that the ready beep or channel capture path is committed.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready and the mode is `OnTheRoad`
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval
- **AND** keep the pending On-the-road request owned by the audio input subsystem

#### Scenario: Do not play ready beep before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state or call endpoint callbacks to report an acceptable car capture route before playing the ready beep
- **AND** SHALL also require the audio input subsystem's non-Telecom route facts to be compatible with car capture
- **AND** SHALL NOT use `AudioManager.communicationDevice` as the sole hard proof that the car call route can carry the ready beep

#### Scenario: Bluetooth call audio route starts committed capture
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active for a non-RSM endpoint
- **AND** the audio input subsystem has observed that stale Work/RSM communication routing is not active
- **AND** the selected channel target has accepted the input request
- **AND** capture startup succeeds for the active route
- **THEN** the system SHALL play the mandatory ready beep through the active call route
- **AND** SHALL deliver post-beep microphone capture to the committed channel target
- **AND** mark the car PTT session as recording

#### Scenario: Ready beep cannot be played through car call route
- **WHEN** Telecom reports an acceptable car call route
- **BUT** the audio input subsystem cannot play the ready beep through the active call route before the configured bound
- **THEN** the system SHALL NOT start committed channel capture
- **AND** SHALL provide problem feedback when possible
- **AND** SHALL release the pending Telecom route/session state exactly once

### Requirement: End-call action stops car PTT and triggers route switch
The system SHALL treat Telecom disconnect callbacks as the authoritative stop signal for an active car PTT capture. After stopping capture, the system SHALL trigger a mandatory route switch that releases SCO and ends the call before allowing the next PTT cycle.

#### Scenario: Steering-wheel hang-up stops capture and triggers route switch
- **WHEN** a Subspace Telecom car PTT capture is recording
- **AND** the car sends an end-call action that causes `Connection.onDisconnect()`
- **THEN** the system SHALL stop microphone capture
- **AND** finalize the active PTT session
- **AND** trigger the route switch to release SCO and end the call
- **AND** the car SHALL return to normal media mode with media controls available

#### Scenario: Telecom abort stops capture and triggers route switch
- **WHEN** a Subspace Telecom car PTT connection receives an abort, reject, destroy, or call-loss callback while capture is active
- **THEN** the system SHALL stop microphone capture if it is running
- **AND** release the car PTT session state
- **AND** trigger the route switch to release SCO

### Requirement: Mandatory SCO release on every PTT release
The system SHALL release the SCO audio link on every on-the-road PTT release, regardless of whether the channel produces response audio. The route switch (release SCO, end call, switch to A2DP) SHALL be triggered even when the channel does not play back the recording.

#### Scenario: Channel without playback releases SCO
- **WHEN** a channel (such as Journal) completes a PTT capture and writes to disk instead of playing back
- **THEN** the system SHALL trigger the route switch to release SCO and end the call
- **AND** the car SHALL NOT redial

#### Scenario: Channel with playback releases SCO before A2DP response
- **WHEN** a channel (such as Echo) completes a PTT capture and plays back the recording
- **THEN** the system SHALL release SCO via the route switch before playing the response via A2DP
- **AND** the car SHALL NOT redial

### Requirement: Telecom car PTT fails safe
The system SHALL fail safe toward released capture state whenever Telecom or car audio state becomes ambiguous.

#### Scenario: Car disconnects during capture
- **WHEN** a Subspace Telecom car PTT capture is active
- **AND** Android Auto, Bluetooth, or Telecom call audio disconnects
- **THEN** the system SHALL stop microphone capture
- **AND** release the active car PTT session
- **AND** trigger the route switch to release SCO

#### Scenario: Capture route timeout
- **WHEN** a Subspace Telecom car PTT connection is created
- **AND** no acceptable call audio route becomes active before the configured timeout
- **THEN** the system SHALL disconnect the Telecom connection
- **AND** leave car PTT released
- **AND** provide error feedback when possible

#### Scenario: Real call conflict
- **WHEN** a real cellular or higher-priority Telecom call conflicts with Subspace car PTT
- **THEN** the system SHALL abort or release the Subspace car PTT capture
- **AND** leave the microphone closed

### Requirement: 30-second idle timeout after route switch
The system SHALL start a 30-second idle timer after the route switch completes. If no new PTT cycle begins within 30 seconds, the on-the-road session resources SHALL be cleaned up.

#### Scenario: Idle timeout fires
- **WHEN** the route switch has completed and 30 seconds pass without a new play/pause press
- **THEN** the system SHALL clean up on-the-road session resources

#### Scenario: Idle timer cancelled by new PTT
- **WHEN** the user presses play/pause before the 30-second idle timer fires
- **THEN** the system SHALL cancel the idle timer and start a new PTT cycle

### Requirement: Pending Telecom route is an audio input session state
The system SHALL represent an On-the-road Telecom PTT request as an active audio input session while it is waiting for an acceptable Bluetooth call audio route, channel commitment, route/capture preflight, and mandatory ready-beep completion. The session SHALL become committed capture only after the ready beep contract is satisfied, and SHALL be released if route acquisition, route validation, channel commitment, ready beep, timeout, or cancellation fails.

#### Scenario: Pending Telecom route reserves session ownership
- **WHEN** the driver starts car PTT and Subspace begins placing or attaching a self-managed Telecom call
- **THEN** the audio input subsystem records an active pending On-the-road session
- **AND** phone and RSM PTT requests are ignored or rejected until the pending session commits capture or terminates

#### Scenario: Telecom route becomes committed capture
- **WHEN** the pending Telecom session receives an acceptable Bluetooth call audio route
- **AND** the audio input subsystem observes that stale Work/RSM route state does not own the communication path
- **AND** the selected channel target accepts the input request
- **AND** the audio input subsystem confirms capture preflight for the On-the-road route
- **AND** the ready beep completes through the call route
- **THEN** the audio input subsystem transitions the pending session to active committed capture
- **AND** delivers post-beep channel input to the committed target through the normal channel input contract

#### Scenario: Telecom route fails before commitment
- **WHEN** the pending Telecom session times out, disconnects, aborts, fails route validation, fails channel commitment, fails ready beep playback, or fails capture preflight before commitment
- **THEN** the audio input subsystem releases the pending session and the route associated with that session exactly once
- **AND** leaves no active capture session or route lease behind
- **AND** provides problem feedback when possible

### Requirement: Telecom route switch is triggered by session release
The mandatory On-the-road route switch SHALL be triggered by the audio input session owner when the session ends or is cancelled. Channels SHALL NOT trigger Telecom route switching directly.

#### Scenario: Channel without playback ends Telecom session
- **WHEN** a channel completes an On-the-road capture and does not produce response playback
- **THEN** the audio input session owner releases the Telecom route
- **AND** the mandatory route switch drops SCO and ends the call

#### Scenario: Forced cancel ends Telecom session
- **WHEN** an On-the-road session is force-cancelled during pending route acquisition or active capture
- **THEN** the audio input session owner triggers Telecom cleanup
- **AND** the car route returns to released state

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
On-the-road setup SHALL select the car HFP endpoint by `BluetoothDevice` identity, not display name. Selection SHALL exclude the exact target RSM and SHALL succeed only when exactly one connected non-RSM HFP candidate remains. Successful priming SHALL be a bounded pulse on that exact car device: observe its HFP audio connect, stop voice recognition on the same device, observe its HFP audio disconnect, and reserve that device as the expected Telecom route before placing the call. The priming route SHALL NOT remain concurrently owned while Telecom acquires call audio. A failed or timed-out prime SHALL NOT be ignored when deciding whether the car capture route is ready.

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
Before `placeCall`, the coordinator SHALL reserve the exact car `BluetoothDevice` selected and primed by On-the-road setup. The outgoing `ConnectionService` SHALL claim that reservation exactly once and construct the Subspace connection with it; a missing or duplicate claim SHALL fail closed. Capture-route readiness SHALL require both an active Bluetooth call route and `CallAudioState.activeBluetoothDevice` identity equal to the reserved car, and SHALL require that the target RSM is neither the active HFP audio path nor the selected communication route. Display names SHALL be diagnostic only: a same-name device, a differently named device, or an unreadable name SHALL NOT substitute for identity. Bluetooth support in the route mask alone SHALL NOT establish readiness.

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

#### Scenario: Stale RSM route conflicts with car readiness
- **WHEN** Telecom reports an acceptable car call audio route
- **BUT** Android still reports the target RSM as the active HFP audio path or selected communication route
- **THEN** the audio input subsystem SHALL NOT deliver channel input start for the car session
- **AND** SHALL fail or continue waiting according to the active route gate

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