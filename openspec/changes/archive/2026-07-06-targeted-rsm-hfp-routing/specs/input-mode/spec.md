## MODIFIED Requirements

### Requirement: Mode availability gates
The system SHALL compute mode availability from semantic endpoint readiness and expose availability to the UI for display. Work availability SHALL be based on target RSM logical readiness rather than `AudioDeviceInfo` product-name identity.

#### Scenario: Work mode available
- **WHEN** Bluetooth/audio permissions are granted
- **AND** Bluetooth is enabled
- **AND** the target RSM `B02PTT-FF01` is bonded
- **AND** the target RSM serial/SPP connection is open for button and monitor events
- **AND** the target RSM is connected in the Bluetooth Headset/HFP profile
- **THEN** `Work` mode SHALL be available for selection

#### Scenario: Work mode available with anonymous SCO transport
- **WHEN** the Work availability inputs are satisfied
- **AND** Android exposes only a generic `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` whose product name does not identify `B02PTT-FF01`
- **THEN** `Work` mode SHALL still be available for selection

#### Scenario: Work mode unavailable
- **WHEN** any Work availability input is false
- **THEN** `Work` mode SHALL be unavailable and greyed out in the selector

#### Scenario: Work and On-the-road modes available together
- **WHEN** the Work availability inputs are satisfied
- **AND** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `Work` mode SHALL be available for selection
- **AND** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode available
- **WHEN** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode unavailable
- **WHEN** no media browser client is connected to `CarMediaSessionService`
- **THEN** `OnTheRoad` mode SHALL be unavailable and greyed out in the selector

#### Scenario: On-a-pinch mode always available
- **WHEN** the system is running
- **THEN** `OnAPinch` mode SHALL always be available for selection

### Requirement: Actuator auto-transition
The system SHALL automatically transition to the home mode of any actuator that is pressed when that home mode is available, and then dispatch the PTT using that mode's route acquisition rules. If the home mode is unavailable or route acquisition fails, the system SHALL fail closed without routing capture or feedback through a different endpoint.

#### Scenario: RSM PTT pressed from any mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** dispatch the PTT in `Work` mode
- **AND** Work route acquisition SHALL prove target RSM HFP ownership before capture or playback begins

#### Scenario: RSM PTT acquisition fails
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **BUT** target RSM HFP route acquisition fails
- **THEN** the system SHALL NOT start capture
- **AND** the system SHALL NOT use the OnTheRoad car route as fallback
- **AND** the system SHALL NOT play transition-failure feedback through the car route

#### Scenario: Phone channel long-press from any mode
- **WHEN** a channel card is long-pressed on the Android app while in any mode
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** dispatch the PTT in `OnAPinch` mode

#### Scenario: Android Auto play/pause from any mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** dispatch the PTT in `OnTheRoad` mode

#### Scenario: Actuator pressed for unavailable mode
- **WHEN** an actuator is pressed but its home mode is not available
- **THEN** the system SHALL not transition
- **AND** the system SHALL NOT resolve capture or feedback through a different mode's endpoint
