## MODIFIED Requirements

### Requirement: Channel readiness state
The system SHALL require channels to provide a computed `isReady` state
indicating if they have all necessary configuration to handle PTT broadcasts.
For `KeyboardChannel`, `isReady` SHALL be `true` if and only if a host profile
is configured AND the sleepwalker BLE bridge is connected.

#### Scenario: Fully configured keyboard channel with bridge connected
- **WHEN** the keyboard channel has a host profile set
- **AND** the sleepwalker BLE bridge reports `Connected`
- **THEN** its `isReady` state SHALL evaluate to `true`

#### Scenario: Keyboard channel bridge disconnected
- **WHEN** the keyboard channel has a host profile set
- **AND** the sleepwalker BLE bridge is not connected
- **THEN** its `isReady` state SHALL evaluate to `false`

#### Scenario: Fully configured journal channel
- **WHEN** the Journal channel has a valid output directory and at least one
  save toggle enabled
- **THEN** its `isReady` state SHALL evaluate to `true`

#### Scenario: Incompletely configured journal channel
- **WHEN** the Journal channel has no output directory selected
- **THEN** its `isReady` state SHALL evaluate to `false`

### Requirement: PTT routing respects readiness
The system SHALL evaluate the target channel's readiness state before
dispatching a PTT capture, regardless of which input mode or actuator initiated
the PTT. Route resolution SHALL be based on the active `InputMode`, not on the
`PttSource`. When the active channel is `KeyboardChannel`, the system SHALL
dispatch press, release, and cancel to `KeyboardPttController`.

#### Scenario: PTT pressed on ready keyboard channel
- **WHEN** the keyboard channel is active and ready
- **AND** a PTT press is dispatched from any source
- **THEN** the system SHALL call `KeyboardPttController.onPttPressed(route)`
  with the resolved audio route

#### Scenario: PTT released on keyboard channel
- **WHEN** the keyboard channel is active
- **AND** a PTT release is dispatched
- **THEN** the system SHALL call `KeyboardPttController.onPttReleased(route)`

#### Scenario: Active PTT cancelled on keyboard channel
- **WHEN** the keyboard channel is active
- **AND** the active PTT session is cancelled (SPP disconnect, car terminal
  status, or channel switch)
- **THEN** the system SHALL call `KeyboardPttController.cancelAndRelease()`

#### Scenario: Keyboard channel deactivated
- **WHEN** the user switches the active channel away from the keyboard channel
- **THEN** the system SHALL call `KeyboardPttController.cancelAndRelease()`
- **AND** SHALL NOT keep the keyboard controller enabled
