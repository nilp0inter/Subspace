## MODIFIED Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel instance at any time, identified by a stable ID present in the persisted ordered channel catalogue. The active channel instance is the intended destination for PTT audio captures, subject to its runtime readiness evaluation. The migrated default catalogue SHALL include Journal, Debug, and Keyboard instances, while the runtime SHALL support catalogue additions, removals, and multiple instances of a supported kind.

#### Scenario: One channel instance is active
- **WHEN** a channel instance is selected as active
- **THEN** PTT captures SHALL be evaluated against that instance's runtime readiness state
- **AND** every other catalogue instance SHALL be inactive

#### Scenario: Multiple instances share a kind
- **WHEN** the catalogue contains two instances of the same supported channel kind
- **THEN** either instance SHALL be independently selectable by its stable instance ID
- **AND** selection SHALL NOT be inferred from channel kind

### Requirement: Channel configuration persistence
The system SHALL persist the ordered channel definitions and kind-specific configuration across app restarts, including every Journal, Debug, and Keyboard instance. Configuration changes SHALL be addressed by stable instance ID and take effect for subsequent PTT preparation without requiring a service restart. A configuration change SHALL NOT alter the target already committed to an active PTT session.

#### Scenario: App restarted after instance configuration
- **WHEN** the user configures a channel instance and the app is killed and restarted
- **THEN** that instance's ID, kind, name, position, enabled state, and kind-specific configuration SHALL be restored

#### Scenario: Built-in configuration changed at runtime
- **WHEN** the user changes a built-in channel instance's valid configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT preparation for that instance
- **AND** the current committed target, if any, SHALL retain its accepted configuration snapshot

### Requirement: Channels do not silently drop committed input
A channel target that has accepted input SHALL either consume the input events for that session or report failure or cancellation through the channel input contract. It SHALL NOT silently ignore a committed input start because selection, order, configuration, catalogue membership, or mutable runtime state changed after acceptance.

#### Scenario: Committed target snapshots required definition state
- **WHEN** a runtime accepts a PTT input request
- **THEN** the target SHALL bind the instance ID and all configuration and domain resources required for that session before the ready beep is played
- **AND** subsequent catalogue mutations SHALL NOT redirect the committed session

#### Scenario: Journal input target commits paths before ready beep
- **WHEN** a Journal instance accepts a PTT input request
- **THEN** Journal SHALL have the base directory and entry paths needed to process the input before the ready beep is played
- **AND** the committed session SHALL use those paths for live-frame writing and terminal metadata

#### Scenario: Debug input target snapshots mode before ready beep
- **WHEN** a Debug instance accepts a PTT input request
- **THEN** Debug SHALL bind the current debug mode and controller target for that session before the ready beep is played
- **AND** release for that session SHALL use the same committed debug target

#### Scenario: Instance removed after commitment
- **WHEN** a channel instance is removed after its target has accepted an input request
- **THEN** the target SHALL continue to receive the terminal event for that session
- **AND** the removed instance SHALL refuse subsequent input preparation
