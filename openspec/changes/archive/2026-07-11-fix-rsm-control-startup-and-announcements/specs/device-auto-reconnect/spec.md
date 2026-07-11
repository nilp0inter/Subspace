## ADDED Requirements

### Requirement: Eligible service startup initiates the first serial connection
The system SHALL establish serial monitoring intent when the device-link service starts and SHALL schedule an immediate first SPP serial connection when required permissions are granted, Android Bluetooth is enabled, and the bonded `B02PTT-FF01` target is available. Eligibility SHALL NOT depend on any prior manual or successful SPP session.

#### Scenario: First launch with bonded target available
- **WHEN** the device-link service starts with required permissions granted, Bluetooth enabled, and the target RSM bonded
- **AND** no serial session has previously connected
- **THEN** the system SHALL establish monitoring intent
- **AND** schedule an immediate SPP connection attempt
- **AND** process hardware button events after that connection succeeds

#### Scenario: Startup prerequisites are incomplete
- **WHEN** the device-link service starts while permissions are missing, Bluetooth is disabled, or no bonded target is available
- **THEN** the system SHALL NOT start an ineligible SPP attempt
- **AND** SHALL retain startup monitoring intent until explicit disconnect or service destruction
- **AND** a later readiness refresh SHALL schedule the first attempt when all prerequisites become available

#### Scenario: Explicit disconnect suppresses startup monitoring for the current service lifetime
- **WHEN** the user explicitly disconnects serial monitoring after automatic startup monitoring was established
- **THEN** the system SHALL clear monitoring intent
- **AND** cancel active or scheduled serial attempts
- **AND** subsequent readiness refreshes in that service lifetime SHALL NOT re-establish monitoring intent

#### Scenario: Later service start restores automatic startup behavior
- **WHEN** a prior service lifetime ended after explicit disconnect and a new device-link service instance starts with all prerequisites available
- **THEN** the new service instance SHALL establish startup monitoring intent
- **AND** schedule an immediate first attempt for that service lifetime

#### Scenario: Manual connect remains an explicit recovery action
- **WHEN** the user requests manual serial connection while no SPP session is active
- **THEN** the system SHALL establish monitoring intent
- **AND** use the same serialized prerequisite-gated connection scheduler as automatic startup
- **AND** SHALL NOT create a concurrent connection attempt
