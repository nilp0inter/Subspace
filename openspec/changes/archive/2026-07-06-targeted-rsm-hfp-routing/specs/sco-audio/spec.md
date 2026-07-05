## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: SCO route is acquired on PTT press
The system SHALL acquire the Work Bluetooth SCO communication route by targeting the `B02PTT-FF01` `BluetoothDevice` through the `BluetoothHeadset` profile, then using the active `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` only as the transport after target RSM ownership is proven.

#### Scenario: Work SCO is inactive on PTT press
- **WHEN** Work SCO is inactive, Work mode is selected or auto-selected, and the user presses RSM PTT
- **THEN** the system SHALL resolve the target RSM `BluetoothDevice`
- **AND** verify the target RSM Headset/HFP profile connection is connected
- **AND** call `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** poll `BluetoothHeadset.isAudioConnected(targetRsm)` or observe target audio state until RSM HFP audio is connected
- **AND** select the active `TYPE_BLUETOOTH_SCO` communication device as the Work transport
- **AND** report SCO state transitions: `Inactive → Starting → Active`

#### Scenario: Work SCO is already active on PTT press
- **WHEN** Work SCO is already active and owned by `B02PTT-FF01` from a previous warm Work session
- **AND** the user presses RSM PTT
- **THEN** the system SHALL return immediately without re-acquisition
- **AND** the system SHALL report SCO state `Active`

#### Scenario: Target RSM HFP is not connected
- **WHEN** Work route acquisition starts
- **AND** the target RSM Headset/HFP profile is not connected
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP not connected")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted voice recognition start fails
- **WHEN** Work route acquisition calls `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** the call returns false or throws
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP audio start failed")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted HFP audio connection times out
- **WHEN** Work route acquisition starts target RSM voice recognition
- **AND** `BluetoothHeadset.isAudioConnected(targetRsm)` does not become true before timeout
- **THEN** the system SHALL report SCO state `Failed("Timed out waiting for target RSM HFP audio")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Generic SCO device is not found after target ownership proof
- **WHEN** `BluetoothHeadset.isAudioConnected(targetRsm)` is true
- **BUT** no `TYPE_BLUETOOTH_SCO` communication transport is available to route AudioTrack output
- **THEN** the system SHALL report SCO state `Failed("Bluetooth SCO transport not available")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure

### Requirement: SCO warmup retention
The system SHALL use centralized reference counting to keep the Work SCO route active for 30 seconds after the last active Work client releases the route, but the retained route SHALL remain bound to the target RSM owner and SHALL NOT be reused by other input sources or modes.

#### Scenario: Active Work client drops to zero — RSM SCO retained warm
- **WHEN** all components that have acquired the Work SCO route release it (active client count drops to 0)
- **AND** the active route is still owned by `B02PTT-FF01`
- **THEN** the system SHALL keep target RSM HFP audio active for up to 30 seconds
- **AND** the system SHALL keep the selected Work SCO transport associated with the RSM owner
- **AND** the system SHALL report SCO state as `Active` during the warmup window

#### Scenario: Warmup expires
- **WHEN** 30 seconds pass with the active Work client count remaining at 0
- **THEN** the system SHALL call `BluetoothHeadset.stopVoiceRecognition(targetRsm)` if target RSM voice recognition remains active
- **AND** the system SHALL clear the selected Work SCO transport
- **AND** the system SHALL set audio manager mode back to `MODE_NORMAL` when no other route owner requires communication mode
- **AND** the system SHALL report SCO state transition: `Active → Closing → Inactive`

#### Scenario: Work route acquired during warmup
- **WHEN** a Work warmup delay is active and a Work component requests to acquire the route
- **THEN** the system SHALL cancel the warmup delay
- **AND** the system SHALL increment the active client count
- **AND** the system SHALL return immediately without re-acquisition since the target RSM-owned route is already active

#### Scenario: Different mode requested during Work warmup
- **WHEN** a Work warmup delay is active
- **AND** an OnTheRoad or OnAPinch route is requested
- **THEN** the system SHALL stop target RSM voice recognition before acquiring the non-Work route
- **AND** the system SHALL NOT reuse the Work SCO transport for the different mode
