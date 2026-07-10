## MODIFIED Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace. Each PTT cycle SHALL be a complete call lifecycle: place call, acquire a verified car capture route, commit the selected channel target, play the mandatory ready beep through the active call route, capture post-beep audio, release on hang-up, and perform a mandatory route switch. Telecom call audio state is a required input to readiness, but SHALL NOT by itself prove that the ready beep or channel capture path is committed.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready and the mode is `OnTheRoad`
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval

#### Scenario: Do not play ready beep before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state or call endpoint callbacks to report an acceptable car capture route before playing the ready beep
- **AND** SHALL NOT use `AudioManager.communicationDevice` as the sole hard proof that the car call route can carry the ready beep

#### Scenario: Bluetooth call audio route starts committed capture
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active for a non-RSM endpoint
- **AND** the selected channel target has accepted the input request
- **AND** the audio input subsystem has enough route/capture preflight evidence to commit post-beep audio delivery
- **THEN** the system SHALL play the mandatory ready beep through the active call route
- **AND** SHALL deliver post-beep microphone capture to the committed channel target
- **AND** mark the car PTT session as recording

#### Scenario: Ready beep cannot be played through car call route
- **WHEN** Telecom reports an acceptable car call route
- **BUT** the audio input subsystem cannot play the ready beep through the active call route before the configured bound
- **THEN** the system SHALL NOT start committed channel capture
- **AND** SHALL provide problem feedback when possible
- **AND** SHALL release the pending Telecom route/session state exactly once

### Requirement: Pending Telecom route is an audio input session state
The system SHALL represent an On-the-road Telecom PTT request as an active audio input session while it is waiting for acceptable Bluetooth call audio, channel commitment, route/capture preflight, and mandatory ready-beep completion. The session SHALL become committed capture only after the ready beep contract is satisfied, and SHALL be released if route acquisition, route validation, channel commitment, ready beep, timeout, or cancellation fails.

#### Scenario: Pending Telecom route reserves session ownership
- **WHEN** the driver starts car PTT and Subspace begins placing or attaching a self-managed Telecom call
- **THEN** the audio input subsystem records an active pending On-the-road session
- **AND** phone and RSM PTT requests are ignored or rejected until the pending session commits capture or terminates

#### Scenario: Telecom route becomes committed capture
- **WHEN** the pending Telecom session receives an acceptable Bluetooth call audio route
- **AND** the selected channel target accepts the input request
- **AND** the audio input subsystem confirms capture preflight for the On-the-road route
- **AND** the ready beep completes through the call route
- **THEN** the audio input subsystem transitions the pending session to active committed capture
- **AND** delivers post-beep channel input through the normal channel input contract

#### Scenario: Telecom route fails before commitment
- **WHEN** the pending Telecom session times out, disconnects, aborts, fails route validation, fails channel commitment, fails ready beep playback, or fails capture preflight before commitment
- **THEN** the audio input subsystem releases the pending session and the route associated with that session exactly once
- **AND** leaves no active capture session or route lease behind
- **AND** provides problem feedback when possible
