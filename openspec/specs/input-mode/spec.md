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

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel's readiness state before dispatching a PTT capture, regardless of which input mode or actuator initiated the PTT. Route resolution SHALL be based on the active `InputMode` after actuator auto-transition succeeds, and each mode's route acquisition SHALL prove ownership of its semantic endpoint before capture begins.

#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL acquire the Work audio route by proving target RSM HFP ownership
- **AND** resolve the audio route for `Work` mode using the target RSM-owned SCO transport
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode (Telecom self-call for car SCO)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode (default local phone route, not Work or car SCO)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: Phone PTT on a ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is true
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** resolve the audio route for `OnAPinch` mode
- **AND** dispatch the capture to that channel's designated controller

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work` mode
- **AND** acquire the target RSM-owned Work route
- **AND** resolve the audio route for `Work` mode
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: RSM PTT Work route acquisition fails closed
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **BUT** the system cannot prove target RSM HFP ownership of the SCO transport
- **THEN** the system SHALL NOT dispatch capture to the active channel's designated controller
- **AND** the system SHALL NOT resolve capture through the OnTheRoad car route
- **AND** the system SHALL leave no active Work route lease behind

#### Scenario: Android Auto play/pause auto-transitions to On-the-road mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad` mode
- **AND** resolve the audio route for `OnTheRoad` mode
- **AND** dispatch the capture to the active channel's designated controller
### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved home-mode audio route if PTT is pressed while the target channel is not ready and that home-mode route can be safely acquired. The system SHALL NOT play error feedback through a different mode's endpoint when actuator auto-transition or route acquisition fails.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is false
- **AND** target RSM HFP ownership of the Work SCO route can be proven
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route (target RSM-owned SCO)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Work not-ready beep skipped when RSM route ownership cannot be proven
- **WHEN** PTT is pressed for the Work/RSM actuator path
- **AND** the active channel's `isReady` state is false or route acquisition fails
- **AND** target RSM HFP ownership of the Work SCO route cannot be proven
- **THEN** the system SHALL NOT play the error beep through the car route or any non-Work endpoint
- **AND** the system SHALL drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route (media/local output)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is false
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** play a two-tone error beep on the On-a-pinch mode audio route
- **AND** drop the PTT capture without routing to a controller

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
