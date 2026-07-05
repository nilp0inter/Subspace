## MODIFIED Requirements

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
