## ADDED Requirements

### Requirement: InputMode selects physical audio endpoints
The system SHALL use the active `InputMode` as the authority for the physical audio input and output endpoint. `Work` SHALL use the B02PTT-FF01 RSM communication endpoint, `OnTheRoad` SHALL use the car Telecom/call-audio capture endpoint and car media response endpoint, and `OnAPinch` SHALL use phone/local audio without SCO.

#### Scenario: RSM actuator selects Work endpoint policy
- **WHEN** the RSM PTT button is pressed while `Work` mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** subsequent route resolution SHALL require the RSM communication endpoint for capture and output

#### Scenario: Car actuator selects OnTheRoad endpoint policy
- **WHEN** the Android Auto play/pause signal starts a car PTT cycle while `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** subsequent route resolution SHALL require the Telecom car call-audio capture path and car media response path

#### Scenario: Phone actuator selects OnAPinch endpoint policy
- **WHEN** a phone channel card is long-pressed
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** subsequent route resolution SHALL use phone/local capture and media output without acquiring SCO

## MODIFIED Requirements

### Requirement: Mode availability gates
The system SHALL compute mode availability based on device connectivity and endpoint-specific audio readiness, and SHALL expose it to the UI for display.

#### Scenario: Work mode available
- **WHEN** permissions are granted, Bluetooth is enabled, the RSM is bonded, SPP is connected, and the B02PTT-FF01 is available as a Bluetooth SCO communication endpoint
- **THEN** `Work` mode SHALL be available for selection

#### Scenario: Work mode unavailable
- **WHEN** any of permissions, Bluetooth, RSM bonding, SPP connection, or B02PTT-FF01 SCO endpoint availability is missing
- **THEN** `Work` mode SHALL be unavailable and greyed out in the selector

#### Scenario: Non-RSM SCO does not make Work available
- **WHEN** the RSM is bonded and SPP connected
- **AND** a Bluetooth SCO endpoint is available only for a different device such as the car
- **THEN** `Work` mode SHALL remain unavailable

#### Scenario: On-the-road mode available
- **WHEN** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode unavailable
- **WHEN** no media browser client is connected to `CarMediaSessionService`
- **THEN** `OnTheRoad` mode SHALL be unavailable and greyed out in the selector

#### Scenario: On-a-pinch mode always available
- **WHEN** the system is running
- **THEN** `OnAPinch` mode SHALL always be available for selection
