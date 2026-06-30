## ADDED Requirements

### Requirement: Disconnected readiness is periodically refreshed
The system SHALL periodically perform the same readiness checks as the manual `Retry readiness checks` action while the foreground device-link service is active and the aggregate device readiness gate is false.

#### Scenario: Device is not ready while service is active
- **WHEN** the foreground device-link service is active
- **AND** the aggregate device readiness gate is false
- **THEN** the system periodically refreshes readiness using the same checks as the manual `Retry readiness checks` action
- **AND** the refresh checks required permissions, Android Bluetooth enabled state, bonded target availability, and Bluetooth SCO headset availability

#### Scenario: Periodic refresh observes recovered readiness
- **WHEN** periodic readiness refresh is active
- **AND** a later refresh finds permissions granted, Android Bluetooth enabled, the target device bonded, SPP connected, and Bluetooth SCO headset availability restored
- **THEN** the aggregate device readiness gate becomes true without requiring the user to tap `Retry readiness checks`

#### Scenario: Device becomes ready
- **WHEN** periodic readiness refresh is active
- **AND** the aggregate device readiness gate becomes true
- **THEN** the system stops periodic readiness refresh work

#### Scenario: User explicitly disconnects serial monitoring
- **WHEN** periodic readiness refresh is active
- **AND** the user requests serial disconnect
- **THEN** the system stops periodic readiness refresh work
- **AND** the system does not use periodic readiness refresh to re-establish monitoring intent

#### Scenario: Service is destroyed
- **WHEN** periodic readiness refresh is active
- **AND** the device-link service is destroyed
- **THEN** the system cancels periodic readiness refresh work

### Requirement: Periodic readiness refresh preserves manual fallback behavior
The system SHALL keep the existing manual `Retry readiness checks` action available and SHALL NOT replace manual scan, pair, Bluetooth settings, or serial connection actions.

#### Scenario: Manual readiness retry remains available
- **WHEN** the Device Link screen is visible
- **THEN** the user can still tap `Retry readiness checks` to run readiness checks immediately

#### Scenario: Periodic refresh does not initiate setup actions
- **WHEN** periodic readiness refresh runs while the target device is not ready
- **THEN** the system SHALL NOT start Bluetooth discovery
- **AND** the system SHALL NOT initiate pairing
- **AND** the system SHALL NOT open Android Bluetooth settings
- **AND** the system SHALL NOT initiate a new manual serial connection
