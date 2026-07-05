## MODIFIED Requirements

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel's readiness state before dispatching a PTT capture, regardless of which input mode or actuator initiated the PTT. Route resolution SHALL be based on the active `InputMode`, not on the `PttSource`, and the resolved route SHALL match that mode's required physical input/output endpoint.

#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `Work` mode using the B02PTT-FF01 RSM SCO input and output endpoint
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode using the Telecom self-call capture route and car media response route
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode using phone/local capture and media output without SCO
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
- **AND** resolve the audio route for `Work` mode
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: Android Auto play/pause auto-transitions to On-the-road mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad` mode
- **AND** resolve the audio route for `OnTheRoad` mode
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: Active SCO endpoint from another mode is not reused
- **WHEN** the active `InputMode` is `Work`
- **AND** a car Bluetooth SCO endpoint is active from a previous on-the-road cycle
- **THEN** route resolution SHALL NOT treat that active car SCO endpoint as a valid Work route

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved audio route if PTT is pressed while the target channel is not ready. The audio route SHALL be resolved based on the active `InputMode` and SHALL match that mode's required physical output endpoint.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the B02PTT-FF01 RSM SCO output route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the OnTheRoad route when possible
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the OnAPinch media/local output route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is false
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** play a two-tone error beep on the OnAPinch media/local output route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Work error beep ignores active car SCO
- **WHEN** PTT is pressed while in `Work` mode and the target channel is not ready
- **AND** a car SCO endpoint is active or warm
- **THEN** the error beep SHALL NOT be routed to the car endpoint
