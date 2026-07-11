## MODIFIED Requirements

### Requirement: Active channel mutual exclusivity
The system SHALL maintain a single valid `activeChannelId` selected from the persisted ordered channel catalogue. Activating a channel instance SHALL inherently make every other instance inactive. Catalogue mutation SHALL preserve or atomically repair this invariant.

#### Scenario: Switching active channel
- **WHEN** the user selects a different catalogue instance from any supported surface
- **THEN** the system SHALL set that instance ID as the active channel
- **AND** all other instances SHALL become inactive

#### Scenario: Unknown channel selected
- **WHEN** a selection request references an ID absent from the current catalogue
- **THEN** the system SHALL reject the request
- **AND** it SHALL preserve the existing valid active channel ID

### Requirement: Channel readiness state
The system SHALL obtain readiness from the runtime entry associated with each channel instance. Readiness SHALL include valid enabled configuration plus live dependencies required by that instance's kind. Readiness evaluation SHALL be instance-specific even when multiple instances share a kind.

For a Keyboard instance, readiness SHALL be true only when its host profile is configured and the Sleepwalker BLE bridge is connected. For a Journal instance, readiness SHALL be true only when its output directory is valid and at least one save option is enabled. A Debug instance SHALL additionally require the resources needed by its configured debug mode.

#### Scenario: Ready instance
- **WHEN** an enabled channel instance has valid configuration and every live dependency required by its kind
- **THEN** its runtime readiness SHALL be true

#### Scenario: Instance dependency unavailable
- **WHEN** a channel instance lacks valid configuration or a required live dependency becomes unavailable
- **THEN** its runtime readiness SHALL be false
- **AND** readiness of other instances SHALL be evaluated independently

#### Scenario: Inactive Debug instance retains dependency readiness
- **WHEN** an enabled Debug instance has valid configuration and all dependencies for its configured mode but is not active
- **THEN** its runtime readiness SHALL remain true
- **AND** shared-controller activation state SHALL NOT be treated as dependency availability

#### Scenario: Keyboard bridge disconnected
- **WHEN** a Keyboard instance has a host profile but the Sleepwalker BLE bridge is disconnected
- **THEN** that Keyboard runtime SHALL be not ready

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active catalogue instance's runtime readiness before dispatching a PTT capture, regardless of which input mode or actuator initiated PTT. Route resolution SHALL remain based on active `InputMode`, not `PttSource`. Ready input SHALL be prepared through the ID-keyed runtime registry rather than fixed built-in ID branches.

#### Scenario: PTT on a ready active instance in Work mode
- **WHEN** PTT is pressed while `Work` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Work audio route
- **AND** request a committed input target from the runtime associated with the active instance ID

#### Scenario: PTT on a ready active instance in On-the-road mode
- **WHEN** PTT is pressed while `OnTheRoad` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Telecom audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: PTT on a ready active instance in On-a-pinch mode
- **WHEN** PTT is pressed while `OnAPinch` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the local audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: Phone PTT selects an instance
- **WHEN** the user starts phone PTT from a ready functional channel card
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that card's instance ID as active
- **AND** route capture through the matching runtime entry

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** RSM PTT is pressed while Work mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** route capture to the active runtime instance

#### Scenario: Android Auto PTT auto-transitions to On-the-road mode
- **WHEN** Android Auto PTT is initiated while On-the-road mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** route capture to the active runtime instance

#### Scenario: Runtime refuses preparation
- **WHEN** the active runtime becomes unavailable or refuses input after route gating but before capture starts
- **THEN** the system SHALL NOT start capture
- **AND** host-owned error feedback and route cleanup SHALL follow the existing audio session policy

## REMOVED Requirements

### Requirement: Keyboard channel ordering
**Reason**: Channel order is user-controlled catalogue data rather than a compile-time property of `KeyboardChannel`.

**Migration**: The initial migrated catalogue places Journal, Debug, and Keyboard in their current stable order; subsequent order changes use catalogue move operations.

### Requirement: Keyboard channel in channel repository
**Reason**: Persistence is generalized to ordered instance definitions and no longer exposes one load/save method per concrete channel type.

**Migration**: Existing Keyboard preferences seed the initial Keyboard definition, after which configuration is loaded and saved by instance ID through the catalogue repository.
