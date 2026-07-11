## Purpose

TBD. Defines the active channel routing, mutual exclusivity, and readiness evaluation before dispatching PTT audio.

## Requirements

### Requirement: Active channel mutual exclusivity
The system SHALL maintain a single valid `activeChannelId` selected from the persisted ordered channel catalogue. Activating a channel instance SHALL inherently make every other instance inactive. Catalogue mutation SHALL preserve or atomically repair this invariant.

#### Scenario: Switching active channel
- **WHEN** the user selects a different catalogue instance from any supported surface
- **THEN** the system SHALL set that instance ID as the active channel
- **AND** all other instances SHALL become inactive

#### Scenario: Unknown channel selected
- **WHEN** a selection request references an ID absent from the current catalogue
- **THEN** the system SHALL reject the request
- **AND** it SHALL preserve the existing valid active channel ID

### Requirement: Channel readiness state
The system SHALL obtain readiness from the runtime entry associated with each channel instance. Readiness SHALL include valid enabled configuration plus live dependencies required by that instance's kind. Readiness evaluation SHALL be instance-specific even when multiple instances share a kind.

For a Keyboard instance, readiness SHALL be true only when its host profile is configured and the Sleepwalker BLE bridge is connected. For a Journal instance, readiness SHALL be true only when its output directory is valid and at least one save option is enabled. A Debug instance SHALL additionally require the resources needed by its configured debug mode.

#### Scenario: Ready instance
- **WHEN** an enabled channel instance has valid configuration and every live dependency required by its kind
- **THEN** its runtime readiness SHALL be true

#### Scenario: Instance dependency unavailable
- **WHEN** a channel instance lacks valid configuration or a required live dependency becomes unavailable
- **THEN** its runtime readiness SHALL be false
- **AND** readiness of other instances SHALL be evaluated independently

#### Scenario: Inactive Debug instance retains dependency readiness
- **WHEN** an enabled Debug instance has valid configuration and all dependencies for its configured mode but is not active
- **THEN** its runtime readiness SHALL remain true
- **AND** shared-controller activation state SHALL NOT be treated as dependency availability

#### Scenario: Keyboard bridge disconnected
- **WHEN** a Keyboard instance has a host profile but the Sleepwalker BLE bridge is disconnected
- **THEN** that Keyboard runtime SHALL be not ready

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active catalogue instance's runtime readiness before dispatching a PTT capture, regardless of which input mode or actuator initiated PTT. Route resolution SHALL remain based on active `InputMode`, not `PttSource`. Ready input SHALL be prepared through the ID-keyed runtime registry rather than fixed built-in ID branches. An enabled and configured Keyboard instance whose only unavailable live dependency is a disconnected or connecting Sleepwalker bridge SHALL enter pending input preparation so the runtime can attempt connection recovery; every other not-ready instance SHALL follow the immediate problem-feedback path.

#### Scenario: PTT on a ready active instance in Work mode
- **WHEN** PTT is pressed while `Work` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Work audio route
- **AND** request a committed input target from the runtime associated with the active instance ID

#### Scenario: PTT on a ready active instance in On-the-road mode
- **WHEN** PTT is pressed while `OnTheRoad` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Telecom audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: PTT on a ready active instance in On-a-pinch mode
- **WHEN** PTT is pressed while `OnAPinch` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the local audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: Disconnected Keyboard enters recoverable preparation
- **WHEN** PTT is pressed while the selected instance is an enabled and configured Keyboard channel
- **AND** the Sleepwalker bridge is not `Connected`
- **THEN** the system SHALL reserve one pending audio input session
- **AND** request recoverable input preparation from that Keyboard runtime
- **AND** SHALL NOT select the immediate not-ready problem-feedback path solely because the bridge is disconnected

#### Scenario: Non-Keyboard instance is not ready
- **WHEN** PTT is pressed while the selected non-Keyboard runtime is not ready
- **THEN** the system SHALL select the immediate problem-feedback path
- **AND** SHALL NOT request channel input preparation

#### Scenario: Phone PTT selects an instance
- **WHEN** the user starts phone PTT from a ready functional channel card
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that card's instance ID as active
- **AND** route capture through the matching runtime entry

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** RSM PTT is pressed while Work mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** route capture to the active runtime instance

#### Scenario: Android Auto PTT auto-transitions to On-the-road mode
- **WHEN** Android Auto PTT is initiated while On-the-road mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** route capture to the active runtime instance

#### Scenario: Runtime refuses preparation
- **WHEN** the active runtime becomes unavailable, fails recoverable preparation, or refuses input after route gating but before capture starts
- **THEN** the system SHALL NOT start capture
- **AND** host-owned error feedback and route cleanup SHALL follow the existing audio session policy

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
The system SHALL emit a characteristic two-tone error beep over the resolved audio route if PTT is pressed while the target channel is not ready and the channel is not eligible for recoverable input preparation. If recoverable Keyboard preparation is attempted, the system SHALL defer the error decision until that preparation fails or times out. The audio route SHALL be resolved based on the active `InputMode`.

#### Scenario: PTT on a non-recoverable not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active channel is not ready and is not eligible for recovery
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route (SCO)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a non-recoverable not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active channel is not ready and is not eligible for recovery
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without routing to a controller

#### Scenario: PTT on a non-recoverable not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active channel is not ready and is not eligible for recovery
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route (media/local output)
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Phone PTT on a non-recoverable not-ready channel card
- **WHEN** a functional channel card is long-pressed and that channel is not ready and is not eligible for recovery
- **THEN** the system SHALL transition to `OnAPinch` mode
- **AND** set that channel as the active channel
- **AND** play a two-tone error beep on the On-a-pinch mode audio route
- **AND** drop the PTT capture without routing to a controller

#### Scenario: Recoverable Keyboard connection succeeds
- **WHEN** PTT is pressed for a recoverable Keyboard channel whose Sleepwalker bridge is disconnected
- **AND** bridge preparation reaches `Connected`
- **THEN** the system SHALL NOT play the two-tone error beep
- **AND** SHALL continue through the normal ready beep and capture path

#### Scenario: Recoverable Keyboard connection fails
- **WHEN** PTT is pressed for a recoverable Keyboard channel whose Sleepwalker bridge is disconnected
- **AND** bridge preparation fails or times out
- **THEN** the system SHALL play the two-tone error beep on the resolved input-mode route when possible
- **AND** SHALL drop the PTT capture without playing the ready beep
