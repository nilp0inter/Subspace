## MODIFIED Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace. Each PTT cycle SHALL be a complete call lifecycle: place call, acquire a verified car capture route, capture, release on hang-up, and perform a mandatory route switch. Telecom call audio state is a required input to readiness, but it SHALL NOT be the only proof that the channel capture path is ready.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready and the mode is `OnTheRoad`
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval
- **AND** keep the pending On-the-road request owned by the audio input subsystem

#### Scenario: Do not start capture before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state or call endpoint callbacks to report an acceptable car capture route before starting microphone recording
- **AND** SHALL also require the audio input subsystem's non-Telecom route facts to be compatible with car capture

#### Scenario: Bluetooth call audio route starts capture only after composed readiness
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active for a non-RSM endpoint
- **AND** the audio input subsystem has observed that stale Work/RSM communication routing is not active
- **AND** capture startup succeeds for the active route
- **THEN** the system SHALL start microphone capture for the active channel
- **AND** mark the car PTT session as recording

### Requirement: Pending Telecom route is an audio input session state
The system SHALL represent an On-the-road Telecom PTT request as an active audio input session while it is waiting for an acceptable Bluetooth call audio route and any other route/capture facts required for car capture. The session SHALL become capturing only after composed On-the-road readiness is confirmed, and SHALL be released if route acquisition, route validation, capture startup, timeout, or cancellation fails.

#### Scenario: Pending Telecom route reserves session ownership
- **WHEN** the driver starts car PTT and Subspace begins placing or attaching a self-managed Telecom call
- **THEN** the audio input subsystem records an active pending On-the-road session
- **AND** phone and RSM PTT requests are ignored or rejected until the pending session starts capture or terminates

#### Scenario: Telecom route becomes ready
- **WHEN** the pending Telecom session receives an acceptable Bluetooth call audio route
- **AND** the audio input subsystem observes that stale Work/RSM route state does not own the communication path
- **AND** capture startup succeeds for the On-the-road route
- **THEN** the audio input subsystem transitions the pending session to active capture
- **AND** delivers channel input through the normal channel input contract

#### Scenario: Telecom route fails before capture
- **WHEN** the pending Telecom session times out, disconnects, aborts, fails route validation, or fails capture startup before capture starts
- **THEN** the audio input subsystem releases the pending session and the route associated with that session exactly once
- **AND** leaves no active capture session or route lease behind

## ADDED Requirements

### Requirement: Car HFP priming result is authoritative
When the On-the-road setup explicitly primes a car HFP audio path, the system SHALL treat the observed priming result as part of route readiness. A failed or timed-out car HFP prime SHALL NOT be ignored when deciding whether the car capture route is ready.

#### Scenario: Car HFP prime fails
- **WHEN** the system requests car HFP audio priming for On-the-road setup
- **AND** Android does not report the car HFP audio path connected before the configured timeout
- **THEN** the audio input subsystem SHALL fail or continue waiting for another explicit Telecom/car route readiness path
- **AND** SHALL NOT treat elapsed time as successful priming

#### Scenario: Stale RSM route conflicts with car readiness
- **WHEN** Telecom reports an acceptable car call audio route
- **BUT** Android still reports the target RSM as the active HFP audio path or selected communication route
- **THEN** the audio input subsystem SHALL NOT deliver channel input start for the car session
- **AND** SHALL fail or continue waiting according to the active route gate
