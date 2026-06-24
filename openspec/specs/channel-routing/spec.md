## Purpose

TBD. Defines the active channel routing, mutual exclusivity, and readiness evaluation before dispatching PTT audio.

## Requirements

### Requirement: Active channel mutual exclusivity
The system SHALL maintain a single `activeChannelId` to represent the selected communication channel, replacing independent channel enablement states.

#### Scenario: Switching active channel
- **WHEN** the user taps a channel card on the main dashboard
- **THEN** that channel SHALL become the active channel and all others SHALL become inactive

### Requirement: Channel readiness state
The system SHALL require channels to provide a computed `isReady` state indicating if they have all necessary configuration to handle PTT broadcasts.

#### Scenario: Fully configured channel
- **WHEN** the Captain's Log channel has a valid output directory and at least one save toggle enabled
- **THEN** its `isReady` state SHALL evaluate to true

#### Scenario: Incompletely configured channel
- **WHEN** the Captain's Log channel has no output directory selected
- **THEN** its `isReady` state SHALL evaluate to false

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active channel's readiness state before dispatching a PTT capture. 

#### Scenario: PTT on a ready channel
- **WHEN** PTT is pressed and the active channel's `isReady` state is true
- **THEN** the system SHALL dispatch the capture to the channel's designated controller

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the SCO headset if PTT is pressed while the active channel is not ready.

#### Scenario: PTT on a not-ready channel
- **WHEN** PTT is pressed and the active channel's `isReady` state is false
- **THEN** the system SHALL acquire the SCO link if needed, play a two-tone error beep to the headset, and drop the PTT capture without routing to a controller
