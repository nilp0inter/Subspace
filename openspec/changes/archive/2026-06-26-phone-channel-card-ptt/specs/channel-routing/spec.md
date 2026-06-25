## MODIFIED Requirements

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel's readiness state before dispatching a PTT capture, regardless of whether PTT was started by the RSM or by phone channel-card long-press.

#### Scenario: PTT on a ready active channel
- **WHEN** RSM PTT is pressed and the active channel's `isReady` state is true
- **THEN** the system SHALL dispatch the capture to the active channel's designated controller

#### Scenario: Phone PTT on a ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is true
- **THEN** the system SHALL set that channel as the active channel
- **AND** dispatch the capture to that channel's designated controller

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved audio route if PTT is pressed while the target channel is not ready.

#### Scenario: RSM PTT on a not-ready active channel
- **WHEN** RSM PTT is pressed and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the resolved audio route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel's `isReady` state is false
- **THEN** the system SHALL set that channel as the active channel
- **AND** play a two-tone error beep on the resolved audio route
- **AND** drop the PTT capture without routing to a controller
