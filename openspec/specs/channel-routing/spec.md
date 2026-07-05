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
The system SHALL evaluate the target channel's readiness state before dispatching a PTT capture, regardless of which input mode or actuator initiated the PTT. Route resolution SHALL be based on the active `InputMode`, not on the `PttSource`.

### Requirement: Work SCO endpoint ownership is proven by target RSM HFP
The system SHALL prove Work/RSM SCO ownership with the target `B02PTT-FF01` `BluetoothDevice` in the `BluetoothHeadset` profile before treating any generic `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` as the Work transport.

#### Scenario: Target RSM owns anonymous SCO transport
- **WHEN** Work route acquisition calls `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** `BluetoothHeadset.isAudioConnected(targetRsm)` becomes true
- **AND** `AudioManager.communicationDevice` is `TYPE_BLUETOOTH_SCO` but its product name does not identify `B02PTT-FF01`
- **THEN** the system SHALL treat that SCO device as the Work transport owned by the target RSM
- **AND** the system SHALL route Work ready beep, capture, and playback through that transport

#### Scenario: Anonymous SCO without target RSM ownership
- **WHEN** a `TYPE_BLUETOOTH_SCO` device is available
- **BUT** `BluetoothHeadset.isAudioConnected(targetRsm)` is false or cannot be queried
- **THEN** the system SHALL NOT treat the SCO device as a Work/RSM transport
- **AND** the system SHALL NOT fall back to the first available SCO device

#### Scenario: Target HFP ownership diagnostics
- **WHEN** Work route acquisition starts, succeeds, fails, or releases
- **THEN** the system SHALL log target RSM HFP state, `startVoiceRecognition` result, `isAudioConnected(targetRsm)` state, selected SCO transport, and release result
- **AND** the system SHALL NOT log Bluetooth MAC addresses or PCM/audio payloads
#### Scenario: PTT on a ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `Work` mode (SCO via RSM headset)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnTheRoad` mode (Telecom self-call for SCO)
- **AND** dispatch the capture to the active channel's designated controller

#### Scenario: PTT on a ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel's `isReady` state is true
- **THEN** the system SHALL resolve the audio route for `OnAPinch` mode (default audio route, not SCO)
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

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the resolved audio route if PTT is pressed while the target channel is not ready. The audio route SHALL be resolved based on the active `InputMode`.

#### Scenario: PTT on a not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel's `isReady` state is false
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route (SCO)
- **AND** drop the PTT capture without routing to a controller

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
