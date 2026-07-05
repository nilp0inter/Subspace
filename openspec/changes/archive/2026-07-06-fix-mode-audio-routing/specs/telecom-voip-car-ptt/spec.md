## MODIFIED Requirements

### Requirement: Telecom-backed car PTT capture session
The system SHALL represent each in-car PTT capture interval as a self-managed Telecom VoIP call owned by Subspace. Each PTT cycle SHALL be a complete call lifecycle: place call, acquire the car call-audio route, capture, release on hang-up, and perform a mandatory route switch.

#### Scenario: Start car PTT through Telecom
- **WHEN** the driver invokes the car PTT start action while the active channel is ready and the mode is `OnTheRoad`
- **THEN** the system SHALL place a self-managed Telecom call using Subspace's registered `PhoneAccount`
- **AND** create a Subspace-owned `Connection` for the capture interval

#### Scenario: Do not start capture before call audio route is ready
- **WHEN** a Subspace Telecom car PTT connection is created
- **THEN** the system SHALL wait for Telecom call audio state to report an acceptable car capture route before starting microphone recording

#### Scenario: Bluetooth call audio route starts capture
- **WHEN** the active Subspace Telecom car PTT connection reports Bluetooth call audio as active
- **THEN** the system SHALL start microphone capture for the active channel
- **AND** mark the car PTT session as recording

#### Scenario: RSM SCO does not satisfy car capture readiness
- **WHEN** an RSM Bluetooth SCO endpoint is active or warm
- **AND** no Telecom-owned car call-audio route is ready for the active Subspace car PTT connection
- **THEN** the system SHALL NOT start OnTheRoad microphone capture
