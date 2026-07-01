## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain at most one active channel instance at any time, identified by a unique channel instance ID. The active channel instance is the intended destination for PTT audio captures, subject to readiness evaluation. When the configured channel list is non-empty, the system SHALL maintain exactly one active channel instance.

#### Scenario: One channel instance is active
- **WHEN** a channel instance is selected as active
- **THEN** PTT captures SHALL be evaluated against that channel instance's readiness state

#### Scenario: Empty channel list has no active channel
- **WHEN** the configured channel list is empty
- **THEN** the system SHALL have no active channel instance
- **AND** PTT captures SHALL not dispatch to a channel controller

### Requirement: Channel activation mutual exclusion
The system SHALL ensure that activating a channel instance inherently establishes it as the sole active channel instance.

#### Scenario: Channel instance activated while another is active
- **WHEN** Channel Instance A is active and the user activates Channel Instance B
- **THEN** the system SHALL set Channel Instance B as the active channel
- **AND** PTT captures SHALL be routed to Channel Instance B, provided it is ready

### Requirement: Channel configuration persistence
The system SHALL persist the ordered channel instance list, active channel instance ID, and type-specific channel instance configuration across app restarts. Configuration changes SHALL take effect immediately without requiring a service restart.

#### Scenario: App restarted after channel configuration
- **WHEN** the user configures the channel list or a channel instance and the app is killed and restarted
- **THEN** the channel list, channel ordering, active channel instance, and instance configuration SHALL be restored to the previously saved state

#### Scenario: Configuration changed at runtime
- **WHEN** the user changes channel list configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT capture without restarting the service

## ADDED Requirements

### Requirement: Channel type registry
The system SHALL resolve channel behavior through built-in channel types. Each channel instance SHALL reference one channel type ID, and that channel type SHALL define readiness evaluation, default configuration, configuration surface behavior, and PTT dispatch behavior for instances of that type.

#### Scenario: Channel instance behavior is resolved by type
- **WHEN** a PTT capture targets a channel instance
- **THEN** the system SHALL resolve the instance's channel type
- **AND** dispatch the capture using that type's behavior and the instance's type-specific configuration

#### Scenario: Unknown channel type is loaded
- **WHEN** persisted configuration contains a channel instance whose channel type ID is not available in the app
- **THEN** the system SHALL keep the instance in the ordered list as not ready
- **AND** PTT routing SHALL not dispatch captures to that instance
