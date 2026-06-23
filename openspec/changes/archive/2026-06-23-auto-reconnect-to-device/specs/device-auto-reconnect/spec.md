## ADDED Requirements

### Requirement: Unexpected serial loss triggers automatic reconnect
The system SHALL automatically attempt to reconnect the SPP serial control channel to the bonded `B02PTT-FF01` after serial monitoring ends unexpectedly while monitoring is still user-requested.

#### Scenario: SPP session ends without explicit disconnect
- **WHEN** serial monitoring has been started by the user and the active SPP event stream ends without the user requesting disconnect
- **THEN** the system keeps monitoring intent active
- **AND** the system schedules an automatic SPP reconnect attempt for the bonded target device
- **AND** the system does not stop the foreground device-link service solely because of that serial loss

#### Scenario: Reconnect succeeds
- **WHEN** an automatic reconnect attempt succeeds
- **THEN** the serial control channel becomes connected again
- **AND** subsequent hardware button events from the device are processed by the existing button parser and monitor state machine

### Requirement: Explicit disconnect stops automatic reconnect
The system SHALL treat the user's disconnect action as cancellation of serial monitoring intent and MUST NOT automatically reconnect after that action.

#### Scenario: User disconnects during an active SPP session
- **WHEN** the user requests serial disconnect while SPP is connected
- **THEN** the system closes the active serial connection
- **AND** the system clears monitoring intent
- **AND** the system cancels any pending automatic reconnect work
- **AND** the system removes foreground device-link ownership

#### Scenario: User disconnects while reconnect is pending
- **WHEN** the user requests serial disconnect while an automatic reconnect attempt is pending or in progress
- **THEN** the system cancels pending and in-progress automatic reconnect work
- **AND** the system MUST NOT start another reconnect attempt until the user requests serial connection again

### Requirement: Reconnect respects connection prerequisites
The system SHALL attempt automatic serial reconnect only when required permissions are granted, Android Bluetooth is enabled, and the target device is bonded.

#### Scenario: Permissions are missing
- **WHEN** automatic reconnect is evaluated and required permissions are missing
- **THEN** the system does not start an SPP reconnect attempt
- **AND** the system reports the connection as not ready

#### Scenario: Bluetooth is disabled
- **WHEN** automatic reconnect is evaluated and Android Bluetooth is disabled
- **THEN** the system does not start an SPP reconnect attempt
- **AND** the system reports the connection as not ready

#### Scenario: Target is not bonded
- **WHEN** automatic reconnect is evaluated and no bonded `B02PTT-FF01` target is available
- **THEN** the system does not start an SPP reconnect attempt
- **AND** the system reports the target device as unavailable or not found

### Requirement: Reconnect attempts are serialized and delayed
The system SHALL avoid concurrent SPP reconnect attempts and SHALL wait between failed reconnect attempts while monitoring intent remains active.

#### Scenario: Reconnect already in progress
- **WHEN** an automatic reconnect attempt is already in progress
- **THEN** the system does not start a second concurrent SPP reconnect attempt

#### Scenario: Reconnect attempt fails while still eligible
- **WHEN** an automatic reconnect attempt fails and monitoring intent remains active with all reconnect prerequisites satisfied
- **THEN** the system schedules another reconnect attempt after a delay
- **AND** the system does not retry in a tight loop

### Requirement: Existing manual setup remains available
The system SHALL preserve the existing manual setup and fallback actions for permissions, Bluetooth settings, scanning, pairing, readiness retry, and manual serial connection.

#### Scenario: Device has not been paired
- **WHEN** the target device is not bonded
- **THEN** the user can still use the existing scan and pair controls to prepare the device
- **AND** automatic serial reconnect does not replace the pairing flow

#### Scenario: Manual connect after cancellation
- **WHEN** automatic reconnect has been cancelled by explicit disconnect
- **THEN** the user can request serial connection manually again
- **AND** a new monitoring intent is established for that session
