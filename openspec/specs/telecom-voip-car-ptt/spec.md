## Purpose

TBD. Defines self-managed Telecom VoIP-based car PTT capture through Subspace's PhoneAccount, with Telecom callbacks as the authoritative stop signal.

## Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace. Each PTT cycle SHALL be a complete call lifecycle: place call, acquire SCO, capture, release on hang-up, and perform a mandatory route switch.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready and the mode is `OnTheRoad`
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval

#### Scenario: Do not start capture before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state to report an acceptable capture route before starting microphone recording

#### Scenario: Bluetooth call audio route starts capture
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active
- **THEN** the system SHALL start microphone capture for the active channel
- **AND** mark the car PTT session as recording

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
The system SHALL represent an On-the-road Telecom PTT request as an active audio input session while it is waiting for an acceptable Bluetooth call audio route. The session SHALL become capturing only after Telecom route readiness is confirmed, and SHALL be released if route acquisition fails, times out, or is cancelled.

#### Scenario: Pending Telecom route reserves session ownership
- **WHEN** the driver starts car PTT and Subspace begins placing or attaching a self-managed Telecom call
- **THEN** the audio input subsystem records an active pending On-the-road session
- **AND** phone and RSM PTT requests are ignored or rejected until the pending session starts capture or terminates

#### Scenario: Telecom route becomes ready
- **WHEN** the pending Telecom session receives an acceptable Bluetooth call audio route
- **THEN** the audio input subsystem transitions the pending session to active capture
- **AND** delivers channel input through the normal channel input contract

#### Scenario: Telecom route fails before capture
- **WHEN** the pending Telecom session times out, disconnects, aborts, or fails before capture starts
- **THEN** the audio input subsystem releases the pending session
- **AND** leaves no active capture session or route lease behind

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