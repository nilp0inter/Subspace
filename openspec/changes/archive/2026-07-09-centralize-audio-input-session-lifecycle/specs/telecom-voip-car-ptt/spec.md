## ADDED Requirements

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
