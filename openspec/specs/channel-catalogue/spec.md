## Purpose

Defines the persisted ordered channel catalogue, its active selection, and the mutations that maintain its validity.

## Requirements

### Requirement: Channel catalogue is the authoritative ordered source
The system SHALL maintain one persisted, nonempty, ordered catalogue of channel definitions and exactly one active channel instance whose ID exists in that catalogue. Each definition SHALL contain a stable opaque instance ID, display name, supported channel kind, enabled state, configuration schema version, and kind-specific configuration. Instance IDs SHALL NOT change when a channel is renamed or reordered and SHALL NOT be derived from display name or list position.

#### Scenario: Catalogue loads valid definitions
- **WHEN** the application loads a valid persisted catalogue
- **THEN** it SHALL publish the definitions in persisted list order
- **AND** it SHALL publish the persisted active instance ID

#### Scenario: Multiple instances share a kind
- **WHEN** two channel definitions use the same supported built-in kind
- **THEN** each definition SHALL retain an independent instance ID and configuration
- **AND** both SHALL appear independently in the ordered catalogue

#### Scenario: Same-kind configuration update is isolated
- **WHEN** one of multiple same-kind instances receives a configuration update addressed by its instance ID
- **THEN** only that definition SHALL change
- **AND** every sibling definition, catalogue order, and active ID SHALL remain unchanged

#### Scenario: Invalid catalogue is rejected
- **WHEN** a persisted catalogue has an unsupported schema version, duplicate or blank IDs, unsupported kind, invalid configuration, no definitions, or an active ID outside the definition list
- **THEN** the system SHALL NOT publish the invalid catalogue as runtime state
- **AND** it SHALL surface an actionable load failure rather than silently constructing partial defaults

### Requirement: Catalogue mutations commit atomically
The system SHALL serialize catalogue mutations and atomically persist the complete ordered definition list and active instance ID before publishing the resulting snapshot. A failed commit SHALL leave the previously persisted and published snapshot unchanged.

#### Scenario: Successful mutation
- **WHEN** a valid add, update, remove, reorder, or selection mutation is requested
- **THEN** the system SHALL persist one complete valid next snapshot
- **AND** it SHALL publish that same snapshot after persistence succeeds

#### Scenario: Persistence fails during mutation
- **WHEN** persistence fails before the next catalogue snapshot is committed
- **THEN** the system SHALL retain the preceding definitions, order, and active ID
- **AND** consumers SHALL NOT observe a partially applied mutation

### Requirement: Supported built-in instances can be added and updated
The system SHALL allow the user to create an instance of any supported built-in channel kind, assign a display name, and edit that instance's valid kind-specific configuration. New instances SHALL receive unique opaque IDs.

#### Scenario: Add a built-in instance
- **WHEN** the user creates a channel using a supported built-in kind and valid configuration
- **THEN** the system SHALL append a new definition to the catalogue
- **AND** the new definition SHALL have an ID distinct from every existing definition

#### Scenario: Removed seeded kind can be recreated
- **WHEN** the migrated seed instance of a supported built-in kind is removed
- **THEN** the user SHALL remain able to create another instance of that kind with a new opaque ID
- **AND** existing instances of that kind SHALL NOT prevent additional instances

#### Scenario: Rename a channel
- **WHEN** the user changes a channel instance's display name
- **THEN** the system SHALL persist the new display name
- **AND** the instance ID, configuration, active state, and list position SHALL remain unchanged

#### Scenario: Reject invalid configuration
- **WHEN** an add or update request contains configuration invalid for its declared kind or schema version
- **THEN** the system SHALL reject the mutation
- **AND** the persisted catalogue SHALL remain unchanged

### Requirement: Channel order can be changed
The system SHALL allow a channel instance to be moved to another valid position. List order in the committed catalogue SHALL be the sole ordering authority for phone, hardware, and Android Auto channel traversal.

#### Scenario: Move a channel
- **WHEN** the user moves a channel from one valid list position to another
- **THEN** the system SHALL persist the resulting ordered definition list
- **AND** the active channel ID SHALL remain unchanged

#### Scenario: Reorder survives restart
- **WHEN** the application restarts after a successful reorder
- **THEN** it SHALL restore the same channel order

#### Scenario: Invalid move is rejected
- **WHEN** a move references an unknown channel ID or an out-of-range destination
- **THEN** the system SHALL reject the mutation
- **AND** the existing order SHALL remain unchanged

### Requirement: Channel removal preserves active-selection validity
The system SHALL allow removal of a channel when at least one other channel remains. Removing the active channel SHALL select the channel that followed it in the pre-removal order, or the preceding channel when no following channel exists. The final remaining channel SHALL NOT be removable.

#### Scenario: Remove an inactive channel
- **WHEN** the user removes an inactive channel while at least two definitions exist
- **THEN** the system SHALL remove that definition
- **AND** it SHALL preserve the current active channel ID

#### Scenario: Remove active channel with a successor
- **WHEN** the active channel is removed and another channel followed it in the pre-removal order
- **THEN** the system SHALL make that following channel active in the same committed mutation

#### Scenario: Remove final active channel in the order
- **WHEN** the active channel is the last ordered definition and is removed while another definition precedes it
- **THEN** the system SHALL make the preceding channel active in the same committed mutation

#### Scenario: Attempt to remove the final channel
- **WHEN** the catalogue contains one definition and its removal is requested
- **THEN** the system SHALL reject the removal
- **AND** that definition SHALL remain active

### Requirement: Legacy channel settings migrate once
When no catalogue document exists, the system SHALL construct and atomically persist an initial catalogue from existing Journal, Debug, Keyboard, and active-channel preferences. The initial order SHALL preserve the current built-in order, the seeded definitions SHALL retain the existing built-in IDs, and existing valid configuration and active selection SHALL be preserved. After a catalogue is committed, it SHALL be the only channel-definition source of truth.

#### Scenario: First start with legacy settings
- **WHEN** no catalogue exists and valid legacy channel preferences exist
- **THEN** the system SHALL create Journal, Debug, and Keyboard definitions with their prior configuration
- **AND** it SHALL preserve the prior valid active built-in ID
- **AND** it SHALL persist the catalogue before publishing it

#### Scenario: Migration commit fails
- **WHEN** creating the initial catalogue from legacy preferences fails to commit
- **THEN** the system SHALL retain the legacy preferences
- **AND** it SHALL retry migration on a subsequent start rather than marking migration complete

#### Scenario: Catalogue already exists
- **WHEN** a valid catalogue document exists
- **THEN** the system SHALL load that catalogue
- **AND** it SHALL NOT merge or overwrite it from legacy preference keys
