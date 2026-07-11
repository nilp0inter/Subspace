## Purpose

Defines the KeyboardChannel capability for PTT-driven speech-to-text typing via the sleepwalker BLE bridge.

## Requirements

### Requirement: Keyboard channel identity and configuration
The system SHALL provide a `KeyboardChannel` subtype of `Channel` with a stable
ID (`"keyboard-channel"`), a human-readable name, a configurable target host
profile (drawn from `sleepwalker-core`'s `HostProfile`), and persisted
configuration that survives app restarts.

#### Scenario: Keyboard channel loaded from persisted configuration
- **WHEN** the app starts and `ChannelRepository.loadChannels()` is called
- **THEN** the returned list SHALL include a `KeyboardChannel` whose host
  profile is the previously persisted value, or the default profile
  (`LINUX_US`) if none was persisted

#### Scenario: Host profile changed at runtime
- **WHEN** the user selects a new host profile on the keyboard channel config
  screen
- **THEN** the selection SHALL be persisted immediately
- **AND** the next PTT cycle on the keyboard channel SHALL use the new profile
  without requiring a service restart

### Requirement: Keyboard channel readiness
The `KeyboardChannel.isReady` state SHALL evaluate to `true` if and only if a
host profile is configured AND the sleepwalker BLE bridge is connected. STT
model readiness SHALL NOT factor into `isReady`; it is a separate runtime
monitor state.

#### Scenario: Bridge connected and profile configured
- **WHEN** the keyboard channel has a host profile set
- **AND** the sleepwalker BLE bridge reports `Connected`
- **THEN** `KeyboardChannel.isReady` SHALL be `true`

#### Scenario: Bridge disconnected
- **WHEN** the sleepwalker BLE bridge is not connected
- **THEN** `KeyboardChannel.isReady` SHALL be `false`
- **AND** the dashboard and Android Auto browse tree SHALL show the channel as
  Standby

### Requirement: Keyboard channel PTT lifecycle
The keyboard channel SHALL follow the capture → transcribe → type lifecycle on
a PTT press→release cycle: on press, acquire the audio route and begin
recording; on release, stop recording, transcribe the PCM via the existing STT
pipeline, plan the transcript into HID operations via `TextPlanner`, and send
the operations to the sleepwalker bridge.

#### Scenario: Successful capture, transcription, and typing
- **WHEN** the keyboard channel is active and ready
- **AND** the user presses and releases PTT
- **THEN** the system SHALL capture audio through the resolved audio route
- **AND** SHALL transcribe the capture to text via the STT pipeline
- **AND** SHALL plan the text into HID operations for the configured host
  profile
- **AND** SHALL send the operations to the sleepwalker bridge
- **AND** SHALL release the audio route after typing completes
- **AND** SHALL surface a `Done` status with the typed text

#### Scenario: Empty audio capture
- **WHEN** the user presses and releases PTT without producing audio
- **THEN** the system SHALL surface an `EmptyAudio` status
- **AND** SHALL NOT attempt transcription or typing
- **AND** SHALL release the audio route

#### Scenario: STT model not ready
- **WHEN** the user releases PTT
- **AND** the STT model has not finished loading
- **THEN** the system SHALL surface an `Error` status with reason
  `"STT model not ready"`
- **AND** SHALL NOT attempt typing
- **AND** SHALL release the audio route

#### Scenario: Transcription failure
- **WHEN** the STT pipeline returns a failure
- **THEN** the system SHALL surface an `Error` status with the failure reason
- **AND** SHALL NOT attempt typing
- **AND** SHALL release the audio route

### Requirement: HID safety discipline
The keyboard channel SHALL arm the sleepwalker bridge before sending any HID
operations and SHALL disarm it after the sequence completes. On any error
during typing, the channel SHALL issue a `kill` command (force release-all +
disarm) to the bridge if the connection is still open.

#### Scenario: Normal typing sequence
- **WHEN** the channel begins typing a transcript
- **THEN** it SHALL send an `arm` operation first
- **AND** SHALL send the typed key operations
- **AND** SHALL await the `SENT_TO_USB` status notification (correlation ACK) for the last sent keystroke operation before disarming
- **AND** SHALL send a `disarm` operation last

#### Scenario: Error during typing
- **WHEN** an error occurs after `arm` has been sent
- **AND** the BLE connection is still open
- **THEN** the channel SHALL send a `kill` operation before surfacing the
  error

### Requirement: Text rendering failure handling
The keyboard channel SHALL surface an `Error` status with the failure reason and
SHALL NOT send any HID operations for a transcript that `TextPlanner.plan`
cannot represent on the configured host profile.

#### Scenario: Unrepresentable glyph in transcript
- **WHEN** the transcript contains a character the host profile cannot type
- **THEN** the channel SHALL surface an `Error` status naming the
  unrepresentable character and the profile
- **AND** SHALL NOT arm the bridge or send any key operations
- **AND** SHALL release the audio route

### Requirement: Keyboard channel status reporting
The system SHALL surface a `KeyboardStatus` state in `MonitorState` that
progresses through the channel's lifecycle: `Idle`, `Recording`,
`Transcribing`, `Typing`, `Done` (with typed text), and `Error` (with
reason). The status SHALL be updated on every lifecycle transition.

#### Scenario: Status transitions during a successful cycle
- **WHEN** the user presses PTT, speaks, and releases
- **THEN** the status SHALL transition `Idle` → `Recording` → `Transcribing`
  → `Typing` → `Done`

#### Scenario: Status reset on cancel
- **WHEN** the active PTT is cancelled or the channel is deactivated
- **THEN** the status SHALL reset to `Idle`

### Requirement: Sleepwalker BLE bridge connection management
The system SHALL manage a single BLE connection to the sleepwalker bridge,
owned by the foreground service, that scans by device name `"sleepwalker"`,
discovers `BleUuids.SERVICE`, enables TX notifications, requests MTU 247, and
writes the RX characteristic with `WRITE_TYPE_NO_RESPONSE`. The connection
state SHALL be exposed as a `StateFlow` consumed by both the keyboard
controller and the UI.

#### Scenario: Bridge connects on demand
- **WHEN** the keyboard channel config screen is opened or the channel is
  activated
- **THEN** the system SHALL initiate a scan for a device named `"sleepwalker"`
- **AND** SHALL request MTU 247 on connection
- **AND** SHALL write the descriptor to enable TX notifications after the MTU updates
- **AND** SHALL transition the connection state through `Scanning` →
  `Connecting` → `Connected` only after the descriptor write completes

#### Scenario: Bridge disconnects
- **WHEN** the BLE connection to the sleepwalker is lost
- **THEN** the connection state SHALL transition to `Disconnected`
- **AND** `KeyboardChannel.isReady` SHALL re-evaluate to `false`
- **AND** any in-flight typing operation SHALL be treated as failed

#### Scenario: Send HID operation over connection
- **WHEN** the keyboard controller sends a `LowLevelOp`
- **THEN** the connection SHALL frame the op (`toFrameBytes()`), chunk it to
  the negotiated MTU (`BleWriter.chunkFrame`), write each chunk to the RX
  characteristic with `WRITE_TYPE_NO_RESPONSE`
- **AND** SHALL introduce a 15ms queue delay between consecutive characteristic writes to prevent BLE stack saturation

### Requirement: Keyboard channel dispatch integration
The PTT dispatch in `PttForegroundService` SHALL route press, release, and
cancel events to the `KeyboardPttController` when `KeyboardChannel.ID` is the
active channel, under the same mutual-exclusion and readiness rules as the
existing channels.

#### Scenario: PTT pressed on active keyboard channel
- **WHEN** the keyboard channel is active and ready
- **AND** a PTT press is dispatched
- **THEN** the system SHALL call `KeyboardPttController.onPttPressed(route)`
  with the resolved audio route

#### Scenario: Channel deactivated while active
- **WHEN** the user switches away from the keyboard channel
- **THEN** the system SHALL call `KeyboardPttController.cancelAndRelease()`
- **AND** SHALL NOT keep the keyboard controller enabled
