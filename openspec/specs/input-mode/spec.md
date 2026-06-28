## Purpose

TBD. Defines the InputMode state machine (Work, OnTheRoad, OnAPinch), availability gates, automatic transitions, user selection, and actuator gating for PTT operations.

## Requirements

### Requirement: InputMode state machine
The system SHALL maintain a first-class `InputMode { Work, OnTheRoad, OnAPinch }` state that determines audio route resolution, actuator gating, and feedback surface for PTT operations.

#### Scenario: Default mode on launch
- **WHEN** Subspace launches with no devices connected
- **THEN** the system SHALL set `InputMode` to `OnAPinch`

#### Scenario: Default mode with RSM connected
- **WHEN** Subspace launches with the RSM already bonded, SPP connected, and SCO available
- **THEN** the system SHALL set `InputMode` to `Work`

#### Scenario: Default mode with Android Auto connected
- **WHEN** Subspace launches with Android Auto already projecting (media browser client connected)
- **AND** the RSM is not bonded
- **THEN** the system SHALL set `InputMode` to `OnTheRoad`

### Requirement: Mode availability gates
The system SHALL compute mode availability based on device connectivity and expose it to the UI for display.

#### Scenario: Work mode available
- **WHEN** `connection.readyForMonitor` is true (permissions granted, Bluetooth enabled, RSM bonded, SPP connected, SCO available)
- **THEN** `Work` mode SHALL be available for selection

#### Scenario: Work mode unavailable
- **WHEN** any of `connection.readyForMonitor` is false
- **THEN** `Work` mode SHALL be unavailable and greyed out in the selector

#### Scenario: On-the-road mode available
- **WHEN** `CarMediaSessionService` has at least one active media browser client connection
- **THEN** `OnTheRoad` mode SHALL be available for selection

#### Scenario: On-the-road mode unavailable
- **WHEN** no media browser client is connected to `CarMediaSessionService`
- **THEN** `OnTheRoad` mode SHALL be unavailable and greyed out in the selector

#### Scenario: On-a-pinch mode always available
- **WHEN** the system is running
- **THEN** `OnAPinch` mode SHALL always be available for selection

### Requirement: Automatic mode transitions on device events
The system SHALL automatically transition between modes based on device connectivity events.

#### Scenario: Android Auto connects
- **WHEN** Android Auto connects (media browser client arrives) while in any mode
- **THEN** the system SHALL transition to `OnTheRoad`

#### Scenario: RSM connects from On-a-pinch
- **WHEN** the RSM bonds and SPP connects while in `OnAPinch`
- **THEN** the system SHALL transition to `Work`

#### Scenario: RSM connects from On-the-road
- **WHEN** the RSM bonds and SPP connects while in `OnTheRoad`
- **THEN** the system SHALL remain in `OnTheRoad`
- **AND** `Work` mode SHALL become available in the selector

#### Scenario: On-the-road disconnect with RSM bonded
- **WHEN** Android Auto disconnects while in `OnTheRoad`
- **AND** the RSM is bonded
- **THEN** the system SHALL transition to `Work`

#### Scenario: On-the-road disconnect without RSM
- **WHEN** Android Auto disconnects while in `OnTheRoad`
- **AND** the RSM is not bonded
- **THEN** the system SHALL transition to `OnAPinch`

#### Scenario: Work disconnect with Android Auto connected
- **WHEN** the RSM disconnects while in `Work`
- **AND** Android Auto is connected (media browser client present)
- **THEN** the system SHALL transition to `OnTheRoad`

#### Scenario: Work disconnect without Android Auto
- **WHEN** the RSM disconnects while in `Work`
- **AND** Android Auto is not connected
- **THEN** the system SHALL transition to `OnAPinch`

### Requirement: User mode selection
The system SHALL allow the user to select any available mode from the main dashboard.

#### Scenario: User selects an available mode
- **WHEN** the user taps an available mode in the mode selector
- **THEN** the system SHALL transition to that mode
- **AND** apply the mode's audio route policy and actuator gating

#### Scenario: User selects an unavailable mode
- **WHEN** the user taps an unavailable (greyed out) mode
- **THEN** the system SHALL not transition and SHALL not register a tap on the greyed-out control

#### Scenario: Rules re-assert after user selection
- **WHEN** the user selects a mode and a subsequent device event triggers a transition rule
- **THEN** the rule SHALL take effect regardless of the user's prior selection

### Requirement: Actuator auto-transition
The system SHALL automatically transition to the home mode of any actuator that is pressed, regardless of the current mode, and then dispatch the PTT.

#### Scenario: RSM PTT pressed from any mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** dispatch the PTT in `Work` mode

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
- **THEN** the system SHALL not transition and SHALL play an error beep if the current mode's audio route can deliver it

### Requirement: Mode-exclusive actuator gating during active capture
The system SHALL prevent a new capture from starting while a PTT session is already active, regardless of which actuator or mode started it.

#### Scenario: Second actuator press during active capture
- **WHEN** a PTT session is active (capture in progress)
- **AND** any other actuator is pressed
- **THEN** the system SHALL ignore the second press
- **AND** the active capture SHALL continue uninterrupted

### Requirement: Visible mode selector on main dashboard
The system SHALL display a mode selector on the main dashboard showing all three modes with availability indicators.

#### Scenario: All modes visible
- **WHEN** the main dashboard is displayed
- **THEN** the selector SHALL show `Work`, `OnTheRoad`, and `OnAPinch` as selectable controls

#### Scenario: Unavailable mode greyed out
- **WHEN** a mode is unavailable
- **THEN** its selector control SHALL be visually greyed out and non-interactive

#### Scenario: Active mode highlighted
- **WHEN** a mode is active
- **THEN** its selector control SHALL be visually highlighted to indicate the current mode
