## MODIFIED Requirements

### Requirement: Active channel mutual exclusivity
The system SHALL maintain a single active channel instance ID to represent the selected communication channel instance, replacing independent channel enablement states and fixed singleton channel IDs.

#### Scenario: Switching active channel
- **WHEN** the user taps a channel card on the main dashboard
- **THEN** that channel instance SHALL become the active channel and all others SHALL become inactive

### Requirement: Channel readiness state
The system SHALL require channels to provide a computed `isReady` state indicating if they have all necessary configuration to handle PTT broadcasts. For configurable channels, readiness SHALL be computed for each channel instance using its channel type and instance-specific configuration.

#### Scenario: Fully configured channel
- **WHEN** a Journal channel instance has a valid output directory and at least one save toggle enabled
- **THEN** its `isReady` state SHALL evaluate to true

#### Scenario: Incompletely configured channel
- **WHEN** a Journal channel instance has no output directory selected
- **THEN** its `isReady` state SHALL evaluate to false

#### Scenario: Debug channel instance readiness
- **WHEN** a Debug channel instance exists with a known Debug channel type and a valid Debug mode
- **THEN** its `isReady` state SHALL evaluate to true

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel instance's readiness state before dispatching a PTT capture, regardless of which input mode or actuator initiated the PTT. Route resolution SHALL be based on the active `InputMode`, not on the `PttSource`. Dispatch SHALL resolve behavior from the target instance's channel type and type-specific configuration.

#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel instance's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `Work` mode (SCO via RSM headset)
- **AND** dispatch the capture to the active channel instance's designated type controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel instance's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode (Telecom self-call for SCO)
- **AND** dispatch the capture to the active channel instance's designated type controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel instance's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode (default audio route, not SCO)
- **AND** dispatch the capture to the active channel instance's designated type controller

#### Scenario: Phone PTT on a ready channel card
- **WHEN** a functional channel card is long-pressed and that channel instance's `isReady` state is true
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel instance as the active channel
- **AND** resolve the audio route for `OnAPinch` mode
- **AND** dispatch the capture to that channel instance's designated type controller

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** the RSM PTT button is pressed while in any mode
- **AND** `Work` mode is available
- **THEN** the system SHALL transition to `Work` mode
- **AND** resolve the audio route for `Work` mode
- **AND** dispatch the capture to the active channel instance's designated type controller

#### Scenario: Android Auto play/pause auto-transitions to On-the-road mode
- **WHEN** the Android Auto play/pause signal is received while in any mode
- **AND** `OnTheRoad` mode is available
- **THEN** the system SHALL transition to `OnTheRoad` mode
- **AND** resolve the audio route for `OnTheRoad` mode
- **AND** dispatch the capture to the active channel instance's designated type controller

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved audio route if PTT is pressed while the target channel instance is not ready. The audio route SHALL be resolved based on the active `InputMode`.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel instance's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route (SCO)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel instance's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel instance's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route (media/local output)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel instance's `isReady` state is false
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel instance as the active channel
- **AND** play a two-tone error beep on the On-a-pinch mode audio route
- **AND** drop the PTT capture without routing to a controller

## ADDED Requirements

### Requirement: PTT sessions retain channel instance identity
The system SHALL retain the channel instance identity and dispatch context selected at PTT press time until the matching PTT release completes.

#### Scenario: Channel list changes during active PTT
- **WHEN** a PTT session is active for a channel instance
- **AND** the channel list or active channel changes before PTT is released
- **THEN** the release event SHALL complete the original PTT session for the original channel instance
- **AND** the changed channel list SHALL affect only subsequent PTT sessions
