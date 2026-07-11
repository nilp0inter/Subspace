## ADDED Requirements

### Requirement: Keyboard PTT recovers a disconnected Sleepwalker bridge
When an enabled and configured Keyboard channel is selected for PTT while the Sleepwalker bridge is disconnected, the system SHALL start or join one bounded bridge connection attempt before accepting or refusing the channel input. The system SHALL accept the Keyboard input only after the bridge reaches `Connected`, and SHALL NOT start capture or play the ready beep while connection recovery is pending.

#### Scenario: Disconnected bridge connects during PTT preparation
- **WHEN** the user presses PTT with an enabled and configured Keyboard channel selected
- **AND** the Sleepwalker bridge is disconnected
- **AND** the bounded connection attempt reaches `Connected`
- **THEN** the Keyboard runtime SHALL accept the input request
- **AND** the system SHALL continue through the normal route preflight, ready beep, capture, transcription, and HID delivery lifecycle

#### Scenario: Existing bridge connection attempt is joined
- **WHEN** the user presses PTT with an enabled and configured Keyboard channel selected
- **AND** the Sleepwalker bridge is already `Scanning` or `Connecting`
- **THEN** the Keyboard runtime SHALL await that connection attempt
- **AND** the system SHALL NOT start a second BLE scan or GATT connection
- **AND** the input SHALL proceed only if the shared attempt reaches `Connected`

#### Scenario: Bridge connection fails
- **WHEN** the Keyboard PTT connection attempt terminates without reaching `Connected`
- **THEN** the Keyboard runtime SHALL refuse the input request with the connection failure reason
- **AND** the system SHALL NOT start capture or play the ready beep

#### Scenario: Bridge connection times out
- **WHEN** the Keyboard PTT connection attempt does not reach `Connected` within the bounded timeout
- **THEN** the system SHALL stop or close the stale BLE attempt
- **AND** the Keyboard runtime SHALL refuse the input request with a timeout reason
- **AND** the system SHALL NOT start capture or play the ready beep

#### Scenario: PTT ends while bridge connection is pending
- **WHEN** the PTT is released or cancelled before the Sleepwalker bridge reaches `Connected`
- **THEN** that PTT session SHALL remain terminal and uncommitted
- **AND** a later connection result SHALL NOT start capture or play the ready beep for that PTT
- **AND** the system SHALL NOT require disconnecting the bridge if the shared connection attempt subsequently succeeds

#### Scenario: Bridge is already connected
- **WHEN** the user presses PTT with an enabled and configured Keyboard channel selected
- **AND** the Sleepwalker bridge is already `Connected`
- **THEN** bridge preparation SHALL complete without starting another connection attempt
- **AND** the existing Keyboard PTT lifecycle SHALL proceed unchanged
