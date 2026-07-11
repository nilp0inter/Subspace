## MODIFIED Requirements

### Requirement: Eligible service startup initiates the first serial connection
The system SHALL establish serial monitoring intent when the device-link service starts, SHALL establish started foreground-service ownership before automatic monitoring work depends on surviving the activity binding, and SHALL schedule an immediate first SPP serial connection when required permissions are granted, Android Bluetooth is enabled, and the bonded `B02PTT-FF01` target is available. Eligibility SHALL NOT depend on any prior manual or successful SPP session. Removing the activity binding because the app is backgrounded or the phone is locked SHALL NOT stop an active started device-link service.

#### Scenario: First launch with bonded target available
- **WHEN** the device-link service starts with required permissions granted, Bluetooth enabled, and the target RSM bonded
- **AND** no serial session has previously connected
- **THEN** the system SHALL establish monitoring intent
- **AND** establish started foreground-service ownership
- **AND** schedule an immediate SPP connection attempt
- **AND** process hardware button events after that connection succeeds

#### Scenario: Startup prerequisites are incomplete
- **WHEN** the device-link service starts while permissions are missing, Bluetooth is disabled, or no bonded target is available
- **THEN** the system SHALL NOT start an ineligible SPP attempt
- **AND** SHALL retain startup monitoring intent while the started foreground service remains active
- **AND** a later readiness refresh SHALL schedule the first attempt when all prerequisites become available

#### Scenario: App is backgrounded after automatic startup
- **WHEN** automatic monitoring has started without the user first requesting manual serial connection
- **AND** the activity loses visibility and removes its service binding
- **THEN** the started foreground device-link service SHALL remain active
- **AND** the foreground notification SHALL remain present
- **AND** active SPP monitoring or eligible reconnect work SHALL continue
- **AND** subsequent RSM hardware button events SHALL continue to be processed

#### Scenario: Phone locks after automatic startup
- **WHEN** automatic monitoring has started without the user first requesting manual serial connection
- **AND** locking the phone stops the activity and removes its service binding
- **THEN** the started foreground device-link service SHALL remain active
- **AND** the foreground notification SHALL remain present
- **AND** active SPP monitoring or eligible reconnect work SHALL continue
- **AND** subsequent RSM hardware button events SHALL continue to be processed while the process remains alive

#### Scenario: Explicit disconnect suppresses startup monitoring for the current service lifetime
- **WHEN** the user explicitly disconnects serial monitoring after automatic startup monitoring was established
- **THEN** the system SHALL clear monitoring intent
- **AND** cancel active or scheduled serial attempts
- **AND** remove started foreground-service ownership
- **AND** subsequent readiness refreshes in that service lifetime SHALL NOT re-establish monitoring intent

#### Scenario: Later service start restores automatic startup behavior
- **WHEN** a prior service lifetime ended after explicit disconnect and a new device-link service instance starts with all prerequisites available
- **THEN** the new service instance SHALL establish startup monitoring intent
- **AND** establish started foreground-service ownership
- **AND** schedule an immediate first attempt for that service lifetime

#### Scenario: Manual connect remains an explicit recovery action
- **WHEN** the user requests manual serial connection while no SPP session is active
- **THEN** the system SHALL establish monitoring intent
- **AND** establish or retain started foreground-service ownership
- **AND** use the same serialized prerequisite-gated connection scheduler as automatic startup
- **AND** SHALL NOT create a concurrent connection attempt

### Requirement: Reconnect respects connection prerequisites
The system SHALL attempt automatic serial reconnect only when required permissions are granted, Android Bluetooth is enabled, and the target device is bonded. While serial monitoring intent remains active, a temporary prerequisite failure SHALL NOT remove started foreground-service ownership, and the system SHALL continue readiness refresh so recovery can resume automatic connection without reopening the app.

#### Scenario: Permissions are missing
- **WHEN** automatic reconnect is evaluated and required permissions are missing
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the connection as not ready
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after permission recovery

#### Scenario: Bluetooth is disabled
- **WHEN** automatic reconnect is evaluated and Android Bluetooth is disabled
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the connection as not ready
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after Bluetooth is enabled

#### Scenario: Target is not bonded or temporarily unavailable
- **WHEN** automatic reconnect is evaluated and no eligible `B02PTT-FF01` target is available
- **THEN** the system SHALL NOT start an SPP reconnect attempt
- **AND** the system SHALL report the target device as unavailable or not found
- **AND** the started foreground service SHALL remain active while monitoring intent remains active
- **AND** a later readiness refresh SHALL resume eligible automatic connection after the target becomes available

#### Scenario: Monitoring was explicitly stopped
- **WHEN** reconnect prerequisites are blocked after the user explicitly disconnected serial monitoring
- **THEN** the system SHALL NOT retain foreground-service ownership for recovery
- **AND** readiness refresh SHALL NOT re-establish monitoring intent
